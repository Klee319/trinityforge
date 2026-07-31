package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single resolution seam for item ids that may live in either the TrinityForge catalog
 * ({@code items/catalog.yml}) or the ArsPaper custom-item registry (2026-07-23 stat-gate-overhaul §1
 * 緊急修正1): a handful of catalog ids (gacha tickets, scrap, crystal apple, core materials, compressed
 * blocks, …) were moved to ArsPaper's {@code materials.yml} on 2026-07-21 by an editor "move to
 * materials tab" action — that is the correct, intended home for them, not an editor bug — so any TF
 * consumer that only ever looked at the TF catalog silently broke for those ids. This class shares the
 * exact resolution order {@code GiveItemCommand} already used, so every caller (give command, gacha
 * ticket identification/prize generation, future drop tables) agrees on one precedence instead of each
 * re-implementing its own catalog/Ars/Material fallback chain.
 *
 * <p><b>Creation order</b>: TF catalog ({@link ItemCatalogConfig#template} + {@link ItemFactory}) first,
 * then the ArsPaper item registry ({@link ArsItemGiveBridge#create}), then a bare vanilla
 * {@link Material} fallback. <b>Identification</b> ({@link #idOf}) reads the TF {@code ITEM_CATALOG_ID}
 * PDC tag first, then the ArsPaper {@code arspaper:custom_item_id} PDC tag directly (no ArsPaper class
 * dependency needed — {@link NamespacedKey} + {@link org.bukkit.persistence.PersistentDataContainer} are
 * core Bukkit API), so a ticket/prize identified purely by its Ars PDC tag is still recognized.
 *
 * <p><b>{@code custom:} prefix</b> (2026-07-30): an explicit {@code custom:<id>} token resolves as a
 * custom item only — TF catalog then Ars registry, <b>never</b> the vanilla {@link Material} fallback,
 * so {@code custom:DIAMOND} cannot silently become a diamond. Accepting the prefix here matters because
 * the config editor normalizes every custom pick to {@code custom:<id>}
 * ({@code public/js/util.js} {@code materialInput}), while ten other domains
 * ({@code MobOverridesConfig}, {@code MobLevelTableConfig}, {@code RecipeIngredient},
 * {@code FoodGimmickConfig}, …) strip it locally before they get here. This shared seam did not, so
 * every gacha prize / achievement reward item / drop-table entry written from the editor resolved to
 * nothing at runtime — and because {@code GachaListener} treats an unresolvable prize as "do not consume
 * the ticket", that failure surfaced as free, unlimited re-rolls rather than as an error.
 */
public final class CrossPluginItemResolver {

    /** Ars {@code ItemKeys.CUSTOM_ITEM_ID} — duplicated here (not a compile dependency) since TF only
     *  soft-depends on ArsPaper; the key shape (namespace + name) is a stable cross-plugin contract. */
    private static final NamespacedKey ARS_CUSTOM_ITEM_ID = new NamespacedKey("arspaper", "custom_item_id");

    /** Editor-normalized custom item token prefix, e.g. {@code custom:tf_gacha_ticket_5}. */
    private static final String CUSTOM_PREFIX = "custom:";

    private static final Logger LOG = Logger.getLogger(CrossPluginItemResolver.class.getName());

    private final ItemCatalogConfig itemCatalog;
    private final ItemFactory itemFactory;

    public CrossPluginItemResolver(ItemCatalogConfig itemCatalog, ItemFactory itemFactory) {
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
    }

    /**
     * Builds {@code id} with a random roll seed and quality {@code 0} (unstamped quality is not
     * applicable to Ars/Material items anyway; catalog items with a meaningful quality should use
     * {@link #create(String, long, int)} directly).
     */
    public Optional<ItemStack> create(String id) {
        return create(id, java.util.concurrent.ThreadLocalRandom.current().nextLong(), 0);
    }

    /**
     * Resolves {@code id} to a built {@link ItemStack}: TF catalog (with the given roll seed/quality)
     * first, then ArsPaper's registry (quality not applicable there), then a bare vanilla
     * {@link Material}. Empty when none of the three resolve, or the catalog/Ars build throws.
     */
    public Optional<ItemStack> create(String id, long rollSeed, int quality) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String bare = stripCustomPrefix(id);
        if (bare == null || bare.isBlank()) {
            return Optional.empty();
        }
        Optional<ItemStack> catalog = createCatalog(bare, rollSeed, quality);
        if (catalog.isPresent()) {
            return catalog;
        }
        Optional<ItemStack> ars = createArs(bare);
        if (ars.isPresent()) {
            return ars;
        }
        // An explicit custom: token must never fall through to a vanilla Material.
        return isCustomToken(id) ? Optional.empty() : createMaterial(bare);
    }

    /**
     * Drops a leading {@code custom:} (case-insensitive) and trims. Ids without the prefix are returned
     * unchanged, so callers that already stripped it locally are unaffected (the operation is idempotent).
     */
    private static String stripCustomPrefix(String id) {
        String trimmed = id.trim();
        return isCustomToken(trimmed) ? trimmed.substring(CUSTOM_PREFIX.length()).trim() : trimmed;
    }

    private static boolean isCustomToken(String id) {
        return id != null
                && id.trim().regionMatches(true, 0, CUSTOM_PREFIX, 0, CUSTOM_PREFIX.length());
    }

    /**
     * TF catalog resolution only (no Ars/Material fallback): {@code items/catalog.yml} template built
     * via {@link ItemFactory}. Exposed separately so a caller that needs a specific fallback ORDER
     * (e.g. {@code GiveItemCommand}, which tries Ars first for its own labeling/quality-stamp reasons)
     * can still share this build logic without going through the unified {@link #create} precedence.
     */
    public Optional<ItemStack> createCatalog(String id, long rollSeed, int quality) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        id = stripCustomPrefix(id);
        Optional<ItemTemplate> template = itemCatalog.template(id);
        if (template.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(itemFactory.create(template.get(), rollSeed, quality));
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "[cross-plugin-item] catalog build failed for '" + id + "'", ex);
            return Optional.empty();
        }
    }

    /** ArsPaper registry resolution only (no catalog/Material fallback). Public: see {@link #createCatalog}. */
    public static Optional<ItemStack> createArs(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        id = stripCustomPrefix(id);
        try {
            return ArsItemGiveBridge.create(id);
        } catch (LinkageError | RuntimeException ex) {
            LOG.log(Level.FINE, "[cross-plugin-item] Ars bridge unavailable for '" + id + "'", ex);
            return Optional.empty();
        }
    }

    private static Optional<ItemStack> createMaterial(String id) {
        Material material = Material.matchMaterial(id);
        if (material == null || isAir(material)) {
            return Optional.empty();
        }
        return Optional.of(new ItemStack(material, 1));
    }

    private static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    /** Whether {@code id} resolves via the catalog, Ars registry, or a vanilla Material (in that order). */
    public boolean exists(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String bare = stripCustomPrefix(id);
        if (bare.isBlank()) {
            return false;
        }
        if (itemCatalog.template(bare).isPresent()) {
            return true;
        }
        try {
            if (ArsItemGiveBridge.create(bare).isPresent()) {
                return true;
            }
        } catch (LinkageError | RuntimeException ex) {
            LOG.log(Level.FINE, "[cross-plugin-item] Ars bridge unavailable for '" + bare + "'", ex);
        }
        if (isCustomToken(id)) {
            // Mirrors create(): custom: never means a vanilla Material.
            return false;
        }
        Material material = Material.matchMaterial(bare);
        return material != null && !isAir(material);
    }

    /**
     * Dual-read item identification: the TF {@code ITEM_CATALOG_ID} PDC tag first, then the ArsPaper
     * {@code arspaper:custom_item_id} PDC tag. Empty for an item stamped by neither (vanilla / no meta).
     */
    public static Optional<String> idOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        Optional<String> tfId = ItemData.of(meta).catalogId();
        if (tfId.isPresent()) {
            return tfId;
        }
        return arsIdOf(stack);
    }

    /**
     * ArsPaper 側の刻印だけを読む({@code arspaper:custom_item_id})。TF の catalog PDC は見ない。
     *
     * <p>{@link #idOf} と分けてあるのは、呼び出し側が「TF の品か Ars の品か」で扱いを変える必要が
     * ある場合のため。図鑑({@code CollectionListener})は TF カタログ品を無条件に記録する一方、
     * Ars 側は登録アイテムが300件超(グリフ120件を含む)あるので設定から参照されているIDだけに
     * 絞る必要があり、同じ {@code Optional<String>} では判別できない。
     */
    public static Optional<String> arsIdOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        String arsId = stack.getItemMeta().getPersistentDataContainer()
                .get(ARS_CUSTOM_ITEM_ID, PersistentDataType.STRING);
        return (arsId == null || arsId.isBlank()) ? Optional.empty() : Optional.of(arsId);
    }
}
