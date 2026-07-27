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
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #6 Part B のエンドツーエンド検証: 最終物理ダメージが負値になったとき {@link CombatListener} は
 * ダメージを0にし、被害者をその絶対値分だけ回復させる(setCancelledはしない)。防具の flat-defense を
 * 意図的に極端な値にして負の最終ダメージを作り出し、{@code physical.min-component-damage} を負値許容
 * 範囲(brief記載の既存スキーマ変更)まで緩めることで、床(floor)にクリップされる前の負値を観測する。
 */
@SuppressWarnings("removal") // deprecated-for-removal event ctors are the only test-constructable ones.
class CombatListenerNegativeDamageHealTest {

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
        return listener(dir, """
                items:
                  DIAMOND_CHESTPLATE:
                    fixed: { flat-defense: 500.0 }
                """);
    }

    private CombatListener listener(File dir, String itemStatsYaml) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), itemStatsYaml);

        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                  min-component-damage: -1000.0
                """);
        Plugin plugin = MockBukkit.createMockPlugin();
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
    void negativeFinalDamage_dealsZeroAndHealsVictim(@TempDir File dir) throws IOException {
        listener = listener(dir);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.AIR));

        // victimはPlayerでなければならない: flat-defenseの item-stats.yml オーバーレイは
        // PlayerStatAggregator/PlayerDefenseResolver経由でのみ適用される(Zombie等の非Playerはvanilla
        // armor/toughnessミラーのみを使う)。
        Player victim = server.addPlayer();
        victim.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        // victimに事前ダメージを与えて回復が観測できるようにする(満タンのままだと clampで見えなくなる)。
        double maxHealth = victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        victim.setHealth(Math.max(1.0, maxHealth / 2.0));
        double healthBefore = victim.getHealth();

        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 6.0);

        listener.onEntityDamageByEntity(event);

        assertEquals(0.0, event.getDamage(), 1e-9, "final damage must be clamped to 0, not negative");
        assertTrue(victim.getHealth() > healthBefore,
                "victim must be healed by the magnitude of the negative final damage; before="
                        + healthBefore + " after=" + victim.getHealth());
    }

    @Test
    void configuredNegativeAttackPowerReplacesVanillaAndHealsVictim(@TempDir File dir) throws IOException {
        listener = listener(dir, """
                items:
                  GOLDEN_SWORD:
                    fixed: { attack-power: -20.0, damage-modifier: 1.0 }
                """);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD));

        Player victim = server.addPlayer();
        double maxHealth = victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        victim.setHealth(Math.max(1.0, maxHealth / 2.0));
        double healthBefore = victim.getHealth();

        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 6.0);

        listener.onEntityDamageByEntity(event);

        assertEquals(0.0, event.getDamage(), 1e-9);
        assertTrue(victim.getHealth() > healthBefore,
                "negative configured attack-power must be used instead of falling back to vanilla damage");
    }
}
