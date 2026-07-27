package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code progression/achievements.yml}: バニラ実績連動 + カスタム統計しきい値の
 * アチーブメント定義 (2026-07-23-stat-gate-overhaul §6.2/§6.7)。
 *
 * <p>トラッキングは {@code AchievementService} の責務(statistic型は周期ポーリング、advancement型は
 * {@code PlayerAdvancementDoneEvent})。本クラスは定義の読み込みのみ。
 *
 * <p>Malformed entries are skipped with a warning; the rest load. Snapshots swap atomically on reload.
 */
public final class AchievementsConfig implements LoadableConfig {

    public static final String PATH = "progression/achievements.yml";

    public enum TriggerType { STATISTIC, ADVANCEMENT, STATIC }

    /**
     * @param type        STATISTIC | ADVANCEMENT | STATIC（図鑑登録）
     * @param statistic   type=STATISTIC のとき参照する Bukkit Statistic(それ以外は null)
     * @param threshold   type=STATISTIC のとき到達判定するしきい値
     * @param advancement type=ADVANCEMENT のとき対象の進捗キー(それ以外は null)
     */
    public record Trigger(TriggerType type, Statistic statistic, long threshold, String advancement,
                          String collectionScope, String collectionTarget, boolean collectionPercent) {
    }

    /**
     * @param special        special-rewards.yml のID配列
     * @param commands       {@code %player%} 置換コマンド配列
     * @param items          達成時に付与するアイテム(カタログID/ArsPaper登録ID/バニラMaterial)
     * @param vanillaExp     達成時に付与するバニラ経験値(0=無し)
     * @param jobExp         達成時に付与する職業EXP(複数スキル可)
     * @param permanentBuffs 達成が続く限り常時適用される永続ステータスバフ(canonicalキー→合算値)
     */
    public record Rewards(List<String> special, List<String> commands, List<ItemGrant> items,
                          int vanillaExp, List<ExpGrant> jobExp, Map<String, Double> permanentBuffs) {
        public Rewards {
            special = List.copyOf(special);
            commands = List.copyOf(commands);
            items = List.copyOf(items);
            vanillaExp = Math.max(0, vanillaExp);
            jobExp = List.copyOf(jobExp);
            permanentBuffs = Map.copyOf(permanentBuffs);
        }
    }

    /**
     * @param id          アチーブメントID(キー自身)
     * @param displayName プレイヤーへ表示する達成名
     * @param trigger     達成条件
     * @param broadcast   true でサーバー全体へ達成をアナウンス
     * @param rewards     達成時に付与する報酬
     */
    public record Achievement(String id, String displayName, Trigger trigger, boolean broadcast, Rewards rewards) {
    }

    private volatile List<Achievement> achievements = List.of();

    public List<Achievement> achievements() {
        return achievements;
    }

    /** type=STATISTIC のアチーブメントのみ(周期ポーリング対象)。 */
    public List<Achievement> statisticAchievements() {
        return achievements.stream().filter(a -> a.trigger().type() == TriggerType.STATISTIC).toList();
    }

    /** type=ADVANCEMENT のアチーブメントのみ({@code PlayerAdvancementDoneEvent} 対象)。 */
    public List<Achievement> advancementAchievements() {
        return achievements.stream().filter(a -> a.trigger().type() == TriggerType.ADVANCEMENT).toList();
    }

    /** type=STATIC（図鑑登録）のアチーブメントのみ。保存形式の collection は後方互換で維持する。 */
    public List<Achievement> staticAchievements() {
        return achievements.stream().filter(a -> a.trigger().type() == TriggerType.STATIC).toList();
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

        ParseResult result = parse(yaml.getConfigurationSection("achievements"), log);
        this.achievements = result.achievements();

        if (result.skipped() > 0) {
            log.warning("[" + PATH + "] loaded " + result.achievements().size()
                    + " achievement(s), " + result.skipped() + " skipped");
            return false;
        }
        log.info("[" + PATH + "] loaded " + result.achievements().size() + " achievement(s) OK");
        return true;
    }

    /** Pure parse of {@code achievements:} — unit-testable headlessly. */
    static ParseResult parse(ConfigurationSection root, Logger log) {
        List<Achievement> parsed = new ArrayList<>();
        int skipped = 0;
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection entry = root.getConfigurationSection(id);
                if (entry == null) {
                    log.warning("[" + PATH + "] achievement '" + id + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                String displayName = entry.getString("display-name", id);
                Trigger trigger = parseTrigger(entry.getConfigurationSection("trigger"), id, log);
                if (trigger == null) {
                    skipped++;
                    continue;
                }
                boolean broadcast = entry.getBoolean("broadcast", false);
                Rewards rewards = parseRewards(entry.getConfigurationSection("rewards"), id, log);
                parsed.add(new Achievement(id, displayName, trigger, broadcast, rewards));
            }
        }
        return new ParseResult(List.copyOf(parsed), skipped);
    }

