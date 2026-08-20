package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code progression/collection.yml}: コレクション図鑑 (M7)。収集記録のソース切替と、
 * 図鑑登録数しきい値ベースの報酬ティア(称号/コスメ/恒久QoL — 縦強化にしない、DUNGEON_SPEC §5)。
 * Malformed tiers are skipped with a warning; the rest load. Snapshots swap atomically on reload.
 */
public final class CollectionConfig implements LoadableConfig {

    public static final String PATH = "progression/collection.yml";

    /**
     * 報酬ティア: 図鑑登録数(エントリ種類数)が {@code threshold} に達したとき一度だけ解放される。
     *
     * @param id        ティアID(claimed記録キー。0x1F禁止は PlayerData 側で検証)
     * @param threshold 解放しきい値(登録エントリ数、1以上)
     * @param title     解放時にプレイヤーへ表示する称号テキスト(null=なし)
     * @param broadcast true でサーバー全体へ解放アナウンス
     * @param commands       コンソール実行コマンド({@code %player%} 置換)。空=なし
     * @param special        special-rewards.yml の titles/particles/particle-seeds のID配列(任意、
     *                       2026-07-23-stat-gate-overhaul §6.7 追加フィールド)。解放時に直接付与される。
     * @param items          解放時に付与するアイテム(カタログID/ArsPaper登録ID/バニラMaterial)
     * @param vanillaExp     解放時に付与するバニラ経験値(0=無し)
     * @param jobExp         解放時に付与する職業EXP(複数スキル可)
     * @param permanentBuffs 解放が続く限り常時適用される永続ステータスバフ(canonicalキー→合算値)
     */
    public record RewardTier(String id, int threshold, String title, boolean broadcast,
                             List<String> commands, List<String> special, List<ItemGrant> items,
                             int vanillaExp, List<ExpGrant> jobExp, Map<String, Double> permanentBuffs) {
        public RewardTier {
            commands = List.copyOf(commands);
            special = List.copyOf(special);
            items = List.copyOf(items);
            vanillaExp = Math.max(0, vanillaExp);
            jobExp = List.copyOf(jobExp);
            permanentBuffs = Map.copyOf(permanentBuffs);
        }
    }

    /**
     * 図鑑GUIの表示分類 (§6.3/§6.7): {@code entries} に列挙されたカタログID/EntityType名がこの
     * カテゴリに属する。未分類のエントリはGUI表示時に自動で「その他」カテゴリへ回す。
     *
     * @param id          カテゴリID(キー自身)
     * @param displayName 表示名
     * @param order       表示順(昇順)
     * @param entries     このカテゴリに属するID集合(catalogId または ENTITY_TYPE 名)
     */
    public record Category(String id, String displayName, int order, List<String> entries) {
        public Category {
            entries = List.copyOf(entries);
        }
    }

    /** {@code gui.locked-icon} の既定値。未発見エントリの「錠前」アイコン。 */
    public static final String DEFAULT_LOCKED_ICON = "BARRIER";

    private volatile boolean enabled = true;
    private volatile boolean catalogItems = true;
    private volatile boolean mobKills = true;
    private volatile String lockedIcon = DEFAULT_LOCKED_ICON;
    private volatile Map<String, String> itemDisplayNames = Map.of();
    private volatile Map<String, String> mobDisplayNames = Map.of();
    private volatile List<RewardTier> tiers = List.of();
    private volatile List<Category> itemCategories = List.of();
    private volatile List<Category> mobCategories = List.of();

    public boolean enabled() {
        return enabled;
    }

    /** items/catalog.yml アイテム入手の記録が有効か。 */
    public boolean catalogItemsEnabled() {
        return enabled && catalogItems;
    }

    /** プレイヤー討伐(EntityType)の記録が有効か。 */
    public boolean mobKillsEnabled() {
        return enabled && mobKills;
    }

    /** 報酬ティア(threshold昇順)。 */
    public List<RewardTier> tiers() {
        return tiers;
    }

    /** {@code categories.items} (表示順昇順)。 */
    public List<Category> itemCategories() {
        return itemCategories;
    }

    /** {@code categories.mobs} (表示順昇順)。 */
    public List<Category> mobCategories() {
        return mobCategories;
    }

    /**
     * 未発見エントリに使う「錠前」アイコンの Material 名 ({@code gui.locked-icon})。
     * 名前の妥当性はここでは検証しない — 解決は表示側 (GUI) が行い、解決できなければ
     * {@link #DEFAULT_LOCKED_ICON} へ落とす (config のtypo で図鑑が開けなくなるのを避ける)。
     */
    public String lockedIcon() {
        return lockedIcon;
    }

    /**
     * {@code display-names.items.<catalogId>} — 図鑑での表示名の明示上書き(任意、既定は空)。
     * 空のときはアイテム自身の display-name(カタログ/ArsPaper)が使われるので、通常は書かなくてよい。
     */
    public Map<String, String> itemDisplayNames() {
        return itemDisplayNames;
    }

