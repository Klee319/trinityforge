package com.trinityforge.config.domains;

import com.trinityforge.combat.DamageType;
import com.trinityforge.combat.MobAbility;
import com.trinityforge.config.LoadableConfig;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code combat/mob-abilities.yml} のローダー（2026-07-31）。
 *
 * <p>敵の特殊攻撃を「テンプレート」として定義し、{@code combat/mob-overrides.yml} の
 * {@code abilities: [id, ...]} から参照する。テンプレート方式にした理由は
 * <b>モブが 396 体あるから</b> — 個別に攻撃を書き下すと yml が破裂するし、バランス調整のときに
 * 396 箇所を直すことになる。テンプレート側の数値を1つ変えれば全体に効く形にしてある。
 *
 * <p>壊れたエントリは警告つきで<b>そのエントリだけ</b>捨てる（他のアチーブメント/モブ設定と同じ方針）。
 * ロード全体を失敗させると「1つの typo で全モブの特殊攻撃が消える」ため。
 */
public final class MobAbilitiesConfig implements LoadableConfig {

    public static final String PATH = "combat/mob-abilities.yml";

    private volatile Map<String, MobAbility> abilities = Map.of();
    private volatile boolean enabled = true;
    private volatile int checkIntervalTicks = 20;

    /** テンプレートID→定義。未定義IDの参照は {@code null} を返す（呼び出し側が読み飛ばす）。 */
    public MobAbility ability(String id) {
        if (id == null) {
            return null;
        }
        return abilities.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** 全テンプレート（editor の候補生成とテスト用）。 */
    public Map<String, MobAbility> abilities() {
        return abilities;
    }

    /** 機能全体のスイッチ。false なら周期タスクそのものを回さない。 */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 発動判定を回す間隔（tick）。<b>短くするとサーバ負荷が直線的に増える</b>ので下限 5 tick で丸める。
     * 判定間隔とクールダウンは別物で、ここは「抽選をどれだけ細かく行うか」だけを決める。
     */
    public int checkIntervalTicks() {
        return checkIntervalTicks;
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
        this.enabled = yaml.getBoolean("enabled", true);
        this.checkIntervalTicks = Math.max(5, Math.min(200, yaml.getInt("check-interval-ticks", 20)));

        ParseResult result = parse(yaml.getConfigurationSection("abilities"), log);
        this.abilities = result.abilities();
        if (result.skipped() > 0) {
            log.warning("[" + PATH + "] loaded " + result.abilities().size()
                    + " ability template(s), " + result.skipped() + " skipped");
            return false;
        }
        log.info("[" + PATH + "] loaded " + result.abilities().size() + " ability template(s) OK");
        return true;
    }

    /** ヘッドレスにテストできる純パース。 */
    public static ParseResult parse(ConfigurationSection root, Logger log) {
        Map<String, MobAbility> parsed = new LinkedHashMap<>();
        int skipped = 0;
        if (root != null) {
            for (String rawId : root.getKeys(false)) {
                ConfigurationSection entry = root.getConfigurationSection(rawId);
                String id = rawId.trim().toLowerCase(Locale.ROOT);
                if (entry == null) {
                    log.warning("[" + PATH + "] ability '" + rawId + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                MobAbility.Type type = parseType(entry.getString("type"));
                if (type == null) {
                    log.warning("[" + PATH + "] ability '" + rawId + "' has invalid type ("
                            + typeNames() + "); skipped");
                    skipped++;
                    continue;
                }
                parsed.put(id, new MobAbility(id,
                        entry.getString("display-name", ""),
                        type,
                        parseDamageType(entry.getString("damage-type")),
                        entry.getDouble("damage-percent", 1.0),
                        entry.getDouble("cooldown-seconds", 10.0),
                        entry.getDouble("chance", 0.3),
                        entry.getDouble("range", 16.0),
                        entry.getDouble("radius", 4.0),
                        entry.getInt("count", 1),
                        entry.getDouble("spread-degrees", 45.0),
                        entry.getString("projectile", ""),
                        entry.getString("summon-type", ""),
                        entry.getDouble("duration-seconds", 0.0),
                        entry.getDouble("knockback", 0.0),
                        parseEffects(entry.getMapList("effects")),
                        entry.getString("particle", ""),
                        entry.getInt("particle-count", 0),
                        entry.getString("sound", "")));
            }
        }
        return new ParseResult(Map.copyOf(parsed), skipped);
    }

    private static List<MobAbility.EffectSpec> parseEffects(List<Map<?, ?>> raw) {
        List<MobAbility.EffectSpec> out = new ArrayList<>();
        for (Map<?, ?> map : raw) {
            Object type = map.get("type");
            if (type == null || String.valueOf(type).isBlank()) {
                continue;
            }
            out.add(new MobAbility.EffectSpec(String.valueOf(type),
                    toDouble(map.get("duration-seconds"), 3.0),
                    (int) toDouble(map.get("amplifier"), 0.0)));
        }
        return out;
    }

    private static double toDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static MobAbility.Type parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MobAbility.Type.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * {@code damage-type} は物理/魔法のみ。{@code TYPELESS} を許すと防御を一切通さない
     * 「即死級の抜け道」になるので、未知の値は物理へ寄せる。
     */
    private static DamageType parseDamageType(String raw) {
        if (raw != null && raw.trim().equalsIgnoreCase("magical")) {
            return DamageType.MAGICAL;
        }
        return DamageType.PHYSICAL;
    }

    private static String typeNames() {
        StringBuilder sb = new StringBuilder();
        for (MobAbility.Type type : MobAbility.Type.values()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(type.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /** @param abilities id→定義 / @param skipped 壊れていて捨てたエントリ数 */
    public record ParseResult(Map<String, MobAbility> abilities, int skipped) {
    }
}
