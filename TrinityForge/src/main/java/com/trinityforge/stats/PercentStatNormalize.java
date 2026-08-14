package com.trinityforge.stats;

import java.util.Set;

/**
 * Coerces percent-point authoring mistakes ({@code 75} meaning 75%) into the fraction form ({@code 0.75})
 * that lore ({@link LoreValueFormat#PERCENT} = ×100) and combat expect.
 *
 * <p>Only keys that are conventionally in {@code [0, 1]} (or a signed rate near that) are rewritten.
 * {@code crit-damage} is excluded — values like {@code 10} (+1000% on crit) are intentional.
 * {@code damage-modifier} uses the same integer 2–100 → /100 rule as combat (1.0 stays ×1.0).
 *
 * <p><b>キー追加時の注意</b>: {@link #RATE_KEYS} は {@link StatVocabulary} や {@code stats/lore.yml} とは
 * 独立して手動管理されており、自動同期しない。新しい % 系 stat キーを追加する際は {@code StatVocabulary}/
 * {@code lore.yml} と合わせてここも更新すること（さもなくばドリフトが発生する）。
 */
public final class PercentStatNormalize {

    private static final Set<String> FIXED_RATE_KEYS = Set.of(
            StatKeys.canonical("damage-modifier"),
            StatKeys.canonical("percent-bonus-damage"),
            StatKeys.canonical("crit-chance"),
            StatKeys.canonical("penetration"),
            StatKeys.canonical("bleed-chance"),
            StatKeys.canonical("dodge-chance"),
            StatKeys.canonical("phys-resistance"),
            StatKeys.canonical("magic-resistance"),
            StatKeys.canonical("damage-reduction"),
            StatKeys.canonical("armor-strength"),
            // 2026-07-23 stat-gate-overhaul §2.1: 新規PERCENT系キー
            StatKeys.canonical("bow-accuracy"),
            StatKeys.canonical("ammo-save-chance"),
            StatKeys.canonical("distance-damage-bonus"),
            StatKeys.canonical("arrow-velocity"),
            // 2026-07-31: bow-cooldown-reduction は語彙ごと廃止(アイテムCT短縮へ一本化)。
            // melee-knockback も除外した — 矢ノックバック(arrow-knockback)と単位系を揃えて
            // FLAT + 単位 m にしたため、ここに残すと「2(=2m)」が 0.02 へ黙って矯正される。
            StatKeys.canonical("stun-chance"),
            StatKeys.canonical("power-attack-damage"),
            StatKeys.canonical("health-regen-bonus"),
            StatKeys.canonical("hunger-save-chance"),
            StatKeys.canonical("mob-drop-bonus"),
            StatKeys.canonical("skill-exp-bonus"),
            StatKeys.canonical("cooldown-reduction"),
            StatKeys.canonical("gacha-rate-bonus"),
            StatKeys.canonical("suspicious-respawn-chance"),
            StatKeys.canonical("hive-harvest-fortune"),
            StatKeys.canonical("food-save-chance"),
            // ※ lapis-cost-reduction は2026-08-14に廃止(ArsPaper の消費側リスナーごと削除)。
            //   もともと「軽減する個数」(FLAT整数)でRATE_KEYS対象外だったので、この一覧に影響は無い。
            StatKeys.canonical("source-cost-reduction"),
            StatKeys.canonical("material-refund-chance"),
            StatKeys.canonical("ingredient-save-chance"),
            StatKeys.canonical("fishing-luck"),
            // 2026-08-05 実サーバ報告「釣りボーナスは%では？ドロップ増加ステと同じ期待値仕様だったはず」:
            // fishing-bonus は 2026-07-28 に「追加ドロップの期待個数(生値)」として意図的に対象外にされて
            // いたが、出荷 skilltree/fishing.yml は他の%系と同じくパーセントポイント(A:5 / C:10 /
            // prestige:10)で書かれている。矯正されないと合算 25 がそのまま
            // GatheringPolicy.expectedExtra へ渡り、1回の釣りで追加ドロップ25個(MAX_EXTRA 256 まで)に
            // なっていた ── mining-fortune の登録漏れ(同日修正)と全く同じ壊れ方。
            // ocean-fishing-bonus は出荷値が 0.05 の分数なので coerce は素通りする(|v|<=1 は非対象)が、
            // 5 と書かれたときに fishing-bonus と食い違わないよう一緒に登録する。
            StatKeys.canonical("fishing-bonus"),
            StatKeys.canonical("ocean-fishing-bonus"),
            // 2026-07-28: mining-fortune はここに登録漏れしていた(fishing-luck だけが入っていた)。
            // skilltree の各ノードは effect-text「ドロップ増加+15%」に合わせて mining-fortune: 15 と
            // パーセントポイントで書かれているが、矯正されないと 15(=1500%) のまま
            // MiningFortuneListener へ渡り、1ブロックあたり数個の追加ドロップになっていた
            // (精密破壊I だけでドロップ約6倍)。
            StatKeys.canonical("mining-fortune"),
            // 2026-07-24 S9: 確率系(0-1)の新規stat。管理者が 20(=20%のつもり)と入力しても 0.2 へ矯正する。
            // 倍率系(vanilla-exp-bonus / food-restore-bonus 等、1超が正当)は意図的に除外し確率3種のみ追加。
            StatKeys.canonical("woodcutting-extra-drop-chance"),
            StatKeys.canonical("harvest-extra-drop-chance"),
            StatKeys.canonical("breeding-extra-child-chance"),
            // 2026-07-25: エンチャント/ポーション実行者限定ステ。brew-speed-bonus は短縮率。
            // enchant-luck/potion-quality-bonus は coating-charges 同様の整数ポイント蓄積のため対象外。
            // 2026-08-14: enchant-exp-gain-bonus をここから外した。廃止して enchanting-exp-bonus へ
            // 統合したが、職業EXP増加(<スキルID>-exp-bonus)は倍率系として意図的に矯正対象外
            // (1超が正当)なので、エイリアスで読み替わった先をここへ足してはいけない。
            StatKeys.canonical("brew-speed-bonus"),
            // 2026-07-25 (config editor T2): マナ初期値の%系3キー(base-stats.yml 専用、lore.yml未登録)。
            StatKeys.canonical("mana-onhit-percent"),
            StatKeys.canonical("mana-onattack-percent"),
            StatKeys.canonical("mana-idle-bonus-percent"),
            // 2026-07-25: attack-speed-bonus は全ソース横断の割合ボーナス(0.10=+10%)。
            // 20と書いても0.2へ矯正する(他の-bonus系%キーと同じ扱い)。
            StatKeys.canonical("attack-speed-bonus"),
            // 2026-07-25 CT設計一本化 §2: アクティブスキルCT短縮のper-skillキー(旧グローバル
            // skill-cooldown-reduction を分割)。cooldown-reduction(アイテムCT短縮)と同じ割合系(符号反転=正が
            // 短縮、負でCT増加)なので同じ矯正対象に含める。新しいActiveSkillごとに
            // "<id>-cooldown-reduction" をここにも追加すること(ActiveSkillCooldownKeysのクラスコメント参照 —
            // このSetはStatVocabularyとは独立して手動管理されており自動同期しない)。
            StatKeys.canonical("haste-active-mining-cooldown-reduction"),
            // 2026-07-25 PRG-07/伐採一括伐採CT短縮: tree-fell専用CT短縮キー(StatVocabulary参照)。
            StatKeys.canonical("tree-fell-cooldown-reduction"),
            // 2026-07-25 課題2: 棘の鎧ステータス化の反射率（割）。reflect-flat は固定ダメージ量(FLAT)なので
            // 対象外 — armor-defense-rate と同じ理由。
            StatKeys.canonical("reflect-percent"),
            // 2026-07-26 stat-scope 境界引き直し §3 (B→C 昇格): aoe-damage-rate(主命中ダメージに対する
            // 割合0.0〜)と mana-cost-reduction-percent(マナ消費軽減率)は率系。同じ8キーのうち
            // aoe-radius/aoe-max-targets/hit-mana-recovery/damage-mana-recovery/mana-cost-reduction-flat
            // は[0,1]の割合ではない(半径/対象数/マナ回復量/整数軽減量)ため対象外。
            StatKeys.canonical("aoe-damage-rate"),
            StatKeys.canonical("mana-cost-reduction-percent"),
            // enchant-cost-reductionは0..0.9にランタイム側でもクランプする割合系。
            // stun-duration-bonus は2026-07-29に割合からtick加算へ移行したため対象外。
            StatKeys.canonical("enchant-cost-reduction"),
            // 2026-08-15: defense-rate(パーク側の防御率)。armor-defense-rate から分離した [0,1] の割合系なので
            // ここに入れる — 逆に armor-defense-rate はバニラ防具値(点数)なので下のコメント通り対象外のまま。
            // 分離前は「率なのに%矯正の対象外」で、yml に 10 と書くと 1000% 軽減として通っていた。
            StatKeys.canonical("defense-rate"));
            // gathering-efficiency(採集効率、エンチャント連動方式)は「合算値をfloorしてエンチャント
            // レベルへ変換する」加算値であり、[0,1]の割合ではないため対象外(mining-efficiencyと同じ扱い)。
            // armor-defense-rate is NOT a [0,1] rate — it is vanilla Attribute.ARMOR points
            // (e.g. 8 = +8 armor). Coercing it ÷100 collapses all armor to near-zero.
            // arrow-piercing (INTEGER) and the FLAT craft-* roll keys are intentionally excluded —
            // they are not [0,1] rates.

