package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.MiningGimmickConfig;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.stats.GatheringPolicy;
import com.trinityforge.stats.StatKeys;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 採掘の連続処理(gathering): サバイバルでツールの mining-fortune ステ + MINING スキルLvに応じた期待値ぶんだけ、
 * 対象鉱石/作物ブロック({@code stats/mining-gimmick.yml} {@code fortune.fortune-blocks})を壊した際のドロップを
 * 追加スポーンする。整数部は確定、小数部は確率で+1する期待値方式({@link GatheringPolicy#expectedExtra})。
 *
 * <p><strong>ランタイム検証必須</strong>: {@link org.bukkit.World#dropItemNaturally} によるブロック破壊時の
 * 追加ドロップスポーンは、実サーバーでの動作確認が必要。
 */
public final class MiningFortuneListener implements Listener {

    private static final String MINING_FORTUNE_KEY = StatKeys.canonical("mining-fortune");

    private final MiningGimmickConfig gathering;
    private final SkillLevelSource skillLevelSource;
    private final PlayerStatAggregator aggregator;
    private final PlacedBlockTracker placedBlockTracker;
    private final Enchantment silkTouch;

    public MiningFortuneListener(MiningGimmickConfig gathering,
                                 SkillLevelSource skillLevelSource, PlayerStatAggregator aggregator,
                                 PlacedBlockTracker placedBlockTracker) {
        this.gathering = Objects.requireNonNull(gathering, "gathering");
        this.skillLevelSource = Objects.requireNonNull(skillLevelSource, "skillLevelSource");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.placedBlockTracker = Objects.requireNonNull(placedBlockTracker, "placedBlockTracker");
        this.silkTouch = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("silk_touch"));
    }

    /**
     * GTH-01 exploit-fix support field (2026-07-25). {@link BlockDropItemEvent} fires strictly AFTER a
     * player-initiated break's {@link BlockBreakEvent} finishes ALL priorities including {@code MONITOR}
     * — and {@code NativeSkillExperienceListener#onBlockBreak} (also {@code MONITOR}) already calls
     * {@code placedBlockTracker.clearIfPlaced(block)} there. So by the time {@link #onBlockDropItem} runs,
     * {@code placedBlockTracker.isPlaced(block)} would ALWAYS read false for a player-initiated break of a
     * placed block, regardless of what priority this listener picks on {@code BlockDropItemEvent} — the
     * mark is already gone. This single-slot cache captures the placed status at {@link EventPriority#HIGH}
     * on {@link #onBlockBreak} (strictly before {@code MONITOR}, so before the clear, and priority buckets
     * are ordered independent of listener registration order) and hands it to the paired
     * {@code onBlockDropItem} call for the SAME break. A single mutable field (not a growing map) is safe
     * because Bukkit processes one block break fully, synchronously, on the main thread — no other break's
     * onBlockBreak/onBlockDropItem pair can interleave between this write and its paired read.
     *
     * <p>Chain-mined blocks (vein-mining/tree-felling/digging-gimmick extras broken via
     * {@link Block#breakNaturally(ItemStack)}) never fire {@code BlockBreakEvent} at all, so this field is
     * never set for them and {@link #onBlockDropItem} falls back to a direct
     * {@code placedBlockTracker.isPlaced(block)} read — which is correct for that path because
     * {@code clearIfPlaced} is likewise never invoked for a block that never had a {@code BlockBreakEvent}.
     */
    private Location pendingPlacedBreakLocation;

    /**
     * Captures placement status for the paired {@link #onBlockDropItem} call — see
     * {@link #pendingPlacedBreakLocation} javadoc. Scoped to {@code fortune-blocks} only (cheap check,
     * keeps this a true single-slot cache instead of accumulating irrelevant breaks).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (SpellBreakGuard.isSpellBreak(block)) {
            // 魔法(Ars)破壊の合成イベントにはmining-fortuneの追加ドロップを一切与えない(このリスナーの
            // 実効果はBlockDropItemEvent側にあり、fork破壊経路では発火しないため今は無害だが、将来の
            // 変更に備えて他の採取系リスナーと同じガードを一貫して適用する)。
            return;
        }
        if (!gathering.fortuneBlocks().contains(block.getType())) {
            return;
        }
        pendingPlacedBreakLocation = placedBlockTracker.isPlaced(block) ? block.getLocation() : null;
    }

    /**
     * {@link EventPriority#HIGHEST}: other plugins/handlers at lower priorities decide/cancel first, so
     * by the time this runs {@link BlockDropItemEvent#isCancelled()} reflects the final outcome (only
     * {@code MONITOR} runs later, and MONITOR must not mutate). {@code ignoreCancelled = true} skips the
     * call entirely once cancelled; the explicit re-check below is a defense-in-depth belt-and-suspenders
     * guard against any future change to that dispatch behavior.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        if (!gathering.fortuneBlocks().contains(event.getBlockState().getType())) {
            return;
        }
        Block brokenBlock = event.getBlock();
        boolean placedViaCache = brokenBlock.getLocation().equals(pendingPlacedBreakLocation);
        pendingPlacedBreakLocation = null; // consume regardless of match — see field javadoc
        if (placedViaCache || placedBlockTracker.isPlaced(brokenBlock)) {
            // GTH-01 exploit fix (2026-07-25): every sibling extra-drop implementation
            // (VeinMiningListener#onBlockBreakDropTables, TreeFellingListener, DiggingGimmickListener,
            // GatheringExtraDropListener) excludes player-placed blocks from bonus drops; this listener was
            // the one gap. Without it, a craftable-and-placeable fortune-block (e.g. GLOWSTONE: 4 dust ->
            // 1 block via vanilla recipe) can be placed and re-broken in a loop, and this listener's
            // uncapped expected-extra bonus (up to GatheringPolicy.MAX_EXTRA) pushes the yield-per-break
            // above vanilla's break-even point, making the resource increase without bound.
            return;
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (hasSilkTouch(tool)) {
            // Silk Touch changes the primary drop and therefore never combines with extra-drop rolls.
            return;
        }

        // mining-fortune は「総合ステータス」扱い: ツール単体ではなく、防具4部位 + (設定により)オフハンド +
        // パーク + スレッド等アドオンまで全チャネルを加算し、乗算レイヤも適用した値を使う。
        PlayerCombatAggregate agg = aggregator.aggregate(player, tool);
        double toolFortune = agg.totalOf(MINING_FORTUNE_KEY);

        int miningLevel = skillLevelSource.levelsOf(player.getUniqueId())
                .getOrDefault(gathering.fortuneSkillId(), 0);
        double expected = expectedExtraRate(toolFortune, miningLevel, gathering.fortunePerLevel());

        int extra = GatheringPolicy.expectedExtra(expected, ThreadLocalRandom.current().nextDouble());
        if (extra <= 0) {
            return;
        }

        Item primary = event.getItems().stream()
                .filter(dropped -> {
                    ItemStack stack = dropped.getItemStack();
                    return stack != null && !stack.getType().isAir();
                })
                .findFirst()
                .orElse(null);
        if (primary == null) {
            return;
        }

        // Semantics: "extra" is a count of additional individual items of the PRIMARY drop, not a
        // multiplier over every dropped stack (GatheringPolicy docs) — so seed/produce side-drops in
        // the same block break are left untouched.
        ItemStack template = primary.getItemStack().clone();
        template.setAmount(1);
        int bounded = Math.min(extra, GatheringPolicy.MAX_EXTRA);
        for (int i = 0; i < bounded; i++) {
            event.getBlock().getWorld().dropItemNaturally(primary.getLocation(), template.clone());
        }
    }

    private boolean hasSilkTouch(ItemStack tool) {
        if (tool == null || !tool.hasItemMeta()) {
            return false;
        }
        var meta = tool.getItemMeta();
        return silkTouch != null && meta.getEnchantLevel(silkTouch) > 0;
    }

    /**
     * 追加ドロップの期待個数 = {@code mining-fortune}(割合。0.15 = 「ドロップ増加+15%」) +
     * MINING Lv × {@code fortune-per-level}。
     *
     * <p>2026-07-28: 以前は結果に {@code × 0.30} を掛けていた。この係数があると skilltree が
     * 宣言している「ドロップ増加+15%」が実際には +4.5% にしかならず、effect-text と実挙動が
     * 食い違う。係数は撤去し、レベル項の既定値を 1/0.30 ぶん下げる({@code fortune-per-level}
     * 0.02→0.006、出荷 yml 0.01→0.003)ことでレベル由来の増加量は従来どおりに保つ。
     */
    static double expectedExtraRate(double miningFortune, int miningLevel, double fortunePerLevel) {
        return miningFortune + miningLevel * fortunePerLevel;
    }
}
