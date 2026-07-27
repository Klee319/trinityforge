package com.trinityforge.combat;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnchantmentStatBridgeTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void adjustedAttackPowerAppliesPercentBonus() {
        EnchantmentStatBridge.Bonuses bonuses = new EnchantmentStatBridge.Bonuses(0.10, 0.0);
        assertEquals(11.0, EnchantmentStatBridge.adjustedAttackPower(10.0, bonuses), 1e-9);
    }

    @Test
    void adjustedDurabilityAppliesUnbreakingPercent() {
        EnchantmentStatBridge.Bonuses bonuses = new EnchantmentStatBridge.Bonuses(0.0, 0.30);
        assertEquals(130, EnchantmentStatBridge.adjustedDurability(100, bonuses));
    }

    @Test
    void noneBonusesAreNeutral() {
        assertEquals(10.0, EnchantmentStatBridge.adjustedAttackPower(10.0, EnchantmentStatBridge.Bonuses.NONE), 1e-9);
        assertEquals(100, EnchantmentStatBridge.adjustedDurability(100, EnchantmentStatBridge.Bonuses.NONE));
    }

    @Test
    void luckOfTheSeaAddsFishingLuckBonusPerLevel() {
        // 2026-07-23 stat-gate-overhaul §2.3: 宝釣り +20%/Lv fishing_luck (Sharpness等と同パターン)。
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = rod.getItemMeta();
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 3, true);
        rod.setItemMeta(meta);

        EnchantmentStatBridge.Bonuses bonuses = EnchantmentStatBridge.bonuses(rod, null);

        assertEquals(0.60, bonuses.fishingLuckBonus(), 1e-9);
    }

    @Test
    void noEnchantsYieldsZeroFishingLuckBonus() {
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        EnchantmentStatBridge.Bonuses bonuses = EnchantmentStatBridge.bonuses(rod, null);
        assertEquals(0.0, bonuses.fishingLuckBonus(), 1e-9);
    }

    // --- 2026-07-25バグ修正: Sweeping Edge がTF独自ダメージ式へ反映されていなかった ---

    @Test
    void sweepingEdgeLevelReadsFromWeaponMeta() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.addEnchant(Enchantment.SWEEPING_EDGE, 3, true);
        sword.setItemMeta(meta);

        assertEquals(3, EnchantmentStatBridge.sweepingEdgeLevel(sword));
    }

    @Test
    void sweepingEdgeLevelIsZeroWithoutEnchantOrItem() {
        assertEquals(0, EnchantmentStatBridge.sweepingEdgeLevel(new ItemStack(Material.DIAMOND_SWORD)));
        assertEquals(0, EnchantmentStatBridge.sweepingEdgeLevel(null));
        assertEquals(0, EnchantmentStatBridge.sweepingEdgeLevel(new ItemStack(Material.AIR)));
    }

    @Test
    void sweepDamageWithoutSweepingEdgeIsFlatOne() {
        // バニラの既知挙動: Sweeping Edge無しの素手/装備でもsweep攻撃は常に1ダメージ。
        assertEquals(1.0, EnchantmentStatBridge.sweepDamage(10.0, 0), 1e-9);
        assertEquals(1.0, EnchantmentStatBridge.sweepDamage(6.0, 0), 1e-9);
    }

    @Test
    void sweepDamageMatchesVanillaWikiExample() {
        // wiki例: 鉄の剣(攻撃力6) + Sweeping Edge I -> 1 + 6*(1/2) = 4
        assertEquals(4.0, EnchantmentStatBridge.sweepDamage(6.0, 1), 1e-9);
        // wiki例: Sharpness V + Sweeping Edge III の剣(攻撃力10) -> 1 + 10*(3/4) = 8.5 -> round = 9? (Math.round(8.5)=9)
        assertEquals(Math.round(1.0 + 10.0 * 3.0 / 4.0), EnchantmentStatBridge.sweepDamage(10.0, 3));
    }

    @Test
    void sweepDamageNegativeLevelTreatedAsZero() {
        assertEquals(1.0, EnchantmentStatBridge.sweepDamage(10.0, -5), 1e-9);
    }

    @Test
    void sweepDamageNonFiniteAttackDamageTreatedAsZero() {
        assertEquals(1.0, EnchantmentStatBridge.sweepDamage(Double.NaN, 2), 1e-9);
    }

    // --- 2026-07-25バグ修正: 攻撃側エンチャント取りこぼし ---

    @Test
    void unbreakingNoLongerAddsDurabilityBonus() {
        // #6 ユーザー決定「バニラ優先」: TFの+10%/Lv底上げを撤去、バニラの耐久消費スキップ確率のみに一本化。
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        sword.setItemMeta(meta);

        EnchantmentStatBridge.Bonuses bonuses = EnchantmentStatBridge.bonuses(sword, null);

        assertEquals(0.0, bonuses.durabilityMultiplier(), 1e-9);
    }

    @Test
    void densityLevelIsCapturedButDoesNotAffectAttackPowerPercent() {
        // #1 重撃(Density)は落下距離依存のため率(%)化できず、生レベルを Bonuses#densityLevel() に
        // 持ち回るだけ(実際の加算計算は CombatListener 側で MaceSmashDamage を使う)。
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        meta.addEnchant(Enchantment.DENSITY, 4, true);
        mace.setItemMeta(meta);

        EnchantmentStatBridge.Bonuses bonuses = EnchantmentStatBridge.bonuses(mace, null);

        assertEquals(4, bonuses.densityLevel());
        assertEquals(0.0, bonuses.attackPowerMultiplier(), 1e-9);
    }

    @Test
    void noDensityEnchantYieldsZeroLevel() {
        assertEquals(0, EnchantmentStatBridge.bonuses(new ItemStack(Material.MACE), null).densityLevel());
        assertEquals(0, EnchantmentStatBridge.Bonuses.NONE.densityLevel());
    }

    @Test
    void smiteMatchesBoggedCamelHuskAndParched() {
        // #4 2026-07-25バグ修正: 従来の isUndead() 列挙/フォールバック名前一致("ZOMBIE"/"SKELETON"を
        // 含む名前)のどちらにも該当しなかった1.21系アンデッドモブ。
        for (EntityType type : new EntityType[] {EntityType.BOGGED, EntityType.CAMEL_HUSK, EntityType.PARCHED}) {
            LivingEntity victim = mock(LivingEntity.class);
            when(victim.getType()).thenReturn(type);

            ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
            ItemMeta meta = sword.getItemMeta();
            meta.addEnchant(Enchantment.SMITE, 2, true);
            sword.setItemMeta(meta);

            EnchantmentStatBridge.Bonuses bonuses = EnchantmentStatBridge.bonuses(sword, victim);

            assertEquals(2 * 0.075, bonuses.attackPowerMultiplier(), 1e-9,
                    "Smite should match " + type);
        }
    }

    @Test
    void impalingMatchesWetVictimRegardlessOfEntityType() {
        // #5 2026-07-25バグ修正: バニラ1.21のImpalingは isInWaterOrRain() で判定する。ここでは
        // 旧EntityType列挙では非水生扱いだった PIG が、雨/水中にいれば命中することを示す。
        LivingEntity victim = mock(LivingEntity.class);
        when(victim.getType()).thenReturn(EntityType.PIG);
        when(victim.isInWaterOrRain()).thenReturn(true);

        ItemStack trident = new ItemStack(Material.TRIDENT);
        ItemMeta meta = trident.getItemMeta();
        meta.addEnchant(Enchantment.IMPALING, 5, true);
        trident.setItemMeta(meta);

        EnchantmentStatBridge.Bonuses bonuses = EnchantmentStatBridge.bonuses(trident, victim);

        assertEquals(5 * 0.075, bonuses.attackPowerMultiplier(), 1e-9);
    }

    @Test
    void impalingDoesNotMatchDryVictimEvenIfOldEnumConsideredItAquatic() {
        // 旧EntityType列挙ではSQUIDを常に水生扱いしていたが、バニラは実際の濡れ/水中状態で判定するため、
        // (テスト上あり得ない状況だが)乾いていれば命中しないことを式レベルで示す。
        LivingEntity victim = mock(LivingEntity.class);
        when(victim.getType()).thenReturn(EntityType.SQUID);
        when(victim.isInWaterOrRain()).thenReturn(false);

        ItemStack trident = new ItemStack(Material.TRIDENT);
        ItemMeta meta = trident.getItemMeta();
        meta.addEnchant(Enchantment.IMPALING, 5, true);
        trident.setItemMeta(meta);

        EnchantmentStatBridge.Bonuses bonuses = EnchantmentStatBridge.bonuses(trident, victim);

        assertEquals(0.0, bonuses.attackPowerMultiplier(), 1e-9);
    }
}
