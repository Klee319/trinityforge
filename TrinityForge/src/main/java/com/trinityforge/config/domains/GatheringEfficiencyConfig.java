package com.trinityforge.config.domains;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code stats/gathering-efficiency.yml}: the single tunable knob for
 * {@code com.trinityforge.gathering.GatheringEfficiencyEnchantApplier} (2026-07-25 採集効率エンチャント
 * 連動方式) — the ceiling on the runtime Efficiency enchant level TF mirrors from the aggregated
 * {@code gathering-efficiency} stat onto a mainhand FARMING/MINING/WOODCUTTING/DIGGING tool.
 *
 * <p>2026-07-26 ユーザー決定(効率ステータス統合＋上限撤廃): {@code max-enchant-level: 0}(または負値)は
 * 「無制限」を意味する — これが既定値になった。正の値を書けば従来どおりその値が上限として機能する
 * (後方互換: 既存ymlに書かれた正の上限値はそのまま尊重される)。無制限時でも内部ハード上限255
 * ({@link com.trinityforge.gathering.GatheringEfficiencyMath#resolveLevel} 側)は超えない。
 *
 * <p>旧仕様(既定5=バニラ本来の最大値、6以上はGeyserバグGeyserMC/Geyser#5843を踏むため非推奨)は
 * ユーザー決定により撤廃された。無制限運用はバランス上のリスクを伴う(採掘速度は効率レベルの
 * {@code level^2 + 1} で伸びるため、大きすぎる値は事故になりうる)ので、設定者への注意は出荷ymlの
 * コメントに委ねる(このクラス自体はもう上限のガード/警告をしない)。
 */
public final class GatheringEfficiencyConfig {

    public static final String PATH = "stats/gathering-efficiency.yml";

    /** 既定値0 = 無制限(2026-07-26 ユーザー決定でデフォルトを撤廃側に変更)。 */
    private static final int DEFAULT_MAX_ENCHANT_LEVEL = 0;

    private volatile int maxEnchantLevel = DEFAULT_MAX_ENCHANT_LEVEL;

    /**
     * The Efficiency enchant level ceiling TF will mirror the {@code gathering-efficiency} stat up to.
     * {@code <= 0} means unlimited (subject only to
     * {@link com.trinityforge.gathering.GatheringEfficiencyMath}'s internal hard cap); a positive value
     * is the configured ceiling, exactly as before this key's semantics were extended.
     */
    public int maxEnchantLevel() {
        return maxEnchantLevel;
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

        // 0以下は「無制限」として扱う(2026-07-26 ユーザー決定)。上限撤廃なので、もはや負値を
        // エラー扱いして既定へ差し戻す必要はない — 0と同じ「無制限」として素通しする。
        this.maxEnchantLevel = yaml.getInt("max-enchant-level", DEFAULT_MAX_ENCHANT_LEVEL);
        return true;
    }
}
