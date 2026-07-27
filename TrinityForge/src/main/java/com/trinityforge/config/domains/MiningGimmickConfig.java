package com.trinityforge.config.domains;

import com.trinityforge.skilltree.effects.TierTable;
import com.trinityforge.stats.DropTableConfig;
import com.trinityforge.stats.GatheringPolicy;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.loot.LootTables;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code stats/mining-gimmick.yml}: tuning for the採掘スキルツリーflag系dedicated-effect
 * consumers that have no existing config home ({@code vein-mining}, {@code haste-active-mining} — see
 * {@code skilltree/dedicated-effects.yml}), the {@code mining} drop-table categories (2026-07-23
 * stat-gate-overhaul §4 — replaces the old {@code gacha-ticket-drop}/ancient-debris hardcoded consumers),
 * and the {@code fortune:} tuning migrated off the now-removed {@code stats/gathering.yml} (§D廃止).
 */
public final class MiningGimmickConfig {

    public static final String PATH = "stats/mining-gimmick.yml";

    private static final int DEFAULT_MAX_EXTRA_BLOCKS = 32;
    private static final int DEFAULT_HASTE_AMPLIFIER = 1;
    private static final int DEFAULT_HASTE_DURATION_TICKS = 200;
    private static final int DEFAULT_HASTE_COOLDOWN_TICKS = 600;
    private static final double DEFAULT_FORTUNE_PER_LEVEL = 0.02;
    /** GTH-04 既定値: 怪しげな砂の再湧きに使う考古学ルートテーブル(砂漠ピラミッド相当)。 */
    private static final LootTables DEFAULT_SUSPICIOUS_SAND_LOOT_TABLE = LootTables.DESERT_PYRAMID_ARCHAEOLOGY;
    /** GTH-04 既定値: 怪しげな砂利の再湧きに使う考古学ルートテーブル(遺跡歩道 common相当)。 */
    private static final LootTables DEFAULT_SUSPICIOUS_GRAVEL_LOOT_TABLE = LootTables.TRAIL_RUINS_ARCHAEOLOGY_COMMON;

    private volatile Set<Material> oreBlocks = Set.of();
    private volatile int veinMiningMaxExtraBlocks = DEFAULT_MAX_EXTRA_BLOCKS;
    private volatile int hasteAmplifier = DEFAULT_HASTE_AMPLIFIER;
    private volatile int hasteDurationTicks = DEFAULT_HASTE_DURATION_TICKS;
    private volatile int hasteCooldownTicks = DEFAULT_HASTE_COOLDOWN_TICKS;
    /** fortune連続処理が参照するスキルid。プラグイン側で固定(旧: fortune.skill-id 設定値)。 */
    private static final String FORTUNE_SKILL_ID = "MINING";

    private volatile Map<String, DropTableConfig.Category> dropTables = Map.of();
    private volatile double fortunePerLevel = DEFAULT_FORTUNE_PER_LEVEL;
    private volatile Set<Material> fortuneBlocks = Set.of();

    // GTH-04: 怪しげなブロックをAPI経由(current.setType)で再湧きさせるとルートテーブル無しになり、
    // ブラシで一切ドロップしない不具合の修正。respawn時に明示的に付け直すルートテーブル(Material別)。
    private volatile LootTables suspiciousSandLootTable = DEFAULT_SUSPICIOUS_SAND_LOOT_TABLE;
    private volatile LootTables suspiciousGravelLootTable = DEFAULT_SUSPICIOUS_GRAVEL_LOOT_TABLE;

    // --- 2026-07-25 gather-rework-active-framework §1: 任意の tier -> パラメータ表。未定義(empty)なら
    // 上のグローバルscalarへ完全後方互換フォールバックする(TierTable#resolve の isEmpty契約)。
    /** {@code vein-mining.tiers.<tier>.max-extra-blocks}。 */
    private volatile TierTable<Integer> veinMiningTiers = TierTable.empty();
    /** {@code haste-active-mining.tiers.<tier>.{amplifier,duration-ticks}}。 */
    private volatile TierTable<HasteTierValues> hasteTiers = TierTable.empty();

