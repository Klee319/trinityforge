package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.StatCapsConfig;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-07-26 stat-caps カバレッジ拡大: DEFENSE チャネル(phys-resistance / damage-reduction /
 * armor-strength / dodge-chance 等)が {@link PlayerDefenseResolver#resolve} の
 * {@code DefenseStats#combine} 直後(唯一の出口)で {@code combat/stat-caps.yml} の上限に実際に服すること、
 * かつ上限未設定(statCaps=null、既定)なら {@link DefenderProfile} が1ビットも変わらないことの両方を固定する。
 */
class PlayerDefenseResolverStatCapTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void writeItemStats(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        // 4部位合計で damage-reduction=0.80, armor-strength=0.80 を仕込む(上限テスト用)。両キーとも
        // PercentStatNormalize の RATE_KEYS 対象(|v|>1 は "75" のような入力ミスとして÷100矯正される)
        // なので、意図した値のまま届くよう最初から[0,1]の小数(0.20)で書く。
        Files.writeString(itemStats.toPath(), """
                items:
                  DIAMOND_HELMET:
                    fixed: { damage-reduction: 0.20, armor-strength: 0.20 }
                  DIAMOND_CHESTPLATE:
                    fixed: { damage-reduction: 0.20, armor-strength: 0.20 }
                  DIAMOND_LEGGINGS:
                    fixed: { damage-reduction: 0.20, armor-strength: 0.20 }
                  DIAMOND_BOOTS:
                    fixed: { damage-reduction: 0.20, armor-strength: 0.20 }
                """);
    }

    private PlayerDefenseResolver resolver(File dir, String statCapsYaml) throws IOException {
        writeItemStats(dir);
        if (statCapsYaml != null) {
            File statCaps = new File(dir, StatCapsConfig.PATH);
            Files.createDirectories(statCaps.getParentFile().toPath());
            Files.writeString(statCaps.toPath(), statCapsYaml);
        }
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                vanilla-armor:
                  defense-rate-per-point: 0.0
                  defense-rate-max: 0.0
                  armor-strength-per-point: 0.0
                """);
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> List.of());
        PlayerStatAggregator aggregator = statCapsYaml != null
                ? new PlayerStatAggregator(cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()),
                        null, null, null, cm.statCaps())
                : new PlayerStatAggregator(cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        return new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
    }

    private Player fullyArmored() {
        Player player = server.addPlayer();
        player.getInventory().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        player.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        player.getInventory().setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
        player.getInventory().setBoots(new ItemStack(Material.DIAMOND_BOOTS));
        return player;
    }

    @Test
    void damageReductionAndArmorStrengthAreClampedByStatCaps(@TempDir File dir) throws IOException {
        PlayerDefenseResolver resolver = resolver(dir, """
                stat-caps:
                  damage-reduction: 0.5
                  armor-strength: 0.3
                """);
        Player player = fullyArmored();

        DefenderProfile profile = resolver.resolve(player, DamageType.PHYSICAL);

        assertEquals(0.5, profile.stats().damageReduction(), 1e-9,
                "4-piece damage-reduction sum (0.80) must be clamped to the configured cap (0.5)");
        assertEquals(0.3, profile.stats().armorStrength(), 1e-9,
                "4-piece armor-strength sum (0.80) must be clamped to the configured cap (0.3)");
    }

    @Test
    void valuesBelowCapAreUnaffected(@TempDir File dir) throws IOException {
        PlayerDefenseResolver resolver = resolver(dir, """
                stat-caps:
                  damage-reduction: 5.0
                  armor-strength: 999.0
                """);
        Player player = fullyArmored();

        DefenderProfile profile = resolver.resolve(player, DamageType.PHYSICAL);

        assertEquals(0.8, profile.stats().damageReduction(), 1e-9);
        assertEquals(0.8, profile.stats().armorStrength(), 1e-9);
    }

    @Test
    void nullStatCapsLeavesDefenderProfileCompletelyUnchanged(@TempDir File dir) throws IOException {
        PlayerDefenseResolver resolver = resolver(dir, null);
        Player player = fullyArmored();

        DefenderProfile profile = resolver.resolve(player, DamageType.PHYSICAL);

        assertEquals(0.8, profile.stats().damageReduction(), 1e-9,
                "with no stat-caps.yml wired in at all (statCaps=null), the 4-piece sum (0.80) must reach "
                        + "DefenderProfile completely unclamped, matching pre-existing behaviour");
        assertEquals(0.8, profile.stats().armorStrength(), 1e-9);
    }
}
