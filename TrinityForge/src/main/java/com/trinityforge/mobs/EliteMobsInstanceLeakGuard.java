package com.trinityforge.mobs;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.logging.Level;

/**
 * Releases completed EliteMobs dungeon instances from EliteMobs' static instance registry.
 *
 * <p>EliteMobs' {@code DungeonInstance} registers every new dungeon in a static
 * {@code dungeonInstances} set. Its normal removal path only removes the inherited
 * {@code MatchInstance.instances} entry, which retains the completed world, its bosses and their
 * transitive block states for the remainder of the JVM lifetime. This guard removes the completed
 * dungeon from the missing registry on the next server tick, after EliteMobs has completed its own
 * destruction sequence.
 *
 * <p>The dependency is intentionally reflective: EliteMobs is optional on TrinityForge's resource
 * server, and Paper keeps plugin class loaders separate. If the expected EliteMobs API is absent or
 * changes, this guard logs once per failed cleanup and otherwise leaves EliteMobs untouched.
 */
public final class EliteMobsInstanceLeakGuard implements Listener {

    private static final String ELITE_MOBS_PLUGIN = "EliteMobs";
    private static final String MATCH_DESTROY_EVENT =
            "com.magmaguy.elitemobs.api.instanced.MatchDestroyEvent";
    private static final String DUNGEON_INSTANCE =
            "com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance";

    private final JavaPlugin plugin;
    private boolean hookInstalled;

    public EliteMobsInstanceLeakGuard(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Installs the optional EliteMobs event hook when that plugin is enabled. */
    public synchronized void installIfAvailable() {
        if (hookInstalled) {
            return;
        }

        Plugin eliteMobs = Bukkit.getPluginManager().getPlugin(ELITE_MOBS_PLUGIN);
        if (eliteMobs == null || !eliteMobs.isEnabled()) {
            return;
        }

        try {
            ClassLoader classLoader = eliteMobs.getClass().getClassLoader();
            Class<?> rawEventClass = Class.forName(MATCH_DESTROY_EVENT, false, classLoader);
            Class<? extends Event> eventClass = rawEventClass.asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(eventClass, this, EventPriority.MONITOR,
                    (ignored, event) -> scheduleRelease(event, classLoader), plugin, false);
            hookInstalled = true;
            plugin.getLogger().info("[elitemobs-instance-leak-guard] completion cleanup hook installed");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[elitemobs-instance-leak-guard] unable to install completion cleanup hook", ex);
        }
    }

    @org.bukkit.event.EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (ELITE_MOBS_PLUGIN.equals(event.getPlugin().getName())) {
            installIfAvailable();
        }
    }

    private void scheduleRelease(Event event, ClassLoader classLoader) {
        // MatchDestroyEvent is fired before DungeonInstance.removeInstance(). Deferring one tick
        // preserves EliteMobs' teardown order while preventing the static set from retaining it.
        Bukkit.getScheduler().runTask(plugin, () -> releaseCompletedDungeon(event, classLoader));
    }

    private void releaseCompletedDungeon(Event event, ClassLoader classLoader) {
        try {
            Object matchInstance = event.getClass().getMethod("getInstance").invoke(event);
            Class<?> dungeonInstanceClass = Class.forName(DUNGEON_INSTANCE, false, classLoader);
            Object rawInstances = dungeonInstanceClass.getMethod("getDungeonInstances").invoke(null);
            if (!(rawInstances instanceof Set<?> instances)) {
                plugin.getLogger().warning("[elitemobs-instance-leak-guard] dungeon registry is not a Set");
                return;
            }

            @SuppressWarnings("unchecked")
            Set<Object> retainingSet = (Set<Object>) instances;
            releaseDestroyedDungeonInstance(matchInstance, dungeonInstanceClass, retainingSet);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[elitemobs-instance-leak-guard] unable to release completed dungeon instance", ex);
        }
    }

    static boolean releaseDestroyedDungeonInstance(
            Object matchInstance, Class<?> dungeonInstanceClass, Set<Object> retainingSet) {
        return matchInstance != null
                && dungeonInstanceClass.isInstance(matchInstance)
                && retainingSet.remove(matchInstance);
    }
}
