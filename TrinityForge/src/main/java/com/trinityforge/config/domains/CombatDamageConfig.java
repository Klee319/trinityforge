package com.trinityforge.config.domains;

import com.trinityforge.combat.AttackStatKeys;
import com.trinityforge.combat.DefenseClamp;
import com.trinityforge.combat.DefenseStatKeys;
import com.trinityforge.config.ConfigDomain;
import com.trinityforge.config.ConfigSchema;
import com.trinityforge.config.SchemaField;
import com.trinityforge.config.TypedConfig;
import com.trinityforge.durability.DurabilityPenaltySettings;
import com.trinityforge.mobs.DungeonLevelReward;
import com.trinityforge.mobs.MobLevelCutoff;

import java.util.List;

/**
 * Typed accessor for {@code combat/damage.yml} (M1 symmetric pipeline, physical component).
 * These are the global, tunable knobs feeding the structural 8-step pipeline
 * (COMBAT_SYSTEM_SPEC 2.1); per-item / per-mob stats live in PDC, not here.
 * Config-driven so balance changes never require code edits (IMPLEMENTATION_PLAN section 0).
 */
public final class CombatDamageConfig {

    public static final String PATH = "combat/damage.yml";

    private static final String MAGICAL_SCALE_WITH_COMBAT_LEVEL = "magical.scale-with-combat-level";
    // 2026-07-31: 杖(触媒)の attack-power を魔法の基礎ダメージへ加算するときの係数。
    // 既定 1.0 = 仕様どおり100%加算(MAGIC_BALANCE_SPEC / COMBAT_SYSTEM_SPEC の
    // 「デフォルト魔法ダメージ = Arsスペル攻撃力 + 触媒の攻撃力ステータス」)。
    // 0 にすると杖の攻撃力は魔法へ一切乗らない。読むのは ArsPaper フォークの TrinityForgeBridge。
    private static final String MAGICAL_ATTACK_POWER_SCALE = "magical.attack-power-scale";

    private static final String DEFENSE_MIN_RATE = "defense.min-rate";
    private static final String DEFENSE_MAX_RATE = "defense.max-rate";
    private static final String DEFENSE_MIN_FLAT = "defense.min-flat";
    private static final String DEFENSE_MAX_FLAT = "defense.max-flat";

    private static final String DEFENSE_MAX_DODGE_CHANCE = "defense.max-dodge-chance";
    private static final String DEFENSE_MAX_CRIT_REDUCTION = "defense.max-crit-reduction";
    private static final String DEFENSE_ENCHANT_PROTECTION_SCALE = "defense.enchant-protection-scale";

    private static final String AOE_HIT_PLAYERS = "aoe.hit-players";

    private static final String PVP_ENABLED = "pvp.enabled";
    private static final String PVP_DAMAGE_MULTIPLIER = "pvp.damage-multiplier";
    private static final String PVP_MAX_DAMAGE_PERCENT_OF_MAX_HEALTH =
            "pvp.max-damage-percent-of-max-health";

    private static final String MELEE_CHARGE_ENABLED = "melee-charge.enabled";
    private static final String MELEE_CHARGE_MIN_MULTIPLIER = "melee-charge.min-multiplier";
    private static final String MELEE_CHARGE_EXPONENT = "melee-charge.exponent";

    // 2026-07-25: attack-speed / attack-speed-bonus 分離仕様(PerkAttributeApplier参照)。
    private static final String ATTACK_SPEED_MIN_EFFECTIVE = "attack-speed.min-effective";
    private static final String ATTACK_SPEED_RECONCILE_INTERVAL_TICKS =
            "attack-speed.reconcile-interval-ticks";

    private static final String WEAPON_BASE_FORMULA_ENABLED = "weapon-base-formula.enabled";
    private static final String WEAPON_BASE_FORMULA_A = "weapon-base-formula.a";
    private static final String WEAPON_BASE_FORMULA_B = "weapon-base-formula.b";

    // 2026-07-28 日光炎上: バニラの炎上ダメージ(1発1.0固定)はTFのモブ最大HP(Lv0で400〜)に対して
    // 無意味で、「朝になっても敵が炎上で死なない」状態だった。最大HP割合へ置き換える。
    private static final String SUNLIGHT_BURN_ENABLED = "sunlight-burn.enabled";
    private static final String SUNLIGHT_BURN_DAMAGE_PERCENT = "sunlight-burn.damage-percent-of-max-health";
    private static final String SUNLIGHT_BURN_MOBS = "sunlight-burn.mobs";

    // 2026-07-28 序盤(低レベル帯)のモブ火力緩和。
    private static final String EARLY_LEVEL_ATTACK_ENABLED = "early-level-attack.enabled";
    private static final String EARLY_LEVEL_ATTACK_UNTIL_LEVEL = "early-level-attack.until-level";
    private static final String EARLY_LEVEL_ATTACK_LEVEL0_MULTIPLIER = "early-level-attack.level-0-multiplier";

    // 2026-07-30 装備耐久ペナルティ(EliteMobsダンジョンでのダウンは PlayerDeathEvent を出さない)。
    private static final String DURABILITY_DUNGEON_ONLY = "durability.dungeon-only";
    private static final String DURABILITY_RESPECT_UNBREAKING = "durability.respect-unbreaking";
    private static final String DURABILITY_PREVENT_BREAK = "durability.prevent-break";
    private static final String DURABILITY_ON_HIT_ENABLED = "durability.on-hit.enabled";
    private static final String DURABILITY_ON_HIT_PERCENT = "durability.on-hit.percent-of-max";
    private static final String DURABILITY_ON_HIT_MIN_DAMAGE = "durability.on-hit.min-damage";
    private static final String DURABILITY_ON_HIT_INCLUDE_OFFHAND = "durability.on-hit.include-offhand";
    private static final String DURABILITY_ON_DEATH_ENABLED = "durability.on-death.enabled";
    private static final String DURABILITY_ON_DEATH_PERCENT = "durability.on-death.percent-of-max";
    private static final String DURABILITY_ON_DEATH_MIN_DAMAGE = "durability.on-death.min-damage";
    private static final String DURABILITY_ON_DEATH_INCLUDE_HANDS = "durability.on-death.include-hands";

