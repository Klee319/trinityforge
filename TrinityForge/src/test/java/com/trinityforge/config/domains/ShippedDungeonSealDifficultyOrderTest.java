package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 実績(アチーブメント)と図鑑に並ぶ「ダンジョンの印(dungeon_seal_*)」が、ダンジョン難易度の
 * 昇順に並んでいることを固定する(2026-08-25 / W-229 ユーザー決定「ダンジョン難易度順に並べる」)。
 *
 * <h2>なぜこの並びを直す必要があったか</h2>
 * 旧 K-22(2)「{@code goal_worldbinder} の parent が {@code delve_all_seals}(全印収集)で、
 * 名目上の第1目標が実質いちばん最後にしか解けない」は、2026-08-16 の再構築で
 * {@code goal_worldbinder} の parent を武器階梯側({@code w_infinity})へ切り離したことで
 * <b>達成そのものを縛る意味では既に解消済み</b>({@link ShippedAchievementGraphReachabilityTest}
 * が固定している)。しかし {@code seal_27}(束縛者以外の印27種を集める)の {@code targets}
 * と {@code collection.yml} の図鑑カテゴリ {@code dungeon} の {@code entries} は、印を
 * 19種→28種へ組み替えた際の並び(概ね命名順)のままで、<b>易しいダンジョンと難しいダンジョンが
 * 交互に並ぶ</b>状態だった。プレイヤーが「次に何を踏破すればよいか」を一覧から読み取れるよう、
 * ダンジョン難易度の昇順(同着は id 昇順)へ並べ替えた。
 *
 * <h2>難易度の一次情報</h2>
 * {@code combat/mob-overrides.yml} には {@code difficulty:} のような機械可読キーは無く、
 * 各ダンジョンの {@code stats:} 直下にある「難易度 N の係数 = …(難易度1が最大・難易度10で
 * 1.0 の逓減梯子)。」というコメントだけが一次情報(ソースコード上の事実として2026-08-25に
 * 確認済み)。このテストは<b>期待する順序を書き写さず</b>、そのコメントと踏破ボスの
 * {@code drops} 行(印の実体が生まれる唯一の場所、{@link ShippedDungeonSealLedgerDriftTest}
 * と同じ考え方)を出荷 yml から直接読み、ID→難易度の対応表をその場で組み立てる。
 * 各ダンジョンの「難易度N の係数」行は、そのダンジョン自身の印ドロップ行より必ず先に出現する
 * (両方とも同じダンジョンブロック内にあり、係数はブロック冒頭、ドロップはボスのモブ定義の中)。
 */
class ShippedDungeonSealDifficultyOrderTest {

    private static final String MOB_OVERRIDES = "src/main/resources/combat/mob-overrides.yml";
    private static final String ACHIEVEMENTS = "src/main/resources/progression/achievements.yml";
    private static final String COLLECTION = "src/main/resources/progression/collection.yml";

    private static final Pattern DIFFICULTY_LINE =
            Pattern.compile("難易度(\\d+)\\s*の係数\\s*=");
    private static final Pattern SEAL_DROP_LINE =
            Pattern.compile("custom:dungeon_seal_([a-z0-9_]+)");
    private static final String SEAL_PREFIX = "dungeon_seal_";

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("seal_27(束縛者以外の印27種)の targets はダンジョン難易度の昇順に並んでいる")
    void seal27TargetsAreOrderedByDungeonDifficulty() {
        Map<String, Integer> difficultyBySeal = difficultyBySealFromMobOverrides();

        ConfigurationSection root = loadShipped(ACHIEVEMENTS).getConfigurationSection("achievements");
        assertNotNull(root, "achievements.yml の achievements セクションが読めない");
        ConfigurationSection seal27 = root.getConfigurationSection("seal_27.trigger.collection");
        assertNotNull(seal27, "seal_27.trigger.collection が無い(ノードIDか構造が変わった"
                + "ならこのテストも直すこと)");
        List<String> targets = seal27.getStringList("targets");
        assertFalse(targets.isEmpty(), "seal_27 の targets が空");

        assertOrderedByDifficulty(targets, difficultyBySeal, "achievements.yml の seal_27.targets");
    }

