package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.mobs.LevelTierDropEntry;
import com.trinityforge.mobs.LevelTierRule;
import com.trinityforge.mobs.MobLevelBandTable;
import com.trinityforge.mobs.MobTargetFilter;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code combat/mob-level-table.yml}: モブのレベル帯ごとにドロップ削除/追加/バニラEXP/
 * ダンジョン限定を設定するレベルテーブル(2026-07-25 要望)。{@code tiers:} が空(既定)なら
 * {@link #resolve(int)} は常に {@link Optional#empty()} を返し、既存挙動を完全維持する(後方互換)。
 *
 * <p>フィールドモブ({@code combat/mob-types.yml} 由来)・ダンジョンモブ(EliteMobs輸入、
 * {@code combat/mob-profiles.yml} 由来)のどちらにも同じ表を適用する。両者とも {@code MobData#level()}
 * が読める(PDCの {@code MOB_LEVEL})ため、このconfigはレベル値だけを見てEntityType/由来を問わない
 * (適用可否は呼び出し側リスナーが {@code MobData#hasProfile()} で判定する)。2026-07-25 レベルテーブルの
 * モブ別ドロップ指定拡張: この「レベル帯」自体の適用条件はそのままに、{@code add-drops} の各エントリだけが
 * 任意で {@code mobs:}({@link EntityType} 一覧)による絞り込みを持てるようになった
 * ({@link com.trinityforge.mobs.LevelTierDropEntry#appliesTo}) — 未指定なら従来どおり全モブに適用される。
 *
 * <p>{@link #parse(ConfigurationSection, Logger)} は {@link #load(Plugin)} と分離し、
 * {@code Plugin} 無しでYAMLを直接テストできるようにする({@code MobTypesConfig}/{@code MobImportConfig}
 * と同じ流儀)。
 *
 * <p>2026-07-27 {@code no-skill-exp-mobs}(牧場対策): このトップレベルリストに載った
 * {@link EntityType} は、TrinityForge が独自に付与する戦闘スキルEXP(武器・魔法=討伐、
 * 弓術=命中、防具=被弾)を一切加算しない({@link #suppressesSkillExp(EntityType)})。バニラの
 * {@code org.bukkit.event.entity.EntityDeathEvent#setDroppedExp(int)}(EXPオーブ)には一切触れない —
 * エンチャント等の用途があるバニラEXP自体は従来どおり落ちてよい、という
 * ユーザー判断による(この config/クラス自身はEXPオーブを一切扱わない)。実際の抑止判定は
 * {@code CombatListener}(武器・弓術)/{@code NativeSkillExperienceListener}(防具)/
 * {@code ArsMagicExperienceListener}(魔法)側が呼び出す。
 */
public final class MobLevelTableConfig implements LoadableConfig {

    public static final String PATH = "combat/mob-level-table.yml";
    private static final String TIERS = "tiers";

    private volatile boolean dungeonOnly = false;
    private volatile MobLevelBandTable<LevelTierRule> tiers = MobLevelBandTable.empty();
    private volatile Set<EntityType> noSkillExpMobs = Set.of();

    /** true = このテーブルのルール全体を、ダンジョンインスタンスワールド内の討伐でのみ適用する。 */
    public boolean dungeonOnly() {
        return dungeonOnly;
    }

    /** レベル帯の floor lookup。{@code tiers:} 未設定/該当帯なしなら常に {@link Optional#empty()}。 */
    public Optional<LevelTierRule> resolve(int level) {
        return tiers.resolve(level);
    }

    /**
     * {@code no-skill-exp-mobs}(2026-07-27 牧場対策)。true なら、このEntityTypeを相手にした
     * TrinityForgeの戦闘スキルEXP(軽・重武器/魔法=討伐、弓術=命中、防具=被弾)を
     * 一切加算しない。
     * バニラのEXPオーブ(討伐/エンチャント等)には一切影響しない — {@code MobLevelTableListener} は
     * この値を読まない。{@code dungeon-only-exp}/{@code outside-dungeon-exp-rate}(stats/skill-exp.yml)
     * のゲートとは独立に、常に効く(牧場はダンジョン外にあるため、ダンジョン限定にすると意味がない)。
     * 省略/空リストなら何もしない(完全な後方互換)。
     */
    public boolean suppressesSkillExp(EntityType type) {
        return noSkillExpMobs.contains(type);
    }

    /** {@code no-skill-exp-mobs} の不変コピー(パース時点で既に不変集合)。 */
    public Set<EntityType> noSkillExpMobs() {
        return noSkillExpMobs;
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
        // 構文エラー時は直前に成功ロード済みの状態を維持しfalseを返す。
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }

        ParseResult result = parse(yaml, log);
        this.dungeonOnly = result.dungeonOnly();
        this.tiers = result.tiers();
        this.noSkillExpMobs = result.noSkillExpMobs();
        if (result.skipped() > 0) {
            log.warning("[" + PATH + "] loaded " + result.tierCount() + " level band(s), "
                    + result.skipped() + " skipped");
            return false;
        }
        log.info("[" + PATH + "] loaded " + result.tierCount() + " level band(s) OK");
        return true;
    }

    /** Pure parse. Invalid bands/drops are skipped, never fatal (mirrors {@code MobTypesConfig}). */
    static ParseResult parse(ConfigurationSection root, Logger log) {
        boolean dungeonOnly = root != null && root.getBoolean("dungeon-only", false);
        Map<Integer, LevelTierRule> parsed = new LinkedHashMap<>();
        int skipped = 0;
        List<Map<?, ?>> rawTiers = root == null ? List.of() : root.getMapList(TIERS);
        for (Map<?, ?> raw : rawTiers) {
            Object minLevelRaw = raw.get("min-level");
            if (!(minLevelRaw instanceof Number number)) {
                log.warning("[" + PATH + "] tier entry missing numeric 'min-level'; skipped");
                skipped++;
                continue;
            }
            int minLevel = number.intValue();
            if (minLevel < 0) {
                log.warning("[" + PATH + "] tier 'min-level' must be >= 0 (was " + minLevel + "); skipped");
                skipped++;
                continue;
            }
            if (parsed.containsKey(minLevel)) {
                log.warning("[" + PATH + "] duplicate min-level " + minLevel + "; earlier entry kept, this one skipped");
                skipped++;
                continue;
            }
            RemoveDropsResult removeResult = parseRemoveDrops(raw.get("remove-drops"), minLevel, log);
            skipped += removeResult.skipped();
            AddDropsResult addResult = parseAddDrops(raw.get("add-drops"), minLevel, log);
            skipped += addResult.skipped();
            Integer vanillaExp = parseVanillaExp(raw.get("vanilla-exp"), minLevel, log);
            // 2026-07-26: 帯そのものの適用対象モブ。add-drops 各エントリの同名キーとは別レイヤで、
            // ここで弾かれた討伐には remove-drops / add-drops / vanilla-exp のどれも適用されない。
            MobFilterResult bandFilter = parseTargetFilter(raw.get("mobs"), raw.get("mob-ids"),
                    "min-level=" + minLevel + " (band)", log);
            skipped += bandFilter.skipped();
            try {
                parsed.put(minLevel, new LevelTierRule(removeResult.materials(), addResult.drops(), vanillaExp,
                        bandFilter.targets()));
            } catch (IllegalArgumentException ex) {
                log.warning("[" + PATH + "] tier min-level=" + minLevel + " invalid (" + ex.getMessage()
                        + "); skipped");
                skipped++;
            }
        }
        NoSkillExpMobsResult noSkillExpResult = parseNoSkillExpMobs(root, log);
        skipped += noSkillExpResult.skipped();
        return new ParseResult(dungeonOnly, MobLevelBandTable.of(parsed), skipped, parsed.size(),
                noSkillExpResult.types());
    }

    /**
     * Parses the top-level {@code no-skill-exp-mobs} list (2026-07-27 牧場対策)。省略/未設定なら
     * 空集合(何もしない、後方互換)。不明な {@link EntityType} は警告してスキップし、他のエントリの
     * 読み込みは継続する({@code mobs:}/{@code remove-drops} と同じ fail-soft 方針)。大文字小文字は
     * 正規化して受け付ける。
     */
    private static NoSkillExpMobsResult parseNoSkillExpMobs(ConfigurationSection root, Logger log) {
        Set<EntityType> types = new LinkedHashSet<>();
        if (root == null) {
            return new NoSkillExpMobsResult(Set.of(), 0);
        }
        List<?> raw = root.getList("no-skill-exp-mobs");
        if (raw == null) {
            return new NoSkillExpMobsResult(Set.of(), 0);
        }
        int skipped = 0;
        for (Object entry : raw) {
            String name = entry == null ? null : String.valueOf(entry).trim();
            if (name == null || name.isBlank()) {
                log.warning("[" + PATH + "] no-skill-exp-mobs has a blank entry; skipped");
                skipped++;
                continue;
            }
            try {
                types.add(EntityType.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                log.warning("[" + PATH + "] no-skill-exp-mobs entry '" + name
                        + "' is not a valid EntityType; skipped");
                skipped++;
            }
        }
        return new NoSkillExpMobsResult(Set.copyOf(types), skipped);
    }

    private static RemoveDropsResult parseRemoveDrops(Object raw, int minLevel, Logger log) {
        List<Material> materials = new ArrayList<>();
        int skipped = 0;
        if (raw == null) {
            return new RemoveDropsResult(materials, 0);
        }
        if (!(raw instanceof List<?> list)) {
            log.warning("[" + PATH + "] min-level=" + minLevel + " remove-drops must be a list; skipped");
            return new RemoveDropsResult(materials, 1);
        }
        for (Object item : list) {
            String name = item == null ? null : String.valueOf(item).trim();
            if (name == null || name.isBlank()) {
                log.warning("[" + PATH + "] min-level=" + minLevel + " remove-drops has a blank entry; skipped");
                skipped++;
                continue;
            }
            try {
                materials.add(Material.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                log.warning("[" + PATH + "] min-level=" + minLevel + " remove-drops material '" + name
                        + "' invalid; skipped");
                skipped++;
            }
        }
        return new RemoveDropsResult(materials, skipped);
    }

    private static final String CUSTOM_PREFIX = "custom:";

    /**
     * Parses each {@code add-drops} entry's item as a vanilla {@link Material} OR (2026-07-25 レベル
     * テーブルのモブ別ドロップ指定拡張) a {@code custom:<catalogId>} token — same {@code custom:} prefix
     * convention {@code com.trinityforge.stats.RecipeIngredient}/{@code ItemCatalogConfig} already use.
     * The catalog id itself is only resolved at drop-roll time by {@code MobLevelTableListener}
     * ({@code com.trinityforge.stats.CrossPluginItemResolver}) — an unresolvable id therefore never
     * fails config load/startup (2-B ⚠️ requirement), it just logs a warning and skips that roll later.
     * Also parses the optional {@code mobs:} EntityType filter (2-A) — empty/absent = every mob
     * (back-compat).
     */
    private static AddDropsResult parseAddDrops(Object raw, int minLevel, Logger log) {
        List<LevelTierDropEntry> drops = new ArrayList<>();
        int skipped = 0;
        if (raw == null) {
            return new AddDropsResult(drops, 0);
        }
        if (!(raw instanceof List<?> list)) {
            log.warning("[" + PATH + "] min-level=" + minLevel + " add-drops must be a list; skipped");
            return new AddDropsResult(drops, 1);
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                log.warning("[" + PATH + "] min-level=" + minLevel + " add-drops has a non-mapping entry; skipped");
                skipped++;
                continue;
            }
            Object materialRaw = map.get("material");
            if (materialRaw == null) {
                log.warning("[" + PATH + "] min-level=" + minLevel + " add-drops entry missing material; skipped");
                skipped++;
                continue;
            }
            String token = String.valueOf(materialRaw).trim();
            Material material = null;
            String catalogId = null;
            if (token.regionMatches(true, 0, CUSTOM_PREFIX, 0, CUSTOM_PREFIX.length())) {
                catalogId = token.substring(CUSTOM_PREFIX.length()).trim();
                if (catalogId.isEmpty()) {
                    log.warning("[" + PATH + "] min-level=" + minLevel + " add-drops 'custom:' id must not be "
                            + "blank; skipped");
                    skipped++;
                    continue;
                }
            } else {
                try {
                    material = Material.valueOf(token.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    log.warning("[" + PATH + "] min-level=" + minLevel + " add-drops material '" + materialRaw
                            + "' invalid; skipped");
                    skipped++;
                    continue;
                }
            }
            String label = catalogId != null ? token : material.name();
            MobFilterResult mobFilter = parseTargetFilter(map.get("mobs"), map.get("mob-ids"),
                    "min-level=" + minLevel + " add-drops for " + label, log);
            skipped += mobFilter.skipped();
            Set<String> roles = parseRoleFilter(map.get("roles"),
                    "min-level=" + minLevel + " add-drops for " + label, log);
            try {
                double chance = clamp01(requireDouble(map, "chance", minLevel, label, log));
                int min = requireInt(map, "min", minLevel, label, log);
                int max = requireInt(map, "max", minLevel, label, log);
                drops.add(catalogId != null
                        ? LevelTierDropEntry.ofCatalog(catalogId, chance, min, max, mobFilter.targets(), roles)
                        : LevelTierDropEntry.ofMaterial(material, chance, min, max, mobFilter.targets(), roles));
            } catch (IllegalArgumentException ex) {
                log.warning("[" + PATH + "] min-level=" + minLevel + " add-drops for " + label + " invalid ("
                        + ex.getMessage() + "); skipped");
                skipped++;
            }
        }
        return new AddDropsResult(drops, skipped);
    }

    /**
     * {@code roles:} — キルしたプレイヤーの職業でこのエントリを絞る(2026-08-02 柱7)。
     * 未指定/空なら空集合＝職業を問わない(後方互換)。IDは {@code progression/role-buffs.yml} の
     * キーと同じ正規化(小文字・前後空白除去)で持つ — 揃えないと「Farmer」と書いた yml が黙って外れる。
     *
     * <p>存在しない職業IDかどうかはここでは判定しない。role-buffs.yml は別ドメイン設定で
     * ロード順が保証されないため、ここで弾くと「順番次第で消える」不安定な挙動になる。
     */
    private static Set<String> parseRoleFilter(Object rolesRaw, String context, Logger log) {
        if (rolesRaw == null) {
            return Set.of();
        }
        if (!(rolesRaw instanceof List<?> list)) {
            log.warning("[" + PATH + "] " + context + " 'roles' must be a list; ignored");
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (Object entry : list) {
            String name = entry == null ? null : String.valueOf(entry).trim();
            if (name == null || name.isBlank()) {
                log.warning("[" + PATH + "] " + context + " has a blank 'roles' entry; skipped");
                continue;
            }
            roles.add(name.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(roles);
    }

    /**
     * The {@code mobs:} (EntityType) + {@code mob-ids:} (EliteMobsモブid) target filter, used both at
     * band level (2026-07-26 「レベルテーブルを付けるモブを指定できない」) and per {@code add-drops}
     * entry (2026-07-25 2-A). Both absent/empty = every mob (back-compat).
     *
     * @param context human-readable location for warnings, e.g. {@code "min-level=10 add-drops for BONE"}
     */
    private static MobFilterResult parseTargetFilter(Object mobsRaw, Object mobIdsRaw, String context,
                                                      Logger log) {
        if (mobsRaw == null && mobIdsRaw == null) {
            return new MobFilterResult(MobTargetFilter.EMPTY, 0);
        }
        int skipped = 0;
        Set<EntityType> types = new LinkedHashSet<>();
        if (mobsRaw != null) {
            if (!(mobsRaw instanceof List<?> list)) {
                log.warning("[" + PATH + "] " + context + " 'mobs' must be a list; ignored");
                skipped++;
            } else {
                for (Object entry : list) {
                    String name = entry == null ? null : String.valueOf(entry).trim();
                    if (name == null || name.isBlank()) {
                        log.warning("[" + PATH + "] " + context + " has a blank 'mobs' entry; skipped");
                        skipped++;
                        continue;
                    }
                    try {
                        types.add(EntityType.valueOf(name.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ex) {
                        log.warning("[" + PATH + "] " + context + " mobs entry '" + name
                                + "' is not a valid EntityType; skipped");
                        skipped++;
                    }
                }
            }
        }
        Set<String> ids = new LinkedHashSet<>();
        if (mobIdsRaw != null) {
            if (!(mobIdsRaw instanceof List<?> list)) {
                log.warning("[" + PATH + "] " + context + " 'mob-ids' must be a list; ignored");
                skipped++;
            } else {
                for (Object entry : list) {
                    String id = entry == null ? null : String.valueOf(entry).trim();
                    if (id == null || id.isBlank()) {
                        log.warning("[" + PATH + "] " + context + " has a blank 'mob-ids' entry; skipped");
                        skipped++;
                        continue;
                    }
                    ids.add(id);
                }
            }
        }
        // MobTargetFilter.of が MobIdNormalizer を通す(裸id/.yml付きの表記ゆれを吸収する)。
        return new MobFilterResult(MobTargetFilter.of(types, ids), skipped);
    }

    private static Integer parseVanillaExp(Object raw, int minLevel, Logger log) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number)) {
            log.warning("[" + PATH + "] min-level=" + minLevel + " vanilla-exp must be numeric; ignored");
            return null;
        }
        int value = number.intValue();
        if (value < 0) {
            log.warning("[" + PATH + "] min-level=" + minLevel + " vanilla-exp must be >= 0; ignored");
            return null;
        }
        return value;
    }

    private static double requireDouble(Map<?, ?> raw, String field, int minLevel, String itemLabel, Logger log) {
        Object value = raw.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("'" + field + "' is required and must be numeric for min-level="
                    + minLevel + "/" + itemLabel);
        }
        return number.doubleValue();
    }

    private static int requireInt(Map<?, ?> raw, String field, int minLevel, String itemLabel, Logger log) {
        Object value = raw.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("'" + field + "' is required and must be numeric for min-level="
                    + minLevel + "/" + itemLabel);
        }
        return number.intValue();
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Parse outcome: dungeon-only flag, the immutable band table, and how many bands/drops were skipped. */
    record ParseResult(boolean dungeonOnly, MobLevelBandTable<LevelTierRule> tiers, int skipped, int tierCount,
                        Set<EntityType> noSkillExpMobs) {
    }

    private record RemoveDropsResult(List<Material> materials, int skipped) {
    }

    private record AddDropsResult(List<LevelTierDropEntry> drops, int skipped) {
    }

    private record MobFilterResult(MobTargetFilter targets, int skipped) {
    }

    private record NoSkillExpMobsResult(Set<EntityType> types, int skipped) {
    }
}
