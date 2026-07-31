package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FarmingGimmickConfig;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 畜産 {@code animal-damage-4x} の対象判定が Paper の {@code Enemy} 基準であることの契約。
 *
 * <p>2026-07-31 まで {@link AnimalDamageListener} は Bukkit の {@code Monster} で敵対を判定していた。
 * <b>HOGLIN は {@code Animals=true} / {@code Monster=false} / {@code Enemy=true}</b> なので、
 * ネザーで<b>ホグリンを畜産倍率(既定4倍)で殴れていた</b>。
 * 「{@code Monster}/{@code Animals} は敵対分類に使えない」は既に明文化済みの落とし穴
 * ({@code ResourceServerMobSimulationTest#isHostile})。
 */
class AnimalDamageHostileVictimTest {

    private static final double MULTIPLIER = 4.0;
    private static final double BASE_DAMAGE = 5.0;

    private ServerMock server;
    private WorldMock world;
    private AnimalDamageListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");

        DedicatedEffectsConfig effects = mock(DedicatedEffectsConfig.class);
        when(effects.isActive(any(Player.class), eq("animal-damage-4x"))).thenReturn(true);
        FarmingGimmickConfig gimmick = mock(FarmingGimmickConfig.class);
        when(gimmick.animalDamageMultiplier()).thenReturn(MULTIPLIER);

        listener = new AnimalDamageListener(effects, gimmick);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private double damageDealtTo(Class<? extends Entity> victimType) {
        Player attacker = server.addPlayer();
        attacker.teleport(world.getSpawnLocation());
        Entity victim = world.spawn(world.getSpawnLocation(), victimType);
        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker)
                .withDirectEntity(attacker)
                .build();
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, BASE_DAMAGE);
        listener.onEntityDamageByEntity(event);
        return event.getDamage();
    }

    @Test
    @DisplayName("牛には畜産倍率が乗る")
    void peacefulAnimalStillGetsTheMultiplier() {
        assertEquals(BASE_DAMAGE * MULTIPLIER, damageDealtTo(Cow.class));
    }

    @Test
    @DisplayName("ホグリンには畜産倍率が乗らない(Animals だが Enemy)")
    void hoglinIsExcludedEvenThoughItImplementsAnimals() {
        assertEquals(BASE_DAMAGE, damageDealtTo(Hoglin.class),
                "Monster 基準だと Animals=true/Monster=false で4倍が乗り、ネザーで殴り放題だった");
    }

    @Test
    @DisplayName("敵対判定の基準: HOGLIN は Animals かつ Enemy だが Monster ではない")
    void hoglinClassificationIsTheReasonMonsterCannotBeUsed() {
        assertTrue(Animals.class.isAssignableFrom(Hoglin.class));
        assertTrue(Enemy.class.isAssignableFrom(Hoglin.class));
        assertFalse(Monster.class.isAssignableFrom(Hoglin.class),
                "Monster で敵対を判定してはいけない理由そのもの");
        // 牛は Animals だけ(=倍率対象)。
        assertTrue(Animals.class.isAssignableFrom(Cow.class));
        assertFalse(Enemy.class.isAssignableFrom(Cow.class));
    }
}
