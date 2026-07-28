package com.trinityforge.listeners;

import com.trinityforge.active.ActiveSkillCooldownKeys;
import com.trinityforge.active.CooldownManager;
import com.trinityforge.active.FeedbackLayer;
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
import com.trinityforge.woodcutting.WoodcuttingMaterials;
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
public final class TreeFellingListener implements Listener {

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
     * Chain-fells up to the resolved tier's max-extra-logs connected same-type log blocks via
     * {@link VeinMiningAlgorithm#collect}, gated on holding an axe and on a per-player cooldown. The
     * originally-broken block ({@code origin}) is left to the event itself. Leaves are deliberately
     * left untouched (要調整: no connected-leaves cleanup, vanilla decay handles them).
     *
     * <p>2026-07-25 PRG-07: 私製{@code ConcurrentHashMap}のCT管理を、汎用アクティブスキル基盤の
     * {@link CooldownManager}(共有インスタンス、{@code ActivationDispatcher}と同じ)へ統合。CT短縮は
     * woodcutting.yml B-1/B-2/B-3 が誤って読んでいたアイテムCT短縮キー({@code cooldown-reduction})ではなく、
     * 一括伐採専用の{@link #COOLDOWN_REDUCTION_KEY}({@code tree-fell-cooldown-reduction})を読む
     * (数値0.3は変更していない、キーのみ移行)。基準CTは従来通り{@code gimmickConfig.treeFellCooldownTicks()}。
     */
    private void handleTreeFelling(Player player, Block origin, Material type) {
        if (!GatheringToolMatcher.matches(player.getInventory().getItemInMainHand(),
                GatheringToolMatcher.WOODCUTTING)) {
            // 2026-07-27: 旧判定は WoodcuttingMaterials.isAxe(マテリアル) で、TFが別ラインで出荷している
            // **戦闘用の斧(use-skill: HEAVY_WEAPONS)でも一括伐採が発動していた**。use-skill タグを
            // 優先し、タグの無い素のバニラの斧はマテリアル推論で従来どおり許可する。
            return;
        }
        if (!PlayerData.of(player).treeFellEnabled()) {
            // 2026-07-25 §2 B-2: プレイヤートグルOFF(特定の木だけ伐りたい場面向け)。
            return;
        }

        OptionalDouble tier = dedicatedEffects.valueMax(player, EFFECT_TREE_FELL);
        if (tier.isEmpty()) {
            return;
        }
        int maxExtra = gimmickConfig.treeFellMaxExtraLogs((int) tier.getAsDouble());
        if (maxExtra <= 0) {
            return;
        }

        long baseCooldownMillis = gimmickConfig.treeFellCooldownTicks() * 50L;
        double reduction = aggregator.aggregate(player).totalOf(COOLDOWN_REDUCTION_KEY);
        long cooldownMillis = CooldownManager.applyReduction(baseCooldownMillis, reduction);
        long now = System.currentTimeMillis();
        if (!cooldowns.tryConsume(player.getUniqueId(), COOLDOWN_SKILL_ID, cooldownMillis, now)) {
            return;
        }

        World world = origin.getWorld();
        BlockPos start = new BlockPos(origin.getX(), origin.getY(), origin.getZ());
        ItemStack tool = player.getInventory().getItemInMainHand();

        List<BlockPos> extra = VeinMiningAlgorithm.collect(
                start,
                pos -> world.getBlockAt(pos.x(), pos.y(), pos.z()).getType() == type,
                maxExtra);

        // 2026-07-28: 連鎖分の採取EXPと道具耐久は ChainBreakSupport が担う(旧実装は breakNaturally
        // だけで、EXPも耐久も一切処理されていなかった)。
        int broken = ChainBreakSupport.breakChain(player, world, extra, type, tool, chainBreakExp);
        if (broken > 0) {
            // 2026-07-25 §2 B-1: 発動フィードバック(控えめなactionbar)。
            feedback.subtle(player, "一括伐採 x" + broken);
        }
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
