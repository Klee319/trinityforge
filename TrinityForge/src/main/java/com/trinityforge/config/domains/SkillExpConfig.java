package com.trinityforge.config.domains;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code stats/skill-exp.yml}: per-skill EXP gain rates for TrinityForge custom skills
 * (ARS_SMITHING craft EXP, ARS_MAGIC cast/mana EXP; future skills append here).
 */
public final class SkillExpConfig {

    public static final String PATH = "stats/skill-exp.yml";

    /** タスク1: 採取EXPの算出方式({@code gathering.exp-mode})。 */
    public enum GatheringExpMode { DROP_SUM, BLOCK_VALUE, MAX }

    private volatile double arsSmithingExpPerCraft = 10.0;
    // 2026-07-25 PRG-13: SMITHING EXPは耐久消耗ベースの付与(旧onItemDamage)を廃止し、ARS_SMITHINGと同じ
    // 「武器/防具/ツールをクラフトした瞬間」に一本化した(CraftQualityListener#onCraft、
    // categorySkill: weapon/armor/tool -> SMITHING)。耐久消耗はプレイ時間に比例して無限に発生させられる
    // (修理して壊す/掘り続ける等)ため無限EXP経路になっていたが、クラフトは1回の生産行為に対して1回しか
    // 発生しない(素材の入手コストが実質的な上限になる)。既定値15.0は、旧durability方式で
    // ダイヤモンドツール1本を耐久使い切るまでに得られたEXP量(耐久1561×tool_stack0.01≈15.6)を目安に
    // 「装備1個クラフト = ツール1本を使い切った程度」として据えた暫定値。実プレイでの体感ペースを見て
    // 要調整。
    private volatile double smithingExpPerCraft = 15.0;
    private volatile double arsMagicExpPerCast = 2.0;
    private volatile double arsMagicExpPerMana = 0.1;
    private volatile double combatExpPerHit = 1.0;
    private volatile Map<String, Double> combatExpBySkill = Map.of();
    private volatile double combatSameTargetCooldownSeconds = 10.0;
    private volatile boolean dungeonOnlyExp = true;
    // --- 2026-07-26 オーバーワールドEXP開放: ダンジョン外の戦闘スキルEXP倍率 ---
    // dungeon-only-exp を false にした上で、ダンジョン外(オーバーワールド等)で得られる戦闘スキルEXPを
    // この倍率まで絞る。1.0 = ダンジョンと同率、0.0 = 従来の dungeon-only-exp: true と同じ完全遮断。
    // 「オーバーワールドでも育つが、メインはダンジョン」を数値で担保するための鍵。
    private volatile double outsideDungeonExpRate = 0.25;
    // --- 2026-07-26 TT/放置対策: 同一地点の逓減 (com.trinityforge.progression.LocationExpDiminishing) ---
    private volatile boolean spotDiminishingEnabled = true;
    private volatile double spotDiminishingRadius = 24.0;
    private volatile double spotDiminishingWindowSeconds = 300.0;
    private volatile int spotDiminishingThreshold = 30;
    private volatile double spotDiminishingDecayPerKill = 0.05;
    private volatile double spotDiminishingFloor = 0.1;
    private volatile boolean spotDiminishingExemptsDungeons = true;
    // --- 2026-07-26 EXP調整タスク1: 採取EXPの算出方式 ---
    // drop_sum(既定): 従来どおりドロップ品の合計値(現行の実サーバ挙動と完全一致・変更なし)。
    // block_value   : ブロック自体に設定された値(mining_break.<ブロック名>等)をそのまま使う。
    //                 深層岩バリアント等、ドロップ品が同じでもブロック側の値が違うケースに反映される。
    // max           : ドロップ合計とブロック値の大きい方。
    private volatile GatheringExpMode gatheringExpMode = GatheringExpMode.DROP_SUM;
    // --- 2026-07-26 EXP調整タスク2: 戦闘EXPの算出方式 ---
    // false(既定): 従来どおり combat.exp-per-hit / combat.by-skill の固定値(現行挙動と完全一致)。
    // true        : 与ダメージ×damage-scale に、モブレベル×mob-level-scale ぶんの倍率を掛けた値。
    private volatile boolean combatDamageScaledMode = false;
    private volatile double combatDamageScale = 0.1;
    private volatile double combatMobLevelScale = 0.02;
    // --- 2026-07-26 EXP調整タスク3: レベル逓減カーブ(既定OFF) ---
    private volatile boolean gatheringExpDiminishingEnabled = false;
    private volatile boolean combatExpDiminishingEnabled = false;
    private volatile String expDiminishingFormula = "1 / (1 + %level% / 50)";
    private volatile double expDiminishingFloor = 0.2;
    // --- EXP獲得/レベルアップ表示 (S5/S6) ---
    // exp-display.mode: "bossbar"(既定, レベル+EXP+獲得量をボスバー表示) / "actionbar"(獲得量のみアクションバー)
    private volatile boolean expDisplayActionBarOnly = false;
    private volatile double expBarSeconds = 4.0;
    // B3(2026-07-25 バグ報告): 複数スキルのEXPが同tickで同時に入っても、スキルごとに独立したボスバーを
    // 出す(既定でボスバーが画面を埋めないよう同時表示数に上限を設ける)。
    private volatile int maxConcurrentBossBars = 4;
    private volatile boolean levelUpChat = true;
    private volatile boolean levelUpSoundEnabled = true;
    private volatile String levelUpSound = "ENTITY_PLAYER_LEVELUP";
    private volatile int titleEveryLevels = 10;

