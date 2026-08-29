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
            putToken(out, token);
        }
        return out;
    }

    /**
     * 1トークン({@code "key=value"})を解釈して {@code into} へ足す。壊れたトークン(= が無い、キーが
     * 空、数値でない、非有限、ゼロ)は黙って捨てる —— 1件の破損で残り全部を落とさないため。
     * {@link #parse} と {@link #parseLayered} でトークンの解釈規則を共有するための切り出し。
     */
    private static void putToken(Map<String, Double> into, String token) {
        int eq = token.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String key = token.substring(0, eq).trim();
        if (key.isEmpty()) {
            return;
        }
        double value;
        try {
            value = Double.parseDouble(token.substring(eq + 1).trim());
        } catch (NumberFormatException notNumeric) {
            return;
        }
        if (!Double.isFinite(value) || value == 0.0) {
            return;
        }
        into.merge(StatKeys.canonical(key), value, Double::sum);
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
     * 乗算レイヤの<b>既定</b>レイヤID。{@code PlayerCombatAggregate} は「レイヤ内は Σ(v-1) を合算し、
     * レイヤ同士は乗算」する。{@code thread-sets.yml} の乗算ステが {@code layer:} を書かなかったときの
     * 受け皿で、どのレイヤにも属さない倍率をここ1本に束ねる。
     *
     * <p><b>2026-08-22(W-186)まではアドオン倍率が全部このIDへ固定で入っていた。</b>
     * 現在は {@code layer:} で {@code stats/lore.yml} の {@code multiplier-layers}(layer_1 など)を
     * 名指しでき、名指しした倍率は装備側の同レイヤと<b>同じレイヤの中で加算合流</b>する
     * (別レイヤ扱いの掛け算にはならない)。スレッドと装備の攻撃力%が二重に乗るのを避けるための変更。
     */
    public static final String MULTIPLIER_LAYER_ID = "addon";

    /**
     * レイヤIDとステキーの区切り文字。{@code "layer_1@attack-power=0.25"}。
     * canonical なステキー(英小文字とハイフン)にもレイヤID(英数字とアンダースコア)にも現れない。
     */
    private static final char LAYER_SEPARATOR = '@';

    /**
     * レイヤ付き倍率マップ(レイヤID → canonicalステキー → 倍率の<b>増分</b>。0.1 = +10%)を
     * {@code "layer@key=value;layer@key=value"} へ符号化する。ゼロ・非有限値を捨てる規則は
     * {@link #encode} と同じ。レイヤIDが null/空白なら {@link #MULTIPLIER_LAYER_ID} を使う。
     */
    public static String encodeLayered(Map<String, Map<String, Double>> byLayer) {
        StringBuilder out = new StringBuilder();
        if (byLayer == null) {
            return "";
        }
        for (Map.Entry<String, Map<String, Double>> layer : byLayer.entrySet()) {
            if (layer.getValue() == null) {
                continue;
            }
            String layerId = layer.getKey() == null || layer.getKey().isBlank()
                    ? MULTIPLIER_LAYER_ID : layer.getKey().trim();
            for (Map.Entry<String, Double> entry : layer.getValue().entrySet()) {
                double value = entry.getValue() == null ? 0.0 : entry.getValue();
                if (value == 0.0 || !Double.isFinite(value)) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append(';');
                }
                out.append(layerId).append(LAYER_SEPARATOR)
                        .append(StatKeys.canonical(entry.getKey())).append('=').append(value);
            }
        }
        return out.toString();
    }

    /**
     * {@link #encodeLayered} の逆。壊れたトークンは1件ずつ捨て、全体は落とさない。
     *
     * <p><b>{@code @} を含まないトークンは {@link #MULTIPLIER_LAYER_ID} へ落とす。</b>
     * PDC はサーバ再起動をまたいでプレイヤーに残るので、レイヤ導入前(2026-08-22 以前)に書かれた
     * {@code "key=value"} 形式をそのまま読めないと、古い値を持ったままのプレイヤーだけ倍率が
     * 無言で消える。フォークが次に装備を再計算した時点で新形式へ上書きされる。
     */
    public static Map<String, Map<String, Double>> parseLayered(String raw) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String token : raw.split(";")) {
            int at = token.indexOf(LAYER_SEPARATOR);
            String layerId = MULTIPLIER_LAYER_ID;
            String rest = token;
            if (at >= 0) {
                String head = token.substring(0, at).trim();
                if (!head.isEmpty()) {
                    layerId = head;
                }
                rest = token.substring(at + 1);
            }
            Map<String, Double> layer = out.computeIfAbsent(layerId, k -> new LinkedHashMap<>());
            putToken(layer, rest);
        }
        out.values().removeIf(Map::isEmpty);
        return out;
    }

    /**
     * The player's addon <b>multiplier</b> contribution (empty when unset or blank):
     * レイヤID → canonicalステキー → 倍率の増分(0.1 = +10%)。
     * {@link PdcKeys#PLAYER_ADDON_COMBAT_MULTIPLIERS} を {@link #parseLayered} で読む。Never throws。
     *
     * <p>加算チャネル({@link #read})と違い、この値は加算合算が終わった総合値へ<b>掛かる</b>。
     * フォークが {@code thread-sets.yml} の {@code mode: multiply} を集計してここへ書く。
     */
    public static Map<String, Map<String, Double>> readLayeredMultipliers(Player player) {
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
        return parseLayered(raw);
    }
}
