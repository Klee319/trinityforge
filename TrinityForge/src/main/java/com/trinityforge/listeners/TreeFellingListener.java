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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

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
        if (!(WoodcuttingMaterials.isLog(type) || WoodcuttingMaterials.isLeaves(type))
                || placedBlockTracker.isPlaced(block)) {
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
     * 葉は {@link #fellLeaves} が木全体を種にして掃除する(2026-07-30 で導入、2026-07-31 N2 で拡張)。
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
        int tierValue = (int) tier.getAsDouble();
        int maxExtra = gimmickConfig.treeFellMaxExtraLogs(tierValue);

        World world = origin.getWorld();
        BlockPos originPos = new BlockPos(origin.getX(), origin.getY(), origin.getZ());
        ItemStack tool = player.getInventory().getItemInMainHand();

        // 2026-07-31 N1: 走査の錨は「叩いたブロック」ではなく木そのもの。幹の最下段まで降りてから
        // 木全体を把握し、伐る本数を決定的な順序(y→x→z)で選ぶ。これで上面/側面/底面のどこを叩いても
        // 同じ集合が伐れる(TreeScan の javadoc 参照)。走査上限は伐採上限とは別枠 —
        // 上限で伐り残した幹も葉の走査の種に必要なため。
        Predicate<BlockPos> sameLog = pos -> world.getBlockAt(pos.x(), pos.y(), pos.z()).getType() == type;
        BlockPos base = TreeScan.trunkBase(originPos, sameLog);
        List<BlockPos> tree = TreeScan.wholeTree(base, sameLog, TreeScan.TREE_SCAN_LIMIT);
        List<BlockPos> extra = TreeScan.selectFelled(tree, originPos, maxExtra);

        // 2026-07-31 N1: CT は範囲が確定してから消費する。旧実装は走査より前に消費していたので
        // 「1本も伐れない破壊」でも10秒のCTを取られ、面を変えて試した2回目が無言で不発になっていた。
        // 葉の掃除だけが走る余地(1本木)がある場合は仕事があるものとして扱う。
        if (extra.isEmpty() && !gimmickConfig.treeFellBreakLeaves()) {
            return;
        }
        long cooldownMillis = cooldownMillis(player);
        long now = System.currentTimeMillis();
        if (!cooldowns.tryConsume(player.getUniqueId(), COOLDOWN_SKILL_ID, cooldownMillis, now)) {
            return;
        }

        // 2026-07-28: 連鎖分の採取EXPと道具耐久は ChainBreakSupport が担う(旧実装は breakNaturally
        // だけで、EXPも耐久も一切処理されていなかった)。
        int broken = ChainBreakSupport.breakChain(player, world, extra, type, tool, chainBreakExp);
        int leaves = fellLeaves(player, world, originPos, tree, broken, tool, tierValue);
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
     * 伐り倒した木に繋がる葉も一緒に壊す(2026-07-30 実サーバ要望「一括伐採時に葉っぱも一括破壊されて
     * ほしい」／2026-07-31 N2「葉の自動破壊がバニラより大幅に速くなるように」)。
     *
     * <p><b>種は「木全体」</b>: この時点で伐った幹はすでに AIR なので元の1ブロックから flood-fill しても
     * 葉に辿り着けない。さらに<em>伐った丸太だけ</em>を種にすると、上限で伐り残した幹に付いた樹冠へ到達
     * できない(葉の BFS は葉しか辿らないため)。そこで {@link TreeScan#wholeTree} が把握した木全体
     * — 伐り残した幹も含む — を種にする。これが「側面から叩くと葉が0枚、真上から叩くと48枚」という
     * 階段状の起点依存を消す一点。
     *
     * <p><b>{@code leaves-decay-only}(既定 true)</b>: 上限を桁で上げたので、葉だけを辿る BFS を素のまま
     * 使うと隣の木の樹冠まで食う。{@link LeafDecayPlanner} が「バニラなら崩壊する葉」= 設置された葉
     * ({@code persistent})でなく、かつ残存原木から距離6以内で支えられていない葉 — だけに絞る。
     * 支持判定は<b>破壊後に残るもの</b>で解く必要があるので、{@code origin}(この直後にイベント本体が
     * 壊す1本)は支持原木から除外する。
     *
     * <p>道具の耐久は消費しない({@link ChainBreakSupport#breakChain} の {@code consumeDurability=false})
     * — 葉は硬度0.2でバニラなら耐久を減らすが、1回で数百枚が巻き込まれるため、そのまま取ると
     * 「一括伐採を解放した途端に斧が即壊れる」になる。採取EXPは原木と同じく設定次第
     * ({@code woodcutting_progression.yml} に葉の行が無ければ0 — 現状は0のまま据え置き)。
     *
     * @param treeLogs 木全体(伐り残した幹も含む)。葉の走査の種。
     * @return 壊すことが確定した葉の枚数(段階破壊した場合は次tick以降に回した分も含む)
     */
    private int fellLeaves(Player player, World world, BlockPos origin, List<BlockPos> treeLogs,
                           int brokenLogs, ItemStack tool, int tier) {
        if (!gimmickConfig.treeFellBreakLeaves() || treeLogs.isEmpty()) {
            return 0;
        }
        int maxLeaves = gimmickConfig.treeFellMaxLeaves(tier, brokenLogs);
        if (maxLeaves <= 0) {
            return 0;
        }
        boolean decayOnly = gimmickConfig.treeFellLeavesDecayOnly();
        List<BlockPos> doomed = LeafDecayPlanner.plan(
                treeLogs,
                pos -> isBreakableLeaf(world, pos, decayOnly),
                pos -> !pos.equals(origin)
                        && WoodcuttingMaterials.isLog(world.getBlockAt(pos.x(), pos.y(), pos.z()).getType()),
                maxLeaves,
                decayOnly);
        return breakLeavesStaged(player, world, doomed, tool);
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
     * @return 壊すことが確定した枚数(次tick以降に回した分も含む)
     */
    private int breakLeavesStaged(Player player, World world, List<BlockPos> doomed, ItemStack tool) {
        if (doomed.isEmpty()) {
            return 0;
        }
        int perTick = gimmickConfig.treeFellLeavesPerTick();
        Plugin plugin = schedulerPlugin();
        if (perTick <= 0 || plugin == null || doomed.size() <= perTick) {
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
