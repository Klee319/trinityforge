package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Objects;
import java.util.Optional;

/**
 * Identity boundary used when deciding whether vanilla material behaviour may consume or transform
 * an item declared in {@code items/catalog.yml}.
 *
 * <p>CustomModelData is deliberately mandatory. A stack without CMD is vanilla even when old
 * quality/catalog PDC remains on it; this is the same contract used by catalog workbench matching.
 * Once CMD exists, a known stamped catalog id wins, with material+CMD as the recovery path for
 * identity-only/external construction that has not received PDC yet.
 */
public final class CatalogVanillaOperationPolicy {

    private CatalogVanillaOperationPolicy() {
    }

    public static boolean isCatalogItem(ItemStack stack, ItemCatalogConfig catalog) {
        return catalogIdOf(stack, catalog).isPresent();
    }

    public static Optional<String> catalogIdOf(ItemStack stack, ItemCatalogConfig catalog) {
        Objects.requireNonNull(catalog, "catalog");
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        Integer cmd = DerivedItemStats.customModelDataOf(meta);
        if (cmd == null) {
            return Optional.empty();
        }

        Optional<String> stamped = ItemData.of(meta).catalogId();
        if (stamped.isPresent()) {
            Optional<ItemTemplate> declared = catalog.template(stamped.get());
            if (declared.isPresent() && declared.get().material() == stack.getType()) {
                return stamped;
            }
        }
        return CatalogIdentity.find(catalog, stack.getType(), cmd).map(ItemTemplate::id);
    }
}
