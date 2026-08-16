package com.trinityforge.placeholder;

import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.ranking.RankingStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code %trinityforge_...%} の書式解釈。
 *
 * <p>一番壊れやすいのは<b>スキル ID とフィールド名の切り分け</b>。スキル ID 自体が
 * {@code heavy_armor} のようにアンダースコアを含むため、素朴に先頭で切ると
 * 全ての複合語スキルが黙って解決不能になる（順位表からその列が丸ごと消える）。
 */
class PlaceholderResolverTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private static PlayerProgression progressionWith(String skillId, SkillProgress progress) {
        return PlayerProgression.empty(PLAYER).withSkill(skillId, progress);
    }

    private static String resolve(String params, PlayerProgression progression, RankingStats stats) {
        Supplier<PlayerProgression> p = () -> progression;
        Supplier<RankingStats> r = () -> stats;
        return PlaceholderResolver.resolve(params, p, r);
    }

    // ---- スキル系 ------------------------------------------------------------------

    @Test
    @DisplayName("単語スキルのレベルを返す")
    void singleWordSkillLevel() {
        PlayerProgression progression =
                progressionWith(SkillId.MINING, new SkillProgress(37, 5.0, 12345.6, 2, 100));

        assertEquals("37", resolve("skill_mining_level", progression, RankingStats.EMPTY));
    }

    @Test
    @DisplayName("複合語スキル(heavy_armor)でも ID とフィールドを取り違えない")
    void compoundSkillIdIsSplitAtTheLastUnderscore() {
        PlayerProgression progression =
                progressionWith(SkillId.HEAVY_ARMOR, new SkillProgress(21, 0.0, 900.0, 1, 100));

        assertEquals("21", resolve("skill_heavy_armor_level", progression, RankingStats.EMPTY));
        assertEquals("1", resolve("skill_heavy_armor_prestige", progression, RankingStats.EMPTY));
        assertEquals("900", resolve("skill_heavy_armor_totalexp", progression, RankingStats.EMPTY));
    }

    @Test
    @DisplayName("16 スキルすべてが level で解決できる")
    void everySkillIdResolves() {
        for (String skillId : SkillId.ALL) {
            PlayerProgression progression =
                    progressionWith(skillId, new SkillProgress(9, 0.0, 0.0, 0, 100));
            String params = "skill_" + skillId.toLowerCase(java.util.Locale.ROOT) + "_level";

            assertEquals("9", resolve(params, progression, RankingStats.EMPTY),
                    "解決できないスキル ID があるとランキングからその列が消える: " + params);
        }
    }

    @Test
    @DisplayName("未取得スキルは null ではなく 0（順位表から行を落とさない）")
    void unknownSkillRowYieldsZeroNotNull() {
        PlayerProgression empty = PlayerProgression.empty(PLAYER);

        assertEquals("0", resolve("skill_fishing_level", empty, RankingStats.EMPTY));
        assertEquals("0", resolve("skill_fishing_prestige", empty, RankingStats.EMPTY));
        assertEquals("0", resolve("skill_fishing_totalexp", empty, RankingStats.EMPTY));
    }

    @Test
    @DisplayName("累計 EXP は整数へ丸める（指数表記だと数値ソートが壊れる）")
    void totalExpIsRoundedToAnInteger() {
        PlayerProgression progression =
                progressionWith(SkillId.ALCHEMY, new SkillProgress(3, 0.0, 1.23456789E8, 0, 100));

        String value = resolve("skill_alchemy_totalexp", progression, RankingStats.EMPTY);

        assertEquals("123456789", value);
        assertTrue(value.chars().allMatch(Character::isDigit), "指数表記や小数点を含まないこと");
    }

    @Test
    @DisplayName("skill_total_level は 16 スキルのレベル合計")
    void totalLevelSumsEverySkill() {
        PlayerProgression progression = PlayerProgression.empty(PLAYER)
                .withSkill(SkillId.MINING, new SkillProgress(10, 0.0, 0.0, 0, 100))
                .withSkill(SkillId.FARMING, new SkillProgress(5, 0.0, 0.0, 0, 100))
                .withSkill(SkillId.HEAVY_ARMOR, new SkillProgress(7, 0.0, 0.0, 0, 100));

        assertEquals("22", resolve("skill_total_level", progression, RankingStats.EMPTY));
    }

    @Test
    @DisplayName("合計は level だけ。prestige/totalexp の合計は出さない")
    void totalOnlySupportsLevel() {
        PlayerProgression progression =
                progressionWith(SkillId.MINING, new SkillProgress(10, 0.0, 50.0, 3, 100));

        assertNull(resolve("skill_total_prestige", progression, RankingStats.EMPTY));
        assertNull(resolve("skill_total_totalexp", progression, RankingStats.EMPTY));
    }

    @Test
    @DisplayName("存在しないスキル ID / フィールドは null（未知の placeholder は素通し）")
    void unknownSkillOrFieldIsNull() {
        PlayerProgression progression =
                progressionWith(SkillId.MINING, new SkillProgress(10, 0.0, 0.0, 0, 100));

        assertNull(resolve("skill_cooking_level", progression, RankingStats.EMPTY));
        assertNull(resolve("skill_mining_bogus", progression, RankingStats.EMPTY));
        assertNull(resolve("skill_heavy_level", progression, RankingStats.EMPTY),
                "heavy は単体ではスキルではない");
        assertNull(resolve("skill_mining", progression, RankingStats.EMPTY));
        assertNull(resolve("skill_mining_", progression, RankingStats.EMPTY));
    }

    @Test
    @DisplayName("大文字で書かれても解決する")
    void paramsAreCaseInsensitive() {
        PlayerProgression progression =
                progressionWith(SkillId.LIGHT_WEAPONS, new SkillProgress(44, 0.0, 0.0, 0, 100));

        assertEquals("44", resolve("SKILL_LIGHT_WEAPONS_LEVEL", progression, RankingStats.EMPTY));
    }

    @Test
    @DisplayName("進行サービス未初期化でも書式が正しければ 0 を返す")
    void missingProgressionYieldsZero() {
        assertEquals("0", resolve("skill_mining_level", null, RankingStats.EMPTY));
    }

    // ---- ランキング系 --------------------------------------------------------------

    @Test
    @DisplayName("図鑑・グリフ・討伐数を返す")
    void rankingValues() {
        RankingStats stats = new RankingStats(80, 35, 19, 12345);
        PlayerProgression progression = PlayerProgression.empty(PLAYER);

        assertEquals("115", resolve("collection_entries", progression, stats));
        assertEquals("80", resolve("collection_items", progression, stats));
        assertEquals("35", resolve("collection_mobs", progression, stats));
        assertEquals("19", resolve("glyphs_unlocked", progression, stats));
        assertEquals("12345", resolve("kills_total", progression, stats));
    }

    @Test
    @DisplayName("集計が無いプレイヤーは 0（null にすると順位表から消える）")
    void missingRankingYieldsZero() {
        PlayerProgression progression = PlayerProgression.empty(PLAYER);

        assertEquals("0", resolve("collection_entries", progression, null));
        assertEquals("0", resolve("glyphs_unlocked", progression, null));
        assertEquals("0", resolve("kills_total", progression, null));
    }

    @Test
    @DisplayName("未知の書式・空文字は null")
    void unknownParamsAreNull() {
        PlayerProgression progression = PlayerProgression.empty(PLAYER);

        assertNull(resolve("", progression, RankingStats.EMPTY));
        assertNull(resolve(null, progression, RankingStats.EMPTY));
        assertNull(resolve("mana", progression, RankingStats.EMPTY));
        assertNull(resolve("collection", progression, RankingStats.EMPTY));
    }

    @Test
    @DisplayName("スキル系の解決でランキング側を評価しない（無駄な SQLite 読みを起こさない）")
    void skillLookupDoesNotTouchRankingSource() {
        PlayerProgression progression =
                progressionWith(SkillId.MINING, new SkillProgress(10, 0.0, 0.0, 0, 100));
        boolean[] rankingEvaluated = {false};

        PlaceholderResolver.resolve("skill_mining_level", () -> progression, () -> {
            rankingEvaluated[0] = true;
            return RankingStats.EMPTY;
        });

        assertTrue(!rankingEvaluated[0], "遅延評価が壊れるとプレースホルダ 1 個ごとに DB を叩く");
    }
}
