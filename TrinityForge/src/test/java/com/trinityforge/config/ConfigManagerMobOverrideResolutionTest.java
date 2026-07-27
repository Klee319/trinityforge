package com.trinityforge.config;

import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.config.domains.MobProfileConfig;
import com.trinityforge.mobs.MobProfile;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigManager#resolveRuntimeProfile(String, int, long, String)}: the wiring point where
 * {@code combat/mob-overrides.yml} (2026-07-26 ダンジョン×モブ単位オーバーライド新設) overlays
 * {@code combat/mob-profiles.yml}. Drives a real {@link ConfigManager#loadAll()} over a temp data
 * folder (mirrors {@code CombatWiringSupport}'s pattern) so this exercises the actual production wiring,
 * not just the isolated {@code MobOverridesConfig} unit.
 */
class ConfigManagerMobOverrideResolutionTest {

    private static Plugin resourcePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ConfigManagerMobOverrideResolutionTest");
            case "saveResource" -> {
                copyResource((String) args[0], dataFolder);
                yield null;
            }
            case "getResource" -> ConfigManagerMobOverrideResolutionTest.class
                    .getResourceAsStream("/" + args[0]);
            case "toString" -> "ResourcePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static void copyResource(String resourcePath, File dataFolder) {
        try (var in = ConfigManagerMobOverrideResolutionTest.class.getResourceAsStream("/" + resourcePath)) {
            if (in == null) {
                return;
            }
            File target = new File(dataFolder, resourcePath);
            Files.createDirectories(target.getParentFile().toPath());
            Files.copy(in, target.toPath());
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("failed to copy bundled resource " + resourcePath, ex);
        }
    }

    private static void writeFile(File dataFolder, String path, String content) throws Exception {
        File target = new File(dataFolder, path);
        Files.createDirectories(target.getParentFile().toPath());
        Files.writeString(target.toPath(), content);
    }

    @Test
    void shippedDefaultsAreFullyBackwardCompatible(@TempDir File dir) {
        // 回帰防止(最重要): mob-overrides.yml が既定(空)のまま loadAll() した場合、
        // resolveRuntimeProfile の新旧オーバーロードは同一の結果を返す。
        ConfigManager manager = new ConfigManager(resourcePlugin(dir));
        manager.loadAll();

        Optional<MobProfile> withoutWorld = manager.resolveRuntimeProfile("nonexistent_mob", 10, 42L);
        Optional<MobProfile> withWorld = manager.resolveRuntimeProfile("nonexistent_mob", 10, 42L, "any_world");
        assertEquals(withoutWorld, withWorld);
    }

    @Test
    void unknownMobIsSynthesizedFromTheImportPolicy(@TempDir File dir) throws Exception {
        // 2026-07-26 「無料DL枠ダンジョンの敵を設定できない」修正: mob-profiles.yml に無いモブでも
        // mob-import.yml のランプを実レベルで評価したプロファイルが返り、mob-overrides.yml が乗る。
        writeFile(dir, MobOverridesConfig.PATH, """
                overrides:
                  default:
                    mobs:
                      never_imported_boss:
                        stats:
                          max-health: 777
                """);
        ConfigManager manager = new ConfigManager(resourcePlugin(dir));
        manager.loadAll();

        Optional<MobProfile> plain = manager.resolveRuntimeProfile("never_imported_boss", 30, 0L);
        assertTrue(plain.isPresent(), "an un-imported mob is synthesized rather than left profile-less");
        assertEquals(30, plain.get().level(), "synthesized at the mob's runtime level");
        assertTrue(plain.get().maxHealth() > 0.0, "the shipped max-health ramp is applied");

        Optional<MobProfile> overridden =
                manager.resolveRuntimeProfile("never_imported_boss", 30, 0L, "any_world");
        assertEquals(777.0, overridden.get().maxHealth(), "mob-overrides.yml now reaches un-imported mobs");
    }

    @Test
    void yamlSuffixedIdResolvesToTheImportedProfile(@TempDir File dir) throws Exception {
        // EliteMobs hands out ids via MagmaCore's getFilename(), which always carries ".yml", while the
        // importer keys profiles on the bare name. Without normalization the ".yml" form would now
        // synthesize a fresh profile and shadow the imported one.
        writeFile(dir, MobProfileConfig.PATH, """
                profiles:
                  goblin_chief:
                    level: 10
                    max-health: 100.0
                    armor-strength: 0.2
                    physical:
                      defense-rate: 0.1
                    magical:
                      defense-rate: 0.1
                """);
        writeFile(dir, MobOverridesConfig.PATH, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 500
                """);
        ConfigManager manager = new ConfigManager(resourcePlugin(dir));
        manager.loadAll();

        assertEquals(manager.resolveRuntimeProfile("goblin_chief", 10, 7L, "w"),
                manager.resolveRuntimeProfile("goblin_chief.yml", 10, 7L, "w"),
                "the .yml-suffixed id must resolve identically to the bare one");
        assertEquals(500.0, manager.resolveRuntimeProfile("goblin_chief.yml", 10, 7L, "w").get().maxHealth(),
                "and the override still reaches it");
    }

    @Test
    void unknownMobStaysProfilelessWhenSynthesisIsDisabled(@TempDir File dir) throws Exception {
        writeFile(dir, "combat/mob-import.yml", """
                level:
                  source: ELITEMOBS
                  default: 1
                unknown-mobs:
                  synthesize: false
                """);
        ConfigManager manager = new ConfigManager(resourcePlugin(dir));
        manager.loadAll();

        assertTrue(manager.resolveRuntimeProfile("never_imported_boss", 30, 0L).isEmpty(),
                "opt-out restores the pre-2026-07-26 behaviour");
        assertTrue(manager.resolveRuntimeProfile("never_imported_boss", 30, 0L, "any_world").isEmpty());
    }

    @Test
    void worldSpecificOverrideWinsOverDefaultAndBaseProfile(@TempDir File dir) throws Exception {
        writeFile(dir, MobProfileConfig.PATH, """
                profiles:
                  goblin_chief:
                    level: 10
                    max-health: 100.0
                    armor-strength: 0.2
                    physical:
                      defense-rate: 0.1
                    magical:
                      defense-rate: 0.1
                """);
        writeFile(dir, MobOverridesConfig.PATH, """
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
        ConfigManager manager = new ConfigManager(resourcePlugin(dir));
        manager.loadAll();

        Optional<MobProfile> resolved = manager.resolveRuntimeProfile("goblin_chief", 10, 0L, "my_dungeon");
        assertTrue(resolved.isPresent());
        assertEquals(2000.0, resolved.get().maxHealth(), "world scope's max-health wins");
        assertEquals(20, resolved.get().level(), "level cascades from the default scope (unset in world scope)");
        assertEquals(0.1, resolved.get().physical().defenseRate(), "untouched field keeps mob-profiles.yml's value");

        Optional<MobProfile> resolvedOtherWorld =
                manager.resolveRuntimeProfile("goblin_chief", 10, 0L, "some_other_world");
        assertEquals(500.0, resolvedOtherWorld.get().maxHealth(), "falls back to default scope only");

        // The pre-existing 3-arg (no-world) overload is untouched by this feature: it never consults
        // mob-overrides.yml at all (only the NEW 4-arg overload does), so existing callers that have not
        // been migrated to pass a world keep their exact prior behavior byte-for-byte — in particular it
        // must NOT pick up either scope's max-health override (500/2000), whatever base+variance value
        // combat/mob-import.yml's default policy produces.
        Optional<MobProfile> resolvedNoWorldOverload = manager.resolveRuntimeProfile("goblin_chief", 10, 0L);
        assertFalse(resolvedNoWorldOverload.isEmpty());
        assertFalse(resolvedNoWorldOverload.get().maxHealth() == 500.0
                        || resolvedNoWorldOverload.get().maxHealth() == 2000.0,
                "3-arg (no-world) overload must be unaffected by mob-overrides.yml; got "
                        + resolvedNoWorldOverload.get().maxHealth());
    }
}
