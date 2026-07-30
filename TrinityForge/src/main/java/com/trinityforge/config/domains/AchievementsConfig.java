package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
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
     * type=STATISTIC の修飾子(qualifier)。Bukkit の統計には {@code MINE_BLOCK}(BLOCK) /
     * {@code CRAFT_ITEM}(ITEM) / {@code KILL_ENTITY}(ENTITY) のように「何を」を指定しないと読めない型があり、
     * {@code Player#getStatistic(Statistic)} 単体で呼ぶと必ず {@link IllegalArgumentException} になる。
     * 2026-07-30 以前はその型のアチーブメントを読み込み時に丸ごと skip していた(＝書けなかった)。
     *
     * <p>{@code UNTYPED} の統計では {@link #NONE} を使う(両フィールドとも null)。
     *
     * @param material   BLOCK / ITEM 型の統計で参照する Material(それ以外は null)
     * @param entityType ENTITY 型の統計で参照する EntityType(それ以外は null)
     */
    public record StatisticQualifier(Material material, EntityType entityType) {

        /** UNTYPED 統計用の「修飾子なし」。 */
        public static final StatisticQualifier NONE = new StatisticQualifier(null, null);

        /** この修飾子で {@code statistic} を読む。UNTYPED なら修飾子なしの読み出しになる。 */
        public long read(Player player, Statistic statistic) {
            if (material != null) {
                return player.getStatistic(statistic, material);
            }
            if (entityType != null) {
                return player.getStatistic(statistic, entityType);
            }
            return player.getStatistic(statistic);
        }

        /** GUI 表示用の修飾子名。修飾子なしなら空文字。 */
        public String label() {
            if (material != null) {
                return material.name();
            }
            return entityType == null ? "" : entityType.name();
        }
    }

    /**
     * @param type              STATISTIC | ADVANCEMENT | STATIC（図鑑登録）
     * @param statistic         type=STATISTIC のとき参照する Bukkit Statistic(それ以外は null)
     * @param statisticQualifier type=STATISTIC のとき統計に添える Material/EntityType
     *                          (UNTYPED 統計と他の型では {@link StatisticQualifier#NONE})
     * @param threshold         type=STATISTIC のとき到達判定するしきい値
     * @param advancement       type=ADVANCEMENT のとき対象の進捗キー(それ以外は null)
     * @param collectionTargets type=STATIC の対象ID群。2026-07-27 に単数 {@code collection.target} から
     *                          複数 {@code collection.targets} へ拡張した(「複数アイテムの図鑑登録が
     *                          全部そろったら達成」を表現できるようにするため)。単数キーは
     *                          後方互換として読み続け、要素1件のリストとして正規化される。
     */
    public record Trigger(TriggerType type, Statistic statistic, StatisticQualifier statisticQualifier,
                          long threshold, String advancement,
                          String collectionScope, List<String> collectionTargets, boolean collectionPercent) {
        public Trigger {
            collectionTargets = collectionTargets == null ? List.of() : List.copyOf(collectionTargets);
            statisticQualifier = statisticQualifier == null ? StatisticQualifier.NONE : statisticQualifier;
        }

        /** 単一ターゲット時代の互換アクセサ。未指定なら空文字。 */
        public String collectionTarget() {
            return collectionTargets.isEmpty() ? "" : collectionTargets.get(0);
        }
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
     * @param icon        {@code /achievement} GUI のアイコン。バニラ Material 名 / カタログID /
     *                    {@code custom:<ArsのID>} のいずれか。空欄なら GUI 側の既定アイコン。
     * @param lore        GUI に出す説明文(MiniMessage 可)。空リストなら説明なし。
     * @param coords      GUI 上のノード座標 {@code "x,y"}。空欄なら読み込み順に自動配置する。
     * @param parent      前提アチーブメントID(null=起点)。<b>達成そのものを縛る</b>:
     *                    前提未達成の間は条件を満たしても達成にならない(2026-07-29 ユーザー確定)。
     * @param parentsAny  代替前提。{@code parent} かこの一覧のどれか1つを達成していれば前提を満たす
     *                    (スキルツリーの {@code parents-any} と同じ意味)。
     */
    public record Achievement(String id, String displayName, Trigger trigger, boolean broadcast, Rewards rewards,
                              String icon, List<String> lore, String coords,
                              String parent, List<String> parentsAny) {
        public Achievement {
            icon = icon == null ? "" : icon.trim();
            lore = lore == null ? List.of() : List.copyOf(lore);
            coords = coords == null ? "" : coords.trim();
            parent = parent == null || parent.isBlank() ? null : parent.trim();
            parentsAny = parentsAny == null ? List.of() : List.copyOf(parentsAny);
        }

        /** 旧シグネチャ互換(既存テスト/呼び出し用): ノード表示系のフィールドを全て未設定にする。 */
        public Achievement(String id, String displayName, Trigger trigger, boolean broadcast, Rewards rewards) {
            this(id, displayName, trigger, broadcast, rewards, "", List.of(), "", null, List.of());
        }

        /** 前提を1つも持たない(=ツリーの起点)か。 */
        public boolean isRoot() {
            return parent == null && parentsAny.isEmpty();
        }
    }

    /**
     * サーバ側でバニラ進捗(advancement)解除を止める設定(2026-07-28)。
     *
     * @param disabled                true でバニラ進捗の解除自体をキャンセルする(既定 true)
     * @param keepRecipeAdvancements  true(既定) で {@code minecraft:recipes/} 配下だけは通す
     *                                (false にするとレシピ本の解禁が止まる)
     * @param keep                    追加で通したい進捗キーの前方一致リスト
     */
    public record VanillaAdvancementGate(boolean disabled, boolean keepRecipeAdvancements, List<String> keep) {
        public VanillaAdvancementGate {
            keep = keep == null ? List.of() : List.copyOf(keep);
        }
    }

    private volatile List<Achievement> achievements = List.of();
    private volatile VanillaAdvancementGate vanillaAdvancements =
            new VanillaAdvancementGate(true, true, List.of());

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

    /**
     * バニラ進捗解除の抑止設定 ({@code vanilla-advancements:}, 2026-07-28)。
     * {@code VanillaAdvancementBlockListener} が判定に使う。
     */
    public VanillaAdvancementGate vanillaAdvancements() {
        return vanillaAdvancements;
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
        this.vanillaAdvancements = parseVanillaAdvancementGate(yaml.getConfigurationSection("vanilla-advancements"));

        // 相互作用の警告(2026-07-28、「静かに壊れるより騒がしく落ちる」方針): type=advancement の
        // TFアチーブメントは vanilla-advancements.disabled=true だと PlayerAdvancementCriterionGrantEvent
        // 自体がキャンセルされ、バニラ側の進捗が二度と完了しなくなるため永久に発火しない。設定ミスに
        // 気づけるよう起動/reload毎に警告する(現在の出荷achievements.ymlはtype=advancement 0件のため
        // 既定では出ない)。
        if (this.vanillaAdvancements.disabled()) {
            long advancementCount = this.achievements.stream()
                    .filter(a -> a.trigger().type() == TriggerType.ADVANCEMENT).count();
            if (advancementCount > 0) {
                log.warning("[" + PATH + "] vanilla-advancements.disabled=true ですが、trigger.type: advancement"
                        + " のアチーブメントが" + advancementCount + "件定義されています。バニラ進捗の解除自体が"
                        + "止まるため、これらのアチーブメントは永久に達成できません。");
            }
        }

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
                parsed.add(new Achievement(id, displayName, trigger, broadcast, rewards,
                        entry.getString("icon", ""),
                        entry.getStringList("lore"),
                        entry.getString("coords", ""),
                        entry.getString("parent"),
                        cleanIdList(entry.getStringList("parents-any"), id)));
            }
        }
        List<Achievement> result = List.copyOf(parsed);
        warnUnreachablePrerequisites(result, log);
        return new ParseResult(result, skipped);
    }

    /** 空要素・自己参照・重複を落とした前提IDリスト。 */
    private static List<String> cleanIdList(List<String> raw, String selfId) {
        List<String> out = new ArrayList<>();
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty() || trimmed.equals(selfId) || out.contains(trimmed)) {
                continue;
            }
            out.add(trimmed);
        }
        return List.copyOf(out);
    }

    /**
     * 前提が「存在しないID」や「循環」を指していると、そのアチーブメントは条件を満たしても
     * 永久に達成できない({@link #prerequisitesMet} が常に false になる)。静かに死ぬのを避けるため
     * 読み込み時に1回だけ警告する({@code vanilla-advancements} の相互作用警告と同じ方針)。
     */
    static void warnUnreachablePrerequisites(List<Achievement> achievements, Logger log) {
        java.util.Set<String> known = new java.util.LinkedHashSet<>();
        for (Achievement achievement : achievements) {
            known.add(achievement.id());
        }
        Map<String, Achievement> byId = new java.util.LinkedHashMap<>();
        for (Achievement achievement : achievements) {
            byId.put(achievement.id(), achievement);
        }
        for (Achievement achievement : achievements) {
            List<String> missing = new ArrayList<>();
            if (achievement.parent() != null && !known.contains(achievement.parent())) {
                missing.add(achievement.parent());
            }
            for (String any : achievement.parentsAny()) {
                if (!known.contains(any)) {
                    missing.add(any);
                }
            }
            if (!missing.isEmpty()) {
                log.warning("[" + PATH + "] achievement '" + achievement.id() + "' references unknown"
                        + " prerequisite(s) " + missing + "; 前提が存在しないため、条件を満たしても"
                        + "達成になりません。");
            }
            if (hasPrerequisiteCycle(achievement, byId)) {
                log.warning("[" + PATH + "] achievement '" + achievement.id() + "' の前提(parent)が"
                        + "循環しています。循環に含まれるアチーブメントは永久に達成できません。");
            }
        }
    }

    /** {@code parent} 鎖をたどって自分自身へ戻るか(parents-any は OR なので循環判定には使わない)。 */
    private static boolean hasPrerequisiteCycle(Achievement start, Map<String, Achievement> byId) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(start.id());
        Achievement cursor = start;
        while (cursor != null && cursor.parent() != null) {
            if (!seen.add(cursor.parent())) {
                return true;
            }
            cursor = byId.get(cursor.parent());
        }
        return false;
    }

    /**
     * 前提を満たしているか。{@code parent} かつ/または {@code parents-any} の「どれか1つ」を
     * 達成していれば true(スキルツリーの合流ノードと同じ意味)。前提が無ければ常に true。
     *
     * <p>2026-07-29 ユーザー確定「達成そのものを縛る」に基づき、これが false の間は
     * {@code AchievementService} が達成扱いにしない(報酬も出ない)。
     *
     * @param achievedIds そのプレイヤーが達成済みのアチーブメントID
     */
    public static boolean prerequisitesMet(Achievement achievement, java.util.Collection<String> achievedIds) {
        if (achievement.isRoot()) {
            return true;
        }
        if (achievement.parent() != null && achievedIds.contains(achievement.parent())) {
            return true;
        }
        for (String any : achievement.parentsAny()) {
            if (achievedIds.contains(any)) {
                return true;
            }
        }
        return false;
    }

    /** Pure parse of {@code vanilla-advancements:} — unit-testable headlessly. Section may be null (未設定)。 */
    static VanillaAdvancementGate parseVanillaAdvancementGate(ConfigurationSection section) {
        if (section == null) {
            return new VanillaAdvancementGate(true, true, List.of());
        }
        boolean disabled = section.getBoolean("disabled", true);
        boolean keepRecipeAdvancements = section.getBoolean("keep-recipe-advancements", true);
        List<String> keep = section.getStringList("keep");
        return new VanillaAdvancementGate(disabled, keepRecipeAdvancements, keep);
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
            // 2026-07-30: qualifier(Material/EntityType)必須のStatistic(MINE_BLOCK/CRAFT_ITEM/KILL_ENTITY等)を
            // trigger.statistic-qualifier で書けるようにした。以前はこの型を丸ごとスキップしていた
            // (Player#getStatistic(Statistic) 単体呼び出しが必ず IllegalArgumentException になるため)。
            // 解決に失敗した場合は従来どおりロード時に1回だけ警告してスキップする — 毎分のポーリングで
            // 例外を出し続けるより安全side。
            StatisticQualifier qualifier =
                    parseStatisticQualifier(trigger, achievementId, statistic, log);
            if (qualifier == null) {
                return null;
            }
            long threshold = trigger.getLong("threshold", -1);
            if (threshold < 1) {
                log.warning("[" + PATH + "] achievement '" + achievementId
                        + "' has missing/invalid trigger.threshold (>=1); skipped");
                return null;
            }
            return new Trigger(TriggerType.STATISTIC, statistic, qualifier, threshold,
                    null, null, List.of(), false);
        }
        if (type == TriggerType.STATIC) {
            String scope = trigger.getString("collection.scope", "all").trim().toLowerCase(Locale.ROOT);
            if (!scope.equals("all") && !scope.equals("category") && !scope.equals("item") && !scope.equals("mob")) {
                log.warning("[" + PATH + "] achievement '" + achievementId + "' has invalid collection scope; skipped");
                return null;
            }
            List<String> targets = parseCollectionTargets(trigger);
            if (!scope.equals("all") && targets.isEmpty()) {
                log.warning("[" + PATH + "] achievement '" + achievementId
                        + "' needs collection.target or collection.targets; skipped");
                return null;
            }
            boolean percent = trigger.getBoolean("collection.percent", false);
            // 2026-07-27: targets を並べた「全部そろったら」を最短で書けるように、item/mob スコープで
            // threshold 未指定なら「列挙した件数」を既定にする(単数targetなら従来どおり 1)。
            // category/all は列挙数と候補数が一致しないため、従来どおり threshold を必須のままにする。
            long defaultThreshold = (!percent && (scope.equals("item") || scope.equals("mob")))
                    ? targets.size() : -1;
            long threshold = trigger.getLong("collection.threshold", defaultThreshold);
            if (threshold < 1 || (percent && threshold > 100)) {
                log.warning("[" + PATH + "] achievement '" + achievementId + "' has invalid collection threshold; skipped");
                return null;
            }
            return new Trigger(TriggerType.STATIC, null, StatisticQualifier.NONE, threshold,
                    null, scope, targets, percent);
        }
        String advancement = trigger.getString("advancement");
        if (advancement == null || advancement.isBlank()) {
            log.warning("[" + PATH + "] achievement '" + achievementId
                    + "' has missing trigger.advancement; skipped");
            return null;
        }
        return new Trigger(TriggerType.ADVANCEMENT, null, StatisticQualifier.NONE, 0,
                advancement.trim(), null, List.of(), false);
    }

    /**
     * {@code collection.targets}(複数) と {@code collection.target}(単数) を1つのリストへ正規化する。
     * 両方書かれていた場合は複数キーを正とし、単数キーがそこに含まれていなければ先頭へ足す
     * (どちらか一方だけを黙って捨てて「書いたのに効かない」状態を作らないため)。重複は畳む。
     */
    private static List<String> parseCollectionTargets(ConfigurationSection trigger) {
        List<String> targets = new ArrayList<>();
        for (String raw : trigger.getStringList("collection.targets")) {
            if (raw != null && !raw.isBlank() && !targets.contains(raw.trim())) {
                targets.add(raw.trim());
            }
        }
        String single = trigger.getString("collection.target", "");
        if (single != null && !single.isBlank() && !targets.contains(single.trim())) {
            targets.add(0, single.trim());
        }
        return List.copyOf(targets);
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

    /**
     * {@code trigger.statistic-qualifier} を解決する。解決できない/型と噛み合わない場合は警告を出して
     * {@code null}(＝このアチーブメントをスキップ)を返す。
     *
     * <p>Bukkit の {@code Statistic.Type} は UNTYPED / BLOCK / ITEM / ENTITY の4種。BLOCK と ITEM は
     * Material を、ENTITY は EntityType を要求する。ここで Material の {@code isBlock()}/{@code isItem()}
     * まで見ておかないと、読み込みは通るのに実行時の {@code getStatistic} が投げる(＝ポーリングのたびに
     * 警告が出る)状態になる。
     */
    private static StatisticQualifier parseStatisticQualifier(ConfigurationSection trigger,
                                                              String achievementId, Statistic statistic,
                                                              Logger log) {
        String raw = trigger.getString("statistic-qualifier", "");
        raw = raw == null ? "" : raw.trim();
        Statistic.Type type = statistic.getType();
        if (type == Statistic.Type.UNTYPED) {
            if (!raw.isEmpty()) {
                log.warning("[" + PATH + "] achievement '" + achievementId + "' trigger.statistic '"
                        + statistic + "' is UNTYPED; statistic-qualifier '" + raw + "' is ignored");
            }
            return StatisticQualifier.NONE;
        }
        if (raw.isEmpty()) {
            log.warning("[" + PATH + "] achievement '" + achievementId + "' trigger.statistic '"
                    + statistic + "' needs trigger.statistic-qualifier ("
                    + (type == Statistic.Type.ENTITY ? "EntityType" : "Material") + "); skipped");
            return null;
        }
        String token = raw.toUpperCase(Locale.ROOT);
        if (type == Statistic.Type.ENTITY) {
            EntityType entityType;
            try {
                entityType = EntityType.valueOf(token);
            } catch (IllegalArgumentException ex) {
                log.warning("[" + PATH + "] achievement '" + achievementId
                        + "' trigger.statistic-qualifier '" + raw + "' is not an EntityType; skipped");
                return null;
            }
            return new StatisticQualifier(null, entityType);
        }
        Material material;
        try {
            material = Material.valueOf(token);
        } catch (IllegalArgumentException ex) {
            log.warning("[" + PATH + "] achievement '" + achievementId
                    + "' trigger.statistic-qualifier '" + raw + "' is not a Material; skipped");
            return null;
        }
        if (type == Statistic.Type.BLOCK && !material.isBlock()) {
            log.warning("[" + PATH + "] achievement '" + achievementId + "' trigger.statistic '" + statistic
                    + "' needs a block Material but got '" + raw + "'; skipped");
            return null;
        }
        if (type == Statistic.Type.ITEM && !material.isItem()) {
            log.warning("[" + PATH + "] achievement '" + achievementId + "' trigger.statistic '" + statistic
                    + "' needs an item Material but got '" + raw + "'; skipped");
            return null;
        }
        return new StatisticQualifier(material, null);
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
