package com.trinityforge.config.domains;

import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless parse checks for the special reward registry (2026-07-23-stat-gate-overhaul §6.1/§6.7). */
class SpecialRewardsConfigTest {

    private static final Logger LOG = Logger.getLogger("SpecialRewardsConfigTest");

    private static SpecialRewardsConfig.ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return SpecialRewardsConfig.parse(cfg, LOG);
    }

    @Test
    void parsesAllThreeCategories() throws Exception {
        SpecialRewardsConfig.ParseResult result = parse("""
                titles:
                  dragon-slayer:
                    display: "<red>竜殺し</red>"
                particles:
                  crit-aura:
                    particle: CRIT
                    count: 4
                    radius: 0.6
                    interval-ticks: 10
                    shape: aura
                particle-seeds:
                  ember-seed:
                    seed-item: "custom:core_ember"
                    particle: FLAME
                    count: 3
                """);
        assertEquals(0, result.skipped());
        assertEquals("<red>竜殺し</red>", result.titles().get("dragon-slayer").display());

        SpecialRewardsConfig.ParticleEffect particle = result.particles().get("crit-aura");
        assertEquals(Particle.CRIT, particle.particle());
        assertEquals(4, particle.count());
        assertEquals(0.6, particle.radius());
        assertEquals(10, particle.intervalTicks());
        assertEquals(SpecialRewardsConfig.Shape.AURA, particle.shape());

        SpecialRewardsConfig.ParticleSeed seed = result.particleSeeds().get("ember-seed");
        assertEquals("custom:core_ember", seed.seedItem());
        assertEquals(Particle.FLAME, seed.particle());
        assertEquals(3, seed.count());
    }

    @Test
    void titleMissingDisplayIsSkipped() throws Exception {
        SpecialRewardsConfig.ParseResult result = parse("""
                titles:
                  broken: {}
                """);
        assertEquals(1, result.skipped());
        assertTrue(result.titles().isEmpty());
    }

    @Test
    void particleWithInvalidParticleNameIsSkipped() throws Exception {
        SpecialRewardsConfig.ParseResult result = parse("""
                particles:
                  bad:
                    particle: NOT_A_REAL_PARTICLE
                """);
        assertEquals(1, result.skipped());
        assertTrue(result.particles().isEmpty());
    }

    @Test
    void particleWithInvalidShapeIsSkipped() throws Exception {
        SpecialRewardsConfig.ParseResult result = parse("""
                particles:
                  bad:
                    particle: FLAME
                    shape: triangle
                """);
        assertEquals(1, result.skipped());
    }

    @Test
    void particleSeedMissingSeedItemIsSkipped() throws Exception {
        SpecialRewardsConfig.ParseResult result = parse("""
                particle-seeds:
                  bad:
                    particle: FLAME
                """);
        assertEquals(1, result.skipped());
    }

    @Test
    void particleDefaultsApplyWhenOmitted() throws Exception {
        SpecialRewardsConfig.ParseResult result = parse("""
                particles:
                  minimal:
                    particle: FLAME
                """);
        SpecialRewardsConfig.ParticleEffect effect = result.particles().get("minimal");
        assertEquals(1, effect.count());
        assertEquals(0.5, effect.radius());
        assertEquals(10, effect.intervalTicks());
        assertEquals(SpecialRewardsConfig.Shape.CIRCLE, effect.shape());
    }

    @Test
    void emptyDocumentYieldsEmptyMaps() throws Exception {
        SpecialRewardsConfig.ParseResult result = parse("titles: {}\nparticles: {}\nparticle-seeds: {}");
        assertEquals(0, result.skipped());
        assertTrue(result.titles().isEmpty());
        assertTrue(result.particles().isEmpty());
        assertTrue(result.particleSeeds().isEmpty());
        assertFalse(new SpecialRewardsConfig().isKnown("anything"));
    }

    // --- display.title-separator (2026-08-02: TextDisplayオフセット当て推量方式からの置き換え) -------------

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("SpecialRewardsConfigTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not be called when the file already exists on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static SpecialRewardsConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, SpecialRewardsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        SpecialRewardsConfig config = new SpecialRewardsConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void titleSeparatorDefaultsWhenAbsent(@TempDir File tempDir) throws IOException {
        SpecialRewardsConfig config = loaded(tempDir, "titles: {}\nparticles: {}\nparticle-seeds: {}\n");
        assertEquals(" ", config.titleSeparator());
    }

    @Test
    void titleSeparatorHonorsExplicitValue(@TempDir File tempDir) throws IOException {
        SpecialRewardsConfig config = loaded(tempDir, """
                display:
                  title-separator: " | "
                titles: {}
                particles: {}
                particle-seeds: {}
                """);
        assertEquals(" | ", config.titleSeparator());
    }

    @Test
    void titleSeparatorHonorsExplicitEmptyString(@TempDir File tempDir) throws IOException {
        // 空文字列("区切り無し")は正式な設定として許容する。null(未設定)だけが既定にフォールバックする。
        SpecialRewardsConfig config = loaded(tempDir, """
                display:
                  title-separator: ""
                titles: {}
                particles: {}
                particle-seeds: {}
                """);
        assertEquals("", config.titleSeparator());
    }

    // --- prune-orphaned-grants / lastLoadOk (SpecialRewardPruner の安全弁, 2026-07-28) ---------------

    @Test
    void pruneOrphanedGrantsDefaultsToTrueWhenAbsent(@TempDir File tempDir) throws IOException {
        SpecialRewardsConfig config = loaded(tempDir, "titles: {}\nparticles: {}\nparticle-seeds: {}\n");
        assertTrue(config.pruneOrphanedGrants());
    }

    @Test
    void pruneOrphanedGrantsHonorsExplicitFalse(@TempDir File tempDir) throws IOException {
        SpecialRewardsConfig config = loaded(tempDir, """
                prune-orphaned-grants: false
                titles: {}
                particles: {}
                particle-seeds: {}
                """);
        assertFalse(config.pruneOrphanedGrants());
    }

    @Test
    void lastLoadOkIsTrueAfterCleanLoad(@TempDir File tempDir) throws IOException {
        SpecialRewardsConfig config = loaded(tempDir, "titles: {}\nparticles: {}\nparticle-seeds: {}\n");
        assertTrue(config.lastLoadOk());
    }

    @Test
    void lastLoadOkIsFalseAfterMalformedEntrySkip(@TempDir File tempDir) throws IOException {
        // A malformed entry (title without display) makes load() return false via result.skipped() > 0,
        // even though titles/particles/particleSeeds are still (partially) reassigned.
        SpecialRewardsConfig config = loaded(tempDir, """
                titles:
                  broken: {}
                particles: {}
                particle-seeds: {}
                """);
        assertFalse(config.lastLoadOk());
    }

    @Test
    void lastLoadOkIsFalseAfterYamlSyntaxError(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, SpecialRewardsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), "titles: [this is not valid yaml for a map");
        SpecialRewardsConfig config = new SpecialRewardsConfig();
        config.load(fakePlugin(tempDir));
        assertFalse(config.lastLoadOk());
        // and the safety-net default stays true even though load() itself failed.
        assertTrue(config.pruneOrphanedGrants());
    }
}