    // 2026-08-09 レベル差による足きり(combat/mob-overrides.yml の level-cutoff から移設)。
    private static final String LEVEL_CUTOFF_OVER_THRESHOLD = "level-cutoff.over-level.threshold";
    private static final String LEVEL_CUTOFF_OVER_EXP_RATE = "level-cutoff.over-level.exp-rate";
    private static final String LEVEL_CUTOFF_OVER_DROP_RATE = "level-cutoff.over-level.drop-rate";
    private static final String LEVEL_CUTOFF_UNDER_ITEM_THRESHOLD = "level-cutoff.under-level.item-threshold";
    // 2026-08-18 (W-60) 線形傾斜: 閾値到達直後に固定レートへジャンプするだけだったのを、
    // 超過1レベルごとに rate を減衰させる形へ拡張。既定は全て0/0で「decayなし」= 完全後方互換。
    private static final String LEVEL_CUTOFF_OVER_EXP_DECAY_PER_LEVEL = "level-cutoff.over-level.exp-decay-per-level";
    private static final String LEVEL_CUTOFF_OVER_DROP_DECAY_PER_LEVEL =
            "level-cutoff.over-level.drop-decay-per-level";
    private static final String LEVEL_CUTOFF_OVER_RATE_FLOOR = "level-cutoff.over-level.rate-floor";
    // 2026-08-18 (W-72) under-level を over-level と完全対称にした。以前は item-threshold 1本だけで
    // 「TF追加ドロップを付けない」の全か無かしか無く、経験値には一切効かなかったので、低レベルのまま
    // ハメ殺し/デスルーラーで高レベルのモブを倒すとバニラEXPもTF戦闘EXPも満額入っていた。
    // 閾値のキー名は item-threshold のまま(配備済み config の値が無言で既定値に化けるのを避ける)。
    private static final String LEVEL_CUTOFF_UNDER_EXP_RATE = "level-cutoff.under-level.exp-rate";
    private static final String LEVEL_CUTOFF_UNDER_DROP_RATE = "level-cutoff.under-level.drop-rate";
    private static final String LEVEL_CUTOFF_UNDER_EXP_DECAY_PER_LEVEL =
            "level-cutoff.under-level.exp-decay-per-level";
    private static final String LEVEL_CUTOFF_UNDER_DROP_DECAY_PER_LEVEL =
            "level-cutoff.under-level.drop-decay-per-level";
    private static final String LEVEL_CUTOFF_UNDER_RATE_FLOOR = "level-cutoff.under-level.rate-floor";
    // 2026-08-18 経験値だけ別の閾値から絞り始めるための追加キー。未設定(-1)なら item-threshold を使う
    // (＝従来どおり1本の閾値でアイテムと経験値の両方が発動する)。出荷値は経験値だけ 15 から絞り始め、
    // アイテムは 20 で完全遮断のまま ── 「経験値は15〜30レベル差の区間をかけて0になるように」という指示。
    private static final String LEVEL_CUTOFF_UNDER_EXP_THRESHOLD = "level-cutoff.under-level.exp-threshold";

    // 2026-08-18 (W-80) ダンジョンの挑戦レベルに応じた報酬の増減。
    // EMダイナミックダンジョンの選択レベルは敵の強さにしか効いておらず報酬には無関係だったので、
    // 一番低いレベルを選んで回すのが常に最適だった。判定はプレイヤーとのレベル差ではなく
    // 「倒したモブのレベル」=選んだレベル±難易度補正で、適用先もダンジョンワールドに限る
    // (レベル差で書くとオーバーワールドの高レベルモブにも効いて level-cutoff.under-level と衝突する)。
    // pivot-level で等倍、それより低いダンジョンは規定値より少なく、高いダンジョンは多くなる。
    // step レベル刻みの階段にするのは、EMの難易度(normal/hard/mythic)がモブレベルを ∓5 動かすので
    // 「難易度1段=報酬1段」で対応させるため。
    private static final String DUNGEON_LEVEL_REWARD_ENABLED = "dungeon-level-reward.enabled";
    private static final String DUNGEON_LEVEL_REWARD_PIVOT_LEVEL = "dungeon-level-reward.pivot-level";
    private static final String DUNGEON_LEVEL_REWARD_STEP = "dungeon-level-reward.step";
    private static final String DUNGEON_LEVEL_REWARD_DROP_PER_STEP = "dungeon-level-reward.drop-bonus-per-step";
    private static final String DUNGEON_LEVEL_REWARD_DROP_CAP = "dungeon-level-reward.drop-bonus-cap";
    private static final String DUNGEON_LEVEL_REWARD_DROP_PENALTY_CAP = "dungeon-level-reward.drop-penalty-cap";
    private static final String DUNGEON_LEVEL_REWARD_EXP_PER_STEP = "dungeon-level-reward.exp-bonus-per-step";
    private static final String DUNGEON_LEVEL_REWARD_EXP_CAP = "dungeon-level-reward.exp-bonus-cap";
    private static final String DUNGEON_LEVEL_REWARD_EXP_PENALTY_CAP = "dungeon-level-reward.exp-penalty-cap";

    private static final String VANILLA_ARMOR_DEFENSE_RATE_PER_POINT = "vanilla-armor.defense-rate-per-point";
    private static final String VANILLA_ARMOR_DEFENSE_RATE_MAX = "vanilla-armor.defense-rate-max";
    private static final String VANILLA_ARMOR_STRENGTH_PER_POINT = "vanilla-armor.armor-strength-per-point";

    private final ConfigDomain domain;

