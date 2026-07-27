package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.gacha.GachaEntry;
import com.trinityforge.gacha.GachaPool;
import com.trinityforge.gacha.GachaTicket;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code gacha.yml}: config-driven "gacha ticket" prize tables. {@code tickets:} maps a
 * physical ticket item (by its {@code items/catalog.yml} id, matched via the item's
 * {@code ITEM_CATALOG_ID} PDC tag — never by display name) to a {@code pools:} entry, which is a
 * weighted list of prizes. A prize's {@code item} is either a TrinityForge catalog id or a vanilla
 * {@link org.bukkit.Material} name; that resolution happens at draw time in {@code GachaListener},
 * not here, so this loader never depends on {@link ItemCatalogConfig}.
 *
 * <p>Both sections are open-ended (arbitrary ticket/pool ids). Malformed entries are skipped with a
 * warning and the rest still load, same fail-soft policy as {@link ItemCatalogConfig} /
 * {@link MobProfileConfig}. A ticket whose {@code pool} does not resolve to a parsed pool is skipped
 * (pool-ref validation) rather than left dangling.
 *
 * <p>{@link #parse(YamlConfiguration, Logger)} is separated from {@link #load(Plugin)} so parsing
 * (including the pool-ref check) is unit-testable headlessly without a Plugin.
 */
public final class GachaConfig implements LoadableConfig {

    public static final String PATH = "gacha.yml";
    private static final String TICKETS_ROOT = "tickets";
    private static final String POOLS_ROOT = "pools";

    private volatile Map<String, GachaTicket> tickets = Map.of();
    private volatile Map<String, GachaPool> pools = Map.of();

    /** Looks up a ticket definition by the physical item's catalog id (its {@code ITEM_CATALOG_ID} PDC tag). */
    public Optional<GachaTicket> ticket(String catalogId) {
        return Optional.ofNullable(tickets.get(catalogId));
    }

    public Optional<GachaPool> pool(String poolId) {
        return Optional.ofNullable(pools.get(poolId));
    }

    public Map<String, GachaTicket> tickets() {
        return tickets;
    }

    public Map<String, GachaPool> pools() {
        return pools;
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

        // loadConfiguration(File) は構文エラーを握り潰して空configを返すため自前でload()する。
        // 構文エラー時は直前に成功ロード済みのtickets/pools(初回失敗時はMap.of())を維持しfalseを返す。
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }

        ParseResult result = parse(yaml, log);
        this.tickets = result.tickets();
        this.pools = result.pools();

        if (result.skipped() > 0) {
            log.warning("[" + PATH + "] loaded " + result.tickets().size() + " ticket(s), "
                    + result.pools().size() + " pool(s), " + result.skipped() + " issue(s); see console");
            return false;
        }
        log.info("[" + PATH + "] loaded " + result.tickets().size() + " ticket(s), "
                + result.pools().size() + " pool(s) OK");
        return true;
    }

    /** Pure parse of the whole file: pools first, then tickets (validated against the parsed pools). */
    static ParseResult parse(YamlConfiguration yaml, Logger log) {
        PoolsResult poolsResult = parsePools(yaml.getConfigurationSection(POOLS_ROOT), log);
        TicketsResult ticketsResult = parseTickets(yaml.getConfigurationSection(TICKETS_ROOT),
                poolsResult.pools(), log);
        return new ParseResult(ticketsResult.tickets(), poolsResult.pools(),
                poolsResult.skipped() + ticketsResult.skipped());
    }

    private static PoolsResult parsePools(ConfigurationSection root, Logger log) {
        Map<String, GachaPool> parsed = new LinkedHashMap<>();
        int skipped = 0;
        if (root != null) {
            for (String poolId : root.getKeys(false)) {
                ConfigurationSection poolSection = root.getConfigurationSection(poolId);
                if (poolSection == null) {
                    log.warning("[" + PATH + "] pool '" + poolId + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                EntriesResult entriesResult = parseEntries(poolSection.getList("entries"), poolId, log);
                skipped += entriesResult.skipped();
                if (entriesResult.entries().isEmpty()) {
                    log.warning("[" + PATH + "] pool '" + poolId + "' has no valid entries; skipped");
                    skipped++;
                    continue;
                }
                // 天井(pity)設定。0(既定)=無効。pity.threshold 回連続で最高レア(最小weight枠)を
                // 引けなかった場合、次回抽選を最高レア枠から強制確定させる(CR-9安全弁②)。
                int pityThreshold = Math.max(0, poolSection.getInt("pity.threshold", 0));
                parsed.put(poolId, new GachaPool(poolId, entriesResult.entries(), pityThreshold));
            }
        }
        return new PoolsResult(Map.copyOf(parsed), skipped);
    }

    private static EntriesResult parseEntries(List<?> rawEntries, String poolId, Logger log) {
        List<GachaEntry> entries = new ArrayList<>();
        int skipped = 0;
        if (rawEntries != null) {
            for (Object raw : rawEntries) {
                if (!(raw instanceof Map<?, ?> map)) {
                    log.warning("[" + PATH + "] pool '" + poolId + "' has a non-map entry; skipped");
                    skipped++;
                    continue;
                }
                try {
                    entries.add(parseEntry(map));
                } catch (IllegalArgumentException ex) {
                    log.warning("[" + PATH + "] pool '" + poolId + "' entry invalid (" + ex.getMessage()
                            + "); skipped");
                    skipped++;
                }
            }
        }
        return new EntriesResult(List.copyOf(entries), skipped);
    }

    private static GachaEntry parseEntry(Map<?, ?> map) {
        Object rawItem = map.get("item");
        String itemId = rawItem == null ? null : String.valueOf(rawItem).trim();
        if (itemId == null || itemId.isEmpty()) {
            throw new IllegalArgumentException("missing 'item'");
        }
        int weight = toInt(map.get("weight"), 0);
        int amount = toInt(map.get("amount"), 1);
        boolean qualityRandom = Boolean.TRUE.equals(map.get("quality-random"));
        return new GachaEntry(itemId, weight, amount, qualityRandom);
    }

    private static int toInt(Object raw, int defaultValue) {
        return raw instanceof Number number ? number.intValue() : defaultValue;
    }

    private static TicketsResult parseTickets(ConfigurationSection root, Map<String, GachaPool> pools, Logger log) {
        Map<String, GachaTicket> parsed = new LinkedHashMap<>();
        int skipped = 0;
        if (root != null) {
            for (String catalogId : root.getKeys(false)) {
                ConfigurationSection ticketSection = root.getConfigurationSection(catalogId);
                if (ticketSection == null) {
                    log.warning("[" + PATH + "] ticket '" + catalogId + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                String poolId = ticketSection.getString("pool");
                if (poolId == null || poolId.isBlank()) {
                    log.warning("[" + PATH + "] ticket '" + catalogId + "' has no 'pool'; skipped");
                    skipped++;
                    continue;
                }
                poolId = poolId.trim();
                if (!pools.containsKey(poolId)) {
                    log.warning("[" + PATH + "] ticket '" + catalogId + "' references unknown pool '"
                            + poolId + "'; skipped");
                    skipped++;
                    continue;
                }
                try {
                    parsed.put(catalogId, new GachaTicket(catalogId, poolId));
                } catch (IllegalArgumentException ex) {
                    log.warning("[" + PATH + "] ticket '" + catalogId + "' invalid (" + ex.getMessage()
                            + "); skipped");
                    skipped++;
                }
            }
        }
        return new TicketsResult(Map.copyOf(parsed), skipped);
    }

    /** Parse outcome: the immutable ticket/pool maps and how many entries were skipped in total. */
    record ParseResult(Map<String, GachaTicket> tickets, Map<String, GachaPool> pools, int skipped) {
    }

    private record PoolsResult(Map<String, GachaPool> pools, int skipped) {
    }

    private record TicketsResult(Map<String, GachaTicket> tickets, int skipped) {
    }

    private record EntriesResult(List<GachaEntry> entries, int skipped) {
    }
}
