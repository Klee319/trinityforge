package com.trinityforge.stats;

import com.trinityforge.command.StatsCategory;
import com.trinityforge.progression.core.SkillId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「破壊時バニラEXP増加が職業間で漏れない」ことの回帰テスト (2026-08-15)。
 *
 * <p>背景: 実サーバ報告「破壊時バニラEXP解放のスキルが各職業間で共通になっちゃってない？」。
 * 解放ゲート({@code feature:break-vanilla-exp})は 2026-08-01 に採取スキル別へ直っていたが、
 * <b>倍率の {@code break_vanilla_exp_bonus} はスコープを持たない総合ステのまま</b>で、
 * 出荷スキルツリーの6ノード(mining/woodcutting/digging×2/farming×2)が全部そこへ 0.5 を配っていた。
 * 結果、採掘ツリーで取った +50% が伐採・整地・農業の破壊EXPにも乗っていた
 * (ノードの説明文は「破壊で1.5倍」等とツリー内で完結する前提の書き方)。
 *
 * <p>固定する不変条件は2つ:
 * <ol>
 *   <li>採取スキル別キーが4スキル分そろい、語彙・分類・{@code /tf stats} タブ・lore の
 *       4箇所に登録されている(どれか欠けるとそのスキルだけ無言で効かない/表示されない)</li>
 *   <li><b>出荷スキルツリーがスコープ無しの {@code break-vanilla-exp-bonus} を使っていない</b>
 *       — ここが崩れた瞬間、そのノードの倍率はまた全採取へ漏れる</li>
 * </ol>
 */
class BreakVanillaExpBonusKeysTest {

    /** 出荷スキルツリー全16本。追加時にここへ足さないと 2 番目の検査が素通りする。 */
    private static final List<String> SHIPPED_TREES = List.of(
            "alchemy", "archery", "ars_magic", "ars_smithing", "digging", "enchanting",
            "farming", "fishing", "heavy_armor", "heavy_weapons", "light_armor",
            "light_weapons", "mining", "power", "smithing", "woodcutting");

