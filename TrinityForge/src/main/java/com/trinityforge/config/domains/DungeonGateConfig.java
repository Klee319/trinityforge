package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.mobs.DungeonGate;
import com.trinityforge.mobs.GateRegion;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code dungeon/gates.yml}: per-dungeon entry gates keyed by destination world name,
 * with optional content-package aliases for EliteMobs instanced dungeons.
 */
public final class DungeonGateConfig implements LoadableConfig {

    public static final String PATH = "dungeon/gates.yml";
    private static final String ROOT = "gates";

    private volatile Map<String, DungeonGate> gatesByWorld = Map.of();
    private volatile Map<String, DungeonGate> gatesByAlias = Map.of();
    // D3 topology: 区画(in-place)ゲートを region.world 名で引く移動チェック用インデックス。
    private volatile Map<String, List<DungeonGate>> regionGatesByWorld = Map.of();

    /** Gate keyed by destination world name. */
    public Optional<DungeonGate> gate(String worldName) {
        return Optional.ofNullable(gatesByWorld.get(worldName));
    }

    /**
     * Resolves a gate by world name or content-package alias (case-insensitive for aliases).
     * World name match is exact (Bukkit world names are case-sensitive on some platforms).
     */
    public Optional<DungeonGate> resolve(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            return Optional.empty();
        }
        DungeonGate byWorld = gatesByWorld.get(lookupKey);
        if (byWorld != null) {
            return Optional.of(byWorld);
        }
        return Optional.ofNullable(gatesByAlias.get(lookupKey.toLowerCase(Locale.ROOT)));
    }

    public Map<String, DungeonGate> all() {
        return gatesByWorld;
    }

    /** 区画ゲート(region付き)を、そのregionが属するワールド名で引く。無ければ空リスト。 */
    public List<DungeonGate> regionGates(String worldName) {
        return regionGatesByWorld.getOrDefault(worldName, List.of());
    }

    /** 区画ゲートが1つでも設定されているか(移動イベントの早期リターン用)。 */
    public boolean hasRegionGates() {
        return !regionGatesByWorld.isEmpty();
    }

    public String resourcePath() {
        return PATH;
    }

    @Override
    public boolean load(Plugin plugin) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), PATH);
        if (!file.exists()) {
            plugin.saveResource(PATH, false);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }
        ParseResult result = parse(yaml.getConfigurationSection(ROOT), log);
        this.gatesByWorld = result.gatesByWorld();
        this.gatesByAlias = result.gatesByAlias();
        this.regionGatesByWorld = result.regionGatesByWorld();

        if (result.skipped() > 0) {
            log.warning("[" + PATH + "] loaded " + result.gatesByWorld().size() + " gate(s), "
                    + result.skipped() + " skipped");
            return false;
        }
        log.info("[" + PATH + "] loaded " + result.gatesByWorld().size() + " dungeon gate(s) OK");
        return true;
    }

    static ParseResult parse(ConfigurationSection root, Logger log) {
        Map<String, DungeonGate> byWorld = new LinkedHashMap<>();
        Map<String, DungeonGate> byAlias = new LinkedHashMap<>();
        Map<String, List<DungeonGate>> regionByWorld = new LinkedHashMap<>();
        int skipped = 0;
        if (root != null) {
            for (String world : root.getKeys(false)) {
                ConfigurationSection entry = root.getConfigurationSection(world);
                if (entry == null) {
                    log.warning("[" + PATH + "] gate '" + world + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                int requiredLevel = entry.getInt("required-combat-level", 0);
                Material keyMaterial = parseKeyMaterial(entry, world, log);
                int keyAmount = Math.max(1, entry.getInt("key-amount", 1));
                List<String> aliases = parseAliases(entry);
                GateRegion region;
                try {
                    region = parseRegion(entry.getConfigurationSection("region"));
                } catch (IllegalArgumentException ex) {
                    // fail-soft: 壊れたregionはこのゲートのregion経路だけを無効化し、
                    // world名/エイリアス経路(テレポート/インスタンス入場)は生かす。
                    log.warning("[" + PATH + "] gate '" + world + "' region invalid ("
                            + ex.getMessage() + "); region gate ignored");
                    skipped++;
                    region = null;
                }
                DungeonGate gate = new DungeonGate(world, aliases, requiredLevel, keyMaterial,
                        keyAmount, region);
                byWorld.put(world, gate);
                if (region != null) {
                    regionByWorld.computeIfAbsent(region.world(), k -> new ArrayList<>()).add(gate);
                }
                for (String alias : aliases) {
                    String key = alias.toLowerCase(Locale.ROOT);
                    if (byAlias.containsKey(key)) {
                        log.warning("[" + PATH + "] duplicate content-package alias '" + alias
                                + "'; last gate wins");
                    }
                    byAlias.put(key, gate);
                }
            }
        }
        Map<String, List<DungeonGate>> regionFrozen = new LinkedHashMap<>();
        regionByWorld.forEach((k, v) -> regionFrozen.put(k, List.copyOf(v)));
        return new ParseResult(Map.copyOf(byWorld), Map.copyOf(byAlias),
                Map.copyOf(regionFrozen), skipped);
    }

    /**
     * Reads the optional {@code region:} section (D3 区画ダンジョン): {@code world} (required) and
     * {@code min}/{@code max} as {@code [x, y, z]} integer triples (any two opposite corners;
     * {@link GateRegion} normalises). Throws {@link IllegalArgumentException} on structural problems
     * so the caller can drop just the region while keeping the rest of the gate.
     */
    private static GateRegion parseRegion(ConfigurationSection region) {
        if (region == null) {
            return null;
        }
        String world = region.getString("world");
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("region needs a 'world'");
        }
        int[] min = parseCorner(region, "min");
        int[] max = parseCorner(region, "max");
        return new GateRegion(world.trim(), min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    private static int[] parseCorner(ConfigurationSection region, String key) {
        List<Integer> raw = region.getIntegerList(key);
        if (raw.size() != 3) {
            throw new IllegalArgumentException("region '" + key + "' must be [x, y, z]");
        }
        return new int[] {raw.get(0), raw.get(1), raw.get(2)};
    }

    private static List<String> parseAliases(ConfigurationSection entry) {
        List<String> out = new ArrayList<>();
        if (entry.isList("aliases")) {
            for (String raw : entry.getStringList("aliases")) {
                if (raw != null && !raw.isBlank()) {
                    out.add(raw.trim());
                }
            }
        }
        String single = entry.getString("content-package");
        if (single != null && !single.isBlank()) {
            out.add(single.trim());
        }
        return out;
    }

    private static Material parseKeyMaterial(ConfigurationSection entry, String world, Logger log) {
        String raw = entry.getString("key-material");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            log.warning("[" + PATH + "] gate '" + world + "' has unknown key-material '" + raw
                    + "'; key gate ignored");
        }
        return material;
    }

    record ParseResult(Map<String, DungeonGate> gatesByWorld, Map<String, DungeonGate> gatesByAlias,
                       Map<String, List<DungeonGate>> regionGatesByWorld, int skipped) {
    }
}