    /** ARS_SMITHING experience granted when a player crafts Ars gear (the custom skill's EXP source). */
    public double arsSmithingExpPerCraft() {
        return arsSmithingExpPerCraft;
    }

    /**
     * SMITHING experience granted when a player crafts weapon/armor/tool equipment (PRG-13: the sole
     * SMITHING EXP source since the durability-based grant was removed).
     */
    public double smithingExpPerCraft() {
        return smithingExpPerCraft;
    }

    /** ARS_MAGIC base EXP granted per successful spell cast. */
    public double arsMagicExpPerCast() {
        return arsMagicExpPerCast;
    }

    /** ARS_MAGIC additional EXP per point of mana consumed on cast. */
    public double arsMagicExpPerMana() {
        return arsMagicExpPerMana;
    }

    /** Default weapon-skill EXP granted per successful player attack hit. */
    public double combatExpPerHit() {
        return combatExpPerHit;
    }

    /** EXP for a weapon hit when the weapon's use-skill is {@code skillType}; falls back to {@link #combatExpPerHit()}. */
    public double combatExpForSkill(String skillType) {
        if (skillType == null || skillType.isBlank()) {
            return 0.0;
        }
        return combatExpBySkill.getOrDefault(skillType, combatExpPerHit);
    }

    /**
     * Exploit fix (武器スキルEXP無限farm): per-(attacker,target) cooldown, in seconds, during which a
     * landed hit on the SAME target grants no combat skill EXP (a fresh target always grants normally).
     */
    public double combatSameTargetCooldownSeconds() {
        return combatSameTargetCooldownSeconds;
    }

    /**
     * true = 武器スキルEXP等はダンジョン内でのみ付与される想定(top-level {@code dungeon-only-exp})。
     * このゲート判定自体は別タスクで実装する。
     */
    public boolean dungeonOnlyExp() {
        return dungeonOnlyExp;
    }

    /**
     * ダンジョン外で戦闘スキルEXPに掛かる倍率 [0,1] (2026-07-26 オーバーワールドEXP開放)。
     *
     * <p>{@link #dungeonOnlyExp()} が {@code true} のときは従来どおり完全遮断が優先されるため、この値は
     * 参照されない。両者の合成は {@link #worldExpRate(boolean)} が一箇所で行う — 呼び出し側が
     * 「フラグを見てから倍率も見る」という二段判定を書くと、片方を忘れた経路が静かに全額付与になるため。
     */
    public double outsideDungeonExpRate() {
        return outsideDungeonExpRate;
    }

