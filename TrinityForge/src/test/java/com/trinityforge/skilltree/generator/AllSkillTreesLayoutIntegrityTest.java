package com.trinityforge.skilltree.generator;

import com.trinityforge.config.domains.SkillTreeConfig;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷16ツリー全部の<b>生成座標そのもの</b>に対する拘束テスト(2026-07-30)。
 * {@link AllSkillTreesProgressionTest} が「YAMLが壊れていないか」を見るのに対し、こちらは
 * 「GUIで読める配置になっているか」だけを見る。
 *
 * <p>守る不変条件は3つ:
 * <ol>
 *   <li><b>親→子のコネクタが迂回しない</b>(経路長 == マンハッタン距離)。実バグ: light/heavy_weapons の
 *       lv100 で {@code D-1-1(0,0)}→{@code D-1-2(0,-4)} の間に排他ノード {@code E-alpha-1(0,-2)} が
 *       挟まり、コネクタが排他グループの行を大きく横切っていた(「排他と分岐が干渉」)。</li>
 *   <li><b>ノード同士が8近傍で接触しない</b>(コネクタ用の隙間が必ず1セル残る)。</li>
 *   <li><b>排他路線(GREEK)を持つツリーでは GREEK が左半平面・BRANCH が右半平面</b>に分かれる。
 *       side を「兄弟の並び順」で決めていた頃は、同じツリー内で排他の側が主軸ごとに入れ替わり、
 *       分岐チェーンの列と衝突していた。</li>
 * </ol>
 * 違反は全ツリー分をまとめて報告する(1件ずつ潰すより差分が読める)。
 */
class AllSkillTreesLayoutIntegrityTest {

    private static final List<String> FILES = List.of(
            "light_weapons.yml", "heavy_weapons.yml", "archery.yml", "light_armor.yml", "heavy_armor.yml",
            "ars_magic.yml", "mining.yml", "woodcutting.yml", "farming.yml", "enchanting.yml",
            "digging.yml", "smithing.yml", "alchemy.yml", "fishing.yml", "ars_smithing.yml", "power.yml");

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("AllSkillTreesLayoutIntegrityTest");
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
            try (InputStream in = AllSkillTreesLayoutIntegrityTest.class.getClassLoader()
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
    @DisplayName("every shipped tree lays out with detour-free connectors, node gaps and role half-planes")
    void everyTreeLaysOutWithoutInterference(@TempDir File dataFolder) throws IOException {
        List<String> problems = new ArrayList<>();

        for (SkillTree tree : loadAll(dataFolder)) {
            String skill = tree.skill();
            SkillTreeLayout layout = new SkillTreeLayout(tree);

            List<SkillNode> sorted = new ArrayList<>(tree.nodes().values());
            sorted.sort(Comparator.comparingInt(SkillNode::level).thenComparing(SkillNode::id));
            Map<String, Coord> coords = new LinkedHashMap<>();
            for (SkillNode node : sorted) {
                coords.put(node.id(), layout.coordOf(node.id()));
            }
            boolean hasGreek = sorted.stream().anyMatch(node -> node.role() == SkillRole.GREEK);

            for (SkillNode node : sorted) {
                String parentId = node.parent();
                if (parentId == null || !tree.nodes().containsKey(parentId)) {
                    continue;
                }
                Coord parent = coords.get(parentId);
                Coord child = coords.get(node.id());
                int manhattan = Math.abs(parent.x() - child.x()) + Math.abs(parent.y() - child.y());
                int length;
                try {
                    length = layout.route(parent, child).size() - 1;
                } catch (RuntimeException unreachable) {
                    problems.add(skill + ": no connector route " + parentId + " -> " + node.id());
                    continue;
                }
                if (length > manhattan) {
                    problems.add(skill + ": connector " + parentId + " -> " + node.id()
                            + " must detour around another node (len=" + length + ", direct=" + manhattan + ")");
                }
                if (hasGreek) {
                    boolean greek = node.role() == SkillRole.GREEK;
                    boolean left = child.x() < layout.startX();
                    boolean right = child.x() > layout.startX();
                    if (greek && !left) {
                        problems.add(skill + ": greek node " + node.id() + " left the left half-plane at "
                                + child.format());
                    }
                    if (node.role() == SkillRole.BRANCH && !right) {
                        problems.add(skill + ": branch node " + node.id() + " left the right half-plane at "
                                + child.format());
                    }
                }
            }

            List<String> ids = new ArrayList<>(coords.keySet());
            for (int i = 0; i < ids.size(); i++) {
                for (int j = i + 1; j < ids.size(); j++) {
                    Coord a = coords.get(ids.get(i));
                    Coord b = coords.get(ids.get(j));
                    if (Math.abs(a.x() - b.x()) <= 1 && Math.abs(a.y() - b.y()) <= 1) {
                        problems.add(skill + ": nodes " + ids.get(i) + " and " + ids.get(j)
                                + " touch (no connector gap) at " + a.format() + " / " + b.format());
                    }
                }
            }
        }

        assertTrue(problems.isEmpty(), () -> "skill tree layout interference:\n  " + String.join("\n  ", problems));
    }
}
