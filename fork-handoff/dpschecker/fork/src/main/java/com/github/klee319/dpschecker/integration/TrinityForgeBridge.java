package com.github.klee319.dpschecker.integration;

import com.github.klee319.dpschecker.dummy.DummyDefenseProfile;
import com.trinityforge.TrinityForge;
import com.trinityforge.combat.DamageType;
import com.trinityforge.combat.DefenseStatBridge;
import com.trinityforge.combat.DefenseStatKeys;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.VanillaArmorMapping;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.pdc.MobData;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.Optional;

/**
 * Syncs the dummy's TrinityForge defender profile into mob PDC so
 * {@code SymmetricCombatService} applies the configured mitigation.
 *
 * <p>実効防御は2層 (#6):
 * <ul>
 *   <li>手動プロファイル ({@link DummyDefenseProfile}) — TF防御GUIで編集する基準値。</li>
 *   <li>装備由来 — ダミーにTF防具を着せると、その防具の item-stats から算出した防御を<b>優先</b>して
 *       stampする(プレイヤーが着た時と同じ防御)。防具を外すと自動で手動プロファイルへ復帰。</li>
 * </ul>
 * 装備由来の算出はプレイヤー防御と<b>同一ロジック</b>を共有する: TF公開API
 * {@code PlayerStatAggregator.equipmentDefenseItemStats}(防具4部位 + メインハンド折込 + オフハンドゲート
 * + 乗算レイヤ)で item 防御ステを導出 → {@code DefenseStatBridge.bridge}/{@code dodgeChance} でブリッジし、
 * 防御率%(bridgeは常に0)は vanilla armor 属性ミラー({@code VanillaArmorMapping.toDefense})で補って
 * {@code combine}(プレイヤー防御の二重計上ガードと同じ構造)。
 *
 * <p>TF pipeline notes (COMBAT_SYSTEM_SPEC §2.1 + LD-13, current):
 * <ul>
 *   <li>{@code flatDefense}(守備力) — flat subtraction (step 6), offset-able by attacker 固定ダメージ.
 *       {@code armorStrength}(防具強度) is a CRIT-REDUCTION RATE [0,1] at step 2, not a step-6 flat</li>
 *   <li>Only 耐性% is typed (physical/magical); 防御率%・被ダメ軽減%・守備力・防具強度・回避 are
 *       type-independent, so the stamped physical/magical components differ only in resistance</li>
 * </ul>
 */
public final class TrinityForgeBridge {

    private static final String PLUGIN_NAME = "TrinityForge";
    private static final double EPS = 1.0e-9;

    private TrinityForgeBridge() {
    }

