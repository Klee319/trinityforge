package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.MobTypesConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.mobs.MobDropEntry;
import com.trinityforge.mobs.MobDropRoller;
import com.trinityforge.mobs.MobTypeDefinition;
import com.trinityforge.pdc.MobData;
import com.trinityforge.stats.CraftQualityPolicy;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.MaterialTier;
import com.trinityforge.stats.PlayerMobDropBonusSource;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * combat/mob-types.yml で定義されたEntityTypeのバニラモブが死亡した際、その{@code drops:}一覧に従って
 * 追加ドロップを生成するリスナー。対象条件は「死亡entityのEntityTypeがmob-typesに定義済み」であること
 * (PDCのMOB_LEVEL有無は問わない)。装備品には {@link ItemFactory#stamp} でrollSeed+品質を刻印する。
 * 既存ドロップは一切削除せず、{@code event.getDrops()}へ追加するのみ。
 *
 * <p>追加テーブルはプレイヤーが倒した場合のみ抽選する。溶岩・落下・モブ同士の戦闘など
 * {@link LivingEntity#getKiller()} がnullの死亡では何も追加しない。また、AFK対策の
 * {@link #setDropGate(java.util.function.Predicate)} がkillerを抑止した場合も、バニラドロップには
 * 触れず追加分だけを止める。
 */
public final class MobTypeDropListener implements Listener {

    private final MobTypesConfig mobTypesConfig;
    private final CraftQualityConfig craftQuality;
    private final QualityConfig quality;
    private final ItemFactory itemFactory;
    private final PlayerMobDropBonusSource mobDropBonus;
    // 品質基準値 (item-stats quality-mode-offset) の参照元。null可 (テスト/未配線時はオフセット0)。
    private final ItemStatsConfig itemStats;
    private final SplittableRandom random;
    private volatile java.util.function.Predicate<Player> dropGate;

    public MobTypeDropListener(MobTypesConfig mobTypesConfig, CraftQualityConfig craftQuality,
                                QualityConfig quality, ItemFactory itemFactory) {
        this(mobTypesConfig, craftQuality, quality, itemFactory, null, null, new SplittableRandom());
    }

    public MobTypeDropListener(MobTypesConfig mobTypesConfig, CraftQualityConfig craftQuality,
                                QualityConfig quality, ItemFactory itemFactory,
                                PlayerMobDropBonusSource mobDropBonus) {
        this(mobTypesConfig, craftQuality, quality, itemFactory, mobDropBonus, null, new SplittableRandom());
    }

    public MobTypeDropListener(MobTypesConfig mobTypesConfig, CraftQualityConfig craftQuality,
                                QualityConfig quality, ItemFactory itemFactory,
                                PlayerMobDropBonusSource mobDropBonus, ItemStatsConfig itemStats) {
        this(mobTypesConfig, craftQuality, quality, itemFactory, mobDropBonus, itemStats, new SplittableRandom());
    }

    /** Package-visible ctor for tests that need a deterministic random source. */
    MobTypeDropListener(MobTypesConfig mobTypesConfig, CraftQualityConfig craftQuality,
                         QualityConfig quality, ItemFactory itemFactory,
                         PlayerMobDropBonusSource mobDropBonus, ItemStatsConfig itemStats, SplittableRandom random) {
        this.mobTypesConfig = Objects.requireNonNull(mobTypesConfig, "mobTypesConfig");
        this.craftQuality = Objects.requireNonNull(craftQuality, "craftQuality");
        this.quality = Objects.requireNonNull(quality, "quality");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
        this.mobDropBonus = mobDropBonus; // nullable: 未配線時はボーナス0
        this.itemStats = itemStats; // nullable: 未配線時は品質基準値オフセット0
        this.random = Objects.requireNonNull(random, "random");
    }

    /** TF追加ドロップの抑止述語を設定する。nullで無効化。 */
    public void setDropGate(java.util.function.Predicate<Player> gate) {
        this.dropGate = gate;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null || isGated(killer)) {
            return;
        }
        MobData mobData = MobData.of(entity);
        if (!mobData.isMobTypeStamped()) {
            // Not a mob-types spawn: either an EliteMobs/dungeon mob (shares MOB_LEVEL but never
            // this marker) or a vanilla mob that existed before the plugin/config loaded. Neither
            // should receive mob-types drop tables or the equipment-quality stamp.
            return;
        }
        Optional<MobTypeDefinition> maybeDef = mobTypesConfig.definition(entity.getType());
        if (maybeDef.isEmpty()) {
            return;
        }
        MobTypeDefinition def = maybeDef.get();
        int mobLevel = mobData.level();
        // mobドロップボーナス(power_mobdropbonus_add): killerの合計値ぶん品質modeを底上げする。
        // 幸運と同機構(整数分+1、端数は確率的)だが、幸運は意図的にmobドロップへ効かないため別stat。
        int bonusMode = mobDropBonus == null ? 0
                : mobDropBonus.qualityModeBonus(killer, random.nextDouble());

        for (MobDropEntry drop : def.drops()) {
            if (!MobDropRoller.rolls(drop.chance(), random.nextDouble())) {
                continue;
            }
            int count = MobDropRoller.rollCount(drop.min(), drop.max(), random.nextInt());
            ItemStack stack = new ItemStack(drop.material(), count);
            if (MaterialTier.of(drop.material()).isEquipment()) {
                int qualityValue = drop.quality() != null ? drop.quality()
                        : resolveQuality(mobLevel, bonusMode, drop.material());
                itemFactory.stamp(stack, random.nextLong(), qualityValue);
            }
            event.getDrops().add(stack);
        }
    }

    /** AFK判定側の一時障害で通常プレイのドロップまで失わないよう、述語失敗時は付与を継続する。 */
    private boolean isGated(Player killer) {
        java.util.function.Predicate<Player> gate = this.dropGate;
        if (gate == null) {
            return false;
        }
        try {
            return gate.test(killer);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Mob-level-driven quality: same split-normal draw as crafted/fished equipment (5.2d), plus the
     * dropped material's 品質基準値 (item-stats {@code quality-mode-offset}, 2026-07-23
     * stat-gate-overhaul §6.6 適用拡大). The drop is a plain (no CMD) material, so only the base
     * {@code MATERIAL} offset entry can ever apply here.
     */
    private int resolveQuality(int mobLevel, int bonusMode, org.bukkit.Material material) {
        if (!craftQuality.dropEnabled()) {
            return random.nextInt(Math.max(0, quality.maxQuality()) + 1);
        }
        int qualityModeOffset = itemStats == null ? 0 : itemStats.qualityModeOffsetFor(material, null);
        int mode = CraftQualityPolicy.modeFromLevel(mobLevel, craftQuality.dropStrengthPerQuality(),
                craftQuality.dropBaseQuality()) + Math.max(0, bonusMode) + qualityModeOffset;
        double gaussianSample = random.nextGaussian();
        return CraftQualityPolicy.resolveDropQuality(mode, gaussianSample, quality.spreadUp(),
                quality.spreadDown(), quality.maxQuality());
    }
}
