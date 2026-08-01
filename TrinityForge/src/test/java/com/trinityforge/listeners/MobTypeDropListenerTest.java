package com.trinityforge.listeners;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.MobTypesConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.mobs.MobDropEntry;
import com.trinityforge.mobs.MobLevelCoefficients;
import com.trinityforge.mobs.MobTypeDefinition;
import com.trinityforge.pdc.MobData;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.ItemFactory;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Player-kill and AFK gates for {@link MobTypeDropListener}'s additive drop table. */
class MobTypeDropListenerTest {

    private ServerMock server;
    private WorldMock world;
    private MobTypeDropListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");

        listener = listenerWith(List.of(new MobDropEntry(Material.DIAMOND, 1.0, 1, 1, null)), null);
    }

    /**
     * 2026-08-01 U13: drops 一覧と {@code custom:} 解決先を差し替えられるようにした
     * (バニラ Material のドロップとカスタムアイテムのドロップを同じ経路で確かめるため)。
     */
    private static MobTypeDropListener listenerWith(List<MobDropEntry> drops,
                                                     CrossPluginItemResolver itemResolver) {
        MobTypesConfig mobTypes = mock(MobTypesConfig.class);
        MobTypeDefinition zombieDefinition = new MobTypeDefinition(
                org.bukkit.entity.EntityType.ZOMBIE,
                1,
                0.0,
                null,
                DefenseStats.NONE,
                DefenseStats.NONE,
                AttackStats.plain(0),
                MobLevelCoefficients.ZERO,
                drops);
        when(mobTypes.definition(org.bukkit.entity.EntityType.ZOMBIE))
                .thenReturn(Optional.of(zombieDefinition));

        return new MobTypeDropListener(
                mobTypes,
                mock(CraftQualityConfig.class),
                mock(QualityConfig.class),
                mock(ItemFactory.class),
                null,
                null,
                itemResolver,
                new SplittableRandom(0));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private EntityDeathEvent deathEvent(Player killer) {
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        MobData.stampMobType(zombie, 1, DefenseStats.NONE, DefenseStats.NONE);
        if (killer != null) {
            ((org.mockbukkit.mockbukkit.entity.LivingEntityMock) zombie).setKiller(killer);
        }
        org.bukkit.damage.DamageSource source = org.bukkit.damage.DamageSource
                .builder(org.bukkit.damage.DamageType.GENERIC_KILL).build();
        return new EntityDeathEvent(zombie, source, new ArrayList<ItemStack>());
    }

    @Test
    void nonPlayerKillNeverRollsMobTypeDrops() {
        EntityDeathEvent event = deathEvent(null);

        listener.onDeath(event);

        assertTrue(event.getDrops().isEmpty(),
                "lava, fall damage, and mob-infighting kills must not produce mob-types bonus drops");
    }

    @Test
    void afkDropGateSuppressesOnlyTheBonusDrop() {
        listener.setDropGate(player -> true);
        EntityDeathEvent event = deathEvent(server.addPlayer());
        event.getDrops().add(new ItemStack(Material.ROTTEN_FLESH));

        listener.onDeath(event);

        assertEquals(1, event.getDrops().size(),
                "AFK gating must preserve the vanilla drop list while suppressing the TF bonus");
        assertEquals(Material.ROTTEN_FLESH, event.getDrops().get(0).getType());
    }

    @Test
    void activePlayerKillStillReceivesMobTypeDrop() {
        listener.setDropGate(player -> false);
        EntityDeathEvent event = deathEvent(server.addPlayer());

        listener.onDeath(event);

        assertEquals(1, event.getDrops().size());
        assertEquals(Material.DIAMOND, event.getDrops().get(0).getType());
    }

    // --- 2026-08-01 U13: drops[].material の custom:<id> ---

    @Test
    void customDropIsBuiltByTheResolverWithTheRolledCount() {
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        when(resolver.create("tf_scrap")).thenReturn(Optional.of(new ItemStack(Material.PAPER, 1)));
        MobTypeDropListener custom = listenerWith(
                List.of(MobDropEntry.ofCatalog("tf_scrap", 1.0, 3, 3, null)), resolver);
        EntityDeathEvent event = deathEvent(server.addPlayer());

        custom.onDeath(event);

        assertEquals(1, event.getDrops().size());
        assertEquals(Material.PAPER, event.getDrops().get(0).getType());
        assertEquals(3, event.getDrops().get(0).getAmount(),
                "解決したスタックの個数は抽選結果で上書きされる");
        verify(resolver).create("tf_scrap");
    }

    @Test
    void unresolvableCustomDropSkipsOnlyThatRollAndKeepsVanillaDrops() {
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        when(resolver.create("gone")).thenReturn(Optional.empty());
        MobTypeDropListener custom = listenerWith(
                List.of(MobDropEntry.ofCatalog("gone", 1.0, 1, 1, null),
                        new MobDropEntry(Material.DIAMOND, 1.0, 1, 1, null)),
                resolver);
        EntityDeathEvent event = deathEvent(server.addPlayer());
        event.getDrops().add(new ItemStack(Material.ROTTEN_FLESH));

        custom.onDeath(event);

        assertEquals(2, event.getDrops().size(),
                "解決できない custom: は自分の1件だけを捨て、他のドロップは巻き込まない");
        assertEquals(Material.ROTTEN_FLESH, event.getDrops().get(0).getType());
        assertEquals(Material.DIAMOND, event.getDrops().get(1).getType());
    }

    @Test
    void unwiredResolverDropsNothingCustomButStillRollsVanillaEntries() {
        MobTypeDropListener custom = listenerWith(
                List.of(MobDropEntry.ofCatalog("tf_scrap", 1.0, 1, 1, null),
                        new MobDropEntry(Material.DIAMOND, 1.0, 1, 1, null)),
                null);
        EntityDeathEvent event = deathEvent(server.addPlayer());

        custom.onDeath(event);

        assertEquals(1, event.getDrops().size(),
                "resolver 未配線なら custom: は出ないが、Material のドロップは従来どおり出る");
        assertEquals(Material.DIAMOND, event.getDrops().get(0).getType());
    }

    @Test
    void customDropIsNeverQualityStampedAgain() {
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        when(resolver.create("tf_sword")).thenReturn(Optional.of(new ItemStack(Material.IRON_SWORD, 1)));
        ItemFactory itemFactory = mock(ItemFactory.class);
        MobTypesConfig mobTypes = mock(MobTypesConfig.class);
        when(mobTypes.definition(org.bukkit.entity.EntityType.ZOMBIE)).thenReturn(Optional.of(
                new MobTypeDefinition(org.bukkit.entity.EntityType.ZOMBIE, 1, 0.0, null,
                        DefenseStats.NONE, DefenseStats.NONE, AttackStats.plain(0),
                        MobLevelCoefficients.ZERO,
                        List.of(MobDropEntry.ofCatalog("tf_sword", 1.0, 1, 1, 7)))));
        MobTypeDropListener custom = new MobTypeDropListener(mobTypes, mock(CraftQualityConfig.class),
                mock(QualityConfig.class), itemFactory, null, null, resolver, new SplittableRandom(0));
        EntityDeathEvent event = deathEvent(server.addPlayer());

        custom.onDeath(event);

        assertEquals(1, event.getDrops().size());
        // カタログ品は resolver 内の ItemFactory#create が rollSeed/品質を打つので、
        // ここで stamp を重ねると上書きになる。
        verify(itemFactory, never()).stamp(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }
}
