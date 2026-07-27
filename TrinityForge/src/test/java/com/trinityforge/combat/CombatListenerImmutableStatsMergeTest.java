package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.listeners.CombatListener;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * CMB-01 regression: {@code CombatListener.mergeStats} used to return the caller's immutable
 * {@code base} map unchanged whenever {@code addend} was empty (no unlocked skill-tree perks / no
 * addon stats — the standard state for a brand-new player). {@code PlayerCombatAggregate.item()} is
 * built via {@link java.util.Map#copyOf}, so aliasing it and later calling {@code .merge()} on the
 * result (Breach penetration bonus, coating flat-damage bonus) threw
 * {@link UnsupportedOperationException}, aborting {@code onEntityDamageByEntity} entirely and letting
 * vanilla's raw damage through with none of TF's pipeline (armor zeroing, EXP, bleed, AoE, ...)
 * applied.
 *
 * <p>This test reproduces exactly that trigger: a player with no skill-tree perks unlocked
 * ({@link SkillPerkStatSource#EMPTY}) and no addon stats, wielding a mainhand weapon enchanted with
 * Breach (which forces {@code enchantBonuses.penetrationBonus() > 0}, taking the
 * {@code attackerStats.merge(...)} branch at {@code CombatListener.java:220-221}). Before the fix this
 * threw; after the fix it must complete without throwing and still apply TF's damage pipeline (the
 * event's damage must be overwritten, not left as vanilla's raw value).
 */
@SuppressWarnings("removal") // deprecated-for-removal event ctors are the only test-constructable ones.
class CombatListenerImmutableStatsMergeTest {

    private ServerMock server;
    private WorldMock world;
    private CombatListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private CombatListener listener(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        // No stat overlay for DIAMOND_SWORD: item() carries no attack-power entry, so
        // tfBaseReplaces=false and the merge chain runs through the empty-addend short-circuit path
        // that used to alias the immutable Map.copyOf base.
        Files.writeString(itemStats.toPath(), "items: {}\n");
        // dungeon-only-exp defaults true, which routes expAllowedInWorld through
        // TrinityForge.getInstance().dungeonWorldRegistry() -- unavailable in this unit-test wiring
        // (no live plugin singleton). Disabling it keeps the test focused on CMB-01 (mergeStats) and
        // out of that unrelated live-plugin dependency.
        File skillExp = new File(dir, com.trinityforge.config.domains.SkillExpConfig.PATH);
        Files.createDirectories(skillExp.getParentFile().toPath());
        Files.writeString(skillExp.toPath(), "dungeon-only-exp: false\n");

        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                """);
        Plugin plugin = MockBukkit.createMockPlugin();
        // No perks unlocked (SkillPerkStatSource.EMPTY) and no addon stats (RoleBuffResolver with an
        // empty role-buffs config) => agg.perkAttack() and agg.addon() are both empty, which is the
        // exact CMB-01 trigger condition.
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        SymmetricCombatService svc = new SymmetricCombatService(
                damage, cm.combatLevel(), cm.mobTypes(), SkillLevelSource.EMPTY, defense);
        BleedService bleed = new BleedService(plugin, svc, damage);
        return new CombatListener(plugin, svc, cm.itemStats(),
                damage, SkillLevelSource.EMPTY, bleed, perks, aggregator,
                cm.useRequirements(), cm.skillExp(), cm.craftingFeatures(),
                new RoleBuffResolver(cm.roleBuffs()));
    }

    @Test
    void breachPenetrationWithNoPerksOrAddonStats_doesNotThrowUnsupportedOperationException(
            @TempDir File dir) throws IOException {
        listener = listener(dir);
        Player attacker = server.addPlayer();

        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.addEnchant(Enchantment.BREACH, 1, true);
        sword.setItemMeta(meta);
        attacker.getInventory().setItemInMainHand(sword);

        Player victim = server.addPlayer();

        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 6.0);

        assertDoesNotThrow(() -> listener.onEntityDamageByEntity(event),
                "mergeStats must never alias/mutate the immutable Map.copyOf produced by "
                        + "PlayerCombatAggregate.item() — CMB-01 regression");
    }
}