    public CombatDamageConfig() {
        ConfigSchema schema = new ConfigSchema()
                .field(SchemaField.number("physical.base-coefficient", SchemaField.Type.DOUBLE, 1.0, 0.0, 100.0))
                // min-component-damage の下限を負まで許容: 負に設定すると最終ダメージが負(=回復)まで落ちうる。
                // 既定 1.0 は従来通り(0未満を作らない)。負ダメは CombatListener が被弾者の回復として適用する。
                .field(SchemaField.number("physical.min-component-damage", SchemaField.Type.DOUBLE,
                        1.0, -1_000_000.0, 1_000_000.0))
                .field(SchemaField.number("magical.base-coefficient", SchemaField.Type.DOUBLE, 1.0, 0.0, 100.0))
                .field(SchemaField.number("magical.min-component-damage", SchemaField.Type.DOUBLE,
                        1.0, -1_000_000.0, 1_000_000.0))
                // 防御ステのクランプ境界(負クランプ対応)。既定 (0,1,0,大) は従来の [0,1]/[0,∞) と同挙動。
                // max-rate を 1 超にすると被ダメ軽減%が全軽減を超え、x(1-軽減) が負→最終ダメージ負(回復)になりうる。
                // min-rate/min-flat を負にすると防御ステが逆に被ダメージを増幅する。
                .field(SchemaField.number(DEFENSE_MIN_RATE, SchemaField.Type.DOUBLE, 0.0, -100.0, 0.0))
                .field(SchemaField.number(DEFENSE_MAX_RATE, SchemaField.Type.DOUBLE, 1.0, 0.0, 100.0))
                .field(SchemaField.number(DEFENSE_MIN_FLAT, SchemaField.Type.DOUBLE,
                        0.0, -1_000_000.0, 0.0))
                .field(SchemaField.number(DEFENSE_MAX_FLAT, SchemaField.Type.DOUBLE, 1_000_000.0, 0.0, 1_000_000.0))
                // 攻撃範囲(AoE, #2): 近接主命中時に aoe-radius/aoe-damage-rate を持つ武器等が周囲へ範囲ダメージ。
                // hit-players を true にすると他プレイヤーも巻き込み対象にする(PvP)。既定 false=モブのみ。
                .field(SchemaField.of(AOE_HIT_PLAYERS, SchemaField.Type.BOOLEAN, false))
                // 2026-07-27 PvP: モブ向けに調整されたダメージ式がそのまま対人に乗っており、
                // Lv100帯の攻撃力 約1052 に対しプレイヤー最大体力 約33 =「先に当てた方が確定で即死」
                // だった。倍率と「最大体力に対する1発の割合上限」の2段で抑える(詳細は
                // combat/damage.yml のコメントと combat.PvpDamagePolicy の javadoc)。
                .field(SchemaField.of(PVP_ENABLED, SchemaField.Type.BOOLEAN, true))
                // 出荷は倍率 0 = 対人ダメージ無し(PvP無し)。enabled を false にすると抑制が外れ即死になる。
                .field(SchemaField.number(PVP_DAMAGE_MULTIPLIER, SchemaField.Type.DOUBLE, 0.0, 0.0, 100.0))
                .field(SchemaField.number(PVP_MAX_DAMAGE_PERCENT_OF_MAX_HEALTH,
                        SchemaField.Type.DOUBLE, 0.15, 0.0, 100.0))
                // B2: バニラのチャージ攻撃(クールダウン中の連打減衰)をTFの独自ダメージパイプラインへ
                // 再導入する。既定は出荷 yml と同じ下限0.1倍・指数1.6(2026-08-01 調整。旧バニラ相当は 0.2/2.0)。
                // 近接プレイヤー攻撃のみに適用される(CombatListener側のゲート、弓/クロスボウ/トライデント/魔法/モブ攻撃には適用しない)。
                .field(SchemaField.of(MELEE_CHARGE_ENABLED, SchemaField.Type.BOOLEAN, true))
                .field(SchemaField.number(MELEE_CHARGE_MIN_MULTIPLIER, SchemaField.Type.DOUBLE, 0.1, 0.0, 1.0))
                .field(SchemaField.number(MELEE_CHARGE_EXPONENT, SchemaField.Type.DOUBLE, 1.6, 0.01, 100.0))
                // 2026-07-25: attack-speed(絶対値)+attack-speed-bonus(割合)の合成後、最終実効速度がこの値を
                // 割らないようクランプする下限(デバフ過多でも0/負にはならない)。既定0.1。
                .field(SchemaField.number(ATTACK_SPEED_MIN_EFFECTIVE, SchemaField.Type.DOUBLE, 0.1, 0.01, 4.0))
                // PerkAttributeApplierの装備フィンガープリント再照合の周期(tick)。既定10tick=0.5秒。
                .field(SchemaField.number(ATTACK_SPEED_RECONCILE_INTERVAL_TICKS, SchemaField.Type.INT,
                        10, 1, 1200))
                // 魔法の基本ダメージに combat レベル倍率を掛けるか。出荷・Java 既定とも true
                // (物理と同じレベル倍率)。false にすると C2 旧方針の bypass に戻る。
                .field(SchemaField.of(MAGICAL_SCALE_WITH_COMBAT_LEVEL, SchemaField.Type.BOOLEAN, true))
                // 2026-07-31 D6(魔法ダメージに杖の攻撃力が乗らない)の係数。既定1.0=100%加算。
                // 上限10.0は「杖の attack-power を10倍まで盛れる」逃げ道として置いてある(通常は1.0)。
                .field(SchemaField.number(MAGICAL_ATTACK_POWER_SCALE, SchemaField.Type.DOUBLE,
                        1.0, 0.0, 10.0))
                // 武器の基本火力(attack-power)を使用可能lvから算出: base = 1 + (useLevel^a / b)。
                // item-stats/roll/material-base で attack-power を明示した武器や useLevel<=0 の武器には
                // 適用しない(明示 > 式 > バニラ)。b は0除算回避のため厳密に正(>0)、無効値は既定へ戻す。
                .field(SchemaField.of(WEAPON_BASE_FORMULA_ENABLED, SchemaField.Type.BOOLEAN, true))
                .field(SchemaField.number(WEAPON_BASE_FORMULA_A, SchemaField.Type.DOUBLE, 2.0, 0.0, 100.0))
                .field(SchemaField.numberExclusiveMin(WEAPON_BASE_FORMULA_B, SchemaField.Type.DOUBLE,
                        100.0, 0.0, 1_000_000.0))
                .field(SchemaField.number("level-scaling.per-level", SchemaField.Type.DOUBLE, 0.01, 0.0, 10.0))
                // Balance ceiling for the non-penetrable multiplicative mitigations (耐性% / 被ダメージ
                // 軽減%), applied after additive armor/potion stacking so total penetration-proof immunity
                // is structurally impossible even with a mis-tuned roll table (B3). 1.0 disables the cap.
                .field(SchemaField.number("defense.max-mitigation-rate", SchemaField.Type.DOUBLE, 0.9, 0.0, 1.0))
                // 回避率の上限(B3の回避版): 上限が無いと回避率が加算スタッキングで1.0(=永久回避=実質無敵)に
                // 到達しうる。既定0.9で「必ず10%は当たる」を保証する。1.0にすると上限を実質無効化できる。
                .field(SchemaField.number(DEFENSE_MAX_DODGE_CHANCE, SchemaField.Type.DOUBLE, 0.9, 0.0, 1.0))
                // 防具強度(会心軽減率%)の上限(共通変数)。既定 1.0 = キャップ無し(会心の増加分を最大100%まで
                // 軽減しうる。ただし [0,1] 構造クランプにより相手の会心ダメージが0%未満へ反転することはない)。
                // 1.0未満に設定すると会心は必ず (1 - max-crit-reduction) 分の増加を残す(防具強度の加算スタッ
                // キングでも会心を完全に打ち消せなくなる)。
                .field(SchemaField.number(DEFENSE_MAX_CRIT_REDUCTION, SchemaField.Type.DOUBLE, 1.0, 0.0, 1.0))
                // 防護エンチャント(Protection/Projectile Protection)の再導出軽減率に掛ける倍率(2026-07-25)。
                // 既定0.5: 1.0=バニラ準拠(防護IVフルセットで64%軽減)だと defense.max-mitigation-rate(0.9)の
                // 枠をエンチャント1種で71%も食い潰し、TF自前の防具ステが無意味になるため半分に絞っている。
                .field(SchemaField.number(DEFENSE_ENCHANT_PROTECTION_SCALE, SchemaField.Type.DOUBLE, 0.5, 0.0, 10.0))
                // Bleed DoT (Q3 = (c)): how often a bleed deals damage, and for how many applications.
                .field(SchemaField.number("bleed.tick-interval-ticks", SchemaField.Type.INT, 20, 1, 1200))
                .field(SchemaField.number("bleed.ticks", SchemaField.Type.INT, 5, 1, 200))
                // Victim armor/toughness -> physical defense-rate% / 防具強度(会心軽減率%) fallback for
                // targets with no addon PDC profile (COMBAT_SYSTEM_SPEC 5, VanillaArmorMapping).
                .field(SchemaField.number(VANILLA_ARMOR_DEFENSE_RATE_PER_POINT, SchemaField.Type.DOUBLE, 0.04, 0.0, 1.0))
                .field(SchemaField.number(VANILLA_ARMOR_DEFENSE_RATE_MAX, SchemaField.Type.DOUBLE, 0.8, 0.0, 1.0))
                // 防具強度(会心軽減率%)/toughness点。既定 0 = バニラ防具の toughness は会心軽減に寄与しない
                // (バニラ防具のステは後日 config で別途定義するためフォールバックのみ)。会心軽減は主に
                // アイテム/防具/mob が付与する armor-strength ステ(DefenseStatBridge が直接読む)から得る。
                .field(SchemaField.number(VANILLA_ARMOR_STRENGTH_PER_POINT, SchemaField.Type.DOUBLE, 0.0, 0.0, 100.0))
                // 日光炎上(2026-07-28): 対象EntityTypeが日光で燃えている間の1発を「最大HP×割合」へ置き換える
                // (バニラ値の方が大きい場合はバニラのまま = 下げる方向には決して働かない)。
                // mobs: を空にすると全モブが対象になり、火属性エンチャントが野外昼間で%HPダメージ化する
                // (＝強力すぎる)ため、既定は「バニラで日光焼却される種別」だけを列挙する。
                .field(SchemaField.of(SUNLIGHT_BURN_ENABLED, SchemaField.Type.BOOLEAN, true))
                .field(SchemaField.number(SUNLIGHT_BURN_DAMAGE_PERCENT, SchemaField.Type.DOUBLE, 0.10, 0.0, 1.0))
                .field(SchemaField.of(SUNLIGHT_BURN_MOBS, SchemaField.Type.STRING_LIST, List.of(
                        "ZOMBIE", "ZOMBIE_VILLAGER", "DROWNED", "GIANT",
                        "SKELETON", "STRAY", "BOGGED", "PHANTOM",
                        "ZOMBIE_HORSE", "SKELETON_HORSE")))
                // 序盤モブ火力の緩和(2026-07-28): モブ→プレイヤーの基本ダメージに
                //   multiplier(L) = L >= until-level ? 1.0 : m0 + (1 - m0) * L / until-level
                // を掛ける。Lv0で m0 倍、until-level で等倍へ線形に戻るので、中盤以降の校正値は動かない。
                .field(SchemaField.of(EARLY_LEVEL_ATTACK_ENABLED, SchemaField.Type.BOOLEAN, true))
                .field(SchemaField.number(EARLY_LEVEL_ATTACK_UNTIL_LEVEL, SchemaField.Type.INT, 10, 0, 1000))
                .field(SchemaField.number(EARLY_LEVEL_ATTACK_LEVEL0_MULTIPLIER,
                        SchemaField.Type.DOUBLE, 0.7, 0.0, 1.0))
                // 装備耐久ペナルティ(2026-07-30): EliteMobsのインスタンスダンジョンは致死ダメージを
                // キャンセルしてダウンへ移すため PlayerDeathEvent が発火せず、死亡ペナルティも
                // キャンセルされた一撃分の防具耐久消費も両方失われていた。TF側で明示的に補う。
                // 既定は dungeon-only=true(ダンジョン内のみ)。詳細は combat/damage.yml のコメント参照。
                .field(SchemaField.of(DURABILITY_DUNGEON_ONLY, SchemaField.Type.BOOLEAN, true))
                .field(SchemaField.of(DURABILITY_RESPECT_UNBREAKING, SchemaField.Type.BOOLEAN, true))
                .field(SchemaField.of(DURABILITY_PREVENT_BREAK, SchemaField.Type.BOOLEAN, true))
                .field(SchemaField.of(DURABILITY_ON_HIT_ENABLED, SchemaField.Type.BOOLEAN, true))
                .field(SchemaField.number(DURABILITY_ON_HIT_PERCENT, SchemaField.Type.DOUBLE, 0.001, 0.0, 1.0))
                .field(SchemaField.number(DURABILITY_ON_HIT_MIN_DAMAGE, SchemaField.Type.INT, 1, 0, 10_000))
                .field(SchemaField.of(DURABILITY_ON_HIT_INCLUDE_OFFHAND, SchemaField.Type.BOOLEAN, true))
                .field(SchemaField.of(DURABILITY_ON_DEATH_ENABLED, SchemaField.Type.BOOLEAN, true))
                .field(SchemaField.number(DURABILITY_ON_DEATH_PERCENT, SchemaField.Type.DOUBLE, 0.1, 0.0, 1.0))
                .field(SchemaField.number(DURABILITY_ON_DEATH_MIN_DAMAGE, SchemaField.Type.INT, 1, 0, 10_000))
                .field(SchemaField.of(DURABILITY_ON_DEATH_INCLUDE_HANDS, SchemaField.Type.BOOLEAN, true))
                // レベル差による足きり(2026-08-09、combat/mob-overrides.yml から移設)。
                // 以前はEliteMobsスタンプ済みモブ(=ダンジョンモブ)にしか効かず、フィールドの野良モブには
                // 一切掛からなかった。全モブ共通の設定にするためこちらへ移した。
                // threshold の既定 -1 は「足きり無効」。rate の -1 は「完全に入手不可」を表す特別値なので、
                // 「無干渉」を表したいときは 1.0 を書く(0.0 は "1個も出ない" 側の端)。
                .field(SchemaField.number(LEVEL_CUTOFF_OVER_THRESHOLD, SchemaField.Type.INT, -1, -1, 10_000))
                .field(SchemaField.number(LEVEL_CUTOFF_OVER_EXP_RATE, SchemaField.Type.DOUBLE, 1.0, -1.0, 1.0))
                .field(SchemaField.number(LEVEL_CUTOFF_OVER_DROP_RATE, SchemaField.Type.DOUBLE, 1.0, -1.0, 1.0))
                .field(SchemaField.number(LEVEL_CUTOFF_UNDER_ITEM_THRESHOLD, SchemaField.Type.INT, -1, -1, 10_000))
                // 2026-08-18 (W-60): 超過1レベルごとに exp-rate/drop-rate から引く量。既定0 = 減衰なし
                // (閾値到達で固定レートへジャンプする従来挙動のまま)。rate自体と同じ[0,1]レンジ。
                .field(SchemaField.number(LEVEL_CUTOFF_OVER_EXP_DECAY_PER_LEVEL, SchemaField.Type.DOUBLE,
                        0.0, 0.0, 1.0))
                .field(SchemaField.number(LEVEL_CUTOFF_OVER_DROP_DECAY_PER_LEVEL, SchemaField.Type.DOUBLE,
                        0.0, 0.0, 1.0))
                // 減衰後レートの下限。既定0 = 完全に0まで絞れる(従来の clamp01 の下限と同じ)。
                .field(SchemaField.number(LEVEL_CUTOFF_OVER_RATE_FLOOR, SchemaField.Type.DOUBLE, 0.0, 0.0, 1.0))
                // 2026-08-18 (W-72): under-level 側も over-level と同じ4つの道具を持つ。
                // 既定値は「対称化する前の under-level の挙動」に合わせてある ── exp-rate 1.0(経験値に
                // 触れない) / drop-rate -1(発動したらTF追加ドロップを一切付けない) / decay・floor は 0。
                // これにより under-level のキーを1つも書いていない配備済み config の意味が変わらない。
                .field(SchemaField.number(LEVEL_CUTOFF_UNDER_EXP_RATE, SchemaField.Type.DOUBLE, 1.0, -1.0, 1.0))
                .field(SchemaField.number(LEVEL_CUTOFF_UNDER_DROP_RATE, SchemaField.Type.DOUBLE, -1.0, -1.0, 1.0))
                .field(SchemaField.number(LEVEL_CUTOFF_UNDER_EXP_DECAY_PER_LEVEL, SchemaField.Type.DOUBLE,
                        0.0, 0.0, 1.0))
                .field(SchemaField.number(LEVEL_CUTOFF_UNDER_DROP_DECAY_PER_LEVEL, SchemaField.Type.DOUBLE,
                        0.0, 0.0, 1.0))
                .field(SchemaField.number(LEVEL_CUTOFF_UNDER_RATE_FLOOR, SchemaField.Type.DOUBLE, 0.0, 0.0, 1.0))
                // 経験値だけ別の閾値から絞り始めたいとき用。既定 -1 = 未設定 = item-threshold を使う
                // (＝1本の閾値でアイテムと経験値の両方が発動する従来挙動のまま)。
                .field(SchemaField.number(LEVEL_CUTOFF_UNDER_EXP_THRESHOLD, SchemaField.Type.INT, -1, -1, 10_000))
                // 2026-08-18 (W-80): ダンジョンの挑戦レベルに応じた報酬の増減。
                // 既定は「無効」── 出荷 yml 側で有効にする。ここを true 既定にすると、この節を1行も
                // 書いていない配備済み config の意味が jar 差し替えだけで変わってしまうため。
                .field(SchemaField.of(DUNGEON_LEVEL_REWARD_ENABLED, SchemaField.Type.BOOLEAN, false))
                .field(SchemaField.number(DUNGEON_LEVEL_REWARD_PIVOT_LEVEL, SchemaField.Type.INT, 0, 0, 10_000))
                // 0 は「未設定」= 既定の5レベル刻み扱い(DungeonLevelReward 側で解決する)。
                .field(SchemaField.number(DUNGEON_LEVEL_REWARD_STEP, SchemaField.Type.INT, 0, 0, 1_000))
                .field(SchemaField.number(DUNGEON_LEVEL_REWARD_DROP_PER_STEP, SchemaField.Type.DOUBLE,
                        0.0, 0.0, 1.0))
                .field(SchemaField.number(DUNGEON_LEVEL_REWARD_DROP_CAP, SchemaField.Type.DOUBLE, 0.0, 0.0, 10.0))
                // 減少側の上限は 0.9 まで。1.0 以上を許すと報酬が 0 や負になってしまう。
                .field(SchemaField.number(DUNGEON_LEVEL_REWARD_DROP_PENALTY_CAP, SchemaField.Type.DOUBLE,
                        0.0, 0.0, 0.9))
                .field(SchemaField.number(DUNGEON_LEVEL_REWARD_EXP_PER_STEP, SchemaField.Type.DOUBLE,
                        0.0, 0.0, 1.0))
                .field(SchemaField.number(DUNGEON_LEVEL_REWARD_EXP_CAP, SchemaField.Type.DOUBLE, 0.0, 0.0, 10.0))
                .field(SchemaField.number(DUNGEON_LEVEL_REWARD_EXP_PENALTY_CAP, SchemaField.Type.DOUBLE,
                        0.0, 0.0, 0.9));
        // 2026-07-25 (CMB-31): attack-stat-keys.* / defense-stat-keys.* のconfig駆動スキーマ項目は
        // 削除した。AttackStatKeys/DefenseStatKeys の固定名を参照する理由は両クラスのjavadoc参照。
        this.domain = new ConfigDomain(PATH, schema);
    }