    /**
     * One {@code haste-active-mining.tiers.<tier>} row: amplifier/duration only — <b>NOT</b> cooldown
     * (2026-07-25 CT設計一本化: 段階(tier)はCTに一切影響させない、CTは唯一の基準値+CT短縮ステータスだけで
     * 決まる。tierごとのCT差はここから撤去済み。{@code cooldown-ticks} は
     * {@link MiningGimmickConfig#hasteCooldownTicks()}(グローバルscalar、全tier共通の唯一の基準値)を使う)。
     */
    public record HasteTierValues(int amplifier, int durationTicks) {
    }

    /** 鉱石Material一覧(vein-mining連結破壊 + gacha-ticket-1/2/3ドロップ判定 共用)。 */
    public Set<Material> oreBlocks() {
        return oreBlocks;
    }

    /** vein-mining 一括破壊の上限ブロック数(トリガーブロックを含まない)。tiers未定義時のグローバル既定値。 */
    public int veinMiningMaxExtraBlocks() {
        return veinMiningMaxExtraBlocks;
    }

    /**
     * vein-mining 一括破壊の上限ブロック数を {@code tier}(プレイヤーの解放済み最高tier、
     * {@code DedicatedEffectsConfig#valueMax} の結果)で解決する。{@code vein-mining.tiers} が
     * 未定義、または {@code tier} 未満の行しかない場合は {@link #veinMiningMaxExtraBlocks()}
     * (グローバルscalar)へ完全後方互換フォールバックする。
     */
    public int veinMiningMaxExtraBlocks(int tier) {
        return veinMiningTiers.resolve(tier).orElse(veinMiningMaxExtraBlocks);
    }

    /** haste-active-mining が付与する HASTE の amplifier。tiers未定義時のグローバル既定値。 */
    public int hasteAmplifier() {
        return hasteAmplifier;
    }

    /** haste-active-mining の持続時間(tick)。tiers未定義時のグローバル既定値。 */
    public int hasteDurationTicks() {
        return hasteDurationTicks;
    }

    /**
     * haste-active-mining のクールダウン(tick)。2026-07-25 CT設計一本化により、これが全tier共通の
     * 唯一の基準値(旧: tierごとに別値だったものを統合。tier1の値を採用— 後方互換の据え置き)。CTを
     * 短くする唯一の手段は{@link com.trinityforge.active.ActiveSkillCooldownKeys}で解決するCT短縮
     * ステータスであり、段階(tier)はもう一切関与しない。
     */
    public int hasteCooldownTicks() {
        return hasteCooldownTicks;
    }

    /** {@link #veinMiningMaxExtraBlocks(int)}と同じ floor+フォールバック則で解決する amplifier。 */
    public int hasteAmplifier(int tier) {
        return hasteTiers.resolve(tier).map(HasteTierValues::amplifier).orElse(hasteAmplifier);
    }

    /** {@link #veinMiningMaxExtraBlocks(int)}と同じ floor+フォールバック則で解決する持続時間(tick)。 */
    public int hasteDurationTicks(int tier) {
        return hasteTiers.resolve(tier).map(HasteTierValues::durationTicks).orElse(hasteDurationTicks);
    }

    // 2026-07-25 CT設計一本化: hasteCooldownTicks(int tier) は撤去した(tierはCTに一切影響しない)。
    // CTは常に hasteCooldownTicks() の1値のみ — 呼び出し側は HasteActiveSkill#cooldownMillis(int) 参照。

    /** {@code drop-tables.categories} (2026-07-23 §4): カテゴリid -&gt; 定義。ゲート/抽選は {@code DropTablePolicy} が担う。 */
    public Map<String, DropTableConfig.Category> dropTables() {
        return dropTables;
    }

    /** 採掘fortune連続処理に使うスキルid。MINING固定(旧 gathering.yml mining.skill-id / config読取は廃止)。 */
    public String fortuneSkillId() {
        return FORTUNE_SKILL_ID;
    }

    /** MINING Lv 1 あたりの追加mining-fortune期待値(旧 gathering.yml mining.fortune-per-level)。 */
    public double fortunePerLevel() {
        return fortunePerLevel;
    }

    /** fortune追加ドロップの対象になるMaterial集合(旧 gathering.yml mining.fortune-blocks)。 */
    public Set<Material> fortuneBlocks() {
        return fortuneBlocks;
    }

    /** GTH-04: SUSPICIOUS_SAND再湧き時に付与するルートテーブル。 */
    public LootTables suspiciousSandLootTable() {
        return suspiciousSandLootTable;
    }

