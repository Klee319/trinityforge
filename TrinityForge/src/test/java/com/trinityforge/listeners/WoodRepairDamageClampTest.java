package com.trinityforge.listeners;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 木材修繕が「損傷が最大耐久を超えている装備」でも落ちないことを固定する
 * （2026-08-21 実サーバログ: 修繕クリックのたびに
 * {@code Could not pass event InventoryClickEvent to TrinityForge /
 * java.lang.IllegalArgumentException: Damage cannot exceed max damage}）。
 *
 * <p><b>なぜ引き算しかしていないのに上限を超えるのか</b>: 1.21 では最大耐久が
 * {@code max_damage} コンポーネントで決まり、TF は {@code item-stats.yml} の {@code durability} から
 * 個体ごとに書き込む。その値を<b>下げる方向</b>へ調整すると、既に配られている個体は
 * 「damage &gt; max_damage」のまま残る。Bukkit の {@code setDamage} はその値を弾くので、
 * 下限（0）しか見ていない実装では<b>回復量がいくらであっても必ず落ちる</b>。
 *
 * <p>落ちる位置が {@code event.setCancelled(true)} より手前なので、プレイヤーから見た症状は
 * 「修繕したのに何も起きず、素材だけ普通に持ち替わる」。
 */
class WoodRepairDamageClampTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** {@code maxDamage} を持ち、{@code damage} がそれを超えている装備。 */
    private static ItemStack overDamaged(int maxDamage, int damage) {
        ItemStack stack = new ItemStack(Material.DIAMOND_CHESTPLATE);
        Damageable meta = (Damageable) stack.getItemMeta();
        meta.setMaxDamage(maxDamage);
        meta.setDamage(damage);
        stack.setItemMeta(meta);
        return stack;
    }

    @Test
    @DisplayName("損傷が最大耐久を超えていても例外を出さず、最大耐久まで丸める")
    void repairingAnOverDamagedItemDoesNotThrow() {
        // damage(500) > max_damage(300)。回復量100を引いても 400 で、まだ上限超過。
        ItemStack stack = overDamaged(300, 500);

        WoodRepairListener.ItemMetaRepair.applyRepair(stack, 100);

        Damageable meta = (Damageable) stack.getItemMeta();
        assertEquals(300, meta.getDamage(),
                "上限で丸めること（丸めないと Bukkit が IllegalArgumentException を投げる）");
    }

    @Test
    @DisplayName("正常な装備は素直に回復する（クランプが本来の回復を邪魔しない）")
    void aNormalItemIsRepairedAsBefore() {
        ItemStack stack = overDamaged(300, 250);

        WoodRepairListener.ItemMetaRepair.applyRepair(stack, 100);

        assertEquals(150, ((Damageable) stack.getItemMeta()).getDamage());
    }

    @Test
    @DisplayName("回復量が損傷を上回っても 0 未満にはしない")
    void repairNeverGoesBelowZero() {
        ItemStack stack = overDamaged(300, 40);

        WoodRepairListener.ItemMetaRepair.applyRepair(stack, 1000);

        assertEquals(0, ((Damageable) stack.getItemMeta()).getDamage());
    }

    @Test
    @DisplayName("max_damage を持たない装備は素材の既定耐久で丸める")
    void withoutACustomMaxDamageTheVanillaDurabilityIsUsed() {
        ItemStack stack = new ItemStack(Material.DIAMOND_CHESTPLATE);
        int vanillaMax = Material.DIAMOND_CHESTPLATE.getMaxDurability();
        assertTrue(vanillaMax > 0, "前提: 素材が耐久を持つこと");

        Damageable meta = (Damageable) stack.getItemMeta();
        assertEquals(vanillaMax,
                WoodRepairListener.ItemMetaRepair.clampDamage(stack, meta, vanillaMax + 999));
        assertEquals(0, WoodRepairListener.ItemMetaRepair.clampDamage(stack, meta, -5));
    }
}
