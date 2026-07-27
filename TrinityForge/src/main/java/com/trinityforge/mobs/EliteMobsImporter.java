package com.trinityforge.mobs;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Bulk-converts a directory of EliteMobs custom-boss files into {@code combat/mob-profiles.yml}
 * (concern: convert the distributed dungeon mobs to this server's stats). Walks the source tree
 * recursively (content packs nest mob files under sub-folders), converts every file that looks like
 * an EliteMobs mob (has an {@code entityType}) via {@link EliteMobsMobMapping} under the configured
 * {@link ConversionPolicy}, and writes the result keyed by id (file name without extension).
 *
 * <p><strong>Merge, not overwrite:</strong> if the output file already exists, it is loaded first and
 * only the ids produced by this run replace their existing entry; every other id already in the file
 * (a different dungeon's mobs from an earlier import, or a hand-authored counter-theme exception
 * mob — TRINITY_SPEC 2) is left untouched. This lets multiple themed dungeons share one profile
 * table and survives repeated imports without wiping admin edits. If the existing output file cannot
 * be parsed as YAML, the import aborts without touching it (see {@link #importFrom}).
 *
 * <p>Each entry also carries informational {@code source-name}/{@code entity-type} keys so the admin
 * can recognise the mob; those are ignored at runtime by {@code MobProfileConfig}.
 */
public final class EliteMobsImporter {

    /** Safety cap on the recursive directory walk; a real content pack is far smaller. */
    private static final int DEFAULT_MAX_FILES = 2_000;

    private static final String ROOT = "profiles";

    private final ConversionPolicy policy;
    private final Logger log;
    private final int maxFiles;

    public EliteMobsImporter(ConversionPolicy policy, Logger log) {
        this(policy, log, DEFAULT_MAX_FILES);
    }

    // Package-private (not private) so unit tests can exercise the file-cap/truncation boundary
    // with a handful of files instead of creating thousands of them on disk.
    EliteMobsImporter(ConversionPolicy policy, Logger log, int maxFiles) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.log = Objects.requireNonNull(log, "log");
        if (maxFiles <= 0) {
            throw new IllegalArgumentException("maxFiles must be positive: " + maxFiles);
        }
        this.maxFiles = maxFiles;
    }

    /**
     * Converts every EliteMobs mob file under {@code sourceDir} and merges the result into
     * {@code outputFile}.
     *
     * @throws IOException if the source is not a directory, the existing output file is not valid
     *                      YAML (the merge aborts rather than risk discarding hand-authored entries),
     *                      or the output cannot be written
     */
    public ImportResult importFrom(File sourceDir, File outputFile) throws IOException {
        Objects.requireNonNull(sourceDir, "sourceDir");
        Objects.requireNonNull(outputFile, "outputFile");
        if (!sourceDir.isDirectory()) {
            throw new IOException("source is not a directory: " + sourceDir);
        }

        List<File> files = new ArrayList<>();
        AtomicBoolean truncated = new AtomicBoolean(false);
        collectYaml(sourceDir, files, truncated);
        if (truncated.get()) {
            log.warning("[mob-import] file cap " + maxFiles + " reached; remaining files ignored");
        }

        YamlConfiguration out = loadExistingOrEmpty(outputFile);
        Set<String> existingIdsBeforeThisRun = existingProfileIds(out);
        Set<String> idsThisRun = new HashSet<>();

        int imported = 0;
        int replaced = 0;
        int skipped = 0;
        int errors = 0;
        int duplicates = 0;
        for (File file : files) {
            YamlConfiguration boss;
            try {
                boss = loadYaml(file);
            } catch (InvalidConfigurationException | IOException ex) {
                log.warning("[mob-import] skipping '" + file.getPath() + "': YAML parse error: "
                        + ex.getMessage());
                errors++;
                continue;
            }
            if (boss.getString("entityType") == null) {
                // Not an EliteMobs mob file (e.g. a dungeon/loot definition) — skip.
                skipped++;
                continue;
            }
            String id = sanitizeId(stripExtension(file.getName()), file);
            if (id.isBlank()) {
                // e.g. a dot-prefixed file like ".yml" strips to an empty id, which would write a
                // malformed "profiles.." key path. Skip it rather than emit an unmatchable profile.
                log.warning("[mob-import] skipping '" + file.getPath() + "': empty id after stripping extension");
                skipped++;
                continue;
            }
            if (!idsThisRun.add(id)) {
                log.warning("[mob-import] duplicate mob id '" + id + "' (" + file.getPath()
                        + "); keeping the first, skipping this one");
                duplicates++;
                continue;
            }
            if (existingIdsBeforeThisRun.contains(id)) {
                replaced++;
            }
            MobProfile profile = EliteMobsMobMapping.convert(id, boss, policy);
            write(out, ROOT + "." + id + ".", profile,
                    EliteMobsMobMapping.sourceName(boss), EliteMobsMobMapping.entityType(boss));
            imported++;
        }

        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create output directory: " + parent.getAbsolutePath());
        }
        out.save(outputFile);
        return new ImportResult(imported, replaced, skipped, errors, duplicates, truncated.get(),
                outputFile.getPath());
    }

    /**
     * Loads the existing output file so this run can merge into it. Returns an empty config when the
     * file does not exist yet (first import). Uses a self-managed {@code load()} rather than
     * {@code YamlConfiguration.loadConfiguration(File)}, which silently swallows YAML syntax errors
     * as an empty config — that would be indistinguishable from "no existing profiles" and would
     * silently wipe out everything already in the file once saved.
     */
    private YamlConfiguration loadExistingOrEmpty(File outputFile) throws IOException {
        YamlConfiguration out = new YamlConfiguration();
        if (!outputFile.exists()) {
            return out;
        }
        try {
            out.load(outputFile);
        } catch (InvalidConfigurationException | IOException ex) {
            throw new IOException("existing output file has invalid YAML; aborting the merge to avoid "
                    + "discarding its contents (hand-authored entries included): "
                    + outputFile.getPath(), ex);
        }
        return out;
    }

    private static Set<String> existingProfileIds(YamlConfiguration out) {
        ConfigurationSection profiles = out.getConfigurationSection(ROOT);
        return profiles == null ? Set.of() : new HashSet<>(profiles.getKeys(false));
    }

    /**
     * Self-managed YAML load for one candidate mob file. {@code YamlConfiguration.loadConfiguration}
     * swallows syntax errors as an empty config, which this importer's caller would otherwise
     * misreport as "not an EliteMobs mob file" (no {@code entityType}) rather than a parse failure.
     */
    private static YamlConfiguration loadYaml(File file) throws InvalidConfigurationException, IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        return yaml;
    }

    private void collectYaml(File dir, List<File> out, AtomicBoolean truncated) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (out.size() >= maxFiles) {
                // A child remains unprocessed here, so the walk is genuinely truncated — unlike
                // simply finishing the last directory exactly at the cap with nothing left over.
                truncated.set(true);
                return;
            }
            // Do not follow symlinks: a content pack unpacked from an archive could contain links
            // pointing outside the source tree, which would pull in unrelated YAML.
            if (Files.isSymbolicLink(child.toPath())) {
                continue;
            }
            if (child.isDirectory()) {
                collectYaml(child, out, truncated);
            } else {
                String lower = child.getName().toLowerCase();
                if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
                    out.add(child);
                }
            }
        }
    }

    /**
     * Bukkit config paths split on '.', so a dotted id would silently nest into the wrong keys.
     * Replace dots with '_' (and warn) so each id stays a single literal key.
     */
    private String sanitizeId(String rawId, File file) {
        if (rawId.indexOf('.') < 0) {
            return rawId;
        }
        String safe = rawId.replace('.', '_');
        log.warning("[mob-import] mob id '" + rawId + "' (" + file.getPath()
                + ") contains '.'; stored as '" + safe + "'");
        return safe;
    }

    private static void write(YamlConfiguration out, String base, MobProfile profile,
                              String sourceName, String entityType) {
        out.set(base + "level", profile.level());
        // Only written when true (redundant-key avoidance, matching this writer's other fields).
        if (profile.dynamic()) {
            out.set(base + "dynamic", true);
        }
        out.set(base + "dungeon-theme", profile.dungeonTheme() == null ? "" : profile.dungeonTheme());
        out.set(base + "source-name", sourceName);
        out.set(base + "entity-type", entityType);
        out.set(base + "armor-strength", profile.armorStrength());
        // 0 = unconfigured HP → the EliteMobs fork keeps its own level-scaled HP for this mob.
        out.set(base + "max-health", profile.maxHealth());
        writeDefense(out, base + "physical.", profile.physical());
        writeDefense(out, base + "magical.", profile.magical());
        writeAttack(out, base, profile);
    }

    private static void writeDefense(YamlConfiguration out, String base, DefenseStats stats) {
        out.set(base + "defense-rate", stats.defenseRate());
        out.set(base + "resistance", stats.resistance());
        out.set(base + "damage-reduction", stats.damageReduction());
        out.set(base + "flat-defense", stats.flatDefense());
    }

    /**
     * Writes the attacker block only when the policy actually synthesized one; a re-import with an
     * all-zero attack ramp also CLEARS a previously written block (set to null), so re-running the
     * import after zeroing {@code combat/mob-import.yml attack:} genuinely reverts the mobs to their
     * vanilla/EliteMobs damage instead of leaving a stale stamp source behind.
     */
    private static void writeAttack(YamlConfiguration out, String base, MobProfile profile) {
        if (!profile.hasAttack()) {
            out.set(base + "attack", null);
            return;
        }
        AttackStats attack = profile.attack();
        out.set(base + "attack.attack-power", attack.defaultDamage());
        out.set(base + "attack.flat-bonus-damage", attack.flatBonusDamage());
        out.set(base + "attack.percent-bonus-damage", attack.percentBonusDamage());
        out.set(base + "attack.crit-chance", attack.critChance());
        out.set(base + "attack.crit-damage", attack.critDamage());
        out.set(base + "attack.penetration", attack.penetration());
        out.set(base + "attack.damage-modifier", attack.damageModifier());
        out.set(base + "attack.fixed-damage", attack.fixedDamage());
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /**
     * Outcome of an import run.
     *
     * @param imported  mob profiles written this run (new ids + replaced ids)
     * @param replaced  subset of {@code imported} that overwrote an id already present in the output
     *                  file before this run
     * @param skipped   candidate files that were not a mob (no {@code entityType}) or produced an
     *                  empty id
     * @param errors    candidate files that failed to parse as YAML
     * @param duplicates mob ids that collided within this run's own file set (first wins)
     * @param truncated whether the directory walk hit the file cap and left files unprocessed
     */
    public record ImportResult(int imported, int replaced, int skipped, int errors, int duplicates,
                               boolean truncated, String outputPath) {
    }
}
