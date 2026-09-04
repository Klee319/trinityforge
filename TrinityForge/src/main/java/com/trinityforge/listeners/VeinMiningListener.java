package com.trinityforge.listeners;

import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.MiningGimmickConfig;
import com.trinityforge.gathering.ChainBreakExpGrant;
import com.trinityforge.gathering.ChainBreakSupport;
import com.trinityforge.gathering.GatheringToolMatcher;
import com.trinityforge.items.ItemStackDrops;
import com.trinityforge.mining.VeinMiningAlgorithm;
import com.trinityforge.mining.VeinMiningAlgorithm.BlockPos;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DropTableConfig;
import com.trinityforge.stats.DropTablePolicy;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ore-break-triggered dedicated-effect consumers (all scoped to
 * {@code stats/mining-gimmick.yml vein-mining.ore-blocks} so stone/dirt/anything outside the ore
 * list is never affected — no config, no plugin behaviour change):
 *
 * <ul>
 *   <li>{@code vein-mining} (SCALE, 2026-07-25 gather-rework-active-framework §1): chain-breaks connected
 *       same-type ore ({@link VeinMiningAlgorithm}), capped by the player's resolved tier
 *       ({@code stats/mining-gimmick.yml vein-mining.tiers}, floor lookup via
 *       {@code DedicatedEffectsConfig#valueMax}).
 *       <b>2026-08-24</b>: 手置きの鉱石でも連鎖するようになった(ユーザー要望)。
 *       「すでに1回採掘済み」なので<b>TF が独自に配る報酬</b>だけを落とす —— 採取EXPと
 *       破壊時バニラEXPは {@code NativeSkillExperienceListener} の設置マークガードが、
 *       ドロップテーブルは {@link #onBlockBreakDropTables} の除外がそれぞれ担う。
 *       <b>鉱石そのもののバニラEXPオーブ({@code expToDrop})は対象外</b> ——
 *       バニラが設置ブロックでも等しく出すものなので触らない({@link #onBlockBreak} 参照)。
 *       <b>2026-08-24(2)</b>: 逆に<b>連鎖分のオーブは出ていなかった</b>(一括破壊が存在して以来の
 *       取りこぼし)。{@link #grantChainVanillaExperience} が起点にまとめて出す。</li>
 *   <li>{@code mining} drop-table (2026-07-23 stat-gate-overhaul §4): each configured category
 *       ({@code stats/mining-gimmick.yml drop-tables.categories}) independently rolls its own
 *       trigger-chance, then draws one weighted+gated prize ({@link DropTablePolicy}) — replaces the old
 *       hardcoded {@code gacha-ticket-1/2/3}/{@code ancient-debris-drop} dedicated-effect consumers.</li>
 * </ul>
 *
 * <p>Bundled into one listener (rather than 2) because both are gated on the exact same trigger
 * (an ore-block break) and share the same "is this an ore block" guard.
 */
public final class VeinMiningListener implements Listener {

    private static final String EFFECT_VEIN_MINING = "vein-mining";
    private static final String PROF_MINING = "mining";

    private final DedicatedEffectsConfig dedicatedEffects;
    private final MiningGimmickConfig gimmickConfig;
    private final CrossPluginItemResolver itemResolver;
    private final PlacedBlockTracker placedBlockTracker;
    private final FeedbackLayer feedback;

    /** 連鎖破壊分の採取EXP付与口(2026-07-28)。null 可 — 旧5引数コンストラクタ経由では EXP のみ入らない。 */
    private final ChainBreakExpGrant chainBreakExp;

    /** @deprecated 連鎖破壊分のEXPが入らない旧配線。8引数版ではなく6引数版を使うこと。 */
    @Deprecated
    public VeinMiningListener(DedicatedEffectsConfig dedicatedEffects, MiningGimmickConfig gimmickConfig,
                               CrossPluginItemResolver itemResolver, PlacedBlockTracker placedBlockTracker,
                               FeedbackLayer feedback) {
        this(dedicatedEffects, gimmickConfig, itemResolver, placedBlockTracker, feedback, null);
    }

    public VeinMiningListener(DedicatedEffectsConfig dedicatedEffects, MiningGimmickConfig gimmickConfig,
                               CrossPluginItemResolver itemResolver, PlacedBlockTracker placedBlockTracker,
                               FeedbackLayer feedback, ChainBreakExpGrant chainBreakExp) {
        this.chainBreakExp = chainBreakExp;
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
        this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");
        this.placedBlockTracker = Objects.requireNonNull(placedBlockTracker, "placedBlockTracker");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    /**
     * Vein-mining chain-break only (existing mechanic, priority unchanged — 2026-07-23 verifier指摘⑧:
     * the drop-table roll moved out to {@link #onBlockBreakDropTables} at {@link EventPriority#MONITOR}
     * so a protection plugin cancelling at {@code HIGHEST} can no longer suppress the break yet still
     * have a prize drop out of it).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (SpellBreakGuard.isSpellBreak(event.getBlock())) {
            // 魔法(Ars)破壊の合成イベントには一括破壊/ドロップテーブルを一切与えない。
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material type = block.getType();
        if (!gimmickConfig.oreBlocks().contains(type)) {
            // no-op fallback: everything below is scoped to the configured ore list only.
            return;
        }
        // 2026-08-24 ユーザー要望「鉱石の一括破壊は手置きのものにも適用されるようにしてほしい。
        // ただすでに一回採掘済みなのでバニラEXPと職業EXPは反映されないようにする必要がある」。
        //
        // 【変更前】ここに `if (placedBlockTracker.isPlaced(block)) return;` があり、手置きの鉱石を
        // 壊しても連鎖が一切起きなかった(GTH-02: シルクタッチで回収 → 並べて設置 → 幸運で1個割ると
        // 全部が幸運付きで連鎖破壊される、という増殖経路を止めるための門だった)。
        //
        // 【変更後】門を外して連鎖を通す。<b>このリスナー側で足す抑止は無い</b> ── 要望の
        // 「EXPは入らない」側は既に3経路とも成立しているため:
        //   ・起点の採取EXPと<b>破壊時バニラEXP</b>(TFのパークが配るおまけの経験値) →
        //     NativeSkillExperienceListener#blockedByPlaceBreakGuard が設置マークで弾く
        //   ・連鎖分の採取EXP → 同じガードを ChainBreakExpGrant 経由で1ブロックずつ通る
        //     (手置きの段だけが0になり、巻き込まれた自然生成の段は従来どおり入る)
        //   ・連鎖分のバニラEXPオーブ → ChainBreakSupport#breakOnce は setType(AIR) で壊すので
        //     そもそもオーブが出ない(※2026-08-24: これは手置き対策ではなく<b>取りこぼし</b>だった。
        //     grantChainVanillaExperience で起点にまとめて補うようにしたが、そちらは設置/自然を
        //     区別しない —— 理由は同メソッドの javadoc)
        //
        // ⚠ <b>起点の expToDrop(鉱石そのものが落とすバニラの経験値オーブ)は絶対に触らない。</b>
        //   2026-08-24 に一度 setExpToDrop(0) を入れて差し戻された。要望の「バニラEXP」は
        //   TF のステ「破壊時バニラEXP」を指していて、鉱石固有のオーブのことではない。
        //   あれはバニラが設置ブロックでも等しく出すもので、ここで消すと一括破壊とは無関係に
        //   「手置きの鉱石を割ると経験値が出ない」というバニラからの無言の乖離になる。
        // ⚠ ドロップテーブル抽選(onBlockBreakDropTables)は設置ブロックを除外したまま据え置く
        //   ── あれは TF 独自の「採掘の報酬」なので、採取EXP と同じ扱いにしておく。
        // ⚠ GTH-02 の増殖経路そのものは開く。シルクタッチ回収 → 再設置 → 幸運で割る、は
        //   バニラでも1個ずつなら可能な手順で、ここでは連鎖するぶん速くなる。
        //   幸運を手置きの段だけ無効化するなら別途指示が要る(要望の範囲外なので触っていない)。
        if (!PlayerData.of(player).veinMiningEnabled()) {
            // 2026-07-25 §2 B-2: プレイヤートグルOFF(選択採掘したい場面向け)。
            return;
        }
        if (!GatheringToolMatcher.matches(player.getInventory().getItemInMainHand(),
                GatheringToolMatcher.MINING)) {
            // 2026-07-27: 従来ここには**ツール判定が一切無く**、素手でも杖でも伐採斧でも一括破壊が
            // 発動していた(一括伐採だけが斧を要求しており非対称だった)。use-skill タグ優先・
            // 素のバニラのツルハシはマテリアル推論で許可、という規則は GatheringToolMatcher 参照。
            return;
        }

        OptionalDouble tier = dedicatedEffects.valueMax(player, EFFECT_VEIN_MINING);
        if (tier.isEmpty()) {
            return;
        }
        int broken = handleVeinMining(player, block, type, (int) tier.getAsDouble());
        grantChainVanillaExperience(event, broken);
    }

    /**
     * 連鎖分のバニラ経験値オーブを起点にまとめて出す (2026-08-24 ユーザー指摘
     * 「連鎖分のバニラEXPオーブでないの問題じゃない？」)。
     *
     * <p><b>直した不具合</b>: {@link ChainBreakSupport#breakOnce} は {@code setType(AIR)} で壊すので
     * 連鎖分は経験値オーブを1個も出さない。旧実装の {@code breakNaturally(ItemStack)} も Paper では
     * {@code dropExperience=false} の縮退呼び出しなので同じ。つまり<b>一括破壊が存在して以来ずっと</b>、
     * 石炭/ダイヤ/エメラルド/ラピス/レッドストーン/ネザークォーツ/ネザー金の鉱脈を一括で掘ると
     * <b>起点1ブロック分の経験値しか入っていなかった</b>。1個ずつ手で掘るより損をする、という
     * 無言の弱体で、一括伐採の「EXPも耐久も入らない」(2026-07-28 報告)と同じ型の取りこぼし。
     *
     * <p><b>起点の {@code expToDrop} を人数分に掛ける方式にした理由</b>: ブロックが落とす経験値量を
     * 問い合わせる API は paper-api 1.21.11 に無い({@code Block} に {@code getExpDrop} 系は存在せず、
     * {@code breakNaturally} は経験値を出す代わりに<b>ルートテーブルを引き直す</b>ので使えない ——
     * 2026-07-31 に「EXPの根拠と実際に落ちた物が食い違う」として潰した二重抽選が復活する)。
     * 一括破壊の連鎖対象は<b>起点と同一材質</b>に限られるので、起点が実際に転がした
     * {@code expToDrop} が「この材質をこの道具で壊したときの1ブロック分」の正確な実測値になる。
     * これを {@code 1 + broken} 倍する。
     *
     * <p>この方式で自動的に正しくなるもの:
     * <ul>
     *   <li><b>シルクタッチ</b> —— 起点が 0 なので連鎖分も 0(バニラと同じ)</li>
     *   <li><b>経験値を落とさない鉱石</b>(鉄/銅/金/古代の残骸) —— 同上、0 のまま</li>
     *   <li><b>他プラグインが起点の {@code expToDrop} を書き換えた場合</b> —— HIGH で読むので追随する</li>
     * </ul>
     *
     * <p><b>バニラとの差</b>: (1) オーブは連鎖先ではなく<b>起点にまとめて</b>湧く(拾い漏れが減る方向。
     * 洞窟の奥に取り残されない)。(2) 1ブロックごとに振り直すのではなく起点の目を全段に使うので、
     * 期待値は同じで<b>ばらつきだけが大きい</b>(ダイヤは 3〜7 の一様分布)。
     *
     * <p><b>設置ブロックも数に入れる。</b> 経験値オーブはバニラが設置ブロックにも等しく出すもので、
     * ここで抜くと「手置きの段だけ経験値が出ない」という乖離になる(2026-08-24 の差し戻しと同じ理由)。
     * <b>増殖経路にはならない</b> —— 鉱石は壊すと資源に変わって<b>ブロックが手元に戻らない</b>ので、
     * 「シルクタッチで回収 → 置く → 割る」は経験値の総量が1個ずつ壊した場合と変わらない
     * (シルクタッチ側が 0 なので、置いて割った1回分だけが入る)。
     *
     * @param broken {@link #handleVeinMining} が実際に壊した連鎖分の数(起点は含まない)
     */
    private void grantChainVanillaExperience(BlockBreakEvent event, int broken) {
        if (broken <= 0) {
            return;
        }
        int perBlock = event.getExpToDrop();
        if (perBlock <= 0) {
            // シルクタッチ / 経験値を落とさない鉱石。掛け算しても 0 なので何もしない
            // (setExpToDrop(0) を呼ぶと「TF が経験値を消した」ように見えるので呼ばない)。
            return;
        }
        event.setExpToDrop(perBlock * (1 + broken));
    }

    /**
     * Drop-table roll only (2026-07-23 verifier指摘⑧): {@link EventPriority#MONITOR} +
     * {@code ignoreCancelled=true} so a protection plugin's {@code HIGHEST} cancel (or any other plugin's
     * cancel) reliably suppresses the prize too; also excludes player-placed ore blocks
     * ({@link PlacedBlockTracker#isPlaced}) from ever contributing to the drop table.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreakDropTables(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (SpellBreakGuard.isSpellBreak(block)) {
            // 魔法(Ars)破壊の合成イベントには一括破壊/ドロップテーブルを一切与えない。
            return;
        }
        if (!gimmickConfig.oreBlocks().contains(block.getType()) || placedBlockTracker.isPlaced(block)) {
            return;
        }
        rollDropTables(event.getPlayer(), block);
    }

    /**
     * Chain-breaks up to {@link MiningGimmickConfig#veinMiningMaxExtraBlocks()} connected same-type
     * ore blocks via {@link VeinMiningAlgorithm#collect}. The originally-broken block ({@code origin})
     * is left to the event itself (never cancelled, never re-broken here). Each extra block is broken
     * with {@link Block#breakNaturally(ItemStack)} using the player's held tool, so vanilla
     * fortune/silk-touch on that tool still applies to the drop exactly as a normal break would.
     *
     * @return 実際に壊した連鎖分の数(起点は含まない)。連鎖分のバニラ経験値を起点にまとめて出すため、
     *         呼び出し側が {@link #grantChainVanillaExperience} へ渡す。
     */
    private int handleVeinMining(Player player, Block origin, Material type, int tier) {
        World world = origin.getWorld();
        BlockPos start = new BlockPos(origin.getX(), origin.getY(), origin.getZ());
        ItemStack tool = player.getInventory().getItemInMainHand();

        List<BlockPos> extra = VeinMiningAlgorithm.collect(
                start,
                pos -> world.getBlockAt(pos.x(), pos.y(), pos.z()).getType() == type,
                gimmickConfig.veinMiningMaxExtraBlocks(tier));

        // 連鎖分の drop-tables 抽選の母数は「壊す前」に数える —— 破壊後は設置マークが残らない。
        int natural = countNatural(world, extra);

        // 2026-07-28: 連鎖分の採取EXPと道具耐久は ChainBreakSupport が担う(旧実装は breakNaturally
        // だけで、EXPも耐久も一切処理されていなかった)。
        int broken = ChainBreakSupport.breakChain(player, world, extra, type, tool, chainBreakExp);
        rollChainDropTables(player, origin, Math.min(broken, natural));
        if (broken > 0) {
            // 2026-07-25 §2 B-1: 発動フィードバック(控えめなactionbar、旧仕様の無告知を解消)。
            feedback.subtle(player, "一括破壊 x" + broken);
        }
        return broken;
    }

    /**
     * 連鎖破壊した鉱石のぶんの drop-tables 抽選(2026-08-24。一括伐採 W-204 と同型の取りこぼし)。
     *
     * <p><b>なぜ必要か。</b> 追加ドロップは {@link #onBlockBreakDropTables} が {@code BlockBreakEvent}
     * を見て引くが、連鎖分は {@link ChainBreakSupport#breakOnce} が {@code setType(AIR)} で消すだけで
     * <b>{@code BlockBreakEvent} を発火しない</b>(合成すると設置追跡・採掘運など10以上のリスナーが
     * 連鎖分にも反応してしまうため意図的にそうしてある)。その結果、鉱脈を一括で掘っても
     * <b>起点1ブロック分しか抽選されていなかった</b> —— 1個ずつ手で掘るより損をする。
     *
     * <p><b>なぜブロック1つごとに引かないか。</b> {@code vein-mining.chain-drop-rolls-max} を上限として
     * 「壊した数と上限の小さいほう」だけ引く。上限が無いと1回の一括破壊で最大 max-extra-blocks 回
     * 引くことになり、手掘りと入手量の桁が変わる。
     *
     * <p><b>設置ブロックは数に入れない。</b> {@link #onBlockBreakDropTables} が起点の設置ブロックを
     * 除外しているのと同じ規約(追加ドロップは TF 独自の「採掘の報酬」なので採取EXPと同じ扱い)。
     * 数えるのは<b>壊す前</b> —— 破壊後は設置マークが残らない。
     *
     * <p>落とす位置は叩いた起点。連鎖先は鉱脈の奥まで伸びるので、取り残しを避けて手元に集める。
     *
     * @param rolls 連鎖で壊した自然生成ブロックの数(0以下なら何もしない)
     */
    private void rollChainDropTables(Player player, Block origin, int rolls) {
        if (rolls <= 0) {
            return;
        }
        int capped = Math.min(rolls, gimmickConfig.veinMiningChainDropRollsMax());
        for (int i = 0; i < capped; i++) {
            rollDropTables(player, origin);
        }
    }

    /** {@code positions} のうちプレイヤーが設置したのではないブロックの数。 */
    private int countNatural(World world, List<BlockPos> positions) {
        int natural = 0;
        for (BlockPos pos : positions) {
            if (!placedBlockTracker.isPlaced(world.getBlockAt(pos.x(), pos.y(), pos.z()))) {
                natural++;
            }
        }
        return natural;
    }

    /** Evaluates every {@code mining} drop-table category independently (§4). */
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
                    PROF_MINING, category, heldPerks, dropGatePerks, rng.nextDouble(), rng.nextDouble());
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
        // 2026-09-04 W-312: entry.amount() が99を超える設定でも、アイテムエンティティの
        // コーデック上限(99)以下へ分割してから落とす(ItemStackDrops 参照)。
        ItemStackDrops.dropSplit(block.getWorld(), block.getLocation(), prize);
    }
}
