package com.trinityforge.combat;

import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.stats.DerivedItemStats;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;

/**
 * Public API that turns a catalyst/weapon {@link ItemStack} into {@link AttackStats}, reusing the exact
 * path {@code CombatListener} runs for a melee weapon: {@link DerivedItemStats#resolve} (the SOLE,
 * fully deterministic per-item stat source: {@code stats/item-stats.yml} fixed + per-quality, plus the
 * weapon use-level base formula) then {@link AttackStatBridge#bridge} through the configured
 * {@code attack-stat-keys.*} mapping (COMBAT_SYSTEM_SPEC 3.1).
 *
 * <p>Reason it exists: the ArsPaper fork's magical path currently hands {@link AttackStats#plain(0)} to
 * {@link SymmetricCombatService#magicalFinalDamage}, so a wand/spellbook's crit/penetration/... never
 * reach the pipeline. With this resolver the fork can call {@code forItem(catalyst)} and pass the real
 * catalyst stats instead. {@code defaultDamage} is intentionally left at 0 — the caller injects the
 * level-scaled {@code spellBase} downstream, matching {@code magicalFinalDamage}'s contract.
 *
 * <p>Null-safe by contract: a null/AIR/empty item yields {@code plain(0)}, and any runtime failure while
 * reading a malformed item is swallowed back to {@code plain(0)} so a bad stack can never abort a spell.
 */
public final class WeaponAttackStatResolver {

    private final ItemStatsConfig itemStats;
    private final CombatDamageConfig combatDamage;
    private final AttackStatKeys attackStatKeys;
    private final CraftingFeaturesConfig craftingFeatures;

    public WeaponAttackStatResolver(ItemStatsConfig itemStats,
                                    CombatDamageConfig combatDamage, AttackStatKeys attackStatKeys,
                                    CraftingFeaturesConfig craftingFeatures) {
        this.itemStats = Objects.requireNonNull(itemStats, "itemStats");
        this.combatDamage = Objects.requireNonNull(combatDamage, "combatDamage");
        this.attackStatKeys = Objects.requireNonNull(attackStatKeys, "attackStatKeys");
        this.craftingFeatures = Objects.requireNonNull(craftingFeatures, "craftingFeatures");
    }

