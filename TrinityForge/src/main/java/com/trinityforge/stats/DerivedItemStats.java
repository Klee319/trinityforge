package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.WeaponBaseFormula;
import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The single per-item stat source (ITEM_ECONOMY_SPEC 5.1, fully deterministic): {@code
 * stats/item-stats.yml} ({@link ItemStatsConfig}) is the ONLY stat layer — there is no material-base
 * or rollSeed-driven roll anymore. An item's stats are:
 * <ol>
 *   <li><b>{@code fixed}</b> — the per-item exact values, applied to EVERY matching item (even a
 *       PDC-less vanilla/Valhalla item; a bare {@code MATERIAL} entry IS that vanilla item's stats);
 *       and</li>
 *   <li><b>{@code per-quality}</b> — an additive {@code step * qualityLevel} bonus on top of {@code
 *       fixed}, using the item's own PDC quality (0 when absent); and</li>
 *   <li><b>weapon use-level base formula</b> — ADDS an {@code attack-power} base computed from the item's
 *       use-level for a weapon-category item, summed on top of any directly-authored attack-power (式 + 明示).</li>
 * </ol>
 * Given the same item (material + CustomModelData + quality) this always derives to the exact same
 * map — no rollSeed entropy is consulted. {@code rollSeed} itself remains on the item purely as the
 * "this item is TF-stamped" identity marker ({@link ItemData#hasRollSeed()}, {@code
 * ItemRefreshPolicy}); it plays no role in stat derivation. Result is a fresh mutable map the caller
 * may keep summing into.
 */
public final class DerivedItemStats {

    /** Canonical attack-power key + the stat-category the weapon base formula applies to. */
    private static final String ATTACK_POWER_KEY = StatKeys.canonical("attack-power");
    private static final String WEAPON_CATEGORY = "weapon";

    private DerivedItemStats() {
    }

    /**
     * The item-stats overlay (fixed + per-quality) + (weapon ? use-level base formula) for one item;
     * empty for a null/air item. The {@code weaponBaseFormula} step fills the physical base {@code
     * attack-power} from the item's use-level only for a weapon-category item that no explicit layer
     * already set it (see {@link #applyWeaponBaseFormula}); pass {@link WeaponBaseFormula#disabled()}
     * to skip it entirely.
     */
    public static Map<String, Double> resolve(ItemStack item,
                                              ItemStatsConfig itemStats, WeaponBaseFormula weaponBaseFormula) {
        return resolve(item, itemStats, weaponBaseFormula, null);
    }

    /**
     * Same as {@link #resolve(ItemStack, ItemStatsConfig, WeaponBaseFormula)} with optional
     * {@code threadSlotMaxByCategory} caps from {@code progression/crafting-features.yml}.
     */
    public static Map<String, Double> resolve(ItemStack item,
                                              ItemStatsConfig itemStats, WeaponBaseFormula weaponBaseFormula,
                                              Map<String, Integer> threadSlotMaxByCategory) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (item == null || item.getType().isAir()) {
            return result;
        }

        int qualityLevel = 0;
        int useLevel = 0;
        long rollSeed = 0L;
        Integer customModelData = null;
        CraftRollMods craftRollMods = CraftRollMods.NONE;
        int ritualThreadSlotBonus = 0;
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            ItemData data = ItemData.of(meta);
            useLevel = data.useLevelRequirement().orElse(0);
            qualityLevel = data.quality();
            rollSeed = data.rollSeed().orElse(0L);
            customModelData = customModelDataOf(meta);
            craftRollMods = data.craftRollMods();
            ritualThreadSlotBonus = data.ritualThreadSlotBonus();
        }

        // 解決順: アイテム個別プロファイル → カテゴリ別フォールバック(不足分)。
        // バニラ材質へのフォールバックは実装されていない(過去に VanillaMaterialStats として
        // 存在したが呼び出し元が無く2026-07-25にデッドコードとして削除済み)。
        // カテゴリフォールバックにも値が無いキーは 0 のままになる。
        ItemStatProfile profile = itemStats.profileFor(item.getType(), customModelData).orElse(null);
        Set<String> granted = resolveGrantedKeys(profile, rollSeed);
        applyProfile(result, profile, qualityLevel, granted);
        QualityRollModel effModel = itemStats.rollModel() == null
                ? null : itemStats.rollModel().withCraftMods(craftRollMods);
        applyRandom(result, profile, qualityLevel, rollSeed, effModel, granted);
        mergeAdditive(result, itemStats.fallbackFixedFor(item.getType(), customModelData));

        // Weapon use-level base formula runs LAST and is ADDED on top: a weapon's directly-authored
        // attack-power and its use-level base are summed (式 + 明示), not either/or.
        Set<String> itemCategories = EquipmentSlotResolver.statCategories(item.getType());
        applyWeaponBaseFormula(result, weaponBaseFormula, itemCategories, useLevel);
        ThreadSlotPolicy.applyCraftBonus(result, ritualThreadSlotBonus);
        ThreadSlotPolicy.applyCategoryCap(result, item.getType(), threadSlotMaxByCategory);
        return result;
    }

    /**
     * Resolves the item's 乗算モード contributions: layer id → canonical stat key → {@code v - 1}
     * (the additive share of the player-wide layer sum, where the authored/resolved value {@code v}
     * is the multiplier itself, e.g. 1.2 = x1.2). Value resolution mirrors the additive layers
     * (fixed overwrite + per-quality × quality + deterministic random roll). Empty for a null/air
     * item or a profile with no {@code multipliers} section. Grant gating (advanced) does not apply
     * to multiplier entries.
     */
    public static Map<String, Map<String, Double>> resolveMultipliers(ItemStack item, ItemStatsConfig itemStats) {
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        if (item == null || item.getType().isAir()) {
            return result;
        }
        int qualityLevel = 0;
        long rollSeed = 0L;
        Integer customModelData = null;
        CraftRollMods craftRollMods = CraftRollMods.NONE;
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            ItemData data = ItemData.of(meta);
            qualityLevel = data.quality();
            rollSeed = data.rollSeed().orElse(0L);
            customModelData = customModelDataOf(meta);
            craftRollMods = data.craftRollMods();
        }
        ItemStatProfile profile = itemStats.profileFor(item.getType(), customModelData).orElse(null);
        QualityRollModel effModel = itemStats.rollModel() == null
                ? null : itemStats.rollModel().withCraftMods(craftRollMods);
        return resolveMultipliers(profile, qualityLevel, rollSeed, effModel);
    }

    /** Pure core of {@link #resolveMultipliers(ItemStack, ItemStatsConfig)} for unit tests / lore baking. */
    public static Map<String, Map<String, Double>> resolveMultipliers(ItemStatProfile profile, int qualityLevel,
                                                                      long rollSeed, QualityRollModel rollModel) {
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        if (profile == null || profile.multipliers().isEmpty()) {
            return result;
        }
        profile.multipliers().forEach((layer, spec) -> {
            Map<String, Double> values = new LinkedHashMap<>();
            spec.fixed().forEach(values::put);
            spec.perQuality().forEach((key, step) -> values.merge(key, step * qualityLevel, Double::sum));
            if (rollModel != null) {
                spec.random().forEach((key, range) -> {
                    double z = RollHash.standardNormal(rollSeed, "mult:" + layer + ":" + key);
                    double reach = rollModel.reach(qualityLevel, z);
                    values.merge(key, range.valueAt(reach), Double::sum);
                });
            }
            Map<String, Double> contributions = new LinkedHashMap<>();
            values.forEach((key, v) -> {
                if (v != null && Double.isFinite(v)) {
                    contributions.put(key, v - 1.0);
                }
            });
            if (!contributions.isEmpty()) {
                result.put(layer, contributions);
            }
        });
        return result;
    }

    /**
     * The item-stats overlay (fixed + per-quality) ONLY, for a bare {@code (material,
     * customModelData, qualityLevel)} triple with no live {@link ItemStack} — used by {@link
     * ItemAssembler#assemble}, which only has an {@link ItemMeta} being built (no weapon use-level
     * context is meaningful at assembly time, so the weapon base formula is intentionally not applied
     * here; the combat-facing {@link #resolve} is the path that fills attack-power from use-level).
     *
     * <p>{@code rollSeed} seeds the random roll layer identically to {@link #resolve}: the assembler
     * passes the item's own rollSeed so the LORE it bakes matches what combat later re-derives from the
     * live item (pass {@code 0L} for a seedless context such as thread previews, which carry no
     * {@code random} section anyway).
     */
    public static Map<String, Double> profileStats(Material material, Integer customModelData,
                                                    int qualityLevel, long rollSeed, ItemStatsConfig itemStats) {
        return profileStats(material, customModelData, qualityLevel, rollSeed, itemStats, itemStats.rollModel());
    }

    /**
     * Overload of {@link #profileStats(Material, Integer, int, long, ItemStatsConfig)} that takes an
     * explicit roll model — used by {@link ItemAssembler#assemble} to bake lore with the crafter's
     * stage-2 perk deltas applied ({@code QualityRollModel#withCraftMods}) so the baked lore matches what
     * {@link #resolve} later re-derives from the item's own PDC.
     */
    public static Map<String, Double> profileStats(Material material, Integer customModelData,
                                                    int qualityLevel, long rollSeed, ItemStatsConfig itemStats,
                                                    QualityRollModel rollModelOverride) {
        Map<String, Double> result = new LinkedHashMap<>();
        ItemStatProfile profile = itemStats.profileFor(material, customModelData).orElse(null);
        Set<String> granted = resolveGrantedKeys(profile, rollSeed);
        applyProfile(result, profile, qualityLevel, granted);
        applyRandom(result, profile, qualityLevel, rollSeed, rollModelOverride, granted);
        if (material != null) {
            mergeAdditive(result, itemStats.fallbackFixedFor(material, customModelData));
        }
        return result;
    }

    /** Puts entries from {@code fill} only when {@code result} does not already contain the key. */
    static void fillMissing(Map<String, Double> result, Map<String, Double> fill) {
        if (fill == null || fill.isEmpty()) {
            return;
        }
        fill.forEach((key, value) -> result.putIfAbsent(key, value));
    }

    static void mergeAdditive(Map<String, Double> result, Map<String, Double> additions) {
        if (additions == null) return;
        additions.forEach((key, value) -> {
            if (value != null && Double.isFinite(value)) result.merge(key, value, Double::sum);
        });
    }

    /**
     * ADDS the weapon use-level base {@code attack-power} (via {@code 1 + useLevel^a / b}) on top of
     * whatever the item-stats overlay (fixed / per-quality / random) already contributed — the two are
     * summed (式 + 明示), so a weapon can carry both a directly-authored attack-power AND the use-level
     * base. Runs ONLY when the formula is enabled, the item is a {@code weapon}-category item, and its
     * use-level is positive; armor / catalyst / use-level-less weapons never gain a phantom attack-power,
     * and a disabled formula leaves the map untouched (回帰なし). Pure (no Bukkit {@link ItemStack}) so the
     * conditions are unit-tested directly.
     */
    static void applyWeaponBaseFormula(Map<String, Double> result, WeaponBaseFormula formula,
                                       Set<String> categories, int useLevel) {
        if (!formula.enabled() || useLevel <= 0 || !categories.contains(WEAPON_CATEGORY)) {
            return;
        }
        // 直接指定の attack-power と使用可能Lv式の基礎を both 加算する (以前は明示があれば式をスキップしていた)。
        result.merge(ATTACK_POWER_KEY, formula.baseAttackPower(useLevel), Double::sum);
    }

    /**
     * When {@code advanced.randomize-grants} is on, returns the set of assigned stat keys that pass
     * their per-key grant chance (deterministic via {@link RollHash#unitInterval}). Returns
     * {@code null} when gating is off (all keys granted).
     */
    public static Set<String> resolveGrantedKeys(ItemStatProfile profile, long rollSeed) {
        if (profile == null || !profile.randomizeGrants()) {
            return null;
        }
        Set<String> assigned = new LinkedHashSet<>();
        assigned.addAll(profile.fixed().keySet());
        assigned.addAll(profile.perQuality().keySet());
        assigned.addAll(profile.random().keySet());
        Set<String> granted = new LinkedHashSet<>();
        for (String key : assigned) {
            double chance = profile.grantChance(key);
            if (chance >= 1.0 || RollHash.unitInterval(rollSeed, "grant:" + key) < chance) {
                granted.add(key);
            }
        }
        return granted;
    }

    /**
     * Overlays an item's individual {@link ItemStatProfile} onto the (initially empty) derived map:
     * {@code fixed} is applied unconditionally, OVERWRITING (via {@code put}) any prior value. Then
     * {@code perQuality} ADDS {@code step * qualityLevel} on top of whatever the map holds so far (no
     * rollSeed involved); a quality level of 0 is a no-op, and a stat absent from {@code perQuality} is
     * untouched.
     *
     * <p>Package-private and pure (no Bukkit {@link ItemStack}) so the overwrite semantics are unit
     * tested directly; {@link #resolve} supplies the live map + PDC-derived quality. A null or empty
     * profile leaves the map untouched. Keys on both sides are canonical.
     */
    static void applyProfile(Map<String, Double> result, ItemStatProfile profile, int qualityLevel) {
        applyProfile(result, profile, qualityLevel, null);
    }

    static void applyProfile(Map<String, Double> result, ItemStatProfile profile, int qualityLevel,
                             Set<String> granted) {
        if (profile == null) {
            return;
        }
        profile.fixed().forEach((key, value) -> {
            if (granted == null || granted.contains(key)) {
                result.put(key, value);
            }
        });
        profile.perQuality().forEach((key, step) -> {
            if (granted == null || granted.contains(key)) {
                result.merge(key, step * qualityLevel, Double::sum);
            }
        });
    }

    /**
     * Overlays the item's random roll layer (段2) on top of whatever {@link #applyProfile} left in the
     * map: for each stat listed in the profile's {@code random} section, a deterministic standard-normal
     * draw {@code Z} (seeded by {@code rollSeed} + the stat key via {@link RollHash}) is shaped by the
     * quality-dependent split-normal {@link QualityRollModel} into a reach in {@code [0, 1]}, and
     * {@code min + reach * (max - min)} is ADDED (summed) on top — an undefined base counts as 0. Only
     * listed stats roll; physical and magic stats share this one mechanism.
     *
     * <p>No-op when the profile is null/has no random stats, or when no roll model is wired
     * ({@code rollModel == null}, i.e. {@link ItemStatsConfig#useRollModel} was never called): the roll
     * layer is inert rather than throwing, so a stat's fixed/per-quality base still applies. Package-private
     * and pure (no Bukkit {@link ItemStack}) so the roll math is unit-tested directly. Keys are canonical.
     */
    static void applyRandom(Map<String, Double> result, ItemStatProfile profile, int qualityLevel,
                            long rollSeed, QualityRollModel rollModel) {
        applyRandom(result, profile, qualityLevel, rollSeed, rollModel, null);
    }

    static void applyRandom(Map<String, Double> result, ItemStatProfile profile, int qualityLevel,
                            long rollSeed, QualityRollModel rollModel, Set<String> granted) {
        if (profile == null || rollModel == null || profile.random().isEmpty()) {
            return;
        }
        profile.random().forEach((key, range) -> {
            if (granted != null && !granted.contains(key)) {
                return;
            }
            double z = RollHash.standardNormal(rollSeed, key);
            double reach = rollModel.reach(qualityLevel, z);
            result.merge(key, range.valueAt(reach), Double::sum);
        });
    }

    /**
     * The item's CustomModelData, or {@code null} when unset. {@code setCustomModelData(int)} and its
     * getters are deprecated in favour of the 1.21 component API but remain the simplest stable single-id
     * accessor (matching {@code ItemFactory}); suppressed deliberately.
     */
    @SuppressWarnings("deprecation")
    public static Integer customModelDataOf(ItemMeta meta) {
        return meta.hasCustomModelData() ? meta.getCustomModelData() : null;
    }
}
