package com.trinityforge.listeners;

import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Event wiring regression coverage for the Valhalla farming action tables. */
class ValhallaNonCombatExperienceListenerTest {

    private static final SkillCatalogEntry FARMING = new SkillCatalogEntry(
            SkillId.FARMING, 100, "1", level -> 1L,
            Map.of(
                    "entity_breed.FROG", 160.0,
                    "entity_drops.BEEF", 60.0,
                    "entity_shear.SHEEP", 200.0),
            Map.of());

    @Test
    void breedingUsesConfiguredSpeciesValue() {
        Wired wired = wired();
        Player breeder = player();
        LivingEntity child = mock(LivingEntity.class);
        when(child.getType()).thenReturn(EntityType.FROG);
        EntityBreedEvent event = mock(EntityBreedEvent.class);
        when(event.getBreeder()).thenReturn(breeder);
        when(event.getEntity()).thenReturn(child);

        wired.listener().onFarmingBreed(event);

        verify(wired.dispatcher()).grant(breeder.getUniqueId(), SkillId.FARMING, 160.0);
    }

    @Test
    void butcheryUsesConfiguredActualDropAmount() {
        Wired wired = wired();
        Player killer = player();
        LivingEntity victim = mock(LivingEntity.class);
        when(victim.getKiller()).thenReturn(killer);
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getEntityType()).thenReturn(EntityType.FROG);
        ItemStack beef = stack(Material.BEEF, 2);
        when(event.getDrops()).thenReturn(List.of(beef));

        wired.listener().onFarmingMobDeath(event);

        verify(wired.dispatcher()).grant(killer.getUniqueId(), SkillId.FARMING, 120.0);
    }

    @Test
    void shearingUsesConfiguredEntityValue() {
        Wired wired = wired();
        Player player = player();
        Entity sheep = mock(Entity.class);
        when(sheep.getType()).thenReturn(EntityType.SHEEP);
        PlayerShearEntityEvent event = mock(PlayerShearEntityEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getEntity()).thenReturn(sheep);

        wired.listener().onFarmingShear(event);

        verify(wired.dispatcher()).grant(player.getUniqueId(), SkillId.FARMING, 200.0);
    }

    private static Wired wired() {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.FARMING)).thenReturn(FARMING);
        return new Wired(new NativeSkillExperienceListener(
                fakePlugin(), dispatcher, catalog, mock(PlacedBlockTracker.class)), dispatcher);
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        return player;
    }

    private static ItemStack stack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        return stack;
    }

    private static Plugin fakePlugin() {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                ValhallaNonCombatExperienceListenerTest.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "TrinityForge";
                    case "namespace" -> "trinityforge";
                    case "toString" -> "FakePlugin";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private record Wired(NativeSkillExperienceListener listener,
                         NativeExperienceDispatcher dispatcher) {
    }
}
