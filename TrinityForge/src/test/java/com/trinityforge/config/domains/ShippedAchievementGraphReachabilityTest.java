package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code progression/achievements.yml} の<b>依存グラフそのもの</b>を固定する(2026-08-01)。
 *
 * <p>{@link ShippedAchievementTreeTest} が見ているのは「parent が実在するか / 鎖が自分へ戻らないか」まで。
 * ここではさらに踏み込んで、
 * <ul>
 *   <li>全ノードが起点 {@code main} から<b>実際にたどり着けるか</b>(親も parents-any も
 *       非到達ノードしか指していない孤島が無いか)</li>
 *   <li>「名目上の第1目標」である {@code goal_worldbinder} が、<b>順序の意味で本当に前の方にあるか</b></li>
 * </ul>
 * を固定する。
 *
 * <p><b>なぜ2つ目が要るか (K-22(2))</b>: アチーブメントの前提は表示順ではなく
 * <b>達成そのものを縛る</b>(2026-07-29 ユーザー確定 /
 * {@link com.trinityforge.progression.AchievementService} の {@code gateOpen})。
 * {@code goal_worldbinder} の parent が {@code delve_all_seals}(19種すべての踏破の証)だと、
 * 「第1目標」と銘打ったノードが<b>全ダンジョン踏破後にしか解けない＝事実上いちばん最後</b>になる。
 * これは yml 上は完全に正当な設定で、警告も出ず、ゲーム内では「なぜか取れない」としか見えないため、
 * 機械で固定するしか気づく手段が無い。
 *
 * <p>既存プレイヤーへの影響が無いことも確認済み: {@code goal_worldbinder} は {@code type: static} で
 * {@code AchievementService.pollStatistics} の周期ポーリングが毎周 {@code gateOpen} を評価し直すため、
 * 前提を<b>緩める</b>方向の変更はデータ移行なしで次の周から効く(達成済みIDはPDCに永続化され再付与されない)。
 */
class ShippedAchievementGraphReachabilityTest {

    private static final String ACHIEVEMENTS = "src/main/resources/" + AchievementsConfig.PATH;

    /** ツリーの起点。{@link ShippedAchievementTreeTest#exactlyOneRoot} がここ1本であることを固定している。 */
    private static final String ROOT = "main";

    private List<AchievementsConfig.Achievement> achievements;
    private Map<String, AchievementsConfig.Achievement> byId;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        ConfigurationSection root = YamlConfiguration
                .loadConfiguration(new File(ACHIEVEMENTS))
                .getConfigurationSection("achievements");
        assertNotNull(root, "出荷 achievements.yml の achievements: セクションが読めない");
        AchievementsConfig.ParseResult result =
                AchievementsConfig.parse(root, Logger.getLogger("ShippedAchievementGraphReachabilityTest"));
        this.achievements = result.achievements();
        assertFalse(achievements.isEmpty(), "1件も読み込めていない");
        Map<String, AchievementsConfig.Achievement> map = new LinkedHashMap<>();
        achievements.forEach(a -> map.put(a.id(), a));
        this.byId = map;
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("全ノードが起点 main から到達可能(孤島が無い)")
    void everyNodeIsReachableFromRoot() {
        assertTrue(byId.containsKey(ROOT), "起点 '" + ROOT + "' が無い");
        // 子方向の隣接表を作る。parents-any は「どれか1つ」で開くので、どの前提から辿っても到達扱いにする。
        Map<String, List<String>> children = new LinkedHashMap<>();
        for (AchievementsConfig.Achievement achievement : achievements) {
            Set<String> prerequisites = new LinkedHashSet<>();
            if (achievement.parent() != null) {
                prerequisites.add(achievement.parent());
            }
            prerequisites.addAll(achievement.parentsAny());
            for (String prerequisite : prerequisites) {
                children.computeIfAbsent(prerequisite, k -> new ArrayList<>()).add(achievement.id());
            }
        }

        Set<String> reached = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        reached.add(ROOT);
        queue.add(ROOT);
        while (!queue.isEmpty()) {
            for (String child : children.getOrDefault(queue.poll(), List.of())) {
                if (reached.add(child)) {
                    queue.add(child);
                }
            }
        }

        List<String> unreachable = achievements.stream()
                .map(AchievementsConfig.Achievement::id)
                .filter(id -> !reached.contains(id))
                .toList();
        assertEquals(List.of(), unreachable,
                "起点 '" + ROOT + "' から到達できないノードがある(前提が永久に開かず、GUIにも枝として現れない): "
                        + unreachable);
    }

    /**
     * K-22(2) の回帰ガード。parent を {@code delve_all_seals} に戻すとここが落ちる。
     */
    @Test
    @DisplayName("第1目標 goal_worldbinder の前提に「全踏破」が入っていない")
    void firstGoalIsNotGatedBehindEveryDungeonSeal() {
        AchievementsConfig.Achievement goal = byId.get("goal_worldbinder");
        assertNotNull(goal, "goal_worldbinder が無い");
        assertEquals("delve_relics", goal.parent(),
                "第1目標の parent は delve_relics(深層3種)であること。"
                        + "delve_all_seals(19種すべての印)にすると、前提は達成そのものを縛るので"
                        + "「第1目標」が事実上いちばん最後にしか解けなくなる(K-22(2))。現在の値: "
                        + goal.parent());

        Set<String> ancestors = ancestorsOf("goal_worldbinder");
        assertFalse(ancestors.contains("delve_all_seals"),
                "goal_worldbinder の祖先に delve_all_seals が居る(経路が変わっただけで実質最後のまま): "
                        + ancestors);
    }

    /**
     * 「戻す」の副作用で収集側の枝が消えていないこと。{@code delve_all_seals} は図鑑コンプ側の枝として
     * {@code delve_relics} の下に残す方針(2026-08-01 プラン §1-4)。
     */
    @Test
    @DisplayName("delve_all_seals は収集系の別枝として残っている")
    void allSealsRemainsAsACollectionBranch() {
        AchievementsConfig.Achievement allSeals = byId.get("delve_all_seals");
        assertNotNull(allSeals, "delve_all_seals が消えている(19種の印を集める枝が失われる)");
        assertEquals("delve_relics", allSeals.parent(),
                "delve_all_seals は delve_relics の下に残すこと。現在の値: " + allSeals.parent());
        assertEquals(19, allSeals.trigger().collectionTargets().size(),
                "delve_all_seals は19種すべての印を対象にする");
    }

    /** parent 鎖をたどって祖先IDを集める(循環は {@link ShippedAchievementTreeTest} 側で禁じている)。 */
    private Set<String> ancestorsOf(String id) {
        Set<String> seen = new LinkedHashSet<>();
        String cursor = byId.get(id) == null ? null : byId.get(id).parent();
        while (cursor != null && seen.add(cursor)) {
            AchievementsConfig.Achievement next = byId.get(cursor);
            cursor = next == null ? null : next.parent();
        }
        return seen;
    }
}
