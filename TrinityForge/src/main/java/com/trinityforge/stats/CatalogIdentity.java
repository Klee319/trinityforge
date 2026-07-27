package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Restores {@code items/catalog.yml} identity ({@code catalogId}, {@code bindType}) onto an item
 * that matches a template by material + CustomModelData. Shared by craft / pickup / fishing stamp
 * paths so Valhalla or creative stacks without TF identity still get SOULBOUND owner semantics.
 */
public final class CatalogIdentity {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private CatalogIdentity() {
    }

    /**
     * When the stack has no catalog id and/or bind type, copy them from the matching catalog
     * template (if any). No-op when already complete or when no template matches.
     *
     * @return true when meta was modified
     */
    public static boolean ensure(ItemStack stack, ItemCatalogConfig catalog) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(catalog, "catalog");
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        ItemData data = ItemData.of(meta);
        Optional<String> existingCatalogId = data.catalogId();
        boolean adopted = existingCatalogId.isEmpty();
        Optional<ItemTemplate> match = existingCatalogId.flatMap(catalog::template);
        if (match.isEmpty()) {
            Integer cmd = DerivedItemStats.customModelDataOf(meta);
            // Vanilla stacks (no CMD) must not match a catalog template that happens to share the
            // material (e.g. example_sword on DIAMOND_SWORD) — that wrongly SOULBOUNDs every creative
            // /give sword. Catalog identity restore is for custom-model gear (Valhalla/TF CMD items).
            if (cmd == null) {
                return false;
            }
            match = find(catalog, stack.getType(), cmd);
        }
        if (match.isEmpty()) {
            return false;
        }
        ItemTemplate template = match.get();
        boolean changed = false;
        if (data.catalogId().isEmpty()) {
            data.setCatalogId(template.id());
            changed = true;
        }
        if (data.bindType().isEmpty()) {
            data.setBindType(template.bindType());
            changed = true;
        }
        // Valhalla-created results do not pass through ItemFactory.buildIdentity(), so adopting their
        // material+CMD identity must also apply the catalog display name. Once adopted, preserve normal
        // anvil renames; only repair legacy items whose visible name is still an internal Valhalla/id key.
        if (template.displayName() != null
                && (adopted || isMachineGeneratedName(meta.displayName(), template.id()))) {
            Component configuredName = MINI_MESSAGE.deserialize(template.displayName())
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
            if (!Objects.equals(meta.displayName(), configuredName)) {
                meta.displayName(configuredName);
                changed = true;
            }
        }
        if (changed) {
            stack.setItemMeta(meta);
        }
        return changed;
    }

    private static boolean isMachineGeneratedName(Component displayName, String catalogId) {
        if (displayName == null) {
            return true;
        }
        String plain = PLAIN.serialize(displayName).trim().toLowerCase(Locale.ROOT);
        if (plain.isEmpty()) {
            return true;
        }
        if (plain.startsWith("<") && plain.endsWith(">")) {
            plain = plain.substring(1, plain.length() - 1);
        }
        String id = catalogId.toLowerCase(Locale.ROOT);
        String dashed = id.replace('_', '-');
        return plain.equals(id)
                || plain.equals("craft_" + id)
                || plain.equals("material-" + dashed)
                || plain.equals("lang.material-" + dashed);
    }

    /** Reads the catalog id stamped on item meta (PDC), if any. */
    public static Optional<String> catalogIdOf(ItemMeta meta) {
        if (meta == null) {
            return Optional.empty();
        }
        return ItemData.of(meta).catalogId();
    }

    public static Optional<ItemTemplate> find(ItemCatalogConfig catalog, Material material, Integer cmd) {
        for (ItemTemplate template : catalog.all().values()) {
            if (template.material() != material) {
                continue;
            }
            if (Objects.equals(template.customModelData(), cmd)) {
                return Optional.of(template);
            }
        }
        return Optional.empty();
    }
}
