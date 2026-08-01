package com.trinityforge.config.domains;

import com.trinityforge.skilltree.DedicatedEffectEntry;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-01 実サーバ報告の修正 — <b>値を持たない機能フラグ({@code param:none})のツリー限定判定</b>。
 *
 * <p>{@code feature:break-vanilla-exp} は mining / woodcutting / digging / farming の
 * <b>4ツリーすべて</b>が A ノードに置いている。ツリーを問わない
 * {@link DedicatedEffectsConfig#isActiveByPerks(Set, String)} でこれを見ると、
 * <b>採掘の A しか取っていないプレイヤーが作物や原木を壊してもバニラEXPが出る</b>
 * (=残り3ツリー分の解放をタダ取りできる)。
 *
 * <p>{@link DedicatedEffectsConfig#isActiveByPerks(Set, String, String)} は同じ判定を
 * 「その配置がどのツリーに置かれているか」で絞る。ここではその絞り込みだけを縛る。
 */
class DedicatedEffectsSkillScopedFlagTest {

    private static final String FLAG = "feature:break-vanilla-exp";

    private static SkillNode node(String id, String effectId) {
        return new SkillNode(id, id, 10, SkillRole.MAIN, null, null, "STONE", 1, "desc",
                Map.of(), Map.of(), List.of(), List.of(),
                List.of(new DedicatedEffectEntry(effectId, null)));
    }

    private static SkillTree tree(String skill, String displayName) {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A", node("A", FLAG));
        return new SkillTree(skill, displayName, null, "2,10", null, nodes);
    }

    /** 4ツリーすべてが同じフラグを置いている、実配置そのままの索引。 */
    private static DedicatedEffectsConfig shippedShapedConfig() {
        DedicatedEffectsConfig config = new DedicatedEffectsConfig();
        config.reindex(List.of(
                tree("MINING", "採掘"),
                tree("WOODCUTTING", "伐採"),
                tree("DIGGING", "土木"),
                tree("FARMING", "農業")));
        return config;
    }

    @Test
    void miningOnlyUnlockDoesNotSatisfyTheOtherThreeTrees() {
        DedicatedEffectsConfig config = shippedShapedConfig();
        Set<String> miningOnly = Set.of("mining_perk_a");

        assertTrue(config.isActiveByPerks(miningOnly, FLAG, "MINING"));
        assertFalse(config.isActiveByPerks(miningOnly, FLAG, "WOODCUTTING"),
                "採掘ツリーの解放で伐採の破壊時バニラEXPまで出てはいけない");
        assertFalse(config.isActiveByPerks(miningOnly, FLAG, "DIGGING"));
        assertFalse(config.isActiveByPerks(miningOnly, FLAG, "FARMING"));
    }

    @Test
    void eachTreeUnlocksOnlyItsOwn() {
        DedicatedEffectsConfig config = shippedShapedConfig();

        assertTrue(config.isActiveByPerks(Set.of("woodcutting_perk_a"), FLAG, "WOODCUTTING"));
        assertFalse(config.isActiveByPerks(Set.of("woodcutting_perk_a"), FLAG, "MINING"));
        assertTrue(config.isActiveByPerks(Set.of("farming_perk_a"), FLAG, "FARMING"));
        assertFalse(config.isActiveByPerks(Set.of("farming_perk_a"), FLAG, "DIGGING"));
    }

    @Test
    void holdingSeveralTreesUnlocksEachOfThem() {
        DedicatedEffectsConfig config = shippedShapedConfig();
        Set<String> two = Set.of("mining_perk_a", "farming_perk_a");

        assertTrue(config.isActiveByPerks(two, FLAG, "MINING"));
        assertTrue(config.isActiveByPerks(two, FLAG, "FARMING"));
        assertFalse(config.isActiveByPerks(two, FLAG, "WOODCUTTING"));
    }

    @Test
    void nullOrBlankScopeStaysCrossTreeAndMatchesTheTwoArgumentForm() {
        DedicatedEffectsConfig config = shippedShapedConfig();
        Set<String> miningOnly = Set.of("mining_perk_a");

        assertTrue(config.isActiveByPerks(miningOnly, FLAG, null));
        assertTrue(config.isActiveByPerks(miningOnly, FLAG, "  "));
        assertTrue(config.isActiveByPerks(miningOnly, FLAG));
    }

    @Test
    void scopeMatchingIsCaseInsensitiveAndBareFeatureIdIsStillNormalized() {
        DedicatedEffectsConfig config = shippedShapedConfig();
        Set<String> miningOnly = Set.of("mining_perk_a");

        // ツリーの skill: は大文字だが、呼び出し側が小文字で渡しても同じ結果でなければならない。
        assertTrue(config.isActiveByPerks(miningOnly, FLAG, "mining"));
        // prefix 無しの素のIDも従来どおり feature: へ正規化される(既存の全gimmickリスナーの呼び方)。
        assertTrue(config.isActiveByPerks(miningOnly, "break-vanilla-exp", "MINING"));
        assertFalse(config.isActiveByPerks(miningOnly, "break-vanilla-exp", "FARMING"));
    }

    @Test
    void unknownScopeAndUnknownEffectAreFalse() {
        DedicatedEffectsConfig config = shippedShapedConfig();

        assertFalse(config.isActiveByPerks(Set.of("mining_perk_a"), FLAG, "NO_SUCH_TREE"));
        assertFalse(config.isActiveByPerks(Set.of("mining_perk_a"), "feature:no-such-effect", "MINING"));
        assertFalse(config.isActiveByPerks(Set.of(), FLAG, "MINING"));
        assertFalse(config.isActiveByPerks(null, FLAG, "MINING"));
        assertFalse(config.isActive(null, FLAG, "MINING"));
    }
}
