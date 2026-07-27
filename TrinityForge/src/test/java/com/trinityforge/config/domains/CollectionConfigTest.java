package com.trinityforge.config.domains;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless parse checks for the collection encyclopedia config (M7). */
class CollectionConfigTest {

    private static final Logger LOG = Logger.getLogger("CollectionConfigTest");

    private static CollectionConfig.ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return CollectionConfig.parseTiers(cfg.getConfigurationSection("reward-tiers"), LOG);
    }

    @Test
    void parsesTiersSortedByThreshold() throws Exception {
        CollectionConfig.ParseResult result = parse("""
                reward-tiers:
                  silver:
                    threshold: 30
                    title: "熟練収集家"
                    broadcast: true
                    commands:
                      - "say %player% silver!"
                  bronze:
                    threshold: 10
                """);
        assertEquals(0, result.skipped());
        List<CollectionConfig.RewardTier> tiers = result.tiers();
        assertEquals(2, tiers.size());
        // threshold昇順に並ぶ(付与順が段階解放の順序になる)。
        assertEquals("bronze", tiers.get(0).id());
        assertEquals(10, tiers.get(0).threshold());
        assertNull(tiers.get(0).title());
        assertFalse(tiers.get(0).broadcast());
        assertTrue(tiers.get(0).commands().isEmpty());

        assertEquals("silver", tiers.get(1).id());
        assertEquals("熟練収集家", tiers.get(1).title());
        assertTrue(tiers.get(1).broadcast());
        assertEquals(List.of("say %player% silver!"), tiers.get(1).commands());
    }

    @Test
    void invalidThresholdIsSkipped() throws Exception {
        CollectionConfig.ParseResult result = parse("""
                reward-tiers:
                  broken:
                    title: "no threshold"
                  zero:
                    threshold: 0
                  ok:
                    threshold: 5
                """);
        assertEquals(2, result.skipped());
        assertEquals(1, result.tiers().size());
        assertEquals("ok", result.tiers().get(0).id());
    }

    @Test
    void missingSectionYieldsNoTiers() {
        assertEquals(0, CollectionConfig.parseTiers(null, LOG).tiers().size());
    }

    @Test
    void parsesTierSpecialRewardIds() throws Exception {
        CollectionConfig.ParseResult result = parse("""
                reward-tiers:
                  bronze:
                    threshold: 10
                    special:
                      - dragon-slayer
                      - "  crit-aura  "
                """);
        assertEquals(0, result.skipped());
        assertEquals(List.of("dragon-slayer", "crit-aura"), result.tiers().get(0).special());
    }

    @Test
    void tierWithoutSpecialYieldsEmptyList() throws Exception {
        CollectionConfig.ParseResult result = parse("""
                reward-tiers:
                  bronze:
                    threshold: 5
                """);
        assertTrue(result.tiers().get(0).special().isEmpty());
        assertTrue(result.tiers().get(0).items().isEmpty());
        assertEquals(0, result.tiers().get(0).vanillaExp());
        assertTrue(result.tiers().get(0).jobExp().isEmpty());
        assertTrue(result.tiers().get(0).permanentBuffs().isEmpty());
    }

    @Test
    void parsesItemsVanillaExpJobExpAndPermanentBuffs() throws Exception {
        CollectionConfig.ParseResult result = parse("""
                reward-tiers:
                  bronze:
                    threshold: 5
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
        CollectionConfig.RewardTier tier = result.tiers().get(0);
        assertEquals(2, tier.items().size());
        assertEquals("diamond", tier.items().get(0).id());
        assertEquals(3, tier.items().get(0).amount());
        assertEquals(1, tier.items().get(1).amount(), "amount省略時は1");
        assertEquals(100, tier.vanillaExp());
        assertEquals(1, tier.jobExp().size());
        assertEquals("MINING", tier.jobExp().get(0).skill());
        assertEquals(500.0, tier.jobExp().get(0).amount());
        assertEquals(5.0, tier.permanentBuffs().get("attack_power"));
        assertEquals(0.02, tier.permanentBuffs().get("move_speed"));
    }

    @Test
    void jobExpWithUnknownSkillIsSkippedNotFatal() throws Exception {
        CollectionConfig.ParseResult result = parse("""
                reward-tiers:
                  bronze:
                    threshold: 5
                    job-exp:
                      - skill: NOT_A_SKILL
                        amount: 10
                """);
        assertEquals(0, result.skipped());
        assertTrue(result.tiers().get(0).jobExp().isEmpty());
    }

    @Test
    void negativeVanillaExpIsClampedToZero() throws Exception {
        CollectionConfig.ParseResult result = parse("""
                reward-tiers:
                  bronze:
                    threshold: 5
                    vanilla-exp: -10
                """);
        assertEquals(0, result.tiers().get(0).vanillaExp());
    }

    private static List<CollectionConfig.Category> parseCategories(String yaml, String kind) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return CollectionConfig.parseCategories(cfg.getConfigurationSection("categories." + kind), LOG, kind);
    }

    @Test
    void parsesCategoriesSortedByOrder() throws Exception {
        List<CollectionConfig.Category> categories = parseCategories("""
                categories:
                  items:
                    bosses:
                      display-name: "ボス素材"
                      order: 2
                      entries: ["core_ember", "core_frost"]
                    weapons:
                      display-name: "武器"
                      order: 1
                      entries: ["iron_blade"]
                """, "items");
        assertEquals(2, categories.size());
        assertEquals("weapons", categories.get(0).id());
        assertEquals(1, categories.get(0).order());
        assertEquals(List.of("iron_blade"), categories.get(0).entries());
        assertEquals("bosses", categories.get(1).id());
        assertEquals(List.of("core_ember", "core_frost"), categories.get(1).entries());
    }

    @Test
    void missingCategorySectionYieldsEmptyList() {
        assertTrue(CollectionConfig.parseCategories(null, LOG, "items").isEmpty());
    }

    @Test
    void categoryDisplayNameDefaultsToId() throws Exception {
        List<CollectionConfig.Category> categories = parseCategories("""
                categories:
                  mobs:
                    bosses:
                      order: 1
                """, "mobs");
        assertEquals("bosses", categories.get(0).displayName());
        assertTrue(categories.get(0).entries().isEmpty());
    }
}
