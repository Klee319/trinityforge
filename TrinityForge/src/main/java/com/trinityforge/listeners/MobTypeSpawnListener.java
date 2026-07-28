package com.trinityforge.listeners;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DamageType;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.config.domains.MobTypesConfig;
import com.trinityforge.mobs.MobLevelCoefficients;
import com.trinityforge.mobs.MobTransformCarryOver;
import com.trinityforge.mobs.MobLevelScaling;
import com.trinityforge.mobs.MobStatScaling;
import com.trinityforge.mobs.MobTypeDefinition;
import com.trinityforge.pdc.MobData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * combat/mob-types.yml の定義をバニラモブのスポーン時に適用する。
 * effective = base + coefficient * effectiveLevel
 *
 * <p>mob-types に無い EntityType は {@code defaults:}（基準戦闘レベル・座標係数・防御・HP・係数）を
 * タグ付き定義と同じ式でスケールして刻印する。defaults が全てゼロで HP 未設定のときは刻印せず、
 * 戦闘時の {@code defaultDefense} + バニラ防具合算パスを維持する。
 *
 * <p>HP 適用は他プラグインより後に走らせ、1tick 後にも再適用する。以前の
 * {@code setHealth(min(target, attr.getValue()))} は setBaseValue 直後に古い getValue()
 * （バニラ20など）へ現在HPを戻し、「スポーン時点ですでに削れている」「個体ごとにHPが違う」
 * ように見える原因になっていた。
 */
public final class MobTypeSpawnListener implements Listener {

    private static final Logger LOG = Logger.getLogger("TrinityForge");

    /**
     * サーバーの {@code spigot.yml settings.attribute.maxHealth.max} が原因でMAX_HEALTHが無言に
     * 頭打ちされたときの直近の「観測された上限」(検知の重複警告を防ぐレート制限用)。上限値が変わった
     * ときだけ再度WARNINGを出す({@code null} = まだ一度もクランプを観測していない)。
     */
    private static volatile Double lastWarnedHealthCeiling = null;

    private final Plugin plugin;
    private final MobTypesConfig mobTypesConfig;

