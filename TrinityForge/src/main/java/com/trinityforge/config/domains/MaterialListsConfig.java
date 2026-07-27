package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.stats.MaterialLists;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code items/material-lists.yml}: user-defined material equivalence lists referenced
 * from recipe ingredients as {@code list:<id>} tokens (editor: 素材欄の「互換リスト」ボタン).
 *
 * <pre>
 * lists:
 *   planks:
 *     label: 板材
 *     materials: [OAK_PLANKS, SPRUCE_PLANKS, ...]
 * </pre>
 *
 * Malformed entries are skipped with a warning; the rest load. The parsed snapshot is pushed into
 * the static {@link MaterialLists} holder so ingredient resolution stays lazy and reload-safe.
 * Must be registered BEFORE the item catalog so recipes registered on the same pass see the
 * just-loaded lists.
 */
public final class MaterialListsConfig implements LoadableConfig {

    public static final String PATH = "items/material-lists.yml";
    private static final String ROOT = "lists";

    @Override
    public boolean load(Plugin plugin) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), PATH);
        if (!file.exists()) {
            plugin.saveResource(PATH, false);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の互換リストを維持します: " + ex.getMessage(), ex);
            return false;
        }
        return parseInto(yaml.getConfigurationSection(ROOT), log);
    }

    /** Pure parse of {@code lists:} into the {@link MaterialLists} snapshot. Unit-testable. */
    static boolean parseInto(ConfigurationSection root, Logger log) {
        Map<String, Set<Material>> lists = new LinkedHashMap<>();
        Map<String, Set<String>> customIds = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        int skipped = 0;
        if (root != null) {
            for (String id : root.getKeys(false)) {
                // エディタ(schema.js)は ID を ^[a-z0-9_]+$ に制限している。手書きで逸脱したIDも
                // ゲーム内では有効だが、エディタで保存できなくなるため警告で周知する (読込は続行)。
                if (!id.matches("[a-z0-9_]+")) {
                    log.warning("[" + PATH + "] list '" + id + "': IDは半角英小文字/数字/アンダースコア"
                            + "を推奨します (エディタからは編集できません)");
                }
                ConfigurationSection entry = root.getConfigurationSection(id);
                if (entry == null) {
                    log.warning("[" + PATH + "] list '" + id + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                List<String> names = entry.getStringList("materials");
                Set<Material> members = new LinkedHashSet<>();
                Set<String> custom = new LinkedHashSet<>();
                for (String name : names) {
                    if (name != null && name.regionMatches(true, 0, "custom:", 0, "custom:".length())) {
                        String customId = name.substring("custom:".length()).trim();
                        if (!customId.isEmpty()) { custom.add(customId); continue; }
                    }
                    Material material = name == null ? null : Material.matchMaterial(name.trim());
                    if (material == null) {
                        log.warning("[" + PATH + "] list '" + id + "': unknown material '" + name
                                + "' ignored");
                        continue;
                    }
                    members.add(material);
                }
                if (members.isEmpty() && custom.isEmpty()) {
                    log.warning("[" + PATH + "] list '" + id + "' has no valid materials or custom items; skipped");
                    skipped++;
                    continue;
                }
                lists.put(id, members);
                customIds.put(id, custom);
                String label = entry.getString("label");
                if (label != null && !label.isBlank()) {
                    labels.put(id, label);
                }
            }
        }
        MaterialLists.update(lists, customIds, labels);
        if (skipped > 0) {
            log.warning("[" + PATH + "] loaded " + lists.size() + " list(s), " + skipped + " skipped");
            return false;
        }
        log.info("[" + PATH + "] loaded " + lists.size() + " material list(s) OK");
        return true;
    }
}
