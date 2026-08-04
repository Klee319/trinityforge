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
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code stats/skill-exp.yml}: per-skill EXP gain rates for TrinityForge custom skills.
 */
public final class SkillExpConfig {

    public static final String PATH = "stats/skill-exp.yml";

    /** タスク1: 採取EXPの算出方式({@code gathering.exp-mode})。 */
    public enum GatheringExpMode { DROP_SUM, BLOCK_VALUE, MAX }

    private volatile double arsSmithingExpPerCraft = 10.0;
    // 2026-08-04: 儀式で実際に消費したソース量に比例した追加EXP({@code ars-smithing.exp-per-source})。
    // 素材表/定額で決まる base に「消費ソース量 × この値」を足し込む(別付与にはしない — 別々に
    // grantSkillExp すると逓減の窓が2回進み、素材由来ぶんと合わせた総量が読めなくなる)。
    // 既定 0.0 = 従来どおり(ソースは加味しない)。ソースは儀式の階梯とともに桁で増える
    // (source_shard 100 → infinity_source_core 45,000,000)ので、1.0 のような値を入れると
    // 上位儀式1回で最大レベルに届く。0.001 程度から試すこと。
    private volatile double arsSmithingExpPerSource = 0.0;
    // 2026-08-04: 総合(POWER)レベルを何レベル進めるごとにスキルポイントを1点与えるか
    // ({@code power.levels-per-skill-point})。1 = 1レベルごとに1点(従来の挙動)。
    // 2 にすると2レベルで1点になる。0 以下は 1 に丸める(ゼロ除算とポイント無限増加の両方を防ぐ)。
    private volatile int powerLevelsPerSkillPoint = 1;
    // 2026-07-25 PRG-13: SMITHING EXPは耐久消耗ベースの付与(旧onItemDamage)を廃止し、ARS_SMITHINGと同じ
    // 「武器/防具/ツールをクラフトした瞬間」に一本化した(CraftQualityListener#onCraft、
    // categorySkill: weapon/armor/tool -> SMITHING)。耐久消耗はプレイ時間に比例して無限に発生させられる
    // (修理して壊す/掘り続ける等)ため無限EXP経路になっていたが、クラフトは1回の生産行為に対して1回しか
    // 発生しない(素材の入手コストが実質的な上限になる)。既定値15.0は、旧durability方式で
    // ダイヤモンドツール1本を耐久使い切るまでに得られたEXP量(耐久1561×tool_stack0.01≈15.6)を目安に
    // 「装備1個クラフト = ツール1本を使い切った程度」として据えた暫定値。実プレイでの体感ペースを見て
    // 要調整。
    private volatile double smithingExpPerCraft = 15.0;
    // 2026-07-30 「鍛冶のレベルが上がりにくい」への対応: 定額ではなく「クラフト盤面に置いた素材の合計」で
    // EXPを決める。キーは Material 名 または custom:<catalogId>。表に無い素材は0(=無報酬)。
    // 表が空のときだけ上の定額 smithingExpPerCraft にフォールバックする(yml未更新のサーバで鍛冶EXPが
    // 丸ごと0になるのを避けるため)。
    private volatile Map<String, Double> smithingExpPerMaterial = Map.of();
    private volatile boolean arsMagicKillExpEnabled = true;
    private volatile double arsMagicKillExpBase = 20.0;
    private volatile double arsMagicKillExpPerMobLevel = 1.5;
    private volatile double arsMagicKillExpPerMaxHealth = 0.25;
    private volatile Map<String, Double> arsMagicKillEntityTypeMultipliers = Map.of();
    private volatile boolean arsMagicBlockBreakExpEnabled = true;
    private volatile double arsMagicBlockBreakSourceMultiplier = 1.0;
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
    /**
     * 日次逓減(2026-07-31 ユーザー確定「既存の24時間EXPによって取得量が軽減されていく設定」)。
     * この機構は「既にある」と思われていたが実在せず、あったのは spot-diminishing(同一地点)だけだった。
     * 既定は無効なので、yml に書くまで挙動は変わらない。
     */
    private volatile com.trinityforge.progression.DailyExpDiminishing.Settings dailyDiminishing =
            com.trinityforge.progression.DailyExpDiminishing.Settings.DISABLED;
    // --- 2026-07-26 EXP調整タスク1: 採取EXPの算出方式 ---
    // drop_sum(既定): 従来どおりドロップ品の合計値(現行の実サーバ挙動と完全一致・変更なし)。
    // block_value   : ブロック自体に設定された値(mining_break.<ブロック名>等)をそのまま使う。
    //                 深層岩バリアント等、ドロップ品が同じでもブロック側の値が違うケースに反映される。
    // max           : ドロップ合計とブロック値の大きい方。
    private volatile GatheringExpMode gatheringExpMode = GatheringExpMode.DROP_SUM;
    /**
     * 討伐時ベースの武器スキルEXP基礎値。N5(2026-07-31)で {@code ARCHERY} を追加した(弓術も軽・重武器と
     * 同じ討伐時ベースへ統一)。同時に {@code LIGHT_WEAPONS} の 25.0 を出荷 yml の 20 へ揃えた
     * (既定値だけ 25 で出荷 yml が 20 という drift があり、yml を消した環境だけ挙動が変わっていた)。
     * <b>この既定値と {@code stats/skill-exp.yml} の値は必ず一致させること</b>
     * ({@code SkillExpConfigTest} の出荷値ドリフト検知が両方を突き合わせている)。
     */
    private volatile Map<String, Double> combatKillExpBase = Map.of(
            "HEAVY_WEAPONS", 30.0,
            "LIGHT_WEAPONS", 20.0,
            "ARCHERY", 25.0);
    private volatile double combatKillExpPerMobLevel = 2.0;
    private volatile double combatKillExpPerMaxHealth = 0.25;
    private volatile Map<String, Double> combatKillEntityTypeMultipliers = Map.of();
    /**
     * 2026-07-28 ユーザー要望「モブ定義にないモブは経験値なし」: {@code entity-type-multipliers} に
     * 行が無いモブへ適用する倍率。既定 0.0 = 未定義のモブは討伐EXPを一切生まない。
     *
     * <p>2026-07-26 の per-mob EXP ramp では「未設定は0EXPでなく無干渉(=1.0)」という逆の判断を
     * していた。運用してみると、定義していないモブ(装飾用・イベント用・外部プラグイン由来など)まで
     * フル EXP を出してしまうため、既定を反転した。1.0 に戻せば旧挙動。
     */
    private volatile double combatKillUnlistedEntityMultiplier = 0.0;
    private volatile double arsMagicKillUnlistedEntityMultiplier = 0.0;
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
    // --- 2026-07-28 使用可能レベル連動EXP: use-level-scaling.* ---
    private volatile boolean useLevelScalingEnabled = true;
    private volatile double useLevelScalingMaxMultiplier = 3.0;
    private volatile Map<String, Double> useLevelScalingPerLevel = Map.of();

