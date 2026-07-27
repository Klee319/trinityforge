package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code afk.yml}: AFK(離席)判定のしきい値と、AFK中に止める報酬の内訳 (2026-07-27)。
 *
 * <p>値の妥当性はここで丸める(不正値でサーバ挙動が壊れるより、既定へ寄せて動き続けるほうがよい)。
 * とくに {@code kick-after-seconds} は {@code idle-seconds} 未満だと「AFKになった瞬間にキック」に
 * なってしまうので、0(=キックしない)でない限り {@code idle-seconds} 以上へ引き上げる。
 */
public final class AfkConfig implements LoadableConfig {

    public static final String PATH = "afk.yml";

    private static final int DEFAULT_IDLE_SECONDS = 300;
    private static final int DEFAULT_KICK_AFTER_SECONDS = 1800;
    private static final int DEFAULT_CHECK_INTERVAL_TICKS = 40;
    private static final int MIN_CHECK_INTERVAL_TICKS = 20;
    private static final String DEFAULT_KICK_MESSAGE =
            "<yellow>長時間の放置により切断しました。<newline><gray>またのご参加をお待ちしています。";
    private static final String DEFAULT_TAB_SUFFIX = " <gray>[AFK]</gray>";
    private static final String DEFAULT_EXEMPT_PERMISSION = "trinityforge.afk.exempt";

    private volatile boolean enabled = true;
    private volatile int idleSeconds = DEFAULT_IDLE_SECONDS;
    private volatile int kickAfterSeconds = DEFAULT_KICK_AFTER_SECONDS;
    private volatile String kickMessage = DEFAULT_KICK_MESSAGE;
    private volatile boolean notify = true;
    private volatile boolean tabSuffix = true;
    private volatile String tabSuffixText = DEFAULT_TAB_SUFFIX;
    private volatile String exemptPermission = DEFAULT_EXEMPT_PERMISSION;
    private volatile int checkIntervalTicks = DEFAULT_CHECK_INTERVAL_TICKS;
    private volatile boolean suppressSkillExp = true;
    private volatile boolean suppressVanillaExp = true;
    private volatile boolean suppressMobDrops = true;
    private volatile boolean suppressFishingSell = true;

    public boolean enabled() {
        return enabled;
    }

    public int idleSeconds() {
        return idleSeconds;
    }

    /** 0 = キックしない。0 以外なら必ず {@link #idleSeconds()} 以上。 */
    public int kickAfterSeconds() {
        return kickAfterSeconds;
    }

    public String kickMessage() {
        return kickMessage;
    }

    public boolean notifyPlayer() {
        return notify;
    }

    public boolean tabSuffix() {
        return tabSuffix;
    }

    public String tabSuffixText() {
        return tabSuffixText;
    }

    /** 空文字なら免除権限は無効。 */
    public String exemptPermission() {
        return exemptPermission;
    }

    public int checkIntervalTicks() {
        return checkIntervalTicks;
    }

    public boolean suppressSkillExp() {
        return enabled && suppressSkillExp;
    }

    public boolean suppressVanillaExp() {
        return enabled && suppressVanillaExp;
    }

    public boolean suppressMobDrops() {
        return enabled && suppressMobDrops;
    }

    public boolean suppressFishingSell() {
        return enabled && suppressFishingSell;
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
        this.idleSeconds = Math.max(1, yaml.getInt("idle-seconds", DEFAULT_IDLE_SECONDS));

        int rawKick = Math.max(0, yaml.getInt("kick-after-seconds", DEFAULT_KICK_AFTER_SECONDS));
        if (rawKick > 0 && rawKick < idleSeconds) {
            log.warning("[" + PATH + "] kick-after-seconds(" + rawKick + ") が idle-seconds("
                    + idleSeconds + ") 未満です。AFK判定と同時にキックされるのを避けるため "
                    + idleSeconds + " へ引き上げました。");
            rawKick = idleSeconds;
        }
        this.kickAfterSeconds = rawKick;

        this.kickMessage = trimOrDefault(yaml.getString("kick-message"), DEFAULT_KICK_MESSAGE);
        this.notify = yaml.getBoolean("notify", true);
        this.tabSuffix = yaml.getBoolean("tab-suffix", true);
        this.tabSuffixText = trimOrDefault(yaml.getString("tab-suffix-text"), DEFAULT_TAB_SUFFIX);
        // 免除権限は「空文字で無効化」を許すので、trimOrDefault ではなく素直に読む。
        String rawPermission = yaml.getString("exempt-permission", DEFAULT_EXEMPT_PERMISSION);
        this.exemptPermission = rawPermission == null ? "" : rawPermission.trim();
        this.checkIntervalTicks = Math.max(MIN_CHECK_INTERVAL_TICKS,
                yaml.getInt("check-interval-ticks", DEFAULT_CHECK_INTERVAL_TICKS));

        this.suppressSkillExp = yaml.getBoolean("suppress.skill-exp", true);
        this.suppressVanillaExp = yaml.getBoolean("suppress.vanilla-exp", true);
        this.suppressMobDrops = yaml.getBoolean("suppress.mob-drops", true);
        this.suppressFishingSell = yaml.getBoolean("suppress.fishing-sell", true);

        log.info("[" + PATH + "] loaded OK (enabled=" + enabled + ", idle=" + idleSeconds + "s, kick="
                + (kickAfterSeconds == 0 ? "off" : kickAfterSeconds + "s") + ")");
        return true;
    }

    /** 空白のみ/未設定を既定値へ寄せる(空文字を意味のある値として扱うキーには使わない)。 */
    private static String trimOrDefault(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
