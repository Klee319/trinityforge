package com.trinityforge.config.domains;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.config.domains.MobOverridesConfig.ParseResult;
import com.trinityforge.mobs.MobLevelCutoff;
import com.trinityforge.mobs.MobOverrideDropEntry;
import com.trinityforge.mobs.MobOverrideEntry;
import com.trinityforge.mobs.MobProfile;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MobOverridesConfig}: parse + priority resolution (world > default > base) + item-level merge
 * for stats + replace (not merge) semantics for drops + back-compat empty behavior.
 */
class MobOverridesConfigTest {

    private static final Logger LOG = Logger.getLogger("MobOverridesConfigTest");

    private static ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        // MobOverridesConfig#load と同じ pathSeparator に揃える(2026-07-26 M1 副次バグ対策) — 揃えないと
        // このヘルパー経由のテストだけ '.' を含むモブidキーが読み込み時点で誤って再分割されてしまう。
        cfg.options().pathSeparator(MobOverridesConfig.MOB_ID_SAFE_PATH_SEPARATOR);
        cfg.loadFromString(yaml);
        return MobOverridesConfig.parse(cfg, LOG);
    }

    private static MobOverridesConfig configOf(Map<String, Map<String, MobOverrideEntry>> scopes) throws Exception {
        MobOverridesConfig config = new MobOverridesConfig();
        java.lang.reflect.Field field = MobOverridesConfig.class.getDeclaredField("scopes");
        field.setAccessible(true);
        field.set(config, scopes);
        return config;
    }

    /** Full {@code load()} pipeline (unlike {@link #parse} + {@link #configOf}, this also wires up
     *  {@code scopeLevelCutoffs}, needed to exercise {@link MobOverridesConfig#levelCutoffFor}). */
    private static MobOverridesConfig loadedConfig(File dataFolder, String yaml) throws Exception {
        File file = new File(dataFolder, MobOverridesConfig.PATH);
        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
        java.nio.file.Files.writeString(file.toPath(), yaml);
        MobOverridesConfig config = new MobOverridesConfig();
        config.load(fakePlugin(dataFolder));
        return config;
    }

    private static org.bukkit.plugin.Plugin fakePlugin(File dataFolder) {
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> LOG;
            case "saveResource" -> null;
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(),
                new Class<?>[]{org.bukkit.plugin.Plugin.class}, handler);
    }

    private static MobProfile baseProfile() {
        DefenseStats physical = new DefenseStats(0.1, 0.1, 0.1, 5.0, 0.2);
        DefenseStats magical = new DefenseStats(0.1, 0.1, 0.1, 5.0, 0.2);
        AttackStats attack = AttackStats.plain(10.0);
        return new MobProfile("goblin_chief", 10, null, physical, magical, attack, 100.0, false);
    }

    // --- back-compat / empty config ---

    @Test
    void emptyOverridesFileIsFullyBackwardCompatible() throws Exception {
        ParseResult r = parse("overrides: {}\n");
        assertEquals(0, r.skipped());
        assertTrue(r.scopes().isEmpty());

        MobOverridesConfig config = configOf(r.scopes());
        MobProfile base = baseProfile();
        assertSame(base, config.resolve("any_world", "goblin_chief", base));
        assertTrue(config.dropsFor("any_world", "goblin_chief").isEmpty());
    }

    @Test
    void missingOverridesKeyYieldsEmptyScopes() throws Exception {
        ParseResult r = parse("");
        assertEquals(0, r.skipped());
        assertTrue(r.scopes().isEmpty());
    }

    // --- stats: item-level merge + priority ---

    @Test
    void defaultScopeOverridesOnlyConfiguredFields() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 500
                """);
        assertEquals(0, r.skipped());
        MobOverridesConfig config = configOf(r.scopes());
        MobProfile result = config.resolve("some_world", "goblin_chief", baseProfile());
        assertEquals(500.0, result.maxHealth());
        // Untouched fields keep the base profile's values.
        assertEquals(10, result.level());
        assertEquals(0.2, result.armorStrength());
        assertEquals(0.1, result.physical().defenseRate());
        assertEquals(10.0, result.attack().defaultDamage());
    }

    @Test
    void worldScopeWinsOverDefaultScopePerField() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 500
                          level: 20
                  my_dungeon:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 2000
                """);
        assertEquals(0, r.skipped());
        MobOverridesConfig config = configOf(r.scopes());
        MobProfile result = config.resolve("my_dungeon", "goblin_chief", baseProfile());
        // world scope's max-health wins...
        assertEquals(2000.0, result.maxHealth());
        // ...but level (unset in world scope) still cascades from the default scope.
        assertEquals(20, result.level());
    }

    @Test
    void unknownWorldFallsBackToDefaultScopeOnly() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 500
                  my_dungeon:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 2000
                """);
        MobOverridesConfig config = configOf(r.scopes());
        MobProfile result = config.resolve("some_other_world", "goblin_chief", baseProfile());
        assertEquals(500.0, result.maxHealth());
    }

    @Test
    void nullWorldNameOnlyAppliesDefaultScope() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 500
                  my_dungeon:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 2000
                """);
        MobOverridesConfig config = configOf(r.scopes());
        MobProfile result = config.resolve(null, "goblin_chief", baseProfile());
        assertEquals(500.0, result.maxHealth());
    }

    @Test
    void nestedPhysicalAndAttackFieldsMergeIndividually() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          physical:
                            defense-rate: 0.5
                          attack:
                            crit-chance: 0.9
                """);
        MobOverridesConfig config = configOf(r.scopes());
        MobProfile base = baseProfile();
        MobProfile result = config.resolve("w", "goblin_chief", base);
        assertEquals(0.5, result.physical().defenseRate());
        // untouched physical sub-fields keep base values
        assertEquals(base.physical().resistance(), result.physical().resistance());
        assertEquals(base.physical().flatDefense(), result.physical().flatDefense());
        // magical component untouched entirely
        assertEquals(base.magical(), result.magical());
        assertEquals(0.9, result.attack().critChance());
        assertEquals(base.attack().defaultDamage(), result.attack().defaultDamage());
    }

    @Test
    void armorStrengthOverrideAppliesToBothComponents() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          armor-strength: 0.75
                """);
        MobOverridesConfig config = configOf(r.scopes());
        MobProfile result = config.resolve("w", "goblin_chief", baseProfile());
        assertEquals(0.75, result.physical().armorStrength());
        assertEquals(0.75, result.magical().armorStrength());
        assertEquals(0.75, result.armorStrength());
    }

    @Test
    void unknownMobIdIsUntouched() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      some_other_mob:
                        stats:
                          max-health: 999
                """);
        MobOverridesConfig config = configOf(r.scopes());
        MobProfile base = baseProfile();
        assertSame(base, config.resolve("w", "goblin_chief", base));
    }

    // --- drops: replace, not merge ---

    @Test
    void worldDropsReplaceDefaultDropsEntirely() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: BONE, chance: 0.5, min: 1, max: 1 }
                  my_dungeon:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: DIAMOND, chance: 0.1, min: 1, max: 1 }
                          - { item: "custom:source_gem", chance: 0.05, min: 1, max: 2 }
                """);
        assertEquals(0, r.skipped());
        MobOverridesConfig config = configOf(r.scopes());
        List<MobOverrideDropEntry> drops = config.dropsFor("my_dungeon", "goblin_chief");
        assertEquals(2, drops.size());
        assertEquals(Material.DIAMOND, drops.get(0).material());
        assertTrue(drops.get(1).isCustom());
        assertEquals("source_gem", drops.get(1).catalogId());
    }

    @Test
    void emptyWorldDropsFallsBackToDefaultDrops() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: BONE, chance: 0.5, min: 1, max: 1 }
                  my_dungeon:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 500
                """);
        MobOverridesConfig config = configOf(r.scopes());
        List<MobOverrideDropEntry> drops = config.dropsFor("my_dungeon", "goblin_chief");
        assertEquals(1, drops.size());
        assertEquals(Material.BONE, drops.get(0).material());
    }

    // --- instanced dungeon world matching (2026-07-26: EliteMobs renames every instance `<blueprint>_<n>`) ---

    @Test
    void blueprintScopeMatchesEveryInstanceOfThatDungeon() throws Exception {
        ParseResult r = parse("""
                overrides:
                  em_the_shattered_gate:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 2000
                        drops:
                          - { item: BONE, chance: 1.0, min: 1, max: 1 }
                """);
        MobOverridesConfig config = configOf(r.scopes());
        MobProfile base = baseProfile();

        for (String instance : List.of("em_the_shattered_gate_1", "em_the_shattered_gate_2",
                "em_the_shattered_gate_137")) {
            assertEquals(2000.0, config.resolve(instance, "goblin_chief", base).maxHealth(), instance);
            assertEquals(1, config.dropsFor(instance, "goblin_chief").size(), instance);
        }
        // The blueprint world itself (used directly by non-instanced dungeons) still matches exactly.
        assertEquals(2000.0, config.resolve("em_the_shattered_gate", "goblin_chief", base).maxHealth());
    }

    @Test
    void unrelatedWorldsAreNotMatchedByABlueprintScope() throws Exception {
        ParseResult r = parse("""
                overrides:
                  em_gate:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 2000
                """);
        MobOverridesConfig config = configOf(r.scopes());
        MobProfile base = baseProfile();

        // Only a `_<digits>` suffix is an instance; anything else is a different world entirely.
        assertSame(base, config.resolve("em_gate_north", "goblin_chief", base));
        assertSame(base, config.resolve("em_gateway_1", "goblin_chief", base));
        assertSame(base, config.resolve("em_gate_", "goblin_chief", base));
        assertSame(base, config.resolve("em_gate_1_a", "goblin_chief", base));
    }

    @Test
    void exactWorldNameAndLongestBlueprintWin() throws Exception {
        ParseResult r = parse("""
                overrides:
                  em_gate:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 100
                  em_gate_deep:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 200
                  em_gate_deep_3:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 300
                """);
        MobOverridesConfig config = configOf(r.scopes());
        MobProfile base = baseProfile();

        assertEquals(300.0, config.resolve("em_gate_deep_3", "goblin_chief", base).maxHealth(),
                "an exact live-world-name key beats blueprint matching");
        assertEquals(200.0, config.resolve("em_gate_deep_4", "goblin_chief", base).maxHealth(),
                "the instance resolves to its own blueprint, not to the shorter key it starts with");
    }

    // --- stats: validation (2026-07-26 H3) ---

    @Test
    void negativeLevelIsSkippedNotFatal() throws Exception {
        // Before H3 this would build a MobStatOverride that, once applied via resolve(), made
        // MobProfile's compact constructor throw IllegalArgumentException("level must be >= 0") —
        // which TrinityForgeSpawnListener silently caught and skipped the ENTIRE stamp for. Now the
        // bad field alone is dropped (skipped-and-warned) and the rest of the entry still applies.
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          level: -5
                          max-health: 500
                """);
        assertEquals(1, r.skipped(), "the invalid level field must be counted as skipped");
        MobOverridesConfig config = configOf(r.scopes());
        MobProfile result = config.resolve("w", "goblin_chief", baseProfile());
        assertEquals(10, result.level(), "invalid level is ignored -> base profile's level survives");
        assertEquals(500.0, result.maxHealth(), "the OTHER valid field in the same stats: block still applies");
    }

    @Test
    void negativeMaxHealthIsSkippedNotFatal() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: -100
                          level: 30
                """);
        assertEquals(1, r.skipped());
        MobOverridesConfig config = configOf(r.scopes());
        MobProfile result = config.resolve("w", "goblin_chief", baseProfile());
        assertEquals(100.0, result.maxHealth(), "invalid max-health is ignored -> base profile's value survives");
        assertEquals(30, result.level(), "the OTHER valid field in the same stats: block still applies");
    }

    @Test
    void nonNumericStatsFieldIsSkippedNotSilentlyZero() throws Exception {
        // ConfigurationSection#getDouble silently returns 0.0 for a non-numeric value with no warning;
        // parseStats must not inherit that behavior for override fields (a bogus "flat-defense: high"
        // must never quietly become "flat-defense: 0.0").
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          physical:
                            flat-defense: "high"
                """);
        assertEquals(1, r.skipped());
        MobOverridesConfig config = configOf(r.scopes());
        MobProfile result = config.resolve("w", "goblin_chief", baseProfile());
        assertEquals(baseProfile().physical().flatDefense(), result.physical().flatDefense(),
                "an invalid field must be ignored (base value kept), never coerced to 0.0");
    }

    @Test
    void numericStringStatsFieldStillParses() throws Exception {
        // A quoted-but-genuinely-numeric YAML value ("1200") must still work — only truly non-numeric
        // values are rejected.
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: "1200"
                """);
        assertEquals(0, r.skipped());
        MobOverridesConfig config = configOf(r.scopes());
        MobProfile result = config.resolve("w", "goblin_chief", baseProfile());
        assertEquals(1200.0, result.maxHealth());
    }

    // --- id normalization (2026-07-26 M1) ---

    @Test
    void yamlSuffixedMobIdNormalizesToMatchTheStampedBareId() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief.yml:
                        stats:
                          max-health: 500
                """);
        assertEquals(0, r.skipped());
        MobOverridesConfig config = configOf(r.scopes());
        // The fork always stamps the extension-stripped id (TrinityForgeSpawnListener#resolveProfileId);
        // querying with the bare id must resolve against a ".yml"-suffixed authored key.
        MobProfile result = config.resolve("w", "goblin_chief", baseProfile());
        assertEquals(500.0, result.maxHealth());
    }

    // --- malformed structure (2026-07-26 M2) ---

    @Test
    void scopeMissingMobsSectionIsWarnedAndSkippedNotSilentOk() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    max-health: 500
                """);
        assertEquals(1, r.skipped(), "a scope with no 'mobs:' nesting must be counted as skipped, not silently OK");
        assertTrue(r.scopes().isEmpty());
    }

    @Test
    void invalidDropEntrySkippedNotFatal() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: NOT_A_REAL_MATERIAL, chance: 0.5, min: 1, max: 1 }
                          - { item: BONE, chance: 0.5, min: 1, max: 1 }
                """);
        assertEquals(1, r.skipped());
        MobOverridesConfig config = configOf(r.scopes());
        List<MobOverrideDropEntry> drops = config.dropsFor("w", "goblin_chief");
        assertEquals(1, drops.size());
        assertEquals(Material.BONE, drops.get(0).material());
    }

    // --- vanilla-exp (モブごとのレベル依存EXP式、2026-07-26) ---

    @Test
    void vanillaExpAbsentMeansNotConfiguredNeverZero() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 200
                """);
        MobOverridesConfig config = configOf(r.scopes());
        assertTrue(config.vanillaExpFor("w", "goblin_chief", 50).isEmpty(),
                "no vanilla-exp anywhere must read as 'leave the kill's EXP alone', not as 0 EXP");
        assertTrue(config.vanillaExpFor("w", "unknown_mob", 50).isEmpty());
    }

    @Test
    void vanillaExpScalarIsALevelIndependentAmount() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        vanilla-exp: 25
                """);
        MobOverridesConfig config = configOf(r.scopes());
        assertEquals(25, config.vanillaExpFor("w", "goblin_chief", 1).getAsInt());
        assertEquals(25, config.vanillaExpFor("w", "goblin_chief", 100).getAsInt());
    }

    @Test
    void vanillaExpRampFollowsLevel() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        vanilla-exp:
                          base: 5.0
                          per-level: 1.5
                          growth: 1.03
                          growth-interval: 1.0
                """);
        MobOverridesConfig config = configOf(r.scopes());
        // (5 + 1.5*L) * 1.03^L, rounded
        assertEquals(Math.round(6.5 * Math.pow(1.03, 1)), config.vanillaExpFor("w", "goblin_chief", 1).getAsInt());
        assertEquals(Math.round(155.0 * Math.pow(1.03, 100)),
                config.vanillaExpFor("w", "goblin_chief", 100).getAsInt());
    }

    @Test
    void vanillaExpLinearWhenGrowthOmitted() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        vanilla-exp: { base: 10.0, per-level: 2.0 }
                """);
        MobOverridesConfig config = configOf(r.scopes());
        assertEquals(30, config.vanillaExpFor("w", "goblin_chief", 10).getAsInt());
        assertEquals(210, config.vanillaExpFor("w", "goblin_chief", 100).getAsInt());
    }

    @Test
    void vanillaExpWorldScopeReplacesDefaultScope() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        vanilla-exp: { base: 10.0, per-level: 0.0 }
                  em_gate:
                    mobs:
                      goblin_chief:
                        vanilla-exp: { base: 0.0, per-level: 3.0 }
                """);
        MobOverridesConfig config = configOf(r.scopes());
        assertEquals(10, config.vanillaExpFor("other_world", "goblin_chief", 20).getAsInt());
        // 置換であってマージではない: 20*3 = 60 (default の base 10 は足されない)
        assertEquals(60, config.vanillaExpFor("em_gate_7", "goblin_chief", 20).getAsInt(),
                "the world scope's ramp replaces default's, and blueprint matching still applies");
    }

    @Test
    void vanillaExpNegativeResultClampsToZero() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        vanilla-exp: { base: -50.0, per-level: 1.0 }
                """);
        MobOverridesConfig config = configOf(r.scopes());
        assertEquals(0, config.vanillaExpFor("w", "goblin_chief", 10).getAsInt());
        assertEquals(50, config.vanillaExpFor("w", "goblin_chief", 100).getAsInt());
    }

    @Test
    void shippedYamlParsesCleanlyAndGivesEveryMobAnExpRamp() throws Exception {
        // 出荷している実ファイルのバイトをそのまま食わせる(手書きfixtureではなく本物を検証する)。
        // 2026-07-26 に396体へ vanilla-exp を一括投入したので、取りこぼしがあれば即検出できる。
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new java.io.File("src/main/resources/" + MobOverridesConfig.PATH));
        ParseResult r = MobOverridesConfig.parse(cfg, LOG);

        assertEquals(0, r.skipped(), "the shipped mob-overrides.yml must have no skipped entries");
        MobOverridesConfig config = configOf(r.scopes());
        int checked = 0;
        int defaultScopeMobs = 0;
        for (Map.Entry<String, Map<String, MobOverrideEntry>> scope : r.scopes().entrySet()) {
            if (scope.getKey().equals(MobOverridesConfig.DEFAULT_SCOPE)) {
                // 2026-07-31: default スコープに「バニラモブの特殊攻撃(abilities)」を入れたので、
                // ここには vanilla-exp を持たないエントリが正当に存在する。バニラモブの経験値は
                // バニラのままが正しく、ramp を書くと「オーバーワールドのゾンビのEXPを TF が上書きする」
                // ことになるため、意図的に書いていない(vanillaExpFor は empty を返し、
                // 呼び出し側はキルのEXPに触らない)。
                for (Map.Entry<String, MobOverrideEntry> mob : scope.getValue().entrySet()) {
                    assertFalse(mob.getValue().abilities().isEmpty(),
                            "default." + mob.getKey() + " は abilities を持つためだけに置いてあるはずだが空だった"
                            + "(vanilla-exp も無いので、このエントリは何もしていない)");
                    defaultScopeMobs++;
                }
                continue;
            }
            for (Map.Entry<String, MobOverrideEntry> mob : scope.getValue().entrySet()) {
                assertTrue(config.vanillaExpFor(scope.getKey(), mob.getKey(), 50).isPresent(),
                        scope.getKey() + "." + mob.getKey() + " has no vanilla-exp ramp");
                checked++;
            }
        }
        assertEquals(r.mobCount(), checked + defaultScopeMobs,
                "parse が数えたモブ数と、走査したモブ数(ダンジョン + default)が一致すること");
        assertTrue(checked >= 396, "expected the full imported dungeon roster, got " + checked);
    }

    @Test
    void vanillaExpInvalidShapeIsIgnoredNotFatal() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        vanilla-exp: "not a number"
                        stats:
                          max-health: 200
                """);
        MobOverridesConfig config = configOf(r.scopes());
        assertTrue(config.vanillaExpFor("w", "goblin_chief", 10).isEmpty());
        assertEquals(200.0, config.resolve("w", "goblin_chief", baseProfile()).maxHealth(),
                "a malformed vanilla-exp must not take the rest of the entry down with it");
    }

    // --- level-cutoff (2026-07-27 「レベル差による足きり」) ---

    @Test
    void mobLevelCutoffAbsentYieldsNone(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 200
                """);
        assertEquals(MobLevelCutoff.NONE, config.levelCutoffFor("w", "goblin_chief"));
    }

    @Test
    void mobLevelCutoffParsesBothBlocks(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        level-cutoff:
                          over-level:
                            threshold: 10
                            exp-rate: 0.25
                            drop-rate: -1
                          under-level:
                            item-threshold: 20
                """);
        MobLevelCutoff cutoff = config.levelCutoffFor("w", "goblin_chief");
        assertEquals(new MobLevelCutoff(10, 0.25, -1.0, 20), cutoff);
    }

    @Test
    void mobLevelCutoffWinsOverScopeLevelCutoffInTheSameScope(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    level-cutoff:
                      over-level:
                        threshold: 999
                    mobs:
                      goblin_chief:
                        level-cutoff:
                          over-level:
                            threshold: 5
                """);
        assertEquals(5, config.levelCutoffFor("w", "goblin_chief").overLevelThreshold().intValue(),
                "the mob-level block must win over the scope-level block");
    }

    @Test
    void scopeLevelCutoffAppliesWhenMobHasNone(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    level-cutoff:
                      over-level:
                        threshold: 7
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 200
                """);
        assertEquals(7, config.levelCutoffFor("w", "goblin_chief").overLevelThreshold().intValue());
    }

    @Test
    void worldScopeMobLevelCutoffWinsOverDefaultScopeMobLevelCutoff(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        level-cutoff:
                          over-level:
                            threshold: 999
                  my_dungeon:
                    mobs:
                      goblin_chief:
                        level-cutoff:
                          over-level:
                            threshold: 5
                """);
        assertEquals(5, config.levelCutoffFor("my_dungeon", "goblin_chief").overLevelThreshold().intValue(),
                "world scope mob-level cutoff beats default scope mob-level cutoff");
        assertEquals(999, config.levelCutoffFor("some_other_world", "goblin_chief").overLevelThreshold().intValue(),
                "an unrelated world falls back to the default scope's mob-level cutoff");
    }

    @Test
    void worldScopeScopeLevelCutoffWinsOverDefaultScopeMobLevelCutoff(@TempDir File dir) throws Exception {
        // Priority order per spec: world-scope's SCOPE-level block still beats default-scope's MOB-level
        // block (world always wins over default, regardless of mob-vs-scope granularity within each).
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        level-cutoff:
                          over-level:
                            threshold: 999
                  my_dungeon:
                    level-cutoff:
                      over-level:
                        threshold: 5
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 200
                """);
        assertEquals(5, config.levelCutoffFor("my_dungeon", "goblin_chief").overLevelThreshold().intValue());
    }

    @Test
    void unknownMobIdLevelCutoffFallsBackToScopeLevelCutoff(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    level-cutoff:
                      over-level:
                        threshold: 7
                    mobs:
                      some_other_mob:
                        level-cutoff:
                          over-level:
                            threshold: 999
                """);
        assertEquals(7, config.levelCutoffFor("w", "goblin_chief").overLevelThreshold().intValue());
    }

    // --- scope 直下の stats:(ダンジョン単位の既定ステータス、2026-08-03 #62) ---

    @Test
    void scopeLevelStatsApplyToAMobWithNoEntryOfItsOwn(@TempDir File dir) throws Exception {
        // この層の存在意義そのもの: mobs: に1件も書いていないモブに効くこと。効かないなら
        // 「ダンジョンのコンセプト」は396体へ1体ずつ書く以外に手段が無くなる。
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  my_dungeon:
                    stats:
                      magical:
                        resistance: 0.32
                      attack:
                        magic-ratio: 0.6
                    mobs:
                      some_other_mob:
                        stats:
                          max-health: 1
                """);
        MobProfile result = config.resolve("my_dungeon", "goblin_chief", baseProfile());
        assertEquals(0.32, result.magical().resistance());
        assertEquals(0.6, result.attack().magicRatio());
    }

    @Test
    void scopeLevelStatsOnlyTouchTheFieldsItSets(@TempDir File dir) throws Exception {
        // 機構としては守備ステも書ける(mobs: に無いモブには効く)。出荷ymlが magic-ratio しか
        // 書かないのは別の理由(下の scopeDefenseLosesToAPerMobEntry)で、ここは機構の確認。
        // Lv45以降の flat-defense ランプや HP を巻き添えに潰さないことが安全に配れる前提。
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  my_dungeon:
                    stats:
                      physical:
                        resistance: 0.05
                    mobs: {}
                """);
        MobProfile result = config.resolve("my_dungeon", "goblin_chief", baseProfile());
        assertEquals(0.05, result.physical().resistance());
        assertEquals(5.0, result.physical().flatDefense(), "flat-defense は据え置き");
        assertEquals(0.1, result.physical().defenseRate(), "defense-rate は据え置き");
        assertEquals(0.1, result.magical().resistance(), "magical 側は据え置き");
        assertEquals(100.0, result.maxHealth(), "max-health は据え置き");
    }

    @Test
    void scopeDefenseLosesToAPerMobEntry(@TempDir File dir) throws Exception {
        // 2026-08-03 の実害の再現: scope 直下に耐性を書いても、モブ個別が resistance を持っていれば
        // 【必ず】負ける(MobStatOverride.mergeDefense は項目単位マージで、モブ個別が後に適用される)。
        // 出荷ymlのダンジョンモブはほぼ全部が自前の resistance を持つため、scope 側の耐性は
        // 丸ごと no-op になっていた。この性質を明文化しておかないと同じ書き方を繰り返す。
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  my_dungeon:
                    stats:
                      physical:
                        resistance: 0.05
                      magical:
                        resistance: 0.32
                    mobs:
                      goblin_chief:
                        stats:
                          physical: { defense-rate: 0.374, resistance: 0.306 }
                          magical:  { defense-rate: 0.068, resistance: 0.204 }
                """);
        MobProfile result = config.resolve("my_dungeon", "goblin_chief", baseProfile());
        assertEquals(0.306, result.physical().resistance(), "モブ個別が scope の 0.05 を潰す");
        assertEquals(0.204, result.magical().resistance(), "モブ個別が scope の 0.32 を潰す");
        // 対照: 同じ scope でも mobs: に居ないモブには効く(= 効き方が2種類あるので余計に紛らわしい)。
        assertEquals(0.05, config.resolve("my_dungeon", "no_entry", baseProfile()).physical().resistance());
    }

    @Test
    void mobLevelStatsWinOverTheScopeLevelStatsFieldByField(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  my_dungeon:
                    stats:
                      magical:
                        resistance: 0.32
                      attack:
                        magic-ratio: 0.6
                    mobs:
                      goblin_chief:
                        stats:
                          attack:
                            magic-ratio: 0.0
                """);
        MobProfile result = config.resolve("my_dungeon", "goblin_chief", baseProfile());
        assertEquals(0.0, result.attack().magicRatio(), "モブ個別が勝つ");
        assertEquals(0.32, result.magical().resistance(),
                "モブ個別が書いていない項目は scope 直下の値が生きる(ブロック採用ではなく項目単位)");
    }

    @Test
    void worldScopeStatsWinOverDefaultScopeStats(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    stats:
                      attack:
                        magic-ratio: 0.3
                      physical:
                        resistance: 0.12
                    mobs: {}
                  my_dungeon:
                    stats:
                      attack:
                        magic-ratio: 0.6
                    mobs: {}
                """);
        MobProfile inDungeon = config.resolve("my_dungeon", "goblin_chief", baseProfile());
        assertEquals(0.6, inDungeon.attack().magicRatio(), "ワールドscopeが default scope に勝つ");
        assertEquals(0.12, inDungeon.physical().resistance(),
                "ワールドscopeが書いていない項目は default scope の値が生きる");
        MobProfile elsewhere = config.resolve("unrelated_world", "goblin_chief", baseProfile());
        assertEquals(0.3, elsewhere.attack().magicRatio(), "無関係のワールドは default scope だけ");
    }

    @Test
    void scopeLevelStatsMatchEveryInstanceOfABlueprintDungeon(@TempDir File dir) throws Exception {
        // インスタンスワールド名は入場のたびに _1 _2 _3 と変わる。設計図名で書いた stats: が
        // 全インスタンスに当たらないと、コンセプトは1インスタンス目にしか乗らない。
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  em_id_the_mines:
                    stats:
                      attack:
                        magic-ratio: 0.08
                    mobs: {}
                """);
        assertEquals(0.08, config.resolve("em_id_the_mines_7", "any_mob", baseProfile()).attack().magicRatio());
        assertEquals(0.08, config.resolve("em_id_the_mines", "any_mob", baseProfile()).attack().magicRatio());
    }

    @Test
    void scopeWithOnlyStatsAndNoMobsSectionIsValidNotSkipped(@TempDir File dir) throws Exception {
        // scope直下だけで完結する設定は正しい書き方なので警告もskippedもしない。
        // (mobs: も scope直下設定も無い scope は従来どおり書き間違いとして skipped のまま —
        //  scopeMissingMobsSectionIsWarnedAndSkippedNotSilentOk がそちらを守っている)
        ParseResult r = parse("""
                overrides:
                  my_dungeon:
                    stats:
                      attack:
                        magic-ratio: 0.6
                """);
        assertEquals(0, r.skipped());
        assertEquals(1, r.scopeStats().size());

        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  my_dungeon:
                    stats:
                      attack:
                        magic-ratio: 0.6
                """);
        assertEquals(0.6, config.resolve("my_dungeon", "goblin_chief", baseProfile()).attack().magicRatio(),
                "mobs: が1件も無い scope でも worldScopeKey が一致すること");
    }

    @Test
    void scopeLevelMagicRatioOutOfRangeIsSkippedNotFatal(@TempDir File dir) throws Exception {
        ParseResult r = parse("""
                overrides:
                  my_dungeon:
                    stats:
                      attack:
                        magic-ratio: 1.5
                      magical:
                        resistance: 0.32
                    mobs: {}
                """);
        assertEquals(1, r.skipped(), "範囲外の magic-ratio は skipped に計上される");
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  my_dungeon:
                    stats:
                      attack:
                        magic-ratio: 1.5
                      magical:
                        resistance: 0.32
                    mobs: {}
                """);
        MobProfile result = config.resolve("my_dungeon", "goblin_chief", baseProfile());
        assertEquals(0.0, result.attack().magicRatio(), "不正値は無視され、基底プロファイルの値のまま");
        assertEquals(0.32, result.magical().resistance(), "同じブロックの正しい項目は生きる");
    }

    @Test
    void shippedYamlAssignsAConceptToEveryDungeonExceptTheHub() throws Exception {
        // 出荷ファイルの実バイトで「28ダンジョン全部にコンセプトが乗っている」ことを担保する。
        // 新しいダンジョンを追加したのに割り当て漏れ、が起きたらここで落ちる。
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new java.io.File("src/main/resources/" + MobOverridesConfig.PATH));
        ParseResult r = MobOverridesConfig.parse(cfg, LOG);
        assertEquals(0, r.skipped());

        java.util.Set<String> withoutConcept = new java.util.LinkedHashSet<>(r.scopes().keySet());
        withoutConcept.removeAll(r.scopeStats().keySet());
        assertEquals(java.util.Set.of(MobOverridesConfig.DEFAULT_SCOPE, "em_adventurers_guild"), withoutConcept,
                "コンセプト未割り当てが許されるのは default と拠点(DPS計測ダミーがいるので耐性を乗せない)だけ");

        // 3種のコンセプトが実際に配られていること(全部同じ値なら差別化になっていない)。
        java.util.Set<Double> ratios = new java.util.TreeSet<>();
        for (com.trinityforge.mobs.MobStatOverride stats : r.scopeStats().values()) {
            assertTrue(stats.attack() != null && stats.attack().magicRatio() != null,
                    "コンセプトは必ず magic-ratio を持つ");
            // 【初版の実害の再発防止】scope 直下に physical/magical(耐性)を書くと、モブ個別の
            // resistance に【必ず負ける】(MobStatOverride.mergeDefense は項目単位マージ)。
            // 出荷ymlのダンジョンモブは 397/404 が自前の resistance を持つので丸ごと no-op になり、
            // しかも per-mob の実データと食い違う値を書けてしまう。守りは per-mob 側だけで表現する。
            assertTrue(stats.physical() == null && stats.magical() == null,
                    "scope 直下のコンセプトは守備ステを書かない(モブ個別に必ず負けるので no-op になる)");
            assertTrue(stats.maxHealth() == null && stats.level() == null,
                    "コンセプトは HP/レベルには触らない(45+ の難易度ランプを巻き添えにしないため)");
            ratios.add(stats.attack().magicRatio());
        }
        assertEquals(java.util.Set.of(0.10, 0.25, 0.45), ratios,
                "魔法推奨0.10 / 物魔両方0.25 / 物理推奨0.45 の3種がすべて使われていること");
        // 3種が偏りなく配られていること(どれかが1〜2個しかないなら実質2択に戻っている)。
        java.util.Map<Double, Integer> byRatio = new java.util.TreeMap<>();
        for (com.trinityforge.mobs.MobStatOverride stats : r.scopeStats().values()) {
            byRatio.merge(stats.attack().magicRatio(), 1, Integer::sum);
        }
        assertEquals(java.util.Map.of(0.10, 12, 0.25, 5, 0.45, 11), byRatio,
                "魔法推奨12 / 物魔両方5 / 物理推奨11。ここが動いたら ▼コンセプト との対応を見直すこと");
        // 0.45 上限のガード: バニラ系防具は magic-flat-defense がゼロなので、魔法成分は無対策の
        // プレイヤーにほぼ素通りする。ここを上げると「対策すれば無傷/しなければ即死」に振り切れる
        // (根拠は mob-overrides.yml ヘッダの【magic-ratio の上限を 0.45 に抑えている理由】)。
        assertTrue(ratios.stream().allMatch(v -> v <= 0.45),
                "magic-ratio は 0.45 を超えてはならない");
    }

    @Test
    void noLevelCutoffAnywhereYieldsNone(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 200
                """);
        assertEquals(MobLevelCutoff.NONE, config.levelCutoffFor("w", "goblin_chief"));
    }

    @Test
    void expRateOutOfRangeIsSkippedNotFatal(@TempDir File dir) throws Exception {
        File file = new File(dir, MobOverridesConfig.PATH);
        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
        java.nio.file.Files.writeString(file.toPath(), """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        level-cutoff:
                          over-level:
                            threshold: 10
                            exp-rate: 2.0
                            drop-rate: 0.5
                """);
        MobOverridesConfig config = new MobOverridesConfig();
        boolean loaded = config.load(fakePlugin(dir));
        assertFalse(loaded, "an out-of-range exp-rate must be counted as skipped");
        MobLevelCutoff cutoff = config.levelCutoffFor("w", "goblin_chief");
        assertEquals(10, cutoff.overLevelThreshold().intValue(),
                "the OTHER valid field in the same block still applies");
        assertEquals(null, cutoff.overLevelExpRate(), "the invalid exp-rate is dropped (ignored), not clamped");
        assertEquals(0.5, cutoff.overLevelDropRate().doubleValue());
    }

    @Test
    void negativeDropRateOtherThanMinusOneIsSkippedNotFatal(@TempDir File dir) throws Exception {
        File file = new File(dir, MobOverridesConfig.PATH);
        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
        java.nio.file.Files.writeString(file.toPath(), """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        level-cutoff:
                          over-level:
                            threshold: 10
                            drop-rate: -0.5
                """);
        MobOverridesConfig config = new MobOverridesConfig();
        boolean loaded = config.load(fakePlugin(dir));
        assertFalse(loaded, "-0.5 is neither -1 nor within [0,1] and must be skipped");
        assertEquals(null, config.levelCutoffFor("w", "goblin_chief").overLevelDropRate());
    }

    @Test
    void thresholdNegativeValueIsAcceptedNotWarned(@TempDir File dir) throws Exception {
        // Spec: "未設定/負値 = 無効" — negative threshold values are a legitimate way to author "disabled",
        // not a validation error, so they must NOT be counted as skipped.
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        level-cutoff:
                          over-level:
                            threshold: -1
                          under-level:
                            item-threshold: -1
                """);
        MobLevelCutoff cutoff = config.levelCutoffFor("w", "goblin_chief");
        assertEquals(-1, cutoff.overLevelThreshold().intValue());
        assertEquals(-1, cutoff.underLevelItemThreshold().intValue());
        assertFalse(cutoff.isOverLevelActive(999, 0));
        assertFalse(cutoff.isUnderLevelActive(0, 999));
    }

    @Test
    void nonNumericThresholdIsSkippedNotFatal(@TempDir File dir) throws Exception {
        File file = new File(dir, MobOverridesConfig.PATH);
        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
        java.nio.file.Files.writeString(file.toPath(), """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        level-cutoff:
                          over-level:
                            threshold: "high"
                """);
        MobOverridesConfig config = new MobOverridesConfig();
        boolean loaded = config.load(fakePlugin(dir));
        assertFalse(loaded);
        assertEquals(null, config.levelCutoffFor("w", "goblin_chief").overLevelThreshold());
    }
}