    public ConfigDomain domain() {
        return domain;
    }

    /** PvP(player→player)専用の抑制を掛けるか。{@code false} で従来どおりモブと同じ計算になる。 */
    public boolean pvpEnabled() {
        return domain.get().getBoolean(PVP_ENABLED);
    }

    /** PvPダメージに掛ける倍率(低レベル帯の手触り調整用。0で対人ダメージ0=実質PvP禁止)。 */
    public double pvpDamageMultiplier() {
        return domain.get().getDouble(PVP_DAMAGE_MULTIPLIER);
    }

    /**
     * PvPの1発で削れる量の上限を「被弾者の最大体力に対する割合」で表したもの(0 = 上限なし)。
     * 倍率と違い攻撃カーブのスケールに依存しないので、攻撃力が指数で伸びても
     * 「倒すのに最低 1/この値 発かかる」が構造的に保証される。
     */
    public double pvpMaxDamagePercentOfMaxHealth() {
        return domain.get().getDouble(PVP_MAX_DAMAGE_PERCENT_OF_MAX_HEALTH);
    }

    /** 日光炎上ダメージの最大HP割合への置換を行うか(2026-07-28)。 */
    public boolean sunlightBurnEnabled() {
        return domain.get().getBoolean(SUNLIGHT_BURN_ENABLED);
    }