    /** ARS_SMITHING experience granted when a player crafts Ars gear (the custom skill's EXP source). */
    public double arsSmithingExpPerCraft() {
        return arsSmithingExpPerCraft;
    }

    /**
     * 儀式で消費したソース1あたりの追加 ARS_SMITHING EXP。0.0 = ソースを加味しない(既定)。
     *
     * <p>素材表({@link #smithingExpPerMaterial()})や定額({@link #arsSmithingExpPerCraft()})で
     * 決まった base に足し込む形で使う。ソース要求量は儀式の階梯とともに桁で増えるので、
     * 大きな値を入れると上位儀式1回で最大レベルに届く点に注意。
     */
    public double arsSmithingExpPerSource() {
        return arsSmithingExpPerSource;
    }

    /**
     * 総合(POWER)を何レベル進めるごとにスキルポイントを1点与えるか。必ず 1 以上を返す。
     *
     * <p>ポイント総数は {@code PlayerProgression.STARTING_SKILL_POINTS + POWERレベル / この値}
     * (整数除算・切り捨て)。1 = 従来どおり1レベル1点。
     *
     * <p><b>この値を大きくすると既存プレイヤーの獲得済みポイントが減る</b>ため、
     * 既に使ったポイント(spentPoints)が新しい獲得上限を超える状態が起こりうる。
     * 呼び出し側は available を負にしないこと(ProgressionCurveReconciler が救済する)。
     * なお全員のポイント残高を新しい値で書き直すのは {@code /trinityforge reload} の中の
     * 再計算処理だけで、ログインでは走らない。
     *
     * <p>ポイント総数そのものの計算は
     * {@link com.trinityforge.progression.core.PlayerProgression#earnedPoints(long, int)} に集約してある
     * (式が付与・管理コマンド・reload時再計算の3箇所に散っており、片方だけこの設定を見る状態になると
     * 「レベルアップで増えた点が reload で消える」ような追跡困難な食い違いになるため)。
     */
    public int powerLevelsPerSkillPoint() {
        return powerLevelsPerSkillPoint;
    }

