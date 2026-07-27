package com.trinityforge.mobs;

import com.trinityforge.mobs.ConversionPolicy.DefenseRamp;
import com.trinityforge.mobs.ConversionPolicy.LevelSource;
import com.trinityforge.mobs.ConversionPolicy.Ramp;
import com.trinityforge.mobs.EliteMobsImporter.ImportResult;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * File-IO tests for {@link EliteMobsImporter}: directory walk, id sanitisation, skip/duplicate
 * accounting, and output writing. Uses {@link TempDir} so no live server or fixed paths are needed.
 */
class EliteMobsImporterTest {

    private static final Ramp ZERO = new Ramp(0.0, 0.0);
    private static final DefenseRamp ZERO_DEFENSE = new DefenseRamp(ZERO, ZERO, ZERO, ZERO);

    private static EliteMobsImporter importer() {
        ConversionPolicy policy = new ConversionPolicy(
                LevelSource.ELITEMOBS, 1, 1, "", ZERO_DEFENSE, ZERO_DEFENSE, ZERO);
        return new EliteMobsImporter(policy, java.util.logging.Logger.getLogger("EliteMobsImporterTest"));
    }

    private static EliteMobsImporter importer(int maxFiles) {
        ConversionPolicy policy = new ConversionPolicy(
                LevelSource.ELITEMOBS, 1, 1, "", ZERO_DEFENSE, ZERO_DEFENSE, ZERO);
        return new EliteMobsImporter(policy, java.util.logging.Logger.getLogger("EliteMobsImporterTest"), maxFiles);
    }

