package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.DiggingGimmickConfig;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DropTableConfig;
import com.trinityforge.stats.DropTablePolicy;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 掘削(シャベル適正ブロック破壊)ギミック: {@code digging} drop-table (2026-07-23 stat-gate-overhaul §4)を
 * {@code stats/digging-gimmick.yml drop-tables.categories} から評価する新設リスナー。
 *
 * <p>対象判定は {@link com.trinityforge.listeners.NativeSkillExperienceListener#grantGathering} と
 * 同じ分類ロジックを流用する: {@code digging_progression.yml} の {@code digging_break} 表にブロック自身が
 * 載っており(値&gt;0)、かつ実際のドロップの中に同表へ載っている素材が1つ以上ある場合のみ「掘削の対象」
 * (= {@link NativeSkillExperienceListener#gatheringExp} が正の値を返す)。プレイヤーが設置したブロックは
 * {@link PlacedBlockTracker#isPlaced} で除外する(読み取り専用 — 実際のマーク消去は
 * {@code NativeSkillExperienceListener} のMONITORハンドラの責務のままにし、二重消費を避ける)。
 */
public final class DiggingGimmickListener implements Listener {

    private static final String PROF_DIGGING = "digging";

    private final DedicatedEffectsConfig dedicatedEffects;
    private final DiggingGimmickConfig gimmickConfig;
    private final NativeSkillCatalog catalog;
    private final PlacedBlockTracker placedBlockTracker;
    private final CrossPluginItemResolver itemResolver;

    public DiggingGimmickListener(DedicatedEffectsConfig dedicatedEffects, DiggingGimmickConfig gimmickConfig,
                                   NativeSkillCatalog catalog, PlacedBlockTracker placedBlockTracker,
                                   CrossPluginItemResolver itemResolver) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.placedBlockTracker = Objects.requireNonNull(placedBlockTracker, "placedBlockTracker");
        this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");
    }

    /**
     * Drop-table roll only — this listener has no other mechanic to preserve, so the whole handler moves
     * to {@link EventPriority#MONITOR} + {@code ignoreCancelled=true} (2026-07-23 verifier指摘⑧: a
     * protection plugin cancelling at {@code HIGHEST} must reliably suppress the prize too).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (SpellBreakGuard.isSpellBreak(event.getBlock())) {
            // 魔法(Ars)破壊の合成イベントにはドロップテーブルを一切与えない。
            return;
        }
        Map<String, DropTableConfig.Category> categories = gimmickConfig.dropTables();
        if (categories.isEmpty()) {
            return;
        }
        Block block = event.getBlock();
        if (placedBlockTracker.isPlaced(block)) {
            return;
        }
        Player player = event.getPlayer();
        Material type = block.getType();
        SkillCatalogEntry entry = catalog.get(SkillId.DIGGING);
        ItemStack tool = player.getInventory().getItemInMainHand();
        double exp = NativeSkillExperienceListener.gatheringExp(
                entry, "digging_break", type.name(), block.getDrops(tool, player));
        if (exp <= 0.0) {
            // no-op fallback: not a recognized shovel-appropriate digging_break target.
            return;
        }

        rollDropTables(player, block);
    }

    private void rollDropTables(Player player, Block block) {
        Set<String> heldPerks = DropTableGateSupport.heldPerksOf(player);
        Map<String, Set<String>> dropGatePerks = dedicatedEffects.dropGatePerks();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (DropTableConfig.Category category : gimmickConfig.dropTables().values()) {
            Optional<DropTableConfig.Entry> drawn = DropTablePolicy.evaluateCategory(
                    PROF_DIGGING, category, heldPerks, dropGatePerks, rng.nextDouble(), rng.nextDouble());
            drawn.ifPresent(prizeEntry -> dropEntry(block, prizeEntry));
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
