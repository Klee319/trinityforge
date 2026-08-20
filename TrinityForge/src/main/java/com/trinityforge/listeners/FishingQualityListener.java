package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.FishingGimmickConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.stats.CatalogIdentity;
import com.trinityforge.stats.CraftQualityPolicy;
import com.trinityforge.stats.DerivedItemStats;
import com.trinityforge.stats.GatheringPolicy;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.MaterialTier;
import com.trinityforge.stats.PlayerLootLuckSource;
import com.trinityforge.stats.StatKeys;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 釣果品質の刻印: 宝運({@code loot_luck})に応じて釣果「装備」の品質modeを決め、
 * split-normalでランダム抽選して刻印する(ITEM_ECONOMY_SPEC 5.2d/5.2h)。品質基準値(quality-mode-offset,
 * §6.6適用拡大)をアイテム単位で加算する。ロッドの fishing-bonus ステ + FISHING スキルLvに応じた期待値
 * ぶん、釣果が「非装備」のときだけ追加ドロップを与える(装備の複製を避けるため)。
 * 追加分は釣果本体と同じくプレイヤーのインベントリへ入り、入り切らない分だけ足元へこぼれる(W-166)。
 *
 * <p><strong>ランタイム検証必須</strong>: {@code Item#setItemStack} による釣果差し替え、および
 * {@link org.bukkit.World#dropItemNaturally} による追加ドロップスポーンは実サーバーでの動作確認が必要。
 */
public final class FishingQualityListener implements Listener {

    private static final String FISHING_BONUS_KEY = StatKeys.canonical("fishing-bonus");
    /** T4(2026-07-25): 釣り位置が海洋系バイオームのときだけ fishing-bonus の期待値へ加算される追加分。 */
    private static final String OCEAN_FISHING_BONUS_KEY = StatKeys.canonical("ocean_fishing_bonus");

    private final ItemFactory itemFactory;
    private final QualityConfig quality;
    private final FishingGimmickConfig fishingGimmick;
    private final SkillLevelSource skillLevelSource;
    private final ItemCatalogConfig itemCatalog;
    private final PlayerLootLuckSource lootLuck;
    private final PlayerStatAggregator aggregator;
    private final ItemStatsConfig itemStats;
    private final NamespacedKey treasureFlagKey;

    /**
     * ArsPaper スレッドの再刻印経路。既定は {@link PickupQualityListener#defaultArsThreadRestamp}
     * (拾得経路とまったく同じ実装を共有する — 別実装にすると片方だけ lore 体裁が壊れる)。
     */
    private final PickupQualityListener.ArsThreadQualityRestamper arsThreadRestamper;

    public FishingQualityListener(ItemFactory itemFactory,
                                  QualityConfig quality, FishingGimmickConfig fishingGimmick,
                                  SkillLevelSource skillLevelSource,
                                  ItemCatalogConfig itemCatalog, PlayerLootLuckSource lootLuck,
                                  PlayerStatAggregator aggregator, ItemStatsConfig itemStats,
                                  NamespacedKey treasureFlagKey) {
        this(itemFactory, quality, fishingGimmick, skillLevelSource, itemCatalog, lootLuck,
                aggregator, itemStats, treasureFlagKey, PickupQualityListener::defaultArsThreadRestamp);
    }

    /** テスト用: ArsPaper 非搭載でもスレッド経路を検証できるよう再刻印を差し替える。 */
    FishingQualityListener(ItemFactory itemFactory,
                           QualityConfig quality, FishingGimmickConfig fishingGimmick,
                           SkillLevelSource skillLevelSource,
                           ItemCatalogConfig itemCatalog, PlayerLootLuckSource lootLuck,
                           PlayerStatAggregator aggregator, ItemStatsConfig itemStats,
                           NamespacedKey treasureFlagKey,
                           PickupQualityListener.ArsThreadQualityRestamper arsThreadRestamper) {
        this.arsThreadRestamper = Objects.requireNonNull(arsThreadRestamper, "arsThreadRestamper");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
        this.quality = Objects.requireNonNull(quality, "quality");
        this.fishingGimmick = Objects.requireNonNull(fishingGimmick, "fishingGimmick");
        this.skillLevelSource = Objects.requireNonNull(skillLevelSource, "skillLevelSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.lootLuck = Objects.requireNonNull(lootLuck, "lootLuck");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.itemStats = Objects.requireNonNull(itemStats, "itemStats");
        // Nullable: a caller that never wires FishingGimmickListener (e.g. a unit test) has no flag to
        // read, so the treasure-dupe-guard simply falls back to the legacy treasureMaterials() list.
        this.treasureFlagKey = treasureFlagKey;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        ItemStack caughtStack = caught.getItemStack();
        if (caughtStack == null || caughtStack.getType().isAir()) {
            return;
        }

        ItemStack rod = resolveRod(player.getInventory());
        // ロッドがオフハンド側にある場合はスロット単位でオフハンド合算を除外する(rod がメインハンド寄与
        // として一度数えられるため。参照比較はライブサーバーではスロット読み取りごとの新ミラーで一致しない)。
        boolean rodFromOffhand = player.getInventory().getItemInMainHand().getType() != Material.FISHING_ROD
                && rod.getType() == Material.FISHING_ROD;
        // fishing-bonus は「総合ステータス」扱い: ロッド単体ではなく、防具4部位 + (設定により)オフハンド +
        // パーク + スレッド等アドオンまで全チャネルを加算し、乗算レイヤも適用する。
        PlayerCombatAggregate agg = aggregator.aggregate(player, rod, rodFromOffhand);
        int fishingLevel = skillLevelSource.levelsOf(player.getUniqueId())
                .getOrDefault(fishingGimmick.fishingSkillId(), 0);

        boolean isEquipment = MaterialTier.of(caughtStack.getType()).isEquipment();
        boolean alreadyStamped = caughtStack.hasItemMeta() && ItemData.of(caughtStack.getItemMeta()).hasRollSeed();

        if (isStampableCatch(caughtStack, isEquipment) && !alreadyStamped) {
            stampCaughtEquipment(caught, caughtStack, player);
        }

        // 追加ドロップの分岐は「バニラ装備を釣ったか」のまま据え置く(複製回避が目的で、品質刻印とは別の関心事)。
        // ここまで isStampableCatch へ広げると、ステータス付きの非装備TF品を釣ったときに
        // fishing-bonus の追加ドロップが黙って消える。
        if (!isEquipment) {
            dropFishingBonus(caught, player, agg, fishingLevel, caughtStack.getType());
        }
    }

    /**
     * 釣果に品質を刻んでよいか。
     *
     * <p><b>2026-08-18 の修正</b>: 以前は {@link MaterialTier#isEquipment()} 単独で判定していた。
     * これは {@code CraftQualityListener#isStampableCraftResult} が 2026-08-04 に塞いだのと同じ穴で、
     * <b>ベース素材がバニラ装備でない TF 品(広辞苑・杖・触媒、そして ArsPaper のスレッド)は
     * {@code item-stats.yml} に品質で変動する層を持っていても品質ロールに一度も到達しない</b>。
     * 現時点では釣果テーブルにこれらが登場しないため実害は出ていないが、
     * 1 件でも追加された瞬間に「常に品質0で釣れる」が再発する。
     *
     * <p>バニラの魚・棒・糸などを巻き込まないのは、{@code item-stats.yml} に
     * {@code MATERIAL#CMD} のプロファイルが無ければ {@code qualityApplies} が偽になるため。
     */
    private boolean isStampableCatch(ItemStack caughtStack, boolean isEquipment) {
        if (isEquipment) {
            return true;
        }
        ItemMeta meta = caughtStack.hasItemMeta() ? caughtStack.getItemMeta() : null;
        if (meta != null && PickupQualityListener.hasArsThreadMarker(meta)) {
            return true;
        }
        Integer cmd = meta != null ? DerivedItemStats.customModelDataOf(meta) : null;
        return itemStats.profileFor(caughtStack.getType(), cmd)
                .filter(com.trinityforge.stats.ItemStatProfile::qualityApplies)
                .isPresent();
    }

    private void stampCaughtEquipment(Item caught, ItemStack caughtStack, Player fisher) {
        Integer cmd = caughtStack.hasItemMeta()
                ? DerivedItemStats.customModelDataOf(caughtStack.getItemMeta()) : null;
        // Canonical fishing.luck-per-quality was 1. PlayerLootLuckSource preserves that +1 mode per
        // whole loot-luck point and applies the existing expected-value roll to a fractional remainder.
        int mode = quality.fishingBaseQuality()
                + lootLuck.qualityModeBonus(fisher, ThreadLocalRandom.current())
                + itemStats.qualityModeOffsetFor(caughtStack.getType(), cmd);
        int rolled = CraftQualityPolicy.resolveQualityNormal(mode, ThreadLocalRandom.current().nextGaussian(),
                quality.spreadUp(), quality.spreadDown(), quality.maxQuality());

        ItemStack stamped = caughtStack.clone();
        // ArsPaper のスレッドは lore がスレッド専用体裁(効果説明/スロット案内/バックパック行)なので、
        // 汎用 ItemFactory#stamp(lore 全体を組み直す)へ絶対に流さない。再刻印に失敗したら
        // 無刻印のまま諦める(lore を壊すより良い)。拾得経路 PickupQualityListener と同じ判断。
        ItemMeta stampedMeta = stamped.getItemMeta();
        if (stampedMeta != null && PickupQualityListener.hasArsThreadMarker(stampedMeta)) {
            if (arsThreadRestamper.restampIfThread(stamped, rolled)) {
                caught.setItemStack(stamped);
            }
            return;
        }
        long seed = ThreadLocalRandom.current().nextLong();
        itemFactory.stamp(stamped, seed, rolled);
        CatalogIdentity.ensure(stamped, itemCatalog);
        stampOwnerIfSoulbound(stamped, fisher, seed);
        caught.setItemStack(stamped);
    }

    /** First fisher auto-binds SOULBOUND catches (parity with pickup / craft). */
    private void stampOwnerIfSoulbound(ItemStack stamped, Player fisher, long seed) {
        ItemMeta meta = stamped.getItemMeta();
        if (meta == null) {
            return;
        }
        ItemData data = ItemData.of(meta);
        if (data.owner().isPresent()) {
            return;
        }
        Optional<BindType> bindType = data.bindType();
        if (bindType.isEmpty() || !bindType.get().autoStampsOwner()) {
            return;
        }
        data.setOwner(fisher.getUniqueId());
        stamped.setItemMeta(meta);
        itemFactory.stamp(stamped, seed, data.quality());
    }

    /**
     * Dupe fix (トレジャー複製): a non-equipment catch that is treasure must never go through the
     * fishing-bonus expected-extra-copy roll meant for common fish/junk. Treasure-ness is read first from
     * {@link FishingGimmickListener}'s per-catch PDC flag (drop-table mode: {@code true} = drawn from the
     * treasure group); when that flag is absent (drop tables not configured — legacy fallback mode), the
     * old {@code fishingGimmick.treasureMaterials()} list membership is used instead.
     */
    private void dropFishingBonus(Item caught, Player player, PlayerCombatAggregate agg, int fishingLevel,
                                  Material caughtMaterial) {
        if (isTreasureCatch(caught, caughtMaterial)) {
            return;
        }
        double bonusExpected = agg.totalOf(FISHING_BONUS_KEY)
                + fishingLevel * fishingGimmick.bonusPerLevel();
        // T4(2026-07-25): 海洋系バイオームで釣ったときだけ ocean_fishing_bonus を追加加算する
        // (バイオーム判定を新設。fishing-gimmick.yml の fishing.ocean-biomes で構成可能)。
        // caught.getLocation() はテスト用モックエンティティ等でnullを返し得るため防御的にnullチェックする。
        org.bukkit.Location catchLocation = caught.getLocation();
        if (catchLocation != null && fishingGimmick.isOceanBiome(catchLocation.getBlock().getBiome())) {
            bonusExpected += agg.totalOf(OCEAN_FISHING_BONUS_KEY);
        }
        int extra = GatheringPolicy.expectedExtra(bonusExpected, ThreadLocalRandom.current().nextDouble());
        if (extra <= 0) {
            return;
        }
        ItemStack template = caught.getItemStack();
        int bounded = Math.min(extra, GatheringPolicy.MAX_EXTRA);
        // W-166(2026-08-20): 以前はここで地面へスポーンさせていたが、釣果本体は
        // バニラの挙動でプレイヤーへ飛んでいく(=インベントリに入る)ため、
        // ボーナス分だけ足元に散らばって「インベントリに入らない」と見えていた。
        // 本体と揃えてインベントリへ入れ、入り切らない分だけ地面へこぼす。
        for (int i = 0; i < bounded; i++) {
            giveOrDrop(player, template.clone());
        }
    }

    /** インベントリへ入れ、入り切らなかった分だけ足元へこぼす。 */
    private static void giveOrDrop(Player player, ItemStack stack) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (ItemStack remainder : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), remainder);
        }
    }

    private boolean isTreasureCatch(Item caught, Material caughtMaterial) {
        if (treasureFlagKey != null
                && caught.getPersistentDataContainer().has(treasureFlagKey, PersistentDataType.BOOLEAN)) {
            return Boolean.TRUE.equals(
                    caught.getPersistentDataContainer().get(treasureFlagKey, PersistentDataType.BOOLEAN));
        }
        return fishingGimmick.treasureMaterials().contains(caughtMaterial);
    }

    /**
     * The rod stack for stat resolution: the hand actually holding a {@link Material#FISHING_ROD} — main
     * hand preferred, off hand used only when main hand isn't the rod (a player can fish while holding
     * the rod off-hand with a tool/torch/etc. in the main hand).
     */
    private static ItemStack resolveRod(PlayerInventory inventory) {
        ItemStack mainHand = inventory.getItemInMainHand();
        if (mainHand.getType() == Material.FISHING_ROD) {
            return mainHand;
        }
        ItemStack offHand = inventory.getItemInOffHand();
        if (offHand.getType() == Material.FISHING_ROD) {
            return offHand;
        }
        return mainHand;
    }
}
