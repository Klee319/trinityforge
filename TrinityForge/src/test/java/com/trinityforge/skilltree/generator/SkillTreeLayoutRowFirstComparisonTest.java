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
    private record Metrics(int bandDrift, int laneDrift, int touching, int detours, int onTrunk,
                           int shared, int fused, int width, int height) {
        String format() {
            return String.format(
                    "帯ずれ%2d 列ずれ%2d 接触%2d 迂回%2d 主軸侵入%2d 重なり%2d 融合%2d (幅%2d 高さ%2d)",
                    bandDrift, laneDrift, touching, detours, onTrunk, shared, fused, width, height);
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

    private static Metrics measure(SkillTree tree, boolean rowFirst, boolean continuationFirst) {
        SkillTreeLayout layout = new SkillTreeLayout(tree, rowFirst, continuationFirst);
        int startX = layout.startX();
        int bandDrift = 0;
        int laneDrift = 0;
        int detours = 0;
        int onTrunk = 0;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        List<Coord> placed = new ArrayList<>();
        // 中継セルごとの利用本数。2本以上が同じセルを通ると1本の線に見える。
        java.util.Map<Coord, Integer> usage = new java.util.HashMap<>();
        // 中継セルごとに「そのセルを横向きに通った連結子」の集合。
        // ⚠ 報告の「つながり方がおかしい」の実体は<b>同じセルの重なりではない</b>。
        //   別々の連結子が同じ行の<b>隣り合うセル</b>を横向きに通ると、GUI では
        //   境目が無いので<b>1本の長い横線に見える</b>（どのノードから来た線か追えなくなる）。
        //   逆に同じ連結子が続いているだけなら、長い横線でも1本として正しく読める。
        java.util.Map<Coord, java.util.Set<String>> horizontalOwners = new java.util.HashMap<>();

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
                // 列ずれ: 「チェーンの続き」(親も枝である)は親と同じ列に置けるのが正しい。
                // 横へずれるとコネクタが斜めに走り、他の線と同じ通路の行で合流する。
                // 主軸から新しく出る枝は主軸の真上に来られないので、ここでは数えない。
                SkillNode parentNode = tree.nodes().get(parentId);
                boolean continuation = parentNode.role() == SkillRole.BRANCH
                        || parentNode.role() == SkillRole.GREEK;
                if (continuation && here.x() != parent.x()) {
                    laneDrift++;
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
                    Coord cell = path.get(i);
                    usage.merge(cell, 1, Integer::sum);
                    Coord prev = path.get(i - 1);
                    Coord next = path.get(i + 1);
                    if (prev.y() == cell.y() || next.y() == cell.y()) {
                        horizontalOwners
                                .computeIfAbsent(cell, ignored -> new java.util.HashSet<>())
                                .add(parentId + ">" + node.id());
                    }
                }
            } catch (RuntimeException unreachable) {
                detours++;
            }
        }
        int shared = (int) usage.values().stream().filter(count -> count > 1).count();
        int fused = 0;
        for (java.util.Map.Entry<Coord, java.util.Set<String>> entry : horizontalOwners.entrySet()) {
            java.util.Set<String> right =
                    horizontalOwners.get(new Coord(entry.getKey().x() + 1, entry.getKey().y()));
            if (right != null && java.util.Collections.disjoint(entry.getValue(), right)) {
                fused++;
            }
        }

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
        return new Metrics(bandDrift, laneDrift, touching, detours, onTrunk, shared, fused,
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
            // ⚠ どちらも continuationFirst=false ―― これは 2026-08-24 当時の比較を
            //   そのまま保存したもの。既定を変えた後もこの比較の結論が変わらないことを見る。
            Metrics before = measure(tree, false, false);
            Metrics after = measure(tree, true, false);
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

    /**
     * レーンを配る順序を「行を下から / 同じ行ではチェーンの続きを先に」へ変えても
     * <b>どのツリーも読みにくくならない</b>ことを確かめる (2026-08-25 / W-250)。
     *
     * <p>実サーバ報告「エンチャントと総合のノードのつながり方がおかしい」の正体は、
     * <b>主軸ノードが最初に全部トランクへ置かれる</b>ため、どの主軸の扇も
     * 「下から伸びてきたチェーンの続き」より先にレーンを取ってしまうこと。
     * 押し出された続きは斜めに 4〜6 セル走り、通路の行はノード行の間に1本しか無いので、
     * 扇の線と合流して T 字・十字に描き替わる（付呪では1本の通路に5本のコネクタが載っていた）。
     *
     * <p>ここで一番見たいのは<b>線の重なり</b>(別々のコネクタが同じセルを通る箇所)。
     * 前回(W-216)は帯ずれを消す代償として1つ増えるのを許したが、今回はそこを減らすのが目的。
     */
    @Test
    @DisplayName("チェーン優先のレーン配りは、出荷16ツリーのどれも読みにくくしない")
    void continuationFirstNeverRegressesAnyShippedTree(@TempDir File dataFolder) throws IOException {
        List<String> report = new ArrayList<>();
        List<String> regressions = new ArrayList<>();
        int driftBefore = 0;
        int driftAfter = 0;
        int laneBefore = 0;
        int laneAfter = 0;
        int fusedBefore = 0;
        int fusedAfter = 0;

        for (SkillTree tree : loadAll(dataFolder)) {
            Metrics before = measure(tree, true, false);
            Metrics after = measure(tree, true, true);
            report.add(String.format("%-14s 旧: %s%n               新: %s",
                    tree.skill(), before.format(), after.format()));
            driftBefore += before.bandDrift();
            driftAfter += after.bandDrift();
            laneBefore += before.laneDrift();
            laneAfter += after.laneDrift();
            fusedBefore += before.fused();
            fusedAfter += after.fused();

            if (after.bandDrift() > before.bandDrift()) {
                regressions.add(tree.skill() + ": レベル帯を飛ばしたノードが増えた "
                        + before.bandDrift() + " -> " + after.bandDrift());
            }
            if (after.laneDrift() > before.laneDrift()) {
                regressions.add(tree.skill() + ": チェーンが親の列から外れる数が増えた "
                        + before.laneDrift() + " -> " + after.laneDrift());
            }
            if (after.fused() > before.fused()) {
                regressions.add(tree.skill() + ": 別々の線が1本に見える箇所が増えた "
                        + before.fused() + " -> " + after.fused());
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
            // 注: shape を問わない重なり(shared)は増えてよい。チェーンが真上へ伸びると、
            //   その縦線は主軸の扇の通路を1セルだけ横切るので shared は +1 される。
            //   しかしそれは十字に描かれる交差で、線をたどれば親子は読める。
            //   読めなくなるのは同じ向きが重なったときだけなので、縛るのは融合の方。
        }

        String table = String.join(System.lineSeparator(), report);
        assertTrue(regressions.isEmpty(),
                "チェーン優先で悪化したツリーがある:" + System.lineSeparator()
                        + String.join(System.lineSeparator(), regressions)
                        + System.lineSeparator() + table);
        assertTrue(driftAfter <= driftBefore,
                "レベル帯を飛ばすノードが増えた (旧 " + driftBefore + " -> 新 " + driftAfter + ")"
                        + System.lineSeparator() + table);
        assertTrue(laneAfter < laneBefore,
                "チェーンが親の列から外れる数が減っていない (旧 " + laneBefore + " -> 新 " + laneAfter + ")"
                        + System.lineSeparator() + table);
        // 目的そのもの: 別々の線が隣り合って1本に見える箇所が減っていること。
        assertTrue(fusedAfter < fusedBefore,
                "別々の線が1本に見える箇所が減っていない (旧 " + fusedBefore
                        + " -> 新 " + fusedAfter + ")" + System.lineSeparator() + table);
        System.out.println("[W-250 continuationFirst]" + System.lineSeparator() + table
                + System.lineSeparator()
                + String.format("合計 帯ずれ %d -> %d / 列ずれ %d -> %d / 融合 %d -> %d",
                        driftBefore, driftAfter, laneBefore, laneAfter,
                        fusedBefore, fusedAfter));
    }
}