    /**
     * 上の固定リスト + 職業EXP増加(スキル別)。後者は {@code skill-exp-bonus} と同じ %系だが、
     * 2026-08-02 の新設時に3件だけ手書きされていた(残り12スキル分は語彙にも無かった)。
     * {@link SkillExpBonusKeys} 経由で導出し、スキルが増えたときに「%矯正だけ抜ける」
     * (yml に 15 と書くと 1500% になる)事故を構造的に防ぐ。
     */
    private static final Set<String> RATE_KEYS = buildRateKeys();

    private static Set<String> buildRateKeys() {
        Set<String> keys = new java.util.LinkedHashSet<>(FIXED_RATE_KEYS);
        keys.addAll(SkillExpBonusKeys.all());
        return Set.copyOf(keys);
    }

    private PercentStatNormalize() {
    }

    public static boolean isRateKey(String canonicalKey) {
        return RATE_KEYS.contains(StatKeys.canonical(canonicalKey));
    }

    /**
     * If {@code canonicalKey} is a rate key and {@code value} looks like percent points
     * ({@code |v| > 1} and {@code |v| <= 100}), returns {@code value / 100}; otherwise {@code value}.
     */
    public static double coerce(String canonicalKey, double value) {
        if (StunDurationStatNormalize.KEY.equals(StatKeys.canonical(canonicalKey))) {
            return StunDurationStatNormalize.normalizeAddend(canonicalKey, value);
        }
        if (!Double.isFinite(value) || !isRateKey(canonicalKey)) {
            return value;
        }
        double abs = Math.abs(value);
        // Whole numbers 2..100 (and -2..-100) are almost always "75%" written as 75.
        // Leave |v|<=1 (already a fraction) and non-integers like 1.2 (120% endpoint) alone.
        if (abs > 1.0 && abs <= 100.0 && abs == Math.floor(abs)) {
            return value / 100.0;
        }
        return value;
    }
}
