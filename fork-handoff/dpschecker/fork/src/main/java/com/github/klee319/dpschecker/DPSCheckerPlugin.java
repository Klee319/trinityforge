package com.github.klee319.dpschecker;

import com.github.klee319.dpschecker.calculator.DpsSessionManager;
import com.github.klee319.dpschecker.command.DPSCheckerCommand;
import com.github.klee319.dpschecker.dummy.DummyManager;
import com.github.klee319.dpschecker.listener.DamageListener;
import com.github.klee319.dpschecker.listener.EntityListener;
import com.github.klee319.dpschecker.listener.GUIListener;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class DPSCheckerPlugin extends JavaPlugin {

    private DummyManager dummyManager;
    private DpsSessionManager sessionManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        dummyManager = new DummyManager(this);
        sessionManager = new DpsSessionManager(this, dummyManager);
        sessionManager.start();

        var pm = getServer().getPluginManager();
        pm.registerEvents(new DamageListener(this, dummyManager, sessionManager), this);
        pm.registerEvents(new EntityListener(this, dummyManager), this);
        pm.registerEvents(new GUIListener(this), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            new DPSCheckerCommand(this, dummyManager, sessionManager).register(event.registrar());
        });

        Bukkit.getScheduler().runTask(this, dummyManager::loadExistingDummies);

        if (getConfig().getBoolean("bossbar.enabled", true)) {
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (!dummyManager.getAllDummies().isEmpty()) {
                    dummyManager.updateAllBossBars();
                }
            }, 20L, 20L);
        }

        getLogger().info("DPSChecker has been enabled!");
    }

    @Override
    public void onDisable() {
        if (sessionManager != null) {
            sessionManager.stop();
        }
        if (dummyManager != null) {
            dummyManager.cleanupWithoutRemovingEntities();
        }
        getLogger().info("DPSChecker has been disabled!");
    }

    public DummyManager getDummyManager() { return dummyManager; }
    public DpsSessionManager getSessionManager() { return sessionManager; }
}
