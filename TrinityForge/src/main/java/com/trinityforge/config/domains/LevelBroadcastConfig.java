package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code progression/level-broadcast.yml}: 節目レベルアップの全体アナウンス (2026-08-16)。
 *
 * <p>元は ValhallaMMO アドオン {@code ValTopBoard} が持っていた機能。TF は自分のスキルレベルアップを
 * {@code TrinitySkillLevelUpEvent} で知っているので、外部プラグインを挟まず本体側で完結させる。
 *
 * <p><b>本人向けのレベルアップ通知（{@code stats/skill-exp.yml} の {@code level-up.*}）とは別物。</b>
 * あちらは {@code SkillExpFeedbackService} が本人にだけ出す音/チャット/タイトルで、こちらは全体行。
 * 片方だけ有効にできるよう設定も分けてある。
 *
 * <p>値の妥当性はここで丸める。とくに {@code multiple-of} は 0 以下だと剰余判定が成立しない
 * （0 除算になるか、全レベル通知という別の意味に化ける）ので、警告を出して既定へ戻す。
 */
public final class LevelBroadcastConfig implements LoadableConfig {

    public static final String PATH = "progression/level-broadcast.yml";

    /** 総合スキルの ID。{@code include-power} の判定にだけ使う。 */
    private static final String POWER_SKILL_ID = "POWER";

    private static final int DEFAULT_MULTIPLE_OF = 10;
    private static final int DEFAULT_MAX_PER_BATCH = 3;
    private static final String DEFAULT_MESSAGE =
            "<gold><bold>[祝!]</bold></gold> <yellow>%player%</yellow><gray> が </gray>"
                    + "<aqua>%skill%</aqua><gray> で </gray><green>Lv%level%</green>"
                    + "<gray> に到達しました!</gray>";
    /** プレステージ(NG+)段が1以上のときに使う書式。{@code %prestige%} が段番号。 */
    private static final String DEFAULT_MESSAGE_PRESTIGE =
            "<gold><bold>[祝!]</bold></gold> <yellow>%player%</yellow><gray> が </gray>"
                    + "<aqua>%skill%</aqua><gray>（</gray><white>%prestige%</white><gray>周目）で </gray>"
                    + "<green>Lv%level%</green><gray> に到達しました!</gray>";
    private static final String DEFAULT_SOUND = "UI_TOAST_CHALLENGE_COMPLETE";
    private static final int DEFAULT_MIN_INTERVAL_SECONDS = 30;
    private static final int MIN_INTERVAL_SECONDS_CEILING = 3600;

    private volatile boolean enabled = true;
    private volatile int multipleOf = DEFAULT_MULTIPLE_OF;
    private volatile String message = DEFAULT_MESSAGE;
    private volatile String messagePrestige = DEFAULT_MESSAGE_PRESTIGE;
    private volatile boolean includePower = false;
    private volatile int maxAnnouncementsPerBatch = DEFAULT_MAX_PER_BATCH;
    private volatile int minIntervalSeconds = DEFAULT_MIN_INTERVAL_SECONDS;
    private volatile boolean soundEnabled = true;
    private volatile String sound = DEFAULT_SOUND;
    private volatile float soundVolume = 1.0f;
    private volatile float soundPitch = 1.0f;
    private volatile Set<String> excludedSkills = Set.of();
    private volatile Set<Integer> excludedLevels = Set.of();

    public boolean enabled() {
        return enabled;
    }

    /** 常に 1 以上。 */
    public int multipleOf() {
        return multipleOf;
    }

    /** MiniMessage 書式。{@code %player%} / {@code %skill%} / {@code %level%} を差し込む。 */
    public String message() {
        return message;
    }

    /**
     * プレステージ(NG+)段が1以上のときに使う書式。{@code %prestige%}(段番号)が追加で使える。
     * プレステージ後は上限レベル到達時にしかアナウンスしない(呼び出し側リスナーの判定)
     * ため、こちらは常に「上限到達」の行になる。
     */
    public String messagePrestige() {
        return messagePrestige;
    }

    /** 総合(POWER)のレベルアップもアナウンスするか。既定 false。 */
    public boolean includePower() {
        return includePower;
    }

    /** 1 回のフラッシュで流す最大行数。常に 1 以上。 */
    public int maxAnnouncementsPerBatch() {
        return maxAnnouncementsPerBatch;
    }

    /**
     * 同一プレイヤーの全体放送を、この秒数の間に1行までへ絞る。0 で無効。常に 0〜3600。
     *
     * <p>プレステージの登り直し・EXP異常・管理コマンドの一括付与など、原因が何であれ
     * 「チャットが埋まる」こと自体を止める最後の砦（W-313）。
     */
    public int minIntervalSeconds() {
        return minIntervalSeconds;
    }