    public static boolean isAvailable() {
        try {
            return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) != null
                    && TrinityForge.getInstance() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static DefenseStats mapVanillaArmor(double armorPoints, double toughnessPoints) {
        if (!isAvailable()) {
            return DefenseStats.NONE;
        }
        CombatDamageConfig config = TrinityForge.getInstance().config().combatDamage();
        return VanillaArmorMapping.toDefense(
                armorPoints,
                toughnessPoints,
                config.vanillaArmorDefenseRatePerPoint(),
                config.vanillaArmorDefenseRateMax(),
                config.vanillaArmorStrengthPerPoint());
    }

    /**
     * 装備中のTF防具があればその item-stats 由来の防御を、無ければ手動プロファイルを stamp する。
     *
     * @return true = 装備由来を適用 / false = 手動プロファイル(または TF 不在で未適用)
     */
    public static boolean syncEquipmentOrProfile(LivingEntity entity, DummyDefenseProfile manual) {
        if (!isAvailable() || entity == null) {
            return false;
        }
        Optional<DerivedDefense> derived = deriveFromEquipment(entity);
        if (derived.isPresent()) {
            DerivedDefense d = derived.get();
            MobData.stamp(entity, 0, d.physical(), d.magical());
            entity.getPersistentDataContainer().set(
                    PdcKeys.MOB_DODGE_CHANCE, PersistentDataType.DOUBLE, d.dodge());
            return true;
        }
        syncDefenseProfile(entity, manual);
        return false;
    }

    /** 装備にTF防具由来の防御があるか判定し、あれば物理/魔法DefenseStats＋回避を返す。 */
    private static Optional<DerivedDefense> deriveFromEquipment(LivingEntity entity) {
        EntityEquipment eq = entity.getEquipment();
        if (eq == null) {
            return Optional.empty();
        }
        ConfigManager cfg = TrinityForge.getInstance().config();
        ItemStatsConfig itemStats = cfg.itemStats();
        CombatDamageConfig dmg = cfg.combatDamage();
        DefenseStatKeys keys = dmg.defenseStatKeys();

        // プレイヤーが同じ装備を着けた時と完全に同一ロジックで item 防御ステを導出する:
        // TFの公開API PlayerStatAggregator.equipmentDefenseItemStats が
        // 防具4部位 + メインハンド折込 + オフハンドゲート(offhand-stats-apply) + 乗算レイヤ適用を担う。
        // DerivedItemStats.resolve は不正アイテムで例外を投げうるため捕捉し、失敗時は手動値へ委ねる。
        Map<String, Double> merged;
        try {
            ItemStack[] armor = {eq.getHelmet(), eq.getChestplate(), eq.getLeggings(), eq.getBoots()};
            merged = PlayerStatAggregator.equipmentDefenseItemStats(
                    armor, eq.getItemInMainHand(), eq.getItemInOffHand(), itemStats, dmg);
        } catch (RuntimeException malformedItem) {
            return Optional.empty();
        }

        DefenseStats phys = DefenseStatBridge.bridge(merged, keys, DamageType.PHYSICAL);
        DefenseStats magic = DefenseStatBridge.bridge(merged, keys, DamageType.MAGICAL);
        double dodge = DefenseStatBridge.dodgeChance(merged, keys);

        // 2026-08-15: 防具値ステ(armor-defense-rate)を廃止し防御率(defense-rate)へ一本化したので、
        // bridge は防御率を0ではなく実値で返すようになった。ここでバニラ防具アトリビュート
        // (armor/toughness)をミラーして足すのは、TF未スタンプの素のバニラ防具のぶんを拾うため
        // (TFスタンプ装備は AttributeApplier が Attribute.ARMOR を常に0にするのでミラーは0＝
        // 二重計上にならない)。TFのプレイヤー防御と同じ合成規則。
        DefenseStats mirror = vanillaMirror(entity, dmg);
        phys = phys.combine(mirror);
        magic = magic.combine(mirror);

        if (isZero(phys) && isZero(magic) && dodge <= EPS) {
            return Optional.empty(); // 装備に防御由来なし → 手動プロファイルへ委ねる
        }
        return Optional.of(new DerivedDefense(phys, magic, dodge));
    }

    private static DefenseStats vanillaMirror(LivingEntity entity, CombatDamageConfig dmg) {
        double armor = attributeValue(entity, Attribute.ARMOR);
        double toughness = attributeValue(entity, Attribute.ARMOR_TOUGHNESS);
        if (armor <= EPS && toughness <= EPS) {
            return DefenseStats.NONE;
        }
        return VanillaArmorMapping.toDefense(
                armor, toughness,
                dmg.vanillaArmorDefenseRatePerPoint(),
                dmg.vanillaArmorDefenseRateMax(),
                dmg.vanillaArmorStrengthPerPoint());
    }

    private static double attributeValue(LivingEntity entity, Attribute attribute) {
        AttributeInstance inst = entity.getAttribute(attribute);
        return inst == null ? 0.0 : inst.getValue();
    }

    private static boolean isZero(DefenseStats s) {
        return Math.abs(s.defenseRate()) < EPS
                && Math.abs(s.resistance()) < EPS
                && Math.abs(s.damageReduction()) < EPS
                && Math.abs(s.flatDefense()) < EPS
                && Math.abs(s.armorStrength()) < EPS;
    }

    public static void syncDefenseProfile(PersistentDataHolder holder, DummyDefenseProfile profile) {
        if (!isAvailable() || holder == null || profile == null) {
            return;
        }

        DummyDefenseProfile normalized = profile.normalizedForPipeline();
        MobData.stamp(holder, 0, normalized.physical(), normalized.magical());
        holder.getPersistentDataContainer().set(
                PdcKeys.MOB_DODGE_CHANCE,
                PersistentDataType.DOUBLE,
                normalized.dodgeChance());
    }

    /** 装備由来の実効防御(物理/魔法DefenseStats＋回避)。 */
    private record DerivedDefense(DefenseStats physical, DefenseStats magical, double dodge) {
    }
}
