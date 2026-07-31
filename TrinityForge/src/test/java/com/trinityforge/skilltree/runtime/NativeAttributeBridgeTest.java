package com.trinityforge.skilltree.runtime;

import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.generator.PerkNaming;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link NativeAttributeBridge#armorAttributesFor}: armor-set-buffs 全面移行後の挙動
 * (skilltree {@code set-buffs} スキーマ + {@code armor-set-bonus} 増幅、SKILL_TREE
 * armor-set-buffs migration §1/§2)。{@link PerkBuffResolver} は実インスタンスを使い、
 * {@link SkillPerkStatSource} だけをテストごとに差し替える(Mockito不要・純粋な組み立て)。
 */
class NativeAttributeBridgeTest {

    private static final String LIGHT = "LIGHT_ARMOR";
    private static final String HEAVY = "HEAVY_ARMOR";
    private static final double EPS = 1e-9;

    private ServerMock server;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static SkillNode node(String id, Map<Integer, Map<String, Double>> setBuffs) {
        return new SkillNode(id, "name-" + id, 10, SkillRole.MAIN, null, List.of(), null, "STONE", 1,
                "effect", Map.of(), Map.of(), Map.of(), Map.of(), setBuffs, Map.of(), List.of(), List.of(),
                List.of());
    }

    private static SkillNode plainBuffsNode(String id, Map<String, Double> buffs) {
        return new SkillNode(id, "name-" + id, 10, SkillRole.MAIN, null, null, "STONE", 1,
                "effect", buffs, Map.of(), List.of(), List.of(), List.of());
    }

    private static SkillTree tree(String skill, Map<String, SkillNode> nodes) {
        return new SkillTree(skill, skill, "DIAMOND_CHESTPLATE", "2,10", null, nodes);
    }

    private static String perk(String skill, String nodeId) {
        return PerkNaming.perkId(skill, nodeId);
    }

    /** Builds a bridge wired to a resolver that only unlocks the given skill/node pairs. */
    private NativeAttributeBridge bridgeFor(List<SkillTree> trees, Set<String> unlockedPerkIds) {
        SkillPerkStatSource source = id -> unlockedPerkIds;
        PerkBuffResolver resolver = new PerkBuffResolver(source, () -> trees);
        return new NativeAttributeBridge(resolver);
    }

    private void wearLight(int pieces) {
        wearArmor(java.util.Collections.nCopies(pieces, Material.LEATHER_BOOTS).toArray(new Material[0]));
    }

    private void wearHeavy(int pieces) {
        wearArmor(java.util.Collections.nCopies(pieces, Material.IRON_BOOTS).toArray(new Material[0]));
    }

    private void wearMixed(int lightPieces, int heavyPieces) {
        List<Material> materials = new java.util.ArrayList<>();
        for (int i = 0; i < lightPieces; i++) materials.add(Material.LEATHER_BOOTS);
        for (int i = 0; i < heavyPieces; i++) materials.add(Material.IRON_BOOTS);
        wearArmor(materials.toArray(new Material[0]));
    }