    /** GTH-04: SUSPICIOUS_GRAVEL再湧き時に付与するルートテーブル。 */
    public LootTables suspiciousGravelLootTable() {
        return suspiciousGravelLootTable;
    }

    /** Loads (or reloads) the config. Returns true when it parsed cleanly. */
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

        this.oreBlocks = parseOreBlocks(yaml.getStringList("vein-mining.ore-blocks"), log);
        this.veinMiningMaxExtraBlocks = clampPositiveInt(
                yaml.getInt("vein-mining.max-extra-blocks", DEFAULT_MAX_EXTRA_BLOCKS),
                "vein-mining.max-extra-blocks", DEFAULT_MAX_EXTRA_BLOCKS, log);
        this.hasteAmplifier = Math.max(0,
                yaml.getInt("haste-active-mining.amplifier", DEFAULT_HASTE_AMPLIFIER));
        this.hasteDurationTicks = clampPositiveInt(
                yaml.getInt("haste-active-mining.duration-ticks", DEFAULT_HASTE_DURATION_TICKS),
                "haste-active-mining.duration-ticks", DEFAULT_HASTE_DURATION_TICKS, log);
        this.hasteCooldownTicks = clampPositiveInt(
                yaml.getInt("haste-active-mining.cooldown-ticks", DEFAULT_HASTE_COOLDOWN_TICKS),
                "haste-active-mining.cooldown-ticks", DEFAULT_HASTE_COOLDOWN_TICKS, log);
        this.veinMiningTiers = parseVeinMiningTiers(
                yaml.getConfigurationSection("vein-mining.tiers"), log);
        this.hasteTiers = parseHasteTiers(
                yaml.getConfigurationSection("haste-active-mining.tiers"), log);
        this.dropTables = DropTableConfig.parseCategories(
                yaml.getConfigurationSection("drop-tables.categories"), true, PATH, log);
        this.fortunePerLevel = clampPerLevel(
                yaml.getDouble("fortune.fortune-per-level", DEFAULT_FORTUNE_PER_LEVEL),
                "fortune.fortune-per-level", DEFAULT_FORTUNE_PER_LEVEL, log);
        this.fortuneBlocks = parseOreBlocks(yaml.getStringList("fortune.fortune-blocks"), log);
        this.suspiciousSandLootTable = parseLootTable(
                yaml.getString("suspicious-block-respawn.loot-tables.suspicious-sand"),
                "suspicious-block-respawn.loot-tables.suspicious-sand",
                DEFAULT_SUSPICIOUS_SAND_LOOT_TABLE, log);
        this.suspiciousGravelLootTable = parseLootTable(
                yaml.getString("suspicious-block-respawn.loot-tables.suspicious-gravel"),
                "suspicious-block-respawn.loot-tables.suspicious-gravel",
                DEFAULT_SUSPICIOUS_GRAVEL_LOOT_TABLE, log);

