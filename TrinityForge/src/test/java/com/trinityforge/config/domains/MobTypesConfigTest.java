package com.trinityforge.config.domains;

import com.trinityforge.config.domains.MobTypesConfig.DefaultDefenseResult;
import com.trinityforge.config.domains.MobTypesConfig.DimensionOverridesResult;
import com.trinityforge.mobs.MobTypeDefinition;
import org.bukkit.Material;
import org.bukkit.World;
import com.trinityforge.config.domains.MobTypesConfig.ParseResult;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobTypesConfigTest {

    private static final Logger LOG = Logger.getLogger("MobTypesConfigTest");
    private static final double DELTA = 1.0e-9;

    private static ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return MobTypesConfig.parse(cfg.getConfigurationSection("mob-types"), LOG);
    }

    @Test
    void emptyMobTypesYieldsNone() throws Exception {
        ParseResult r = parse("mob-types: {}\n");
        assertEquals(0, r.skipped());
        assertTrue(r.definitions().isEmpty());
    }

    @Test
    void parsesFullDefinitionWithDrops() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 3
                    coordinate-coefficient: 0.5
                    max-health: 40
                    armor-strength: 2.0
                    physical: { defense-rate: 0.2, resistance: 0.1, damage-reduction: 0.0, flat-defense: 1.0 }
                    magical:  { defense-rate: 0.0, resistance: 0.0, damage-reduction: 0.0, flat-defense: 0.0 }
                    level-coefficients:
                      max-health: 2.0
                      armor-strength: 0.1
                      physical: { defense-rate: 0.01, flat-defense: 0.5 }
                      magical:  { resistance: 0.02 }
                    drops:
                      - { material: ROTTEN_FLESH, chance: 0.1, min: 1, max: 2 }
                      - { material: IRON_SWORD, chance: 0.05, min: 1, max: 1, quality: 3 }
                """);
        assertEquals(0, r.skipped());
        MobTypeDefinition def = r.definitions().get(EntityType.ZOMBIE);
        assertNotNull(def);
        assertEquals(3, def.level());
        assertEquals(0.5, def.coordinateCoefficient(), DELTA);
        assertEquals(40.0, def.maxHealth(), DELTA);
        assertEquals(2.0, def.physical().armorStrength(), DELTA);
        assertEquals(0.2, def.physical().defenseRate(), DELTA);
        assertEquals(2.0, def.levelCoefficients().maxHealth(), DELTA);
        assertEquals(0.1, def.levelCoefficients().armorStrength(), DELTA);
        assertEquals(0.01, def.levelCoefficients().physical().defenseRate(), DELTA);
        assertEquals(0.5, def.levelCoefficients().physical().flatDefense(), DELTA);
        assertEquals(0.02, def.levelCoefficients().magical().resistance(), DELTA);
        assertEquals(2, def.drops().size());
        assertEquals(Material.ROTTEN_FLESH, def.drops().get(0).material());
        assertEquals(3, def.drops().get(1).quality());
    }

    @Test
    void missingMaxHealthAndLevelCoefficientsDefaultSafely() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                """);
        MobTypeDefinition def = r.definitions().get(EntityType.ZOMBIE);
        assertNotNull(def);
        assertEquals(null, def.maxHealth());
        assertEquals(0.0, def.levelCoefficients().maxHealth(), DELTA);
        assertEquals(0.0, def.levelCoefficients().physical().defenseRate(), DELTA);
    }

    @Test
    void nonPositiveMaxHealthSkipsEntry() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                    max-health: 0
                  SKELETON:
                    level: 2
                    max-health: 20
                """);
        assertEquals(1, r.skipped());
        assertEquals(1, r.definitions().size());
        assertTrue(r.definitions().containsKey(EntityType.SKELETON));
        assertEquals(20.0, r.definitions().get(EntityType.SKELETON).maxHealth(), DELTA);
    }

    @Test
    void invalidEntityTypeKeyIsSkippedButOthersLoad() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  NOT_A_REAL_MOB:
                    level: 1
                  ZOMBIE:
                    level: 2
                """);
        assertEquals(1, r.skipped());
        assertEquals(1, r.definitions().size());
        assertTrue(r.definitions().containsKey(EntityType.ZOMBIE));
    }

    @Test
    void invalidDropMaterialIsSkippedButEntryStillLoads() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                    drops:
                      - { material: NOT_A_REAL_MATERIAL, chance: 0.1, min: 1, max: 1 }
                      - { material: ROTTEN_FLESH, chance: 0.1, min: 1, max: 1 }
                """);
        // The invalid-material drop is skipped AND counted (bad drop config must not report a
        // clean load), but the ZOMBIE entry itself still loads with the one valid drop.
        assertEquals(1, r.skipped());
        MobTypeDefinition def = r.definitions().get(EntityType.ZOMBIE);
        assertNotNull(def);
        assertEquals(1, def.drops().size());
        assertEquals(Material.ROTTEN_FLESH, def.drops().get(0).material());
    }

    @Test
    void dropMissingRequiredFieldIsSkippedAndCounted() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                    drops:
                      - { material: ROTTEN_FLESH, chance: 0.1, min: 1 }
                      - { material: IRON_SWORD, chance: 0.1, min: 1, max: 1 }
                """);
        assertEquals(1, r.skipped());
        MobTypeDefinition def = r.definitions().get(EntityType.ZOMBIE);
        assertNotNull(def);
        assertEquals(1, def.drops().size());
        assertEquals(Material.IRON_SWORD, def.drops().get(0).material());
    }

    @Test
    void dropWithNonNumericChanceIsSkippedAndCounted() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                    drops:
                      - { material: ROTTEN_FLESH, chance: "not-a-number", min: 1, max: 1 }
                """);
        assertEquals(1, r.skipped());
        MobTypeDefinition def = r.definitions().get(EntityType.ZOMBIE);
        assertNotNull(def);
        assertTrue(def.drops().isEmpty());
    }

    // --- 2026-08-01 U13: drops[].material の custom:<id> (Material と排他) ---

    @Test
    void customDropTokenBecomesCatalogIdWithoutTouchingMaterial() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                    drops:
                      - { material: "custom:tf_scrap", chance: 0.25, min: 1, max: 3 }
                      - { material: ROTTEN_FLESH, chance: 0.1, min: 1, max: 1 }
                """);
        assertEquals(0, r.skipped());
        MobTypeDefinition def = r.definitions().get(EntityType.ZOMBIE);
        assertNotNull(def);
        assertEquals(2, def.drops().size());
        assertTrue(def.drops().get(0).isCustom());
        assertEquals("tf_scrap", def.drops().get(0).catalogId());
        assertNull(def.drops().get(0).material());
        assertEquals(0.25, def.drops().get(0).chance(), DELTA);
        assertEquals(3, def.drops().get(0).max());
        assertEquals(Material.ROTTEN_FLESH, def.drops().get(1).material());
        assertNull(def.drops().get(1).catalogId());
    }

    @Test
    void customDropPrefixIsCaseInsensitiveAndTrimmed() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                    drops:
                      - { material: "  CUSTOM: tf_scrap  ", chance: 0.1, min: 1, max: 1 }
                """);
        assertEquals(0, r.skipped());
        MobTypeDefinition def = r.definitions().get(EntityType.ZOMBIE);
        assertNotNull(def);
        assertEquals("tf_scrap", def.drops().get(0).catalogId());
    }

    @Test
    void blankCustomDropIdIsSkippedAndCounted() throws Exception {
        // "custom:" だけの行を Material.valueOf へ落とすと "CUSTOM:" という別トークンとして
        // 扱われてしまうので、ここで明示的に弾いて skipped に数える。
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                    drops:
                      - { material: "custom:", chance: 0.1, min: 1, max: 1 }
                      - { material: BONE, chance: 0.1, min: 1, max: 1 }
                """);
        assertEquals(1, r.skipped());
        MobTypeDefinition def = r.definitions().get(EntityType.ZOMBIE);
        assertNotNull(def);
        assertEquals(1, def.drops().size());
        assertEquals(Material.BONE, def.drops().get(0).material());
    }

    @Test
    void missingDefenseSectionsDefaultToZero() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                """);
        MobTypeDefinition def = r.definitions().get(EntityType.ZOMBIE);
        assertNotNull(def);
        assertEquals(0.0, def.physical().defenseRate(), DELTA);
        assertEquals(0.0, def.coordinateCoefficient(), DELTA);
        assertTrue(def.drops().isEmpty());
    }

    @Test
    void defenseRateAboveOneRemainsRawUntilCombatClamp() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                    physical: { defense-rate: 5.0 }
                """);
        assertEquals(5.0, r.definitions().get(EntityType.ZOMBIE).physical().defenseRate(), DELTA);
    }

    @Test
    void signedAttackAndNeutralDamageModifierDefaultsArePreserved() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                    attack:
                      attack-power: -12
                      penetration: -0.5
                    level-coefficients:
                      attack:
                        attack-power: -2
                """);
        MobTypeDefinition def = r.definitions().get(EntityType.ZOMBIE);
        assertEquals(-12.0, def.attack().defaultDamage(), DELTA);
        assertEquals(-0.5, def.attack().penetration(), DELTA);
        assertEquals(1.0, def.attack().damageModifier(), DELTA);
        assertEquals(-2.0, def.levelCoefficients().attack().attackPower(), DELTA);
        assertEquals(0.0, def.levelCoefficients().attack().damageModifier(), DELTA);
    }

    @Test
    void missingGrowthKeysDefaultToLinearBackCompat() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                    level-coefficients:
                      max-health: 55
                """);
        MobTypeDefinition def = r.definitions().get(EntityType.ZOMBIE);
        assertNotNull(def);
        assertEquals(1.0, def.levelCoefficients().maxHealthGrowth(), DELTA);
        assertEquals(1.0, def.levelCoefficients().maxHealthGrowthInterval(), DELTA);
        assertEquals(55.0, def.levelCoefficients().maxHealth(), DELTA);
    }

    @Test
    void parsesMaxHealthGrowthKeys() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                    max-health: 380
                    level-coefficients:
                      max-health: 0
                      max-health-growth: 1.055
                      max-health-growth-interval: 1.0
                """);
        MobTypeDefinition def = r.definitions().get(EntityType.ZOMBIE);
        assertNotNull(def);
        assertEquals(1.055, def.levelCoefficients().maxHealthGrowth(), DELTA);
        assertEquals(1.0, def.levelCoefficients().maxHealthGrowthInterval(), DELTA);
    }

    @Test
    void missingAttackPowerGrowthKeysDefaultToLinearBackCompat() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                    level-coefficients:
                      attack:
                        attack-power: 0.6
                """);
        MobTypeDefinition def = r.definitions().get(EntityType.ZOMBIE);
        assertNotNull(def);
        assertEquals(1.0, def.levelCoefficients().attack().attackPowerGrowth(), DELTA);
        assertEquals(1.0, def.levelCoefficients().attack().attackPowerGrowthInterval(), DELTA);
        assertEquals(0.6, def.levelCoefficients().attack().attackPower(), DELTA);
    }

    @Test
    void parsesAttackPowerGrowthKeys() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: 1
                    attack:
                      attack-power: 5.5
                    level-coefficients:
                      attack:
                        attack-power: 0
                        attack-power-growth: 1.03
                        attack-power-growth-interval: 1.0
                """);
        MobTypeDefinition def = r.definitions().get(EntityType.ZOMBIE);
        assertNotNull(def);
        assertEquals(1.03, def.levelCoefficients().attack().attackPowerGrowth(), DELTA);
        assertEquals(1.0, def.levelCoefficients().attack().attackPowerGrowthInterval(), DELTA);
    }

    @Test
    void negativeLevelEntryIsSkipped() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE:
                    level: -1
                  SKELETON:
                    level: 2
                """);
        assertEquals(1, r.skipped());
        assertEquals(1, r.definitions().size());
        assertTrue(r.definitions().containsKey(EntityType.SKELETON));
    }

    @Test
    void nonSectionEntryIsSkippedButKeepsRest() throws Exception {
        ParseResult r = parse("""
                mob-types:
                  ZOMBIE: 5
                  SKELETON:
                    level: 2
                """);
        assertEquals(1, r.skipped());
        assertEquals(1, r.definitions().size());
        assertTrue(r.definitions().containsKey(EntityType.SKELETON));
    }

    @Test
    void parsesDefaultsSection() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                defaults:
                  max-health: 30
                  armor-strength: 3.0
                  physical: { defense-rate: 0.1, resistance: 0.2, damage-reduction: 0.3, flat-defense: 4.0 }
                  magical:  { defense-rate: 0.5, resistance: 0.0, damage-reduction: 0.0, flat-defense: 0.0 }
                  level-coefficients:
                    max-health: 1.5
                    physical: { flat-defense: 0.25 }
                """);
        DefaultDefenseResult defaults = MobTypesConfig.parseDefaults(cfg.getConfigurationSection("defaults"), LOG);
        assertEquals(0.1, defaults.physical().defenseRate(), DELTA);
        assertEquals(0.2, defaults.physical().resistance(), DELTA);
        assertEquals(3.0, defaults.physical().armorStrength(), DELTA);
        assertEquals(0.5, defaults.magical().defenseRate(), DELTA);
        assertEquals(30.0, defaults.maxHealth(), DELTA);
        assertEquals(1.5, defaults.levelCoefficients().maxHealth(), DELTA);
        assertEquals(0.25, defaults.levelCoefficients().physical().flatDefense(), DELTA);
        assertEquals(0, defaults.level());
        assertEquals(0.0, defaults.coordinateCoefficient(), DELTA);
    }

    @Test
    void parsesDefaultsLevelAndCoordinateCoefficient() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                defaults:
                  level: 5
                  coordinate-coefficient: 0.02
                  armor-strength: 0.0
                """);
        DefaultDefenseResult defaults = MobTypesConfig.parseDefaults(cfg.getConfigurationSection("defaults"), LOG);
        assertEquals(5, defaults.level());
        assertEquals(0.02, defaults.coordinateCoefficient(), DELTA);
    }

    @Test
    void parsesDefaultsFromStandaloneRootYaml() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                physical: { defense-rate: 0.15 }
                magical:  { flat-defense: 2.0 }
                armor-strength: 1.5
                """);
        DefaultDefenseResult defaults = MobTypesConfig.parseDefaultsFromRoot(cfg);
        assertEquals(0.15, defaults.physical().defenseRate(), DELTA);
        assertEquals(2.0, defaults.magical().flatDefense(), DELTA);
        assertEquals(1.5, defaults.physical().armorStrength(), DELTA);
    }

    @Test
    void missingDefaultsSectionYieldsZeroBaseline() {
        DefaultDefenseResult defaults = MobTypesConfig.parseDefaults(null, LOG);
        assertEquals(0.0, defaults.physical().defenseRate(), DELTA);
        assertEquals(0.0, defaults.magical().flatDefense(), DELTA);
        assertEquals(null, defaults.maxHealth());
        assertEquals(0.0, defaults.levelCoefficients().maxHealth(), DELTA);
        assertEquals(0, defaults.level());
        assertEquals(0.0, defaults.coordinateCoefficient(), DELTA);
    }

    // --- CMB-21: max-level (距離由来モブレベルの上限) ---

    @Test
    void missingMaxLevelDefaultsToOneHundred() {
        YamlConfiguration cfg = new YamlConfiguration();
        assertEquals(100, MobTypesConfig.parseMaxLevel(cfg, LOG));
    }

    @Test
    void nullRootDefaultsToOneHundred() {
        assertEquals(100, MobTypesConfig.parseMaxLevel(null, LOG));
    }

    @Test
    void explicitMaxLevelIsHonored() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("max-level: 250\n");
        assertEquals(250, MobTypesConfig.parseMaxLevel(cfg, LOG));
    }

    @Test
    void zeroOrNegativeMaxLevelFallsBackToDefault() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("max-level: -5\n");
        assertEquals(100, MobTypesConfig.parseMaxLevel(cfg, LOG));

        YamlConfiguration zeroCfg = new YamlConfiguration();
        zeroCfg.loadFromString("max-level: 0\n");
        assertEquals(100, MobTypesConfig.parseMaxLevel(zeroCfg, LOG));
    }

    // --- 2026-08-02: dimensions:(ディメンション別の基準レベル下駄) ---

    @Test
    void missingDimensionsSectionYieldsEmptyOverrides() {
        // セクション自体が無い(=既存configの大多数)場合、baseLevel/coordinate-coefficientとも
        // 一切上書きされない(従来どおりの挙動と完全一致)であることを確認する。
        YamlConfiguration cfg = new YamlConfiguration();
        DimensionOverridesResult result = MobTypesConfig.parseDimensions(cfg, LOG);
        assertEquals(0, result.skipped());
        assertTrue(result.baseLevels().isEmpty());
        assertTrue(result.coordinateCoefficients().isEmpty());
    }

    @Test
    void emptyDimensionsSectionYieldsEmptyOverrides() throws Exception {
        // 出荷ymlが書く "dimensions: {}" の形。セクションは存在するが要素0件。
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("dimensions: {}\n");
        DimensionOverridesResult result = MobTypesConfig.parseDimensions(cfg, LOG);
        assertEquals(0, result.skipped());
        assertTrue(result.baseLevels().isEmpty());
        assertTrue(result.coordinateCoefficients().isEmpty());
    }

    @Test
    void parsesDimensionBaseLevelForNetherAndEnd() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                dimensions:
                  NETHER:
                    base-level: 20
                  THE_END:
                    base-level: 45
                """);
        DimensionOverridesResult result = MobTypesConfig.parseDimensions(cfg, LOG);
        assertEquals(0, result.skipped());
        assertEquals(20, result.baseLevels().get(World.Environment.NETHER));
        assertEquals(45, result.baseLevels().get(World.Environment.THE_END));
        // NORMAL は明示していないので未設定=このマップに一切現れない(呼び出し側で0扱い)。
        assertFalse(result.baseLevels().containsKey(World.Environment.NORMAL));
    }

    @Test
    void dimensionCoordinateCoefficientIsOnlyPresentWhenExplicitlySet() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                dimensions:
                  NETHER:
                    base-level: 0
                    coordinate-coefficient: 0.16
                  THE_END:
                    base-level: 10
                """);
        DimensionOverridesResult result = MobTypesConfig.parseDimensions(cfg, LOG);
        assertEquals(0.16, result.coordinateCoefficients().get(World.Environment.NETHER), DELTA);
        // THE_END は coordinate-coefficient を書いていないので、上書きマップには現れない
        // (呼び出し側はモブ定義側の係数をそのまま使う=1/8換算などの自動補正は一切行わない)。
        assertFalse(result.coordinateCoefficients().containsKey(World.Environment.THE_END));
    }

    @Test
    void invalidDimensionEnvironmentKeyIsSkippedButOthersLoad() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                dimensions:
                  NOT_A_REAL_DIMENSION:
                    base-level: 99
                  NETHER:
                    base-level: 20
                """);
        DimensionOverridesResult result = MobTypesConfig.parseDimensions(cfg, LOG);
        assertEquals(1, result.skipped());
        assertEquals(20, result.baseLevels().get(World.Environment.NETHER));
        assertEquals(1, result.baseLevels().size());
    }

    @Test
    void negativeDimensionBaseLevelClampsToZero() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                dimensions:
                  NETHER:
                    base-level: -10
                """);
        DimensionOverridesResult result = MobTypesConfig.parseDimensions(cfg, LOG);
        assertEquals(0, result.skipped());
        assertEquals(0, result.baseLevels().get(World.Environment.NETHER));
    }

    @Test
    void dimensionAccessorsDefaultToUnsetBehaviorOnFreshConfig() {
        // load()を一度も呼んでいない(=フィールドが初期値のまま)インスタンスは、どのEnvironmentでも
        // baseLevel=0・coefficient上書きなし(従来どおり)を返す。これが「未設定=既存挙動と完全一致」
        // の直接の確認になる。
        MobTypesConfig config = new MobTypesConfig();
        assertEquals(0, config.dimensionBaseLevel(World.Environment.NETHER));
        assertEquals(0, config.dimensionBaseLevel(World.Environment.THE_END));
        assertEquals(0, config.dimensionBaseLevel(World.Environment.NORMAL));
        assertEquals(OptionalDouble.empty(), config.dimensionCoordinateCoefficient(World.Environment.NETHER));
    }

    @Test
    void dimensionBaseLevelAddsIntoEffectiveLevelComputation() throws Exception {
        // MobTypeSpawnListener が実際に行う計算(adjustedBaseLevel = level +
        // dimensionBaseLevel(environment) → MobLevelScaling.effectiveLevel)をここで再現し、
        // 「設定時=下駄が乗る」を末端の実効レベルまで確認する。
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                dimensions:
                  NETHER:
                    base-level: 20
                """);
        DimensionOverridesResult result = MobTypesConfig.parseDimensions(cfg, LOG);
        int mobBaseLevel = 5;
        int netherBaseLevel = result.baseLevels().getOrDefault(World.Environment.NETHER, 0);
        int overworldBaseLevel = result.baseLevels().getOrDefault(World.Environment.NORMAL, 0);
        // ネザー: 下駄20が乗ってから距離分(coordinate-coefficient 0.02, distance 100 -> +2)が足される。
        assertEquals(5 + 20 + 2,
                com.trinityforge.mobs.MobLevelScaling.effectiveLevel(mobBaseLevel + netherBaseLevel, 0.02, 100.0, 100));
        // オーバーワールド(NORMAL未設定): 従来どおり下駄0のまま。
        assertEquals(5 + 2,
                com.trinityforge.mobs.MobLevelScaling.effectiveLevel(mobBaseLevel + overworldBaseLevel, 0.02, 100.0, 100));
    }
}
