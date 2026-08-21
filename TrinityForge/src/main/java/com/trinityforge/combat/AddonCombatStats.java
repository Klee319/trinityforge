package com.trinityforge.combat;

import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.stats.StatKeys;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic addon combat-stat contribution channel. A hard-dependent addon (the ArsPaper fork) computes a
 * per-player canonical stat map — e.g. the combined stats of the armor "threads" a player has socketed
 * (each thread's {@code stats/item-stats.yml} entry plus a same-type-count set bonus) — and writes it to
 * the player's PDC under {@link PdcKeys#PLAYER_ADDON_COMBAT_STATS}. TrinityForge folds that map into BOTH
 * the attacker path ({@code CombatListener}) and the defender path ({@link PlayerDefenseResolver}) exactly
 * like a skill-tree perk buff: the attacker bridge picks only offensive keys, the defender bridge only
 * defensive keys, so one mixed map routes correctly with no pre-filter (the sole special case is
 * {@code attack-power}, folded additively into the melee base like a perk, never a base replacement).
 *
 * <p>The map is stored as a compact {@code "key=value;key=value"} STRING (canonical stat key → double),
 * mirroring {@link PdcKeys#ITEM_TOOL_ENCHANT_BONUS}'s codec so TF needs no JSON dependency. TF owns the
 * codec ({@link #encode}/{@link #parse}) and the key; the fork compiles against the TF jar and writes via
 * {@link #encode}, so writer and reader can never disagree on the format.
 *
 * <p>Fail-open by contract: an absent key, blank string, or any malformed/garbage token yields an empty
 * map (never throws), so a missing or older addon simply contributes nothing and combat is unchanged.
 * Zero and non-finite values are dropped on both encode and parse (a no-op stat need not be stored).
 * Bukkit PDC reads mean {@link #read} must run on the server main thread.
 */
public final class AddonCombatStats {

    private AddonCombatStats() {
    }

    /**
     * Encodes a canonical stat map into the compact PDC string. Keys are canonicalized; zero and
     * non-finite values are skipped (they contribute nothing). An empty or all-zero map encodes to the
     * empty string, which the writer should treat as "clear the key" so stale stats never linger.
     */
    public static String encode(Map<String, Double> stats) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Double> entry : stats.entrySet()) {
            double value = entry.getValue() == null ? 0.0 : entry.getValue();
            if (value == 0.0 || !Double.isFinite(value)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(StatKeys.canonical(entry.getKey())).append('=').append(value);
        }
        return out.toString();
    }

    /**
     * Parses the compact PDC string back into a canonical stat map. A null/blank string yields an empty
     * map; a malformed token (no {@code =}, empty key, non-numeric, non-finite, or zero value) is skipped
     * rather than aborting the whole parse. Duplicate keys sum ({@link Double#sum}).
     */
    public static Map<String, Double> parse(String raw) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String token : raw.split(";")) {
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = token.substring(0, eq).trim();
            if (key.isEmpty()) {
                continue;
            }
            double value;
            try {
                value = Double.parseDouble(token.substring(eq + 1).trim());
            } catch (NumberFormatException notNumeric) {
                continue;
            }
            if (!Double.isFinite(value) || value == 0.0) {
                continue;
            }
            out.merge(StatKeys.canonical(key), value, Double::sum);
        }
        return out;
    }

    /**
     * The player's addon combat-stat contribution (empty when unset or blank). Reads the
     * {@link PdcKeys#PLAYER_ADDON_COMBAT_STATS} STRING and {@link #parse}s it. Never throws.
     */
    public static Map<String, Double> read(Player player) {
        if (player == null) {
            return Map.of();
        }
        String raw;
        try {
            raw = player.getPersistentDataContainer()
                    .get(PdcKeys.PLAYER_ADDON_COMBAT_STATS, PersistentDataType.STRING);
        } catch (RuntimeException wrongTypeStored) {
            // Fail-open: some other writer stored a non-STRING under our key (get throws on a type
            // mismatch). Never let that abort a combat event — contribute nothing instead.
            return Map.of();
        }
        return parse(raw);
    }

    /**
     * 乗算レイヤのレイヤID。{@code PlayerCombatAggregate} は「レイヤ内は Σ(v-1) を合算し、
     * レイヤ同士は乗算」するので、アドオン由来の倍率を1レイヤに束ねるための固定ID。
     */
    public static final String MULTIPLIER_LAYER_ID = "addon";

    /**
     * The player's addon <b>multiplier</b> contribution (empty when unset or blank): canonical stat key
     * → 倍率の増分(0.1 = +10%)。{@link PdcKeys#PLAYER_ADDON_COMBAT_MULTIPLIERS} を {@link #parse} で
     * 読むだけなので、コーデックは加算チャネルと完全に共通。Never throws。
     *
     * <p>加算チャネル({@link #read})と違い、この値は総合値へ<b>掛かる</b>。フォークが
     * {@code thread-sets.yml} の {@code mode: multiply} を集計してここへ書く。
     */
    public static Map<String, Double> readMultipliers(Player player) {
        if (player == null) {
            return Map.of();
        }
        String raw;
        try {
            raw = player.getPersistentDataContainer()
                    .get(PdcKeys.PLAYER_ADDON_COMBAT_MULTIPLIERS, PersistentDataType.STRING);
        } catch (RuntimeException wrongTypeStored) {
            return Map.of();
        }
        return parse(raw);
    }
}