    /**
     * {@code display-names.mobs.<ENTITY_TYPE>} — 図鑑でのモブ表示名の明示上書き(任意、既定は空)。
     *
     * <p>未指定のモブは翻訳可能コンポーネント({@code entity.minecraft.*})で表示され、
     * <b>各クライアントの言語で正しく出る</b>。ただしサーバー側は日本語名を知らないため、
     * 名前検索/名前ソートは英語のEntityType名基準になる。日本語で検索したいモブだけ
     * ここへ書けば、検索・並べ替えもその名前で効くようになる。
     */
    public Map<String, String> mobDisplayNames() {
        return mobDisplayNames;
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

        this.enabled = yaml.getBoolean("enabled", true);
        this.catalogItems = yaml.getBoolean("sources.catalog-items", true);
        this.mobKills = yaml.getBoolean("sources.mob-kills", true);
        String rawLockedIcon = yaml.getString("gui.locked-icon", DEFAULT_LOCKED_ICON);
        this.lockedIcon = rawLockedIcon == null || rawLockedIcon.isBlank()
                ? DEFAULT_LOCKED_ICON : rawLockedIcon.trim();
        ParseResult result = parseTiers(yaml.getConfigurationSection("reward-tiers"), log);
        this.tiers = result.tiers();
        this.itemCategories = parseCategories(yaml.getConfigurationSection("categories.items"), log, "items");
        this.mobCategories = parseCategories(yaml.getConfigurationSection("categories.mobs"), log, "mobs");
        this.itemDisplayNames = parseDisplayNames(yaml.getConfigurationSection("display-names.items"));
        this.mobDisplayNames = parseDisplayNames(yaml.getConfigurationSection("display-names.mobs"));

        if (result.skipped() > 0) {
            log.warning("[" + PATH + "] loaded " + result.tiers().size() + " reward tier(s), "
                    + result.skipped() + " skipped");
            return false;
        }
        log.info("[" + PATH + "] loaded " + result.tiers().size() + " reward tier(s) OK"
                + (enabled ? "" : " (disabled)"));
        return true;
    }

    /** Pure parse of {@code reward-tiers:} — unit-testable headlessly. Sorted by threshold asc. */
    static ParseResult parseTiers(ConfigurationSection root, Logger log) {
        List<RewardTier> parsed = new ArrayList<>();
        int skipped = 0;
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection entry = root.getConfigurationSection(id);
                if (entry == null) {
                    log.warning("[" + PATH + "] reward tier '" + id + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                int threshold = entry.getInt("threshold", 0);
                if (threshold < 1) {
                    log.warning("[" + PATH + "] reward tier '" + id
                            + "' has missing/invalid threshold (>=1); skipped");
                    skipped++;
                    continue;
                }
                if (id.indexOf(0x1F) >= 0) {
                    log.warning("[" + PATH + "] reward tier id '" + id
                            + "' contains a control character; skipped");
                    skipped++;
                    continue;
                }
                String title = entry.getString("title");
                if (title != null && title.isBlank()) {
                    title = null;
                }
                List<String> commands = new ArrayList<>();
                for (String raw : entry.getStringList("commands")) {
                    if (raw != null && !raw.isBlank()) {
                        commands.add(raw.trim());
                    }
                }
                List<String> special = new ArrayList<>();
                for (String raw : entry.getStringList("special")) {
                    if (raw != null && !raw.isBlank()) {
                        special.add(raw.trim());
                    }
                }
                String contextLabel = "reward tier '" + id + "'";
                List<ItemGrant> items = RewardFieldsParser.parseItems(entry, PATH, contextLabel, log);
                int vanillaExp = RewardFieldsParser.parseVanillaExp(entry);
                List<ExpGrant> jobExp = RewardFieldsParser.parseJobExp(entry, PATH, contextLabel, log);
                Map<String, Double> permanentBuffs =
                        RewardFieldsParser.parsePermanentBuffs(entry, PATH, contextLabel, log);
                parsed.add(new RewardTier(id, threshold, title,
                        entry.getBoolean("broadcast", false), commands, special,
                        items, vanillaExp, jobExp, permanentBuffs));
            }
        }
        parsed.sort(Comparator.comparingInt(RewardTier::threshold));
        return new ParseResult(List.copyOf(parsed), skipped);
    }

    /**
     * Pure parse of a {@code categories.items}/{@code categories.mobs} section — unit-testable
     * headlessly. A malformed category entry (not a section) is skipped with a warning; the rest
     * load. Sorted by {@code order} ascending.
     */
    static List<Category> parseCategories(ConfigurationSection root, Logger log, String kind) {
        if (root == null) {
            return List.of();
        }
        List<Category> parsed = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection entry = root.getConfigurationSection(id);
            if (entry == null) {
                log.warning("[" + PATH + "] categories." + kind + " '" + id + "' is not a section; skipped");
                continue;
            }
            String displayName = entry.getString("display-name", id);
            int order = entry.getInt("order", 0);
            List<String> entries = new ArrayList<>();
            for (String raw : entry.getStringList("entries")) {
                if (raw != null && !raw.isBlank()) {
                    entries.add(raw.trim());
                }
            }
            parsed.add(new Category(id, displayName, order, entries));
        }
        parsed.sort(Comparator.comparingInt(Category::order));
        return List.copyOf(parsed);
    }

    /**
     * Pure parse of a {@code display-names.items}/{@code display-names.mobs} section — 値が空文字/
     * 非文字列のキーは黙って捨てる(表示名の上書きは任意機能なので、書き損じでロード全体を落とさない)。
     */
    static Map<String, String> parseDisplayNames(ConfigurationSection root) {
        if (root == null) {
            return Map.of();
        }
        Map<String, String> parsed = new java.util.LinkedHashMap<>();
        for (String key : root.getKeys(false)) {
            String value = root.getString(key);
            if (value != null && !value.isBlank()) {
                parsed.put(key.trim(), value.trim());
            }
        }
        return Map.copyOf(parsed);
    }

    record ParseResult(List<RewardTier> tiers, int skipped) {
    }
}