    /** 日光炎上1発あたりのダメージ(被弾モブの最大HPに対する割合)。0で無効。 */
    public double sunlightBurnDamagePercentOfMaxHealth() {
        return domain.get().getDouble(SUNLIGHT_BURN_DAMAGE_PERCENT);
    }

    /** 日光炎上の置換対象EntityType名の一覧(空なら全モブ)。 */
    public List<String> sunlightBurnMobs() {
        return domain.get().getStringList(SUNLIGHT_BURN_MOBS);
    }

    /** 序盤(低レベル帯)モブの火力緩和を行うか(2026-07-28)。 */
    public boolean earlyLevelAttackEnabled() {
        return domain.get().getBoolean(EARLY_LEVEL_ATTACK_ENABLED);
    }

    /** 緩和が完全に解ける(等倍へ戻る)モブレベル。0以下で緩和は無効。 */
    public int earlyLevelAttackUntilLevel() {
        return domain.get().getInt(EARLY_LEVEL_ATTACK_UNTIL_LEVEL);
    }

    /** レベル0のモブに掛かる火力倍率(1.0で緩和なし)。 */
    public double earlyLevelAttackLevel0Multiplier() {
        return domain.get().getDouble(EARLY_LEVEL_ATTACK_LEVEL0_MULTIPLIER);
    }

