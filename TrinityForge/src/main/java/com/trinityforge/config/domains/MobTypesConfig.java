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
    /** 2026-08-03(45+難易度修正): {@code level-coefficients} の max-health 追加加速の開始レベルと傾き。 */
    private static final String MAX_HEALTH_HIGH_LEVEL_FROM = "max-health-high-level-from";
    private static final String MAX_HEALTH_HIGH_LEVEL_PER_LEVEL = "max-health-high-level-per-level";
    private static final String ATTACK_POWER_GROWTH = "attack-power-growth";
    private static final String ATTACK_POWER_GROWTH_INTERVAL = "attack-power-growth-interval";
    /**
     * 2026-08-03(要件#63の残り): {@code level-coefficients.attack} の attack-power 追加加算の開始レベルと
     * 傾き。{@code combat/mob-import.yml}(ダンジョン側)の
     * {@code attack.attack-power.high-level-from / high-level-per-level} と同じ意味・同じ出荷値。
     * 命名は同一ファイル内に既にある {@code max-health-high-level-from / -per-level} と同じ
     * 「{基準キー}-high-level-*」の平坦形に揃えた(mob-import.yml はランプを1つのマップへ畳む書式なので
     * 入れ子キー名が短いだけで、意味は完全に同一)。
     */
    private static final String ATTACK_POWER_HIGH_LEVEL_FROM = "attack-power-high-level-from";
    private static final String ATTACK_POWER_HIGH_LEVEL_PER_LEVEL = "attack-power-high-level-per-level";
    private static final String ATTACK = "attack";
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
    /** {@code summoned:}。既定は無効(=召喚モブも従来どおり距離ベース)。 */
    private volatile SummonedLevelPolicy summonedLevelPolicy = SummonedLevelPolicy.DISABLED;
    private volatile TamedLevelPolicy tamedLevelPolicy = TamedLevelPolicy.DISABLED;
    /** {@code mob-types.<TYPE>.level-coefficients.attack} の攻撃力 高レベル区間。未設定は {@link
     * AttackPowerHighLevelPhase#NONE}(=従来どおり無干渉)。 */
    private volatile Map<EntityType, AttackPowerHighLevelPhase> attackPowerHighLevels = Map.of();
    /** {@code defaults.level-coefficients.attack} の攻撃力 高レベル区間(未タグ付けモブ用)。 */
    private volatile AttackPowerHighLevelPhase defaultAttackPowerHighLevel = AttackPowerHighLevelPhase.NONE;

    /**
     * 2026-08-03(要件#63の残り): フィールドモブの攻撃力に対する「Lv{@code from} 以降だけ効く
     * 加算専用の第2区間」。{@code effective += perLevel * (level - from)}。
     *
     * <p>本来は {@link MobLevelCoefficients.AttackCoeffs} に max-health 側と同じ形で持たせるのが筋
     * (そうすれば {@code MobStatScaling#scaleAttack} を通る全経路が自動的に追随する)。今回は
     * {@code MobLevelCoefficients}/{@code MobStatScaling} が並行作業中の別レーン所有で編集できないため、
     * <b>mob-types 経路専用の値としてこのクラスが保持し、{@code MobTypeSpawnListener} が
     * {@code scaleAttack} の結果へ加算する</b>形にしている。所有権が戻ったら
     * {@code AttackCoeffs} へ畳み込むこと(やり残し)。
     *
     * <p>乗算ではなく加算なのは {@link com.trinityforge.mobs.ConversionPolicy.Ramp} の高レベル区間と
     * 同じ理由 — 基準カーブが0のステでも効かせられるため。{@code level == from} ちょうどでは 0 を足す
     * (境界で不連続にならない)。未設定は {@link #NONE} で、既存 config は 1 ミリも挙動が変わらない。
     */
    public record AttackPowerHighLevelPhase(double from, double perLevel) {

        /** 未設定 = 一切発動しない(従来どおり完全に無干渉)。 */
        public static final AttackPowerHighLevelPhase NONE =
                new AttackPowerHighLevelPhase(Double.POSITIVE_INFINITY, 0.0);

        public AttackPowerHighLevelPhase {
            // NaN / -Infinity は「全レベルで無限に足す」に化けるので、必ず「発動しない」側へ倒す。
            if (Double.isNaN(from) || from == Double.NEGATIVE_INFINITY) {
                from = Double.POSITIVE_INFINITY;
            }
            if (!Double.isFinite(perLevel)) {
                perLevel = 0.0;
            }
        }

        /** この区間が実際に何かを足しうるか(= 攻撃ステの刻印を発火させる価値があるか)。 */
        public boolean isActive() {
            return perLevel != 0.0 && Double.isFinite(from);
        }

        /** {@code level} で上乗せする攻撃力。{@code level < from} と未設定は必ず 0.0。 */
        public double bonusAt(int level) {
            int lvl = Math.max(0, level);
            if (perLevel == 0.0 || !Double.isFinite(from) || lvl < from) {
                return 0.0;
            }
            return perLevel * (lvl - from);
        }
    }

    public Optional<MobTypeDefinition> definition(EntityType type) {
        return Optional.ofNullable(definitions.get(type));
    }

    /**
     * 2026-08-03: この EntityType の攻撃力 高レベル区間({@code level-coefficients.attack.
     * attack-power-high-level-from / -per-level})。未設定/未知の型は {@link
     * AttackPowerHighLevelPhase#NONE}(= 従来どおり一切足さない)。
     */
    public AttackPowerHighLevelPhase attackPowerHighLevel(EntityType type) {
        if (type == null) {
            return AttackPowerHighLevelPhase.NONE;
        }
        return attackPowerHighLevels.getOrDefault(type, AttackPowerHighLevelPhase.NONE);
    }

    /** 未タグ付けモブ({@code defaults:})の攻撃力 高レベル区間。 */
    public AttackPowerHighLevelPhase defaultAttackPowerHighLevel() {
        return defaultAttackPowerHighLevel;
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

    /**
     * 召喚モブ({@code summoned:})のレベル決め。無効なら {@link SummonedLevelPolicy#DISABLED}。
     *
     * <p>召喚モブは「どこで召喚したか」ではなく「誰が召喚したか」で強さが決まるべき、というのが
     * この設定の主旨。既定の距離ベース({@code coordinate-coefficient})のままだと、
     * 拠点付近で召喚した使い魔は永久に Lv0 のままになる。
     */
    public SummonedLevelPolicy summonedLevelPolicy() {
        return summonedLevelPolicy;
    }

    /**
     * 召喚モブのレベルを召喚者のスキルレベルから決める規則({@code summoned:})。
     *
     * @param enabled            false なら召喚モブも従来どおり周囲のモブと同じ距離ベースで決まる
     * @param skill              参照するスキルid(例 {@code ARS_MAGIC})
     * @param levelPerSkillLevel 召喚者のスキルレベル1につき何レベル上げるか
     * @param baseLevel          スキルレベル0でも保証する下駄
     * @param maxLevel           上限(0以下なら {@link MobTypesConfig#maxLevel()} に従う)
     */
    public record SummonedLevelPolicy(boolean enabled, String skill, double levelPerSkillLevel,
                                      int baseLevel, int maxLevel) {

        public static final SummonedLevelPolicy DISABLED =
                new SummonedLevelPolicy(false, "", 0.0, 0, 0);

        /** 召喚者のスキルレベルから召喚モブのレベルを出す。{@code fallbackMax} は yml 未指定時の上限。 */
        public int levelFor(int skillLevel, int fallbackMax) {
            int raw = baseLevel + (int) Math.floor(Math.max(0, skillLevel) * levelPerSkillLevel);
            int ceiling = maxLevel > 0 ? maxLevel : fallbackMax;
            return Math.max(0, Math.min(raw, ceiling));
        }
    }

    static SummonedLevelPolicy parseSummoned(ConfigurationSection root, Logger log) {
        if (root == null) {
            return SummonedLevelPolicy.DISABLED;
        }
        if (!root.getBoolean("enabled", false)) {
            return SummonedLevelPolicy.DISABLED;
        }
        String skill = root.getString("skill", "").trim();
        if (skill.isEmpty()) {
            log.warning("[" + PATH + "] summoned.enabled=true なのに summoned.skill が空。"
                    + "召喚モブのレベル決めを無効のままにします");
            return SummonedLevelPolicy.DISABLED;
        }
        return new SummonedLevelPolicy(true, skill,
                root.getDouble("level-per-skill-level", 1.0),
                root.getInt("base-level", 0),
                root.getInt("max-level", 0));
    }

    /**
     * 手懐けた友好モブ({@code tamed:})のレベル決め。無効なら {@link TamedLevelPolicy#DISABLED}。
     *
     * <p>{@link SummonedLevelPolicy} と同じ形の下駄/係数/上限を持つが、参照するスキルが無い
     * ({@code skill} フィールド自体を持たない)。テイムには専用スキルが存在しないため、
     * 飼い主の総合戦闘レベル({@code progression/combat-level.yml})をそのまま使う設計。
     */
    public TamedLevelPolicy tamedLevelPolicy() {
        return tamedLevelPolicy;
    }

    /**
     * 手懐けモブのレベルを飼い主の総合戦闘レベルから決める規則({@code tamed:})。
     *
     * <p>{@link SummonedLevelPolicy} と違い参照スキルを持たない — 引数は「飼い主の総合戦闘レベル」
     * そのもの({@code combat/mob-types.yml} の {@code tamed:} セクションのコメント参照)。
     *
     * @param enabled            false なら手懐けモブも従来どおり(EntityTypeの通常値のまま)
     * @param levelPerSkillLevel 飼い主の総合戦闘レベル1につき何レベル上げるか
     * @param baseLevel          総合戦闘レベル0でも保証する下駄
     * @param maxLevel           上限(0以下なら {@link MobTypesConfig#maxLevel()} に従う)
     */
    public record TamedLevelPolicy(boolean enabled, double levelPerSkillLevel,
                                    int baseLevel, int maxLevel) {

        public static final TamedLevelPolicy DISABLED =
                new TamedLevelPolicy(false, 0.0, 0, 0);

        /** 飼い主の総合戦闘レベルから手懐けモブのレベルを出す。{@code fallbackMax} は yml 未指定時の上限。 */
        public int levelFor(int combatLevel, int fallbackMax) {
            int raw = baseLevel + (int) Math.floor(Math.max(0, combatLevel) * levelPerSkillLevel);
            int ceiling = maxLevel > 0 ? maxLevel : fallbackMax;
            return Math.max(0, Math.min(raw, ceiling));
        }
    }

    static TamedLevelPolicy parseTamed(ConfigurationSection root, Logger log) {
        if (root == null) {
            return TamedLevelPolicy.DISABLED;
        }
        if (!root.getBoolean("enabled", false)) {
            return TamedLevelPolicy.DISABLED;
        }
        return new TamedLevelPolicy(true,
                root.getDouble("level-per-skill-level", 1.0),
                root.getInt("base-level", 0),
                root.getInt("max-level", 0));
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
        this.attackPowerHighLevels = result.attackPowerHighLevels();
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
        this.defaultAttackPowerHighLevel = defaults.attackPowerHighLevel();
        this.maxLevel = parseMaxLevel(yaml, log);

        this.summonedLevelPolicy = parseSummoned(yaml.getConfigurationSection("summoned"), log);
        this.tamedLevelPolicy = parseTamed(yaml.getConfigurationSection("tamed"), log);

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

    /**
     * 2026-08-03: {@code level-coefficients.attack} から攻撃力の高レベル区間を読む。
     * セクションが無い/キーが無い場合は必ず {@link AttackPowerHighLevelPhase#NONE}
     * (= 従来どおり無干渉)。
     */
    static AttackPowerHighLevelPhase parseAttackPowerHighLevel(ConfigurationSection levelCoefficients) {
        if (levelCoefficients == null) {
            return AttackPowerHighLevelPhase.NONE;
        }
        ConfigurationSection attack = levelCoefficients.getConfigurationSection(ATTACK);
        if (attack == null) {
            return AttackPowerHighLevelPhase.NONE;
        }
        return new AttackPowerHighLevelPhase(
                attack.getDouble(ATTACK_POWER_HIGH_LEVEL_FROM, Double.POSITIVE_INFINITY),
                attack.getDouble(ATTACK_POWER_HIGH_LEVEL_PER_LEVEL, 0.0));
    }

    /** Pure parse of the {@code mob-types:} section. Invalid entries are skipped, not fatal. */
    static ParseResult parse(ConfigurationSection root, Logger log) {
        Map<EntityType, MobTypeDefinition> parsed = new LinkedHashMap<>();
        Map<EntityType, AttackPowerHighLevelPhase> attackHighLevels = new LinkedHashMap<>();
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
                    AttackStats attack = parseAttack(entry.getConfigurationSection(ATTACK));
                    DropParseResult dropResult = parseDrops(entry.getMapList("drops"), key, log);
                    skipped += dropResult.skipped();
                    parsed.put(type, new MobTypeDefinition(type, level, coordinateCoefficient, maxHealth,
                            physical, magical, attack, levelCoefficients, dropResult.drops()));
                    // definitions と同じ put の直後に置く: 別ループで拾い直すと「定義はスキップされたのに
                    // 高レベル区間だけ残る」ズレが起きうるため、必ず同じ成功パスで積む。
                    attackHighLevels.put(type, parseAttackPowerHighLevel(
                            entry.getConfigurationSection(LEVEL_COEFFICIENTS)));
                } catch (IllegalArgumentException ex) {
                    log.warning("[" + PATH + "] mob-types entry '" + key + "' invalid (" + ex.getMessage()
                            + "); skipped");
                    skipped++;
                }
            }
        }
        return new ParseResult(Map.copyOf(parsed), skipped, Map.copyOf(attackHighLevels));
    }

    /** Parses the top-level {@code defaults:} section; null section yields zero baseline stats. */
    static DefaultDefenseResult parseDefaults(ConfigurationSection defaults, Logger log) {
        if (defaults == null) {
            return new DefaultDefenseResult(zeroDefaults(), zeroDefaults(), null, 0, 0.0,
                    MobLevelCoefficients.ZERO, AttackStats.plain(0), AttackPowerHighLevelPhase.NONE);
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
                parseAttack(defaults.getConfigurationSection(ATTACK)),
                parseAttackPowerHighLevel(defaults.getConfigurationSection(LEVEL_COEFFICIENTS)));
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
                parseAttack(yaml.getConfigurationSection(ATTACK)),
                parseAttackPowerHighLevel(yaml.getConfigurationSection(LEVEL_COEFFICIENTS)));
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
                section.getDouble(MAX_HEALTH_GROWTH_INTERVAL, 1.0),
                // 省略時は「発動しない」(Double.POSITIVE_INFINITY)= 従来どおり(後方互換)。
                section.getDouble(MAX_HEALTH_HIGH_LEVEL_FROM, Double.POSITIVE_INFINITY),
                section.getDouble(MAX_HEALTH_HIGH_LEVEL_PER_LEVEL, 0.0));
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

    /**
     * Parse outcome: the immutable EntityType-&gt;definition map and how many entries were skipped.
     * {@code attackPowerHighLevels} (2026-08-03) carries the attack-power high-level phase that
     * {@link MobLevelCoefficients.AttackCoeffs} cannot hold yet — see {@link AttackPowerHighLevelPhase}.
     */
    record ParseResult(Map<EntityType, MobTypeDefinition> definitions, int skipped,
                       Map<EntityType, AttackPowerHighLevelPhase> attackPowerHighLevels) {
    }

    /** Defender defaults parse outcome for the untagged-mob baseline profile. */
    record DefaultDefenseResult(DefenseStats physical, DefenseStats magical,
                                Double maxHealth, int level, double coordinateCoefficient,
                                MobLevelCoefficients levelCoefficients, AttackStats attack,
                                AttackPowerHighLevelPhase attackPowerHighLevel) {
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