    /**
     * SMITHING experience granted when a player crafts weapon/armor/tool equipment (PRG-13: the sole
     * SMITHING EXP source since the durability-based grant was removed).
     *
     * <p>2026-07-30 以降は {@link #smithingExpPerMaterial()} が非空ならそちらが優先され、この定額は
     * <b>素材表が未設定のときのフォールバック</b>としてのみ使われる(jar だけ更新して yml を
     * 書いていないサーバで鍛冶EXPが丸ごと0になるのを防ぐため)。
     */
    public double smithingExpPerCraft() {
        return smithingExpPerCraft;
    }

    /**
     * {@code smithing.exp-per-material}: クラフト盤面に置いた素材<b>1個あたり</b>の鍛冶EXP
     * (2026-07-30 ユーザー確定「鍛冶のレベルが上がりにくい」への対応)。
     *
     * <p>キーは素材トークン: バニラは Material 名({@code IRON_INGOT})、TFカタログ品は
     * {@code custom:<catalogId>}。<b>表に無い素材は 0</b>(未設定=無報酬)。
     * 表が空の場合だけ従来の定額 {@link #smithingExpPerCraft()} にフォールバックする。
     *
     * <p>付与は「完成品に使用可能レベルが設定されているとき」に限る({@code CraftQualityListener})
     * — 解体で素材へ戻せるアイテムを延々と作り直す EXP 稼ぎを塞ぐため。
     */
    public Map<String, Double> smithingExpPerMaterial() {
        return smithingExpPerMaterial;
    }

    public boolean arsMagicKillExpEnabled() {
        return arsMagicKillExpEnabled;
    }

    public boolean arsMagicBlockBreakExpEnabled() {
        return arsMagicBlockBreakExpEnabled;
    }

    public double arsMagicBlockBreakSourceMultiplier() {
        return arsMagicBlockBreakSourceMultiplier;
    }

