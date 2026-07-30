package com.trinityforge.listeners;

import com.trinityforge.combat.MagicPipelineDamage;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArsMagicExperienceListenerAttributionTest {

    @AfterEach
    void clearMarker() {
        while (MagicPipelineDamage.isActive()) MagicPipelineDamage.clear();
    }

    @Test
    void synchronousDeathWindowRequiresPipelineMarkerMagicCauseAndSameCausingPlayer() {
        UUID caster = UUID.randomUUID();
        assertFalse(ArsMagicExperienceListener.isMarkedArsKill(
                MagicPipelineDamage.isActive(), EntityDamageEvent.DamageCause.MAGIC, caster, caster));

        MagicPipelineDamage.mark();
        assertTrue(ArsMagicExperienceListener.isMarkedArsKill(
                MagicPipelineDamage.isActive(), EntityDamageEvent.DamageCause.MAGIC, caster, caster));
        assertFalse(ArsMagicExperienceListener.isMarkedArsKill(
                MagicPipelineDamage.isActive(), EntityDamageEvent.DamageCause.ENTITY_ATTACK, caster, caster));
        assertFalse(ArsMagicExperienceListener.isMarkedArsKill(
                MagicPipelineDamage.isActive(), EntityDamageEvent.DamageCause.MAGIC,
                caster, UUID.randomUUID()));

        MagicPipelineDamage.clear();
        assertFalse(ArsMagicExperienceListener.isMarkedArsKill(
                MagicPipelineDamage.isActive(), EntityDamageEvent.DamageCause.MAGIC, caster, caster));
    }
}
