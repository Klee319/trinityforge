package com.trinityforge.listeners;

import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FarmingGimmickConfig;
import com.trinityforge.farming.AreaHarvestPolicy;
import com.trinityforge.farming.AreaHarvestPolicy.Offset;
import com.trinityforge.farming.DropAdjustment;
import com.trinityforge.farming.DropAdjustment.DropStack;
import com.trinityforge.farming.FarmingCropCatalog;
import com.trinityforge.gathering.ChainBreakExpGrant;
import com.trinityforge.gathering.ChainBreakSupport;
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
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * 農業スキルツリーのflag系dedicated-effect consumer群({@code stats/farming-gimmick.yml}でチューニング):
 *
 * <ul>
 *   <li>{@code auto-replant}(flag): 成熟作物を破壊または右クリックした際、drop計算から種/苗1個分を
 *       差し引いた上で同ブロックをage0で再設置する(=「植え直しと収穫が同時」)。</li>
 *   <li>{@code area-harvest}(flag): 起点の周囲({@code area-harvest.radius}、既定3x3)の成熟作物も
 *       一括収穫する。{@code auto-replant}も保有していれば同様に植え直す。</li>
 * </ul>
 *
 * <p>破壊時は成熟作物の{@link BlockBreakEvent}をトリガーとする「1リスナーに複数flag effectを
 * まとめる」様式({@link TreeFellingListener}と同様)。右クリック収穫も保護・drop・EXP互換のため
 * 認可用BlockBreakEventを発火する。周囲ブロックの処理は{@link Block#setType}を直接呼ぶだけで
 * BlockBreakEventを再発火しないため、area-harvestが area-harvestを連鎖的に再誘発することはない
 * (加えて{@link #processingAreaHarvest}で多重ガード)。
 *
 * <p>2026-07-31 G1 round2 指摘7: 範囲収穫の1マスは<b>ルートテーブルを1回だけ引く</b>
 * ({@link #harvestNeighbor} 参照)。旧実装は EXP 用と実ドロップ用で別々に引いていた。
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

    /**
     * 自動で植え直した作物を「そのプレイヤーが植えたもの」として下流へ知らせるフック
     * (既定は何もしない)。{@link PlantedCropGrowthListener#trackPlanted} を繋ぐ。
     *
     * <p><b>2026-08-17 (ユーザー報告「自動植えつけの作物と自分で植えた作物で成長速度が違う」)</b>:
     * 自動植え直しは {@code setType}/{@code setBlockData} で直接置くため
     * {@code BlockPlaceEvent} が発火せず、成長ボーナスの所有権登録から漏れていた。
     */
    private java.util.function.BiConsumer<Block, Player> onCropReplanted = (block, player) -> { };

    /** 植え直し通知先を差し込む(配線は TrinityForge#registerListeners)。 */
    public void setOnCropReplanted(java.util.function.BiConsumer<Block, Player> hook) {
        this.onCropReplanted = hook == null ? (block, player) -> { } : hook;
    }

    /** 右クリック収穫の認可用に合成したBlockBreakEventを、自身で通常破壊として二重処理しないためのガード。 */
    private boolean authorizingRightClickHarvest = false;

    /** 範囲収穫分の採取EXP付与口(2026-07-28)。null 可 — 旧4引数コンストラクタ経由では EXP のみ入らない。 */
    private final ChainBreakExpGrant chainBreakExp;

    /** @deprecated 範囲収穫分のEXPが入らない旧配線。5引数版を使うこと。 */
    @Deprecated
    public FarmingHarvestListener(Plugin plugin, DedicatedEffectsConfig dedicatedEffects,
                                   FarmingGimmickConfig gimmickConfig, FeedbackLayer feedback) {
        this(plugin, dedicatedEffects, gimmickConfig, feedback, null);
    }

    public FarmingHarvestListener(Plugin plugin, DedicatedEffectsConfig dedicatedEffects,
                                   FarmingGimmickConfig gimmickConfig, FeedbackLayer feedback,
                                   ChainBreakExpGrant chainBreakExp) {
        this.chainBreakExp = chainBreakExp;
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (processingAreaHarvest || authorizingRightClickHarvest) {
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

    /**
     * {@code auto-replant}: 成熟作物への右クリックを、その場での収穫＋age0への再植栽として処理する。
     *
     * <p>保護・追加drop・通常破壊EXPとの互換性を保つため、直接worldを変更する前に認可用の
     * {@link BlockBreakEvent} を発火する。自身のblock-break handlerだけはガードで飛ばし、他の
     * リスナーがキャンセルしなかった場合に限って基本dropと再植栽をここで完了する。元の
     * {@link PlayerInteractEvent} はキャンセルするため、
     * {@link NativeSkillExperienceListener#onFarmingInteract(PlayerInteractEvent)} の
     * {@code MONITOR + ignoreCancelled} 経路とのEXP二重付与も起きない。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        Material type = block.getType();
        if (!FarmingCropCatalog.isCrop(type) || !isMature(block)) {
            return;
        }
        Player player = event.getPlayer();
        if (!PlayerData.of(player).autoReplantEnabled()
                || !dedicatedEffects.isActive(player, EFFECT_AUTO_REPLANT)) {
            return;
        }

        event.setCancelled(true);
        BlockBreakEvent harvestEvent = new BlockBreakEvent(block, player);
        authorizingRightClickHarvest = true;
        try {
            Bukkit.getPluginManager().callEvent(harvestEvent);
        } finally {
            authorizingRightClickHarvest = false;
        }
        if (harvestEvent.isCancelled() || block.getType() != type || !isMature(block)) {
            return;
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (harvestEvent.isDropItems()) {
            List<DropStack> drops = readDrops(block, tool);
            List<DropStack> adjusted = DropAdjustment.subtractOne(
                    drops, FarmingCropCatalog.seedMaterial(type));
            dropAll(block.getWorld(), block.getLocation(), adjusted);
        }
        Ageable replanted = (Ageable) block.getBlockData();
        replanted.setAge(0);
        block.setBlockData(replanted);
        onCropReplanted.accept(block, player);
    }

    /** 起点ブロック: バニラdropを止め、種1個分を差し引いたdropを自前で撒いてからage0で再設置予約する。 */
    private void replantOriginBlock(BlockBreakEvent event, Block block, Material type, ItemStack tool) {
        event.setDropItems(false);
        List<DropStack> drops = readDrops(block, tool);
        List<DropStack> adjusted = DropAdjustment.subtractOne(drops, FarmingCropCatalog.seedMaterial(type));
        dropAll(block.getWorld(), block.getLocation(), adjusted);
        scheduleReplant(block.getWorld(), block.getLocation(), type, event.getPlayer());
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
                // 2026-07-28: 範囲収穫分も BlockBreakEvent が飛ばないため農業EXPが入っていなかった
                // (一括伐採/一括破壊と同じ欠落)。破壊前に付与すること。作物は硬度0なのでバニラでも
                // 鍬の耐久は減らない — ここで耐久を消費しないのは意図的。
                // 2026-07-31 G1 round2 指摘7: ルートテーブルの抽選はこの1回だけ。引いた結果を
                // そのまま harvestNeighbor へ渡す(旧実装は EXP 用と実ドロップ用で別々に引いていたので、
                // 確率ドロップだと「EXPの根拠」と「手に入る物」が食い違い、抽選コストも2倍だった)。
                Collection<ItemStack> rolled =
                        ChainBreakSupport.grantExpFor(chainBreakExp, player, neighbor, tool);
                harvestNeighbor(neighbor, neighborType, tool, replantActive, rolled, player);
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

    /**
     * 範囲収穫の1マス分。<b>{@code rolled} は呼び出し側が既に引いた抽選結果</b>で、ここで引き直さない
     * (2026-07-31 G1 round2 指摘7)。旧実装は自動再植なしの経路で {@code breakNaturally(tool)} を、
     * 自動再植ありの経路で {@code readDrops} をそれぞれ呼んでいたので、どちらも EXP 用の抽選とは
     * <em>別の2回目</em>になっていた。両経路を {@code setType(AIR)} + 自前の散布へ寄せて1回化する
     * ({@link com.trinityforge.gathering.ChainBreakSupport} の連鎖破壊と同じ形)。
     *
     * <p>{@code setType} も {@code breakNaturally} と同じく {@code BlockBreakEvent} を発火しないので、
     * area-harvest が area-harvest を再誘発することは無い(旧実装の性質そのまま)。
     */
    private void harvestNeighbor(Block block, Material type, ItemStack tool, boolean replantActive,
                                 Collection<ItemStack> rolled, Player harvester) {
        List<DropStack> drops = toDropStacks(rolled);
        if (replantActive) {
            drops = DropAdjustment.subtractOne(drops, FarmingCropCatalog.seedMaterial(type));
        }
        World world = block.getWorld();
        Location location = block.getLocation();
        block.setType(Material.AIR);
        dropAll(world, location, drops);
        if (replantActive) {
            scheduleReplant(world, location, type, harvester);
        }
    }

    private static boolean isMature(Block block) {
        BlockData data = block.getBlockData();
        return data instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge();
    }

    private static List<DropStack> readDrops(Block block, ItemStack tool) {
        return toDropStacks(block.getDrops(tool));
    }

    /** 抽選結果を {@link DropStack} 列へ写す。空/AIRのスタックは落とさない。 */
    private static List<DropStack> toDropStacks(Collection<ItemStack> stacks) {
        List<DropStack> drops = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack != null && stack.getType() != Material.AIR && stack.getAmount() > 0) {
                drops.add(new DropStack(stack.getType(), stack.getAmount()));
            }
        }
        return drops;
    }

    private static void dropAll(World world, Location location, List<DropStack> drops) {
        if (!ChainBreakSupport.tileDropsEnabled(world)) {
            // 2026-07-31 G1 round2 指摘4: setType + dropItemNaturally の経路は doTileDrops を
            // 自分で見ないとバニラ(breakNaturally 経由)と食い違う。false のサーバでは湧かせない。
            return;
        }
        for (DropStack drop : drops) {
            world.dropItemNaturally(location, new ItemStack(drop.material(), drop.amount()));
        }
    }

    /**
     * 1tick後、その場が(誰にも上書きされず)まだ空気のままなら age0 の同じ作物を再設置する
     * ({@link MiningGimmickListener#onBlockBreak}の怪しいブロック復活処理と同じ「空気のみ上書き」ガード)。
     */
    private void scheduleReplant(World world, Location location, Material cropType, Player planter) {
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
            // 実際に置けたときだけ通知する(空気でなくて諦めた場合は所有権を作らない)。
            onCropReplanted.accept(current, planter);
        }, REPLANT_DELAY_TICKS);
    }
}