    /**
     * 魔法で敵を倒したときのEXP。敵種・TFモブレベル・最大体力のすべてを設定値だけで合成する。
     */
    public double arsMagicKillExp(String entityType, int mobLevel, double maxHealth) {
        if (isPlayerPlaceableTarget(entityType)) {
            return 0.0;
        }
        double base = arsMagicKillExpBase
                + Math.max(0, mobLevel) * arsMagicKillExpPerMobLevel
                + Math.max(0.0, maxHealth) * arsMagicKillExpPerMaxHealth;
        return Math.max(0.0, base) * entityMultiplier(arsMagicKillEntityTypeMultipliers, entityType,
                arsMagicKillUnlistedEntityMultiplier);
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

    /** 日次逓減の設定(daily-diminishing)。無効時は {@code Settings.DISABLED}。 */
    public com.trinityforge.progression.DailyExpDiminishing.Settings dailyDiminishing() {
        return dailyDiminishing;
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

    /**
     * 軽武器/重武器/弓術の討伐EXP。命中ダメージではなく、倒した敵の種類・TFモブレベル・最大体力で決める。
     *
     * <p>N5(2026-07-31): 弓術({@code ARCHERY})もこの式へ統一した。{@code base} マップに行が無いスキルは
     * {@code getOrDefault(skill, 0.0)} で基礎値0になるだけで例外もログも出ないため、
     * <b>スキルを追加したら必ず {@code combat.kill-exp.base} にも行を足す</b>
     * (行の存在自体は {@code SkillExpConfigTest} が固定している)。
     */
    public double combatKillExp(String skillId, String entityType, int mobLevel, double maxHealth) {
        if (isPlayerPlaceableTarget(entityType)) {
            return 0.0;
        }
        String skill = skillId == null ? "" : skillId.trim().toUpperCase(Locale.ROOT);
        double base = combatKillExpBase.getOrDefault(skill, 0.0)
                + Math.max(0, mobLevel) * combatKillExpPerMobLevel
                + Math.max(0.0, maxHealth) * combatKillExpPerMaxHealth;
        return Math.max(0.0, base) * entityMultiplier(combatKillEntityTypeMultipliers, entityType,
                combatKillUnlistedEntityMultiplier);
    }

    /**
     * <b>プレイヤーが自分で置ける「的」</b>の EntityType (U10, 2026-08-02)。
     * ここに入る種は {@link #combatKillExp} / {@link #arsMagicKillExp} が
     * <b>config を一切見ずに 0 を返す</b>。
     *
     * <p><b>なぜ config の除外リストでは足りないか</b>: 既存の除外機構は2つとも設定値で、
     * どちらも「戻すと復活する」性質のものだった ──
     * {@code combat.kill-exp.unlisted-entity-multiplier} / {@code ars-magic.kill-exp.unlisted-entity-multiplier}
     * を 1.0 にすれば元通り無限に稼げるし、{@code mob-level-table.yml} の {@code no-skill-exp-mobs} は
     * 行を消せば復活する。防具立ては素材3個で無限に量産できるので、
     * <b>設定で開けられる状態にしておくこと自体が事故</b>になる。
     *
     * <p><b>2箇所に効かせる必要がある</b>: 討伐EXPの経路は
     * {@code CombatListener#onCombatKill}(武器スキル)と
     * {@code ArsMagicExperienceListener#onMagicKill}(魔法)の<b>2本</b>あり、
     * 片方だけ塞いでも杖で叩けば通ってしまう。両方が最終的にこのクラスの
     * kill-exp 計算を通るので、<b>ここに置けば1箇所で両方に効く</b>。
     *
     * <p><b>MANNEQUIN も入れてある</b>。1.21.9 で追加された種で
     * {@code EntityDeathEvent} を発火するかは実機未確認だが、発火しないなら無害、
     * 発火するなら防具立てと同じ穴になるので、先に塞いでおくほうが安い。
     */
    private static final Set<String> PLAYER_PLACEABLE_TARGETS = Set.of("ARMOR_STAND", "MANNEQUIN");

    /** 討伐EXPを構造的に0にする種か。{@code null}/空は false(通常の設定経路へ)。 */
    private static boolean isPlayerPlaceableTarget(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return false;
        }
        return PLAYER_PLACEABLE_TARGETS.contains(entityType.trim().toUpperCase(Locale.ROOT));
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

    /** true = 使用可能レベル連動EXP機能そのものが有効(既定true)。 */
    public boolean useLevelScalingEnabled() {
        return useLevelScalingEnabled;
    }

    /** 使用可能レベル連動EXPの倍率上限(暴走止めの安全弁、既定3.0)。 */
    public double useLevelScalingMaxMultiplier() {
        return useLevelScalingMaxMultiplier;
    }

    /** スキルIDごとの per-level 係数(小文字キー)。未定義スキルは {@link #useLevelExpMultiplier} で1.0扱い。 */
    public Map<String, Double> useLevelScalingPerLevel() {
        return useLevelScalingPerLevel;
    }

    /**
     * 使用可能レベル連動EXP (2026-07-28): 鍛冶(作成したツール/装備)・伐採/採掘/切削
     * (破壊に使ったメインハンドのツール)それぞれの「使用可能レベル」に応じてEXP付与量へ掛ける倍率。
     * 農業(FARMING)は対象外(この設定は要件どおりFARMINGを含まない)。
     *
     * <p>式: {@code 倍率 = 1 + 使用可能レベル × per-level}。item-stats.yml の実際の使用可能レベルは
     * 0/10/20/30/40/55/70/85/100 の9段(既定 per-level=0.01 なら 1.0/1.1/1.2/1.3/1.4/1.55/1.7/1.85/2.0倍)。
     *
     * @param skillId  {@link com.trinityforge.progression.core.SkillId} の大文字定数
     *                 (per-levelに該当行が無いスキル、例えばFARMINGは常に1.0=対象外)
     * @param useLevel 解決済みの使用可能レベル(0以下、または未解決(素手・バニラツール・
     *                 item-statsにプロファイル無し)なら常に1.0=現状維持)
     */
    public double useLevelExpMultiplier(String skillId, int useLevel) {
        if (!useLevelScalingEnabled || useLevel <= 0 || skillId == null) {
            return 1.0;
        }
        Double perLevel = useLevelScalingPerLevel.get(skillId.toLowerCase(Locale.ROOT));
        if (perLevel == null) {
            return 1.0;
        }
        double multiplier = 1.0 + useLevel * perLevel;
        // perLevelが負値でもEXPが減る方向(1.0未満)にはしない(要件外)。
        multiplier = Math.max(1.0, multiplier);
        return Math.min(useLevelScalingMaxMultiplier, multiplier);
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
        this.arsSmithingExpPerSource = Math.max(0.0, yaml.getDouble("ars-smithing.exp-per-source", 0.0));
        // 0 以下は 1 に丸める。0 を許すと「0レベルごとに1点」でゼロ除算、負を許すとポイントが
        // レベルとともに減る意味不明な挙動になるため、どちらも設定ミスとして 1 扱いにする。
        this.powerLevelsPerSkillPoint = Math.max(1, yaml.getInt("power.levels-per-skill-point", 1));
        this.smithingExpPerCraft = Math.max(0.0, yaml.getDouble("smithing.exp-per-craft", 15.0));
        this.smithingExpPerMaterial = readMaterialTokenMap(yaml, "smithing.exp-per-material");
        this.arsMagicKillExpEnabled = yaml.getBoolean("ars-magic.kill-exp.enabled", true);
        this.arsMagicKillExpBase = Math.max(0.0, yaml.getDouble("ars-magic.kill-exp.base", 20.0));
        this.arsMagicKillExpPerMobLevel =
                Math.max(0.0, yaml.getDouble("ars-magic.kill-exp.per-mob-level", 1.5));
        this.arsMagicKillExpPerMaxHealth =
                Math.max(0.0, yaml.getDouble("ars-magic.kill-exp.per-max-health", 0.25));
        this.arsMagicKillEntityTypeMultipliers =
                readNonNegativeMap(yaml, "ars-magic.kill-exp.entity-type-multipliers", true);
        this.arsMagicBlockBreakExpEnabled =
                yaml.getBoolean("ars-magic.block-break-exp.enabled", true);
        this.arsMagicBlockBreakSourceMultiplier =
                Math.max(0.0, yaml.getDouble("ars-magic.block-break-exp.source-multiplier", 1.0));
        this.dungeonOnlyExp = yaml.getBoolean("dungeon-only-exp", true);
        this.outsideDungeonExpRate = Math.max(0.0, Math.min(1.0,
                yaml.getDouble("outside-dungeon-exp-rate", 0.25)));
        // 日次逓減。window-hours は「時定数」で、その時間が経つと蓄積が 1/e (約37%) まで戻る。
        // exempt-skills は正規化前の生IDをそのまま集合に入れる(スキルIDは大文字固定なので一致する)。
        // 2026-08-01 仕様変更: 旧 threshold/step/decay-per-step(連続的な逓減)を廃止し、
        // 「per-amount を稼ぐたびに decay-per-amount 倍になる」離散の刻みへ置き換えた。
        // 旧キーが残っていても読まない ── 意味が変わったので黙って引き継ぐと逓減の効き方が化ける。
        this.dailyDiminishing = new com.trinityforge.progression.DailyExpDiminishing.Settings(
                yaml.getBoolean("daily-diminishing.enabled", false),
                Math.max(1.0, yaml.getDouble("daily-diminishing.window-hours", 24.0)) * 3_600_000.0,
                yaml.getDouble("daily-diminishing.per-amount", 150000.0),
                yaml.getDouble("daily-diminishing.decay-per-amount", 1.0),
                yaml.getDouble("daily-diminishing.floor", 0.25),
                new java.util.HashSet<>(yaml.getStringList("daily-diminishing.exempt-skills")));
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
        Map<String, Double> killBases = readNonNegativeMap(yaml, "combat.kill-exp.base", true);
        if (killBases.isEmpty()) {
            killBases = Map.of("HEAVY_WEAPONS", 30.0, "LIGHT_WEAPONS", 20.0, "ARCHERY", 25.0);
        }
        this.combatKillExpBase = killBases;
        this.combatKillExpPerMobLevel =
                Math.max(0.0, yaml.getDouble("combat.kill-exp.per-mob-level", 2.0));
        this.combatKillExpPerMaxHealth =
                Math.max(0.0, yaml.getDouble("combat.kill-exp.per-max-health", 0.25));
        this.combatKillEntityTypeMultipliers =
                readNonNegativeMap(yaml, "combat.kill-exp.entity-type-multipliers", true);
        this.combatKillUnlistedEntityMultiplier = Math.max(0.0,
                yaml.getDouble("combat.kill-exp.unlisted-entity-multiplier", 0.0));
        this.arsMagicKillUnlistedEntityMultiplier = Math.max(0.0,
                yaml.getDouble("ars-magic.kill-exp.unlisted-entity-multiplier", 0.0));
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
        // 使用可能レベル連動EXP (2026-07-28)
        this.useLevelScalingEnabled = yaml.getBoolean("use-level-scaling.enabled", true);
        // maxMultiplierが1.0未満だと「1.0を下回らない」保証が壊れるため、下限1.0でクランプする。
        this.useLevelScalingMaxMultiplier =
                Math.max(1.0, yaml.getDouble("use-level-scaling.max-multiplier", 3.0));
        Map<String, Double> perLevel = new LinkedHashMap<>();
        var perLevelSection = yaml.getConfigurationSection("use-level-scaling.per-level");
        if (perLevelSection != null) {
            for (String key : perLevelSection.getKeys(false)) {
                perLevel.put(key.toLowerCase(Locale.ROOT), perLevelSection.getDouble(key, 0.0));
            }
        }
        this.useLevelScalingPerLevel = Collections.unmodifiableMap(perLevel);
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

    /**
     * 素材トークン → 数値の表を読む。キーは {@code IRON_INGOT} のような Material 名と
     * {@code custom:<catalogId>} が混在しうるので、{@link #normalizeMaterialToken(String)} で
     * 「バニラだけ大文字化・custom は原文維持」に正規化してから積む。
     */
    private static Map<String, Double> readMaterialTokenMap(
            org.bukkit.configuration.ConfigurationSection yaml, String path) {
        var section = yaml.getConfigurationSection(path);
        if (section == null) return Map.of();
        Map<String, Double> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            values.put(normalizeMaterialToken(key), Math.max(0.0, section.getDouble(key, 0.0)));
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * 素材トークンの正規化。{@code custom:} 接頭辞付き(TFカタログ品)は catalogId の大小を潰さず
     * そのまま残し、それ以外は Bukkit の {@code Material} 名として大文字化する。
     * カタログIDは yml のキーと PDC の値が一致していなければならないため、大文字化してはならない。
     */
    public static String normalizeMaterialToken(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.regionMatches(true, 0, "custom:", 0, "custom:".length())) {
            return "custom:" + trimmed.substring("custom:".length());
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static Map<String, Double> readNonNegativeMap(
            org.bukkit.configuration.ConfigurationSection yaml, String path, boolean uppercaseKeys) {
        var section = yaml.getConfigurationSection(path);
        if (section == null) return Map.of();
        Map<String, Double> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            String normalized = uppercaseKeys ? key.toUpperCase(Locale.ROOT) : key;
            values.put(normalized, Math.max(0.0, section.getDouble(key, 0.0)));
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * @param unlistedMultiplier 表に行が無いモブへ適用する倍率(既定 0.0 = EXPなし)。EntityType が
     *                           取れないケース(null/空)も「定義に載っていない」と同じ扱いにする —
     *                           種別が分からないまま満額を出すのは、この設定の目的(未定義モブを
     *                           EXP源にしない)と真っ向から反するため。
     */
    private static double entityMultiplier(Map<String, Double> multipliers, String entityType,
                                           double unlistedMultiplier) {
        if (entityType == null || entityType.isBlank()) return unlistedMultiplier;
        return multipliers.getOrDefault(entityType.trim().toUpperCase(Locale.ROOT), unlistedMultiplier);
    }
}
