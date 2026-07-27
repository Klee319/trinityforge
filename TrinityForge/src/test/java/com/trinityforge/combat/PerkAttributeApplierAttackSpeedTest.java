package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.skilltree.runtime.PerkAttributeApplier;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-07-25 attack-speed(絶対値・メインハンド専用) / attack-speed-bonus(割合・全ソース横断) 分離仕様の
 * {@link PerkAttributeApplier} 統合テスト。{@code CombatWiringSupport} と同じ「reflective fake Plugin で
 * ConfigManagerをロードし、MockBukkit実Pluginでリスナー本体を動かす」パターンを踏襲する。
 *
 * <p>{@code Material#getDefaultAttributeModifiers} は MockBukkit 未実装のため、このテストは
 * TF側が管理しない(明示的な attribute_modifiers を一切書いていない)アイテムだけを使い、
 * {@code itemOwnAttackSpeedContribution} の early-return(=0)経路だけを通す(既知の制約、
 * {@code AttributeApplierMissingDefaultsTest} と同じ回避パターン)。
 */
class PerkAttributeApplierAttackSpeedTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PerkAttributeApplier newApplier(File dir, String itemStatsYaml, String baseStatsYaml)
            throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), itemStatsYaml);
        if (baseStatsYaml != null) {
            File baseStats = new File(dir, com.trinityforge.config.domains.BaseStatsConfig.PATH);
            Files.createDirectories(baseStats.getParentFile().toPath());
            Files.writeString(baseStats.toPath(), baseStatsYaml);
        }
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        RoleBuffResolver role = new RoleBuffResolver(cm.roleBuffs());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, role, null, null, cm.baseStats());
        Plugin plugin = MockBukkit.createMockPlugin();
        return new PerkAttributeApplier(plugin, perks, null, null, cm.baseStats(), aggregator, damage,
                cm.itemStats());
    }

    private Player playerWith(Material mainhand, Material chest) {
        PlayerMock player = server.addPlayer();
        player.registerAttribute(Attribute.ATTACK_SPEED);
        PlayerInventory inv = player.getInventory();
        if (mainhand != null) {
            inv.setItemInMainHand(new ItemStack(mainhand));
        }
        if (chest != null) {
            inv.setChestplate(new ItemStack(chest));
        }
        return player;
    }

    // --- シナリオ1: メインハンド定義済み -> 実効値=著者値そのもの ---
    @Test
    void mainhandDefinedAttackSpeedBecomesEffectiveValue(@TempDir File dir) throws IOException {
        PerkAttributeApplier applier = newApplier(dir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-speed: 6.0 }
                """, null);
        Player player = playerWith(Material.DIAMOND_SWORD, null);

        applier.apply(player);

        assertEquals(6.0, player.getAttribute(Attribute.ATTACK_SPEED).getValue(), 1e-9);
    }

    // --- シナリオ2: 未定義 -> TFは干渉しない(ここでは明示アイテムを一切持たせず素手で確認) ---
    @Test
    void undefinedMainhandBareHandStaysAtVanillaBase(@TempDir File dir) throws IOException {
        PerkAttributeApplier applier = newApplier(dir, "items: {}\n", null);
        Player player = playerWith(null, null);

        applier.apply(player);

        assertEquals(4.0, player.getAttribute(Attribute.ATTACK_SPEED).getValue(), 1e-9);
    }

    // --- シナリオ3: attack-speed-bonusはメインハンド未定義でも常に適用される(素手4.0+10%=4.4) ---
    @Test
    void bonusAppliesToBareHandRegardlessOfMainhandDefinition(@TempDir File dir) throws IOException {
        PerkAttributeApplier applier = newApplier(dir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-speed-bonus: 0.10 }
                """, null);
        Player player = playerWith(Material.DIAMOND_SWORD, null);

        applier.apply(player);

        assertEquals(4.4, player.getAttribute(Attribute.ATTACK_SPEED).getValue(), 1e-9);
    }

    // --- シナリオ5: 全ソース(メインハンド/防具/base-stats)からのattack-speed-bonusを厳密に1回ずつ合算 ---
    @Test
    void attackSpeedBonusAggregatesEachSourceExactlyOnce(@TempDir File dir) throws IOException {
        PerkAttributeApplier applier = newApplier(dir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-speed-bonus: 0.02 }
                  DIAMOND_CHESTPLATE:
                    fixed: { attack-speed-bonus: 0.03 }
                """, """
                base-stats:
                  attack-speed-bonus: 0.05
                """);
        Player player = playerWith(Material.DIAMOND_SWORD, Material.DIAMOND_CHESTPLATE);

        applier.apply(player);

        // 0.02(mainhand) + 0.03(armor) + 0.05(base-stats) = 0.10 -> 4.0 * 1.10 = 4.4
        assertEquals(4.4, player.getAttribute(Attribute.ATTACK_SPEED).getValue(), 1e-9);
    }

    // --- attack-speed(絶対値) と attack-speed-bonus(割合) の合成: 素手ではなく絶対値適用後に掛かる ---
    @Test
    void bonusMultipliesOnTopOfAbsoluteAttackSpeedNotBaseOnly(@TempDir File dir) throws IOException {
        PerkAttributeApplier applier = newApplier(dir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-speed: 6.0, attack-speed-bonus: 0.10 }
                """, null);
        Player player = playerWith(Material.DIAMOND_SWORD, null);

        applier.apply(player);

        // ADD_SCALARなら base(4.0)*1.10=4.4 の系統になり6.4となるが、MULTIPLY_SCALAR_1は
        // running total(6.0)に掛かるので 6.0*1.10=6.6 が正しい。
        assertEquals(6.6, player.getAttribute(Attribute.ATTACK_SPEED).getValue(), 1e-9);
    }

    // --- シナリオ7 (2-a): 著者値0以下は警告のうえ4.0へフォールバック ---
    @Test
    void authoringErrorNonPositiveMainhandFallsBackToFour(@TempDir File dir) throws IOException {
        PerkAttributeApplier applier = newApplier(dir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-speed: -3.0 }
                """, null);
        Player player = playerWith(Material.DIAMOND_SWORD, null);

        applier.apply(player);

        assertEquals(4.0, player.getAttribute(Attribute.ATTACK_SPEED).getValue(), 1e-9);
    }

    // --- シナリオ7 (2-b): デバフ過多でも最終実効速度はmin-effective(既定0.1)を割らず、4.0へは戻らない ---
    // -0.99 を使う理由: PercentStatNormalize の RATE_KEYS 補正(|v|>1かつ整数なら/100)を避けるため
    // (-50.0 と書くと「-50%のつもり」として -0.5 へ補正されてしまい意図した極端デバフにならない)。
    @Test
    void extremeNegativeBonusClampsToLowerBoundNotFour(@TempDir File dir) throws IOException {
        PerkAttributeApplier applier = newApplier(dir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-speed: 4.0, attack-speed-bonus: -0.99 }
                """, null);
        Player player = playerWith(Material.DIAMOND_SWORD, null);

        applier.apply(player);

        double value = player.getAttribute(Attribute.ATTACK_SPEED).getValue();
        assertEquals(0.1, value, 1e-9);
    }

    // --- apply()の冪等性(同一tick内で複数回呼んでも結果は変わらず、例外も出ない) ---
    @Test
    void applyTwiceIsIdempotent(@TempDir File dir) throws IOException {
        PerkAttributeApplier applier = newApplier(dir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-speed: 5.0, attack-speed-bonus: 0.10 }
                """, null);
        Player player = playerWith(Material.DIAMOND_SWORD, null);

        applier.apply(player);
        applier.apply(player);

        assertEquals(5.5, player.getAttribute(Attribute.ATTACK_SPEED).getValue(), 1e-9);
    }
}
