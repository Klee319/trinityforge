package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code network.yml} のローダ: サーバをまたぐチャットと管理者 TP の設定 (2026-08-15)。
 *
 * <p><b>この config は 3 台で共有される。</b>{@code plugins/TrinityForge} は main の実体を
 * resource / dev がジャンクションで指しているので、サーバごとに違う値は置けない。
 * だから「自分がどのサーバか」は config ではなくプロキシに聞き
 * （{@link com.trinityforge.network.ProxyChannel#requestServerName()}）、
 * ここにはその名前から表示名を引く対応表だけを持たせる。
 */
public final class NetworkConfig implements LoadableConfig {

    public static final String PATH = "network.yml";

    private static final String DEFAULT_CHAT_FORMAT = "<gray>【</gray><aqua>%server%</aqua><gray>】</gray>"
            + "<white>%player%</white><gray>:</gray> <white>%message%</white>";
    private static final int DEFAULT_TELEPORT_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_ARRIVAL_DELAY_TICKS = 20;
    private static final int MIN_ARRIVAL_DELAY_TICKS = 1;
    private static final int MAX_ARRIVAL_DELAY_TICKS = 200;

    private volatile boolean enabled = true;
    private volatile boolean chatEnabled = true;
    private volatile String chatFormat = DEFAULT_CHAT_FORMAT;
    private volatile Map<String, String> serverDisplayNames = defaultDisplayNames();
    private volatile boolean teleportEnabled = true;
    private volatile int teleportTimeoutSeconds = DEFAULT_TELEPORT_TIMEOUT_SECONDS;
    private volatile int arrivalDelayTicks = DEFAULT_ARRIVAL_DELAY_TICKS;

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
        this.chatEnabled = yaml.getBoolean("chat.enabled", true);

        String format = yaml.getString("chat.format", DEFAULT_CHAT_FORMAT);
        this.chatFormat = (format == null || format.isBlank()) ? DEFAULT_CHAT_FORMAT : format;

        this.serverDisplayNames = readDisplayNames(yaml.getConfigurationSection("chat.servers"));

        this.teleportEnabled = yaml.getBoolean("teleport.enabled", true);
        this.teleportTimeoutSeconds = Math.max(1,
                yaml.getInt("teleport.timeout-seconds", DEFAULT_TELEPORT_TIMEOUT_SECONDS));
        this.arrivalDelayTicks = clamp(
                yaml.getInt("teleport.arrival-delay-ticks", DEFAULT_ARRIVAL_DELAY_TICKS),
                MIN_ARRIVAL_DELAY_TICKS, MAX_ARRIVAL_DELAY_TICKS);
        return true;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean chatEnabled() {
        return enabled && chatEnabled;
    }

    public String chatFormat() {
        return chatFormat;
    }

    public boolean teleportEnabled() {
        return enabled && teleportEnabled;
    }

    public int teleportTimeoutSeconds() {
        return teleportTimeoutSeconds;
    }

    public int arrivalDelayTicks() {
        return arrivalDelayTicks;
    }

    /**
     * プロキシ上のサーバ名（{@code main} など）を表示名（{@code メイン}）へ直す。
     * 対応表に無い名前はそのまま返す。<b>空表示にはしない</b> — どこの発言か分からなくなるより、
     * 設定漏れが目に見えるほうがよい。
     */
    public String displayName(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return "?";
        }
        String mapped = serverDisplayNames.get(serverName.toLowerCase(Locale.ROOT));
        return mapped != null ? mapped : serverName;
    }

    private static Map<String, String> readDisplayNames(ConfigurationSection section) {
        if (section == null) {
            return defaultDisplayNames();
        }
        Map<String, String> names = new HashMap<>();
        for (String key : section.getKeys(false)) {
            String value = section.getString(key);
            if (value != null && !value.isBlank()) {
                names.put(key.toLowerCase(Locale.ROOT), value);
            }
        }
        return names.isEmpty() ? defaultDisplayNames() : Map.copyOf(names);
    }

    private static Map<String, String> defaultDisplayNames() {
        return Map.of("main", "メイン", "resource", "資源", "dev", "開発");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
