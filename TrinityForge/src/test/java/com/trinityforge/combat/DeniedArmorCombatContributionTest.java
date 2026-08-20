package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.progression.UseRequirementResolver;
import com.trinityforge.progression.UseRequirementService;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for the non-cancellable armor-change window: rejected armor must not contribute its
 * vanilla armor or Protection enchantment before the listener removes it on the next tick.
 */
class DeniedArmorCombatContributionTest {

    private static final double BASE_DAMAGE = 100.0;

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private SymmetricCombatService service(File dir, UseRequirementService gate) throws IOException {
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                  min-component-damage: 0.0
                level-scaling:
                  per-level: 0.0
                vanilla-armor:
                  defense-rate-per-point: 0.04
                  defense-rate-max: 0.9
                  armor-strength-per-point: 0.0
                defense:
                  max-mitigation-rate: 0.95
                  enchant-protection-scale: 1.0
                """);
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, List::of);
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()), null, null,
                null, null, gate);
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        return new SymmetricCombatService(
                damage, cm.combatLevel(), cm.mobTypes(), SkillLevelSource.EMPTY, defense);
    }

    private static ItemStack protectedChestplate() {
        ItemStack chestplate = new ItemStack(Material.DIAMOND_CHESTPLATE);
        ItemMeta meta = chestplate.getItemMeta();
        meta.addEnchant(Enchantment.PROTECTION, 4, true);
        chestplate.setItemMeta(meta);
        return chestplate;
    }

    @Test
    void deniedArmorProvidesNoVanillaOrProtectionMitigationBeforeRemoval(@TempDir File dir)
            throws IOException {
        UseRequirementService deniedGate = mock(UseRequirementService.class);
        when(deniedGate.denialFor(any(Player.class), any(ItemStack.class)))
                .thenReturn(Optional.of(new UseRequirementResolver.Resolved("HEAVY_ARMOR", 50)));
        Player victim = server.addPlayer();
        victim.getInventory().setChestplate(protectedChestplate());

        double deniedDamage = service(dir, deniedGate).physicalFinalDamage(
                UUID.randomUUID(), victim, BASE_DAMAGE, AttackStats.plain(0));
        double allowedDamage = service(dir, null).physicalFinalDamage(
                UUID.randomUUID(), victim, BASE_DAMAGE, AttackStats.plain(0));

        assertEquals(BASE_DAMAGE, deniedDamage, 1e-9,
                "要件未達防具は剥離前でもvanilla防御/Protectionを一切付与してはならない");
        assertTrue(allowedDamage < deniedDamage,
                "sanity: 同じ防具は要件を満たす通常経路ではダメージを軽減する");
    }
}