    private static void writeFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }

    private static String bossYaml(String level) {
        return "entityType: ZOMBIE\nname: \"&cBoss\"\nlevel: " + level + "\n";
    }

    @Test
    @DisplayName("non-directory source throws IOException")
    void nonDirectorySourceThrows(@TempDir Path tmp) throws IOException {
        File notADir = tmp.resolve("plain.yml").toFile();
        writeFile(notADir, bossYaml("5"));
        File out = tmp.resolve("out.yml").toFile();

        assertThrows(IOException.class, () -> importer().importFrom(notADir, out));
    }

    @Test
    @DisplayName("file without entityType is counted as skipped, not imported")
    void fileWithoutEntityTypeSkipped(@TempDir Path tmp) throws IOException {
        File src = tmp.resolve("src").toFile();
        writeFile(new File(src, "loot.yml"), "lootTable: stuff\nname: NotAMob\n");
        File out = tmp.resolve("out.yml").toFile();

        ImportResult result = importer().importFrom(src, out);

        assertEquals(0, result.imported());
        assertEquals(1, result.skipped());
        assertEquals(0, result.duplicates());
    }

    @Test
    @DisplayName("valid mob file is imported and output YAML carries profiles.<id>.level")
    void validMobImportedAndWritten(@TempDir Path tmp) throws IOException {
        File src = tmp.resolve("src").toFile();
        writeFile(new File(src, "debt_collector.yml"), bossYaml("12"));
        File out = tmp.resolve("out.yml").toFile();

        ImportResult result = importer().importFrom(src, out);

        assertEquals(1, result.imported());
        assertTrue(out.exists());
        YamlConfiguration written = YamlConfiguration.loadConfiguration(out);
        assertEquals(12, written.getInt("profiles.debt_collector.level"));
        assertEquals("ZOMBIE", written.getString("profiles.debt_collector.entity-type"));
    }

    @Test
    @DisplayName("a level: dynamic mob is written with dynamic: true")
    void dynamicLevelMobWritesDynamicFlag(@TempDir Path tmp) throws IOException {
        File src = tmp.resolve("src").toFile();
        writeFile(new File(src, "wanderer.yml"), bossYaml("dynamic"));
        File out = tmp.resolve("out.yml").toFile();

        importer().importFrom(src, out);

        YamlConfiguration written = YamlConfiguration.loadConfiguration(out);
        assertTrue(written.getBoolean("profiles.wanderer.dynamic", false));
    }

    @Test
    @DisplayName("a numeric-level mob has no dynamic key written (redundant-key avoidance)")
    void fixedLevelMobOmitsDynamicKey(@TempDir Path tmp) throws IOException {
        File src = tmp.resolve("src").toFile();
        writeFile(new File(src, "grunt.yml"), bossYaml("3"));
        File out = tmp.resolve("out.yml").toFile();

        importer().importFrom(src, out);

        YamlConfiguration written = YamlConfiguration.loadConfiguration(out);
        assertFalse(written.contains("profiles.grunt.dynamic"));
    }

    @Test
    @DisplayName("duplicate id across two directories keeps the first and counts one duplicate")
    void duplicateIdKeepsFirst(@TempDir Path tmp) throws IOException {
        File src = tmp.resolve("src").toFile();
        writeFile(new File(new File(src, "a"), "boss.yml"), bossYaml("3"));
        writeFile(new File(new File(src, "b"), "boss.yml"), bossYaml("7"));
        File out = tmp.resolve("out.yml").toFile();

        ImportResult result = importer().importFrom(src, out);

        assertEquals(1, result.imported());
        assertEquals(1, result.duplicates());
        // Exactly one "boss" profile survives; its level is one of the two inputs (first wins).
        YamlConfiguration written = YamlConfiguration.loadConfiguration(out);
        assertTrue(written.contains("profiles.boss.level"));
    }

    @Test
    @DisplayName("dotted filename is sanitized: elite.boss.yml -> profiles.elite_boss")
    void dottedFilenameSanitized(@TempDir Path tmp) throws IOException {
        File src = tmp.resolve("src").toFile();
        writeFile(new File(src, "elite.boss.yml"), bossYaml("4"));
        File out = tmp.resolve("out.yml").toFile();

        ImportResult result = importer().importFrom(src, out);

        assertEquals(1, result.imported());
        YamlConfiguration written = YamlConfiguration.loadConfiguration(out);
        assertEquals(4, written.getInt("profiles.elite_boss.level"));
    }

    @Test
    @DisplayName("recursive subdir walk imports nested mob files")
    void recursiveSubdirWalk(@TempDir Path tmp) throws IOException {
        File src = tmp.resolve("src").toFile();
        writeFile(new File(new File(new File(src, "pack"), "deep"), "nested_boss.yml"), bossYaml("9"));
        File out = tmp.resolve("out.yml").toFile();

        ImportResult result = importer().importFrom(src, out);

        assertEquals(1, result.imported());
        YamlConfiguration written = YamlConfiguration.loadConfiguration(out);
        assertEquals(9, written.getInt("profiles.nested_boss.level"));
    }

    @Test
    @DisplayName("output parent directory is auto-created when absent")
    void outputParentDirCreated(@TempDir Path tmp) throws IOException {
        File src = tmp.resolve("src").toFile();
        writeFile(new File(src, "a_boss.yml"), bossYaml("1"));
        File out = tmp.resolve("nested").resolve("created").resolve("out.yml").toFile();
        assertFalse(out.getParentFile().exists());

        ImportResult result = importer().importFrom(src, out);

        assertTrue(out.exists());
        assertEquals(1, result.imported());
    }

    @Test
    @DisplayName("attack ramp policy writes profiles.<id>.attack; zero ramp clears a stale block")
    void attackBlockWrittenAndClearedByReimport(@TempDir Path tmp) throws IOException {
        File src = tmp.resolve("src").toFile();
        writeFile(new File(src, "mage_boss.yml"), bossYaml("10"));
        File out = tmp.resolve("out.yml").toFile();

        // First import with a configured attack ramp writes the attack block.
        ConversionPolicy.AttackRamp attackRamp = new ConversionPolicy.AttackRamp(
                new Ramp(2.0, 0.5), ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO);
        ConversionPolicy withAttack = new ConversionPolicy(
                LevelSource.ELITEMOBS, 1, 1, "", ZERO_DEFENSE, ZERO_DEFENSE, ZERO, attackRamp);
        new EliteMobsImporter(withAttack, java.util.logging.Logger.getLogger("EliteMobsImporterTest"))
                .importFrom(src, out);
        YamlConfiguration written = YamlConfiguration.loadConfiguration(out);
        assertEquals(7.0, written.getDouble("profiles.mage_boss.attack.attack-power"), 1.0e-9);

        // Re-import with the zero ramp clears it (revert to vanilla/EliteMobs damage).
        importer().importFrom(src, out);
        written = YamlConfiguration.loadConfiguration(out);
        assertFalse(written.contains("profiles.mage_boss.attack"),
                "zero-ramp re-import must clear the stale attack block");
    }

    @Test
    @DisplayName("max-health ramp writes profiles.<id>.max-health; zero ramp writes 0 (EliteMobs HP)")
    void maxHealthWrittenAndRoundTrips(@TempDir Path tmp) throws IOException {
        File src = tmp.resolve("src").toFile();
        writeFile(new File(src, "tanky_boss.yml"), bossYaml("10"));
        File out = tmp.resolve("out.yml").toFile();

        // A configured HP ramp writes the synthesized absolute max-health.
        ConversionPolicy withHp = new ConversionPolicy(
                LevelSource.ELITEMOBS, 1, 1, "", ZERO_DEFENSE, ZERO_DEFENSE, ZERO,
                ConversionPolicy.AttackRamp.ZERO, new Ramp(20.0, 5.0)); // 20 + 5*10 = 70
        new EliteMobsImporter(withHp, java.util.logging.Logger.getLogger("EliteMobsImporterTest"))
                .importFrom(src, out);
        YamlConfiguration written = YamlConfiguration.loadConfiguration(out);
        assertEquals(70.0, written.getDouble("profiles.tanky_boss.max-health"), 1.0e-9);

        // The default (zero) HP ramp writes 0 = unconfigured (fork keeps the mob's EliteMobs HP).
        importer().importFrom(src, out);
        written = YamlConfiguration.loadConfiguration(out);
        assertTrue(written.contains("profiles.tanky_boss.max-health"));
        assertEquals(0.0, written.getDouble("profiles.tanky_boss.max-health"), 1.0e-9);
    }

    @Test
    @DisplayName("empty-id file like .yml is skipped, never written as profiles..")
    void emptyIdFileSkipped(@TempDir Path tmp) throws IOException {
        File src = tmp.resolve("src").toFile();
        writeFile(new File(src, ".yml"), bossYaml("2"));
        File out = tmp.resolve("out.yml").toFile();

        ImportResult result = importer().importFrom(src, out);

        assertEquals(0, result.imported());
        assertEquals(1, result.skipped());
        YamlConfiguration written = YamlConfiguration.loadConfiguration(out);
        assertFalse(written.contains("profiles"));
    }

    @Test
    @DisplayName("re-import merges: re-imported id is replaced, other existing ids survive")
    void mergePreservesExistingAndReplacesReimported(@TempDir Path tmp) throws IOException {
        File out = tmp.resolve("out.yml").toFile();
        writeFile(out, """
                profiles:
                  hand_authored_exception:
                    level: 42
                    dungeon-theme: ""
                    armor-strength: 3.0
                    physical: { defense-rate: 0.9, resistance: 0.0, damage-reduction: 0.0, flat-defense: 0.0 }
                    magical:  { defense-rate: 0.0, resistance: 0.0, damage-reduction: 0.0, flat-defense: 0.0 }
                  boss:
                    level: 1
                    dungeon-theme: ""
                    armor-strength: 0.0
                    physical: { defense-rate: 0.0, resistance: 0.0, damage-reduction: 0.0, flat-defense: 0.0 }
                    magical:  { defense-rate: 0.0, resistance: 0.0, damage-reduction: 0.0, flat-defense: 0.0 }
                """);

        File src = tmp.resolve("src").toFile();
        writeFile(new File(src, "boss.yml"), bossYaml("99"));

        ImportResult result = importer().importFrom(src, out);

        assertEquals(1, result.imported());
        assertEquals(1, result.replaced());
        YamlConfiguration written = YamlConfiguration.loadConfiguration(out);
        // Re-imported id is replaced with the new level.
        assertEquals(99, written.getInt("profiles.boss.level"));
        // Untouched hand-authored id (a counter-theme exception mob, TRINITY_SPEC 2) survives.
        assertEquals(42, written.getInt("profiles.hand_authored_exception.level"));
        assertEquals(3.0, written.getDouble("profiles.hand_authored_exception.armor-strength"), 1.0e-9);
    }

    @Test
    @DisplayName("invalid existing output YAML aborts the merge without touching the file")
    void invalidExistingOutputAbortsMerge(@TempDir Path tmp) throws IOException {
        File out = tmp.resolve("out.yml").toFile();
        String malformed = "profiles:\n  boss: [unterminated\n";
        writeFile(out, malformed);

        File src = tmp.resolve("src").toFile();
        writeFile(new File(src, "boss.yml"), bossYaml("5"));

        assertThrows(IOException.class, () -> importer().importFrom(src, out));
        assertEquals(malformed, Files.readString(out.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("YAML syntax error in a candidate mob file is counted as an error, not silently skipped")
    void syntaxErrorFileCountedAsError(@TempDir Path tmp) throws IOException {
        File src = tmp.resolve("src").toFile();
        writeFile(new File(src, "broken.yml"), "entityType: [unterminated\n");
        writeFile(new File(src, "good.yml"), bossYaml("3"));
        File out = tmp.resolve("out.yml").toFile();

        ImportResult result = importer().importFrom(src, out);

        assertEquals(1, result.imported());
        assertEquals(1, result.errors());
        assertEquals(0, result.skipped());
    }

    @Test
    @DisplayName("truncated is false when the file count lands exactly on the cap with nothing left over")
    void truncatedFalseAtExactCap(@TempDir Path tmp) throws IOException {
        File src = tmp.resolve("src").toFile();
        writeFile(new File(src, "a.yml"), bossYaml("1"));
        writeFile(new File(src, "b.yml"), bossYaml("2"));
        File out = tmp.resolve("out.yml").toFile();

        ImportResult result = importer(2).importFrom(src, out);

        assertEquals(2, result.imported());
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("truncated is true when the walk leaves files unprocessed past the cap")
    void truncatedTrueWhenFilesRemain(@TempDir Path tmp) throws IOException {
        File src = tmp.resolve("src").toFile();
        writeFile(new File(src, "a.yml"), bossYaml("1"));
        writeFile(new File(src, "b.yml"), bossYaml("2"));
        writeFile(new File(src, "c.yml"), bossYaml("3"));
        File out = tmp.resolve("out.yml").toFile();

        ImportResult result = importer(2).importFrom(src, out);

        assertEquals(2, result.imported());
        assertTrue(result.truncated());
    }
}