    public double physicalBaseCoefficient() {
        return domain.get().getDouble("physical.base-coefficient");
    }

    public double minComponentDamage() {
        return domain.get().getDouble("physical.min-component-damage");
    }

    public double magicalBaseCoefficient() {
        return domain.get().getDouble("magical.base-coefficient");
    }

    public double magicalMinComponentDamage() {
        return domain.get().getDouble("magical.min-component-damage");
    }

    /**
     * Whether the magical component's default damage is scaled by the attacker's combat level.
     * Default {@code true}: magical shares the physical level curve so magic grows with combat level
     * (server decision, overriding the C2 bypass default). {@code false} restores the C2 bypass where
     * magic damage comes only from glyphs/catalyst and ignores combat level.
     */
    public boolean magicalScaleWithCombatLevel() {
        return domain.get().getBoolean(MAGICAL_SCALE_WITH_COMBAT_LEVEL);
    }

    /**
     * Multiplier applied to a catalyst/wand's {@code attack-power} before it is added to the spell's
     * base damage (2026-07-31 D6: 「魔法ダメージに杖の攻撃力が乗らない」). {@code 1.0} (default) is the
     * spec'd 100% additive behaviour — MAGIC_BALANCE_SPEC / COMBAT_SYSTEM_SPEC define the default
     * magical damage as {@code Ars spell power + catalyst attack-power}, symmetric with melee.
     * {@code 0.0} disables the contribution entirely.
     *
     * <p>Consumed by the ArsPaper fork ({@code TrinityForgeBridge#magicalFinalDamage}) rather than by
     * TF itself: the additive base is assembled on the fork side before it is handed to
     * {@link com.trinityforge.combat.SymmetricCombatService}. Clamped to {@code [0, 10]} by the schema.
     *
     * <p><b>2026-07-31 (F4 指摘4)</b>: 掛ける相手の {@code attack-power} は
     * {@link com.trinityforge.combat.WeaponAttackStatResolver#attackPowerOf} が
     * {@code combat/stat-caps.yml} の上限を適用した値になった。以前は近接だけが
     * {@code PlayerCombatAggregate#clamp} を通り魔法は素通りしていたため、
     * ここに書いてある「物理と対称の加算」が clamp の有無で破れていた。
     *
     * <p>この係数と {@code attack-power} は<b>防御無視ダメージ</b>(日輪/月輪の直接HP減少)には
     * 一切乗らない — あちらは {@code EntityDamageEvent} を出さず守備力・耐性・トーテム・
     * {@link com.trinityforge.combat.PvpDamagePolicy} のどれも通らないため、対等性の対象外。
     */
    public double magicalAttackPowerScale() {
        return domain.get().getDouble(MAGICAL_ATTACK_POWER_SCALE);
    }

    public double levelScalingPerLevel() {
        return domain.get().getDouble("level-scaling.per-level");
    }

    /** Whether the weapon base attack-power formula (1 + useLevel^a / b) is applied at all. */
    public boolean weaponBaseFormulaEnabled() {
        return domain.get().getBoolean(WEAPON_BASE_FORMULA_ENABLED);
    }

    /** Exponent {@code a} of the weapon base formula {@code 1 + (useLevel^a / b)}. */
    public double weaponBaseFormulaA() {
        return domain.get().getDouble(WEAPON_BASE_FORMULA_A);
    }

    /** Divisor {@code b} of the weapon base formula {@code 1 + (useLevel^a / b)}; schema-guaranteed &gt; 0. */
    public double weaponBaseFormulaB() {
        return domain.get().getDouble(WEAPON_BASE_FORMULA_B);
    }

    /**
     * The weapon base attack-power formula as a small value object ({@code 1 + useLevel^a / b}) for
     * {@code DerivedItemStats}. The schema guarantees {@code b > 0} (invalid config already fell back to
     * the default at load), so this never constructs an invalid {@link WeaponBaseFormula}.
     */
    public WeaponBaseFormula weaponBaseFormula() {
        TypedConfig config = domain.get();
        return new WeaponBaseFormula(
                config.getBoolean(WEAPON_BASE_FORMULA_ENABLED),
                config.getDouble(WEAPON_BASE_FORMULA_A),
                config.getDouble(WEAPON_BASE_FORMULA_B));
    }

    /** Balance ceiling for 耐性%/被ダメージ軽減% (below full immunity), applied after additive stacking (B3). */
    public double maxMitigationRate() {
        return domain.get().getDouble("defense.max-mitigation-rate");
    }

    /** Balance ceiling for 回避率 (below permanent dodge-immunity), mirroring {@link #maxMitigationRate}. */
    public double maxDodgeChance() {
        return domain.get().getDouble(DEFENSE_MAX_DODGE_CHANCE);
    }

