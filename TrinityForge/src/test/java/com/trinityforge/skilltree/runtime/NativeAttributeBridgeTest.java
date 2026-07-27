package com.trinityforge.skilltree.runtime;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NativeAttributeBridge#armorAttributesFor}: regression for the MEDIUM audit finding —
 * {@code lightarmor_setamount_add} used to be a dead perk for pure light-armor builds (it only ever
 * amplified the *heavy* set-knockback bonus via {@code Math.max(heavyAmt, lightAmt)}, so a light-only
 * wearer with 0 heavy pieces got nothing). Mirrors the existing heavy set-knockback wiring: light armor's
 * own set bonus is dodge chance ({@code lightarmor_setdodgechance_add}), gated on >= 2 matching light
 * pieces and amplified by the light-only {@code lightarmor_setamount_add}.
 */
class NativeAttributeBridgeTest {

    private ServerMock server;
    private PerkBuffResolver perkBuffs;
    private final java.util.Map<String, Double> general = new java.util.HashMap<>();
    private NativeAttributeBridge bridge;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        perkBuffs = mock(PerkBuffResolver.class);
        when(perkBuffs.buffsFor(any())).thenAnswer(invocation ->
                new PerkBuffs(Map.of(), Map.of(), Map.of(), Map.copyOf(general), Map.of()));
        bridge = new NativeAttributeBridge(perkBuffs);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void stub(String key, double value) {
        general.put(key, value);
    }

