package com.trinityforge.progression.catalog;

import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.core.XpCurve;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * 16-skill progression catalog. Runtime authority is {@code plugins/TrinityForge/skills/base};
 * classpath resources are used only to seed missing files and for classpath-only unit tests.
 */
public final class NativeSkillCatalog {

    private static final Logger LOG = Logger.getLogger(NativeSkillCatalog.class.getName());

    private static final int DEFAULT_MAX_LEVEL = 100;
    private static final String DEFAULT_FORMULA = "(%level% + 75 * 2^(%level%/7.6)) + 300";

    private volatile Map<String, SkillCatalogEntry> entries;

    private NativeSkillCatalog(Map<String, SkillCatalogEntry> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    /** Classpath-only load for unit tests. */
    public static NativeSkillCatalog load(ClassLoader classLoader) {
        Map<String, SkillCatalogEntry> result = new LinkedHashMap<>();
        for (String skillId : SkillId.ALL) {
            result.put(skillId, loadEntry(skillId, classLoader, null));
        }
        return new NativeSkillCatalog(result);
    }

    /**
     * Seeds missing data-folder YAMLs from the classpath, then loads the data-folder catalog.
     */
    public static NativeSkillCatalog loadDataFolder(File dataFolder, ClassLoader classLoader) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Objects.requireNonNull(classLoader, "classLoader");
        File baseDir = new File(dataFolder, "skills/base");
        seedDefaults(baseDir, classLoader);
        Map<String, SkillCatalogEntry> parsed = parseAll(baseDir, classLoader);
        return new NativeSkillCatalog(parsed);
    }