    @Test
    @DisplayName("図鑑カテゴリ dungeon の entries もダンジョン難易度の昇順に並んでいる")
    void collectionDungeonCategoryEntriesAreOrderedByDungeonDifficulty() {
        Map<String, Integer> difficultyBySeal = difficultyBySealFromMobOverrides();

        ConfigurationSection dungeonCategory =
                loadShipped(COLLECTION).getConfigurationSection("categories.items.dungeon");
        assertNotNull(dungeonCategory, "collection.yml の categories.items.dungeon が無い"
                + "(構造が変わったならこのテストも直すこと)");
        List<String> entries = dungeonCategory.getStringList("entries");
        assertFalse(entries.isEmpty(), "categories.items.dungeon.entries が空");

        // 「踏破の証」そのものではない末尾2件(binder_fragment / reality_thread_core)や、
        // 束縛者自身の印(dungeon_seal_binder、常に最高難易度なので並び順の対象に含めても
        // 矛盾は起きないが、意味的には別枠)を除いた「難易度が分かる印」だけを対象にする。
        List<String> sealEntries = entries.stream()
                .filter(id -> id.startsWith(SEAL_PREFIX))
                .toList();
        assertFalse(sealEntries.isEmpty(), "dungeon カテゴリに dungeon_seal_* が1件も無い");

        assertOrderedByDifficulty(sealEntries, difficultyBySeal,
                "collection.yml の categories.items.dungeon.entries");
    }

    /** 非減少列であることを確認する。崩れている隣接ペアをそのままメッセージへ出す。 */
    private static void assertOrderedByDifficulty(
            List<String> ids, Map<String, Integer> difficultyBySeal, String sourceLabel) {
        int previousDifficulty = -1;
        String previousId = null;
        for (String id : ids) {
            Integer difficulty = difficultyBySeal.get(id);
            assertNotNull(difficulty, sourceLabel + " の '" + id + "' の難易度が "
                    + MOB_OVERRIDES + " から読めない(印の綴りが違うか、そのダンジョンの"
                    + "「難易度N の係数」コメントが消えている)");
            assertTrue(difficulty >= previousDifficulty,
                    sourceLabel + " の並びがダンジョン難易度の昇順になっていない: "
                            + previousId + "(難易度" + previousDifficulty + ") の直後に "
                            + id + "(難易度" + difficulty + ") が来ている");
            previousDifficulty = difficulty;
            previousId = id;
        }
    }

    /**
     * {@code combat/mob-overrides.yml} を行単位で読み、各ダンジョンの
     * 「難易度N の係数」コメントと、そのダンジョンの踏破ボスが落とす {@code dungeon_seal_*} を
     * 順番に結び付けて ID→難易度の対応表を作る。YAML パーサを使わないのは、難易度が
     * 機械可読キーではなくコメントにしか存在しないため(2026-08-25 時点の事実)。
     */
    private static Map<String, Integer> difficultyBySealFromMobOverrides() {
        List<String> lines;
        try {
            lines = Files.readAllLines(new File(MOB_OVERRIDES).toPath());
        } catch (IOException ex) {
            throw new AssertionError(MOB_OVERRIDES + " を読めない: " + ex.getMessage(), ex);
        }

        Map<String, Integer> out = new LinkedHashMap<>();
        int currentDifficulty = -1;
        for (String line : lines) {
            Matcher difficultyMatch = DIFFICULTY_LINE.matcher(line);
            if (difficultyMatch.find()) {
                currentDifficulty = Integer.parseInt(difficultyMatch.group(1));
                continue;
            }
            Matcher sealMatch = SEAL_DROP_LINE.matcher(line);
            if (sealMatch.find() && currentDifficulty >= 0) {
                String sealId = (SEAL_PREFIX + sealMatch.group(1)).toLowerCase(Locale.ROOT);
                out.putIfAbsent(sealId, currentDifficulty);
            }
        }
        assertFalse(out.isEmpty(), MOB_OVERRIDES + " から「難易度N の係数」と結び付く"
                + "dungeon_seal_* を1件も読めていない(コメントの文言かドロップ表記が変わった)");
        return out;
    }

    private static YamlConfiguration loadShipped(String path) {
        File file = new File(path);
        assertTrue(file.isFile(), "出荷リソースが見つからない: " + file.getAbsolutePath());
        return YamlConfiguration.loadConfiguration(file);
    }
}
