package com.trinityforge.config.domains;

import com.trinityforge.integration.ars.ArsProgressionBridge;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code stats/craft-quality.yml} (ITEM_ECONOMY_SPEC 5.2d): how a CRAFTED item's quality is
 * derived (fished items moved to the rod-driven {@link com.trinityforge.listeners.FishingQualityListener}
 * / {@code stats/fishing-gimmick.yml}, so {@code fishing.*} no longer lives here). {@link #categorySkill()} is a
 * fixed (hard-coded) item stat-category -> production skill map — the crafting production skill lineup
 * ({@code weapon/armor/tool -> SMITHING}, {@code ars-gear -> ARS_SMITHING}) is a stable game rule, not an
 * operator knob — and {@code mode.*} tunes level->quality. (Skill EXP gain rates, e.g. ARS_SMITHING craft
 * EXP, live in {@code stats/skill-exp.yml} / {@link SkillExpConfig} instead.)
 *
 * <p>2026-08-01: {@code workbench.*} / {@code ritual.*} 節を追加。品質抽選のばらつき(σ)補正を
 * 作業台クラフトと儀式(ArsPaperのリチュアル)で別々に設定できる({@link SpreadTuning})。
 */
public final class CraftQualityConfig {

    public static final String PATH = "stats/craft-quality.yml";

    // 固定(ハードコード)の カテゴリ -> 生産スキル 対応。運用上変わらないゲームルールのため設定化しない。
    private static final Map<String, String> CATEGORY_SKILL = Map.of(
            "weapon", "SMITHING",
            "armor", "SMITHING",
            "tool", "SMITHING",
            "ars-gear", ArsProgressionBridge.ARS_SMITHING);

    private volatile int skillLevelsPerQuality = 10;
    private volatile int baseQuality = 0;
    private volatile boolean dropEnabled = true;
    private volatile int dropStrengthPerQuality = 10;
    private volatile int dropBaseQuality = 0;
    private volatile SpreadTuning workbenchSpread = SpreadTuning.IDENTITY;
    private volatile SpreadTuning ritualSpread = SpreadTuning.IDENTITY;

    /**
     * 品質抽選のばらつき(σ)を経路ごとに補正するパラメータ(2026-08-01 作業台/儀式 分離)。
     *
     * <p>従来は作業台クラフトも儀式クラフトも {@code quality.yml spread-up/spread-down} +
     * プレイヤーステ({@code craft_upswing_bonus} / {@code craft_downswing_reduction})という
     * 完全に同じ式を通っており、「儀式だけ上振れを抑える」ような調整ができなかった。
     * ここで経路ごとに「ステを何倍で効かせるか(scale)」「その経路にだけ無条件で足すσ(flat)」を持つ。
     *
     * <p><b>既定値 {@link #IDENTITY}(scale=1.0 / flat=0.0)は分離前と数式が完全に一致する</b> —
     * 分割しただけでバランスが動かないことを既定値で保証する。
     *
     * @param upswingScale             上振れσへ加算する {@code craft_upswing_bonus} の倍率
     * @param upswingFlat              上振れσへ無条件に足す値(ステとは独立)
     * @param downswingReductionScale  下振れσから引く {@code craft_downswing_reduction} の倍率
     * @param downswingReductionFlat   下振れσから無条件に引く値(ステとは独立)
     */
    public record SpreadTuning(double upswingScale, double upswingFlat,
                               double downswingReductionScale, double downswingReductionFlat) {

        /** 分離前と同じ挙動になる恒等パラメータ。 */
        public static final SpreadTuning IDENTITY = new SpreadTuning(1.0, 0.0, 1.0, 0.0);

        /**
         * この経路の実効上振れσ。{@code base}=quality.yml の {@code spread-up}、
         * {@code statBonus}=クラフターの {@code craft_upswing_bonus}(負値は呼び出し側で0に潰す)。
         * σ は負にならない(0 は「上側は mode 固定」の意味)。
         */
        public double effectiveSpreadUp(double base, double statBonus) {
            return Math.max(0.0, base + upswingFlat + Math.max(0.0, statBonus) * upswingScale);
        }

        /**
         * この経路の実効下振れσ。{@code base}=quality.yml の {@code spread-down}、
         * {@code statReduction}=クラフターの {@code craft_downswing_reduction}(負値は0に潰す)。
         */
        public double effectiveSpreadDown(double base, double statReduction) {
            return Math.max(0.0, base - downswingReductionFlat
                    - Math.max(0.0, statReduction) * downswingReductionScale);
        }
    }

    /** 作業台クラフト経路のばらつき補正({@code workbench.*})。 */
    public SpreadTuning workbenchSpread() {
        return workbenchSpread;
    }

    /** 儀式クラフト経路のばらつき補正({@code ritual.*})。 */
    public SpreadTuning ritualSpread() {
        return ritualSpread;
    }

    /** Fixed item stat-category -> production skill map (not config-driven; see class javadoc). */
    public Map<String, String> categorySkill() {
        return CATEGORY_SKILL;
    }

    public int skillLevelsPerQuality() {
        return skillLevelsPerQuality;
    }

    public int baseQuality() {
        return baseQuality;
    }

    /**
     * Whether mob-drop quality is driven by enemy strength: a normal (bell) distribution whose mode
     * shifts up with the elite level (see {@code stats/craft-quality.yml drop}). When {@code false} the
     * EliteMobs fork keeps a uniform-random placeholder over the valid quality range.
     */
    public boolean dropEnabled() {
        return dropEnabled;
    }

    /** Enemy levels per +1 mob-drop quality mode (integer division; ≤0 pins the mode at the base). */
    public int dropStrengthPerQuality() {
        return dropStrengthPerQuality;
    }

    /** Mob-drop quality mode for an enemy of strength 0 (or a non-elite). */
    public int dropBaseQuality() {
        return dropBaseQuality;
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

        apply(yaml);
        log.info("[" + PATH + "] loaded OK");
        return true;
    }

    /**
     * パース本体。{@link #load(Plugin)} からも、ファイルを介さずに値を流し込みたいテストからも使う
     * (Bukkit の Plugin/データフォルダを用意せずに設定の解釈だけを検証できるようにするため)。
     */
    public void apply(YamlConfiguration yaml) {
        this.skillLevelsPerQuality = yaml.getInt("mode.skill-levels-per-quality", 10);
        this.baseQuality = yaml.getInt("mode.base-quality", 0);
        this.dropEnabled = yaml.getBoolean("drop.enabled", true);
        this.dropStrengthPerQuality = yaml.getInt("drop.strength-per-quality", 10);
        this.dropBaseQuality = yaml.getInt("drop.base-quality", 0);
        // 節ごと欠落しても IDENTITY(=分離前と同じ挙動)へ落ちるので、旧 yml をそのまま読んでも安全。
        this.workbenchSpread = readSpread(yaml, "workbench");
        this.ritualSpread = readSpread(yaml, "ritual");
    }

    /**
     * {@code workbench.*} / {@code ritual.*} のばらつき補正を読む。既定値は
     * {@link SpreadTuning#IDENTITY} と厳密に一致させること — config editor 側の
     * 既定値がこことズレると「開いて保存しただけで yml の意味が変わる」事故になる。
     */
    private static SpreadTuning readSpread(YamlConfiguration yaml, String section) {
        SpreadTuning d = SpreadTuning.IDENTITY;
        return new SpreadTuning(
                yaml.getDouble(section + ".upswing-scale", d.upswingScale()),
                yaml.getDouble(section + ".upswing-flat", d.upswingFlat()),
                yaml.getDouble(section + ".downswing-reduction-scale", d.downswingReductionScale()),
                yaml.getDouble(section + ".downswing-reduction-flat", d.downswingReductionFlat()));
    }
}
