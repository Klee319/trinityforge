package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.stats.StatKeys;
import org.bukkit.GameMode;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 採取系の追加ドロップconsumer(2件): {@code woodcutting_extra_drop_chance}(原木/木材)と
 * {@code harvest_extra_drop_chance}(成熟作物)。どちらもフラクション値を確率としてそのまま使う
 * (0.2 = 20%抽選、1.0超は保証+端数の追加抽選)。設置ブロックは{@link PlacedBlockTracker}で除外し、
 * 設置→破壊の量産ファームを対象外にする。
 */
public final class GatheringExtraDropListener implements Listener {

    private static final String WOODCUTTING_EXTRA_DROP_CHANCE =
            StatKeys.canonical("woodcutting_extra_drop_chance");
    private static final String HARVEST_EXTRA_DROP_CHANCE =
            StatKeys.canonical("harvest_extra_drop_chance");

    private final PlayerStatAggregator aggregator;
    private final PlacedBlockTracker placedBlockTracker;

    public GatheringExtraDropListener(PlayerStatAggregator aggregator, PlacedBlockTracker placedBlockTracker) {
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.placedBlockTracker = Objects.requireNonNull(placedBlockTracker, "placedBlockTracker");
    }

    // HIGH で走らせる: 設置マークの消去は NativeSkillExperienceListener が MONITOR で clearIfPlaced する
    // 唯一の場所。MONITOR で isPlaced を読むと登録順によってはマーク消去後になり、設置→破壊の量産
    // ファームに追加ドロップが漏れる。VeinMining/TreeFelling と同じく MONITOR より前(HIGH)で読むことで
    // 順序非依存に設置ブロックを除外する。
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (SpellBreakGuard.isSpellBreak(event.getBlock())) {
            // 魔法(Ars)破壊の合成イベントには追加ドロップ抽選を一切与えない。
            return;
        }
        Player player = event.getPlayer();
        // クリエイティブ/観戦は通常ドロップ自体が発生しない(バニラがdropItemsを抑制する)ため、ここで
        // 追加ドロップを撒くとクリエイティブ限定のアイテム複製経路になってしまう。既存の
        // NativeSkillExperienceListener#excluded と同じ判定で明示的に除外する。
        if (excluded(player) || !event.isDropItems()) return;
        Block block = event.getBlock();
        if (placedBlockTracker.isPlaced(block)) return;

        boolean isLogOrWood = Tag.LOGS.isTagged(block.getType());
        boolean isMatureCrop = block.getBlockData() instanceof Ageable ageable
                && ageable.getAge() == ageable.getMaximumAge();

        if (isLogOrWood) {
            rollExtraDrop(player, block, WOODCUTTING_EXTRA_DROP_CHANCE);
        } else if (isMatureCrop) {
            rollExtraDrop(player, block, HARVEST_EXTRA_DROP_CHANCE);
        }
    }

    private void rollExtraDrop(Player player, Block block, String statKey) {
        double chance = aggregator.aggregate(player).totalOf(statKey);
        if (chance <= 0.0) return;
        // 1.0超 = 保証1回 + 端数分の追加抽選(hunger_save_chance等の既存パターンと同じ丸め方)。
        int guaranteed = (int) Math.floor(chance);
        double remainder = chance - guaranteed;
        int extraCopies = guaranteed;
        if (remainder > 0.0 && ThreadLocalRandom.current().nextDouble() < remainder) {
            extraCopies += 1;
        }
        if (extraCopies <= 0) return;

        for (ItemStack drop : block.getDrops(player.getInventory().getItemInMainHand(), player)) {
            if (drop == null || drop.getType().isAir()) continue;
            for (int i = 0; i < extraCopies; i++) {
                block.getWorld().dropItemNaturally(block.getLocation(), drop.clone());
            }
        }
    }

    private static boolean excluded(Player player) {
        GameMode gm = player.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }
}