    /**
     * そのワールドで戦闘スキルEXPに掛けるべき倍率。ダンジョン内は常に 1.0、ダンジョン外は
     * {@code dungeon-only-exp: true} なら 0.0、false なら {@link #outsideDungeonExpRate()}。
     *
     * <p>武器EXP({@code CombatListener}、命中トリガ)と防具EXP({@code NativeSkillExperienceListener}、
     * 被弾トリガ)の両方がここを通る。倍率0は「付与しない」と等価なので、呼び出し側は {@code > 0} の
     * 判定だけで従来の真偽ゲートと同じ早期リターンを書ける。
     */
    public double worldExpRate(boolean inDungeonWorld) {
        if (inDungeonWorld) {
            return 1.0;
        }
        return dungeonOnlyExp ? 0.0 : outsideDungeonExpRate;
    }

    // --- TT/放置対策: 同一地点の逓減 (spot-diminishing) ---

    /** true = 同一地点での連続稼ぎに逓減を掛ける(既定ON)。 */
    public boolean spotDiminishingEnabled() {
        return spotDiminishingEnabled;
    }

    /** 「同じ場所」とみなす半径(ブロック)。 */
    public double spotDiminishingRadius() {
        return spotDiminishingRadius;
    }

    /** 直近何秒ぶんの獲得を数えるか。 */
    public double spotDiminishingWindowSeconds() {
        return spotDiminishingWindowSeconds;
    }

    /** ここまでは倍率1.0のまま許される回数(窓内・半径内)。 */
    public int spotDiminishingThreshold() {
        return spotDiminishingThreshold;
    }

    /** しきい値を超えた1回あたりの減少幅。 */
    public double spotDiminishingDecayPerKill() {
        return spotDiminishingDecayPerKill;
    }

    /** 倍率の下限。0にしない = 「稼げない」ではなく「割に合わない」に留める。 */
    public double spotDiminishingFloor() {
        return spotDiminishingFloor;
    }

    /** true = ダンジョンワールドでは逓減しない(既定ON。アリーナ型の正常な遊び方を罰しないため)。 */
    public boolean spotDiminishingExemptsDungeons() {
        return spotDiminishingExemptsDungeons;
    }

    /** タスク1: 採取EXPの算出方式(既定 {@link GatheringExpMode#DROP_SUM} = 現行挙動と完全一致)。 */
    public GatheringExpMode gatheringExpMode() {
        return gatheringExpMode;
    }

    /** タスク2: true = 戦闘EXPを与ダメージ比例+モブレベル係数で算出する(既定false=固定値のまま)。 */
    public boolean combatDamageScaledMode() {
        return combatDamageScaledMode;
    }

    /** タスク2: damage_scaledモード時、与ダメージ1あたりのEXP係数。 */
    public double combatDamageScale() {
        return combatDamageScale;
    }

    /** タスク2: damage_scaledモード時、モブレベル1につき加算される倍率(倍率 = 1 + level * この値)。 */
    public double combatMobLevelScale() {
        return combatMobLevelScale;
    }

    /** タスク3: 採取スキル(MINING/FARMING/WOODCUTTING/DIGGING)へレベル逓減カーブを適用するか(既定false)。 */
    public boolean gatheringExpDiminishingEnabled() {
        return gatheringExpDiminishingEnabled;
    }

    /** タスク3: 戦闘スキル(武器/防具/ARS_MAGIC)へレベル逓減カーブを適用するか(既定false)。 */
    public boolean combatExpDiminishingEnabled() {
        return combatExpDiminishingEnabled;
    }

    /**
     * タスク3: 逓減カーブの式({@code %level%} を含む {@code FormulaParser} 互換の式文字列)。
     * 評価結果はそのままEXP倍率として使われる(1.0=減衰なし、0.5=半分、…)。{@link #expDiminishingFloor()}
     * で下限クランプされる。
     */
    public String expDiminishingFormula() {
        return expDiminishingFormula;
    }

