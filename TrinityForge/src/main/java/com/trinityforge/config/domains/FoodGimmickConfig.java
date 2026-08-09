package com.trinityforge.config.domains;

import com.trinityforge.config.PotionEffectTypes;
import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
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

    /** {@code custom:<id>} トークンの接頭辞。{@code RecipeIngredient}/{@code MobOverrideDropEntry}と
     *  同じ既存語彙(2026-07-27 ゴミ食のカスタムアイテム対応)。 */
    private static final String CUSTOM_PREFIX = "custom:";

    private static final double DEFAULT_JUNK_SATURATION_BONUS = 2.0;
    private static final double DEFAULT_NON_JUNK_SATURATION_PENALTY = 1.0;
    private static final double DEFAULT_SATIETY_BUFF_SATURATION_BONUS = 4.0;
    private static final int MIN_FOOD_LEVEL = 0;
    private static final int MAX_FOOD_LEVEL = 20;
    private static final boolean DEFAULT_UNREGISTERED_CUSTOM_FOOD_BAN_ENABLED = true;
    private static final String DEFAULT_UNREGISTERED_CUSTOM_FOOD_BAN_MESSAGE =
            "このアイテムは食料として登録されていません";
    /** ユーザー指示(2026-08-09)により明示除外: グロウベリー系はcustom-foods未登録でも常に食べられる。 */
    private static final Set<Material> DEFAULT_UNREGISTERED_CUSTOM_FOOD_BAN_EXCLUDED_MATERIALS =
            Set.of(Material.GLOW_BERRIES);

    /**
     * カスタム食料1件の上書き値: 食べた時に回復する満腹度({@code foodLevel}, 0-20)と隠し満腹度
     * ({@code saturation}, >=0)。REPLACE方式(ベースMaterialのバニラ栄養値は無視され、この値ちょうどに
     * 置き換わる)。消費側の適用ロジックは {@code FoodGimmickListener} が持つ。
     */
    public record CustomFood(int foodLevel, double saturation) {
    }

    private volatile Set<Material> junkFoodMaterials = Set.of();
    /** {@code custom:<id>} トークンで指定されたゴミ食のTFカタログ/ArsPaper ID集合(2026-07-27新設)。 */
    private volatile Set<String> junkFoodCatalogIds = Set.of();
    private volatile Set<PotionEffectType> junkfoodImmunityCancelledEffects = Set.of();
    private volatile double junkfoodInversionJunkSaturationBonus = DEFAULT_JUNK_SATURATION_BONUS;
    private volatile double junkfoodInversionNonJunkSaturationPenalty = DEFAULT_NON_JUNK_SATURATION_PENALTY;
    private volatile double satietyBuffSaturationBonus = DEFAULT_SATIETY_BUFF_SATURATION_BONUS;
    private volatile Map<String, CustomFood> customFoods = Map.of();
    /** 2026-08-09新設: custom-foods未登録のカスタムID付き食料を食べられなくするギミック。
     *  「custom-foodsに満腹度の設定があれば食料、無ければ素材」という規則そのものが判定基準であり、
     *  個別ID除外は持たない(ユーザー指示2026-08-09: 例外はハードコードのIDリストでなくcustom-foodsへの
     *  登録で表現する。tf_crystal_appleもcustom-foods側に登録して解決する)。 */
    private volatile boolean unregisteredCustomFoodBanEnabled = DEFAULT_UNREGISTERED_CUSTOM_FOOD_BAN_ENABLED;
    private volatile Set<Material> unregisteredCustomFoodBanExcludedMaterials = DEFAULT_UNREGISTERED_CUSTOM_FOOD_BAN_EXCLUDED_MATERIALS;
    private volatile String unregisteredCustomFoodBanMessage = DEFAULT_UNREGISTERED_CUSTOM_FOOD_BAN_MESSAGE;

    /** 「ゴミ食」と判定するMaterial一覧({@code junkfood-immunity}/{@code junkfood-inversion}で共有)。
     *  互換のためMaterial集合のみを返す({@code custom:}指定分は含まない) — カスタム品も含めた判定は
     *  {@link #isJunkFood(ItemStack)} を使うこと。 */
    public Set<Material> junkFoodMaterials() {
        return junkFoodMaterials;
    }

    /** {@code custom:<id>}で指定されたゴミ食のTFカタログ/ArsPaper ID一覧。 */
    public Set<String> junkFoodCatalogIds() {
        return junkFoodCatalogIds;
    }

    /**
     * {@code stack} が「ゴミ食」として設定されているか(2026-07-27新設、カスタム食料対応)。
     * {@link CrossPluginItemResolver#idOf(ItemStack)} でカスタムID(TFカタログ/ArsPaper)が読めれば
     * それを {@link #junkFoodCatalogIds()} と照合し、読めなければ(バニラ品)従来通り
     * {@link #junkFoodMaterials()} とMaterialを照合する。
     */
    public boolean isJunkFood(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        Optional<String> customId = CrossPluginItemResolver.idOf(stack);
        if (customId.isPresent()) {
            return junkFoodCatalogIds.contains(customId.get());
        }
        return junkFoodMaterials.contains(stack.getType());
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

    /** {@code unregistered-custom-food-ban.enabled}: 未登録カスタム食料の禁止ギミックが有効か。 */
    public boolean unregisteredCustomFoodBanEnabled() {
        return unregisteredCustomFoodBanEnabled;
    }

    /** キャンセル時にプレイヤーへ表示する理由文言({@code unregistered-custom-food-ban.message})。 */
    public String unregisteredCustomFoodBanMessage() {
        return unregisteredCustomFoodBanMessage;
    }

    /**
     * {@code stack} が「カタログ/materials定義のカスタムIDを持ち、かつ custom-foods に満腹度設定が
     * 無い」ため禁止対象(=食料でなく素材として扱う)か
     * (2026-08-09新設・2026-08-09補足反映: 圧縮食料等の81倍/729倍を素材のバニラ栄養値で食べられて
     * しまう事故対策。判定基準は「custom-foodsへの登録の有無」そのもので、個別ID除外は持たない
     * ——例外はハードコードのIDリストでなくcustom-foods側への登録で表現する方針)。
     *
     * <p>判定順序: (1) 機能が無効なら常にfalse。(2) ベースMaterialが
     * {@code unregistered-custom-food-ban.excluded-materials}(既定値: {@code GLOW_BERRIES})に
     * 載っていれば常にfalse(グロウベリー系はユーザー指示による明示除外)。(3)
     * {@link CrossPluginItemResolver#idOf(ItemStack)} でカスタムID(TFカタログ/ArsPaper materials)が
     * 読めなければ素のバニラ品なので常にfalse(禁止対象はカスタムID付きのみ)。(4) そのIDが
     * {@link #customFoods}(custom-foods)に登録済みならfalse(満腹度が設定済み=食料として扱う)。
     * (5) 残り(カスタムID付きだが未登録)は素材として扱い禁止対象。
     */
    public boolean isBannedUnregisteredCustomFood(ItemStack stack) {
        if (!unregisteredCustomFoodBanEnabled || stack == null) {
            return false;
        }
        if (unregisteredCustomFoodBanExcludedMaterials.contains(stack.getType())) {
            return false;
        }
        Optional<String> id = CrossPluginItemResolver.idOf(stack);
        if (id.isEmpty()) {
            return false;
        }
        return !customFoods.containsKey(id.get());
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

        JunkFoodTokens junkFoodTokens = parseJunkFoodTokens(yaml.getStringList("junk-food-materials"), log);
        this.junkFoodMaterials = junkFoodTokens.materials();
        this.junkFoodCatalogIds = junkFoodTokens.catalogIds();
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

        this.unregisteredCustomFoodBanEnabled = yaml.getBoolean(
                "unregistered-custom-food-ban.enabled", DEFAULT_UNREGISTERED_CUSTOM_FOOD_BAN_ENABLED);
        // excluded-materials未指定(キー自体が無い)時は既定値(GLOW_BERRIES)を使う。空リストを明示した
        // 場合は「除外なし」の意図として尊重する(getStringListはキー不在でも空リストを返すため、
        // 既定値注入にはcontains()での明示チェックが必要)。
        this.unregisteredCustomFoodBanExcludedMaterials = yaml.contains(
                "unregistered-custom-food-ban.excluded-materials")
                ? parseMaterials(yaml.getStringList("unregistered-custom-food-ban.excluded-materials"), log)
                : DEFAULT_UNREGISTERED_CUSTOM_FOOD_BAN_EXCLUDED_MATERIALS;
        String banMessage = yaml.getString("unregistered-custom-food-ban.message",
                DEFAULT_UNREGISTERED_CUSTOM_FOOD_BAN_MESSAGE);
        this.unregisteredCustomFoodBanMessage =
                (banMessage == null || banMessage.isBlank()) ? DEFAULT_UNREGISTERED_CUSTOM_FOOD_BAN_MESSAGE : banMessage;

        log.info("[" + PATH + "] loaded " + this.junkFoodMaterials.size() + " junk-food material(s), "
                + this.customFoods.size() + " custom-food(s) OK");
        return true;
    }

    /** {@code junk-food-materials} の1トークンをMaterial集合とカスタムID集合に振り分けた結果。 */
    private record JunkFoodTokens(Set<Material> materials, Set<String> catalogIds) {
    }

    /**
     * {@code junk-food-materials} の各トークンを解析する: {@code custom:<id>} なら TFカタログ/ArsPaper
     * のカスタムIDとして {@code catalogIds} へ、それ以外はバニラ {@link Material} 名として
     * {@code materials} へ振り分ける({@code RecipeIngredient}/{@code MobOverrideDropEntry} と同じ
     * {@code custom:} 接頭辞の語彙)。
     */
    private static JunkFoodTokens parseJunkFoodTokens(List<String> names, Logger log) {
        Set<Material> materials = new LinkedHashSet<>();
        Set<String> catalogIds = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String token = name.trim();
            if (token.regionMatches(true, 0, CUSTOM_PREFIX, 0, CUSTOM_PREFIX.length())) {
                String id = token.substring(CUSTOM_PREFIX.length()).trim();
                if (id.isEmpty()) {
                    log.warning("[" + PATH + "] '" + name + "' has a blank custom item id; skipped");
                    continue;
                }
                catalogIds.add(id);
                continue;
            }
            Material material = Material.matchMaterial(token);
            if (material == null) {
                log.warning("[" + PATH + "] '" + name + "' is not a valid Material; skipped");
                continue;
            }
            materials.add(material);
        }
        return new JunkFoodTokens(Set.copyOf(materials), Set.copyOf(catalogIds));
    }

    /** {@code unregistered-custom-food-ban.excluded-materials} 向けの単純なMaterial一覧パーサ。 */
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
