package com.trinityforge.config.domains;

import com.trinityforge.stats.StatKeys;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重装備は部位・素材を問わず移動速度 −5%、回避率ゼロ、防御系は軽装との住み分けを崩さない範囲で
 * 約 10% 強化する(2026-08-29)。
 */
class ShippedHeavyArmorIdentityTest {

    private static final String HEAVY = "HEAVY_ARMOR";
    private static final String MOVE = StatKeys.canonical("move-speed");
    private static final String DODGE = StatKeys.canonical("dodge-chance");
    private static final double MOVE_DEBUFF = -0.05;

    @Test
    @DisplayName("HEAVY_ARMOR の全部位は fixed 移動速度が −5% で、回避率を持たない")
    void everyHeavyPieceHasUniformMoveDebuffAndNoDodge() throws IOException {
        ConfigurationSection items = loadItems();
        List<String> offenders = new ArrayList<>();
        int heavy = 0;
        for (String id : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(id);
            if (item == null || !HEAVY.equals(item.getString("use-skill"))) {
                continue;
            }
            heavy++;
            ConfigurationSection fixed = item.getConfigurationSection("fixed");
            assertNotNull(fixed, id + " に fixed が無い");
            double move = fixed.getDouble("move-speed", 0.0);
            if (Math.abs(move - MOVE_DEBUFF) > 1e-9) {
                offenders.add(id + " move-speed=" + move);
            }
            if (fixed.contains("dodge-chance")) {
                offenders.add(id + " fixed dodge-chance=" + fixed.get("dodge-chance"));
            }
            ConfigurationSection random = item.getConfigurationSection("random");
            if (random != null && random.contains("dodge-chance")) {
                offenders.add(id + " random dodge-chance");
            }
            if (random != null && random.contains("move-speed")) {
                offenders.add(id + " random move-speed (一律は fixed だけ)");
            }
            ConfigurationSection grants = item.getConfigurationSection("grant-chances");
            if (grants != null && grants.contains("dodge-chance")) {
                offenders.add(id + " grant-chances dodge-chance");
            }
        }
        assertTrue(heavy >= 28, "重装が " + heavy + " 件しか無い");
        assertEquals(List.of(), offenders);
        assertFalse(MOVE.isBlank());
        assertFalse(DODGE.isBlank());
    }

    private static ConfigurationSection loadItems() throws IOException {
        try (InputStream in = ShippedHeavyArmorIdentityTest.class.getClassLoader()
                .getResourceAsStream(ItemStatsConfig.PATH.replace('\\', '/'))) {
            assertNotNull(in, "出荷 item-stats.yml が無い");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            ConfigurationSection items = yaml.getConfigurationSection("items");
            assertNotNull(items);
            return items;
        }
    }
}
