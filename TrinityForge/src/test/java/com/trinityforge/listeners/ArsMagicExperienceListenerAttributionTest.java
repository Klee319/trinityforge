package com.trinityforge.listeners;

import com.trinityforge.combat.MagicPipelineDamage;
import org.bukkit.GameMode;
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

    /**
     * 2026-08-18 ユーザー報告「魔法で敵を倒しても Ars 魔法の経験値が手に入らなかった」の回帰ガード。
     *
     * <p>真因は<b>クリエイティブ除外の非対称</b>: 武器・弓術の討伐EXP({@code CombatListener#onCombatKill})
     * はゲームモードを一切見ないのに、魔法だけ CREATIVE/SPECTATOR を弾いていた。実サーバのログでも
     * 報告時刻の前後で {@code /gmc} が連発されており、「剣なら入るのに魔法だと入らない」状態だった。
     */
    @Test
    void killExpIgnoresTheKillersGameMode() {
        UUID caster = UUID.randomUUID();
        MagicPipelineDamage.mark();
        try {
            for (GameMode mode : GameMode.values()) {
                assertTrue(ArsMagicExperienceListener.killGrantsExp(
                                MagicPipelineDamage.isActive(), EntityDamageEvent.DamageCause.MAGIC,
                                caster, caster, mode),
                        "ゲームモード " + mode + " で魔法の討伐EXPが弾かれている"
                                + "(武器・弓術はモードを見ないので非対称になる)");
            }
            // ゲームモードを無視しても、マーカー/cause/起因者の3条件は依然として必須。
            assertFalse(ArsMagicExperienceListener.killGrantsExp(
                    MagicPipelineDamage.isActive(), EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                    caster, caster, GameMode.SURVIVAL));
            assertFalse(ArsMagicExperienceListener.killGrantsExp(
                    MagicPipelineDamage.isActive(), EntityDamageEvent.DamageCause.MAGIC,
                    caster, UUID.randomUUID(), GameMode.SURVIVAL));
        } finally {
            MagicPipelineDamage.clear();
        }
        assertFalse(ArsMagicExperienceListener.killGrantsExp(
                MagicPipelineDamage.isActive(), EntityDamageEvent.DamageCause.MAGIC,
                caster, caster, GameMode.SURVIVAL), "マーカー無しで付与している");
    }
}
