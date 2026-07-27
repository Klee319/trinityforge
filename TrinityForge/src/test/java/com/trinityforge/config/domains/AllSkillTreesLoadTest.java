package com.trinityforge.config.domains;

import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.stats.StatVocabulary;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Permanent regression gate over <b>all 16</b> shipped {@code skilltree/*.yml} trees (SKILL_TREE design
 * sections A–E). Fresh-eyes QA: rather than trust the loader or other phases, this verifies the canonical
 * data itself, both through the real {@link SkillTreeConfig} loader and independently against the raw YAML
 * (Bukkit engine), and lists every violation with its file and node.
 *
 * <p>Checks: (1) all 16 files load into 16 distinct trees whose {@code skill} id matches the expected set
 * and whose node count is &gt; 0, with a clean (warning-free) loader pass; (2) every {@code parent} resolves
 * inside the same tree, every exclusive {@code group} has &ge; 2 members, every {@code buffs} key is in the
 * design section B allow-list, and no two node ids collapse to the same Valhalla perk-id suffix.
 */
class AllSkillTreesLoadTest {

    /** The canonical shipped set: file name → expected {@code skill} id (SKILL_TREE design section E). */
    private static final Map<String, String> EXPECTED = expected();

    private static Map<String, String> expected() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("light_weapons.yml", "LIGHT_WEAPONS");
        m.put("heavy_weapons.yml", "HEAVY_WEAPONS");
        m.put("archery.yml", "ARCHERY");
        m.put("light_armor.yml", "LIGHT_ARMOR");
        m.put("heavy_armor.yml", "HEAVY_ARMOR");
        m.put("ars_magic.yml", "ARS_MAGIC");
        m.put("mining.yml", "MINING");
        m.put("woodcutting.yml", "WOODCUTTING");
        m.put("farming.yml", "FARMING");
        m.put("enchanting.yml", "ENCHANTING");
        m.put("digging.yml", "DIGGING");
        m.put("smithing.yml", "SMITHING");
        m.put("alchemy.yml", "ALCHEMY");
        m.put("fishing.yml", "FISHING");
        m.put("ars_smithing.yml", "ARS_SMITHING");
        m.put("power.yml", "POWER");
        return Collections.unmodifiableMap(m);
    }

    // --- test fixtures -----------------------------------------------------------------------------

    private static Plugin fakePlugin(File dataFolder, Logger logger) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> logger;
            case "saveResource" -> throw new AssertionError("saveResource() must not run headlessly");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    /** Copies every expected {@code skilltree/*.yml} out of the test classpath into the temp data folder. */
    private static void copyAll(File dataFolder) throws IOException {
        File dir = new File(dataFolder, SkillTreeConfig.DIR);
        Files.createDirectories(dir.toPath());
        for (String fileName : EXPECTED.keySet()) {
            try (InputStream in = classpath(fileName)) {
                assertNotNull(in, "bundled skilltree/" + fileName + " must be on the test classpath");
                Files.copy(in, new File(dir, fileName).toPath());
            }
        }
    }

    private static InputStream classpath(String fileName) {
        return AllSkillTreesLoadTest.class.getClassLoader()
                .getResourceAsStream(SkillTreeConfig.DIR + "/" + fileName);
    }

    private static YamlConfiguration rawYaml(String fileName) throws IOException {
        try (InputStream in = classpath(fileName)) {
            assertNotNull(in, "bundled skilltree/" + fileName + " must be on the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        }
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    @DisplayName("all 16 trees load into distinct trees with the expected skill id and non-empty nodes")
    void allSixteenTreesLoadWithExpectedIdsAndNodes(@TempDir File dataFolder) throws IOException {
        copyAll(dataFolder);

        Logger logger = Logger.getLogger("AllSkillTreesLoadTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getLevel() + ": " + record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);

        SkillTreeConfig config = new SkillTreeConfig();
        config.load(fakePlugin(dataFolder, logger));

        List<String> unexpected = warnings.stream()
                .filter(w -> !w.contains("] loaded ") || !w.contains("file(s) with issue(s)"))
                .toList();
        assertTrue(unexpected.isEmpty(),
                "loader flagged unexpected issue(s) in the shipped trees:\n  " + String.join("\n  ", unexpected));

        Map<String, SkillTree> all = config.all();
        assertEquals(EXPECTED.size(), all.size(),
                "expected " + EXPECTED.size() + " trees, loaded " + all.size() + ": " + all.keySet());

        for (Map.Entry<String, String> entry : EXPECTED.entrySet()) {
            String skillId = entry.getValue();
            SkillTree tree = config.tree(skillId).orElseThrow(
                    () -> new AssertionError("missing tree for " + skillId + " (" + entry.getKey() + ")"));
            assertEquals(skillId, tree.skill(), "skill id mismatch in " + entry.getKey());
            assertTrue(tree.nodes().size() > 0, skillId + " must have at least one node");
        }
    }

    @Test
    @DisplayName("every tree: parent resolves in-tree, greek groups exclusive, buff keys allowed, ids unique")
    void everyTreePassesStructuralHealthChecks() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Map.Entry<String, String> entry : EXPECTED.entrySet()) {
            String fileName = entry.getKey();
            YamlConfiguration yaml = rawYaml(fileName);

            String skill = yaml.getString("skill");
            if (skill == null || skill.isBlank()) {
                violations.add(fileName + ": missing top-level 'skill' id");
            }

            ConfigurationSection nodes = yaml.getConfigurationSection("nodes");
            if (nodes == null) {
                violations.add(fileName + ": missing 'nodes' section");
                continue;
            }
            Set<String> ids = nodes.getKeys(false);

            Map<String, Integer> groupCounts = new TreeMap<>();
            Map<String, String> perkIdOwners = new LinkedHashMap<>();

            for (String id : ids) {
                ConfigurationSection node = nodes.getConfigurationSection(id);
                if (node == null) {
                    violations.add(fileName + " node '" + id + "': not a mapping section");
                    continue;
                }
                // parent must resolve within this tree.
                String parent = cleanString(node.getString("parent"));
                if (parent != null && !ids.contains(parent)) {
                    violations.add(fileName + " node '" + id + "': parent '" + parent
                            + "' does not exist in this tree");
                }
                // exclusive group tally.
                String group = cleanString(node.getString("group"));
                if (group != null) {
                    groupCounts.merge(group, 1, Integer::sum);
                }
                // buff keys must be in the allow-list (canonical form).
                ConfigurationSection buffs = node.getConfigurationSection("buffs");
                if (buffs != null) {
                    for (String rawKey : buffs.getKeys(false)) {
                        String key = canonical(rawKey);
                        if (!StatVocabulary.isKnown(key)) {
                            violations.add(fileName + " node '" + id + "': buff key '" + rawKey
                                    + "' is not in the design section B allow-list");
                        }
                    }
                }
                // perk-id suffix collision (two node ids collapsing to one Valhalla perk id).
                String perkSuffix = normalize(id);
                String prior = perkIdOwners.putIfAbsent(perkSuffix, id);
                if (prior != null) {
                    violations.add(fileName + " node '" + id + "': perk-id suffix '" + perkSuffix
                            + "' collides with node '" + prior + "'");
                }
            }

            // prestige buff keys, if any, must also be allow-listed.
            ConfigurationSection prestigeBuffs = yaml.getConfigurationSection("prestige.buffs");
            if (prestigeBuffs != null) {
                for (String rawKey : prestigeBuffs.getKeys(false)) {
                    String key = canonical(rawKey);
                    if (!StatVocabulary.isKnown(key)) {
                        violations.add(fileName + " prestige: buff key '" + rawKey
                                + "' is not in the design section B allow-list");
                    }
                }
            }

            groupCounts.forEach((group, count) -> {
                if (count < 2) {
                    violations.add(fileName + ": exclusive group '" + group + "' has only " + count
                            + " member; exclusivity needs >= 2");
                }
            });
        }

        assertTrue(violations.isEmpty(),
                "structural health violations across the 16 shipped trees:\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("loaded model agrees: every node's parent resolves and every greek group has >= 2 members")
    void loadedModelStructureIsSound(@TempDir File dataFolder) throws IOException {
        copyAll(dataFolder);
        SkillTreeConfig config = new SkillTreeConfig();
        config.load(fakePlugin(dataFolder, Logger.getLogger("AllSkillTreesLoadTest-model")));

        List<String> violations = new ArrayList<>();
        for (SkillTree tree : config.all().values()) {
            Map<String, Integer> groupCounts = new TreeMap<>();
            for (SkillNode node : tree.nodes().values()) {
                if (node.parent() != null && !tree.nodes().containsKey(node.parent())) {
                    violations.add(tree.skill() + " node '" + node.id() + "': unresolved parent '"
                            + node.parent() + "'");
                }
                if (node.group() != null) {
                    groupCounts.merge(node.group(), 1, Integer::sum);
                }
                for (String key : node.buffs().keySet()) {
                    if (!StatVocabulary.isKnown(key)) {
                        violations.add(tree.skill() + " node '" + node.id() + "': loaded buff key '"
                                + key + "' outside allow-list");
                    }
                }
            }
            groupCounts.forEach((group, count) -> {
                if (count < 2) {
                    violations.add(tree.skill() + ": greek group '" + group + "' has " + count
                            + " member (< 2)");
                }
            });
        }
        assertTrue(violations.isEmpty(),
                "loaded-model structural violations:\n  " + String.join("\n  ", violations));
    }

    private static String canonical(String key) {
        return key.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /** The Valhalla perk-id suffix a node id collapses to (mirrors PerkNaming#normalizeNodeId). */
    private static String normalize(String nodeId) {
        return nodeId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private static String cleanString(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return (trimmed.isEmpty() || trimmed.equalsIgnoreCase("null")) ? null : trimmed;
    }
}
