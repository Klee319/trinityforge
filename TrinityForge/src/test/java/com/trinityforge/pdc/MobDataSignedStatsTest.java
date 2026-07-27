package com.trinityforge.pdc;

import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobDataSignedStatsTest {

    private ServerMock server;
    private Zombie mob;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        var world = server.addSimpleWorld("world");
        mob = world.spawn(world.getSpawnLocation(), Zombie.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void partialAttackStampUsesNeutralModifierAndPreservesSignedValues() {
        var pdc = mob.getPersistentDataContainer();
        pdc.set(PdcKeys.MOB_ATTACK_POWER, PersistentDataType.DOUBLE, -12.0);

        MobData data = MobData.of(mob);
        assertTrue(data.hasAttackProfile());
        assertEquals(-12.0, data.attackStats().defaultDamage());
        assertEquals(1.0, data.attackStats().damageModifier());

        pdc.set(PdcKeys.MOB_ATTACK_DAMAGE_MODIFIER, PersistentDataType.DOUBLE, -0.5);
        pdc.set(PdcKeys.MOB_DODGE_CHANCE, PersistentDataType.DOUBLE, -0.3);
        assertEquals(-0.5, data.attackStats().damageModifier());
        assertEquals(-0.3, data.dodgeChance());
    }
}
