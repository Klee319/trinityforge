package com.trinityforge.config.domains;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code combat/display.yml}: cosmetic combat-display toggles (focus-target HP overlay,
 * per-hit damage popup) that carry no gameplay/balance weight.
 */
public final class DisplayConfig {

    public static final String PATH = "combat/display.yml";

    private volatile boolean focusHpEnabled = true;
    private volatile boolean damagePopupEnabled = true;
    private volatile int damagePopupDurationTicks = 15;
    private volatile double damagePopupMinDamage = 1.0;
    private volatile int damageIndicatorMaxCount = DEFAULT_DAMAGE_INDICATOR_MAX_COUNT;

    /** {@code damage-indicator-particles.max-count} の既定値。 */
    public static final int DEFAULT_DAMAGE_INDICATOR_MAX_COUNT = 4;
    /** {@code max-count} に指定できる「制限しない」を表す値。 */
    public static final int DAMAGE_INDICATOR_UNLIMITED = -1;

    /** true(既定) = 注視中モブのHPオーバーレイ(FocusHpDisplay)を表示する。 */
    public boolean focusHpEnabled() {
        return focusHpEnabled;
    }

    /** true(既定) = 与ダメージの数値ポップアップ(DamagePopupDisplay)を表示する。 */
    public boolean damagePopupEnabled() {
        return damagePopupEnabled;
    }

    /** ポップアップ表示を維持するtick数(既定15 = 0.75秒)。 */
    public int damagePopupDurationTicks() {
        return damagePopupDurationTicks;
    }

    /** この値未満のfinalDamageではポップアップを出さない(既定1.0)。 */
    public double damagePopupMinDamage() {
        return damagePopupMinDamage;
    }

    /**
     * 1ヒットあたりに表示するバニラ {@code damage_indicator} パーティクルの個数上限。
     * {@code 0} = 完全に消す / {@link #DAMAGE_INDICATOR_UNLIMITED}({@code -1}) = 制限しない。
     * 負値は全て {@code -1} に正規化されるので、呼び出し側は {@code < 0} で「無制限」を判定してよい。
     */
    public int damageIndicatorMaxCount() {
        return damageIndicatorMaxCount;
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

        this.focusHpEnabled = yaml.getBoolean("focus-hp.enabled", true);
        this.damagePopupEnabled = yaml.getBoolean("damage-popup.enabled", true);
        this.damagePopupDurationTicks = Math.max(1, yaml.getInt("damage-popup.duration-ticks", 15));
        this.damagePopupMinDamage = Math.max(0.0, yaml.getDouble("damage-popup.min-damage", 1.0));
        int maxCount = yaml.getInt("damage-indicator-particles.max-count",
                DEFAULT_DAMAGE_INDICATOR_MAX_COUNT);
        // 負値は全て「制限しない」に丸める(-5 と -1 で挙動が変わるのは事故のもと)。
        this.damageIndicatorMaxCount = maxCount < 0 ? DAMAGE_INDICATOR_UNLIMITED : maxCount;
        log.info("[" + PATH + "] loaded OK");
        return true;
    }
}
