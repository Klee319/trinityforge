package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code items/catalog.yml}: 革ベース装備の色が見分けられること(2026-08-19 / W-133 回帰)。
 *
 * <h2>なぜ必要か(実バグ)</h2>
 * 革装備は<b>ベース材質が全部 {@code LEATHER_*} で同じ</b>なので、区別できる手掛かりは
 * 染色の色だけ。ところが魔導/魔織/守護の3系統は<b>5段20点すべて同色</b>で、
 * 段を上げても見た目が1ミリも変わらなかった。さらにアクセサリ5点は {@code color:} 自体が無く
 * バニラの茶色のままで、これも互いに区別できなかった(実サーバ報告「白が複数ある」)。
 *
 * <p>ここで固定するのは2点:
 * <ol>
 *   <li>革ベースのカタログ品は<b>必ず色を持つ</b>(未指定＝バニラ茶色で全部同じになる)。</li>
 *   <li><b>別のセット</b>が同じ色を共有しない。1セット(兜/胴/脚/靴)の中で同色なのは
 *       意図どおりなので、部位の接尾辞を落とした「セット名」単位で突き合わせる。</li>
 * </ol>
 */
class ShippedLeatherArmorColorTest {

    private static final List<String> PIECE_SUFFIXES =
            List.of("_helmet", "_chestplate", "_leggings", "_boots");

    private static YamlConfiguration shipped() throws Exception {
        try (InputStream in = ShippedLeatherArmorColorTest.class.getClassLoader()
                .getResourceAsStream("items/catalog.yml")) {
            assertNotNull(in, "出荷 items/catalog.yml が classpath に無い");
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** カタログのエントリを持つセクション(catalog.yml のトップレベル構造に依存しないよう探す)。 */
    private static ConfigurationSection entriesOf(YamlConfiguration yaml) {
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            for (String id : section.getKeys(false)) {
                if (section.isConfigurationSection(id) && section.contains(id + ".material")) {
                    return section;
                }
            }
        }
        return yaml;
    }

    /** 部位の接尾辞を落とした「セット名」。接尾辞が無いものは id 自身が1点もののセット。 */
    private static String setNameOf(String id) {
        for (String suffix : PIECE_SUFFIXES) {
            if (id.endsWith(suffix)) {
                return id.substring(0, id.length() - suffix.length());
            }
        }
        return id;
    }

    private static Map<String, String> leatherEntries(ConfigurationSection entries) {
        Map<String, String> byId = new LinkedHashMap<>();
        for (String id : entries.getKeys(false)) {
            String material = entries.getString(id + ".material", "");
            if (material != null && material.toUpperCase(Locale.ROOT).startsWith("LEATHER_")) {
                byId.put(id, entries.getString(id + ".color"));
            }
        }
        return byId;
    }

    @Test
    void everyLeatherItemCarriesAColor() throws Exception {
        Map<String, String> leather = leatherEntries(entriesOf(shipped()));
        assertTrue(leather.size() >= 90,
                "革ベースのカタログ品が急に減っている(検査ごと無効化されていないか): " + leather.size());

        List<String> missing = new ArrayList<>();
        leather.forEach((id, color) -> {
            if (color == null || color.isBlank()) {
                missing.add(id);
            }
        });
        assertTrue(missing.isEmpty(),
                "color: の無い革装備はバニラの茶色のままで他と見分けがつかない: " + missing);
    }

    @Test
    void differentSetsNeverShareTheSameColor() throws Exception {
        Map<String, String> leather = leatherEntries(entriesOf(shipped()));

        // 色 -> その色を使っているセット名の集合
        Map<String, java.util.Set<String>> setsByColor = new TreeMap<>();
        leather.forEach((id, color) -> {
            if (color == null || color.isBlank()) {
                return;
            }
            setsByColor.computeIfAbsent(color.toUpperCase(Locale.ROOT), k -> new java.util.TreeSet<>())
                    .add(setNameOf(id));
        });

        List<String> collisions = new ArrayList<>();
        setsByColor.forEach((color, sets) -> {
            if (sets.size() > 1) {
                collisions.add(color + " -> " + sets);
            }
        });
        assertTrue(collisions.isEmpty(),
                "別々のセットが同じ革の色を使っている(装備してもどれか分からない): " + collisions);
    }
}