    public MobTypeSpawnListener(Plugin plugin, MobTypesConfig mobTypesConfig) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mobTypesConfig = Objects.requireNonNull(mobTypesConfig, "mobTypesConfig");
    }

    /**
     * MONITOR: 他プラグインのスポーン後処理のあとに適用する。
     * 個別 EntityType 定義がある場合はダンジョンtheme付きを除き必ず適用する
     * （先に MOB_LEVEL だけ付いたケースで defaults や他プラグインHPに負けるのを防ぐ）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        MobData data = MobData.of(entity);
        Optional<MobTypeDefinition> maybeDef = mobTypesConfig.definition(entity.getType());

        // 2026-07-29: 変身(ゾンビ→ドラウンド等)由来のスポーンなら、変身前のHP割合を引き継ぐ。
        // 未記録(通常のスポーン)なら 1.0 = 従来どおり満タン。早期returnする経路でも
        // 必ず consume するため、ここで先に取り出しておく(保留マップに滞留させない)。
        double healthRatio = MobTransformCarryOver.consumeHealthRatio(entity.getUniqueId());

        if (maybeDef.isPresent()) {
            if (data.dungeonTheme().isPresent()) {
                return;
            }
            MobTypeDefinition def = maybeDef.get();
            applyScaledProfile(entity, def.level(), def.coordinateCoefficient(),
                    def.physical(), def.magical(), def.maxHealth(),
                    def.attack(), def.levelCoefficients(), "mob-types." + entity.getType().name(),
                    healthRatio);
            return;
        }

        if (data.hasProfile()) {
            return;
        }
        applyUntaggedDefaults(entity, healthRatio);
    }

    private void applyUntaggedDefaults(LivingEntity entity, double healthRatio) {
        int baseLevel = mobTypesConfig.defaultLevel();
        double coordinateCoefficient = mobTypesConfig.defaultCoordinateCoefficient();
        DefenseStats physicalBase = mobTypesConfig.defaultDefense(DamageType.PHYSICAL);
        DefenseStats magicalBase = mobTypesConfig.defaultDefense(DamageType.MAGICAL);
        Double maxHealthBase = mobTypesConfig.defaultMaxHealth().isPresent()
                ? mobTypesConfig.defaultMaxHealth().getAsDouble() : null;
        MobLevelCoefficients coeffs = mobTypesConfig.defaultLevelCoefficients();

        boolean hasLeveling = baseLevel > 0 || coordinateCoefficient != 0.0;
        boolean hasDefense = !isZeroDefense(physicalBase) || !isZeroDefense(magicalBase);
        boolean hasScaling = !isZeroCoeffs(coeffs);
        if (maxHealthBase == null && !hasLeveling && !hasDefense && !hasScaling) {
            return;
        }
        applyScaledProfile(entity, baseLevel, coordinateCoefficient,
                physicalBase, magicalBase, maxHealthBase,
                mobTypesConfig.defaultAttack(), coeffs, "defaults", healthRatio);
    }

    private void applyScaledProfile(LivingEntity entity, int baseLevel, double coordinateCoefficient,
                                    DefenseStats physicalBase, DefenseStats magicalBase,
                                    Double maxHealthBase,
                                    AttackStats attackBase,
                                    MobLevelCoefficients coeffs,
                                    String source,
                                    double healthRatio) {
        double distance = distanceFromWorldSpawn(entity);
        // CMB-21: clamp to mob-types.yml's configured max-level (default 100) so distant mobs cannot
        // scale to an unbounded level (which saturates penetration and makes defense stats moot).
        int level = MobLevelScaling.effectiveLevel(
                baseLevel, coordinateCoefficient, distance, mobTypesConfig.maxLevel());
        double armorBase = physicalBase.armorStrength();
        DefenseStats physical = MobStatScaling.scaleDefense(
                physicalBase, coeffs.physical(), armorBase, coeffs.armorStrength(), level);
        DefenseStats magical = MobStatScaling.scaleDefense(
                magicalBase, coeffs.magical(), armorBase, coeffs.armorStrength(), level);
        AttackStats scaledAttack = MobStatScaling.scaleAttack(attackBase, coeffs.attack(), level);
        MobData.stampMobType(entity, level, physical, magical);
        if (hasConfiguredAttack(attackBase, coeffs.attack())) {
            MobData.stampAttack(entity, scaledAttack);
        }
        syncVanillaArmorIcons(entity, physical.armorStrength());

        Double appliedMaxHealth = null;
        if (maxHealthBase != null) {
            appliedMaxHealth = MobStatScaling.scaleMaxHealth(
                    maxHealthBase, coeffs.maxHealth(),
                    coeffs.maxHealthGrowth(), coeffs.maxHealthGrowthInterval(), level);
            applyMaxHealth(entity, appliedMaxHealth, healthRatio);
            scheduleHealthReassert(entity, appliedMaxHealth, healthRatio, physical.armorStrength());
        }

        String msg = "[mob-types] spawn "
                + entity.getType().name()
                + " via " + source
                + " effectiveLevel=" + level
                + " distance=" + String.format(Locale.ROOT, "%.1f", distance)
                + " armorStrength=" + String.format(Locale.ROOT, "%.3f", physical.armorStrength())
                + (appliedMaxHealth != null
                    ? (" maxHealth=" + String.format(Locale.ROOT, "%.1f", appliedMaxHealth))
                    : " maxHealth=(vanilla)")
                + " currentHealth=" + String.format(Locale.ROOT, "%.1f", entity.getHealth());
        // Per-spawn diagnostics only — keep off the default INFO console (too noisy on busy worlds).
        LOG.fine(msg);
    }

    /**
     * Other plugins often adjust MAX_HEALTH on the same spawn tick. Re-assert next tick so the
     * configured value wins and current HP stays full.
     */
    private void scheduleHealthReassert(LivingEntity entity, double maxHealth, double healthRatio,
                                        double armorStrength) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!entity.isValid() || entity.isDead()) {
                return;
            }
            applyMaxHealth(entity, maxHealth, healthRatio);
            syncVanillaArmorIcons(entity, armorStrength);
        });
    }

    private static boolean isZeroDefense(DefenseStats stats) {
        return stats.defenseRate() == 0.0
                && stats.resistance() == 0.0
                && stats.damageReduction() == 0.0
                && stats.flatDefense() == 0.0
                && stats.armorStrength() == 0.0;
    }

    private static boolean isZeroCoeffs(MobLevelCoefficients coeffs) {
        return coeffs.maxHealth() == 0.0
                && coeffs.armorStrength() == 0.0
                && isZeroDefenseCoeffs(coeffs.physical())
                && isZeroDefenseCoeffs(coeffs.magical());
    }

    private static boolean isZeroDefenseCoeffs(MobLevelCoefficients.DefenseCoeffs c) {
        return c.defenseRate() == 0.0
                && c.resistance() == 0.0
                && c.damageReduction() == 0.0
                && c.flatDefense() == 0.0;
    }

    /** パッケージプライベート(CMB-20テスト用: 他プラグイン相当のmodifierが残ることを直接検証する)。 */
    void applyMaxHealth(LivingEntity entity, double maxHealth) {
        applyMaxHealth(entity, maxHealth, 1.0);
    }

    /**
     * {@code healthRatio} は「最大HPのうちどれだけ現在HPとして入れるか」[0,1]。
     * 通常スポーンは 1.0(満タン)。変身由来のスポーンだけが変身前の割合を持ち込む —
     * 削ったゾンビを水に落とすだけで全回復させないため(2026-07-29)。
     */
    void applyMaxHealth(LivingEntity entity, double maxHealth, double healthRatio) {
        AttributeInstance attr = entity.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) {
            return;
        }
        double value = Math.max(1.0, maxHealth);
        // CMB-20: only drop TF's own transient MAX_HEALTH modifiers so getValue()/display match the
        // configured base. Removing every modifier unconditionally (previous behaviour) also stripped
        // EliteMobs'/other plugins' modifiers on the SAME spawn tick, silently discarding their HP
        // buffs. Reuses PerkAttributeApplier#clearOwnModifiers' identification rule (own NamespacedKey
        // namespace == this plugin) rather than inventing a new one.
        for (AttributeModifier modifier : List.copyOf(attr.getModifiers())) {
            if (isOwnModifier(modifier)) {
                attr.removeModifier(modifier);
            }
        }
        attr.setBaseValue(value);
        // Always fill to the intended max. Never clamp to a stale getValue() (was vanilla 20 etc.).
        // 変身由来のスポーンのときだけ healthRatio < 1 になり、削られた分を引き継ぐ。
        double target = Math.max(1.0, Math.min(value, value * clampRatio(healthRatio)));
        try {
            entity.setHealth(target);
        } catch (IllegalArgumentException ex) {
            // entity.setHealth(value) rejected value because attr.getValue() (the server-enforced
            // ceiling, e.g. spigot.yml settings.attribute.maxHealth.max) is lower than the requested
            // max health. This server currently raises that ceiling to Double.MAX_VALUE, so this path
            // should never trigger in normal operation — if it does, high-level mob HP is silently
            // collapsing to the ceiling with no other symptom, which is why we warn (rate-limited).
            double ceiling = attr.getValue();
            warnHealthCeilingClamp(value, ceiling);
            entity.setHealth(Math.max(1.0, Math.min(target, ceiling)));
        }
    }

    /** 不正値(NaN/負/1超)を [0,1] へ丸める。未指定相当の値は安全側(満タン)へ。 */
    private static double clampRatio(double ratio) {
        if (!Double.isFinite(ratio)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    /**
     * サーバーのMAX_HEALTH上限クランプを検知したときに1回だけ(=上限値が変わるまでは再度出さない)
     * WARNINGを出す。毎spawnで出すとログが埋まるためレート制限する。
     *
     * <p>パッケージプライベート(テスト用)。MockBukkitの{@code LivingEntityMock#setHealth}は実サーバーと
     * 異なり例外を投げず{@code Math.min(value, getMaxHealth())}へ静かにクランプするため、
     * {@link #applyMaxHealth}のcatch経路自体はMockBukkit経由のイベント発火では再現できない
     * (MockBukkit回避策)。そのためこのレート制限ロジック自体を単体で直接検証する。
     *
     * @return 実際にWARNINGを出した場合は{@code true}(テストのアサーション用)。
     */
    static boolean warnHealthCeilingClamp(double requested, double ceiling) {
        if (ceiling >= requested - 0.01) {
            return false; // not a meaningful clamp
        }
        Double previous = lastWarnedHealthCeiling;
        if (previous != null && Math.abs(previous - ceiling) < 0.01) {
            return false; // same ceiling already warned about
        }
        lastWarnedHealthCeiling = ceiling;
        LOG.warning(String.format(Locale.ROOT,
                "[mob-types] MAX_HEALTH clamped by server ceiling: requested=%.1f achieved=%.1f "
                        + "-- check spigot.yml settings.attribute.maxHealth.max (mob HP silently caps "
                        + "here if that ceiling is lower than configured mob-types values).",
                requested, ceiling));
        return true;
    }

    /** テスト専用リセット(レート制限用の静的状態をテスト間で隔離する)。 */
    static void resetHealthCeilingWarningStateForTests() {
        lastWarnedHealthCeiling = null;
    }

    /**
     * Aligns vanilla {@link Attribute#ARMOR} with stamped 防具強度 for HUD icons. Combat for
     * stamped mobs already reads PDC (not vanilla armor), so this is display-only.
     */
    /** パッケージプライベート(CMB-20テスト用)。 */
    void syncVanillaArmorIcons(LivingEntity entity, double armorStrength) {
        // CMB-20: same fix as applyMaxHealth — only remove TF-owned modifiers, never other plugins'.
        AttributeInstance armor = entity.getAttribute(Attribute.ARMOR);
        if (armor != null) {
            for (AttributeModifier modifier : List.copyOf(armor.getModifiers())) {
                if (isOwnModifier(modifier)) {
                    armor.removeModifier(modifier);
                }
            }
            armor.setBaseValue(Math.max(0.0, Math.floor(armorStrength)));
        }
        AttributeInstance toughness = entity.getAttribute(Attribute.ARMOR_TOUGHNESS);
        if (toughness != null) {
            for (AttributeModifier modifier : List.copyOf(toughness.getModifiers())) {
                if (isOwnModifier(modifier)) {
                    toughness.removeModifier(modifier);
                }
            }
            toughness.setBaseValue(0.0);
        }
    }

    /**
     * CMB-20: identifies a TF-owned attribute modifier by NamespacedKey namespace, reusing the same
     * rule as {@code PerkAttributeApplier#clearOwnModifiers} (namespace == this plugin's own). This
     * class does not currently stamp any of its own {@link AttributeModifier}s onto mob attributes
     * (it only calls {@code setBaseValue}), so today this always evaluates to {@code false} — which is
     * the correct, minimal fix: nothing here is TF's to remove, so nothing should be removed. This
     * keeps the removal loop future-proof if this class ever starts adding its own modifiers.
     */
    boolean isOwnModifier(AttributeModifier modifier) {
        return modifier.getKey().getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT));
    }

    private double distanceFromWorldSpawn(LivingEntity entity) {
        Location spawn = entity.getWorld().getSpawnLocation();
        try {
            return entity.getLocation().distance(spawn);
        } catch (IllegalArgumentException ex) {
            return 0.0;
        }
    }

    private static boolean hasConfiguredAttack(AttackStats base, MobLevelCoefficients.AttackCoeffs coeffs) {
        if (base.defaultDamage() != 0 || base.flatBonusDamage() != 0 || base.percentBonusDamage() != 0
                || base.penetration() != 0 || base.critChance() != 0 || base.critDamage() != 0
                || base.damageModifier() != 1 || base.fixedDamage() != 0) {
            return true;
        }
        return coeffs.attackPower() != 0 || coeffs.flatBonusDamage() != 0
                || coeffs.percentBonusDamage() != 0 || coeffs.penetration() != 0
                || coeffs.critChance() != 0 || coeffs.critDamage() != 0
                || coeffs.damageModifier() != 0 || coeffs.fixedDamage() != 0;
    }
}