    private static Trigger parseTrigger(ConfigurationSection trigger, String achievementId, Logger log) {
        if (trigger == null) {
            log.warning("[" + PATH + "] achievement '" + achievementId + "' missing trigger; skipped");
            return null;
        }
        String rawType = trigger.getString("type");
        TriggerType type = parseTriggerType(rawType);
        if (type == null) {
            log.warning("[" + PATH + "] achievement '" + achievementId
                    + "' has invalid trigger.type (statistic|advancement|static); skipped");
            return null;
        }
        if (type == TriggerType.STATISTIC) {
            Statistic statistic = parseStatistic(trigger.getString("statistic"));
            if (statistic == null) {
                log.warning("[" + PATH + "] achievement '" + achievementId
                        + "' has invalid/missing trigger.statistic; skipped");
                return null;
            }
            // 2026-07-23 verifier指摘⑨: qualifier(Material/EntityType)必須のStatistic(MINE_BLOCK等)は
            // 本スキーマがqualifierを持たないため Player#getStatistic(Statistic) 単体呼び出しが必ず
            // IllegalArgumentExceptionになる。毎分のポーリングで警告をスパムする代わりに、ロード時に
            // 1回だけ警告してこのアチーブメントをスキップする。
            if (statistic.getType() != Statistic.Type.UNTYPED) {
                log.warning("[" + PATH + "] achievement '" + achievementId + "' trigger.statistic '"
                        + statistic + "' requires a qualifier (Material/EntityType) that this schema does not"
                        + " support yet; skipped");
                return null;
            }
            long threshold = trigger.getLong("threshold", -1);
            if (threshold < 1) {
                log.warning("[" + PATH + "] achievement '" + achievementId
                        + "' has missing/invalid trigger.threshold (>=1); skipped");
                return null;
            }
            return new Trigger(TriggerType.STATISTIC, statistic, threshold, null, null, null, false);
        }
        if (type == TriggerType.STATIC) {
            String scope = trigger.getString("collection.scope", "all").trim().toLowerCase(Locale.ROOT);
            if (!scope.equals("all") && !scope.equals("category") && !scope.equals("item") && !scope.equals("mob")) {
                log.warning("[" + PATH + "] achievement '" + achievementId + "' has invalid collection scope; skipped");
                return null;
            }
            String target = trigger.getString("collection.target", "").trim();
            if (!scope.equals("all") && target.isBlank()) {
                log.warning("[" + PATH + "] achievement '" + achievementId + "' needs collection.target; skipped");
                return null;
            }
            long threshold = trigger.getLong("collection.threshold", -1);
            boolean percent = trigger.getBoolean("collection.percent", false);
            if (threshold < 1 || (percent && threshold > 100)) {
                log.warning("[" + PATH + "] achievement '" + achievementId + "' has invalid collection threshold; skipped");
                return null;
            }
            return new Trigger(TriggerType.STATIC, null, threshold, null, scope, target, percent);
        }
        String advancement = trigger.getString("advancement");
        if (advancement == null || advancement.isBlank()) {
            log.warning("[" + PATH + "] achievement '" + achievementId
                    + "' has missing trigger.advancement; skipped");
            return null;
        }
        return new Trigger(TriggerType.ADVANCEMENT, null, 0, advancement.trim(), null, null, false);
    }

    private static TriggerType parseTriggerType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if ("collection".equalsIgnoreCase(raw.trim())) {
            return TriggerType.STATIC; // old config migration; editor saves the canonical static form.
        }
        try {
            return TriggerType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Statistic parseStatistic(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Statistic.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Rewards parseRewards(ConfigurationSection rewards, String achievementId, Logger log) {
        if (rewards == null) {
            return new Rewards(List.of(), List.of(), List.of(), 0, List.of(), Map.of());
        }
        List<String> special = new ArrayList<>();
        for (String raw : rewards.getStringList("special")) {
            if (raw != null && !raw.isBlank()) {
                special.add(raw.trim());
            }
        }
        List<String> commands = new ArrayList<>();
        for (String raw : rewards.getStringList("commands")) {
            if (raw != null && !raw.isBlank()) {
                commands.add(raw.trim());
            }
        }
        String contextLabel = "achievement '" + achievementId + "'";
        List<ItemGrant> items = RewardFieldsParser.parseItems(rewards, PATH, contextLabel, log);
        int vanillaExp = RewardFieldsParser.parseVanillaExp(rewards);
        List<ExpGrant> jobExp = RewardFieldsParser.parseJobExp(rewards, PATH, contextLabel, log);
        Map<String, Double> permanentBuffs =
                RewardFieldsParser.parsePermanentBuffs(rewards, PATH, contextLabel, log);
        return new Rewards(special, commands, items, vanillaExp, jobExp, permanentBuffs);
    }

    record ParseResult(List<Achievement> achievements, int skipped) {
    }
}
