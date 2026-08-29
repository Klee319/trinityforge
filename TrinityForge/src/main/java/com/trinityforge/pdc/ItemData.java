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

    /**
     * 品質キーが実際に刻印されているか。{@link #quality()} は欠落時に 0 を返すので、
     * 「劣悪(0)」と「未刻印」を区別するときはこちらを使う。
     */
    public boolean hasQuality() {
        return container.has(PdcKeys.ITEM_QUALITY, PersistentDataType.INTEGER);
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

    /** パーティクルシードの刻印済みID(2026-07-23-stat-gate-overhaul §6.1)。未刻印なら空。 */
    public Optional<String> particleSeed() {
        String raw = container.get(PdcKeys.ITEM_PARTICLE_SEED, PersistentDataType.STRING);
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw);
    }

    public void setParticleSeed(String particleSeedId) {
        container.set(PdcKeys.ITEM_PARTICLE_SEED, PersistentDataType.STRING,
                Objects.requireNonNull(particleSeedId, "particleSeedId"));
    }

    /** 刻印を消す({@code clears: true} のシードで金床にかけたとき。2026-08-25 / W-221)。 */
    public void clearParticleSeed() {
        container.remove(PdcKeys.ITEM_PARTICLE_SEED);
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
     * Writes the current mods. Zero clears any previous keys so a later stamp (厳選の護符など)
     * can drop old ロール運 without leaving stale PDC.
     */
    public void setCraftRollMods(CraftRollMods mods) {
        Objects.requireNonNull(mods, "mods");
        if (mods.isZero()) {
            container.remove(PdcKeys.ITEM_CRAFT_ROLL_UP);
            container.remove(PdcKeys.ITEM_CRAFT_ROLL_DOWN_REDUCTION);
            container.remove(PdcKeys.ITEM_CRAFT_ROLL_INSET_DELTA);
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

    /**
     * このスタックがクリエイティブ由来か(2026-07-31)。詳細と限界は
     * {@link PdcKeys#ITEM_CREATIVE_ORIGIN} の javadoc。absent = false(既存の全アイテム)。
     */
    public boolean creativeOrigin() {
        return container.getOrDefault(
                PdcKeys.ITEM_CREATIVE_ORIGIN, PersistentDataType.BYTE, (byte) 0) != 0;
    }

    /** クリエイティブ由来マーカーを刻む。 */
    public void markCreativeOrigin() {
        container.set(PdcKeys.ITEM_CREATIVE_ORIGIN, PersistentDataType.BYTE, (byte) 1);
    }

    /**
     * クリエイティブ由来マーカーを剥がす(2026-08-01)。
     *
     * <p><b>剥がす経路が必要な理由</b>: 刻む側は「クリエイティブで生成された」ことを完全には
     * 判定できない({@code CollectionListener#onCreativeSet} / {@code onPickup} の javadoc の
     * 「印が誤って付く」節)。印が付いたスタックは図鑑判定から丸ごと外れるので、誤って付くと
     * <b>そのスタックは以後どのサバイバル走査でも永久に図鑑に載らない</b>。エラーも通知も出ない
     * ため、剥がす手段が無いと運用で回復できない。{@code /tf collection unmark} がこれを呼ぶ。
     *
     * @return 実際に印を消したら true(元々付いていなければ false)
     */
    public boolean clearCreativeOrigin() {
        if (!creativeOrigin()) {
            return false;
        }
        container.remove(PdcKeys.ITEM_CREATIVE_ORIGIN);
        return true;
    }

    /**
     * 品質未決定マーカーが立っているか(2026-08-04)。立っている間は品質0・未刻印のまま置かれており、
     * 最初にプレイヤーのインベントリへ入った時点でそのプレイヤーのステータスを参照して刻印される。
     * 経緯と理由は {@link PdcKeys#ITEM_PENDING_CRAFT_QUALITY} の javadoc。absent = false。
     */
    public boolean pendingCraftQuality() {
        return container.getOrDefault(
                PdcKeys.ITEM_PENDING_CRAFT_QUALITY, PersistentDataType.BYTE, (byte) 0) != 0;
    }

    /** 品質未決定マーカーを刻む(儀式クラフトの成果物生成時)。 */
    public void markPendingCraftQuality() {
        container.set(PdcKeys.ITEM_PENDING_CRAFT_QUALITY, PersistentDataType.BYTE, (byte) 1);
    }

    /**
     * 品質未決定マーカーを剥がす(回収者のステータスで品質を確定した後)。
     *
     * @return 実際に印を消したら true(元々付いていなければ false)
     */
    public boolean clearPendingCraftQuality() {
        if (!pendingCraftQuality()) {
            return false;
        }
        container.remove(PdcKeys.ITEM_PENDING_CRAFT_QUALITY);
        return true;
    }
}
