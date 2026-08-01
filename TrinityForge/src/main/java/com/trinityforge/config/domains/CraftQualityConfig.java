package com.trinityforge.config.domains;

import com.trinityforge.integration.ars.ArsProgressionBridge;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
     * <p><b>既定値 {@link #IDENTITY}(scale=1.0 / flat=0.0)は、config から到達できる入力に対して
     * 分離前と同じ値を返す</b> — 分割しただけでバランスが動かないことを既定値で保証する。
     *
     * <p><b>ただし式そのものは1箇所だけ分離前と違う(意図的)</b>: <b>上振れ側の
     * {@code Math.max(0.0, …)} は分離で新設したもの</b>で、分離前の上振れは
     * {@code spread-up + max(0, ステ)} とクランプ無しだった(下振れ側のクランプは分離前からある)。
     * 新設したのは {@code upswing-flat} に<b>負値を書けるようにした</b>ためで、これが無いと
     * {@code upswing-flat: -999} で σ が負に振り切れ、分布の意味が壊れる。
     * <b>既定値では発火しない</b>: {@code base} は {@link QualityConfig#spreadUp()} が既に 0 未満へ
     * 落とさない値、{@code flat}=0、ステ側も {@code max(0, …)} 済みなので、加算結果は必ず 0 以上になる。
     * したがって差が出るのは<b>負の {@code base} を直接渡したときだけ</b>で、それは config からは
     * 作れない入力({@code CraftQualitySpreadSplitTest} がこの境界を固定している)。
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
         *
         * <p>この 0 クランプは 2026-08-01 の分離で<b>新設</b>したもの(分離前はクランプ無し)。
         * 理由と「既定値では発火しない」根拠はクラス javadoc を参照。
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

    /**
     * <b>どの経路でも scale=0 で、完全に効果が無くなっている</b>ばらつきステの canonical キー
     * (2026-08-01)。lore からその行を落とすために {@code LoreComposer#useInertStatKeys} へ渡す
     * ——「設定で殺したのに lore には出たまま」を作らないため(表示と実装の一致)。
     *
     * <p>判定は<b>倍率(scale)だけ</b>を見る。{@code flat} はステとは独立にσを動かす値なので、
     * flat が入っていてもそのステが効くようになるわけではない。逆に scale が 0 以外なら
     * (負値でも)ステは結果に影響するので inert ではない。
     *
     * <p><b>2026-08-01 の設計変更に追随</b>: ばらつきステは経路共通の
     * {@code craft_upswing_bonus} / {@code craft_downswing_reduction} から
     * <b>経路別の4キー</b>へ分割された(共通キーのままだと鍛冶ツリーのパークが儀式へ、
     * 魔法鍛冶ツリーのパークが作業台へ漏れるため)。分割後は<b>1キーが1経路にしか属さない</b>ので、
     * 旧実装の「経路が2つとも 0 のときだけ落とす」という条件は不要になり、
     * <b>そのキーの経路の scale が 0 かどうかだけ</b>を見ればよい。
     * (旧条件のまま経路別キーへ当てると、作業台だけ 0 にしても儀式が生きている限り
     *  作業台側の死んだステが lore に出続ける、という取りこぼしになる。)
     */
    public Set<String> inertSpreadStatKeys() {
        SpreadTuning workbench = workbenchSpread == null ? SpreadTuning.IDENTITY : workbenchSpread;
        SpreadTuning ritual = ritualSpread == null ? SpreadTuning.IDENTITY : ritualSpread;
        Set<String> inert = new LinkedHashSet<>();
        if (workbench.upswingScale() == 0.0) {
            inert.add(WORKBENCH_UPSWING_STAT_KEY);
        }
        if (ritual.upswingScale() == 0.0) {
            inert.add(RITUAL_UPSWING_STAT_KEY);
        }
        if (workbench.downswingReductionScale() == 0.0) {
            inert.add(WORKBENCH_DOWNSWING_STAT_KEY);
        }
        if (ritual.downswingReductionScale() == 0.0) {
            inert.add(RITUAL_DOWNSWING_STAT_KEY);
        }
        return Set.copyOf(inert);
    }

    /** 作業台クラフトの上振れ増加ステ({@code CraftQualityService#upswingBonusFor} が読むキーと同一)。 */
    public static final String WORKBENCH_UPSWING_STAT_KEY = "workbench_upswing_bonus";

    /** 儀式クラフトの上振れ増加ステ。 */
    public static final String RITUAL_UPSWING_STAT_KEY = "ritual_upswing_bonus";

    /** 作業台クラフトの下振れ抑制ステ。 */
    public static final String WORKBENCH_DOWNSWING_STAT_KEY = "workbench_downswing_reduction";

    /** 儀式クラフトの下振れ抑制ステ。 */
    public static final String RITUAL_DOWNSWING_STAT_KEY = "ritual_downswing_reduction";

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
