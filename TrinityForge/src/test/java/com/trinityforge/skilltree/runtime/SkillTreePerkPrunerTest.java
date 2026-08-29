package com.trinityforge.skilltree.runtime;

import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「スキルツリーからノードが消えた perk」の判定を固定する (2026-08-24 / W-213)。
 *
 * <p>ここが緩むと支払い済みの SP が永久に死蔵し、逆に厳しすぎると現役ノードの解放を
 * 剥がしてしまう。どちらも無言で起きるので、判定は表として固定しておく。
 */
class SkillTreePerkPrunerTest {

    private static SkillNode node(String id) {
        return new SkillNode(id, "name-" + id, 10, SkillRole.MAIN, null, null, "STONE", 1,
                "effect", Map.of(), Map.of(), List.of(), List.of(), List.of());
    }

    private static SkillTree tree(String skill, String... nodeIds) {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        for (String id : nodeIds) {
            nodes.put(id, node(id));
        }
        return new SkillTree(skill, skill, "STONE", "2,10", null, nodes);
    }

    @Test
    void nodesThatStillExistAreNeverTouched() {
        Set<String> orphans = SkillTreePerkPruner.orphanPerkIds(
                Set.of("woodcutting_perk_a", "woodcutting_perk_a_2"),
                List.of(tree("WOODCUTTING", "A", "A-2")));
        assertTrue(orphans.isEmpty(), "現役ノードのperkを剥がしてはいけない: " + orphans);
    }

    @Test
    void deletedNodeIsReportedAsOrphan() {
        // 実際に起きた被害 (2026-08-24 の実データ): 伐採の A-2-1..A-2-4 がツリーから消えており、
        // 14人が計30件を保持したまま、その支払い分の SP が戻らなくなっていた。
        Set<String> orphans = SkillTreePerkPruner.orphanPerkIds(
                Set.of("woodcutting_perk_a", "woodcutting_perk_a_2",
                        "woodcutting_perk_a_2_1", "woodcutting_perk_a_2_4"),
                List.of(tree("WOODCUTTING", "A", "A-2")));
        assertEquals(Set.of("woodcutting_perk_a_2_1", "woodcutting_perk_a_2_4"), orphans);
    }

    @Test
    void perksOfATreeThatIsNotLoadedAreLeftAlone() {
        // 「ノードが消えた」と「ツリーがまだ読めていない」は区別できないので、
        // プレフィックスの一致するツリーが1つも無い perk は判定対象にしない。
        Set<String> orphans = SkillTreePerkPruner.orphanPerkIds(
                Set.of("mining_perk_a", "mining_perk_zzz"),
                List.of(tree("WOODCUTTING", "A")));
        assertTrue(orphans.isEmpty(), "未ロードのスキルには触らない: " + orphans);
    }

    @Test
    void prestigeAndRootPerksHaveNoNodeAndAreExempt() {
        // *_perk_ng<段> はプレステージの恒久perk、*_perk_root は合成されたルート。
        // どちらも nodes に対応が無いので、素朴に判定すると全員から剥がしてしまう。
        Set<String> orphans = SkillTreePerkPruner.orphanPerkIds(
                Set.of("woodcutting_perk_ng1", "woodcutting_perk_ng12", "woodcutting_perk_root"),
                List.of(tree("WOODCUTTING", "A")));
        assertTrue(orphans.isEmpty(), "恒久perkを剥がしてはいけない: " + orphans);
    }

    @Test
    void prestigeLookalikeThatIsNotATierIsStillJudged() {
        // ng の後ろが数字でないものはプレステージperkではない(ノード ID 由来)。
        Set<String> orphans = SkillTreePerkPruner.orphanPerkIds(
                Set.of("woodcutting_perk_ng", "woodcutting_perk_ngx1"),
                List.of(tree("WOODCUTTING", "A")));
        assertEquals(Set.of("woodcutting_perk_ng", "woodcutting_perk_ngx1"), orphans);
    }

    @Test
    void skillsWhoseCompactNameSharesASuffixAreNotConfused() {
        // ARS_SMITHING → arssmithing / SMITHING → smithing。前方一致なので取り違えないことを固定する
        // (取り違えると、片方のツリーに在るノードを他方の孤児と誤判定して剥がす)。
        Set<String> orphans = SkillTreePerkPruner.orphanPerkIds(
                Set.of("arssmithing_perk_a", "arssmithing_perk_a_3", "smithing_perk_a"),
                List.of(tree("ARS_SMITHING", "A"), tree("SMITHING", "A")));
        assertEquals(Set.of("arssmithing_perk_a_3"), orphans);
    }

    @Test
    void nodeIdsAreComparedThroughTheSameNormalizationAsPurchase() {
        // 購入時の perk ID は PerkNaming.normalizeNodeId 経由(A-alpha-1 → a_alpha_1)。
        // 判定側が生の ID で比較すると、ギリシャ路線が丸ごと孤児に見える。
        Set<String> orphans = SkillTreePerkPruner.orphanPerkIds(
                Set.of("woodcutting_perk_a_alpha_1", "woodcutting_perk_c_1_1"),
                List.of(tree("WOODCUTTING", "A-alpha-1", "C-1-1")));
        assertTrue(orphans.isEmpty(), "正規化を通した比較になっていない: " + orphans);
    }

    @Test
    void nothingIsJudgedWhenNoTreeIsLoaded() {
        // ツリー0本 = 全部が孤児に見える最悪ケース。ここで空を返さないと全員の解放が消える。
        assertTrue(SkillTreePerkPruner.orphanPerkIds(
                Set.of("woodcutting_perk_a"), List.of()).isEmpty());
        assertTrue(SkillTreePerkPruner.orphanPerkIds(
                Set.of("woodcutting_perk_a"), null).isEmpty());
    }
}
