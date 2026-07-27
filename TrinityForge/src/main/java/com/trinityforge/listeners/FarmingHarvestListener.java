package com.trinityforge.listeners;

import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FarmingGimmickConfig;
import com.trinityforge.farming.AreaHarvestPolicy;
import com.trinityforge.farming.AreaHarvestPolicy.Offset;
import com.trinityforge.farming.DropAdjustment;
import com.trinityforge.farming.DropAdjustment.DropStack;
import com.trinityforge.farming.FarmingCropCatalog;
import com.trinityforge.gathering.GatheringToolMatcher;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * 農業スキルツリーのflag系dedicated-effect consumer群({@code stats/farming-gimmick.yml}でチューニング):
 *
 * <ul>
 *   <li>{@code auto-replant}(flag): 成熟作物を破壊した際、drop計算から種/苗1個分を差し引いた上で
 *       同ブロックをage0で1tick後に再設置する(=「植え直しと収穫が同時」)。</li>
 *   <li>{@code area-harvest}(flag): 起点の周囲({@code area-harvest.radius}、既定3x3)の成熟作物も
 *       一括収穫する。{@code auto-replant}も保有していれば同様に植え直す。</li>
 * </ul>
 *
 * <p>両方とも同じ「成熟作物のBlockBreakEvent」をトリガーとする「1リスナーに複数flag effectをまとめる」
 * 様式({@link TreeFellingListener}と同様)。周囲ブロックの処理は{@link Block#breakNaturally}/
 * {@link Block#setType}を直接呼ぶだけで{@link BlockBreakEvent}を再発火しないため、area-harvestが
 * area-harvestを連鎖的に再誘発することはない(加えて{@link #processingAreaHarvest}で多重ガード)。
 */
public final class FarmingHarvestListener implements Listener {

    private static final String EFFECT_AUTO_REPLANT = "auto-replant";
    private static final String EFFECT_AREA_HARVEST = "area-harvest";
    private static final long REPLANT_DELAY_TICKS = 1L;

    private final Plugin plugin;
    private final DedicatedEffectsConfig dedicatedEffects;
    private final FarmingGimmickConfig gimmickConfig;
    private final FeedbackLayer feedback;

    /**
     * area-harvest 再入ガード({@link CombatListener#applyingAoe}と同様の様式): 周囲ブロックの手動処理
     * (breakNaturally/setType)はBlockBreakEventを発火しないため通常は再入しないが、一度の起点破壊で
     * 1回のAoEのみに限定する保険として明示的にガードする。combatと同じくメインスレッド同期実行のため
     * 単純booleanで十分。
     */
    private boolean processingAreaHarvest = false;

    public FarmingHarvestListener(Plugin plugin, DedicatedEffectsConfig dedicatedEffects,
                                   FarmingGimmickConfig gimmickConfig, FeedbackLayer feedback) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (processingAreaHarvest) {
            return;
        }
        if (SpellBreakGuard.isSpellBreak(event.getBlock())) {
            // 魔法(Ars)破壊の合成イベントには自動再植/範囲収穫を一切与えない(CRITICAL:
            // これを欠くと fork の再ドロップ+TFのage0再設置が重なり無限複製になる)。
            return;
        }
        Block block = event.getBlock();
        Material type = block.getType();
        if (!FarmingCropCatalog.isCrop(type) || !isMature(block)) {
            return;
        }

        Player player = event.getPlayer();
        PlayerData playerData = PlayerData.of(player);
        // 2026-07-25 §2 B-2: プレイヤートグルOFF(苗を残したい/選択収穫したい場面向け)。
        boolean replantActive = playerData.autoReplantEnabled()
                && dedicatedEffects.isActive(player, EFFECT_AUTO_REPLANT);
        if (replantActive) {
            replantOriginBlock(event, block, type, player.getInventory().getItemInMainHand());
        }
        // 2026-07-27: 範囲収穫にツール判定を追加。従来は**判定が一切無く**、素手でも杖でもピッケルでも
        // 3x3収穫が発動していた。鍬(TFの use-skill: FARMING、または素のバニラの鍬)を要求する。
        // auto-replant は意図的に対象外 — 作物はバニラでも素手で収穫できるものであり、自動再植は
        // 種を1つ差し引く等価交換で悪用の余地が無いため、素手収穫を殺してまで縛る理由がない。
        if (playerData.areaHarvestEnabled()
                && GatheringToolMatcher.matches(player.getInventory().getItemInMainHand(),
                        GatheringToolMatcher.FARMING)) {
            OptionalDouble areaHarvestTier = dedicatedEffects.valueMax(player, EFFECT_AREA_HARVEST);
            if (areaHarvestTier.isPresent()) {
                harvestArea(player, block, replantActive, (int) areaHarvestTier.getAsDouble());
            }
        }
    }

    /** 起点ブロック: バニラdropを止め、種1個分を差し引いたdropを自前で撒いてからage0で再設置予約する。 */
    private void replantOriginBlock(BlockBreakEvent event, Block block, Material type, ItemStack tool) {
        event.setDropItems(false);
        List<DropStack> drops = readDrops(block, tool);
        List<DropStack> adjusted = DropAdjustment.subtractOne(drops, FarmingCropCatalog.seedMaterial(type));
        dropAll(block.getWorld(), block.getLocation(), adjusted);
        scheduleReplant(block.getWorld(), block.getLocation(), type);
    }

    /**
     * 起点周囲の成熟作物を一括収穫する(2026-07-25 gather-rework-active-framework §1: SCALE化。
     * {@code tier} は解放済み最高tierで、{@code area-harvest.tiers} 未定義時は
     * {@code area-harvest.radius} グローバルscalarへ完全後方互換フォールバック)。
     */
    private void harvestArea(Player player, Block origin, boolean replantActive, int tier) {
        processingAreaHarvest = true;
        try {
            ItemStack tool = player.getInventory().getItemInMainHand();
            World world = origin.getWorld();
            int harvested = 0;
            for (Offset offset : AreaHarvestPolicy.squareOffsets(gimmickConfig.areaHarvestRadius(tier))) {
                Block neighbor = world.getBlockAt(
                        origin.getX() + offset.dx(), origin.getY(), origin.getZ() + offset.dz());
                Material neighborType = neighbor.getType();
                if (!FarmingCropCatalog.isCrop(neighborType) || !isMature(neighbor)) {
                    continue;
                }
                harvestNeighbor(neighbor, neighborType, tool, replantActive);
                harvested++;
            }
            if (harvested > 0) {
                // 2026-07-25 §2 B-1: 発動フィードバック(控えめなactionbar)。
                feedback.subtle(player, "範囲収穫 x" + harvested);
            }
        } finally {
            processingAreaHarvest = false;
        }
    }

    private void harvestNeighbor(Block block, Material type, ItemStack tool, boolean replantActive) {
        if (!replantActive) {
            // breakNaturally はBlockBreakEventを発火しないため、area-harvestの再帰的な再誘発は起きない。
            block.breakNaturally(tool);
            return;
        }
        List<DropStack> drops = readDrops(block, tool);
        List<DropStack> adjusted = DropAdjustment.subtractOne(drops, FarmingCropCatalog.seedMaterial(type));
        World world = block.getWorld();
        Location location = block.getLocation();
        // setType もイベントを発火しない(breakNaturallyと同じ理由でarea-harvestの再帰は起きない)。
        block.setType(Material.AIR);
        dropAll(world, location, adjusted);
        scheduleReplant(world, location, type);
    }

    private static boolean isMature(Block block) {
        BlockData data = block.getBlockData();
        return data instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge();
    }

    private static List<DropStack> readDrops(Block block, ItemStack tool) {
        List<DropStack> drops = new ArrayList<>();
        for (ItemStack stack : block.getDrops(tool)) {
            drops.add(new DropStack(stack.getType(), stack.getAmount()));
        }
        return drops;
    }

    private static void dropAll(World world, Location location, List<DropStack> drops) {
        for (DropStack drop : drops) {
            world.dropItemNaturally(location, new ItemStack(drop.material(), drop.amount()));
        }
    }

    /**
     * 1tick後、その場が(誰にも上書きされず)まだ空気のままなら age0 の同じ作物を再設置する
     * ({@link MiningGimmickListener#onBlockBreak}の怪しいブロック復活処理と同じ「空気のみ上書き」ガード)。
     */
    private void scheduleReplant(World world, Location location, Material cropType) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Block current = world.getBlockAt(location);
            if (current.getType() != Material.AIR) {
                return;
            }
            current.setType(cropType);
            if (current.getBlockData() instanceof Ageable ageable) {
                ageable.setAge(0);
                current.setBlockData(ageable);
            }
        }, REPLANT_DELAY_TICKS);
    }
}
