package com.trinityforge.skilltree.generator;

import com.trinityforge.config.domains.SkillTreeConfig;
import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Permanent generation smoke over <b>all 16</b> shipped trees: each is loaded through the real
 * {@link SkillTreeConfig} loader and run through {@link SkillTreeProgressionGenerator#generate}
 * (SKILL_TREE design section 2). For every tree the artifact is verified offline — it re-parses through
 * Bukkit's YAML engine, every {@code <lang.X>} reference resolves, no TF {@code buffs} key leaks into
 * {@code perk_rewards}, exclusive greek members lock each other reciprocally, and an enabled prestige
 * yields a hidden {@code ng1} perk with a {@code reset_skill} reward. Violations are collected across all
 * trees and reported together with the offending skill/perk.
 */
class AllSkillTreesProgressionTest {

    private static final List<String> FILES = List.of(
            "light_weapons.yml", "heavy_weapons.yml", "archery.yml", "light_armor.yml", "heavy_armor.yml",
            "ars_magic.yml", "mining.yml", "woodcutting.yml", "farming.yml", "enchanting.yml",
            "digging.yml", "smithing.yml", "alchemy.yml", "fishing.yml", "ars_smithing.yml", "power.yml");

    private static final Pattern LANG_REF = Pattern.compile("<lang\\.([A-Za-z0-9_]+)>");

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("AllSkillTreesProgressionTest");
            case "saveResource" -> throw new AssertionError("saveResource() must not run headlessly");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static Collection<SkillTree> loadAll(File dataFolder) throws IOException {
        File dir = new File(dataFolder, SkillTreeConfig.DIR);
        Files.createDirectories(dir.toPath());
        for (String fileName : FILES) {
            try (InputStream in = AllSkillTreesProgressionTest.class.getClassLoader()
                    .getResourceAsStream(SkillTreeConfig.DIR + "/" + fileName)) {
                assertNotNull(in, "bundled skilltree/" + fileName + " must be on the test classpath");
                Files.copy(in, new File(dir, fileName).toPath());
            }
        }
        SkillTreeConfig config = new SkillTreeConfig();
        config.load(fakePlugin(dataFolder));
        return config.all().values();
    }

    @Test
    @DisplayName("all 16 generate a Bukkit-reparsable progression with resolvable lang refs and no buff leak")
    void everyTreeGeneratesReparsableProgression(@TempDir File dataFolder) throws IOException {
        List<String> problems = new ArrayList<>();
        int generated = 0;

        for (SkillTree tree : loadAll(dataFolder)) {
            String skill = tree.skill();
            GeneratedProgression gen;
            try {
                gen = SkillTreeProgressionGenerator.generate(tree);
            } catch (RuntimeException ex) {
                problems.add(skill + ": generate() threw " + ex);
                continue;
            }
            generated++;

            // Re-parse the emitted YAML through Bukkit's engine.
            File out = new File(dataFolder, skill.toLowerCase() + "_progression.yml");
            Files.writeString(out.toPath(), gen.yaml());
            YamlConfiguration parsed = YamlConfiguration.loadConfiguration(out);
            ConfigurationSection perks = parsed.getConfigurationSection("perks");
            if (perks == null) {
                problems.add(skill + ": re-parsed YAML has no 'perks' section");
            } else if (perks.getKeys(false).size() != gen.perks().size()) {
                problems.add(skill + ": perk count drift on re-parse (" + perks.getKeys(false).size()
                        + " vs " + gen.perks().size() + ")");
            }
            if (parsed.getString("starting_coordinates") == null) {
                problems.add(skill + ": re-parsed YAML has no 'starting_coordinates'");
            }

            // Every lang reference in the YAML resolves in the generated lang map.
            Matcher matcher = LANG_REF.matcher(gen.yaml());
            while (matcher.find()) {
                if (!gen.lang().containsKey(matcher.group(1))) {
                    problems.add(skill + ": unresolved lang ref <lang." + matcher.group(1) + ">");
                }
            }
            for (GeneratedPerk perk : gen.perks().values()) {
                if (!gen.lang().containsKey(perk.nameKey())) {
                    problems.add(skill + ": missing lang name key " + perk.nameKey());
                }
                if (!gen.lang().containsKey(perk.descriptionKey())) {
                    problems.add(skill + ": missing lang description key " + perk.descriptionKey());
                }
                // TF buff keys must never surface in perk_rewards (double-application guard).
                for (String key : perk.perkRewards().keySet()) {
                    if (StatVocabulary.isKnown(key) || StatVocabulary.isKnown(stripPrefix(key))) {
                        problems.add(skill + " perk '" + perk.id() + "': perk_rewards leaks TF buff key '"
                                + key + "'");
                    }
                }
            }

            // Suffixes 12–20 are the supported corner/junction/cross pieces; 21–23 remain undefined.
            Matcher dye = Pattern.compile("DYE:117272[1-3]\\b").matcher(gen.yaml());
            if (dye.find()) {
                problems.add(skill + ": unsupported connector dye present: ..." + dye.group());
            }

            // Deterministic: regenerating yields byte-identical YAML.
            if (!gen.yaml().equals(SkillTreeProgressionGenerator.generate(tree).yaml())) {
                problems.add(skill + ": generation is not deterministic");
            }
        }

        assertTrue(generated == FILES.size(),
                "expected to generate " + FILES.size() + " trees, generated " + generated);
        assertTrue(problems.isEmpty(),
                "generation smoke violations:\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("no two perks in a tree share a coordinate (縦幹レイアウトの重なり=歪み防止)")
    void everyTreeHasUniquePerkCoordinates(@TempDir File dataFolder) throws IOException {
        List<String> problems = new ArrayList<>();

        for (SkillTree tree : loadAll(dataFolder)) {
            String skill = tree.skill();
            GeneratedProgression gen = SkillTreeProgressionGenerator.generate(tree);
            Map<String, List<String>> byCoord = new TreeMap<>();
            for (GeneratedPerk perk : gen.perks().values()) {
                byCoord.computeIfAbsent(perk.coords().format(), k -> new ArrayList<>()).add(perk.id());
            }
            byCoord.forEach((coord, ids) -> {
                if (ids.size() > 1) {
                    problems.add(skill + ": coordinate " + coord + " shared by " + ids);
                }
            });
        }

        assertTrue(problems.isEmpty(),
                "perk coordinate collisions (would overlap in the Valhalla GUI):\n  "
                        + String.join("\n  ", problems));
    }

    /**
     * 2026-07-30 (排他ノードと通常分岐の干渉): 「UIのノードがずれる/消える/くっつく」の再発防止。
     *
     * <ul>
     *   <li>どのノードも8近傍に他のノードを持たない = 必ずコネクタ1セル分の隙間がある
     *       (くっついて1つのノードのように見える/コネクタが引けないのを防ぐ)</li>
     *   <li>主軸の列は MAIN/プレステージ専用 = 分岐/排他ノードが主軸列へ着地しない
     *       (D-1-2 がプレステージの真下に張り付いていた実例への対応)</li>
     * </ul>
     */
    @Test
    @DisplayName("no perk touches another perk (8近傍) and branches never land on the trunk column")
    void perksKeepAConnectorGapAndLeaveTheTrunkColumnToMains(@TempDir File dataFolder) throws IOException {
        List<String> problems = new ArrayList<>();

        for (SkillTree tree : loadAll(dataFolder)) {
            String skill = tree.skill();
            SkillTreeLayout layout = new SkillTreeLayout(tree);
            Map<String, String> nodeAt = new HashMap<>();
            for (SkillNode node : tree.nodes().values()) {
                nodeAt.put(layout.coordOf(node.id()).format(), node.id());
            }

            for (SkillNode node : tree.nodes().values()) {
                Coord coord = layout.coordOf(node.id());
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        String neighbour = nodeAt.get(new Coord(coord.x() + dx, coord.y() + dy).format());
                        if (neighbour != null) {
                            problems.add(skill + ": " + node.id() + " at " + coord.format()
                                    + " touches " + neighbour);
                        }
                    }
                }
                boolean trunkRole = node.role() == com.trinityforge.skilltree.SkillRole.MAIN
                        || node.role() == com.trinityforge.skilltree.SkillRole.INTERMEDIATE;
                if (!trunkRole && coord.x() == layout.startX()) {
                    problems.add(skill + ": " + node.id() + " (" + node.role()
                            + ") occupies the trunk column at " + coord.format());
                }
            }
        }

        assertTrue(problems.isEmpty(),
                "node adjacency / trunk-column violations:\n  " + String.join("\n  ", problems));
    }

    /**
     * 2026-07-30: 排他グループ({@code group})のメンバーは<b>同じ側の連続レーンにまとまる</b>こと。
     * 以前は兄弟を並び順で左右交互に振っていたため、排他3兄弟が主軸や通常分岐を挟んで左右に散り、
     * 「どれとどれが排他なのか」がGUIから読めなくなっていた(light_weapons の C 直下が実例)。
     */
    @Test
    @DisplayName("exclusive group members occupy contiguous lanes on one side of their parent")
    void exclusiveGroupsFormContiguousLanes(@TempDir File dataFolder) throws IOException {
        List<String> problems = new ArrayList<>();

        for (SkillTree tree : loadAll(dataFolder)) {
            String skill = tree.skill();
            SkillTreeLayout layout = new SkillTreeLayout(tree);
            // 同じ親 + 同じ group のメンバーだけが排他の単位(兄弟排他)。
            Map<String, List<SkillNode>> groups = new TreeMap<>();
            for (SkillNode node : tree.nodes().values()) {
                if (node.group() != null && !node.group().isBlank() && node.parent() != null) {
                    groups.computeIfAbsent(node.parent() + "/" + node.group(), k -> new ArrayList<>())
                            .add(node);
                }
            }

            for (Map.Entry<String, List<SkillNode>> entry : groups.entrySet()) {
                List<SkillNode> members = entry.getValue();
                if (members.size() < 2) {
                    continue;
                }
                String parentId = members.get(0).parent();
                Coord parent = layout.coordOf(parentId);
                List<Integer> lanes = new ArrayList<>();
                boolean sameRow = true;
                boolean sameSide = true;
                for (SkillNode member : members) {
                    Coord coord = layout.coordOf(member.id());
                    sameRow &= coord.y() == parent.y() - SkillTreeLayout.TRUNK_STEP;
                    int delta = coord.x() - parent.x();
                    sameSide &= Integer.signum(delta) == Integer.signum(lanes.isEmpty()
                            ? delta : lanes.get(0));
                    lanes.add(delta);
                }
                if (!sameRow || !sameSide) {
                    problems.add(skill + " group '" + entry.getKey() + "': members not on one side of "
                            + parentId + " in the same row -> "
                            + members.stream().map(m -> m.id() + layout.coordOf(m.id()).format()).toList());
                    continue;
                }
                List<Integer> sorted = lanes.stream().map(Math::abs).sorted().toList();
                for (int i = 1; i < sorted.size(); i++) {
                    if (sorted.get(i) - sorted.get(i - 1) != SkillTreeLayout.SIDE_STEP) {
                        problems.add(skill + " group '" + entry.getKey() + "': lanes are not contiguous "
                                + sorted + " (他の分岐が排他グループの間に割り込んでいる)");
                        break;
                    }
                }
            }
        }

        assertTrue(problems.isEmpty(),
                "exclusive-group layout violations:\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("connector routes never pass through an unrelated perk node")
    void connectorsDoNotCrossNodes(@TempDir File dataFolder) throws IOException {
        List<String> problems = new ArrayList<>();
        for (SkillTree tree : loadAll(dataFolder)) {
            GeneratedProgression gen = SkillTreeProgressionGenerator.generate(tree);
            Map<String, String> nodeAt = new HashMap<>();
            gen.perks().values().forEach(perk -> nodeAt.put(perk.coords().format(), perk.id()));
            for (GeneratedPerk perk : gen.perks().values()) {
                for (Map<String, Object> segment : perk.connectionLine().values()) {
                    String position = String.valueOf(segment.get("position"));
                    String node = nodeAt.get(position);
                    if (node != null) {
                        problems.add(tree.skill() + ": " + perk.id() + " connector crosses " + node
                                + " at " + position);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "connector/node collisions:\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("every exclusive greek group is wired with reciprocal perks_locked_add")
    void everyGreekGroupIsReciprocallyLocked(@TempDir File dataFolder) throws IOException {
        List<String> problems = new ArrayList<>();

        for (SkillTree tree : loadAll(dataFolder)) {
            String skill = tree.skill();
            GeneratedProgression gen = SkillTreeProgressionGenerator.generate(tree);

            Map<String, List<SkillNode>> groups = new TreeMap<>();
            for (SkillNode node : tree.nodes().values()) {
                if (node.group() != null) {
                    groups.computeIfAbsent(node.group(), k -> new ArrayList<>()).add(node);
                }
            }

            for (Map.Entry<String, List<SkillNode>> group : groups.entrySet()) {
                List<SkillNode> members = group.getValue();
                members.sort(Comparator.comparing(SkillNode::id));
                for (SkillNode member : members) {
                    String perkId = PerkNaming.perkId(skill, member.id());
                    // Same parent + same group only (sibling exclusivity).
                    List<String> expectedLocks = members.stream()
                            .filter(other -> !other.id().equals(member.id())
                                    && Objects.equals(member.parent(), other.parent()))
                            .map(other -> PerkNaming.perkId(skill, other.id()))
                            .sorted()
                            .toList();
                    Object actual = gen.perks().get(perkId).perkRewards().get("perks_locked_add");
                    if (expectedLocks.isEmpty()) {
                        if (actual != null) {
                            problems.add(skill + " group '" + group.getKey() + "' perk '" + perkId
                                    + "': unexpected perks_locked_add=" + actual);
                        }
                    } else if (!expectedLocks.equals(actual)) {
                        problems.add(skill + " group '" + group.getKey() + "' perk '" + perkId
                                + "': perks_locked_add=" + actual + " expected " + expectedLocks);
                    }
                }
            }
        }

        assertTrue(problems.isEmpty(),
                "greek exclusivity wiring violations:\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("every enabled prestige becomes a hidden ng1 perk carrying reset_skill")
    void everyEnabledPrestigeProducesHiddenNgPerk(@TempDir File dataFolder) throws IOException {
        List<String> problems = new ArrayList<>();

        for (SkillTree tree : loadAll(dataFolder)) {
            Prestige prestige = tree.prestige();
            if (prestige == null || !prestige.enabled()) {
                continue;
            }
            String skill = tree.skill();
            GeneratedProgression gen = SkillTreeProgressionGenerator.generate(tree);
            String ngId = PerkNaming.prestigePerkId(skill, 1);
            GeneratedPerk ng = gen.perks().get(ngId);
            if (ng == null) {
                problems.add(skill + ": enabled prestige produced no perk '" + ngId + "'");
                continue;
            }
            if (ng.hidden()) {
                problems.add(skill + ": prestige perk '" + ngId + "' should be visible (trunk apex)");
            }
            if (ng.connectionLine().isEmpty()) {
                problems.add(skill + ": prestige perk '" + ngId + "' should connect from top MAIN");
            }
            if (!ng.perkRewards().containsKey(PerkNaming.resetSkillKey(skill))) {
                problems.add(skill + ": prestige perk '" + ngId + "' missing "
                        + PerkNaming.resetSkillKey(skill));
            }
        }

        assertTrue(problems.isEmpty(),
                "prestige generation violations:\n  " + String.join("\n  ", problems));
    }

    /** Strips the {@code p:} permanent-reward prefix so a prefixed buff key would still be caught. */
    private static String stripPrefix(String key) {
        return key.startsWith("p:") ? key.substring(2) : key;
    }
}