    /** タスク3: 逓減カーブ倍率の下限(これ未満には下がらない)。既定0.2 = どれだけレベルが上がっても最低20%は残す。 */
    public double expDiminishingFloor() {
        return expDiminishingFloor;
    }

    /** true = 獲得量のみアクションバー表示。false(既定) = レベル/EXP/獲得量をボスバー表示 (S5)。 */
    public boolean expDisplayActionBarOnly() {
        return expDisplayActionBarOnly;
    }

    /** ボスバーを表示し続ける秒数 (S5)。 */
    public double expBarSeconds() {
        return expBarSeconds;
    }

    /**
     * 1プレイヤーあたり同時に表示できるスキルEXPボスバーの最大本数 (B3)。既定4。上限を超えたら
     * 最も古く追加されたスキルのボスバーから閉じる(FIFO、{@code SkillExpFeedbackService} 参照)。
     * 0以下(未スタブのモック等を含む)は安全側で1として扱う(表示が完全に消えることは絶対に避ける)。
     */
    public int maxConcurrentBossBars() {
        return maxConcurrentBossBars;
    }

    /** レベルアップ時にチャット通知するか (S6)。 */
    public boolean levelUpChat() {
        return levelUpChat;
    }

    /** レベルアップ時に通知音を鳴らすか (S6)。 */
    public boolean levelUpSoundEnabled() {
        return levelUpSoundEnabled;
    }

    /** レベルアップ通知音 ({@code org.bukkit.Sound} 名)。不正名なら無音 (S6)。 */
    public String levelUpSound() {
        return levelUpSound;
    }

    /** この倍数のレベルに到達した時タイトルで通知 (S6, 既定10 = 10lvごと)。0以下で無効。 */
    public int titleEveryLevels() {
        return titleEveryLevels;
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

        applyFrom(yaml, log);
        log.info("[" + PATH + "] loaded OK");
        return true;
    }

