package com.github.klee319.dpschecker.dummy;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class DummyManager {

    private final JavaPlugin plugin;
    private final Map<UUID, DummyEntity> dummies = new ConcurrentHashMap<>();
    private final NamespacedKey dummyKey;

    public DummyManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dummyKey = new NamespacedKey(plugin, "dummy");
    }

    public DummyEntity createDummy(UUID ownerUuid, String name, Location location) {
        double maxHp = plugin.getConfig().getDouble("dummy.default-hp", 100.0);
        double defense = plugin.getConfig().getDouble("dummy.default-defense", 0.0);
        double toughness = plugin.getConfig().getDouble("dummy.default-armor-toughness", 0.0);

        DummyEntity dummy = new DummyEntity(plugin, ownerUuid, name, location,
                maxHp, defense, toughness);
        registerDummy(dummy);
        return dummy;
    }

    public void registerDummy(DummyEntity dummy) {
        dummies.put(dummy.getDummyUuid(), dummy);
    }

    public void loadExistingDummies() {
        int loaded = 0;
        for (World world : Bukkit.getWorlds()) {
            loaded += registerFromEntities(world.getEntities());
        }
        if (loaded > 0) {
            plugin.getLogger().info("Restored " + loaded + " DPS dummy(ies) from saved entities.");
        }
    }

    /**
     * 与えられたエンティティ群から未登録のダミーを復元・登録し、登録した件数を返す。
     * 起動時の全ワールド走査({@link #loadExistingDummies})と、後からチャンクがロードされた際の
     * {@code EntitiesLoadEvent} 再登録の両方で共有する。起動時走査はロード済みチャンクしか見ないため、
     * これが無いと未ロードチャンクのダミーは実体は残っても管理対象から外れ「消えた」ように見える。
     */
    public int registerFromEntities(Collection<Entity> entities) {
        int loaded = 0;
        for (Entity entity : entities) {
            if (!(entity instanceof Zombie zombie)) continue;
            if (!entity.getPersistentDataContainer().has(dummyKey, PersistentDataType.BOOLEAN)) {
                continue;
            }
            Optional<DummyEntity> restored = DummyEntity.fromExisting(plugin, zombie);
            if (restored.isEmpty()) continue;

            DummyEntity dummy = restored.get();
            if (dummies.containsKey(dummy.getDummyUuid())) {
                continue;
            }
            registerDummy(dummy);
            loaded++;
        }
        return loaded;
    }

    public void removeDummy(UUID dummyUuid) {
        DummyEntity dummy = dummies.remove(dummyUuid);
        if (dummy != null) {
            dummy.remove();
        }
    }

    public void removeAll() {
        for (DummyEntity dummy : dummies.values()) {
            dummy.remove();
        }
        dummies.clear();
    }

    public void cleanupWithoutRemovingEntities() {
        for (DummyEntity dummy : dummies.values()) {
            dummy.cleanupWithoutRemovingEntity();
        }
        dummies.clear();
    }

    public Optional<DummyEntity> getDummyByEntity(Entity entity) {
        if (!entity.getPersistentDataContainer().has(dummyKey, PersistentDataType.BOOLEAN)) {
            return Optional.empty();
        }

        NamespacedKey idKey = new NamespacedKey(plugin, "dummy_id");
        String idRaw = entity.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        if (idRaw != null) {
            try {
                UUID dummyUuid = UUID.fromString(idRaw);
                DummyEntity byId = dummies.get(dummyUuid);
                if (byId != null && byId.isEntity(entity)) {
                    return Optional.of(byId);
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to scan.
            }
        }

        return dummies.values().stream()
                .filter(d -> d.isEntity(entity))
                .findFirst();
    }

    public Optional<DummyEntity> getNearestDummy(Location location, double maxRange) {
        return dummies.values().stream()
                .filter(d -> d.getEntity() != null && !d.getEntity().isDead())
                .filter(d -> d.getEntity().getLocation().getWorld().equals(location.getWorld()))
                .filter(d -> d.getEntity().getLocation().distance(location) <= maxRange)
                .min(Comparator.comparingDouble(
                        d -> d.getEntity().getLocation().distance(location)));
    }

    public List<DummyEntity> getDummiesByOwner(UUID ownerUuid) {
        return dummies.values().stream()
                .filter(d -> d.getOwnerUuid().equals(ownerUuid))
                .collect(Collectors.toList());
    }

    public List<DummyEntity> getDummiesByName(String name) {
        return dummies.values().stream()
                .filter(d -> d.getName().equals(name))
                .collect(Collectors.toList());
    }

    public int removeByName(String name) {
        List<DummyEntity> matched = getDummiesByName(name);
        for (DummyEntity dummy : matched) {
            removeDummy(dummy.getDummyUuid());
        }
        return matched.size();
    }

    public int getOwnerDummyCount(UUID ownerUuid) {
        return (int) dummies.values().stream()
                .filter(d -> d.getOwnerUuid().equals(ownerUuid))
                .count();
    }

    public int getMaxPerPlayer() {
        return plugin.getConfig().getInt("dummy.max-per-player", 3);
    }

    public boolean isDummyEntity(Entity entity) {
        return entity.getPersistentDataContainer().has(dummyKey, PersistentDataType.BOOLEAN);
    }

    public Collection<DummyEntity> getAllDummies() {
        return Collections.unmodifiableCollection(dummies.values());
    }

    public void updateAllBossBars() {
        int range = plugin.getConfig().getInt("bossbar.range", 30);
        for (DummyEntity dummy : dummies.values()) {
            dummy.updateBossBarVisibility(range);
        }
    }
}
