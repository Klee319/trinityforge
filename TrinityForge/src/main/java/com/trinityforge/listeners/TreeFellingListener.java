package com.trinityforge.listeners;

import com.trinityforge.active.ActiveSkillCooldownKeys;
import com.trinityforge.active.CooldownManager;
import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.active.SemiActiveCooldown;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.WoodcuttingGimmickConfig;
import com.trinityforge.gathering.ChainBreakExpGrant;
import com.trinityforge.gathering.ChainBreakSupport;
import com.trinityforge.gathering.GatheringToolMatcher;
import com.trinityforge.mining.VeinMiningAlgorithm;
import com.trinityforge.mining.VeinMiningAlgorithm.BlockPos;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DropTableConfig;
import com.trinityforge.stats.DropTablePolicy;
import com.trinityforge.woodcutting.LeafDecayPlanner;
import com.trinityforge.woodcutting.TreeScan;
import com.trinityforge.woodcutting.WoodcuttingMaterials;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * 伐採スキルツリーのflag系dedicated-effect consumer群 ({@code stats/woodcutting-gimmick.yml} でチューニング):
 *
 * <ul>
 *   <li>{@code tree-fell} (SCALE, 2026-07-25 gather-rework-active-framework §1/§6 Q1 — 旧
 *       {@code small-tree-fell}/{@code large-tree-fell} を統合): 斧で原木を破壊した時、連結する同種原木を
 *       一括伐採({@link VeinMiningAlgorithm}を流用したflood-fill)。プレイヤーの解放済み最高tier
 *       ({@code DedicatedEffectsConfig#valueMax}) を {@code stats/woodcutting-gimmick.yml
 *       tree-fell.tiers} で解決した上限本数を1回だけ適用する。プレイヤー毎クールダウンあり。</li>
 *   <li>{@code woodcutting} drop-table (2026-07-23 stat-gate-overhaul §4): 葉(LEAVES系)破壊時、または
 *       一括伐採のトリガーになった原木破壊時(連鎖破壊分は対象外)に、各カテゴリ({@code stats/
 *       woodcutting-gimmick.yml drop-tables.categories})が独立にtrigger判定+重み付き抽選
 *       ({@link DropTablePolicy}) を行う — 旧 {@code apple-drop}/{@code golden-apple-drop}/
 *       {@code crystal-apple-drop} 個別consumerを置換。</li>
 * </ul>
 *
 * <p>{@link com.trinityforge.listeners.VeinMiningListener}と同じ「1リスナーに複数consumerをまとめる」様式:
 * すべて同じ「原木 or 葉の破壊」トリガーを共有するため。
 */
public final class TreeFellingListener implements Listener, SemiActiveCooldown {

    /**
     * 診断ログ。{@code Bukkit.getLogger()} を使わないのは、サーバ未起動のユニットテストから呼ばれると
     * NPE になるため(ブロック破壊ハンドラから例外を投げてはいけない)。
     */
    private static final Logger LOG = Logger.getLogger(TreeFellingListener.class.getName());

    private static final String EFFECT_TREE_FELL = "tree-fell";
    private static final String PROF_WOODCUTTING = "woodcutting";
    /** CooldownManager#tryConsume/remainingMillisのskillIdキー(tree-fellはActiveSkillRegistry非登録)。 */
    private static final String COOLDOWN_SKILL_ID = "tree-fell";
    /**
     * 一括伐採専用CT短縮ステータスキー({@code tree-fell-cooldown-reduction})。ActiveSkillCooldownKeys の
     * 命名規約(<id>-cooldown-reduction)を流用するが、tree-fellはsneak+クリック発動のActiveSkillではなく
     * パッシブなブロック破壊ギミックなのでActiveSkillRegistryには登録しない
     * (ActiveSkillCooldownKeys.verifyRegistered の対象外)。
     */
    private static final String COOLDOWN_REDUCTION_KEY = ActiveSkillCooldownKeys.forSkill("tree-fell");

    private final DedicatedEffectsConfig dedicatedEffects;
    private final WoodcuttingGimmickConfig gimmickConfig;
    private final CrossPluginItemResolver itemResolver;
    private final PlacedBlockTracker placedBlockTracker;
    private final FeedbackLayer feedback;
    private final CooldownManager cooldowns;
    private final PlayerStatAggregator aggregator;
    /** 連鎖伐採分の採取EXP付与口(2026-07-28)。null 可 — 旧7引数コンストラクタ経由では EXP のみ入らない。 */
    private final ChainBreakExpGrant chainBreakExp;
    /**
     * 葉の段階破壊(2026-07-31 N2)のスケジューラ所有者。コンストラクタでは受け取らず
     * {@link #schedulerPlugin()} が遅延解決する(null なら同tickで全部壊す縮退)。ブロック破壊は
     * メインスレッドのみなので排他は不要だが、参照の可視性のため volatile。
     */
    private volatile Plugin plugin;
    private volatile boolean pluginResolved;

    /** @deprecated 連鎖伐採分のEXPが入らない旧配線。{@link #TreeFellingListener(DedicatedEffectsConfig,
     * WoodcuttingGimmickConfig, CrossPluginItemResolver, PlacedBlockTracker, FeedbackLayer,
     * CooldownManager, PlayerStatAggregator, ChainBreakExpGrant)} を使うこと。 */
    @Deprecated
    public TreeFellingListener(DedicatedEffectsConfig dedicatedEffects, WoodcuttingGimmickConfig gimmickConfig,
                                CrossPluginItemResolver itemResolver, PlacedBlockTracker placedBlockTracker,
                                FeedbackLayer feedback, CooldownManager cooldowns, PlayerStatAggregator aggregator) {
        this(dedicatedEffects, gimmickConfig, itemResolver, placedBlockTracker, feedback, cooldowns,
                aggregator, null);
    }

    public TreeFellingListener(DedicatedEffectsConfig dedicatedEffects, WoodcuttingGimmickConfig gimmickConfig,
                                CrossPluginItemResolver itemResolver, PlacedBlockTracker placedBlockTracker,
                                FeedbackLayer feedback, CooldownManager cooldowns, PlayerStatAggregator aggregator,
                                ChainBreakExpGrant chainBreakExp) {
        this.chainBreakExp = chainBreakExp;
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
        this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");
        this.placedBlockTracker = Objects.requireNonNull(placedBlockTracker, "placedBlockTracker");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.cooldowns = Objects.requireNonNull(cooldowns, "cooldowns");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    /**
     * Tree-felling chain-break only (existing mechanic, priority unchanged — 2026-07-23 verifier指摘⑧:
     * the drop-table roll moved out to {@link #onBlockBreakDropTables} at {@link EventPriority#MONITOR}).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (SpellBreakGuard.isSpellBreak(event.getBlock())) {
            // 魔法(Ars)破壊の合成イベントには一括伐採/ドロップテーブルを一切与えない。
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material type = block.getType();

        if (WoodcuttingMaterials.isLog(type)) {
            handleTreeFelling(player, block, type);
        }
    }

    /**
     * Drop-table roll only (2026-07-23 verifier指摘⑧): {@link EventPriority#MONITOR} +
     * {@code ignoreCancelled=true}, and excludes player-placed log/leaves blocks
     * ({@link PlacedBlockTracker#isPlaced}) from ever contributing to the drop table (原木設置→破壊の
     * リンゴ量産等の無限ループ対策)。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreakDropTables(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (SpellBreakGuard.isSpellBreak(block)) {
            // 魔法(Ars)破壊の合成イベントには一括伐採/ドロップテーブルを一切与えない。
            return;
        }
        Material type = block.getType();
        // ⚠️ 2026-08-17 修正: ここは以前 isLog(type) も通していたため、原木を割るだけで
        // リンゴ系のドロップテーブルが回っていた(実測: 原木破壊で金リンゴ)。
        // リンゴは葉から採るもので、直下の javadoc/コメントも元から「葉のみ」と書いてある。
        if (!WoodcuttingMaterials.isLeaves(type) || placedBlockTracker.isPlaced(block)) {
            return;
        }
        // LeavesDecayEvent(自然消滅)はブロック破壊の原因プレイヤーを特定できないため対象外
        // (要調整: 近傍プレイヤー推定などを実装すれば拡張可能)。プレイヤーが特定できる
        // BlockBreakEventの葉のみを対象にする。
        rollDropTables(event.getPlayer(), block);
    }

    /**
     * Chain-fells up to the resolved tier's max-extra-logs connected same-type log blocks, gated on
     * holding an axe and on a per-player cooldown. The originally-broken block ({@code origin}) is left
     * to the event itself.
     *
     * <p>2026-07-31 N1: 走査は {@link TreeScan} が「木」に錨づける — {@link TreeScan#trunkBase} で幹の
     * 最下段まで降りてから木全体を把握し、伐る本数を {@link TreeScan#BOTTOM_UP} の決定順で選ぶので、
     * <b>叩いた高さ/面に関係なく同じ集合が伐れる</b>。CT の消費も走査より後ろへ移した。
     * 葉は {@link #planLeaves} が木全体を種にして計画し {@link #breakLeavesStaged} が掃除する
     * (2026-07-30 で導入、2026-07-31 N2 で拡張)。
     *
     * <p><b>2026-07-31 G1 レビュー指摘1: プレイヤーが設置した丸太は「木」ではない。</b>
     * 「常に根元から伐る」は自然樹では正しいが、丸太で組んだ壁/家の上端を叩くと、面隣接で繋がる
     * 塊の <em>y 最小の行</em>(=クリック位置から水平に十数ブロック離れた視界外)が消えるという新しい
     * 事故経路になっていた。そこで走査の述語に {@link PlacedBlockTracker#isPlaced} を通し、
     * <b>設置された丸太を木から除外する</b>({@link TreeScan#trunkBase} の降下も
     * {@link TreeScan#wholeTree} の展開もそこで止まる)。自然樹の丸太は設置扱いにならないので無害。
     * 材質一致を先に判定してから {@code isPlaced} を呼ぶので、PDC の線形走査が走るのは実際に丸太
     * だった位置(最大 {@code scan-limit} 本)だけに収まる。
     *
     * <p><b>2026-07-31 G1 round2 指摘2: 設置記録に依存しない第二の歯止め。</b>
     * 上の緩和策は {@code PlacedBlockTracker} の記録が前提で、記録は {@code BlockPlaceEvent} 1本しか
     * 見ていない。したがって <b>WorldEdit / schematic / {@code /setblock} / 構造物生成で置かれた丸太</b>、
     * <b>ピストンで座標が変わった丸太</b>、<b>チャンクあたり上限の FIFO で追い出されたマーク</b> は
     * 依然として「自然木」として走査される — <em>この残余は緩和策のまま残す</em>と決めた
     * ({@code tmp/decisions.md})。代わりに {@link TreeScan#withinDistance} を走査の述語へ AND し、
     * <b>叩いた位置から水平 {@code max-horizontal-distance} / 垂直 {@code max-vertical-distance} を
     * 超える丸太は最初から見ない</b>。これで記録に乗らない建築でも「クリック位置から十数ブロック離れた
     * 視界外の行が消える」という最悪ケースは起きない(既定 8/32 は実在するバニラ樹木の寸法より大きいので
     * 自然樹の伐採は変わらない)。<b>残余</b>: 距離の内側にある建築部分は依然として伐れる。
     *
     * <p><b>2026-07-31 G1 round2 指摘3: 走査中の PDC 読みはチャンクごとに1回。</b>
     * {@link PlacedBlockTracker#isPlaced} は copy-on-read で毎回 {@code long[]} を丸ごと複製するので、
     * 走査述語から直接呼ぶと「マークが多いチャンクでの伐採1回」が数十MBの短命オブジェクトを
     * メインスレッドに積んでいた。{@link PlacedBlockTracker#newScanLookup()} で走査スコープの
     * ローカルキャッシュを作り、走査が終わったら捨てる(アロケーションが走査ブロック数ではなく
     * <b>触ったチャンク数</b>に比例する)。
     *
     * <p><b>2026-07-31 G1 レビュー指摘3/4: CT は「走査の前に覗き、仕事が確定してから取る」。</b>
     * CT 中は {@link CooldownManager#remainingMillis} で早期 return して走査そのものを行わない
     * (旧: CT 中の空振り破壊でも毎回フルスキャンの代金を払っていた)。逆に消費は原木と葉の両方の
     * 計画が確定した後で、<b>連鎖0本かつ葉0枚のときは消費しない</b>
     * (旧: {@code break-leaves: true} の出荷既定では「葉の計画が0枚でも仕事あり」と見なして
     * CT を取っていた)。
     *
     * <p>2026-07-25 PRG-07: 私製{@code ConcurrentHashMap}のCT管理を、汎用アクティブスキル基盤の
     * {@link CooldownManager}(共有インスタンス、{@code ActivationDispatcher}と同じ)へ統合。CT短縮は
     * woodcutting.yml B-1/B-2/B-3 が誤って読んでいたアイテムCT短縮キー({@code cooldown-reduction})ではなく、
     * 一括伐採専用の{@link #COOLDOWN_REDUCTION_KEY}({@code tree-fell-cooldown-reduction})を読む
     * (数値0.3は変更していない、キーのみ移行)。基準CTは従来通り{@code gimmickConfig.treeFellCooldownTicks()}。
     */
    private void handleTreeFelling(Player player, Block origin, Material type) {
        OptionalDouble tier = eligibleTier(player);
        if (tier.isEmpty()) {
            return;
        }
        // 2026-07-31 G1 指摘3: CT 中は走査そのものを行わない。remainingMillis で覗くだけなので
        // tryConsume の「範囲が確定してから取る」性質(指摘4)は壊さない。
        long cooldownMillis = cooldownMillis(player);
        long now = System.currentTimeMillis();
        if (cooldowns.remainingMillis(player.getUniqueId(), COOLDOWN_SKILL_ID, cooldownMillis, now) > 0L) {
            return;
        }

        int tierValue = (int) tier.getAsDouble();
        int maxExtra = gimmickConfig.treeFellMaxExtraLogs(tierValue);

        World world = origin.getWorld();
        BlockPos originPos = new BlockPos(origin.getX(), origin.getY(), origin.getZ());
        ItemStack tool = player.getInventory().getItemInMainHand();

        // 2026-07-31 N1: 走査の錨は「叩いたブロック」ではなく木そのもの。幹の最下段まで降りてから
        // 木全体を把握し、伐る本数を決定的な順序(y→x→z)で選ぶ。これで上面/側面/底面のどこを叩いても
        // 同じ集合が伐れる(TreeScan の javadoc 参照)。走査上限は伐採上限とは別枠 —
        // 上限で伐り残した幹も葉の走査の種に必要なため。
        // 2026-07-31 G1 指摘1: 設置された丸太は木ではないので走査をそこで止める(丸太建築の事故防止)。
        // 2026-07-31 G1 round2 指摘2: 設置記録は BlockPlaceEvent 経由の丸太しか覆えない(WorldEdit /
        // schematic / ピストン移動 / チャンク上限FIFOで落ちたマークは「自然木」に見える)。そこで
        // 「叩いた位置からの距離」という記録に依存しない第二の歯止めを AND する。
        // 述語の評価順は 距離 → 材質 → 設置記録 の順で固定すること:
        //   距離判定はワールドを読まないので範囲外で getBlockAt が走らない。
        //   材質判定を先に置くので PDC 参照は実際に丸太だった位置(最大 scan-limit 本)だけに収まる。
        // 2026-07-31 G1 round2 指摘3: その PDC 参照も ScanLookup 経由にしてチャンクごとに1回だけ読む
        // (isPlaced は copy-on-read で毎回 long[] を丸ごと複製するため、走査で直接呼ぶと
        //  斧1振りで数十MBの短命オブジェクトがメインスレッドに乗っていた)。走査が終われば捨てる。
        Predicate<BlockPos> withinReach = TreeScan.withinDistance(originPos,
                gimmickConfig.treeFellMaxHorizontalDistance(),
                gimmickConfig.treeFellMaxVerticalDistance());
        PlacedBlockTracker.ScanLookup placedLookup = placedBlockTracker.newScanLookup();
        Predicate<BlockPos> sameLog = pos -> {
            if (!withinReach.test(pos)) {
                return false;
            }
            Block candidate = world.getBlockAt(pos.x(), pos.y(), pos.z());
            return candidate.getType() == type && !placedLookup.isPlaced(candidate);
        };
        BlockPos base = TreeScan.trunkBase(originPos, sameLog);
        List<BlockPos> tree = TreeScan.wholeTree(base, sameLog, gimmickConfig.treeFellScanLimit());
        List<BlockPos> extra = TreeScan.selectFelled(tree, originPos, maxExtra);

        // 2026-07-31 G1 指摘4: 葉の計画も CT を取る前に確定させる。出荷既定 break-leaves: true では
        // 旧条件(extra.isEmpty() && !breakLeaves())が成立せず、単独の丸太を壊すだけで10秒のCTを
        // 取られていた(葉0枚で終わっても返す機構は無い)。
        List<BlockPos> doomedLeaves = planLeaves(world, originPos, tree, extra, tierValue);
        if (extra.isEmpty() && doomedLeaves.isEmpty()) {
            return;
        }
        if (!cooldowns.tryConsume(player.getUniqueId(), COOLDOWN_SKILL_ID, cooldownMillis, now)) {
            return;
        }

        // 2026-07-28: 連鎖分の採取EXPと道具耐久は ChainBreakSupport が担う(旧実装は breakNaturally
        // だけで、EXPも耐久も一切処理されていなかった)。
        int broken = ChainBreakSupport.breakChain(player, world, extra, type, tool, chainBreakExp);
        int leaves = breakLeavesStaged(player, world, doomedLeaves, tool);
        // 2026-07-25 §2 B-1: 発動フィードバック(控えめなactionbar)。2026-07-31 N2: 原木0本でも
        // 葉の掃除だけが走ることがある(1本木・幹の最後の1本)ので、その場合は葉だけを出す。
        if (broken > 0) {
            feedback.subtle(player, leaves > 0
                    ? "一括伐採 x" + broken + " (葉 x" + leaves + ")"
                    : "一括伐採 x" + broken);
        } else if (leaves > 0) {
            feedback.subtle(player, "葉の一括破壊 x" + leaves);
        }
    }

    /**
     * 伐り倒した木に繋がる葉のうち<b>壊すものを決める</b>(2026-07-30 実サーバ要望「一括伐採時に葉っぱも
     * 一括破壊されてほしい」／2026-07-31 N2「葉の自動破壊がバニラより大幅に速くなるように」)。
     *
     * <p><b>種は「木全体」</b>: 伐った幹は直後に AIR になるので元の1ブロックから flood-fill しても
     * 葉に辿り着けない。さらに<em>伐った丸太だけ</em>を種にすると、上限で伐り残した幹に付いた樹冠へ到達
     * できない(葉の BFS は葉しか辿らないため)。そこで {@link TreeScan#wholeTree} が把握した木全体
     * — 伐り残した幹も含む — を種にする。これが「側面から叩くと葉が0枚、真上から叩くと48枚」という
     * 階段状の起点依存を消す一点。
     *
     * <p><b>{@code leaves-decay-only}(既定 true)</b>: 上限を桁で上げたので、葉だけを辿る BFS を素のまま
     * 使うと隣の木の樹冠まで食う。{@link LeafDecayPlanner} が「バニラなら崩壊する葉」= 設置された葉
     * ({@code persistent})でなく、かつ残存原木から距離6以内で支えられていない葉 — だけに絞る。
     *
     * <p>道具の耐久は消費しない({@link ChainBreakSupport#breakChain} の {@code consumeDurability=false})
     * — 葉は硬度0.2でバニラなら耐久を減らすが、1回で数百枚が巻き込まれるため、そのまま取ると
     * 「一括伐採を解放した途端に斧が即壊れる」になる。採取EXPは原木と同じく設定次第
     * ({@code woodcutting_progression.yml} に葉の行が無ければ0 — 現状は0のまま据え置き)。
     *
     * <p><b>2026-07-31 G1 指摘4: 計画だけを行い、世界は書き換えない。</b> CT の消費を「原木も葉も
     * 仕事が0なら取らない」形にするため、破壊より前に計画を確定させる必要がある。そのため支持原木の
     * 述語からは <em>これから消えるブロック全部</em>(叩いた1本 + 連鎖対象)を除外する — 旧実装は
     * 「連鎖を壊した後に計画する」順序に依存して、既に AIR になっている位置が自動的に支持から
     * 外れることを当てにしていた。明示的に除外する方が順序に依存せず正しい。
     *
     * <p><b>残余</b>: {@code brokenLogs} の旧挙動フォールバック({@code leaves-max <= 0} のときの
     * {@code leaves-per-log × 本数})は、実際に壊せた本数ではなく<em>計画した本数</em>で計算する
     * (計画時点では壊していないため)。差が出るのは連鎖の途中で道具が壊れたときだけ。その場合
     * 「残った幹に支えられている葉」を壊してしまう可能性もあるが、過剰破壊側の誤差でしかなく、
     * 再計画すると走査コストが2倍になるので取らない。
     *
     * @param treeLogs 木全体(伐り残した幹も含む)。葉の走査の種。
     * @param felled   これから連鎖破壊する原木。支持原木から除外する。
     * @return 壊すことが確定した葉の座標(種に近い順)
     */
    private List<BlockPos> planLeaves(World world, BlockPos origin, List<BlockPos> treeLogs,
                                      List<BlockPos> felled, int tier) {
        if (!gimmickConfig.treeFellBreakLeaves() || treeLogs.isEmpty()) {
            return List.of();
        }
        int maxLeaves = gimmickConfig.treeFellMaxLeaves(tier, felled.size());
        if (maxLeaves <= 0) {
            return List.of();
        }
        Set<BlockPos> removed = new HashSet<>(felled);
        removed.add(origin);
        boolean decayOnly = gimmickConfig.treeFellLeavesDecayOnly();
        LeafDecayPlanner.Plan plan = LeafDecayPlanner.plan(
                treeLogs,
                pos -> isBreakableLeaf(world, pos, decayOnly),
                pos -> !removed.contains(pos)
                        && WoodcuttingMaterials.isLog(world.getBlockAt(pos.x(), pos.y(), pos.z()).getType()),
                maxLeaves,
                decayOnly);
        if (plan.budgetExhausted()) {
            // 2026-07-31 G1 指摘6a: 予算に当たった=樹冠の一部が計画に入っていない。正常な樹冠では
            // 起きないので、起きたら設定(leaves-max / scan-limit)か地形が異常だという合図。
            LOG.warning("[tree-fell] 葉の候補収集が予算(" + LeafDecayPlanner.PROBE_BUDGET_FACTOR
                    + " x leaves-max=" + maxLeaves + ")に達したため打ち切りました。"
                    + "葉が疎に散っているか leaves-max が小さすぎます(計画 " + plan.size() + "枚)");
        }
        return plan.doomed();
    }

    /**
     * 壊す候補になる葉か。{@code decayOnly} のとき、設置された葉({@code persistent=true}、装飾の葉壁)は
     * バニラでも崩壊しないので除外する。
     *
     * <p>{@code getBlockData()} が {@link Leaves} を返さない実装(テストダブル等)では persistent 情報が
     * 無いので「設置ではない」と見なす — 葉を壊す方向に倒すが、既存挙動と同じなので退行にはならない。
     */
    private static boolean isBreakableLeaf(World world, BlockPos pos, boolean decayOnly) {
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!WoodcuttingMaterials.isLeaves(block.getType())) {
            return false;
        }
        if (!decayOnly) {
            return true;
        }
        return !(block.getBlockData() instanceof Leaves leaves) || !leaves.isPersistent();
    }

    /**
     * {@code leaves-per-tick} 枚ずつ壊し、残りを次tickへ回す(2026-07-31 N2)。上限が最大1024枚なので、
     * 全部を同tickで壊すとチャンク更新とライティング更新が集中する。
     *
     * <p>スケジューラが使えない場合({@link #schedulerPlugin()} が解決できない／{@code leaves-per-tick}
     * が0以下)は同tickで全部壊す。分割しても順序は {@code doomed} のまま(=種に近い順)なので、
     * どこまで壊れたかが決定的。
     *
     * <p><b>残余(2026-07-31 G1 指摘11)</b>: 返り値は<b>楽観値</b>。段階破壊に回した分は
     * {@link #breakLeafBatch} が未ロードチャンクの座標を捨て、{@link #scheduleLeafBatch} は
     * プレイヤーがログアウトしたら残りを放棄するが、その減少分は返り値(=actionbar の枚数)に
     * 反映されない。表示の誤差だけで、破壊もEXPも実際に処理した分しか動かない。
     *
     * @return 壊すことが確定した枚数(次tick以降に回した分も含む)
     */
    private int breakLeavesStaged(Player player, World world, List<BlockPos> doomed, ItemStack tool) {
        if (doomed.isEmpty()) {
            return 0;
        }
        int perTick = gimmickConfig.treeFellLeavesPerTick();
        if (perTick <= 0 || doomed.size() <= perTick) {
            return breakLeafBatch(player, world, doomed, tool, false);
        }
        Plugin plugin = schedulerPlugin();
        if (plugin == null) {
            // 2026-07-31 G1 指摘7: 段階破壊したいのにスケジューラ所有者が取れなかった。無言で
            // 「同tickに最大1024枚破壊」(yml 自身が非推奨と書く挙動)へ落ちるのが観測できないのが
            // 問題なので、1回だけ WARNING を出す(pluginResolved があるので二度目は来ない)。
            LOG.warning("[tree-fell] 葉の段階破壊に使うプラグインを解決できなかったため、"
                    + doomed.size() + "枚を同tickで壊します(leaves-per-tick=" + perTick + " は無効)。"
                    + "TrinityForge.java で TreeFellingListener#setPlugin(this) を呼べば確実になります");
            return breakLeafBatch(player, world, doomed, tool, false);
        }
        breakLeafBatch(player, world, doomed.subList(0, perTick), tool, false);
        scheduleLeafBatch(plugin, player, world, List.copyOf(doomed.subList(perTick, doomed.size())),
                tool, perTick);
        return doomed.size();
    }

    private void scheduleLeafBatch(Plugin plugin, Player player, World world, List<BlockPos> remaining,
                                   ItemStack tool, int perTick) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                // ログアウトしたら残りは諦める(EXP付与も耐久判定もオフラインの Player を触るため)。
                return;
            }
            int end = Math.min(perTick, remaining.size());
            breakLeafBatch(player, world, remaining.subList(0, end), tool, true);
            if (end < remaining.size()) {
                scheduleLeafBatch(plugin, player, world, List.copyOf(remaining.subList(end, remaining.size())),
                        tool, perTick);
            }
        }, 1L);
    }

    /**
     * 1バッチ分の葉を壊す。
     *
     * @param requireLoadedChunk 次tick以降へ回したバッチでは {@code true}。チャンクが未ロードの位置は
     *                           <b>強制ロードせず捨てる</b> — プレイヤーが離れた後にチャンクの読み込みを
     *                           誘発しないため。同tickで壊す最初のバッチでは走査でその座標を読んだ直後
     *                           なので判定不要({@code false})。
     */
    private int breakLeafBatch(Player player, World world, List<BlockPos> batch, ItemStack tool,
                               boolean requireLoadedChunk) {
        List<BlockPos> targets = batch;
        if (requireLoadedChunk) {
            List<BlockPos> loaded = new java.util.ArrayList<>(batch.size());
            for (BlockPos pos : batch) {
                if (world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                    loaded.add(pos);
                }
            }
            targets = loaded;
        }
        return ChainBreakSupport.breakChain(player, world, targets, WoodcuttingMaterials::isLeaves,
                tool, chainBreakExp, false);
    }

    /**
     * 葉の段階破壊に使うスケジューラ所有プラグイン。
     *
     * <p>このリスナーはコンストラクタで {@link Plugin} を受け取っていない(配線は別レーン所有の
     * {@code TrinityForge.java} にあるため触れない)。そこで
     * <ol>
     *   <li>{@link #setPlugin(Plugin)} による明示注入(あれば最優先)</li>
     *   <li>クラスを提供しているプラグインからの遅延解決</li>
     * </ol>
     * の順で解決し、どちらも取れなければ {@code null} を返す(=同tickで全部壊す安全側の縮退)。
     * ブロック破壊ハンドラから例外を投げてはいけないので解決失敗は握り潰す。
     *
     * <p>2026-07-31 G1 指摘7: 縮退したことは{@link #breakLeavesStaged} が WARNING で1回だけ知らせる
     * (無言で非推奨挙動へ落ちるのが問題だった)。本番の明示配線({@code TrinityForge.java} の
     * {@code setPlugin(this)} 1行)は別レーン所有なのでここでは入れていない。
     */
    private Plugin schedulerPlugin() {
        if (pluginResolved) {
            return plugin;
        }
        pluginResolved = true;
        try {
            plugin = JavaPlugin.getProvidingPlugin(TreeFellingListener.class);
        } catch (RuntimeException ex) {
            // テスト環境などプラグインクラスローダ外から呼ばれた場合。段階破壊を諦めるだけ。
            plugin = null;
        }
        return plugin;
    }

    /**
     * 葉の段階破壊に使うプラグインを明示注入する(任意)。呼ばなくても
     * {@link #schedulerPlugin()} が遅延解決するが、明示配線した方が確実。
     */
    public void setPlugin(Plugin plugin) {
        this.plugin = plugin;
        this.pluginResolved = true;
    }

    @Override
    public String id() {
        return COOLDOWN_SKILL_ID;
    }

    @Override
    public String displayName() {
        return "一括伐採";
    }

    @Override
    public boolean isEligible(Player player) {
        return eligibleTier(player).isPresent();
    }

    @Override
    public long cooldownMillis(Player player) {
        long baseCooldownMillis = gimmickConfig.treeFellCooldownTicks() * 50L;
        double reduction = aggregator.aggregate(player).totalOf(COOLDOWN_REDUCTION_KEY);
        return CooldownManager.applyReduction(baseCooldownMillis, reduction);
    }

    private OptionalDouble eligibleTier(Player player) {
        if (!GatheringToolMatcher.matches(player.getInventory().getItemInMainHand(),
                GatheringToolMatcher.WOODCUTTING)) {
            // use-skillタグを優先し、戦闘斧は拒否、タグのないバニラ斧は許可する。
            return OptionalDouble.empty();
        }
        if (!PlayerData.of(player).treeFellEnabled()) {
            return OptionalDouble.empty();
        }
        OptionalDouble tier = dedicatedEffects.valueMax(player, EFFECT_TREE_FELL);
        if (tier.isEmpty()
                || gimmickConfig.treeFellMaxExtraLogs((int) tier.getAsDouble()) <= 0) {
            return OptionalDouble.empty();
        }
        return tier;
    }

    /** Evaluates every {@code woodcutting} drop-table category independently (§4). */
    private void rollDropTables(Player player, Block block) {
        Map<String, DropTableConfig.Category> categories = gimmickConfig.dropTables();
        if (categories.isEmpty()) {
            return;
        }
        Set<String> heldPerks = DropTableGateSupport.heldPerksOf(player);
        Map<String, Set<String>> dropGatePerks = dedicatedEffects.dropGatePerks();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (DropTableConfig.Category category : categories.values()) {
            Optional<DropTableConfig.Entry> drawn = DropTablePolicy.evaluateCategory(
                    PROF_WOODCUTTING, category, heldPerks, dropGatePerks, rng.nextDouble(), rng.nextDouble());
            drawn.ifPresent(entry -> dropEntry(block, entry));
        }
    }

    private void dropEntry(Block block, DropTableConfig.Entry entry) {
        Optional<ItemStack> built = itemResolver.create(entry.item());
        if (built.isEmpty()) {
            // Fail-safe: an unresolvable item id must never throw out of a block-break handler.
            return;
        }
        ItemStack prize = built.get();
        prize.setAmount(Math.max(1, entry.amount()));
        block.getWorld().dropItemNaturally(block.getLocation(), prize);
    }
}