    /**
     * Balance ceiling for 防具強度(会心軽減率%). Default {@code 1.0} = no cap below full nullification of
     * the crit bonus; the pipeline's {@code [0,1]} floor still keeps 会心ダメージ ≥ 0% (a crit never
     * heals). Set below 1.0 to guarantee crits always keep {@code (1 - value)} of their bonus.
     */
    public double maxCritReduction() {
        return domain.get().getDouble(DEFENSE_MAX_CRIT_REDUCTION);
    }

    /**
     * Multiplier applied to the EPF-capped vanilla Protection-family reduction
     * ({@link com.trinityforge.combat.DefenseEnchantmentBridge}). Default {@code 0.5}: {@code 1.0}
     * would reproduce exact vanilla behaviour (Protection IV full set = 64% reduction), but that alone
     * consumes 71% of {@link #maxMitigationRate}'s 0.9 budget, crowding out TF's own armor stats.
     */
    public double enchantProtectionScale() {
        return domain.get().getDouble(DEFENSE_ENCHANT_PROTECTION_SCALE);
    }


    /**
     * The configurable clamp bounds for defender stats (負クランプ対応, #6). Defaults
     * {@code (0,1,0,1e6)} reproduce the legacy {@code [0,1]} rate / {@code [0,∞)} flat behaviour; an
     * operator may widen {@code max-rate} above 1 or drop {@code min-rate}/{@code min-flat} below 0 to
     * let over-mitigation heal or negative defense amplify. Applied once at the pipeline choke
     * ({@code SymmetricCombatService.component()}).
     */
    public DefenseClamp defenseClamp() {
        TypedConfig config = domain.get();
        return new DefenseClamp(
                config.getDouble(DEFENSE_MIN_RATE),
                config.getDouble(DEFENSE_MAX_RATE),
                config.getDouble(DEFENSE_MIN_FLAT),
                config.getDouble(DEFENSE_MAX_FLAT));
    }

    /**
     * Whether 攻撃範囲(AoE, #2) splash may also hit other players. Default {@code false}: only non-player
     * living entities (mobs) are caught in the splash, so friendly players are not griefed. Set {@code true}
     * on a PvP server. The attacker and the primary victim are always excluded regardless.
     */
    public boolean aoeHitPlayers() {
        return domain.get().getBoolean(AOE_HIT_PLAYERS);
    }

    /** B2: whether the vanilla melee-charge damage decay is re-applied to TF's physical pipeline output. */
    public boolean meleeChargeEnabled() {
        return domain.get().getBoolean(MELEE_CHARGE_ENABLED);
    }

    /** B2: the multiplier floor at {@code t=0} (just swung, no charge). Default {@code 0.1} (shipped). */
    public double meleeChargeMinMultiplier() {
        return domain.get().getDouble(MELEE_CHARGE_MIN_MULTIPLIER);
    }

    /** B2: the exponent applied to the cooled-attack-strength fraction. Default {@code 1.6} (shipped). */
    public double meleeChargeExponent() {
        return domain.get().getDouble(MELEE_CHARGE_EXPONENT);
    }

    /**
     * 2026-07-25: attack-speed/attack-speed-bonus 合成後の最終実効速度の下限クランプ値。デバフ過多でも
     * 0/負値にならないための安全弁(既定0.1)。{@code AttackSpeedResolver#resolveBonusMultiplyAmount} が使う。
     */
    public double attackSpeedMinEffective() {
        return domain.get().getDouble(ATTACK_SPEED_MIN_EFFECTIVE);
    }

    /** 2026-07-25: {@code PerkAttributeApplier} の装備フィンガープリント再照合の周期(tick)。既定10。 */
    public int attackSpeedReconcileIntervalTicks() {
        return domain.get().getInt(ATTACK_SPEED_RECONCILE_INTERVAL_TICKS);
    }

    /** Server ticks between bleed applications (Q3 = (c) DoT). */
    public int bleedTickIntervalTicks() {
        return domain.get().getInt("bleed.tick-interval-ticks");
    }

    /** Number of applications a bleed lasts. */
    public int bleedTicks() {
        return domain.get().getInt("bleed.ticks");
    }

    /**
     * TF独自の装備耐久ペナルティ設定(2026-07-30)。EliteMobsのインスタンスダンジョンでは致死ダメージが
     * キャンセルされ {@code PlayerDeathEvent} が発火しないため、死亡ペナルティとキャンセルされた一撃分の
     * 防具耐久消費が両方失われていた。既定はダンジョン限定・被弾0.1%・死亡10%。
     */
    /**
     * レベル差による足きり(2026-08-09、{@code combat/mob-overrides.yml} の {@code level-cutoff} から移設)。
     *
     * <p>移設前はEliteMobsがスタンプしたモブ(=ダンジョンモブ)にしか掛からず、フィールドの野良モブは
     * どれだけレベル差があっても素通りしていた。ここはワールドにもモブidにも依存しない共通設定なので、
     * 返り値は常に単一の {@link MobLevelCutoff} で、呼び出し側でのスコープ解決は不要。
     *
     * <p>スキーマは値を省略できないため「未設定(null)」は表現しない。{@code threshold} が負なら
     * その足きりごと無効、rate は {@code 1.0} が無干渉・{@code -1} が完全遮断という
     * {@link MobLevelCutoff} 本来の規約をそのまま使う。
     *
     * <p>2026-08-18 (W-60): {@code exp-decay-per-level}/{@code drop-decay-per-level}/{@code rate-floor}
     * を追加し、閾値超過量に応じた線形の傾斜を掛けられるようにした。既定は全て {@code 0.0} なので、
     * 出荷設定では従来どおり「閾値到達で固定レートへジャンプする」ステップ関数のまま変わらない。
     *
     * <p>2026-08-18 (W-72): {@code under-level} 側にも同じ4つ(exp-rate / drop-rate / 2つのdecay / rate-floor)
     * を追加して over-level と対称にした。キー未記載の config でも意味が変わらないよう、既定値は
     * 対称化する前の挙動({@code exp-rate: 1.0} = 経験値に触れない、{@code drop-rate: -1} = 発動したら
     * TF追加ドロップを一切付けない)に合わせてある。
     */
    public MobLevelCutoff levelCutoff() {
        TypedConfig config = domain.get();
        return new MobLevelCutoff(
                config.getInt(LEVEL_CUTOFF_OVER_THRESHOLD),
                config.getDouble(LEVEL_CUTOFF_OVER_EXP_RATE),
                config.getDouble(LEVEL_CUTOFF_OVER_DROP_RATE),
                config.getInt(LEVEL_CUTOFF_UNDER_ITEM_THRESHOLD),
                config.getDouble(LEVEL_CUTOFF_OVER_EXP_DECAY_PER_LEVEL),
                config.getDouble(LEVEL_CUTOFF_OVER_DROP_DECAY_PER_LEVEL),
                config.getDouble(LEVEL_CUTOFF_OVER_RATE_FLOOR),
                config.getDouble(LEVEL_CUTOFF_UNDER_EXP_RATE),
                config.getDouble(LEVEL_CUTOFF_UNDER_DROP_RATE),
                config.getDouble(LEVEL_CUTOFF_UNDER_EXP_DECAY_PER_LEVEL),
                config.getDouble(LEVEL_CUTOFF_UNDER_DROP_DECAY_PER_LEVEL),
                config.getDouble(LEVEL_CUTOFF_UNDER_RATE_FLOOR),
                config.getInt(LEVEL_CUTOFF_UNDER_EXP_THRESHOLD));
    }

