package com.trinityforge.active;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ActiveSkillRegistry}: id lookup + target-skill (case-insensitive) candidate matching. */
class ActiveSkillRegistryTest {

    private static ActiveSkill fake(String id, String... targetSkills) {
        Set<String> skills = Set.of(targetSkills);
        return new ActiveSkill() {
            public String id() { return id; }
            public String gateEffectId() { return id; }
            public Set<String> targetSkills() { return skills; }
            public long cooldownMillis(int tier) { return 1000L; }
            public ActivationResult activate(Player player, ActiveContext ctx) {
                return ActivationResult.success("ok");
            }
        };
    }

    @Test
    void getReturnsRegisteredSkillById() {
        ActiveSkillRegistry registry = new ActiveSkillRegistry();
        ActiveSkill skill = fake("haste-active-mining", "MINING");
        registry.register(skill);

        assertEquals(skill, registry.get("haste-active-mining").orElseThrow());
        assertTrue(registry.get("unknown").isEmpty());
        assertTrue(registry.get(null).isEmpty());
    }

    @Test
    void forTargetSkillIsCaseInsensitiveAndPreservesRegistrationOrder() {
        ActiveSkillRegistry registry = new ActiveSkillRegistry();
        ActiveSkill first = fake("skill-a", "MINING");
        ActiveSkill second = fake("skill-b", "MINING");
        ActiveSkill unrelated = fake("skill-c", "WOODCUTTING");
        registry.register(first);
        registry.register(second);
        registry.register(unrelated);

        assertEquals(List.of(first, second), registry.forTargetSkill("mining"));
        assertEquals(List.of(first, second), registry.forTargetSkill("MINING"));
        assertEquals(List.of(unrelated), registry.forTargetSkill("WOODCUTTING"));
    }

    @Test
    void forTargetSkillWithNoMatchesOrBlankInputYieldsEmpty() {
        ActiveSkillRegistry registry = new ActiveSkillRegistry();
        registry.register(fake("skill-a", "MINING"));

        assertTrue(registry.forTargetSkill("FARMING").isEmpty());
        assertTrue(registry.forTargetSkill(null).isEmpty());
        assertTrue(registry.forTargetSkill("").isEmpty());
    }

    @Test
    void skillReachableFromMultipleTargetSkillsIsMatchedByEach() {
        ActiveSkillRegistry registry = new ActiveSkillRegistry();
        ActiveSkill multi = fake("haste-active-mining", "MINING", "DIGGING");
        registry.register(multi);

        assertEquals(List.of(multi), registry.forTargetSkill("MINING"));
        assertEquals(List.of(multi), registry.forTargetSkill("digging"));
        assertTrue(registry.forTargetSkill("WOODCUTTING").isEmpty());
    }

    @Test
    void allReturnsEveryRegisteredSkillInOrder() {
        ActiveSkillRegistry registry = new ActiveSkillRegistry();
        ActiveSkill first = fake("skill-a", "MINING");
        ActiveSkill second = fake("skill-b", "WOODCUTTING");
        registry.register(first);
        registry.register(second);

        assertEquals(List.of(first, second), List.copyOf(registry.all()));
    }
}
