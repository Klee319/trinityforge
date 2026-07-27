package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code economy/villager-trades.yml}: perk-gated custom villager trades.
 */
public final class VillagerTradesConfig implements LoadableConfig {

    public static final String PATH = "economy/villager-trades.yml";

    public record TradeStack(String material, String catalogId, int amount) {
        public boolean isCatalog() {
            return catalogId != null && !catalogId.isBlank();
        }
    }

    public record TradeOffer(TradeStack input, TradeStack output, int maxUses, int villagerXp) {
    }

    /**
     * {@code unlock-effect} is no longer read (2026-07-23 動的ID方式改修): the gate id is always
     * {@code trade:<PROFESSION>}, derived from {@code profession} alone. Any leftover {@code unlock-effect}
     * key in the yml is silently ignored (back-compat: old files don't need editing to keep loading).
     */
    public record ProfessionTrades(Villager.Profession profession,
                                   boolean blockVanillaTrades, List<TradeOffer> trades) {
    }

    private volatile Map<Villager.Profession, ProfessionTrades> byProfession = Map.of();

    public Map<Villager.Profession, ProfessionTrades> byProfession() {
        return byProfession;
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
            log.log(Level.SEVERE, "[" + PATH + "] YAML error: " + ex.getMessage(), ex);
            return false;
        }

        Map<Villager.Profession, ProfessionTrades> parsed = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("professions");
        if (root != null) {
            for (String profName : root.getKeys(false)) {
                Villager.Profession profession = parseProfession(profName);
                if (profession == null) {
                    log.warning("[" + PATH + "] unknown profession: " + profName);
                    continue;
                }
                ConfigurationSection sec = root.getConfigurationSection(profName);
                if (sec == null) {
                    continue;
                }
                boolean blockVanilla = sec.getBoolean("block-vanilla-trades", false);
                List<TradeOffer> offers = new ArrayList<>();
                List<Map<?, ?>> tradeList = sec.getMapList("trades");
                for (Map<?, ?> raw : tradeList) {
                    TradeOffer offer = parseTrade(raw, log);
                    if (offer != null) {
                        offers.add(offer);
                    }
                }
                parsed.put(profession, new ProfessionTrades(
                        profession, blockVanilla, List.copyOf(offers)));
            }
        }
        this.byProfession = Collections.unmodifiableMap(parsed);
        log.info("[" + PATH + "] loaded " + parsed.size() + " profession(s) OK");
        return true;
    }

    private static Villager.Profession parseProfession(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Villager.Profession.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static TradeOffer parseTrade(Map<?, ?> raw, Logger log) {
        if (raw == null) {
            return null;
        }
        Object inObj = raw.get("input");
        Object outObj = raw.get("output");
        if (!(inObj instanceof Map<?, ?> inMap) || !(outObj instanceof Map<?, ?> outMap)) {
            return null;
        }
        TradeStack input = parseStack(inMap);
        TradeStack output = parseStack(outMap);
        if (input == null || output == null) {
            return null;
        }
        int maxUses = raw.get("max-uses") instanceof Number n ? Math.max(1, n.intValue()) : 12;
        int xp = raw.get("villager-xp") instanceof Number x ? Math.max(0, x.intValue()) : 2;
        return new TradeOffer(input, output, maxUses, xp);
    }

    private static TradeStack parseStack(Map<?, ?> map) {
        String catalog = map.get("catalog") instanceof String s ? s : null;
        String material = map.get("material") instanceof String s ? s : null;
        int amount = map.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        if (catalog != null && !catalog.isBlank()) {
            return new TradeStack(null, catalog.trim(), amount);
        }
        if (material != null) {
            Material mat = Material.matchMaterial(material);
            if (mat != null) {
                return new TradeStack(mat.name(), null, amount);
            }
        }
        return null;
    }
}
