package com.trinityforge.listeners;

import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.LivingEntityMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MobOverrideExpListener}: this listener previously had zero test coverage (2026-07-26 review
 * finding). Covers:
 * <ul>
 *   <li>H1 (バニラEXP二重付与) — this listener's own half of the fix: it must write {@code droppedExp}
 *       exactly once, deterministically, from the resolved ramp, and must NOT touch {@code droppedExp}
 *       at all when no ramp is configured for this mob (so an un-ramped mob's EXP — including whatever
 *       {@code DefaultDropsHandler} in the EliteMobs fork already computed for it — is left completely
 *       alone). The OTHER half of H1 (preventing the fork's {@code DefaultDropsHandler} from ALSO
 *       spawning its own {@code ExperienceOrb} when a ramp IS configured) lives in the fork's
 *       {@code DefaultDropsHandler#trinityForgeOwnsVanillaExp}, which calls this same
 *       {@link MobOverridesConfig#vanillaExpFor} — that fork-side behavior cannot be exercised from
 *       TrinityForge's own test suite (no EliteMobs classes on this classpath), so it is out of scope
 *       here by necessity, not by oversight.</li>
 *   <li>H2 (非プレイヤーキル) — {@code entity.getKiller() == null} must leave {@code droppedExp}
 *       untouched entirely.</li>
 *   <li>M1 (モブidの正規化) — an operator-authored {@code mob-overrides.yml} key with a trailing
 *       {@code .yml} (as if copy-pasted from an EliteMobs custom-boss filename) must still resolve
 *       against the bare, fork-stamped {@code MOB_PROFILE_ID}.</li>
 * </ul>
 * H3 (不正な {@code stats:} 値がskippedになり例外を伝播させない) is a pure config-parse concern with no
 * {@code EntityDeathEvent} involvement, so its tests live in {@code MobOverridesConfigTest} alongside the
 * existing {@code invalidDropEntrySkippedNotFatal} coverage, mirroring that file's own precedent.
 */
class MobOverrideExpListenerTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("my_dungeon");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private MobOverridesConfig loadedConfig(File dataFolder, String yaml) throws Exception {
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
            case "getLogger" -> Logger.getLogger("MobOverrideExpListenerTest");
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

    /**
     * @param profileId the {@code MOB_PROFILE_ID} PDC stamp to write, or {@code null} for an unstamped
     *                  mob (mirrors the fork's already-normalized stamp — see
     *                  {@code TrinityForgeSpawnListener#resolveProfileId}).
     * @param killer    the entity's {@code getKiller()} value, or {@code null} for a non-player kill
     *                  (2026-07-26 H2).
     */
    private EntityDeathEvent deathEventFor(org.bukkit.entity.LivingEntity entity, String profileId,
                                            Player killer) {
        if (profileId != null) {
            entity.getPersistentDataContainer().set(PdcKeys.MOB_PROFILE_ID, PersistentDataType.STRING, profileId);
        }
        if (killer != null) {
            ((LivingEntityMock) entity).setKiller(killer);
        }
        org.bukkit.damage.DamageSource source = org.bukkit.damage.DamageSource
                .builder(org.bukkit.damage.DamageType.GENERIC_KILL).build();
        List<ItemStack> drops = new ArrayList<>();
        EntityDeathEvent event = new EntityDeathEvent(entity, source, drops);
        event.setDroppedExp(7); // vanilla's own pre-listener value; must survive untouched when unmodified
        return event;
    }

    @Test
    void mobWithoutProfileIdIsSkipped(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        vanilla-exp: 500
                """);
        MobOverrideExpListener listener = new MobOverrideExpListener(config);

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, null, server.addPlayer());
        listener.onDeath(event);

        assertEquals(7, event.getDroppedExp(), "no profile id -> no ramp to resolve -> untouched");
    }

    @Test
    void playerKillWithConfiguredRampOverwritesDroppedExp(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        vanilla-exp: 500
                """);
        MobOverrideExpListener listener = new MobOverrideExpListener(config);

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief", server.addPlayer());
        listener.onDeath(event);

        assertEquals(500, event.getDroppedExp(), "a configured ramp must overwrite droppedExp exactly once");
    }

    @Test
    void unconfiguredMobLeavesDroppedExpCompletelyUntouched(@TempDir File dir) throws Exception {
        // H1 (TF-side half): a mob with no vanilla-exp ramp anywhere must NOT have its EXP touched by
        // this listener at all — whatever the fork's DefaultDropsHandler already computed for it (its
        // own default multiplier-based orb, for a dropsVanillaLoot:true mob) must survive unmodified.
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      some_other_mob:
                        vanilla-exp: 500
                """);
        MobOverrideExpListener listener = new MobOverrideExpListener(config);

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief", server.addPlayer());
        listener.onDeath(event);

        assertEquals(7, event.getDroppedExp(), "unconfigured mob -> 'leave EXP alone', never 0 or a ramp value");
    }

    @Test
    void nonPlayerKillDoesNotApplyRamp(@TempDir File dir) throws Exception {
        // H2: getKiller() == null (mob infighting, lava, fall damage, ...) must not pay out the ramp EXP
        // even though a ramp IS configured for this mob — otherwise any non-player kill loop is a free
        // EXP farm.
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        vanilla-exp: 500
                """);
        MobOverrideExpListener listener = new MobOverrideExpListener(config);

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief", null); // no killer
        listener.onDeath(event);

        assertEquals(7, event.getDroppedExp(), "non-player kill must never apply the configured ramp");
    }

    @Test
    void yamlSuffixedOverrideKeyResolvesAgainstTheNormalizedStampedId(@TempDir File dir) throws Exception {
        // M1: an operator who copy-pasted the EliteMobs custom-boss filename (with its ".yml" extension)
        // into mob-overrides.yml must still match the bare id the fork actually stamps onto MOB_PROFILE_ID
        // (TrinityForgeSpawnListener normalizes the stamp; this config previously did not normalize its
        // own keys, so "goblin_chief.yml" and "goblin_chief" would never match).
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief.yml:
                        vanilla-exp: 500
                """);
        MobOverrideExpListener listener = new MobOverrideExpListener(config);

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        // The fork always stamps the ALREADY-normalized (extension-stripped) id.
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief", server.addPlayer());
        listener.onDeath(event);

        assertEquals(500, event.getDroppedExp(),
                "a '.yml'-suffixed config key must still resolve against the normalized stamped id");
    }
}
