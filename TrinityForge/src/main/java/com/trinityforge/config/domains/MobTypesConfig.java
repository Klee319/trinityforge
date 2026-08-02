package com.trinityforge.config.domains;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DamageType;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.config.LoadableConfig;
import com.trinityforge.mobs.MobDropEntry;
import com.trinityforge.mobs.MobLevelCoefficients;
import com.trinityforge.mobs.MobTypeDefinition;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code combat/mob-types.yml}: EntityType-keyed level/defense/coordinate-scaling/drop
 * definitions for VANILLA mobs, entirely independent of the EliteMobs-keyed {@code
 * combat/mob-profiles.yml} / {@link MobProfileConfig} system (that config is a style reference
 * only and is never read or written by this class). {@code MobTypeSpawnListener} stamps a
 * matching entry onto a spawning mob's PDC; {@code MobTypeDropListener} rolls its extra drops.
 *
 * <p>{@link #parse(ConfigurationSection, Logger)} is separated from {@link #load(Plugin)} so the
 * parse round-trips against hand-written YAML in unit tests without a {@code Plugin}.
 */
public final class MobTypesConfig implements LoadableConfig {

    public static final String PATH = "combat/mob-types.yml";
    private static final String ROOT = "mob-types";
    private static final String DEFAULTS = "defaults";
    private static final String ARMOR_STRENGTH = "armor-strength";
    private static final String MAX_HEALTH = "max-health";
    private static final String MAX_HEALTH_GROWTH = "max-health-growth";
    private static final String MAX_HEALTH_GROWTH_INTERVAL = "max-health-growth-interval";
    private static final String ATTACK_POWER_GROWTH = "attack-power-growth";
    private static final String ATTACK_POWER_GROWTH_INTERVAL = "attack-power-growth-interval";
    private static final String LEVEL_COEFFICIENTS = "level-coefficients";
    private static final String MAX_LEVEL = "max-level";
    /**
     * 2026-08-01 U13: drops[].material が受け付けるカスタムアイテムトークンの接頭辞。設定エディタは
     * カスタムアイテムの選択を必ず {@code custom:<id>} へ正規化する({@code public/js/util.js} の
     * materialInput)。共有定数は作らず各ドメインが自前で持つのが既存の流儀(MobOverridesConfig /
     * MobLevelTableConfig / RecipeIngredient なども同じ private 定数を持っている)。
     */
    private static final String CUSTOM_PREFIX = "custom:";
    /** CMB-21: mob-import.yml / mob-types.yml の成長式がLv100想定で設計されている(コメント「Lv100で
     * 約134.5」等)ため、既定の距離レベル上限もLv100に揃える。 */
    private static final int DEFAULT_MAX_LEVEL = 100;
    /**
     * 2026-08-02: ディメンション別の基準レベル下駄(NETHER/THE_END等)。ワールド名ではなく
     * {@link World.Environment} で引く — ネザー/エンドのワールド名は構成依存で一致しない上、
     * EliteMobsのインスタンスワールドは毎回名前が変わるため(forks-and-mobs.md 既知の罠)。
     */
    private static final String DIMENSIONS = "dimensions";
    private static final String BASE_LEVEL = "base-level";

    private volatile Map<EntityType, MobTypeDefinition> definitions = Map.of();
    /**
     * CMB-21: 距離由来の実効レベル({@link com.trinityforge.mobs.MobLevelScaling#effectiveLevel(int,
     * double, double, int)})の上限。上限が無いと遠距離スポーンでLv1000等に達し、貫通が1.0へ飽和して
     * 防御ステが全て無意味になる。{@code mob-types.yml} トップレベルの {@code max-level}
     * (未設定/不正値は既定{@value #DEFAULT_MAX_LEVEL})。
     */
    private volatile int maxLevel = DEFAULT_MAX_LEVEL;
    private volatile DefenseStats defaultPhysical = zeroDefaults();
    private volatile DefenseStats defaultMagical = zeroDefaults();
    private volatile Double defaultMaxHealth = null;
    private volatile int defaultLevel = 0;
    private volatile double defaultCoordinateCoefficient = 0.0;
    private volatile MobLevelCoefficients defaultLevelCoefficients = MobLevelCoefficients.ZERO;
    private volatile AttackStats defaultAttack = AttackStats.plain(0);
    /** {@code dimensions.<ENV>.base-level}。未設定のEnvironmentは0(=従来どおり無干渉)。 */
    private volatile Map<World.Environment, Integer> dimensionBaseLevels = Map.of();
    /** {@code dimensions.<ENV>.coordinate-coefficient}(明示設定時のみ値を持つ、上書き用)。 */
    private volatile Map<World.Environment, Double> dimensionCoordinateCoefficients = Map.of();

    public Optional<MobTypeDefinition> definition(EntityType type) {
        return Optional.ofNullable(definitions.get(type));
    }

    public Map<EntityType, MobTypeDefinition> all() {
        return definitions;
    }

    /** Default defender profile for mobs with no addon PDC profile (COMBAT_SYSTEM_SPEC section 6). */
    public DefenseStats defaultDefense(DamageType type) {
        return switch (type) {
            case PHYSICAL -> defaultPhysical;
            case MAGICAL -> defaultMagical;
            case TYPELESS -> DefenseStats.NONE;
        };
    }

    /**
     * Optional max-HP override for untagged (no mob-types / Elite profile) mobs. Empty keeps vanilla HP.
     */
    public OptionalDouble defaultMaxHealth() {
        return defaultMaxHealth == null ? OptionalDouble.empty() : OptionalDouble.of(defaultMaxHealth);
    }

    /** Base combat level for untagged mobs ({@code defaults.level}). */
    public int defaultLevel() {
        return defaultLevel;
    }

    /** Distance-based level scaling coefficient for untagged mobs ({@code defaults.coordinate-coefficient}). */
    public double defaultCoordinateCoefficient() {
        return defaultCoordinateCoefficient;
    }

    /** Level coefficients applied with untagged defaults at the stamped effective level. */
    public MobLevelCoefficients defaultLevelCoefficients() {
        return defaultLevelCoefficients;
    }

    /** Base attack stats for untagged mobs ({@code defaults.attack}). */
    public AttackStats defaultAttack() {
        return defaultAttack;
    }

    public String resourcePath() {
        return PATH;
    }

    /**
     * CMB-21: 距離由来モブレベルの上限(既定100)。{@link com.trinityforge.mobs.MobLevelScaling}が
     * effectiveLevelをこの値でクランプするために使う。
     */
    public int maxLevel() {
        return maxLevel;
    }

    /**
     * 2026-08-02: このディメンションの基準レベル下駄({@code dimensions.<ENV>.base-level}、
     * effectiveLevel計算前に個体のbase levelへ加算する)。未設定/未知のEnvironmentは0
     * (=従来どおりディメンションを一切考慮しない挙動と完全に一致)。
     */
    public int dimensionBaseLevel(World.Environment environment) {
        if (environment == null) {
            return 0;
        }
        return dimensionBaseLevels.getOrDefault(environment, 0);
    }

    /**
     * 2026-08-02: このディメンションの{@code coordinate-coefficient}上書き値
     * ({@code dimensions.<ENV>.coordinate-coefficient}、明示設定時のみ)。空なら呼び出し側は
     * モブ側(mob-types/defaults)の係数をそのまま使うこと — ネザーの距離は座標上オーバーワールド換算
     * 1/8になるが、この上書きが未設定な限り従来どおり「その場の距離を生のブロック数として」係数を掛ける
     * 挙動を維持する(据え置き。8倍換算を自動では行わない。必要なら運用側がこの値でネザーの係数を
     * 明示的に引き上げて補正する)。
     */
    public OptionalDouble dimensionCoordinateCoefficient(World.Environment environment) {
        if (environment == null) {
            return OptionalDouble.empty();
        }
        Double value = dimensionCoordinateCoefficients.get(environment);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    @Override
    public boolean load(Plugin plugin) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), PATH);
        if (!file.exists()) {
            plugin.saveResource(PATH, false);
        }

        // loadConfiguration(File) は構文エラーを握り潰して空configを返すため自前でload()する。
        // 構文エラー時は直前に成功ロード済みのdefinitions(初回失敗時はMap.of())を維持しfalseを返す。
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }
        ParseResult result = parse(yaml.getConfigurationSection(ROOT), log);
        this.definitions = result.definitions();
        ConfigurationSection defaultsSection = yaml.getConfigurationSection(DEFAULTS);
        DefaultDefenseResult defaults;
        if (defaultsSection != null) {
            defaults = parseDefaults(defaultsSection, log);
        } else {
            File fallback = new File(plugin.getDataFolder(), MobDefaultsConfig.PATH);
            if (fallback.exists()) {
                YamlConfiguration fallbackYaml = new YamlConfiguration();
                try {
                    fallbackYaml.load(fallback);
                    defaults = parseDefaultsFromRoot(fallbackYaml);
                    log.info("[" + PATH + "] loaded defender defaults from fallback " + MobDefaultsConfig.PATH);
                } catch (InvalidConfigurationException | IOException ex) {
                    log.log(Level.WARNING, "[" + PATH + "] fallback " + MobDefaultsConfig.PATH
                            + " could not be read; using zero defaults: " + ex.getMessage());
                    defaults = parseDefaults((ConfigurationSection) null, log);
                }
            } else {
                defaults = parseDefaults((ConfigurationSection) null, log);
            }
        }
        this.defaultPhysical = defaults.physical();
        this.defaultMagical = defaults.magical();
        this.defaultMaxHealth = defaults.maxHealth();
        this.defaultLevel = defaults.level();
        this.defaultCoordinateCoefficient = defaults.coordinateCoefficient();
        this.defaultLevelCoefficients = defaults.levelCoefficients();
        this.defaultAttack = defaults.attack();
        this.maxLevel = parseMaxLevel(yaml, log);

        DimensionOverridesResult dimensionOverrides = parseDimensions(yaml, log);
        this.dimensionBaseLevels = dimensionOverrides.baseLevels();
        this.dimensionCoordinateCoefficients = dimensionOverrides.coordinateCoefficients();

        // 2026-08-02(指摘13修正): 以前は mob-types(89エントリ)のskippedとdimensionsのskippedを
        // totalSkippedへ合算して1本のメッセージで出していたため、「dimensionsのキー1件のtypoが
        // mob-typesの読み込み失敗に見える」誤解を招いていた。原因の切り分けができるよう分けて出す。
        boolean ok = true;
        if (result.skipped() > 0) {
            log.warning("[" + PATH + "] loaded " + result.definitions().size() + " mob type(s), "
                    + result.skipped() + " skipped");
            ok = false;
        }
        if (dimensionOverrides.skipped() > 0) {
            log.warning("[" + PATH + "] dimensions: loaded " + dimensionOverrides.baseLevels().size()
                    + " dimension override(s), " + dimensionOverrides.skipped() + " skipped");
            ok = false;
        }
        if (ok) {
            log.info("[" + PATH + "] loaded " + result.definitions().size() + " mob type(s) OK");
        }
        return ok;
    }

    /**
     * 2026-08-02: トップレベル {@code dimensions:} セクションを解析する。キーは {@link
     * World.Environment} 名(NORMAL/NETHER/THE_END等、大小無視)。未知のEnvironment名は警告して
     * そのエントリだけスキップ(他のディメンションの読み込みは継続)。セクション自体が無い場合は
     * 空マップ(=全ディメンションで下駄0・係数上書きなし=従来どおりの挙動)を返す。
     */
    static DimensionOverridesResult parseDimensions(ConfigurationSection root, Logger log) {
        if (root == null) {
            return new DimensionOverridesResult(Map.of(), Map.of(), 0);
        }
        ConfigurationSection section = root.getConfigurationSection(DIMENSIONS);
        if (section == null) {
            return new DimensionOverridesResult(Map.of(), Map.of(), 0);
        }
        Map<World.Environment, Integer> baseLevels = new LinkedHashMap<>();
        Map<World.Environment, Double> coefficients = new LinkedHashMap<>();
        int skipped = 0;
        for (String key : section.getKeys(false)) {
            World.Environment environment;
            try {
                environment = World.Environment.valueOf(key.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                log.warning("[" + PATH + "] dimensions key '" + key
                        + "' is not a valid World.Environment (NORMAL/NETHER/THE_END/CUSTOM); skipped");
                skipped++;
                continue;
            }
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                log.warning("[" + PATH + "] dimensions entry '" + key + "' is not a section; skipped");
                skipped++;
                continue;
            }
            int baseLevel = Math.max(0, entry.getInt(BASE_LEVEL, 0));
            baseLevels.put(environment, baseLevel);
            if (entry.isSet("coordinate-coefficient")) {
                double coefficient = entry.getDouble("coordinate-coefficient", 0.0);
                if (Double.isFinite(coefficient)) {
                    coefficients.put(environment, coefficient);
                } else {
                    log.warning("[" + PATH + "] dimensions." + key
                            + ".coordinate-coefficient must be finite; ignored (falling back to per-mob value)");
                    skipped++;
                }
            }
        }
        return new DimensionOverridesResult(Map.copyOf(baseLevels), Map.copyOf(coefficients), skipped);
    }

    /** Pure parse of the {@code mob-types:} section. Invalid entries are skipped, not fatal. */
    static ParseResult parse(ConfigurationSection root, Logger log) {
        Map<EntityType, MobTypeDefinition> parsed = new LinkedHashMap<>();
        int skipped = 0;
        if (root != null) {
            for (String key : root.getKeys(false)) {
                EntityType type;
                try {
                    type = EntityType.valueOf(key.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    log.warning("[" + PATH + "] mob-types key '" + key + "' is not a valid EntityType; skipped");
                    skipped++;
                    continue;
                }
                ConfigurationSection entry = root.getConfigurationSection(key);
                if (entry == null) {
                    log.warning("[" + PATH + "] mob-types entry '" + key + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                try {
                    int level = entry.getInt("level", 0);
                    double coordinateCoefficient = entry.getDouble("coordinate-coefficient", 0.0);
                    Double maxHealth = parseOptionalMaxHealth(entry);
                    double armorStrength = Math.max(0.0, entry.getDouble(ARMOR_STRENGTH, 0.0));
                    DefenseStats physical = defense(entry.getConfigurationSection("physical"), armorStrength);
                    DefenseStats magical = defense(entry.getConfigurationSection("magical"), armorStrength);
                    MobLevelCoefficients levelCoefficients = parseLevelCoefficients(
                            entry.getConfigurationSection(LEVEL_COEFFICIENTS));
                    AttackStats attack = parseAttack(entry.getConfigurationSection("attack"));
                    DropParseResult dropResult = parseDrops(entry.getMapList("drops"), key, log);
                    skipped += dropResult.skipped();
                    parsed.put(type, new MobTypeDefinition(type, level, coordinateCoefficient, maxHealth,
                            physical, magical, attack, levelCoefficients, dropResult.drops()));
                } catch (IllegalArgumentException ex) {
                    log.warning("[" + PATH + "] mob-types entry '" + key + "' invalid (" + ex.getMessage()
                            + "); skipped");
                    skipped++;
                }
            }
        }
        return new ParseResult(Map.copyOf(parsed), skipped);
    }

    /** Parses the top-level {@code defaults:} section; null section yields zero baseline stats. */
    static DefaultDefenseResult parseDefaults(ConfigurationSection defaults, Logger log) {
        if (defaults == null) {
            return new DefaultDefenseResult(zeroDefaults(), zeroDefaults(), null, 0, 0.0,
                    MobLevelCoefficients.ZERO, AttackStats.plain(0));
        }
        double armorStrength = Math.max(0.0, defaults.getDouble(ARMOR_STRENGTH, 0.0));
        Double maxHealth = null;
        try {
            maxHealth = parseOptionalMaxHealth(defaults);
        } catch (IllegalArgumentException ex) {
            if (log != null) {
                log.warning("[" + PATH + "] defaults.max-health invalid (" + ex.getMessage()
                        + "); treating as unset");
            }
        }
        int level = Math.max(0, defaults.getInt("level", 0));
        double coordinateCoefficient = defaults.getDouble("coordinate-coefficient", 0.0);
        if (!Double.isFinite(coordinateCoefficient)) {
            coordinateCoefficient = 0.0;
        }
        return new DefaultDefenseResult(
                defense(defaults.getConfigurationSection("physical"), armorStrength),
                defense(defaults.getConfigurationSection("magical"), armorStrength),
                maxHealth,
                level,
                coordinateCoefficient,
                parseLevelCoefficients(defaults.getConfigurationSection(LEVEL_COEFFICIENTS)),
                parseAttack(defaults.getConfigurationSection("attack")));
    }

    /**
     * CMB-21: トップレベル {@code max-level}(距離由来モブレベルの上限)。未設定/0以下/非数値は
     * 警告して既定値({@value #DEFAULT_MAX_LEVEL})へフォールバックする。
     */
    static int parseMaxLevel(ConfigurationSection root, Logger log) {
        if (root == null || !root.isSet(MAX_LEVEL)) {
            return DEFAULT_MAX_LEVEL;
        }
        int value = root.getInt(MAX_LEVEL, DEFAULT_MAX_LEVEL);
        if (value <= 0) {
            if (log != null) {
                log.warning("[" + PATH + "] max-level must be a positive integer (got " + value
                        + "); falling back to default " + DEFAULT_MAX_LEVEL);
            }
            return DEFAULT_MAX_LEVEL;
        }
        return value;
    }

    /** Parses defender defaults from a standalone yaml root (e.g. combat/mob-defaults.yml). */
    static DefaultDefenseResult parseDefaultsFromRoot(YamlConfiguration yaml) {
        double armorStrength = Math.max(0.0, yaml.getDouble(ARMOR_STRENGTH, 0.0));
        Double maxHealth = null;
        try {
            maxHealth = parseOptionalMaxHealth(yaml);
        } catch (IllegalArgumentException ignored) {
            maxHealth = null;
        }
        int level = Math.max(0, yaml.getInt("level", 0));
        double coordinateCoefficient = yaml.getDouble("coordinate-coefficient", 0.0);
        if (!Double.isFinite(coordinateCoefficient)) {
            coordinateCoefficient = 0.0;
        }
        return new DefaultDefenseResult(
                defense(yaml.getConfigurationSection("physical"), armorStrength),
                defense(yaml.getConfigurationSection("magical"), armorStrength),
                maxHealth,
                level,
                coordinateCoefficient,
                parseLevelCoefficients(yaml.getConfigurationSection(LEVEL_COEFFICIENTS)),
                parseAttack(yaml.getConfigurationSection("attack")));
    }

    private static Double parseOptionalMaxHealth(ConfigurationSection section) {
        if (section == null || !section.contains(MAX_HEALTH)) {
            return null;
        }
        double value = section.getDouble(MAX_HEALTH);
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException("max-health must be > 0 when set: " + value);
        }
        return value;
    }

    private static Double parseOptionalMaxHealth(YamlConfiguration yaml) {
        if (yaml == null || !yaml.contains(MAX_HEALTH)) {
            return null;
        }
        double value = yaml.getDouble(MAX_HEALTH);
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException("max-health must be > 0 when set: " + value);
        }
        return value;
    }

    static MobLevelCoefficients parseLevelCoefficients(ConfigurationSection section) {
        if (section == null) {
            return MobLevelCoefficients.ZERO;
        }
        return new MobLevelCoefficients(
                section.getDouble(MAX_HEALTH, 0.0),
                section.getDouble(ARMOR_STRENGTH, 0.0),
                parseDefenseCoeffs(section.getConfigurationSection("physical")),
                parseDefenseCoeffs(section.getConfigurationSection("magical")),
                parseAttackCoeffs(section.getConfigurationSection("attack")),
                // 省略時は growth=1.0/interval=1.0 = 従来どおりの線形(後方互換)。
                section.getDouble(MAX_HEALTH_GROWTH, 1.0),
                section.getDouble(MAX_HEALTH_GROWTH_INTERVAL, 1.0));
    }

    private static MobLevelCoefficients.AttackCoeffs parseAttackCoeffs(ConfigurationSection section) {
        if (section == null) {
            return MobLevelCoefficients.AttackCoeffs.ZERO;
        }
        return new MobLevelCoefficients.AttackCoeffs(
                section.getDouble("attack-power", 0.0),
                section.getDouble("flat-bonus-damage", 0.0),
                section.getDouble("percent-bonus-damage", 0.0),
                section.getDouble("penetration", 0.0),
                section.getDouble("crit-chance", 0.0),
                section.getDouble("crit-damage", 0.0),
                section.getDouble("damage-modifier", 0.0),
                section.getDouble("fixed-damage", 0.0),
                // 省略時は growth=1.0/interval=1.0 = 従来どおりの線形(後方互換)。
                section.getDouble(ATTACK_POWER_GROWTH, 1.0),
                section.getDouble(ATTACK_POWER_GROWTH_INTERVAL, 1.0));
    }

    static AttackStats parseAttack(ConfigurationSection section) {
        if (section == null) {
            return AttackStats.plain(0);
        }
        return new AttackStats(
                section.getDouble("attack-power", 0.0),
                section.getDouble("flat-bonus-damage", 0.0),
                section.getDouble("percent-bonus-damage", 0.0),
                section.getDouble("crit-chance", 0.0),
                section.getDouble("crit-damage", 0.0),
                section.getDouble("penetration", 0.0),
                section.getDouble("damage-modifier", 1.0),
                section.getDouble("fixed-damage", 0.0),
                // 2026-08-02: このモブの通常攻撃を魔法として解決する割合[0,1]。既定0.0=完全物理
                // (従来どおり)。AttackStatsのコンパクトコンストラクタが[0,1]へクランプする。
                section.getDouble("magic-ratio", 0.0));
    }

    private static MobLevelCoefficients.DefenseCoeffs parseDefenseCoeffs(ConfigurationSection section) {
        if (section == null) {
            return MobLevelCoefficients.DefenseCoeffs.ZERO;
        }
        return new MobLevelCoefficients.DefenseCoeffs(
                section.getDouble("defense-rate", 0.0),
                section.getDouble("resistance", 0.0),
                section.getDouble("damage-reduction", 0.0),
                section.getDouble("flat-defense", 0.0));
    }

    private static DefenseStats zeroDefaults() {
        return new DefenseStats(0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /**
     * {@code material}, {@code chance}, {@code min}, {@code max} are all required on a drop entry.
     * A missing or invalid field skips that single drop (never the whole mob-types entry) AND
     * increments the returned skipped count, so a bad drop config is reflected in {@link
     * ParseResult#skipped()} instead of reporting a silently clean load.
     *
     * <p>{@code material} accepts a vanilla {@link Material} name OR a {@code custom:<id>} token
     * (2026-08-01 U13) — the same two-format rule {@code MobOverridesConfig#parseDrops} (drops[].item)
     * and {@code MobLevelTableConfig} (add-drops[].material) already implement. The prefix is stripped
     * HERE, locally, per the existing house style (each of the ten domains that accept it strips its
     * own); a blank id is skipped with a warning rather than being turned into a Material lookup.
     */
    private static DropParseResult parseDrops(List<Map<?, ?>> rawDrops, String ownerKey, Logger log) {
        List<MobDropEntry> drops = new ArrayList<>();
        int skipped = 0;
        for (Map<?, ?> raw : rawDrops) {
            Object materialRaw = raw.get("material");
            if (materialRaw == null) {
                log.warning("[" + PATH + "] '" + ownerKey + "' has a drop with no material; skipped");
                skipped++;
                continue;
            }
            String token = String.valueOf(materialRaw).trim();
            Material material = null;
            String catalogId = null;
            if (token.regionMatches(true, 0, CUSTOM_PREFIX, 0, CUSTOM_PREFIX.length())) {
                catalogId = token.substring(CUSTOM_PREFIX.length()).trim();
                if (catalogId.isEmpty()) {
                    log.warning("[" + PATH + "] '" + ownerKey + "' drop 'custom:' id must not be blank; skipped");
                    skipped++;
                    continue;
                }
            } else {
                try {
                    material = Material.valueOf(token.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    log.warning("[" + PATH + "] '" + ownerKey + "' drop material '" + materialRaw
                            + "' invalid; skipped");
                    skipped++;
                    continue;
                }
            }
            try {
                double chance = clamp01(requireDouble(raw, "chance", ownerKey, token));
                int min = requireInt(raw, "min", ownerKey, token);
                int max = requireInt(raw, "max", ownerKey, token);
                Integer quality = raw.get("quality") == null ? null : toInt(raw.get("quality"), 0);
                drops.add(catalogId != null
                        ? MobDropEntry.ofCatalog(catalogId, chance, min, max, quality)
                        : MobDropEntry.ofMaterial(material, chance, min, max, quality));
            } catch (IllegalArgumentException ex) {
                log.warning("[" + PATH + "] '" + ownerKey + "' drop for " + token + " invalid ("
                        + ex.getMessage() + "); skipped");
                skipped++;
            }
        }
        return new DropParseResult(List.copyOf(drops), skipped);
    }

    /** Required numeric field: throws (caught by the caller, which logs + counts it skipped) if absent/non-numeric. */
    private static double requireDouble(Map<?, ?> raw, String field, String ownerKey, String itemToken) {
        Object value = raw.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("'" + field + "' is required and must be numeric for "
                    + ownerKey + "/" + itemToken);
        }
        return number.doubleValue();
    }

    /** Required numeric field: throws (caught by the caller, which logs + counts it skipped) if absent/non-numeric. */
    private static int requireInt(Map<?, ?> raw, String field, String ownerKey, String itemToken) {
        Object value = raw.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("'" + field + "' is required and must be numeric for "
                    + ownerKey + "/" + itemToken);
        }
        return number.intValue();
    }

    private static int toInt(Object raw, int def) {
        return raw instanceof Number number ? number.intValue() : def;
    }

    private static DefenseStats defense(ConfigurationSection section, double armorStrength) {
        if (section == null) {
            return new DefenseStats(0.0, 0.0, 0.0, 0.0, armorStrength);
        }
        return new DefenseStats(
                section.getDouble("defense-rate", 0.0),
                section.getDouble("resistance", 0.0),
                section.getDouble("damage-reduction", 0.0),
                section.getDouble("flat-defense", 0.0),
                armorStrength);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Parse outcome: the immutable EntityType->definition map and how many entries were skipped. */
    record ParseResult(Map<EntityType, MobTypeDefinition> definitions, int skipped) {
    }

    /** Defender defaults parse outcome for the untagged-mob baseline profile. */
    record DefaultDefenseResult(DefenseStats physical, DefenseStats magical,
                                Double maxHealth, int level, double coordinateCoefficient,
                                MobLevelCoefficients levelCoefficients, AttackStats attack) {
    }

    /**
     * 2026-08-02: {@code dimensions:} セクションの解析結果。{@code baseLevels}/{@code
     * coordinateCoefficients} に無い {@link World.Environment} は「未設定=従来どおり無干渉」を意味する
     * (baseLevelは0、coefficientはモブ側の値をそのまま使う)。
     */
    record DimensionOverridesResult(Map<World.Environment, Integer> baseLevels,
                                    Map<World.Environment, Double> coordinateCoefficients,
                                    int skipped) {
    }

    /** Drop-list parse outcome: the immutable valid-drops list and how many drops were skipped. */
    private record DropParseResult(List<MobDropEntry> drops, int skipped) {
    }
}