        log.info("[" + PATH + "] loaded " + this.oreBlocks.size() + " ore-block(s), "
                + this.dropTables.size() + " drop-table categor(y/ies) OK");
        return true;
    }

    /** Same non-finite/clamp guard as {@code GatheringConfig} used for its per-level tunings. */
    private static double clampPerLevel(double raw, String key, double fallback, Logger log) {
        if (!Double.isFinite(raw)) {
            log.warning("[" + PATH + "] '" + key + "' is not a finite number (" + raw
                    + "); using default " + fallback);
            return fallback;
        }
        double clamped = Math.max(0.0, Math.min(raw, GatheringPolicy.MAX_EXTRA));
        if (clamped != raw) {
            log.warning("[" + PATH + "] '" + key + "' = " + raw + " is out of range; clamped to " + clamped);
        }
        return clamped;
    }

    private static Set<Material> parseOreBlocks(List<String> names, Logger log) {
        Set<Material> parsed = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Material material = Material.matchMaterial(name.trim());
            if (material == null) {
                log.warning("[" + PATH + "] '" + name + "' is not a valid Material; skipped");
                continue;
            }
            parsed.add(material);
        }
        return Set.copyOf(parsed);
    }

    /**
     * GTH-04: {@code org.bukkit.loot.LootTables} 列挙名(例: {@code DESERT_PYRAMID_ARCHAEOLOGY})を解決する。
     * 未設定/不正値は{@code fallback}へフォールバックし警告のみ(他の設定値と同じくthrowしない)。
     */
    private static LootTables parseLootTable(String raw, String key, LootTables fallback, Logger log) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LootTables.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warning("[" + PATH + "] '" + key + "' = '" + raw
                    + "' is not a valid org.bukkit.loot.LootTables constant; using default " + fallback);
            return fallback;
        }
    }

    /** Non-finite/non-positive guard for a tick/count value: falls back to {@code fallback}, never throws. */
    private static int clampPositiveInt(int raw, String key, int fallback, Logger log) {
        if (raw <= 0) {
            log.warning("[" + PATH + "] '" + key + "' must be > 0 (was " + raw + "); using default " + fallback);
            return fallback;
        }
        return raw;
    }

    /**
     * {@code vein-mining.tiers: {<tier>: {max-extra-blocks: N}}} (2026-07-25 §1). Absent/empty section
     * yields {@link TierTable#empty()} (§1 item 2 完全後方互換). A tier key that is not a positive integer,
     * or a row missing/with a non-positive {@code max-extra-blocks}, is skipped with a warning.
     */
    private static TierTable<Integer> parseVeinMiningTiers(ConfigurationSection section, Logger log) {
        if (section == null) {
            return TierTable.empty();
        }
        Map<Integer, Integer> rows = new LinkedHashMap<>();
        for (String tierKey : section.getKeys(false)) {
            Integer tier = parseTierKey(tierKey, "vein-mining.tiers", log);
            if (tier == null) {
                continue;
            }
            ConfigurationSection row = section.getConfigurationSection(tierKey);
            int maxExtraBlocks = row == null ? 0 : row.getInt("max-extra-blocks", 0);
            if (maxExtraBlocks <= 0) {
                log.warning("[" + PATH + "] 'vein-mining.tiers." + tierKey
                        + ".max-extra-blocks' must be > 0; row skipped");
                continue;
            }
            rows.put(tier, maxExtraBlocks);
        }
        return TierTable.of(rows);
    }

    /**
     * {@code haste-active-mining.tiers: {<tier>: {amplifier, duration-ticks}}} (2026-07-25 §1/§6 Q3、
     * 2026-07-25 CT設計一本化で {@code cooldown-ticks} をここから撤去 — 段階はCTに一切影響させない設計
     * 決定のため、tier行の {@code cooldown-ticks} は読まれない。CTの基準値は
     * {@code haste-active-mining.cooldown-ticks}(グローバルscalar、{@link #hasteCooldownTicks()})の
     * 1本のみ)。Absent/empty section yields {@link TierTable#empty()}. A row with a non-positive
     * {@code duration-ticks} is skipped with a warning ({@code amplifier} may legitimately be 0).
     */
    private static TierTable<HasteTierValues> parseHasteTiers(ConfigurationSection section, Logger log) {
        if (section == null) {
            return TierTable.empty();
        }
        Map<Integer, HasteTierValues> rows = new LinkedHashMap<>();
        for (String tierKey : section.getKeys(false)) {
            Integer tier = parseTierKey(tierKey, "haste-active-mining.tiers", log);
            if (tier == null) {
                continue;
            }
            ConfigurationSection row = section.getConfigurationSection(tierKey);
            if (row == null) {
                log.warning("[" + PATH + "] 'haste-active-mining.tiers." + tierKey + "' is not a map; row skipped");
                continue;
            }
            int amplifier = Math.max(0, row.getInt("amplifier", DEFAULT_HASTE_AMPLIFIER));
            int durationTicks = row.getInt("duration-ticks", 0);
            if (durationTicks <= 0) {
                log.warning("[" + PATH + "] 'haste-active-mining.tiers." + tierKey
                        + "' duration-ticks must be > 0; row skipped");
                continue;
            }
            rows.put(tier, new HasteTierValues(amplifier, durationTicks));
        }
        return TierTable.of(rows);
    }

    /** Shared tier-key parser: a positive integer, or {@code null} (warned) otherwise. */
    private static Integer parseTierKey(String tierKey, String sectionPath, Logger log) {
        try {
            int tier = Integer.parseInt(tierKey.trim());
            if (tier <= 0) {
                log.warning("[" + PATH + "] '" + sectionPath + "." + tierKey + "' key must be a positive integer; skipped");
                return null;
            }
            return tier;
        } catch (NumberFormatException ex) {
            log.warning("[" + PATH + "] '" + sectionPath + "." + tierKey + "' key is not an integer; skipped");
            return null;
        }
    }
}
