package com.trinityforge.stats;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropTableConfigTest {

    private static final Logger LOG = Logger.getLogger("DropTableConfigTest");

    private static YamlConfiguration yaml(String content) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(content);
        } catch (org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new IllegalStateException(ex);
        }
        return yaml;
    }

    @Test
    void parsesValidCategoryWithTrigger() {
        YamlConfiguration config = yaml("""
                categories:
                  tier1:
                    display-name: "Tier1"
                    trigger-chance-percent: 5.0
                    entries:
                      - item: tf_gacha_ticket_1
                        weight: 10
                        amount: 1
                """);
        Map<String, DropTableConfig.Category> categories = DropTableConfig.parseCategories(
                config.getConfigurationSection("categories"), true, "test", LOG);
        assertEquals(1, categories.size());
        DropTableConfig.Category tier1 = categories.get("tier1");
        assertEquals("Tier1", tier1.displayName());
        assertEquals(5.0, tier1.triggerChancePercent(), 0.0);
        assertEquals(1, tier1.entries().size());
        assertEquals("tf_gacha_ticket_1", tier1.entries().get(0).item());
        assertEquals(10, tier1.entries().get(0).weight());
        assertEquals(1, tier1.entries().get(0).amount());
    }

    @Test
    void ignoresTriggerWhenNotRequired() {
        YamlConfiguration config = yaml("""
                categories:
                  treasure_vanilla:
                    display-name: "Treasure"
                    entries:
                      - item: NAME_TAG
                        weight: 1
                        amount: 1
                """);
        Map<String, DropTableConfig.Category> categories = DropTableConfig.parseCategories(
                config.getConfigurationSection("categories"), false, "test", LOG);
        assertEquals(0.0, categories.get("treasure_vanilla").triggerChancePercent(), 0.0);
    }

    @Test
    void skipsEntryWithBlankItem() {
        YamlConfiguration config = yaml("""
                categories:
                  tier1:
                    entries:
                      - item: ""
                        weight: 10
                      - item: valid_item
                        weight: 5
                """);
        Map<String, DropTableConfig.Category> categories = DropTableConfig.parseCategories(
                config.getConfigurationSection("categories"), true, "test", LOG);
        assertEquals(1, categories.get("tier1").entries().size());
        assertEquals("valid_item", categories.get("tier1").entries().get(0).item());
    }

    @Test
    void skipsEntryWithWeightBelowOne() {
        YamlConfiguration config = yaml("""
                categories:
                  tier1:
                    entries:
                      - item: zero_weight
                        weight: 0
                      - item: negative_weight
                        weight: -5
                      - item: valid_item
                        weight: 3
                """);
        Map<String, DropTableConfig.Category> categories = DropTableConfig.parseCategories(
                config.getConfigurationSection("categories"), true, "test", LOG);
        assertEquals(1, categories.get("tier1").entries().size());
        assertEquals("valid_item", categories.get("tier1").entries().get(0).item());
    }

    @Test
    void defaultsAmountToOneWhenAbsent() {
        YamlConfiguration config = yaml("""
                categories:
                  tier1:
                    entries:
                      - item: valid_item
                        weight: 3
                """);
        Map<String, DropTableConfig.Category> categories = DropTableConfig.parseCategories(
                config.getConfigurationSection("categories"), true, "test", LOG);
        assertEquals(1, categories.get("tier1").entries().get(0).amount());
    }

    @Test
    void categoryWithNoValidEntriesIsDropped() {
        YamlConfiguration config = yaml("""
                categories:
                  empty_after_filter:
                    entries:
                      - item: ""
                        weight: 10
                """);
        Map<String, DropTableConfig.Category> categories = DropTableConfig.parseCategories(
                config.getConfigurationSection("categories"), true, "test", LOG);
        assertTrue(categories.isEmpty());
    }

    @Test
    void absentSectionYieldsEmptyMap() {
        assertTrue(DropTableConfig.parseCategories(null, true, "test", LOG).isEmpty());
    }

    @Test
    void nonFiniteTriggerFallsBackToZero() {
        YamlConfiguration config = yaml("""
                categories:
                  tier1:
                    trigger-chance-percent: .nan
                    entries:
                      - item: x
                        weight: 1
                """);
        Map<String, DropTableConfig.Category> categories = DropTableConfig.parseCategories(
                config.getConfigurationSection("categories"), true, "test", LOG);
        assertEquals(0.0, categories.get("tier1").triggerChancePercent(), 0.0);
        assertFalse(categories.isEmpty());
    }
}
