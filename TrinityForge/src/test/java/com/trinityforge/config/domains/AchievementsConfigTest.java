package com.trinityforge.config.domains;

import org.bukkit.Statistic;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless parse checks for achievements.yml (2026-07-23-stat-gate-overhaul §6.2/§6.7). */
class AchievementsConfigTest {

    private static final Logger LOG = Logger.getLogger("AchievementsConfigTest");

    private static AchievementsConfig.ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return AchievementsConfig.parse(cfg.getConfigurationSection("achievements"), LOG);
    }

    @Test
    void parsesStatisticAchievementWithRewards() throws Exception {
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  jump-king:
                    display-name: "ジャンプ王"
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 10000
                    broadcast: true
                    rewards:
                      special: ["dragon-slayer"]
                      commands: ["give %player% diamond 1"]
                """);
        assertEquals(0, result.skipped());
        AchievementsConfig.Achievement achievement = result.achievements().get(0);
        assertEquals("ジャンプ王", achievement.displayName());
        assertEquals(AchievementsConfig.TriggerType.STATISTIC, achievement.trigger().type());
        assertEquals(Statistic.JUMP, achievement.trigger().statistic());
        assertEquals(10000, achievement.trigger().threshold());
        assertTrue(achievement.broadcast());
        assertEquals(List.of("dragon-slayer"), achievement.rewards().special());
        assertEquals(List.of("give %player% diamond 1"), achievement.rewards().commands());
    }

    @Test
    void parsesAdvancementAchievement() throws Exception {
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  diamond:
                    display-name: "採掘者"
                    trigger:
                      type: advancement
                      advancement: "minecraft:story/mine_diamond"
                """);
        assertEquals(0, result.skipped());
        AchievementsConfig.Achievement achievement = result.achievements().get(0);
        assertEquals(AchievementsConfig.TriggerType.ADVANCEMENT, achievement.trigger().type());
        assertEquals("minecraft:story/mine_diamond", achievement.trigger().advancement());
    }

    @Test
    void missingTriggerIsSkipped() throws Exception {
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  broken:
                    display-name: "x"
                """);
        assertEquals(1, result.skipped());
        assertTrue(result.achievements().isEmpty());
    }

    @Test
    void invalidStatisticNameIsSkipped() throws Exception {
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  broken:
                    trigger:
                      type: statistic
                      statistic: NOT_REAL
                      threshold: 5
                """);
        assertEquals(1, result.skipped());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("2026-07-23 verifier指摘⑨: qualifier(Material/EntityType)必須のStatistic "
            + "(MINE_BLOCK等) はロード時に警告+スキップ(毎分ポーリングの警告スパムを未然に防ぐ)")
    void qualifierRequiringStatisticIsSkippedAtLoadTime() throws Exception {
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  digger:
                    trigger:
                      type: statistic
                      statistic: MINE_BLOCK
                      threshold: 100
                """);
        assertEquals(1, result.skipped());
        assertTrue(result.achievements().isEmpty());
    }

    @Test
    void invalidThresholdIsSkipped() throws Exception {
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  broken:
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 0
                """);
        assertEquals(1, result.skipped());
    }

    @Test
    void advancementWithoutKeyIsSkipped() throws Exception {
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  broken:
                    trigger:
                      type: advancement
                """);
        assertEquals(1, result.skipped());
    }

    @Test
    void statisticAndAdvancementFiltersSplitCorrectly() throws Exception {
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  a:
                    trigger: { type: statistic, statistic: JUMP, threshold: 1 }
                  b:
                    trigger: { type: advancement, advancement: "minecraft:story/mine_diamond" }
                """);
        assertEquals(0, result.skipped());
        assertEquals(2, result.achievements().size());
    }

    @Test
    void missingSectionYieldsEmpty() {
        assertTrue(AchievementsConfig.parse(null, LOG).achievements().isEmpty());
    }

    @Test
    void rewardsDefaultToEmptyWhenOmitted() throws Exception {
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  a:
                    trigger: { type: statistic, statistic: JUMP, threshold: 1 }
                """);
        AchievementsConfig.Achievement achievement = result.achievements().get(0);
        assertTrue(achievement.rewards().special().isEmpty());
        assertTrue(achievement.rewards().commands().isEmpty());
        assertEquals(achievement.id(), achievement.displayName(), "display-name未指定はidにフォールバック");
        assertTrue(achievement.rewards().items().isEmpty());
        assertEquals(0, achievement.rewards().vanillaExp());
        assertTrue(achievement.rewards().jobExp().isEmpty());
        assertTrue(achievement.rewards().permanentBuffs().isEmpty());
    }

    @Test
    void parsesItemsVanillaExpJobExpAndPermanentBuffs() throws Exception {
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  full-reward:
                    trigger: { type: statistic, statistic: JUMP, threshold: 1 }
                    rewards:
                      items:
                        - id: diamond
                          amount: 3
                        - id: emerald
                      vanilla-exp: 100
                      job-exp:
                        - skill: mining
                          amount: 500.0
                      permanent-buffs:
                        attack-power: 5
                        move-speed: 0.02
                """);
        assertEquals(0, result.skipped());
        AchievementsConfig.Rewards rewards = result.achievements().get(0).rewards();
        assertEquals(2, rewards.items().size());
        assertEquals("diamond", rewards.items().get(0).id());
        assertEquals(3, rewards.items().get(0).amount());
        assertEquals("emerald", rewards.items().get(1).id());
        assertEquals(1, rewards.items().get(1).amount(), "amount省略時は1");
        assertEquals(100, rewards.vanillaExp());
        assertEquals(1, rewards.jobExp().size());
        assertEquals("MINING", rewards.jobExp().get(0).skill(), "大文字正規化される");
        assertEquals(500.0, rewards.jobExp().get(0).amount());
        assertEquals(5.0, rewards.permanentBuffs().get("attack_power"));
        assertEquals(0.02, rewards.permanentBuffs().get("move_speed"));
    }

    @Test
    void itemsWithBlankIdIsSkippedNotFatal() throws Exception {
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  a:
                    trigger: { type: statistic, statistic: JUMP, threshold: 1 }
                    rewards:
                      items:
                        - id: ""
                        - id: diamond
                """);
        assertEquals(0, result.skipped(), "アイテム1件不正でもachievement全体は読み込まれる");
        assertEquals(1, result.achievements().get(0).rewards().items().size());
        assertEquals("diamond", result.achievements().get(0).rewards().items().get(0).id());
    }

    @Test
    void jobExpWithUnknownSkillIsSkipped() throws Exception {
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  a:
                    trigger: { type: statistic, statistic: JUMP, threshold: 1 }
                    rewards:
                      job-exp:
                        - skill: NOT_A_SKILL
                          amount: 10
                        - skill: FARMING
                          amount: 10
                """);
        assertEquals(0, result.skipped());
        assertEquals(1, result.achievements().get(0).rewards().jobExp().size());
        assertEquals("FARMING", result.achievements().get(0).rewards().jobExp().get(0).skill());
    }

    @Test
    void jobExpWithNegativeOrZeroAmountIsSkipped() throws Exception {
        // 修正F: 負の「報酬」でjob EXPが減るのを防ぐ。0もvanilla-expのクランプ方針に揃えてスキップする。
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  a:
                    trigger: { type: statistic, statistic: JUMP, threshold: 1 }
                    rewards:
                      job-exp:
                        - skill: MINING
                          amount: -50.0
                        - skill: MINING
                          amount: 0
                        - skill: FARMING
                          amount: 10
                """);
        assertEquals(0, result.skipped());
        assertEquals(1, result.achievements().get(0).rewards().jobExp().size());
        assertEquals("FARMING", result.achievements().get(0).rewards().jobExp().get(0).skill());
    }

    @Test
    void permanentBuffsWithUnknownKeyIsSkippedNotFatal() throws Exception {
        // 修正C: canonical化後もどのperk-buffチャネル(StatVocabulary)にも属さない未知/typoキーは
        // silent no-op(受理されるが誰も読まない)を防ぐため警告してスキップする。
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  a:
                    trigger: { type: statistic, statistic: JUMP, threshold: 1 }
                    rewards:
                      permanent-buffs:
                        atack-power: 5
                        attack-power: 3
                """);
        assertEquals(0, result.skipped(), "permanent-buffs内1キー不正でもachievement全体は読み込まれる");
        var buffs = result.achievements().get(0).rewards().permanentBuffs();
        assertEquals(1, buffs.size());
        assertEquals(3.0, buffs.get("attack_power"));
    }

    @Test
    void negativeVanillaExpIsClampedToZero() throws Exception {
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  a:
                    trigger: { type: statistic, statistic: JUMP, threshold: 1 }
                    rewards:
                      vanilla-exp: -50
                """);
        assertEquals(0, result.achievements().get(0).rewards().vanillaExp());
    }

    @Test
    void permanentBuffsWithNonNumericValueIsSkippedNotFatal() throws Exception {
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  a:
                    trigger: { type: statistic, statistic: JUMP, threshold: 1 }
                    rewards:
                      permanent-buffs:
                        attack-power: "not-a-number"
                        move-speed: 0.02
                """);
        assertEquals(0, result.skipped());
        var buffs = result.achievements().get(0).rewards().permanentBuffs();
        assertEquals(1, buffs.size());
        assertEquals(0.02, buffs.get("move_speed"));
    }

    @Test
    void cmb15PermanentBuffsPenetrationTwentyIsNormalizedToTwentyPercent() throws Exception {
        // CMB-15: permanent-buffsもitem-stats等の他経路と同様にPercentStatNormalizeを通す。
        // 20(20%のつもり)がそのまま渡ると貫通が実質飽和する。
        AchievementsConfig.ParseResult result = parse("""
                achievements:
                  a:
                    trigger: { type: statistic, statistic: JUMP, threshold: 1 }
                    rewards:
                      permanent-buffs:
                        penetration: 20
                        crit-chance: 0.15
                """);
        assertEquals(0, result.skipped());
        var buffs = result.achievements().get(0).rewards().permanentBuffs();
        assertEquals(0.20, buffs.get("penetration"));
        assertEquals(0.15, buffs.get("crit_chance"), "既にフラクションの値は変化しない");
    }
}
