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
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空きセル探索を「行優先」へ変えても<b>どのツリーも読みにくくならない</b>ことを、出荷16ツリーの
 * 実配置で機械的に確かめる (2026-08-24 / W-216)。
 *
 * <p>実サーバ報告「総合で足元強化のノードが変なつながり方している」の正体は、
 * チェーンの続きが置きたい行を<b>次の主軸の枝の根</b>に先取りされ、旧探索順(深さ優先)が
 * 「横へ探す前に1段上へ逃げる」ため<b>レベル帯を1つ飛ばして置かれる</b>こと。飛ばされた分だけ
 * コネクタが他チェーンの間の通路を縦に走るので、線がどこへ繋がっているのか読めなくなる。
 *
 * <p>ここで測るのは見た目の4指標。<b>新方式が旧方式より悪化していないこと</b>を1本ずつ縛る。
 * <ol>
 *   <li><b>帯ずれ</b>: 親の1段上に置けなかったノード数（＝レベル帯を飛ばした数）。今回の主目的</li>
 *   <li><b>接触</b>: ノードが8近傍でくっついてコネクタの隙間が無い箇所</li>
 *   <li><b>迂回</b>: コネクタが他ノードを避けて遠回りしている本数</li>
 *   <li><b>主軸列侵入</b>: 枝ノードが主軸の列に着地している数</li>
 * </ol>
 * 幅・高さは「悪化」の指標にしない（行優先は横へ広がる代わりに縦が縮むトレードオフで、
 * どちらが良いかは一概に決まらない）。値は失敗時のメッセージに出す。
 */
class SkillTreeLayoutRowFirstComparisonTest {

    private static final List<String> FILES = List.of(
            "light_weapons.yml", "heavy_weapons.yml", "archery.yml", "light_armor.yml", "heavy_armor.yml",
            "ars_magic.yml", "mining.yml", "woodcutting.yml", "farming.yml", "enchanting.yml",
            "digging.yml", "smithing.yml", "alchemy.yml", "fishing.yml", "ars_smithing.yml", "power.yml");

