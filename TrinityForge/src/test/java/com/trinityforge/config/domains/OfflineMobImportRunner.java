package com.trinityforge.config.domains;

import com.trinityforge.mobs.ConversionPolicy;
import com.trinityforge.mobs.EliteMobsImporter;
import com.trinityforge.mobs.EliteMobsImporter.ImportResult;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One-shot offline runner that reproduces {@code /trinityforge importmobs} outside the server by
 * driving the exact production path ({@link MobImportConfig#parse} → {@link EliteMobsImporter}). It
 * lets an operator bulk-convert a live server's EliteMobs custombosses tree into
 * {@code mob-profiles.yml} without launching Minecraft, then deploy the result and just
 * {@code /trinityforge reload}.
 *
 * <p>Disabled by default: it only runs when all three environment variables below are set AND their
 * paths exist, so the normal {@code ./gradlew test} run skips it (JUnit {@code assumeTrue}). The test
 * fork inherits the parent process environment, so set these before invoking Gradle:
 * <ul>
 *   <li>{@code TF_IMPORT_POLICY} — path to the deployed {@code combat/mob-import.yml}</li>
 *   <li>{@code TF_IMPORT_SOURCE} — path to the EliteMobs {@code custombosses} folder</li>
 *   <li>{@code TF_IMPORT_OUT}    — output {@code mob-profiles.yml} (pre-seed it with the current
 *       deployed file to preserve hand-authored entries via the importer's merge)</li>
 * </ul>
 */
class OfflineMobImportRunner {

    @Test
    @DisplayName("offline: convert a live custombosses tree into mob-profiles.yml via the production path")
    void runOfflineImport() throws IOException, InvalidConfigurationException {
        String policyPath = System.getenv("TF_IMPORT_POLICY");
        String sourcePath = System.getenv("TF_IMPORT_SOURCE");
        String outPath = System.getenv("TF_IMPORT_OUT");
        assumeTrue(policyPath != null && sourcePath != null && outPath != null,
                "TF_IMPORT_* env vars not set; offline import runner skipped");

        File policyFile = new File(policyPath);
        File sourceDir = new File(sourcePath);
        File outFile = new File(outPath);
        assumeTrue(policyFile.isFile(), "policy file missing: " + policyPath);
        assumeTrue(sourceDir.isDirectory(), "source dir missing: " + sourcePath);

        Logger log = Logger.getLogger("OfflineMobImportRunner");

        // Parse the deployed policy through the same loader the plugin uses at reload.
        YamlConfiguration policyYaml = new YamlConfiguration();
        policyYaml.load(policyFile);
        ConversionPolicy policy = MobImportConfig.parse(policyYaml, log);

        // Merge into the existing output (mirrors the in-game command's merge-not-overwrite behaviour).
        ImportResult result = new EliteMobsImporter(policy, log).importFrom(sourceDir, outFile);

        log.info("[offline-import] imported=" + result.imported()
                + " replaced=" + result.replaced()
                + " skipped=" + result.skipped()
                + " errors=" + result.errors()
                + " duplicates=" + result.duplicates()
                + " truncated=" + result.truncated()
                + " -> " + result.outputPath());
        System.out.println("[offline-import] imported=" + result.imported()
                + " replaced=" + result.replaced()
                + " skipped=" + result.skipped()
                + " errors=" + result.errors()
                + " duplicates=" + result.duplicates()
                + " truncated=" + result.truncated()
                + " -> " + result.outputPath());

        assertTrue(result.imported() > 0, "expected at least one mob profile to be imported");
        assertTrue(outFile.isFile(), "output profiles file was not written");
    }
}