    /**
     * The attacker-side {@link AttackStats} carried by {@code item}: its derived stat map bridged
     * through the configured attack-stat keys. A null/AIR/empty item — or any item that fails to
     * derive — resolves to {@link AttackStats#plain(0)} (defaultDamage 0; the pipeline injects the
     * level-scaled base). Must be called on the server main thread (Bukkit item reads are synchronous).
     */
    public AttackStats forItem(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return AttackStats.plain(0);
        }
        try {
            Map<String, Double> derived = DerivedItemStats.resolve(
                    item, itemStats, combatDamage.weaponBaseFormula(),
                    craftingFeatures.threadSlotMaxByCategory());
            return AttackStatBridge.bridge(derived, attackStatKeys);
        } catch (RuntimeException malformedItem) {
            return AttackStats.plain(0);
        }
    }

    /**
     * The item's derived {@code attack-power} (the TrinityForge physical base-damage stat), or 0.0 when
     * the item is null/AIR/empty, carries no {@code attack-power}, or fails to derive. Reuses the exact
     * {@link DerivedItemStats} path {@link #forItem} runs (item-stats overlay + weapon base formula), so
     * the value matches what {@code CombatListener} would use as the melee base. Exposed for the ArsPaper
     * magic-hybrid path, which needs a catalyst/weapon's attack-power to fold into a spell's base damage.
     * Must be called on the server main thread (Bukkit item reads are synchronous).
     */
    public double attackPowerOf(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return 0.0;
        }
        try {
            Map<String, Double> derived = DerivedItemStats.resolve(
                    item, itemStats, combatDamage.weaponBaseFormula(),
                    craftingFeatures.threadSlotMaxByCategory());
            return derived.getOrDefault(StatKeys.canonical("attack-power"), 0.0);
        } catch (RuntimeException malformedItem) {
            return 0.0;
        }
    }

    /**
     * The full derived item-stats map (canonical key → value) for a bare {@code (material,
     * customModelData)} at quality 0, with NO weapon base formula — i.e. the {@code fixed} overlay of the
     * matching {@code stats/item-stats.yml} entry ({@code MATERIAL} or {@code MATERIAL#cmd}). Exposed for
     * addons (the ArsPaper fork's armor "thread" stats) that need a socketed item's TF stats from just its
     * material + CustomModelData, with no live {@link ItemStack}: a thread carries no quality, so quality
     * 0 yields exactly the fixed values. Returns a fresh, caller-owned <b>mutable</b> map (empty for a
     * null material, no matching entry, or any derivation failure — fail-open), so a caller may accumulate
     * directly into it. Must be called on the server main thread.
     */
    public Map<String, Double> resolveItemStats(Material material, Integer customModelData) {
        if (material == null) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            // profileStats already returns a fresh mutable LinkedHashMap. Thread previews carry no
            // `random` section and have no rollSeed context, so quality 0 + rollSeed 0L (roll layer no-op).
            return DerivedItemStats.profileStats(material, customModelData, 0, 0L, itemStats);
        } catch (RuntimeException malformedProfile) {
            return new java.util.LinkedHashMap<>();
        }
    }

    /**
     * The FULL derived stat map (canonical-ish keys, as authored) for a live {@link ItemStack}: fixed +
     * per-quality (using the item's OWN PDC quality, unlike {@link #resolveItemStats}) + random roll +
     * weapon use-level base formula. Exposed for Change 1 (P10 magic aggregation, ARSPAPER_FORK_SPEC):
     * the ArsPaper fork merges a catalyst's own resolved stats on top of the caster's non-mainhand
     * equipment before bridging to {@link AttackStats}, and Change 2 (P2-Java, catalyst mana-cost
     * reduction) reads {@code mana-cost-reduction-flat}/{@code -percent} from this same resolution so
     * per-quality/random rolls are honoured. Null/AIR/empty/derivation-failure all fail-open to an empty
     * mutable map. Must be called on the server main thread (Bukkit item reads are synchronous).
     */
    public Map<String, Double> resolveFull(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            return DerivedItemStats.resolve(item, itemStats, combatDamage.weaponBaseFormula());
        } catch (RuntimeException malformedItem) {
            return new java.util.LinkedHashMap<>();
        }
    }

    /**
     * Bridges an already-assembled derived stat map (e.g. a caller-merged sum of several stat sources)
     * through the configured {@code attack-stat-keys} mapping, exactly like {@link #forItem} does
     * internally for a single item. Exposed for Change 1 (P10): the ArsPaper fork merges the caster's
     * non-mainhand equipment stats with a catalyst's own resolved stats into one map, then calls this to
     * produce the final {@link AttackStats} (defaultDamage left at 0; the caller injects the level-scaled
     * base afterward, matching {@code magicalFinalDamage}'s contract).
     */
    public AttackStats bridgeStats(Map<String, Double> derivedStats) {
        return AttackStatBridge.bridge(derivedStats, attackStatKeys);
    }

    private static final String ITEM_COOLDOWN_KEY = StatKeys.canonical("item-cooldown");

    /**
     * Longest cooldown TF will set (1 hour) — mirrors {@code CombatListener}'s
     * {@code ITEM_COOLDOWN_MAX_TICKS} clamp, exposed as the single shared ceiling for any caller
     * (TF or fork) that starts its own {@code Player#setCooldown}, so an absurd rolled/authored value
     * can never overflow the int tick count.
     */
    public static final long ITEM_COOLDOWN_MAX_TICKS = 72_000L;

    /**
     * The item's derived {@code item-cooldown} in seconds (0.0 when absent/null/AIR/derivation
     * failure). Exposed for Change 3 (P9): the ArsPaper fork starts the catalyst's item-cooldown gauge
     * on a successful catalyst cast, mirroring the melee weapon-CT resolution
     * ({@code CombatListener.meleeWeaponOnCooldown}/{@code maybeStartItemCooldown}) without duplicating
     * the {@link DerivedItemStats} call. Must be called on the server main thread.
     */
    public double itemCooldownSeconds(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return 0.0;
        }
        try {
            Map<String, Double> derived = DerivedItemStats.resolve(
                    item, itemStats, combatDamage.weaponBaseFormula(),
                    craftingFeatures.threadSlotMaxByCategory());
            return derived.getOrDefault(ITEM_COOLDOWN_KEY, 0.0);
        } catch (RuntimeException malformedItem) {
            return 0.0;
        }
    }

    /**
     * Converts a cooldown in seconds to ticks (× 20), clamped to {@link #ITEM_COOLDOWN_MAX_TICKS} so a
     * caller's {@code Player#setCooldown} call can never overflow/exceed the shared ceiling.
     */
    public static long cooldownTicksFor(double seconds) {
        return Math.min(Math.round(seconds * 20.0), ITEM_COOLDOWN_MAX_TICKS);
    }
}
