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
 * 課題2 (2026-07-25): 棘の鎧レベル -&gt; 反射率（割）ブリッジの formula 検証(10%×レベル、装備4部位合計)。
 */
class ReflectDamageBridgeTest {

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

    private static void setArmorPiece(Player player, int slot, Material material, int thornsLevel) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(Enchantment.THORNS, thornsLevel, true);
        item.setItemMeta(meta);
        ItemStack[] armor = player.getInventory().getArmorContents();
        armor[slot] = item;
        player.getInventory().setArmorContents(armor);
    }

    @Test
    void singlePieceThornsLevelContributes10PercentPerLevel() {
        Player player = server.addPlayer();
        setArmorPiece(player, 2, Material.DIAMOND_CHESTPLATE, Enchantment.THORNS.getMaxLevel());

        assertEquals(3, ReflectDamageBridge.wornThornsLevel(player));
        assertEquals(0.30, ReflectDamageBridge.thornsPercentContribution(player), EPS);
    }

    @Test
    void multiplePiecesSumAcrossArmor() {
        Player player = server.addPlayer();
        setArmorPiece(player, 0, Material.DIAMOND_BOOTS, 1);
        setArmorPiece(player, 1, Material.DIAMOND_LEGGINGS, 2);
        setArmorPiece(player, 2, Material.DIAMOND_CHESTPLATE, 3);
        setArmorPiece(player, 3, Material.DIAMOND_HELMET, 1);

        assertEquals(7, ReflectDamageBridge.wornThornsLevel(player));
        assertEquals(0.70, ReflectDamageBridge.thornsPercentContribution(player), EPS);
    }

    @Test
    void noThornsYieldsZero() {
        Player player = server.addPlayer();
        assertEquals(0, ReflectDamageBridge.wornThornsLevel(player));
        assertEquals(0.0, ReflectDamageBridge.thornsPercentContribution(player), EPS);
    }

    @Test
    void nullLivingEntityYieldsZero() {
        assertEquals(0, ReflectDamageBridge.wornThornsLevel(null));
        assertEquals(0.0, ReflectDamageBridge.thornsPercentContribution(null), EPS);
    }
}
