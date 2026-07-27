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
 * CMB-03 regression: {@link PerkAttributeApplier#apply} used to {@code clearOwnModifiers} (removing
 * every TF-owned attribute modifier, including any MAX_HEALTH bonus) and only afterwards re-add the
 * recomputed modifiers. Because Bukkit clamps current health down whenever MAX_HEALTH drops but never
 * raises it back up when MAX_HEALTH is restored, this clear-then-reapply cycle — fired on every
 * equipment change / durability tick via {@code EntityEquipmentChangedEvent} — permanently shaved a
 * buffed player's current HP down to the transient (unbuffed) clamp floor every single time it ran.
 *
 * <p>This test reproduces the repeated-reapply scenario directly (bypassing the Bukkit event wiring,
 * which {@code PerkAttributeApplierAttackSpeedTest} already establishes as the supported pattern for
 * exercising {@link PerkAttributeApplier#apply} without a live server): a player buffed to 40 max HP
 * (base-stats {@code max-health: 20} on top of vanilla 20) sitting at full (40) health must still be at
 * 40 after a second, equivalent {@code apply()} call (simulating a same-state equipment-change
 * re-trigger) — not clamped down to the vanilla 20 floor.
 */
class PerkAttributeApplierMaxHealthReapplyTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PerkAttributeApplier newApplier(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), "items: {}\n");
        File baseStats = new File(dir, com.trinityforge.config.domains.BaseStatsConfig.PATH);
        Files.createDirectories(baseStats.getParentFile().toPath());
        // base-stats max-health is authored as the FINAL absolute value, not an addend (BaseStatsConfig
        // javadoc: "written - vanillaDefault" is stored internally) -- 40 here means vanilla 20 + 20.
        Files.writeString(baseStats.toPath(), """
                base-stats:
                  max-health: 40
                """);
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

    private Player playerWith(Material mainhand) {
        PlayerMock player = server.addPlayer();
        player.registerAttribute(Attribute.ATTACK_SPEED);
        player.registerAttribute(Attribute.MAX_HEALTH);
        PlayerInventory inv = player.getInventory();
        if (mainhand != null) {
            inv.setItemInMainHand(new ItemStack(mainhand));
        }
        return player;
    }

    @Test
    void repeatedApplyDoesNotClampBuffedHealthDownToVanillaFloor(@TempDir File dir) throws IOException {
        PerkAttributeApplier applier = newApplier(dir);
        Player player = playerWith(Material.DIAMOND_SWORD);

        applier.apply(player);
        assertEquals(40.0, player.getAttribute(Attribute.MAX_HEALTH).getValue(), 1e-9,
                "base-stats max-health:20 must add onto vanilla 20 -> 40 max");

        // Player is topped up to the buffed max (simulates a player who has been at full HP for a
        // while, then equips/unequips something unrelated — e.g. a helmet swap — triggering a re-apply
        // with the identical net max-health buff).
        player.setHealth(40.0);

        applier.apply(player);

        assertEquals(40.0, player.getAttribute(Attribute.MAX_HEALTH).getValue(), 1e-9,
                "max health must still be 40 after the second apply");
        assertEquals(40.0, player.getHealth(), 1e-9,
                "CMB-03 regression: current HP must not be clamped down to the vanilla 20 floor by the "
                        + "transient clear-then-reapply of the MAX_HEALTH modifier");
    }

    @Test
    void repeatedApplyWithUnchangedBuffIsIdempotentOnHealth(@TempDir File dir) throws IOException {
        PerkAttributeApplier applier = newApplier(dir);
        Player player = playerWith(Material.DIAMOND_SWORD);

        applier.apply(player);
        player.setHealth(40.0);

        // Same config, re-applied (e.g. an unrelated equipment slot changed): current HP must be
        // unchanged, not just "not dropped to the vanilla floor".
        applier.apply(player);

        assertEquals(40.0, player.getHealth(), 1e-9, "idempotent re-apply must not change current HP");
    }
}
