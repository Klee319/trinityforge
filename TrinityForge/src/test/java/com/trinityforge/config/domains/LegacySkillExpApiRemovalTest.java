package com.trinityforge.config.domains;

import com.trinityforge.listeners.CombatListener;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

class LegacySkillExpApiRemovalTest {

    @Test
    void removedCastAndPerHitConfigurationApisAreNotExposed() throws Exception {
        Set<String> configMethods = Stream.of(SkillExpConfig.class.getDeclaredMethods())
                .map(method -> method.getName())
                .collect(Collectors.toSet());
        for (String removed : Set.of(
                "arsMagicExpPerCast",
                "arsMagicExpPerMana",
                "combatExpPerHit",
                "combatExpForSkill",
                "combatSameTargetCooldownSeconds",
                "combatDamageScaledMode",
                "combatDamageScale",
                "combatMobLevelScale")) {
            assertFalse(configMethods.contains(removed), removed + " is a removed legacy EXP API");
        }

        Set<String> listenerMethods = Stream.of(CombatListener.class.getDeclaredMethods())
                .map(method -> method.getName())
                .collect(Collectors.toSet());
        assertFalse(listenerMethods.contains("combatSkillExpAmount"),
                "per-hit legacy EXP calculation must not remain reachable");

        String configSource = Files.readString(Path.of(
                "src/main/java/com/trinityforge/config/domains/SkillExpConfig.java"));
        for (String removedKey : Set.of(
                "exp-per-cast",
                "exp-per-mana",
                "exp-per-hit",
                "same-target-cooldown-seconds",
                "combat.by-skill",
                "combat.mode",
                "combat.damage-scale",
                "combat.mob-level-scale")) {
            assertFalse(configSource.contains(removedKey),
                    removedKey + " is a removed legacy configuration key");
        }
    }
}
