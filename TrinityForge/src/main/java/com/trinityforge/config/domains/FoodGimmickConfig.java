package com.trinityforge.config.domains;

import com.trinityforge.config.PotionEffectTypes;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code stats/food-gimmick.yml}: tuning for the農業ツリーA-α/β系flag/percent
 * dedicated-effect consumers that have no existing config home ({@code junkfood-immunity},
 * {@code junkfood-inversion}, {@code satiety-buff} — see the {@code dedicated-effects:} field on each node in {@code skilltree/*.yml}), plus
 * the {@code custom-foods} override table (満腹度/隠し満腹度の置き換え、圧縮食料アイテム向け)。
 * {@code no-food-consume-chance}の確率(%)は他のpercent系effectと同様、skilltree/farming.ymlのノード側
 * {@code dedicated-effects[].value}で持つため、このconfigには含まない。Same raw-YAML loader style as
 * {@link FarmingGimmickConfig}/{@link MiningGimmickConfig}。
 */
public final class FoodGimmickConfig {

    public static final String PATH = "stats/food-gimmick.yml";

    private static final double DEFAULT_JUNK_SATURATION_BONUS = 2.0;
    private static final double DEFAULT_NON_JUNK_SATURATION_PENALTY = 1.0;
    private static final double DEFAULT_SATIETY_BUFF_SATURATION_BONUS = 4.0;
    private static final int MIN_FOOD_LEVEL = 0;
    private static final int MAX_FOOD_LEVEL = 20;

    /**
     * カスタム食料1件の上書き値: 食べた時に回復する満腹度({@code foodLevel}, 0-20)と隠し満腹度
     * ({@code saturation}, >=0)。REPLACE方式(ベースMaterialのバニラ栄養値は無視され、この値ちょうどに
     * 置き換わる)。消費側の適用ロジックは {@code FoodGimmickListener} が持つ。
     */
    public record CustomFood(int foodLevel, double saturation) {
    }

    private volatile Set<Material> junkFoodMaterials = Set.of();
    private volatile Set<PotionEffectType> junkfoodImmunityCancelledEffects = Set.of();
    private volatile double junkfoodInversionJunkSaturationBonus = DEFAULT_JUNK_SATURATION_BONUS;
    private volatile double junkfoodInversionNonJunkSaturationPenalty = DEFAULT_NON_JUNK_SATURATION_PENALTY;
    private volatile double satietyBuffSaturationBonus = DEFAULT_SATIETY_BUFF_SATURATION_BONUS;
    private volatile Map<String, CustomFood> customFoods = Map.of();

    /** 「ゴミ食」と判定するMaterial一覧({@code junkfood-immunity}/{@code junkfood-inversion}で共有)。 */
    public Set<Material> junkFoodMaterials() {
        return junkFoodMaterials;
    }

    /** {@code junkfood-immunity} 有効時、ゴミ食後に打ち消すデバフ系ポーション効果の種類。 */
    public Set<PotionEffectType> junkfoodImmunityCancelledEffects() {
        return junkfoodImmunityCancelledEffects;
    }

    /** {@code junkfood-inversion}: ゴミ食を食べた際の隠し満腹度(saturation)加算量。 */
    public double junkfoodInversionJunkSaturationBonus() {
        return junkfoodInversionJunkSaturationBonus;
    }

    /** {@code junkfood-inversion}: 非ゴミ食を食べた際の隠し満腹度(saturation)減算量。 */
    public double junkfoodInversionNonJunkSaturationPenalty() {
        return junkfoodInversionNonJunkSaturationPenalty;
    }

    /** {@code satiety-buff}: 食事時に追加で加算する隠し満腹度(saturation)。 */
    public double satietyBuffSaturationBonus() {
        return satietyBuffSaturationBonus;
    }

    /** {@code custom-foods}: アイテムID(TFカタログ/Ars素材) -&gt; 満腹度/隠し満腹度の上書き値。 */
    public Map<String, CustomFood> customFoods() {
        return customFoods;
    }

    /** {@code id}に紐づくカスタム食料の上書き値(未設定なら空)。 */
    public Optional<CustomFood> customFood(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(customFoods.get(id));
    }

    /** Loads (or reloads) the config. Returns true when it parsed cleanly. */
    public boolean load(Plugin plugin) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), PATH);
        if (!file.exists()) {
            plugin.saveResource(PATH, false);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }

        this.junkFoodMaterials = parseMaterials(yaml.getStringList("junk-food-materials"), log);
        this.junkfoodImmunityCancelledEffects = parsePotionEffectTypes(
                yaml.getStringList("junkfood-immunity.cancelled-debuff-effects"), log);
        this.junkfoodInversionJunkSaturationBonus = clampNonNegativeDouble(
                yaml.getDouble("junkfood-inversion.junk-saturation-bonus", DEFAULT_JUNK_SATURATION_BONUS),
                "junkfood-inversion.junk-saturation-bonus", DEFAULT_JUNK_SATURATION_BONUS, log);
        this.junkfoodInversionNonJunkSaturationPenalty = clampNonNegativeDouble(
                yaml.getDouble("junkfood-inversion.non-junk-saturation-penalty", DEFAULT_NON_JUNK_SATURATION_PENALTY),
                "junkfood-inversion.non-junk-saturation-penalty", DEFAULT_NON_JUNK_SATURATION_PENALTY, log);
        this.satietyBuffSaturationBonus = clampNonNegativeDouble(
                yaml.getDouble("satiety-buff.saturation-bonus", DEFAULT_SATIETY_BUFF_SATURATION_BONUS),
                "satiety-buff.saturation-bonus", DEFAULT_SATIETY_BUFF_SATURATION_BONUS, log);
        this.customFoods = parseCustomFoods(yaml.getConfigurationSection("custom-foods"), log);

        log.info("[" + PATH + "] loaded " + this.junkFoodMaterials.size() + " junk-food material(s), "
                + this.customFoods.size() + " custom-food(s) OK");
        return true;
    }

    private static Set<Material> parseMaterials(List<String> names, Logger log) {
        Set<Material> parsed = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Material material = Material.matchMaterial(name.trim());
            if (material == null) {
                log.warning("[" + PATH + "] '" + name + "' is not a valid Material; skipped");
                continue;
            }
            parsed.add(material);
        }
        return Set.copyOf(parsed);
    }

    private static Set<PotionEffectType> parsePotionEffectTypes(List<String> names, Logger log) {
        Set<PotionEffectType> parsed = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            PotionEffectType type = PotionEffectTypes.resolve(name);
            if (type == null) {
                log.warning("[" + PATH + "] '" + name + "' is not a valid PotionEffectType; skipped");
                continue;
            }
            parsed.add(type);
        }
        return Set.copyOf(parsed);
    }

    private static Map<String, CustomFood> parseCustomFoods(ConfigurationSection section, Logger log) {
        Map<String, CustomFood> parsed = new LinkedHashMap<>();
        if (section == null) {
            return Map.of();
        }
        for (String id : section.getKeys(false)) {
            if (id == null || id.isBlank()) {
                log.warning("[" + PATH + "] 'custom-foods' has a blank item id entry; skipped");
                continue;
            }
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                log.warning("[" + PATH + "] 'custom-foods." + id + "' is not a mapping; skipped");
                continue;
            }
            int rawFoodLevel = entry.getInt("food-level", -1);
            if (rawFoodLevel < MIN_FOOD_LEVEL) {
                log.warning("[" + PATH + "] 'custom-foods." + id + ".food-level' is missing or negative; skipped");
                continue;
            }
            int foodLevel = rawFoodLevel;
            if (foodLevel > MAX_FOOD_LEVEL) {
                log.warning("[" + PATH + "] 'custom-foods." + id + ".food-level' must be <= " + MAX_FOOD_LEVEL
                        + " (was " + rawFoodLevel + "); clamped");
                foodLevel = MAX_FOOD_LEVEL;
            }
            double saturation = clampNonNegativeDouble(entry.getDouble("saturation", 0.0),
                    "custom-foods." + id + ".saturation", 0.0, log);
            parsed.put(id, new CustomFood(foodLevel, saturation));
        }
        return Map.copyOf(parsed);
    }

    /** Non-finite/negative guard for a saturation bonus/penalty value: falls back to {@code fallback}. */
    private static double clampNonNegativeDouble(double raw, String key, double fallback, Logger log) {
        if (!Double.isFinite(raw) || raw < 0.0) {
            log.warning("[" + PATH + "] '" + key + "' must be >= 0 (was " + raw + "); using default " + fallback);
            return fallback;
        }
        return raw;
    }
}