    public boolean soundEnabled() {
        return soundEnabled;
    }

    /** 効果音名（enum 風でもレジストリキーでもよい）。解決できなければ呼び出し側が無音に倒す。 */
    public String sound() {
        return sound;
    }

    public float soundVolume() {
        return soundVolume;
    }

    public float soundPitch() {
        return soundPitch;
    }

    /** 除外スキル（大文字へ正規化済み）。 */
    public Set<String> excludedSkills() {
        return excludedSkills;
    }

    public Set<Integer> excludedLevels() {
        return excludedLevels;
    }

    /**
     * {@code skillId} の {@code level} 到達を全体アナウンスすべきか。
     *
     * <p>判定を 1 箇所に集約してあるのは、リスナー側とテスト側で条件を書き分けると
     * 「除外したはずのスキルが片方の経路だけ通る」というズレが生えるため。
     *
     * @param skillId 大文字/小文字は問わない。{@code null} は常に false
     */
    public boolean shouldAnnounce(String skillId, int level) {
        if (!enabled || skillId == null || level <= 0) {
            return false;
        }
        String normalized = skillId.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        if (!includePower && POWER_SKILL_ID.equals(normalized)) {
            return false;
        }
        if (excludedSkills.contains(normalized)) {
            return false;
        }
        if (excludedLevels.contains(level)) {
            return false;
        }
        return level % multipleOf == 0;
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

        int rawMultiple = yaml.getInt("multiple-of", DEFAULT_MULTIPLE_OF);
        if (rawMultiple <= 0) {
            log.warning("[" + PATH + "] multiple-of(" + rawMultiple + ") は 1 以上である必要があります"
                    + "（0 以下は剰余判定が成立しない）。既定の " + DEFAULT_MULTIPLE_OF + " を使います。");
            rawMultiple = DEFAULT_MULTIPLE_OF;
        }
        this.multipleOf = rawMultiple;

        this.message = trimOrDefault(yaml.getString("message"), DEFAULT_MESSAGE);
        this.messagePrestige = trimOrDefault(yaml.getString("message-prestige"), DEFAULT_MESSAGE_PRESTIGE);
        this.includePower = yaml.getBoolean("include-power", false);
        this.maxAnnouncementsPerBatch = Math.max(1,
                yaml.getInt("max-announcements-per-batch", DEFAULT_MAX_PER_BATCH));
        // 範囲外(負数・巨大値)は警告せず丸める。0 は「無効」という正当な設定値なので既定へは戻さない
        // (multiple-of の 0 とは意味が違う: あちらは 0 除算で壊れるが、こちらの 0 は単に制限なしを表す)。
        this.minIntervalSeconds = (int) clamp(
                yaml.getInt("min-interval-seconds", DEFAULT_MIN_INTERVAL_SECONDS),
                0, MIN_INTERVAL_SECONDS_CEILING);

        this.soundEnabled = yaml.getBoolean("sound.enabled", true);
        this.sound = trimOrDefault(yaml.getString("sound.key"), DEFAULT_SOUND);
        // 音量は距離減衰にも使われるので上振れを許すが、青天井は事故のもとなので 10 で頭打ちにする。
        this.soundVolume = (float) clamp(yaml.getDouble("sound.volume", 1.0), 0.0, 10.0);
        // ピッチは Minecraft の有効域が 0.5〜2.0。外れた値はクライアントで丸められるので先に丸める。
        this.soundPitch = (float) clamp(yaml.getDouble("sound.pitch", 1.0), 0.5, 2.0);

        this.excludedSkills = readUpperCaseSet(yaml.getStringList("excluded-skills"));
        this.excludedLevels = readIntSet(yaml.getIntegerList("excluded-levels"));

        log.info("[" + PATH + "] loaded OK (enabled=" + enabled + ", multiple-of=" + multipleOf
                + ", include-power=" + includePower + ", excluded-skills=" + excludedSkills.size()
                + ", excluded-levels=" + excludedLevels.size() + ")");
        return true;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Set<String> readUpperCaseSet(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim().toUpperCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return Set.copyOf(out);
    }

    private static Set<Integer> readIntSet(List<Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<Integer> out = new LinkedHashSet<>();
        for (Integer entry : raw) {
            if (entry != null) {
                out.add(entry);
            }
        }
        return Set.copyOf(out);
    }

    /** 空白のみ/未設定を既定値へ寄せる（空文字を意味のある値として扱うキーには使わない）。 */
    private static String trimOrDefault(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