    /** 1ツリーぶんの読みやすさ指標。 */
    private record Metrics(int bandDrift, int touching, int detours, int onTrunk, int shared,
                           int width, int height) {
        String format() {
            return String.format("帯ずれ%2d 接触%2d 迂回%2d 主軸侵入%2d 線の重なり%2d (幅%2d 高さ%2d)",
                    bandDrift, touching, detours, onTrunk, shared, width, height);
        }
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("SkillTreeLayoutRowFirstComparisonTest");
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
            try (InputStream in = SkillTreeLayoutRowFirstComparisonTest.class.getClassLoader()
                    .getResourceAsStream(SkillTreeConfig.DIR + "/" + fileName)) {
                assertNotNull(in, "bundled skilltree/" + fileName + " must be on the test classpath");
                Files.copy(in, new File(dir, fileName).toPath());
            }
        }
        SkillTreeConfig config = new SkillTreeConfig();
        config.load(fakePlugin(dataFolder));
        return config.all().values();
    }

    private static Metrics measure(SkillTree tree, boolean rowFirst) {
        SkillTreeLayout layout = new SkillTreeLayout(tree, rowFirst);
        int startX = layout.startX();
        int bandDrift = 0;
        int detours = 0;
        int onTrunk = 0;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        List<Coord> placed = new ArrayList<>();
        // 中継セルごとの利用本数。2本以上が同じセルを通ると1本の線に見える。
        java.util.Map<Coord, Integer> usage = new java.util.HashMap<>();

        for (SkillNode node : tree.nodes().values()) {
            Coord here = layout.coordOf(node.id());
            placed.add(here);
            minX = Math.min(minX, here.x());
            maxX = Math.max(maxX, here.x());
            minY = Math.min(minY, here.y());
            maxY = Math.max(maxY, here.y());
            if (node.role() != SkillRole.MAIN && node.role() != SkillRole.INTERMEDIATE
                    && here.x() == startX) {
                onTrunk++;
            }
            String parentId = node.parent();
            if (parentId == null || parentId.isBlank() || !tree.nodes().containsKey(parentId)) {
                continue;
            }
            Coord parent = layout.coordOf(parentId);
            // 帯ずれ: 枝ノードは親のちょうど1段上に置かれるのが正しい。
            if (node.role() == SkillRole.BRANCH || node.role() == SkillRole.GREEK) {
                if (here.y() != parent.y() - SkillTreeLayout.TRUNK_STEP) {
                    bandDrift++;
                }
            }
            int manhattan = Math.abs(parent.x() - here.x()) + Math.abs(parent.y() - here.y());
            try {
                List<Coord> path = layout.route(parent, here);
                if (path.size() - 1 != manhattan) {
                    detours++;
                }
                // 端点(ノード自身)を除いた中継セルだけを数える。
                for (int i = 1; i < path.size() - 1; i++) {
                    usage.merge(path.get(i), 1, Integer::sum);
                }
            } catch (RuntimeException unreachable) {
                detours++;
            }
        }
        int shared = (int) usage.values().stream().filter(count -> count > 1).count();

        int touching = 0;
        for (int i = 0; i < placed.size(); i++) {
            for (int j = i + 1; j < placed.size(); j++) {
                Coord a = placed.get(i);
                Coord b = placed.get(j);
                if (Math.abs(a.x() - b.x()) <= 1 && Math.abs(a.y() - b.y()) <= 1) {
                    touching++;
                }
            }
        }
        return new Metrics(bandDrift, touching, detours, onTrunk, shared,
                maxX - minX + 1, maxY - minY + 1);
    }

    @Test
    @DisplayName("行優先の空きセル探索は、出荷16ツリーのどれも旧方式より読みにくくしない")
    void rowFirstSearchNeverRegressesAnyShippedTree(@TempDir File dataFolder) throws IOException {
        List<String> report = new ArrayList<>();
        List<String> regressions = new ArrayList<>();
        int driftBefore = 0;
        int driftAfter = 0;
        int sharedBefore = 0;
        int sharedAfter = 0;

        for (SkillTree tree : loadAll(dataFolder)) {
            Metrics before = measure(tree, false);
            Metrics after = measure(tree, true);
            driftBefore += before.bandDrift();
            driftAfter += after.bandDrift();
            report.add(String.format("%-14s 旧: %s%n               新: %s",
                    tree.skill(), before.format(), after.format()));

            if (after.bandDrift() > before.bandDrift()) {
                regressions.add(tree.skill() + ": レベル帯を飛ばしたノードが増えた "
                        + before.bandDrift() + " -> " + after.bandDrift());
            }
            if (after.touching() > before.touching()) {
                regressions.add(tree.skill() + ": ノードの接触が増えた "
                        + before.touching() + " -> " + after.touching());
            }
            if (after.detours() > before.detours()) {
                regressions.add(tree.skill() + ": 迂回コネクタが増えた "
                        + before.detours() + " -> " + after.detours());
            }
            if (after.onTrunk() > before.onTrunk()) {
                regressions.add(tree.skill() + ": 枝が主軸の列に着地した数が増えた "
                        + before.onTrunk() + " -> " + after.onTrunk());
            }
            sharedBefore += before.shared();
            sharedAfter += after.shared();
        }

        String table = String.join(System.lineSeparator(), report);
        assertTrue(regressions.isEmpty(),
                "行優先で悪化したツリーがある:" + System.lineSeparator()
                        + String.join(System.lineSeparator(), regressions)
                        + System.lineSeparator() + table);
        // 目的そのものの確認: 全体としてレベル帯飛ばしが減っていること。
        assertTrue(driftAfter < driftBefore,
                "レベル帯を飛ばすノードが減っていない (旧 " + driftBefore + " -> 新 " + driftAfter + ")"
                        + System.lineSeparator() + table);
        // 線の重なりだけは【ツリー単位では許容し、合計で悪化させない】。
        // 内側のチェーンが外側のチェーンのレーンを飛び越すときは必ずどこかで1本の通路を共有する
        // ので、帯ずれを消すと重なりが1つ増えることがある(総合がまさにこれ: 10 -> 11)。
        // 帯ずれ(=線が他チェーンの間を縦に走る)のほうが実機で読みにくいので、そちらを優先する。
        assertTrue(sharedAfter <= sharedBefore,
                "別々の線が同じセルを通る箇所が全体で増えた (旧 " + sharedBefore + " -> 新 " + sharedAfter + ")"
                        + System.lineSeparator() + table);
    }
}