    /**
     * YAMLからフィールドへ写す部分だけを切り出した seam。{@link #load(Plugin)} はファイルI/Oと
     * {@link Plugin} 依存を持つためユニットテストから叩けないが、値のクランプや既定値・キーの合成規則は
     * まさにテストしたい部分なので、ここだけを {@link org.bukkit.configuration.ConfigurationSection}
     * 受け取りにして分離してある。
     */
    void applyFrom(org.bukkit.configuration.ConfigurationSection yaml, Logger log) {
        this.arsSmithingExpPerCraft = Math.max(0.0, yaml.getDouble("ars-smithing.exp-per-craft", 10.0));
        this.smithingExpPerCraft = Math.max(0.0, yaml.getDouble("smithing.exp-per-craft", 15.0));
        this.arsMagicExpPerCast = Math.max(0.0, yaml.getDouble("ars-magic.exp-per-cast", 2.0));
        this.arsMagicExpPerMana = Math.max(0.0, yaml.getDouble("ars-magic.exp-per-mana", 0.1));
        this.combatExpPerHit = Math.max(0.0, yaml.getDouble("combat.exp-per-hit", 1.0));
        Map<String, Double> bySkill = new LinkedHashMap<>();
        var bySkillSection = yaml.getConfigurationSection("combat.by-skill");
        if (bySkillSection != null) {
            for (String key : bySkillSection.getKeys(false)) {
                bySkill.put(key, Math.max(0.0, bySkillSection.getDouble(key, combatExpPerHit)));
            }
        }
        this.combatExpBySkill = Collections.unmodifiableMap(bySkill);
        this.combatSameTargetCooldownSeconds =
                Math.max(0.0, yaml.getDouble("combat.same-target-cooldown-seconds", 10.0));
        this.dungeonOnlyExp = yaml.getBoolean("dungeon-only-exp", true);
        this.outsideDungeonExpRate = Math.max(0.0, Math.min(1.0,
                yaml.getDouble("outside-dungeon-exp-rate", 0.25)));
        this.spotDiminishingEnabled = yaml.getBoolean("spot-diminishing.enabled", true);
        this.spotDiminishingRadius = Math.max(1.0, yaml.getDouble("spot-diminishing.radius", 24.0));
        this.spotDiminishingWindowSeconds =
                Math.max(1.0, yaml.getDouble("spot-diminishing.window-seconds", 300.0));
        this.spotDiminishingThreshold = Math.max(1, yaml.getInt("spot-diminishing.threshold", 30));
        this.spotDiminishingDecayPerKill = Math.max(0.0, Math.min(1.0,
                yaml.getDouble("spot-diminishing.decay-per-kill", 0.05)));
        this.spotDiminishingFloor = Math.max(0.0, Math.min(1.0,
                yaml.getDouble("spot-diminishing.floor", 0.1)));
        this.spotDiminishingExemptsDungeons =
                yaml.getBoolean("spot-diminishing.exempt-dungeon-worlds", true);
        // タスク1: 採取EXPの算出方式。未知の値/キー欠落は安全側でdrop_sum(現行挙動)にフォールバックする。
        this.gatheringExpMode = parseGatheringExpMode(yaml.getString("gathering.exp-mode", "drop_sum"));
        // タスク2: 戦闘EXPの算出方式。既定はflat(=現行のexp-per-hit固定値方式のまま)。
        String combatMode = yaml.getString("combat.mode", "flat");
        this.combatDamageScaledMode = "damage_scaled".equalsIgnoreCase(
                combatMode == null ? "" : combatMode.trim());
        this.combatDamageScale = Math.max(0.0, yaml.getDouble("combat.damage-scale", 0.1));
        this.combatMobLevelScale = Math.max(0.0, yaml.getDouble("combat.mob-level-scale", 0.02));
        // タスク3: レベル逓減カーブ。既定はgathering/combatともfalse(=常に倍率1.0、現行挙動と完全一致)。
        this.gatheringExpDiminishingEnabled =
                yaml.getBoolean("level-diminishing.gathering-enabled", false);
        this.combatExpDiminishingEnabled =
                yaml.getBoolean("level-diminishing.combat-enabled", false);
        String diminishingFormula = yaml.getString("level-diminishing.formula", "1 / (1 + %level% / 50)");
        this.expDiminishingFormula = diminishingFormula == null || diminishingFormula.isBlank()
                ? "1 / (1 + %level% / 50)" : diminishingFormula;
        this.expDiminishingFloor = Math.max(0.0, Math.min(1.0,
                yaml.getDouble("level-diminishing.floor", 0.2)));
        // EXP獲得/レベルアップ表示 (S5/S6)
        this.expDisplayActionBarOnly =
                "actionbar".equalsIgnoreCase(yaml.getString("exp-display.mode", "bossbar"));
        this.expBarSeconds = Math.max(0.5, yaml.getDouble("exp-display.bossbar-seconds", 4.0));
        this.maxConcurrentBossBars = Math.max(1, yaml.getInt("exp-display.max-concurrent-bossbars", 4));
        this.levelUpChat = yaml.getBoolean("level-up.chat", true);
        this.levelUpSoundEnabled = yaml.getBoolean("level-up.sound-enabled", true);
        this.levelUpSound = yaml.getString("level-up.sound", "ENTITY_PLAYER_LEVELUP");
        this.titleEveryLevels = yaml.getInt("level-up.title-every-levels", 10);
    }

    /** 未知の文字列/null/空文字は安全側で{@link GatheringExpMode#DROP_SUM}(現行挙動)にフォールバックする。 */
    private static GatheringExpMode parseGatheringExpMode(String raw) {
        if (raw == null) return GatheringExpMode.DROP_SUM;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "block_value" -> GatheringExpMode.BLOCK_VALUE;
            case "max" -> GatheringExpMode.MAX;
            default -> GatheringExpMode.DROP_SUM;
        };
    }
}
