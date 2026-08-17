package com.trinityforge.active;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActiveSkillCooldownKeys}: the {@code <id>-cooldown-reduction} key derivation (2026-07-25 CT設計
 * 一本化 §2, replacing the single global {@code skill-cooldown-reduction}), and the
 * "registered-ActiveSkill vs StatVocabulary" startup consistency check — same "fail loud instead of
 * silently drifting" policy as {@code VanillaAttributeDefaults}, but implemented as an explicit runtime
 * assertion (called from {@code TrinityForge#onEnable} right after every {@link ActiveSkill} is
 * registered) rather than a static initializer, because {@link ActiveSkillRegistry} membership is only
 * known at plugin-enable time, not at class-load time.
 */
class ActiveSkillCooldownKeysTest {

    private static ActiveSkill fake(String id) {
        return new ActiveSkill() {
            public String id() { return id; }
            public String gateEffectId() { return id; }
            public Set<String> targetSkills() { return Set.of("MINING"); }
            public long cooldownMillis(int tier) { return 1000L; }
            public ActivationResult activate(Player player, ActiveContext ctx) {
                return ActivationResult.success("ok");
            }
        };
    }

    @Test
    void forSkillDerivesTheConventionalPerSkillKey() {
        assertEquals("haste_active_mining_cooldown_reduction",
                ActiveSkillCooldownKeys.forSkill("haste-active-mining"));
    }

    @Test
    void forSkillCanonicalizesKebabAndSnakeCaseIdentically() {
        assertEquals(ActiveSkillCooldownKeys.forSkill("haste-active-mining"),
                ActiveSkillCooldownKeys.forSkill("HASTE_ACTIVE_MINING"));
    }

    @Test
    void forSkillIsDistinctPerSkillId() {
        assertTrue(!ActiveSkillCooldownKeys.forSkill("skill-a")
                .equals(ActiveSkillCooldownKeys.forSkill("skill-b")));
    }

    @Test
    void verifyRegisteredPassesWhenEveryRegisteredSkillHasItsKeyInStatVocabulary() {
        // haste-active-mining is the one ActiveSkill actually wired into StatVocabulary.ATTACK_KEYS
        // today (see StatVocabulary), so registering only it must not throw.
        ActiveSkillRegistry registry = new ActiveSkillRegistry();
        registry.register(fake("haste-active-mining"));

        ActiveSkillCooldownKeys.verifyRegistered(registry);
    }

    @Test
    void verifyRegisteredPassesForHasteActiveDiggingToo() {
        // 2026-08-18 (W-59): haste-active-digging も StatVocabulary.ATTACK_KEYS/PercentStatNormalize/
        // StatCategoryInference/StatsCategory/lore.yml/base-stats.yml へ登録済みであることの回帰。
        ActiveSkillRegistry registry = new ActiveSkillRegistry();
        registry.register(fake("haste-active-mining"));
        registry.register(fake("haste-active-digging"));

        ActiveSkillCooldownKeys.verifyRegistered(registry);
    }

    @Test
    void verifyRegisteredThrowsWhenARegisteredSkillsKeyIsMissingFromStatVocabulary() {
        // Simulates the exact mistake the check exists to catch: a new ActiveSkill registered without
        // its "<id>-cooldown-reduction" ever being added to StatVocabulary.ATTACK_KEYS.
        ActiveSkillRegistry registry = new ActiveSkillRegistry();
        registry.register(fake("some-future-active-skill"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ActiveSkillCooldownKeys.verifyRegistered(registry));
        assertTrue(ex.getMessage().contains("some-future-active-skill"), ex.getMessage());
        assertTrue(ex.getMessage().contains("some_future_active_skill_cooldown_reduction"), ex.getMessage());
    }

    @Test
    void verifyRegisteredOnAnEmptyRegistryNeverThrows() {
        ActiveSkillCooldownKeys.verifyRegistered(new ActiveSkillRegistry());
    }
}
