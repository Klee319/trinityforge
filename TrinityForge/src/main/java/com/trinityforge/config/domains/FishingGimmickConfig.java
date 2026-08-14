package com.trinityforge.config.domains;

import com.trinityforge.stats.DropTableConfig;
import com.trinityforge.stats.GatheringPolicy;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
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
 * Loader for {@code stats/fishing-gimmick.yml}: tuning for the釣りスキルツリーB-alpha/B-beta系
 * flag/percent dedicated-effect consumers that have no existing config home
 * ({@code junk-to-scrap}, {@code fish-sell-toggle} — see the {@code dedicated-effects:} field on each
 * node in {@code skilltree/*.yml} / {@code skilltree/fishing.yml}), the {@code fishing} group-ratio/
 * drop-table mechanism (2026-07-23 stat-gate-overhaul §2.3/§4 — replaces the old
 * {@code gacha-ticket-drop} hardcoded consumers), and the {@code fishing.skill-id/luck-per-level/
 * bonus-per-level} tuning migrated off the now-removed {@code stats/gathering.yml} (§D廃止). The legacy
 * top-level {@code junk-materials}/{@code treasure-materials} lists are kept as a fallback ONLY for when
 * {@code fishing.groups} is absent/empty (design doc §4 fallback note) — once the groups are populated,
 * the drop table is authoritative.
 *
 * <p><b>2026-08-15</b>: {@code xp-bottle-store-unlock}(エンチャントツリー「EXPフリーザー」の機能)の
 * 数値設定 {@code xp-bottle-store} は {@link CraftingFeaturesConfig} へ移設した(釣りとは無関係な設定が
 * このファイルに置かれていたため)。{@link #load} はこのセクションがまだ残っていたら警告のみ出す
 * (逆方向の読み取りフォールバックは実装しない)。
 */
public final class FishingGimmickConfig {

    public static final String PATH = "stats/fishing-gimmick.yml";

    private static final double DEFAULT_TREASURE_PERCENT = 15.0;
    private static final double DEFAULT_JUNK_PERCENT = 10.0;
    private static final double DEFAULT_LUCK_PER_LEVEL = 0.005;
    private static final double DEFAULT_BONUS_PER_LEVEL = 0.02;

    private volatile Set<Material> junkMaterials = Set.of();
    private volatile Set<Material> treasureMaterials = Set.of();
    private volatile double treasurePercent = DEFAULT_TREASURE_PERCENT;
    private volatile double junkPercent = DEFAULT_JUNK_PERCENT;
    /** fishing-luck/-bonus連続処理が参照するスキルid。プラグイン側で固定(旧: fishing.skill-id 設定値)。 */
    private static final String FISHING_SKILL_ID = "FISHING";

    private volatile Map<String, Map<String, DropTableConfig.Category>> groups = Map.of();
    /**
     * {@code fishing.unlock-groups}(2026-08-15 機能解放追加テーブル): {@code groups}と完全に同一構造の
     * 「機能解放で開くカテゴリ」置き場。パースは{@link #parseGroups}をそのまま再利用する。任意キー
     * (未設定なら空Mapで挙動は{@code groups}単体と完全に同じ=後方互換)。実際の合流(希釈込みの1プールへの
     * マージ)とゲートキー前置きは呼び出し側({@code FishingGimmickListener})の責務。
     */
    private volatile Map<String, Map<String, DropTableConfig.Category>> unlockGroups = Map.of();
    private volatile double luckPerLevel = DEFAULT_LUCK_PER_LEVEL;
    private volatile double bonusPerLevel = DEFAULT_BONUS_PER_LEVEL;

    /**
     * {@code fish-sell.prices}: 売却額(Vault通貨、fish-sell-toggle消費)。キーはドロップテーブルの
     * {@code item:}と同じトークン語彙(バニラMaterial名 または カスタムアイテムID、大小文字はYAML記載の
     * まま保持)。T2(2026-07-25経済連携拡張): {@code fish}グループにカスタムアイテムを設定できるように
     * なったため、Material限定のキーからString汎用キーへ拡張(未登録=売却対象外は変わらず)。
     */
    private volatile Map<String, Double> fishSellPrices = Map.of();
    private static final int DEFAULT_FISH_SELL_MAX_PER_MINUTE = 20;
    /** {@code fish-sell.max-sells-per-minute}: exploit対策(#5 fixの教訓)の1分あたり売却回数上限。 */
    private volatile int fishSellMaxPerMinute = DEFAULT_FISH_SELL_MAX_PER_MINUTE;

    /** {@code fishing.ocean-biomes} の正規化済みレジストリキー(小文字、namespace無し。例: "ocean")。 */
    private static final Set<String> DEFAULT_OCEAN_BIOME_KEYS = Set.of(
            "ocean", "deep_ocean",
            "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean",
            "cold_ocean", "deep_cold_ocean",
            "frozen_ocean", "deep_frozen_ocean");
    private volatile Set<String> oceanBiomeKeys = DEFAULT_OCEAN_BIOME_KEYS;

    /** {@code junk-to-scrap}/{@code fish-sell-toggle} 共通: 釣りの「ゴミ」枠と判定するMaterial一覧。 */
    public Set<Material> junkMaterials() {
        return junkMaterials;
    }

    /** {@code fish-sell-toggle}: 釣りの「宝」枠と判定するMaterial一覧。 */
    public Set<Material> treasureMaterials() {
        return treasureMaterials;
    }

    /** {@code fishing.group-ratio.treasure-percent} (§2.3): 基準宝率(%、luckTotalでシフトされる前の値)。 */
    public double treasurePercent() {
        return treasurePercent;
    }

    /**
     * {@code fishing.group-ratio.junk-percent} (2026-07-23 仕様確定・三択モデル化): 基準ゴミ率(%、
     * luckTotalによるシフトなしの固定値)。宝%のシフト分に応じて比例縮小/拡大されるのは
     * {@link com.trinityforge.stats.DropTablePolicy#rollFishOutcome} 側の責務。
     */
    public double junkPercent() {
        return junkPercent;
    }

    /** {@code fishing.groups.<treasure|junk>.categories}: グループid -&gt; (カテゴリid -&gt; 定義)。 */
    public Map<String, Map<String, DropTableConfig.Category>> groups() {
        return groups;
    }

    /**
     * {@code fishing.unlock-groups.<treasure|junk|fish>.categories}(2026-08-15
     * 機能解放追加テーブル): {@link #groups()}と対になる、機能解放で開くカテゴリの置き場。構造は
     * {@code groups()}と完全に同一。呼び出し側({@code FishingGimmickListener})が同じグループidの
     * {@code groups()}側と1つの重みプールへ合流させる(希釈あり)。省略時は空Map(完全後方互換)。
     */
    public Map<String, Map<String, DropTableConfig.Category>> unlockGroups() {
        return unlockGroups;
    }

    /** {@code true} when neither {@link #groups()} nor {@link #unlockGroups()} has any category — i.e.
     *  the fallback (vanilla catch + legacy junk/treasure material lists) path must be used instead of
     *  the drop-table replace flow. */
    public boolean dropTablesEmpty() {
        for (Map<String, DropTableConfig.Category> categories : groups.values()) {
            if (!categories.isEmpty()) {
                return false;
            }
        }
        for (Map<String, DropTableConfig.Category> categories : unlockGroups.values()) {
            if (!categories.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 釣りfishing-luck/-bonus連続処理に使うスキルid。FISHING固定(旧 gathering.yml fishing.skill-id / config読取は廃止)。 */
    public String fishingSkillId() {
        return FISHING_SKILL_ID;
    }

    /** FISHING Lv 1 あたりの追加fishing-luck(旧 gathering.yml fishing.luck-per-level。比率専用化に伴い既定値変更)。 */
    public double luckPerLevel() {
        return luckPerLevel;
    }

    /** FISHING Lv 1 あたりの追加fishing-bonus(旧 gathering.yml fishing.bonus-per-level)。 */
    public double bonusPerLevel() {
        return bonusPerLevel;
    }

    /**
     * {@code fish-sell.prices}: トークン(Material名 または カスタムアイテムID) -&gt; 売却額(Vault通貨、
     * 基準額。プレイヤーの{@code fish_sell_price_bonus} statは呼び出し側=リスナーが乗算する)。
     * 未登録のトークンは0円=売却対象外。
     */
    public Map<String, Double> fishSellPrices() {
        return fishSellPrices;
    }

    /**
     * {@code fish-sell.prices}に登録済みのMaterialの基準売却額(後方互換: {@link Material#name()}を
     * トークンとして引く)。未登録は空。
     */
    public Optional<Double> fishSellPriceOf(Material material) {
        return fishSellPriceOf(material.name());
    }

    /**
     * {@code fish-sell.prices}に登録済みのトークン(Material名 または カスタムアイテムID、YAML記載どおりの
     * 大小文字で完全一致)の基準売却額。未登録は空。
     */
    public Optional<Double> fishSellPriceOf(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(fishSellPrices.get(token));
    }

    /**
     * {@code fish-sell.max-sells-per-minute}: 1プレイヤーが1分間に自動売却できる回数の上限
     * (exploit対策、#5 exploit fixの教訓。AFK/自動釣り機での無限換金を防ぐ)。
     */
    public int fishSellMaxPerMinute() {
        return fishSellMaxPerMinute;
    }

    /** {@code fishing.ocean-biomes}: T4海釣り判定の対象バイオーム(レジストリキー、小文字、namespace無し)。 */
    public Set<String> oceanBiomeKeys() {
        return oceanBiomeKeys;
    }

    /** {@code biome} が {@link #oceanBiomeKeys()} に含まれるか({@code null}は非該当)。 */
    public boolean isOceanBiome(Biome biome) {
        if (biome == null) {
            return false;
        }
        try {
            return oceanBiomeKeys.contains(biome.getKey().getKey().toLowerCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            return false;
        }
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

        // 後方互換フォールバック専用(§4): fishing.groups が空の間だけ参照される。
        this.junkMaterials = parseMaterials(yaml.getStringList("junk-materials"), log);
        this.treasureMaterials = parseMaterials(yaml.getStringList("treasure-materials"), log);
        // 2026-08-15: xp-bottle-store は progression/crafting-features.yml へ移設した(このセクション
        // 自体はエンチャントツリーの機能で、釣りとは無関係だったため — CraftingFeaturesConfig参照)。
        // 移行漏れ(移設前の場所にまだ書かれている)に気づけるよう、残っていたら警告するだけで読みはしない
        // (逆方向のフォールバックは実装しない: 原因が隠れるため)。
        if (yaml.isSet("xp-bottle-store")) {
            log.warning("[" + PATH + "] 'xp-bottle-store' は progression/crafting-features.yml へ移動しました"
                    + "(2026-08-15)。このファイルの 'xp-bottle-store' セクションはもう読まれません。"
                    + "設定は progression/crafting-features.yml の 'xp-bottle-store' へ書いてください。");
        }

        this.treasurePercent = clampPercent(
                yaml.getDouble("fishing.group-ratio.treasure-percent", DEFAULT_TREASURE_PERCENT),
                "fishing.group-ratio.treasure-percent", DEFAULT_TREASURE_PERCENT, log);
        this.junkPercent = clampPercent(
                yaml.getDouble("fishing.group-ratio.junk-percent", DEFAULT_JUNK_PERCENT),
                "fishing.group-ratio.junk-percent", DEFAULT_JUNK_PERCENT, log);
        this.groups = parseGroups(yaml.getConfigurationSection("fishing.groups"), log);
        this.unlockGroups = parseGroups(yaml.getConfigurationSection("fishing.unlock-groups"), log);
        this.luckPerLevel = clampPerLevel(
                yaml.getDouble("fishing.luck-per-level", DEFAULT_LUCK_PER_LEVEL),
                "fishing.luck-per-level", DEFAULT_LUCK_PER_LEVEL, log);
        this.bonusPerLevel = clampPerLevel(
                yaml.getDouble("fishing.bonus-per-level", DEFAULT_BONUS_PER_LEVEL),
                "fishing.bonus-per-level", DEFAULT_BONUS_PER_LEVEL, log);
        this.oceanBiomeKeys = yaml.isSet("fishing.ocean-biomes")
                ? parseBiomeKeys(yaml.getStringList("fishing.ocean-biomes"))
                : DEFAULT_OCEAN_BIOME_KEYS;

        this.fishSellPrices = parseFishSellPrices(yaml.getConfigurationSection("fish-sell.prices"), log);
        this.fishSellMaxPerMinute = clampPositiveInt(
                yaml.getInt("fish-sell.max-sells-per-minute", DEFAULT_FISH_SELL_MAX_PER_MINUTE),
                "fish-sell.max-sells-per-minute", DEFAULT_FISH_SELL_MAX_PER_MINUTE, log);

        log.info("[" + PATH + "] loaded " + this.junkMaterials.size() + " junk-material(s), "
                + this.treasureMaterials.size() + " treasure-material(s), "
                + this.groups.size() + " fishing group(s), "
                + this.unlockGroups.size() + " fishing unlock-group(s), "
                + this.fishSellPrices.size() + " fish-sell price(s) OK");
        return true;
    }

    private static Set<String> parseBiomeKeys(List<String> names) {
        Set<String> parsed = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            parsed.add(name.trim().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(parsed);
    }

    /**
     * T2(2026-07-25経済連携拡張): キーはバニラMaterial名 または カスタムアイテムID(ドロップテーブルの
     * {@code item:}と同じトークン語彙)。
     *
     * <p><b>不正キー警告の扱い(判断済み)</b>: 旧実装は「Materialとして解決できないキーを警告」していたが、
     * この節がカスタムIDを許容するようになった以上、Material限定の妥当性検証はもう成立しない。ここで
     * {@link com.trinityforge.stats.CrossPluginItemResolver}(TF catalog + ArsPaper registry + Material)
     * まで踏み込んだロード時検証を行わない選択をした: TF catalog(itemCatalog.yml)は
     * {@code ConfigManager}で本クラスより先にロードされ同一プラグイン内で安定しているが、ArsPaperの
     * カスタムアイテムレジストリは別プラグインのsoft-dependであり、TrinityForgeのconfigロード時点で
     * ArsPaperがまだ{@code onEnable}していない(=レジストリ未登録)順序が普通に起こり得る。そのため
     * ここでArs registryまで検証すると「正しいArsPaperカスタムIDなのに、たまたまTF起動より後にArsPaperが
     * 有効化されただけで誤警告される」事故が起きる(ロード順依存の偽陽性)。よって、キーの妥当性検証は
     * 撤廃し、非空文字列であれば受理する(=実行時解決に倒す: 実際の売却時に未登録トークンなら
     * 「未登録=売却対象外」として静かにスキップされる、既存の安全側動作と自然に一致する)。
     * 数値(価格)側のfinite/positiveチェックは検証可能なのでそのまま維持する。
     */
    private static Map<String, Double> parseFishSellPrices(ConfigurationSection pricesSection, Logger log) {
        if (pricesSection == null) {
            return Map.of();
        }
        Map<String, Double> parsed = new LinkedHashMap<>();
        for (String key : pricesSection.getKeys(false)) {
            String token = key == null ? null : key.trim();
            if (token == null || token.isBlank()) {
                log.warning("[" + PATH + "] fish-sell.prices has a blank key; skipped");
                continue;
            }
            double price = pricesSection.getDouble(key, 0.0);
            if (!Double.isFinite(price) || price <= 0.0) {
                log.warning("[" + PATH + "] fish-sell.prices '" + key + "' = " + price
                        + " must be a finite positive number; skipped");
                continue;
            }
            parsed.put(token, price);
        }
        return Map.copyOf(parsed);
    }

    private static Map<String, Map<String, DropTableConfig.Category>> parseGroups(
            ConfigurationSection groupsSection, Logger log) {
        Map<String, Map<String, DropTableConfig.Category>> parsed = new java.util.LinkedHashMap<>();
        if (groupsSection == null) {
            return Map.of();
        }
        for (String groupId : groupsSection.getKeys(false)) {
            ConfigurationSection groupSection = groupsSection.getConfigurationSection(groupId);
            if (groupSection == null) {
                log.warning("[" + PATH + "] fishing group '" + groupId + "' is not a mapping; skipped");
                continue;
            }
            Map<String, DropTableConfig.Category> categories = DropTableConfig.parseCategories(
                    groupSection.getConfigurationSection("categories"), false,
                    PATH + " (group " + groupId + ")", log);
            parsed.put(groupId, categories);
        }
        return Map.copyOf(parsed);
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

    private static Set<Material> parseMaterials(List<String> names, Logger log) {
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

    /** Clamps a percent value to {@code [0, 100]}; a non-finite value falls back to {@code fallback}. */
    private static double clampPercent(double raw, String key, double fallback, Logger log) {
        if (!Double.isFinite(raw)) {
            log.warning("[" + PATH + "] '" + key + "' is not a finite number (" + raw
                    + "); using default " + fallback);
            return fallback;
        }
        double clamped = Math.max(0.0, Math.min(raw, 100.0));
        if (clamped != raw) {
            log.warning("[" + PATH + "] '" + key + "' = " + raw + " is out of [0,100]; clamped to " + clamped);
        }
        return clamped;
    }

    /** Non-finite/non-positive guard for a count value: falls back to {@code fallback}, never throws. */
    private static int clampPositiveInt(int raw, String key, int fallback, Logger log) {
        if (raw <= 0) {
            log.warning("[" + PATH + "] '" + key + "' must be > 0 (was " + raw + "); using default " + fallback);
            return fallback;
        }
        return raw;
    }
}