    /** スコープ無しキーの yml 行。{@code mining-break-...} に誤ヒットしないよう行頭から見る。 */
    private static final Pattern SCOPELESS_LINE =
            Pattern.compile("(?m)^\\s*break-vanilla-exp-bonus\\s*:");

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static String readShipped(String resource) throws Exception {
        try (InputStream in = BreakVanillaExpBonusKeysTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "出荷リソースが見つからない: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("採取扱いになる4スキル分だけキーがあり、他スキルには作られない")
    void coversTheFourGatheringSkillsOnly() {
        Set<String> keys = BreakVanillaExpBonusKeys.all();
        assertEquals(4, keys.size(), keys.toString());
        assertTrue(keys.contains("mining_break_vanilla_exp_bonus"), keys.toString());
        assertTrue(keys.contains("woodcutting_break_vanilla_exp_bonus"), keys.toString());
        assertTrue(keys.contains("digging_break_vanilla_exp_bonus"), keys.toString());
        assertTrue(keys.contains("farming_break_vanilla_exp_bonus"), keys.toString());

        // 破壊が採取扱いにならないスキルは null(= totalOf に渡す前に素通りできる)。
        assertNull(BreakVanillaExpBonusKeys.forSkill(SkillId.FISHING));
        assertNull(BreakVanillaExpBonusKeys.forSkill(SkillId.POWER));
        assertNull(BreakVanillaExpBonusKeys.forSkill(null));
        assertNull(BreakVanillaExpBonusKeys.forSkill(""));

        // ツリーごとに別のキーであること(ここが同じなら分割の意味が無い)。
        assertNotEquals(BreakVanillaExpBonusKeys.forSkill(SkillId.MINING),
                BreakVanillaExpBonusKeys.forSkill(SkillId.WOODCUTTING));
        assertNotEquals(BreakVanillaExpBonusKeys.forSkill(SkillId.DIGGING),
                BreakVanillaExpBonusKeys.forSkill(SkillId.FARMING));
    }

    @Test
    @DisplayName("全キーが 語彙GENERAL / UTILITY分類 / /tf stats の UTILITY タブ を満たす")
    void everyKeyIsRegisteredEverywhere() {
        List<String> problems = new ArrayList<>();
        for (String key : BreakVanillaExpBonusKeys.all()) {
            if (StatVocabulary.channelOf(key) != StatVocabulary.Channel.GENERAL) {
                problems.add(key + ": StatVocabulary 未登録(channel="
                        + StatVocabulary.channelOf(key) + ") → パーク/装備から合算されない");
            }
            if (StatCategoryInference.infer(key) != StatCategory.UTILITY) {
                problems.add(key + ": 分類が UTILITY でない("
                        + StatCategoryInference.infer(key)
                        + ")。mining- を含むキーが contains(\"mining\") で GATHERING へ落ちていないか");
            }
            if (!StatsCategory.UTILITY.includes(key)) {
                problems.add(key + ": /tf stats の UTILITY タブに出ない");
            }
        }
        assertEquals(List.of(), problems, String.join("\n", problems));
    }

    @Test
    @DisplayName("スコープ無しの break_vanilla_exp_bonus は「採取全般」として残っている")
    void scopelessKeyStillExists() {
        // 分割で消してしまうと、既存の base-stats.yml / アイテムステの記述が無言で無効になる。
        assertEquals(StatVocabulary.Channel.GENERAL,
                StatVocabulary.channelOf("break_vanilla_exp_bonus"));
    }

    @Test
    @DisplayName("出荷 stats/lore.yml に4キーの表示定義がある")
    void shippedLoreYmlDeclaresEveryKey() throws Exception {
        String lore = readShipped("stats/lore.yml");
        // 空振り防止のアンカー。
        assertTrue(lore.contains("break-vanilla-exp-bonus:"),
                "lore.yml を読めていない(この照合は空振りしている)");

        List<String> missing = new ArrayList<>();
        for (String key : BreakVanillaExpBonusKeys.all()) {
            String ymlKey = key.replace('_', '-') + ":";
            if (!lore.contains(ymlKey)) {
                missing.add(ymlKey);
            }
        }
        assertEquals(List.of(), missing,
                "stats/lore.yml に表示定義が無いキー(ステータス画面に一切出ない): " + missing);
    }

    @Test
    @DisplayName("featureId: 4スキル分の gate id が導出でき、他スキルは null")
    void featureIdCoversTheFourGatheringSkillsOnly() {
        assertEquals("break-vanilla-exp-mining", BreakVanillaExpBonusKeys.featureId(SkillId.MINING));
        assertEquals("break-vanilla-exp-woodcutting", BreakVanillaExpBonusKeys.featureId(SkillId.WOODCUTTING));
        assertEquals("break-vanilla-exp-digging", BreakVanillaExpBonusKeys.featureId(SkillId.DIGGING));
        assertEquals("break-vanilla-exp-farming", BreakVanillaExpBonusKeys.featureId(SkillId.FARMING));

        // 破壊が採取扱いにならないスキルは null(呼び出し側はゲート照会自体をスキップできる)。
        assertNull(BreakVanillaExpBonusKeys.featureId(SkillId.FISHING));
        assertNull(BreakVanillaExpBonusKeys.featureId(SkillId.POWER));
        assertNull(BreakVanillaExpBonusKeys.featureId(null));
        assertNull(BreakVanillaExpBonusKeys.featureId(""));

        // 大文字小文字を問わない(SkillId定数は大文字だが、小文字で呼ばれても解決できる)。
        assertEquals("break-vanilla-exp-mining", BreakVanillaExpBonusKeys.featureId("mining"));
    }

    @Test
    @DisplayName("featureId は FeatureEffectRegistry に実在する id を返す (2026-08-18 W-58)")
    void featureIdsAreRegisteredInFeatureEffectRegistry() {
        for (String skillId : BreakVanillaExpBonusKeys.GATHERING_SKILLS) {
            String featureId = BreakVanillaExpBonusKeys.featureId(skillId);
            assertTrue(com.trinityforge.skilltree.effects.FeatureEffectRegistry.isKnown(featureId),
                    "FeatureEffectRegistry に未登録: " + featureId);
        }
        // 旧・4ツリー共通の単一idはW-58で分割済みのため、もう存在しない。
        assertTrue(!com.trinityforge.skilltree.effects.FeatureEffectRegistry.isKnown("break-vanilla-exp"));
    }

    @Test
    @DisplayName("出荷スキルツリーはスコープ無しキーを使わず、各ツリーが自分のキーだけを使う")
    void shippedTreesUseTheirOwnScopedKey() throws Exception {
        List<String> leaks = new ArrayList<>();
        int scopedHits = 0;
        for (String tree : SHIPPED_TREES) {
            String yml = readShipped("skilltree/" + tree + ".yml");
            if (SCOPELESS_LINE.matcher(yml).find()) {
                leaks.add(tree + ".yml: スコープ無しの break-vanilla-exp-bonus を使っている"
                        + "(この倍率は他の採取スキルの破壊EXPにも乗る)");
            }
            for (String key : BreakVanillaExpBonusKeys.all()) {
                String ymlKey = key.replace('_', '-');
                // 1ツリーに複数ノードがあるので「出現回数」を数える(digging/farming は各2ノード)。
                int hits = countOccurrences(yml, ymlKey + ":");
                if (hits == 0) {
                    continue;
                }
                scopedHits += hits;
                String owner = ymlKey.substring(0, ymlKey.indexOf("-break-"));
                if (!owner.replace('-', '_').equals(tree)) {
                    leaks.add(tree + ".yml: 他ツリーのキー " + ymlKey + " を配っている");
                }
            }
        }
        assertTrue(scopedHits >= 6,
                "採取スキル別キーが出荷ツリーで6箇所未満しか使われていない(" + scopedHits
                        + ")。共通キーへ戻っていないか確認する");
        assertEquals(List.of(), leaks, String.join("\n", leaks));
    }

    /** 4採取ツリーのバレル。{@link #SHIPPED_TREES}(16本)からの絞り込み。 */
    private static final List<String> GATHERING_TREES = List.of("mining", "digging", "farming", "woodcutting");

    @Test
    @DisplayName("2026-08-18 (W-58): 4採取ツリーは feature:break-vanilla-exp-<tree> だけを使い、"
            + "旧・共通idはもう使っていない")
    void shippedGatheringTreesUseTheirOwnScopedFeatureGate() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String tree : GATHERING_TREES) {
            String yml = readShipped("skilltree/" + tree + ".yml");
            String expectedGate = "feature:break-vanilla-exp-" + tree;
            if (!yml.contains(expectedGate)) {
                problems.add(tree + ".yml: 期待するゲート " + expectedGate + " が見つからない");
            }
            // 旧・4ツリー共通の単一id(サフィックス無し)が残っていないこと。行頭一致で
            // "feature:break-vanilla-exp-mining" 等への誤ヒットを避ける(直後に文字が続かないことを確認)。
            if (Pattern.compile("feature:break-vanilla-exp(?!-)").matcher(yml).find()) {
                problems.add(tree + ".yml: 旧・共通id(feature:break-vanilla-exp、サフィックス無し)がまだ残っている");
            }
        }
        assertEquals(List.of(), problems, String.join("\n", problems));
    }
}