    /**
     * Re-parses all 16 data-folder files. Swaps the live snapshot only when every skill parses;
     * on failure the previous snapshot is retained.
     *
     * @return {@code true} when the swap succeeded
     */
    public boolean reload(File dataFolder, ClassLoader classLoader) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        File baseDir = new File(dataFolder, "skills/base");
        seedDefaults(baseDir, classLoader);
        try {
            Map<String, SkillCatalogEntry> parsed = parseAll(baseDir, classLoader);
            if (parsed.size() != SkillId.ALL.size()) {
                LOG.warning("[NativeSkillCatalog] reload aborted: expected "
                        + SkillId.ALL.size() + " skills, parsed " + parsed.size());
                return false;
            }
            String curveError = firstInvalidCurve(parsed);
            if (curveError != null) {
                LOG.warning("[NativeSkillCatalog] reload aborted; previous curve snapshot retained: "
                        + curveError);
                return false;
            }
            this.entries = Collections.unmodifiableMap(parsed);
            return true;
        } catch (RuntimeException ex) {
            LOG.warning("[NativeSkillCatalog] reload aborted: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Eagerly evaluates each entry's {@code exp_level_curve} at representative levels
     * (1, cap/2, cap) so a malformed formula is caught at reload time instead of the first
     * time a player earns EXP on that skill (which would otherwise drop the entire in-flight
     * EXP batch — see {@code NativeExperienceDispatcher}). {@link SkillCatalogEntry#curve()} is
     * a lazily-evaluated function, so parsing alone does not exercise the formula.
     *
     * @return a description of the first skill/formula that fails to evaluate, or {@code null}
     *         when every entry's curve evaluates cleanly
     */
    private static String firstInvalidCurve(Map<String, SkillCatalogEntry> parsed) {
        for (SkillCatalogEntry entry : parsed.values()) {
            int cap = Math.max(1, entry.maxLevel());
            int[] representativeLevels = {1, Math.max(1, cap / 2), cap};
            for (int level : representativeLevels) {
                try {
                    entry.curve().expRequiredAt(level);
                } catch (RuntimeException ex) {
                    return "skill " + entry.skillId() + " formula \"" + entry.formulaString()
                            + "\" failed to evaluate at level " + level + ": " + ex.getMessage();
                }
            }
        }
        return null;
    }

    public SkillCatalogEntry get(String skillId) {
        return entries.get(skillId);
    }

    public Map<String, SkillCatalogEntry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    private static Map<String, SkillCatalogEntry> parseAll(File baseDir, ClassLoader classLoader) {
        Map<String, SkillCatalogEntry> result = new LinkedHashMap<>();
        for (String skillId : SkillId.ALL) {
            File file = new File(baseDir, skillId.toLowerCase() + "_progression.yml");
            result.put(skillId, loadEntry(skillId, classLoader, file.exists() ? file : null));
        }
        return result;
    }

    private static void seedDefaults(File baseDir, ClassLoader classLoader) {
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            LOG.warning("[NativeSkillCatalog] could not create " + baseDir.getAbsolutePath());
            return;
        }
        for (String skillId : SkillId.ALL) {
            String name = skillId.toLowerCase() + "_progression.yml";
            File target = new File(baseDir, name);
            if (target.exists()) continue;
            String resource = "skills/base/" + name;
            try (InputStream in = classLoader.getResourceAsStream(resource)) {
                if (in == null) {
                    LOG.warning("[NativeSkillCatalog] missing classpath seed: " + resource);
                    continue;
                }
                try (OutputStream out = Files.newOutputStream(target.toPath())) {
                    in.transferTo(out);
                }
            } catch (IOException ex) {
                LOG.warning("[NativeSkillCatalog] failed to seed " + name + ": " + ex.getMessage());
            }
        }
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    private static SkillCatalogEntry loadEntry(String skillId, ClassLoader classLoader, File file) {
        String resourceName = "skills/base/" + skillId.toLowerCase() + "_progression.yml";
        int maxLevel = DEFAULT_MAX_LEVEL;
        String formula = DEFAULT_FORMULA;
        Map<String, Double> actionExp = new LinkedHashMap<>();
        Map<String, Double> rates = new LinkedHashMap<>();

        try (InputStream is = file != null
                ? new FileInputStream(file)
                : classLoader.getResourceAsStream(resourceName)) {
            if (is == null) {
                LOG.warning("[NativeSkillCatalog] Resource not found: " + resourceName
                        + "; using defaults for " + skillId);
            } else {
                Yaml yaml = new Yaml();
                Object root = yaml.load(is);
                if (root instanceof Map<?, ?> rootMap) {
                    Object expSection = rootMap.get("experience");
                    if (expSection instanceof Map<?, ?> expMap) {
                        Object maxLevelObj = expMap.get("max_level");
                        if (maxLevelObj instanceof Integer ml) {
                            maxLevel = ml;
                        } else if (maxLevelObj instanceof Number n) {
                            maxLevel = n.intValue();
                        }
                        Object curveObj = expMap.get("exp_level_curve");
                        if (curveObj instanceof String curve && !curve.isBlank()) {
                            formula = curve;
                        }
                        putScalarRate(rates, "fishing.catch", expMap.get("fishing_catch_exp"));
                        putScalarRate(rates, "alchemy.brew", expMap.get("alchemy_brew_exp"));
                        // POWER: experience.exp_gain as flat number = EXP granted per other-skill level-up
                        putScalarRate(rates, "power.exp_per_skill_level", expMap.get("exp_gain"));
                        // PRG-09 (2026-07-25): プレステージ周回ごとにそのスキルのレベルアップが生む
                        // POWER EXPを等比減衰させる係数。0.0 = 減衰なし(後方互換/旧挙動)、未指定時は
                        // NativeProgressionService#DEFAULT_PRESTIGE_POWER_DECAY_RATE(0.5)。
                        putScalarRate(rates, "power.prestige_decay_rate", expMap.get("prestige_decay_rate"));
                        // Valhalla armor-hit formula:
                        // exp_damage_piece * rawDamage * wornPieces
                        // * (1 + totalArmorPoints * exp_multiplier_point)
                        // * entityMultiplier * PvPMultiplier.
                        // Keep the two similarly named values under unambiguous runtime keys. The
                        // previous implementation accidentally treated exp_multiplier_point as a
                        // final-damage coefficient and exp_damage_piece as a flat per-hit addend.
                        putScalarRate(rates, "armor.exp_per_damage_piece",
                                expMap.get("exp_damage_piece"));
                        putScalarRate(rates, "armor.exp_armor_point_multiplier",
                                expMap.get("exp_multiplier_point"));
                        putScalarRate(rates, "armor.pvp_multiplier",
                                expMap.get("pvp_multiplier"));
                        putScalarRate(rates, "armor.pvp_multiplier_exponent",
                                expMap.get("pvp_multiplier_exponent"));
                        if (expMap.get("is_chunk_nerfed") instanceof Boolean enabled) {
                            rates.put("armor.location_diminishing_enabled", enabled ? 1.0 : 0.0);
                        }
                        // Exploit fix (semi-AFK armor-EXP farm): minimum final-damage threshold + per-
                        // (victim,attacker) cooldown for the piece-flat armor EXP grant. See
                        // skills/base/{light,heavy}_armor_progression.yml exp_damage_piece_* comments.
                        putScalarRate(rates, "armor.exp_damage_piece_min_damage",
                                expMap.get("exp_damage_piece_min_damage"));
                        putScalarRate(rates, "armor.exp_damage_piece_cooldown_seconds",
                                expMap.get("exp_damage_piece_cooldown_seconds"));
                        putScalarRate(rates, "alchemy.quality_mult",
                                expMap.get("exp_multiplier_quality"));
                        putScalarRate(rates, "alchemy.manual_mult",
                                expMap.get("multiplier_manual"));
                        putScalarRate(rates, "alchemy.auto_mult",
                                expMap.get("multiplier_automated"));
                        putScalarRate(rates, "mining.mine_mult",
                                expMap.get("exp_multiplier_mine"));
                        putScalarRate(rates, "mining.blast_mult",
                                expMap.get("exp_multiplier_blast"));
                        // N5(2026-07-31): 弓術の per-hit EXP 係数(archery.bow_base / crossbow_base /
                        // damage_bonus / distance_* / infinity_multiplier / spawner_multiplier /
                        // pvp_multiplier / max_health_limitation / entity.*)の変換をここから削除した。
                        // 弓術EXPは討伐時ベース(stats/skill-exp.yml の combat.kill-exp)へ統一され、
                        // これらを読む唯一の利用者(CombatListener の per-hit 式)が消えたため。
                        // 変換だけ残すと「editor から編集できるのに効かないキー」になる。
                        // ※防具の pvp_multiplier / entity_exp_multipliers は別キー(armor.pvp_multiplier /
                        //   actionExp の entity_exp_multipliers.*)で生きているので影響しない。
                        Object expGain = expMap.get("exp_gain");
                        if (expGain instanceof Map<?, ?> gainMap) {
                            putScalarRate(rates, "enchant.level_cost_multiplier",
                                    gainMap.get("experience_spent_conversion"));
                            // Valhalla's enchanting producer is not a flat "levels spent" award.
                            // Its editable exp_gain section contains four nested tables:
                            // enchantment base, enchantment-level multiplier, equipment-material
                            // multiplier, and equipment-kind multiplier. Preserve those nested paths
                            // in actionExp so the listener can evaluate the same data-driven formula
                            // without hard-coding any enchantment or tier values.
                            flattenNumericTables(actionExp, "exp_gain", gainMap);
                        }
                        for (Map.Entry<?, ?> action : expMap.entrySet()) {
                            if (!(action.getKey() instanceof String actionName)
                                    || !(action.getValue() instanceof Map<?, ?> values)) {
                                continue;
                            }
                            if ("exp_gain".equals(actionName)) {
                                continue;
                            }
                            for (Map.Entry<?, ?> value : values.entrySet()) {
                                if (value.getKey() instanceof String material
                                        && value.getValue() instanceof Number amount) {
                                    actionExp.put(actionName + "." + material,
                                            Math.max(0.0, amount.doubleValue()));
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.warning("[NativeSkillCatalog] Failed to read " + resourceName
                    + ": " + e.getMessage() + "; using defaults for " + skillId);
        }

        final String finalFormula = formula;
        XpCurve curve = level -> Math.max(1L, Math.round(FormulaParser.evaluate(finalFormula, level)));
        return new SkillCatalogEntry(skillId, maxLevel, formula, curve, actionExp, rates);
    }

    private static void putScalarRate(Map<String, Double> rates, String key, Object raw) {
        if (raw instanceof Number number) {
            rates.put(key, number.doubleValue());
        }
    }

    private static void flattenNumericTables(Map<String, Double> target, String path, Map<?, ?> source) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flattenNumericTables(target, path + "." + key, nested);
            } else if (value instanceof Number number) {
                target.put(path + "." + key, Math.max(0.0, number.doubleValue()));
            }
        }
    }
}