    private void wearLight(int pieces) {
        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < pieces && i < 4; i++) {
            armor[i] = new ItemStack(Material.LEATHER_BOOTS);
        }
        player.getInventory().setArmorContents(armor);
    }

    private void wearHeavy(int pieces) {
        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < pieces && i < 4; i++) {
            armor[i] = new ItemStack(Material.IRON_BOOTS);
        }
        player.getInventory().setArmorContents(armor);
    }

    /** 軽装と重装を混ぜて装備する(ハイブリッド二重取りの検証用)。合計は防具枠の4まで。 */
    private void wearMixed(int lightPieces, int heavyPieces) {
        ItemStack[] armor = new ItemStack[4];
        int slot = 0;
        for (int i = 0; i < lightPieces && slot < 4; i++, slot++) {
            armor[slot] = new ItemStack(Material.LEATHER_BOOTS);
        }
        for (int i = 0; i < heavyPieces && slot < 4; i++, slot++) {
            armor[slot] = new ItemStack(Material.IRON_BOOTS);
        }
        player.getInventory().setArmorContents(armor);
    }

    /** 任意のマテリアルを順に装備する(素材ごとの軽装/重装分類の検証用)。 */
    private void wearArmor(Material... materials) {
        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < materials.length && i < 4; i++) {
            armor[i] = new ItemStack(materials[i]);
        }
        player.getInventory().setArmorContents(armor);
    }

    // --- pure setBonusValue() ---

    @Test
    void setBonusValueRequiresAtLeastThreePieces() {
        assertEquals(0.0, NativeAttributeBridge.setBonusValue(0, 0.1, 0.0));
        assertEquals(0.0, NativeAttributeBridge.setBonusValue(1, 0.1, 0.0));
        // 2026-07-26: 閾値を 2 → 3 へ引き上げ(下の hybrid テスト参照)。
        assertEquals(0.0, NativeAttributeBridge.setBonusValue(2, 0.1, 0.0));
        assertEquals(0.1, NativeAttributeBridge.setBonusValue(3, 0.1, 0.0));
        assertEquals(0.1, NativeAttributeBridge.setBonusValue(4, 0.1, 0.0));
    }

    /**
     * 防具枠は4つしかないので、閾値が3なら 3+3&gt;4 となり軽装セットと重装セットは**併用不能**。
     * 閾値2の頃は「軽装2＋重装2」で両方のセット効果が同時に乗るハイブリッド二重取りができた。
     */
    @Test
    void hybridTwoAndTwoCannotSatisfyBothSets() {
        int light = 2;
        int heavy = 2;
        assertEquals(0.0, NativeAttributeBridge.setBonusValue(light, 0.1, 0.0),
                "軽装2部位でセットが成立してはいけない(重装2部位との二重取りが可能になる)");
        assertEquals(0.0, NativeAttributeBridge.setBonusValue(heavy, 0.1, 0.0),
                "重装2部位でセットが成立してはいけない(軽装2部位との二重取りが可能になる)");
        // 3部位に寄せれば片方だけが成立し、残り枠は最大1部位なのでもう片方は成立し得ない。
        assertEquals(0.1, NativeAttributeBridge.setBonusValue(3, 0.1, 0.0));
        assertEquals(0.0, NativeAttributeBridge.setBonusValue(4 - 3, 0.1, 0.0));
    }

    @Test
    void setBonusValueZeroBaseIsZeroRegardlessOfPieces() {
        assertEquals(0.0, NativeAttributeBridge.setBonusValue(4, 0.0, 5.0));
    }

    @Test
    void setBonusValueAmplifierScalesUpAndFloorsAtZero() {
        assertEquals(0.2, NativeAttributeBridge.setBonusValue(3, 0.1, 1.0), 1e-9);
        // A negative amplifier never reduces below the unamplified base (floored at 0).
        assertEquals(0.1, NativeAttributeBridge.setBonusValue(3, 0.1, -0.5), 1e-9);
    }

    // --- armorAttributesFor() integration (light dodge-chance set bonus) ---

    @Test
    void lightSetDodgeChanceInactiveBelowThreePieces() {
        stub("light_armor_set_dodge_chance", 0.1);
        stub("light_armor_set_bonus_multiplier", 0.5);
        wearLight(2);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertFalse(attrs.containsKey("dodge_chance"),
                "2 light pieces must not trigger the set bonus (それを許すと重装2部位との二重取りができる)");
    }

    @Test
    void lightSetDodgeChanceActiveAtThreePieces() {
        stub("light_armor_set_dodge_chance", 0.1);
        stub("light_armor_set_bonus_multiplier", 0.0);
        wearLight(3);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertEquals(0.1, attrs.get("dodge_chance"), 1e-9);
    }

    @Test
    void lightSetAmountAmplifiesDodgeChanceSetBonus() {
        // node B (lightarmor_setamount_add: 0.1) + node C (lightarmor_setdodgechance_add: 0.1) design values.
        stub("light_armor_set_dodge_chance", 0.1);
        stub("light_armor_set_bonus_multiplier", 0.1);
        wearLight(3);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertEquals(0.1 * 1.1, attrs.get("dodge_chance"), 1e-9);
    }

    @Test
    void lightSetAmountNoLongerAmplifiesHeavyKnockbackBonus() {
        // Regression: lightarmor_setamount_add must never inflate the heavy set-knockback bonus, even
        // when the player happens to be wearing >= 2 heavy pieces (the old Math.max(heavyAmt, lightAmt)
        // cross-contamination bug).
        stub("heavy_armor_set_knockback_resistance", 0.1);
        stub("heavy_armor_set_bonus_multiplier", 0.0);
        stub("light_armor_set_bonus_multiplier", 5.0);
        wearHeavy(3);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertEquals(0.1, attrs.get("knockback_resistance"), 1e-9);
    }

    @Test
    void heavySetAmountStillAmplifiesItsOwnKnockbackBonus() {
        stub("heavy_armor_set_knockback_resistance", 0.1);
        stub("heavy_armor_set_bonus_multiplier", 0.2);
        wearHeavy(3);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertEquals(0.1 * 1.2, attrs.get("knockback_resistance"), 1e-9);
    }

    @Test
    void pureLightBuildWithZeroHeavyPiecesStillGetsItsOwnSetBonus() {
        // The original bug: a light-only wearer (0 heavy pieces) got nothing because the set bonus block
        // was gated exclusively on `heavy >= 2`.
        stub("light_armor_set_dodge_chance", 0.1);
        stub("light_armor_set_bonus_multiplier", 0.0);
        wearLight(4);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertEquals(0.1, attrs.get("dodge_chance"), 1e-9);
        assertFalse(attrs.containsKey("knockback_resistance"));
    }

    /**
     * 2026-07-26: 軽装2＋重装2 のハイブリッドで**両方のセット効果が乗る**二重取りができていた。
     * 閾値を3にしたので、防具枠4では片方しか成立し得ない。
     */
    @Test
    void hybridTwoLightTwoHeavyGrantsNeitherSetBonus() {
        stub("light_armor_set_dodge_chance", 0.1);
        stub("heavy_armor_set_knockback_resistance", 0.1);
        wearMixed(2, 2);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertFalse(attrs.containsKey("dodge_chance"), "軽装2部位でセットが成立してはいけない");
        assertFalse(attrs.containsKey("knockback_resistance"), "重装2部位でセットが成立してはいけない");
    }

    /**
     * 2026-07-26: 金装備の分類が {@code UseSkillDefaults}(軽装) と このクラス(重装) で食い違っており、
     * 「レベルゲートは軽装なのにセット効果は重装」という状態になっていた。判定は
     * {@code UseSkillDefaults.isLightArmor} へ一本化済み。
     */
    @Test
    void goldenArmorCountsAsLightMatchingUseSkillDefaults() {
        stub("light_armor_set_dodge_chance", 0.1);
        stub("heavy_armor_set_knockback_resistance", 0.1);
        wearArmor(Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS);

        Map<String, Double> attrs = bridge.armorAttributesFor(player);

        assertEquals(0.1, attrs.get("dodge_chance"), 1e-9, "金装備3部位は軽装セットとして成立するべき");
        assertFalse(attrs.containsKey("knockback_resistance"), "金装備が重装セットを成立させてはいけない");
    }
}