    /**
     * ダンジョンの挑戦レベルに応じた報酬の増減(2026-08-18 W-80)。
     *
     * <p>EMダイナミックダンジョンの選択レベルはインスタンス内のモブ全員のレベルになるので敵の強さには
     * 効いていたが、TF追加ドロップの確率({@code combat/mob-overrides.yml} の固定 {@code chance})にも
     * 撃破EXPの傾斜にもほとんど効かず、「一番低いレベルを選んで最速で回す」のが常に最適だった。
     *
     * <p>判定に使うのは<b>倒したモブのレベル</b>(= 選んだレベル±難易度補正)だけで、プレイヤーのレベルは見ない。
     * 適用先は呼び出し側({@code KillRewardAdjuster})が<b>ダンジョンワールドで倒したモブに限定</b>する。
     * レベル差で書くとオーバーワールドの高レベルモブにも効いてしまい、
     * {@code level-cutoff.under-level}(W-73)の狙いと正面衝突するため(2026-08-18 差し戻し)。
     *
     * <p>2026-08-18 ユーザー指示で片側の上乗せから<b>増減両方</b>へ変えた。{@code pivot-level} で等倍、
     * それより低いダンジョンは規定値より少なく、高いダンジョンは多くなる。同じ「選ぶ動機」を
     * より低い頭打ち倍率で作れる。刻みが {@code step} レベル単位なのは、EMの難易度が
     * モブレベルを ∓5 動かすので「難易度1段=報酬1段」で対応させるため。
     */
    public DungeonLevelReward dungeonLevelReward() {
        TypedConfig config = domain.get();
        return new DungeonLevelReward(
                config.getBoolean(DUNGEON_LEVEL_REWARD_ENABLED),
                config.getInt(DUNGEON_LEVEL_REWARD_PIVOT_LEVEL),
                config.getInt(DUNGEON_LEVEL_REWARD_STEP),
                config.getDouble(DUNGEON_LEVEL_REWARD_DROP_PER_STEP),
                config.getDouble(DUNGEON_LEVEL_REWARD_DROP_CAP),
                config.getDouble(DUNGEON_LEVEL_REWARD_DROP_PENALTY_CAP),
                config.getDouble(DUNGEON_LEVEL_REWARD_EXP_PER_STEP),
                config.getDouble(DUNGEON_LEVEL_REWARD_EXP_CAP),
                config.getDouble(DUNGEON_LEVEL_REWARD_EXP_PENALTY_CAP));
    }

    public DurabilityPenaltySettings durabilityPenalty() {
        TypedConfig config = domain.get();
        return new DurabilityPenaltySettings(
                config.getBoolean(DURABILITY_DUNGEON_ONLY),
                config.getBoolean(DURABILITY_RESPECT_UNBREAKING),
                config.getBoolean(DURABILITY_PREVENT_BREAK),
                config.getBoolean(DURABILITY_ON_HIT_ENABLED),
                config.getDouble(DURABILITY_ON_HIT_PERCENT),
                config.getInt(DURABILITY_ON_HIT_MIN_DAMAGE),
                config.getBoolean(DURABILITY_ON_HIT_INCLUDE_OFFHAND),
                config.getBoolean(DURABILITY_ON_DEATH_ENABLED),
                config.getDouble(DURABILITY_ON_DEATH_PERCENT),
                config.getInt(DURABILITY_ON_DEATH_MIN_DAMAGE),
                config.getBoolean(DURABILITY_ON_DEATH_INCLUDE_HANDS));
    }

    /** 防御率% granted per point of the victim's vanilla armor attribute (fallback mapping). */
    public double vanillaArmorDefenseRatePerPoint() {
        return domain.get().getDouble(VANILLA_ARMOR_DEFENSE_RATE_PER_POINT);
    }

    /** Upper clamp for the vanilla-armor-derived 防御率% (fallback mapping). */
    public double vanillaArmorDefenseRateMax() {
        return domain.get().getDouble(VANILLA_ARMOR_DEFENSE_RATE_MAX);
    }

    /** 防具強度 granted per point of the victim's vanilla armor-toughness attribute (fallback mapping). */
    public double vanillaArmorStrengthPerPoint() {
        return domain.get().getDouble(VANILLA_ARMOR_STRENGTH_PER_POINT);
    }

    /**
     * The fixed stat-key names feeding {@code AttackStatBridge} (COMBAT_SYSTEM_SPEC 3.1).
     *
     * <p>2026-07-25 (CMB-31): previously read from {@code combat/damage.yml attack-stat-keys.*}, but
     * every key's schema default, shipped yml value, and hardcoded name were identical, and renaming
     * one would silently break other consumers that don't follow config (see
     * {@link AttackStatKeys} javadoc). Now returns the fixed {@link AttackStatKeys#DEFAULT} constant;
     * the method signature is kept so existing callers (including compiled fork consumers) are
     * unaffected.
     */
    public AttackStatKeys attackStatKeys() {
        return AttackStatKeys.DEFAULT;
    }

    /**
     * The fixed stat-key names feeding {@code DefenseStatBridge} (LD-13).
     *
     * <p>2026-07-25 (CMB-31): see {@link #attackStatKeys()} — same rationale, now returns the fixed
     * {@link DefenseStatKeys#DEFAULT} constant instead of reading {@code defense-stat-keys.*}.
     */
    public DefenseStatKeys defenseStatKeys() {
        return DefenseStatKeys.DEFAULT;
    }
}
