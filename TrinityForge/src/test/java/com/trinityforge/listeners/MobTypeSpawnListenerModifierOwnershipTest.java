package com.trinityforge.listeners;

import com.trinityforge.config.domains.MobTypesConfig;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ZombieMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * CMB-20 regression: {@link MobTypeSpawnListener#applyMaxHealth}/{@link MobTypeSpawnListener#syncVanillaArmorIcons}
 * used to remove EVERY attribute modifier on the spawning entity unconditionally
 * ({@code for (AttributeModifier m : attr.getModifiers()) attr.removeModifier(m);}), silently discarding
 * modifiers other plugins (e.g. EliteMobs) had already applied on the same spawn tick. The fix reuses
 * {@code PerkAttributeApplier#clearOwnModifiers}' identification rule (a modifier's {@link NamespacedKey}
 * namespace equals this plugin's own) so only TF-owned modifiers are ever removed.
 */
class MobTypeSpawnListenerModifierOwnershipTest {

    private ServerMock server;
    private Plugin tfPlugin;
    private Plugin foreignPlugin;
    private MobTypeSpawnListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        tfPlugin = MockBukkit.createMockPlugin("TrinityForge");
        foreignPlugin = MockBukkit.createMockPlugin("EliteMobs");
        listener = new MobTypeSpawnListener(tfPlugin, mock(MobTypesConfig.class));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static AttributeModifier modifier(Plugin owner, String key, double amount) {
        return new AttributeModifier(new NamespacedKey(owner, key), amount,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY);
    }

    @Test
    void applyMaxHealthLeavesForeignPluginModifierIntactAndRemovesOwnOnly() {
        ZombieMock zombie = new ZombieMock(server, UUID.randomUUID());
        zombie.registerAttribute(Attribute.MAX_HEALTH);
        AttributeInstance attr = zombie.getAttribute(Attribute.MAX_HEALTH);

        AttributeModifier foreign = modifier(foreignPlugin, "hp-buff", 5.0);
        AttributeModifier own = modifier(tfPlugin, "statmod.max_health.nosd.head", 3.0);
        attr.addModifier(foreign);
        attr.addModifier(own);

        listener.applyMaxHealth(zombie, 40.0);

        assertTrue(attr.getModifiers().contains(foreign),
                "CMB-20: other plugins' MAX_HEALTH modifiers must survive TF's mob-spawn HP application");
        assertFalse(attr.getModifiers().contains(own),
                "TF-owned modifiers must still be cleared before the base value is re-set");
        assertEquals(40.0, attr.getBaseValue(), 1e-9);
    }

    @Test
    void applyMaxHealthWithNoForeignModifiersStillSetsBaseValue() {
        // Sanity check that the new ownership filter didn't silently break the common (no foreign
        // modifier) path.
        ZombieMock zombie = new ZombieMock(server, UUID.randomUUID());
        zombie.registerAttribute(Attribute.MAX_HEALTH);
        AttributeInstance attr = zombie.getAttribute(Attribute.MAX_HEALTH);

        listener.applyMaxHealth(zombie, 55.0);

        assertEquals(55.0, attr.getBaseValue(), 1e-9);
        assertEquals(55.0, zombie.getHealth(), 1e-9);
    }

    @Test
    void syncVanillaArmorIconsLeavesForeignPluginModifierIntactOnArmorAndToughness() {
        ZombieMock zombie = new ZombieMock(server, UUID.randomUUID());
        zombie.registerAttribute(Attribute.ARMOR);
        zombie.registerAttribute(Attribute.ARMOR_TOUGHNESS);
        AttributeInstance armor = zombie.getAttribute(Attribute.ARMOR);
        AttributeInstance toughness = zombie.getAttribute(Attribute.ARMOR_TOUGHNESS);

        AttributeModifier foreignArmor = modifier(foreignPlugin, "armor-buff", 2.0);
        AttributeModifier foreignToughness = modifier(foreignPlugin, "toughness-buff", 1.0);
        armor.addModifier(foreignArmor);
        toughness.addModifier(foreignToughness);

        listener.syncVanillaArmorIcons(zombie, 8.0);

        assertTrue(armor.getModifiers().contains(foreignArmor),
                "CMB-20: other plugins' ARMOR modifiers must survive TF's HUD-icon sync");
        assertTrue(toughness.getModifiers().contains(foreignToughness),
                "CMB-20: other plugins' ARMOR_TOUGHNESS modifiers must survive TF's HUD-icon sync");
        assertEquals(8.0, armor.getBaseValue(), 1e-9);
        assertEquals(0.0, toughness.getBaseValue(), 1e-9);
    }
}
