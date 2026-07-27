package com.trinityforge.pdc;

import com.trinityforge.stats.CraftRollMods;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Type-safe view over an item's PersistentDataContainer (ADDON_INTEGRATION_SPEC 6 / SELECTION 5).
 *
 * <p>Item stats are <strong>not</strong> stored here; only the derivation inputs are
 * ({@code rollSeed} + {@code quality}). Concrete stat values are derived on demand by
 * {@code StatDerivation} so a config-table change re-balances existing items (SELECTION 1.4).
 *
 * <p>Mutating methods edit the supplied {@link ItemMeta} in place; the caller is responsible
 * for {@link ItemStack#setItemMeta(ItemMeta)} afterward. Read methods accept a read-only meta.
 */
public final class ItemData {

    /**
     * Absolute PDC sanity bounds for stored quality. This is only a storage ceiling that stops an
     * absurd value from being persisted; the EFFECTIVE gameplay maximum is config-driven (the number of
     * {@code stats/quality-tiers.yml} tiers, or {@code stats/quality.yml max-quality}) via
     * {@code QualityConfig.maxQuality()} and enforced by the quality draw's clamp at derivation time
     * (ITEM_ECONOMY_SPEC 5, Q = 任意段階).
     */
    public static final int MIN_QUALITY = 0;
    public static final int MAX_QUALITY = 100;

    /** Current PDC schema generation stamped onto newly/re-assembled items (item 4: schema
     * versioning foundation only; no migration-on-read logic exists yet). */
    public static final int CURRENT_DATA_VERSION = 1;

    private final PersistentDataContainer container;

    private ItemData(PersistentDataContainer container) {
        this.container = Objects.requireNonNull(container, "container");
    }

    /** Wraps the meta's container. */
    public static ItemData of(ItemMeta meta) {
        return new ItemData(Objects.requireNonNull(meta, "meta").getPersistentDataContainer());
    }

    public boolean hasRollSeed() {
        return container.has(PdcKeys.ITEM_ROLL_SEED, PersistentDataType.LONG);
    }

    public Optional<Long> rollSeed() {
        return Optional.ofNullable(container.get(PdcKeys.ITEM_ROLL_SEED, PersistentDataType.LONG));
    }

    public void setRollSeed(long seed) {
        container.set(PdcKeys.ITEM_ROLL_SEED, PersistentDataType.LONG, seed);
    }

    /** Quality clamped to [{@link #MIN_QUALITY}, {@link #MAX_QUALITY}]; defaults to MIN when absent. */
    public int quality() {
        int raw = container.getOrDefault(PdcKeys.ITEM_QUALITY, PersistentDataType.INTEGER, MIN_QUALITY);
        return Math.max(MIN_QUALITY, Math.min(MAX_QUALITY, raw));
    }

    public void setQuality(int quality) {
        int clamped = Math.max(MIN_QUALITY, Math.min(MAX_QUALITY, quality));
        container.set(PdcKeys.ITEM_QUALITY, PersistentDataType.INTEGER, clamped);
    }

    public Optional<UUID> owner() {
        String raw = container.get(PdcKeys.ITEM_OWNER, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    public void setOwner(UUID owner) {
        container.set(PdcKeys.ITEM_OWNER, PersistentDataType.STRING,
                Objects.requireNonNull(owner, "owner").toString());
    }

    /** Removes the owner stamp (admin clear / OWNER_BOUND reset). */
    public void clearOwner() {
        container.remove(PdcKeys.ITEM_OWNER);
    }

    public Optional<BindType> bindType() {
        return BindType.fromStorage(container.get(PdcKeys.ITEM_BIND_TYPE, PersistentDataType.STRING));
    }

    public void setBindType(BindType bindType) {
        container.set(PdcKeys.ITEM_BIND_TYPE, PersistentDataType.STRING,
                Objects.requireNonNull(bindType, "bindType").storageValue());
    }

    /** Required skill-tree level to use this item; empty when unrestricted (PROGRESSION 1.6). */
    public Optional<Integer> useLevelRequirement() {
        return Optional.ofNullable(
                container.get(PdcKeys.ITEM_USE_LEVEL_REQ, PersistentDataType.INTEGER));
    }

    /** The skill tree the {@link #useLevelRequirement()} is measured against. */
    public Optional<String> useSkill() {
        return Optional.ofNullable(container.get(PdcKeys.ITEM_USE_SKILL, PersistentDataType.STRING));
    }

    public void setUseRequirement(String skill, int level) {
        container.set(PdcKeys.ITEM_USE_SKILL, PersistentDataType.STRING,
                Objects.requireNonNull(skill, "skill"));
        container.set(PdcKeys.ITEM_USE_LEVEL_REQ, PersistentDataType.INTEGER, level);
    }

    /** PDC schema generation this item was last written with; empty for items predating item 4. */
    public Optional<Integer> dataVersion() {
        return Optional.ofNullable(container.get(PdcKeys.ITEM_DATA_VERSION, PersistentDataType.INTEGER));
    }

    public void setDataVersion(int version) {
        container.set(PdcKeys.ITEM_DATA_VERSION, PersistentDataType.INTEGER, version);
    }

    /** The table generation in effect when this item's lore/attributes were last assembled. */
    public Optional<Integer> tableGeneration() {
        return Optional.ofNullable(
                container.get(PdcKeys.ITEM_TABLE_GENERATION, PersistentDataType.INTEGER));
    }

    public void setTableGeneration(int generation) {
        container.set(PdcKeys.ITEM_TABLE_GENERATION, PersistentDataType.INTEGER, generation);
    }

    /**
     * The {@code items/catalog.yml} id this item was created from ({@code ItemFactory.create}), used by
     * {@code ItemAssembler.assemble} to re-resolve the catalog entry's flavor lore on every (re-)assembly.
     * Empty for items not created from a catalog template (e.g. the crafted/fished {@code stamp} path),
     * which simply get no flavor lore.
     */
    public Optional<String> catalogId() {
        String raw = container.get(PdcKeys.ITEM_CATALOG_ID, PersistentDataType.STRING);
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw);
    }

    public void setCatalogId(String catalogId) {
        container.set(PdcKeys.ITEM_CATALOG_ID, PersistentDataType.STRING,
                Objects.requireNonNull(catalogId, "catalogId"));
    }

    /** パーティクルシード合成済みID(2026-07-23-stat-gate-overhaul §6.1)。未合成なら空。 */
    public Optional<String> particleSeed() {
        String raw = container.get(PdcKeys.ITEM_PARTICLE_SEED, PersistentDataType.STRING);
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw);
    }

    public void setParticleSeed(String particleSeedId) {
        container.set(PdcKeys.ITEM_PARTICLE_SEED, PersistentDataType.STRING,
                Objects.requireNonNull(particleSeedId, "particleSeedId"));
    }

    /**
     * The crafter's stage-2 (ステータスロール) perk deltas baked into this item at craft time, or
     * {@link CraftRollMods#NONE} when absent (never crafted with such perks, or a pre-feature item).
     */
    public CraftRollMods craftRollMods() {
        if (!container.has(PdcKeys.ITEM_CRAFT_ROLL_UP, PersistentDataType.DOUBLE)
                && !container.has(PdcKeys.ITEM_CRAFT_ROLL_DOWN_REDUCTION, PersistentDataType.DOUBLE)
                && !container.has(PdcKeys.ITEM_CRAFT_ROLL_INSET_DELTA, PersistentDataType.DOUBLE)) {
            return CraftRollMods.NONE;
        }
        double up = container.getOrDefault(PdcKeys.ITEM_CRAFT_ROLL_UP, PersistentDataType.DOUBLE, 0.0);
        double downReduction = container.getOrDefault(
                PdcKeys.ITEM_CRAFT_ROLL_DOWN_REDUCTION, PersistentDataType.DOUBLE, 0.0);
        double insetDelta = container.getOrDefault(
                PdcKeys.ITEM_CRAFT_ROLL_INSET_DELTA, PersistentDataType.DOUBLE, 0.0);
        return new CraftRollMods(up, downReduction, insetDelta);
    }

    /**
     * Writes {@code mods} to PDC ONLY when non-zero, so a zero-mods craft writes nothing and reads back
     * as {@link CraftRollMods#NONE} (avoids polluting every item's PDC with inert zero values).
     */
    public void setCraftRollMods(CraftRollMods mods) {
        Objects.requireNonNull(mods, "mods");
        if (mods.isZero()) {
            return;
        }
        container.set(PdcKeys.ITEM_CRAFT_ROLL_UP, PersistentDataType.DOUBLE, mods.rollUpBonus());
        container.set(PdcKeys.ITEM_CRAFT_ROLL_DOWN_REDUCTION, PersistentDataType.DOUBLE, mods.rollDownReduction());
        container.set(PdcKeys.ITEM_CRAFT_ROLL_INSET_DELTA, PersistentDataType.DOUBLE, mods.rollInsetDelta());
    }

    /** 儀式(スレッド枠拡張)由来の累計付与枠数; 0 when absent. */
    public int ritualThreadSlotBonus() {
        return Math.max(0, container.getOrDefault(
                PdcKeys.ITEM_RITUAL_THREAD_SLOT_BONUS, PersistentDataType.INTEGER, 0));
    }

    /** Writes only when {@code bonus > 0}; zero clears any prior value. */
    public void setRitualThreadSlotBonus(int bonus) {
        if (bonus <= 0) {
            container.remove(PdcKeys.ITEM_RITUAL_THREAD_SLOT_BONUS);
            return;
        }
        container.set(PdcKeys.ITEM_RITUAL_THREAD_SLOT_BONUS, PersistentDataType.INTEGER, bonus);
    }

    public int coatingStacks() {
        return Math.max(0, container.getOrDefault(
                PdcKeys.ITEM_COATING_STACKS, PersistentDataType.INTEGER, 0));
    }

    public void setCoatingStacks(int stacks) {
        if (stacks <= 0) {
            container.remove(PdcKeys.ITEM_COATING_STACKS);
            return;
        }
        container.set(PdcKeys.ITEM_COATING_STACKS, PersistentDataType.INTEGER, stacks);
    }

    /** Accumulated flat bonus from coating materials; 0 when absent. */
    public double coatingFlatDamage() {
        return Math.max(0.0, container.getOrDefault(
                PdcKeys.ITEM_COATING_FLAT_DAMAGE, PersistentDataType.DOUBLE, 0.0));
    }

    public void setCoatingFlatDamage(double amount) {
        if (amount <= 0.0) {
            container.remove(PdcKeys.ITEM_COATING_FLAT_DAMAGE);
            return;
        }
        container.set(PdcKeys.ITEM_COATING_FLAT_DAMAGE, PersistentDataType.DOUBLE, amount);
    }
}
