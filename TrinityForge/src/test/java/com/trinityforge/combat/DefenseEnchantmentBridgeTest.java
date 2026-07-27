package com.trinityforge.combat;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 課題1 (2026-07-25): 防護エンチャント再導出の formula 検証。バニラ軽減率式(1ポイント=4%、上限20ポイント
 * =80%、防護=1倍/特化系(飛び道具耐性等)=2倍)を {@link DefenseEnchantmentBridge} が忠実に再現していること、
 * および TFを通らない経路(爆発耐性/火炎耐性は非対象)を確認する。
 */
class DefenseEnchantmentBridgeTest {

    private static final double EPS = 1e-9;

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static void setArmorPiece(Player player, int slot, Material material, Enchantment enchant, int level) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(enchant, level, true);
        item.setItemMeta(meta);
        ItemStack[] armor = player.getInventory().getArmorContents();
        armor[slot] = item;
        player.getInventory().setArmorContents(armor);
    }

    /**
     * 課題1のバグ報告そのもの: 防護IVフルセット(4部位 × Lv4)は EPF = 16(上限20未満)、軽減率 = 16×4% = 64%。
     */
    @Test
    void protectionIVFullSetYields64PercentReduction() {
        Player player = server.addPlayer();
        setArmorPiece(player, 0, Material.DIAMOND_BOOTS, Enchantment.PROTECTION, 4);
        setArmorPiece(player, 1, Material.DIAMOND_LEGGINGS, Enchantment.PROTECTION, 4);
        setArmorPiece(player, 2, Material.DIAMOND_CHESTPLATE, Enchantment.PROTECTION, 4);
        setArmorPiece(player, 3, Material.DIAMOND_HELMET, Enchantment.PROTECTION, 4);

        DefenseStats defense = DefenseEnchantmentBridge.toDefense(player, false);

        assertEquals(0.64, defense.damageReduction(), EPS);
    }

    @Test
    void epfCapsAt20PointsFor80PercentReduction() {
        // 防護は本来レベル4止まりだが、EPFキャップの境界(20ポイント=80%)自体を検証するため
        // 意図的にバニラ上限を超えるレベルをエンチャントする(エンチャ台では起きないが、この式は
        // レベル値そのものを純粋に読むだけなので上限超過の入力でも安全にキャップされることを確認する)。
        Player player = server.addPlayer();
        setArmorPiece(player, 0, Material.DIAMOND_BOOTS, Enchantment.PROTECTION, 10);
        setArmorPiece(player, 1, Material.DIAMOND_LEGGINGS, Enchantment.PROTECTION, 10);
        setArmorPiece(player, 2, Material.DIAMOND_CHESTPLATE, Enchantment.PROTECTION, 10);
        setArmorPiece(player, 3, Material.DIAMOND_HELMET, Enchantment.PROTECTION, 10);

        DefenseStats defense = DefenseEnchantmentBridge.toDefense(player, false);

        assertEquals(0.80, defense.damageReduction(), EPS);
    }

    @Test
    void projectileProtectionOnlyAppliesOnProjectileHit() {
        Player player = server.addPlayer();
        setArmorPiece(player, 0, Material.DIAMOND_BOOTS, Enchantment.PROJECTILE_PROTECTION, 4);

        DefenseStats meleeHit = DefenseEnchantmentBridge.toDefense(player, false);
        DefenseStats projectileHit = DefenseEnchantmentBridge.toDefense(player, true);

        assertEquals(0.0, meleeHit.damageReduction(), EPS,
                "Projectile Protection must not mitigate a melee hit");
        // 1部位のみ Lv4: EPF = 4 × 2(特化系倍率) = 8 → 8 × 4% = 32%.
        assertEquals(0.32, projectileHit.damageReduction(), EPS);
    }

    @Test
    void blastAndFireProtectionAreIntentionallyNotBridged() {
        // 火炎耐性/爆発耐性はTFのCombatListenerを通らない被弾経路(ENTITY_EXPLOSION/FIRE/FIRE_TICK/LAVA)
        // にしか効かないため、意図的に読まない(0寄与のまま)。
        Player player = server.addPlayer();
        setArmorPiece(player, 0, Material.DIAMOND_BOOTS, Enchantment.BLAST_PROTECTION, 4);
        setArmorPiece(player, 1, Material.DIAMOND_LEGGINGS, Enchantment.FIRE_PROTECTION, 4);

        DefenseStats melee = DefenseEnchantmentBridge.toDefense(player, false);
        DefenseStats projectile = DefenseEnchantmentBridge.toDefense(player, true);

        assertEquals(0.0, melee.damageReduction(), EPS);
        assertEquals(0.0, projectile.damageReduction(), EPS);
    }

    @Test
    void noArmorOrNullYieldsNone() {
        Player player = server.addPlayer();
        assertEquals(0.0, DefenseEnchantmentBridge.toDefense(player, false).damageReduction(), EPS);
        assertEquals(DefenseStats.NONE, DefenseEnchantmentBridge.toDefense(null, false));
    }

    /**
     * defense.enchant-protection-scale (2026-07-25): scale 1.0 must reproduce exact vanilla behaviour
     * (same 64% as {@link #protectionIVFullSetYields64PercentReduction}), scale 0.5 must halve it to
     * 32%, and the EPF cap (20 points -> 80%) must still be applied BEFORE the scale multiplies in.
     */
    @Test
    void enchantProtectionScaleOneReproducesVanilla64Percent() {
        Player player = server.addPlayer();
        setArmorPiece(player, 0, Material.DIAMOND_BOOTS, Enchantment.PROTECTION, 4);
        setArmorPiece(player, 1, Material.DIAMOND_LEGGINGS, Enchantment.PROTECTION, 4);
        setArmorPiece(player, 2, Material.DIAMOND_CHESTPLATE, Enchantment.PROTECTION, 4);
        setArmorPiece(player, 3, Material.DIAMOND_HELMET, Enchantment.PROTECTION, 4);

        DefenseStats defense = DefenseEnchantmentBridge.toDefense(player, false, 1.0);

        assertEquals(0.64, defense.damageReduction(), EPS);
    }

    @Test
    void enchantProtectionScaleHalfYields32Percent() {
        Player player = server.addPlayer();
        setArmorPiece(player, 0, Material.DIAMOND_BOOTS, Enchantment.PROTECTION, 4);
        setArmorPiece(player, 1, Material.DIAMOND_LEGGINGS, Enchantment.PROTECTION, 4);
        setArmorPiece(player, 2, Material.DIAMOND_CHESTPLATE, Enchantment.PROTECTION, 4);
        setArmorPiece(player, 3, Material.DIAMOND_HELMET, Enchantment.PROTECTION, 4);

        DefenseStats defense = DefenseEnchantmentBridge.toDefense(player, false, 0.5);

        assertEquals(0.32, defense.damageReduction(), EPS);
    }

    @Test
    void enchantProtectionScaleAppliesAfterEpfCap() {
        // Same over-cap setup as epfCapsAt20PointsFor80PercentReduction(): EPF caps at 20 -> 80%
        // BEFORE scaling, so scale 0.5 must yield 40%, not some other value derived from the
        // uncapped EPF (which would be far larger than 20 here).
        Player player = server.addPlayer();
        setArmorPiece(player, 0, Material.DIAMOND_BOOTS, Enchantment.PROTECTION, 10);
        setArmorPiece(player, 1, Material.DIAMOND_LEGGINGS, Enchantment.PROTECTION, 10);
        setArmorPiece(player, 2, Material.DIAMOND_CHESTPLATE, Enchantment.PROTECTION, 10);
        setArmorPiece(player, 3, Material.DIAMOND_HELMET, Enchantment.PROTECTION, 10);

        DefenseStats defense = DefenseEnchantmentBridge.toDefense(player, false, 0.5);

        assertEquals(0.40, defense.damageReduction(), EPS);
    }

    @Test
    void negativeEnchantProtectionScaleClampsToZero() {
        Player player = server.addPlayer();
        setArmorPiece(player, 0, Material.DIAMOND_BOOTS, Enchantment.PROTECTION, 4);
        setArmorPiece(player, 1, Material.DIAMOND_LEGGINGS, Enchantment.PROTECTION, 4);
        setArmorPiece(player, 2, Material.DIAMOND_CHESTPLATE, Enchantment.PROTECTION, 4);
        setArmorPiece(player, 3, Material.DIAMOND_HELMET, Enchantment.PROTECTION, 4);

        DefenseStats defense = DefenseEnchantmentBridge.toDefense(player, false, -1.0);

        assertEquals(0.0, defense.damageReduction(), EPS);
    }

    @Test
    void onlyDamageReductionFieldIsPopulated() {
        Player player = server.addPlayer();
        setArmorPiece(player, 2, Material.DIAMOND_CHESTPLATE, Enchantment.PROTECTION, 2);

        DefenseStats defense = DefenseEnchantmentBridge.toDefense(player, false);

        assertEquals(0.0, defense.defenseRate(), EPS);
        assertEquals(0.0, defense.resistance(), EPS);
        assertEquals(0.0, defense.flatDefense(), EPS);
        assertEquals(0.0, defense.armorStrength(), EPS);
    }
}
