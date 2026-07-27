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
        log.info("[" + PATH + "] loaded OK");
        return true;
    }
}