    private void wearArmor(Material... materials) {
        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < materials.length && i < 4; i++) {
            armor[i] = new ItemStack(materials[i]);
        }
        player.getInventory().setArmorContents(armor);
    }

    // --- tier selection (light_armor tree) ---

    @Test
    void twoPiecesDoNotSatisfyAnyTier() {
        SkillNode c = node("C", Map.of(3, Map.of("dodge-chance", 0.1), 4, Map.of("dodge-chance", 0.1)));
        SkillTree lightTree = tree(LIGHT, Map.of("C", c));
        NativeAttributeBridge bridge = bridgeFor(List.of(lightTree), Set.of(perk(LIGHT, "C")));
        wearLight(2);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertFalse(attrs.containsKey("dodge_chance"), "2部位ではどの段も成立してはいけない");
    }

    @Test
    void threePiecesSatisfyTierThree() {
        SkillNode c = node("C", Map.of(3, Map.of("dodge-chance", 0.1), 4, Map.of("dodge-chance", 0.2)));
        SkillTree lightTree = tree(LIGHT, Map.of("C", c));
        NativeAttributeBridge bridge = bridgeFor(List.of(lightTree), Set.of(perk(LIGHT, "C")));
        wearLight(3);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertEquals(0.1, attrs.get("dodge_chance"), EPS, "3部位では段3の値だけが採用されるべき");
    }

    @Test
    void fourPiecesAdoptTierFourOnlyNotBothTiers() {
        SkillNode c = node("C", Map.of(3, Map.of("dodge-chance", 0.1), 4, Map.of("dodge-chance", 0.2)));
        SkillTree lightTree = tree(LIGHT, Map.of("C", c));
        NativeAttributeBridge bridge = bridgeFor(List.of(lightTree), Set.of(perk(LIGHT, "C")));
        wearLight(4);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertEquals(0.2, attrs.get("dodge_chance"), EPS,
                "4部位では段4だけが採用され、段3(0.1)は加算されてはいけない(0.1+0.2=0.3になってはならない)");
    }

    @Test
    void undefinedTierFourFallsBackToTierThreeAtFourPieces() {
        SkillNode c = node("C", Map.of(3, Map.of("dodge-chance", 0.1)));
        SkillTree lightTree = tree(LIGHT, Map.of("C", c));
        NativeAttributeBridge bridge = bridgeFor(List.of(lightTree), Set.of(perk(LIGHT, "C")));
        wearLight(4);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertEquals(0.1, attrs.get("dodge_chance"), EPS,
                "段4が未定義なら4部位でも成立している最大の定義済み段(3)を採用するべき");
    }

    // --- armor-set-bonus amplification ---

    @Test
    void armorSetBonusAmplifiesTheSelectedTierValue() {
        SkillNode c = node("C", Map.of(3, Map.of("dodge-chance", 0.1)));
        SkillNode bonus = plainBuffsNode("B", Map.of("armor-set-bonus", 0.5));
        SkillTree lightTree = tree(LIGHT, Map.of("C", c, "B", bonus));
        NativeAttributeBridge bridge = bridgeFor(List.of(lightTree),
                Set.of(perk(LIGHT, "C"), perk(LIGHT, "B")));
        wearLight(3);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertEquals(0.1 * 1.5, attrs.get("dodge_chance"), EPS,
                "armor-set-bonus=0.5 は採用された段の値全体に ×(1+0.5) を掛けるべき");
    }

    @Test
    void negativeArmorSetBonusIsFlooredAtZero() {
        SkillNode c = node("C", Map.of(3, Map.of("dodge-chance", 0.1)));
        SkillNode bonus = plainBuffsNode("B", Map.of("armor-set-bonus", -0.9));
        SkillTree lightTree = tree(LIGHT, Map.of("C", c, "B", bonus));
        NativeAttributeBridge bridge = bridgeFor(List.of(lightTree),
                Set.of(perk(LIGHT, "C"), perk(LIGHT, "B")));
        wearLight(3);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertEquals(0.1, attrs.get("dodge_chance"), EPS, "負のarmor-set-bonusは0扱い(増幅なし)であるべき");
    }

    // --- light/heavy mutual exclusion ---

    @Test
    void threeLightPlusOneHeavyNeverSatisfiesHeavySet() {
        SkillNode lightC = node("C", Map.of(3, Map.of("dodge-chance", 0.1)));
        SkillNode heavyC = node("C", Map.of(3, Map.of("knockback-resistance", 0.1)));
        SkillTree lightTree = tree(LIGHT, Map.of("C", lightC));
        SkillTree heavyTree = tree(HEAVY, Map.of("C", heavyC));
        NativeAttributeBridge bridge = bridgeFor(List.of(lightTree, heavyTree),
                Set.of(perk(LIGHT, "C"), perk(HEAVY, "C")));
        wearMixed(3, 1);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertEquals(0.1, attrs.get("dodge_chance"), EPS, "軽装3部位のセットは成立するべき");
        assertFalse(attrs.containsKey("knockback_resistance"),
                "重装1部位ではセットが成立してはいけない(軽装3+重装1のハイブリッドは併用不能であるべき)");
    }

    /**
     * 2026-07-26 に確立された防具枠4に対する閾値3の設計(段3+段3&gt;4)は set-buffs 移行後も維持される:
     * 金装備3部位は {@code UseSkillDefaults.isLightArmor} 経由で軽装として成立し、重装セットは成立しない。
     */
    @Test
    void goldenArmorCountsAsLightMatchingUseSkillDefaults() {
        SkillNode lightC = node("C", Map.of(3, Map.of("dodge-chance", 0.1)));
        SkillNode heavyC = node("C", Map.of(3, Map.of("knockback-resistance", 0.1)));
        SkillTree lightTree = tree(LIGHT, Map.of("C", lightC));
        SkillTree heavyTree = tree(HEAVY, Map.of("C", heavyC));
        NativeAttributeBridge bridge = bridgeFor(List.of(lightTree, heavyTree),
                Set.of(perk(LIGHT, "C"), perk(HEAVY, "C")));
        wearArmor(Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertEquals(0.1, attrs.get("dodge_chance"), EPS, "金装備3部位は軽装セットとして成立するべき");
        assertFalse(attrs.containsKey("knockback_resistance"), "金装備が重装セットを成立させてはいけない");
    }

    // --- move-speed: 2026-07-31 に「部位数比例の平坦バフ」から set-buffs へ移行 ---

    /**
     * 旧 {@code light-/heavy-armor-move-speed-per-piece} は撤去され、移動速度は
     * {@code set-buffs} の {@code move-speed}(段3/4)から来る。閾値未満では一切効かない
     * (これが移行で変わった唯一の挙動)。
     */
    @Test
    void moveSpeedNowComesFromSetBuffsAndRespectsTheThreshold() {
        SkillNode a = node("A", Map.of(3, Map.of("move-speed", 0.015), 4, Map.of("move-speed", 0.02)));
        SkillTree lightTree = tree(LIGHT, Map.of("A", a));
        NativeAttributeBridge bridge = bridgeFor(List.of(lightTree), Set.of(perk(LIGHT, "A")));

        wearLight(2);
        assertFalse(bridge.armorAttributesFor(player).containsKey("move_speed"),
                "2部位では段3が成立しないので移動速度は付かない");

        wearLight(3);
        assertEquals(0.015, bridge.armorAttributesFor(player).get("move_speed"), EPS);

        wearLight(4);
        assertEquals(0.02, bridge.armorAttributesFor(player).get("move_speed"), EPS);
    }

    /** 撤去した旧キーを平坦 buffs に書いても、もう move_speed には一切ならないこと。 */
    @Test
    void retiredPerPieceKeysNoLongerProduceMoveSpeed() {
        SkillNode a = plainBuffsNode("A", Map.of("light-armor-move-speed-per-piece", 0.5));
        SkillTree lightTree = tree(LIGHT, Map.of("A", a));
        NativeAttributeBridge bridge = bridgeFor(List.of(lightTree), Set.of(perk(LIGHT, "A")));
        wearLight(4);

        assertFalse(bridge.armorAttributesFor(player).containsKey("move_speed"),
                "撤去した per-piece キーが move_speed を生んでいる");
    }
}
