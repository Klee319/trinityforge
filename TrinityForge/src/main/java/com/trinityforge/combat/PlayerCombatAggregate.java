package com.trinityforge.combat;

import com.trinityforge.config.domains.StatCapsConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * プレイヤー戦闘スタットの統合集計結果(#3 全ステ合算)。攻撃側({@code CombatListener})と防御側
 * ({@link PlayerDefenseResolver})の両方がこの一つの集計を参照することで、防具4部位・メインハンド・
 * (設定により)オフハンド・スキルツリーパーク・アドオン(スレッド等)のステータス源を二重実装せずに
 * 共有する({@link PlayerStatAggregator}が唯一の生成者)。
 *
 * @param item        防具4部位 + メインハンド(または発射武器) + (有効時)オフハンドの {@code DerivedItemStats}
 *                    合算(canonicalキー)。攻撃力の置換元・防御ブリッジの入力として使う。
 * @param mainhand    メインハンド(または発射武器)単体の derived マップ。アイテムCTは必ずこれだけを読む
 *                    (防具/オフハンドが誤ってアイテムCTをゲートしないため)。
 * @param perkAttack  スキルツリーパークの攻撃側 buff マップ。
 * @param perkDefense スキルツリーパークの防御側 buff マップ。
 * @param addon       アドオン(ArsPaperの防具スレッド等)が書き込んだプレイヤー単位のスタットマップ。
 * @param multipliers 乗算モード集計: レイヤID → canonicalステキー → Σ(v-1)。装備全部位から集めた
 *                    同一レイヤの倍率は「1 + Σ(v-1)」に合成され、レイヤ同士は乗算される。
 *                    加算合算(item/perk/addon)が終わった総合値に {@link #multiplierFor} を掛けるのが
 *                    消費側の契約(アイテムCT=mainhand専用マップだけは例外で掛けない)。
 * @param statCaps    (2026-07-26 stat-cap導入、任意・{@code null}可) {@code combat/stat-caps.yml} —
 *                    設定されていれば {@link #totalOf} の返り値(装備+パーク+アドオンを加算し乗算まで
 *                    適用した「最終合算値」)へ上限をかける唯一の場所。{@code null} は「クランプ機能
 *                    自体を使わない」旧来どおりの経路(既定は上限なしと等価、後方互換コンストラクタ経由の
 *                    既存呼び出し元は全て {@code null} になる)。
 */
public record PlayerCombatAggregate(
        Map<String, Double> item,
        Map<String, Double> mainhand,
        Map<String, Double> perkAttack,
        Map<String, Double> perkDefense,
        Map<String, Double> addon,
        Map<String, Map<String, Double>> multipliers,
        StatCapsConfig statCaps) {

    public PlayerCombatAggregate {
        item = item == null ? Map.of() : Map.copyOf(item);
        mainhand = mainhand == null ? Map.of() : Map.copyOf(mainhand);
        perkAttack = perkAttack == null ? Map.of() : Map.copyOf(perkAttack);
        perkDefense = perkDefense == null ? Map.of() : Map.copyOf(perkDefense);
        addon = addon == null ? Map.of() : Map.copyOf(addon);
        Map<String, Map<String, Double>> multCopy = new LinkedHashMap<>();
        if (multipliers != null) {
            multipliers.forEach((layer, stats) -> {
                if (stats != null && !stats.isEmpty()) {
                    multCopy.put(layer, Map.copyOf(stats));
                }
            });
        }
        multipliers = Map.copyOf(multCopy);
        // statCaps はそのまま保持(null許容、Map.copyOfのような防御的コピーは不要 — 不変ロード結果への参照)。
    }

    /** 乗算レイヤ・statCaps無しの5引数コンストラクタ(back-compat)。 */
    public PlayerCombatAggregate(Map<String, Double> item, Map<String, Double> mainhand,
                                 Map<String, Double> perkAttack, Map<String, Double> perkDefense,
                                 Map<String, Double> addon) {
        this(item, mainhand, perkAttack, perkDefense, addon, Map.of(), null);
    }

    /** statCaps無しの6引数コンストラクタ(back-compat)。 */
    public PlayerCombatAggregate(Map<String, Double> item, Map<String, Double> mainhand,
                                 Map<String, Double> perkAttack, Map<String, Double> perkDefense,
                                 Map<String, Double> addon, Map<String, Map<String, Double>> multipliers) {
        this(item, mainhand, perkAttack, perkDefense, addon, multipliers, null);
    }

    /**
     * 該当ステの最終倍率: Π over layers of {@code 1 + Σ(v-1)}。乗算エントリが無ければ 1.0。
     * 負倍率も呪い/ギャンブル装備の正式値として保持し、最終的な戦闘結果は共通ダメージ床へ委ねる。
     */
    public double multiplierFor(String canonicalKey) {
        double product = 1.0;
        for (Map<String, Double> layer : multipliers.values()) {
            Double sum = layer.get(canonicalKey);
            if (sum != null) {
                product *= 1.0 + sum;
            }
        }
        return product;
    }

    /**
     * 該当ステの「プレイヤー総合値」: item(防具+メインハンド+オフハンド+ロール) + パーク(攻撃/防御) +
     * アドオンの全チャネル加算に乗算レイヤを適用した値。採集系(mining-fortune / fishing-luck /
     * fishing-bonus)のような「内部データを総合ステータスとして扱う」消費者向けの単一エントリポイント。
     */
    public double totalOf(String canonicalKey) {
        double sum = item.getOrDefault(canonicalKey, 0.0)
                + perkAttack.getOrDefault(canonicalKey, 0.0)
                + perkDefense.getOrDefault(canonicalKey, 0.0)
                + addon.getOrDefault(canonicalKey, 0.0);
        double total = sum * multiplierFor(canonicalKey);
        // 2026-07-26 stat-cap導入: 装備+パーク+アドオンの加算と乗算レイヤ適用が終わった直後の
        // 「最終合算値」がここ(このメソッドの戻り値)であり、上限を掛ける唯一の場所。statCapsが
        // 未設定(null、既定)ならno-opで既存挙動と完全に同一。
        return statCaps == null ? total : statCaps.clamp(canonicalKey, total);
    }

    /**
     * (2026-07-26 stat-cap カバレッジ拡大) {@code totalOf} と同一の意味論で {@link StatCapsConfig#clamp}
     * を公開する生の出口。{@code totalOf} が自前で完結できるのは「item+perk+addonの加算→乗算」という
     * 単純な形の値だけであり、ATTACK/DEFENSE チャネルの一部キー(attack-power の enchant合成、
     * DEFENSE の {@code DefenseStats#combine} 後の合成値等)は呼び出し側(CombatListener /
     * PlayerDefenseResolver)が独自に「装備+パーク+アドオン+乗算適用済みの最終値」を組み立てるため、
     * その一点でこのメソッドを呼んでクランプする(重複実装を避け、CT短縮系除外/非有限値スルーの
     * 意味論を {@link StatCapsConfig} 側に一元化したまま利用する)。statCaps未設定(null)ならno-op。
     */
    public double clamp(String canonicalKey, double raw) {
        return statCaps == null ? raw : statCaps.clamp(canonicalKey, raw);
    }

    /**
     * 合算済みステマップに乗算レイヤを適用した新しいマップを返す(引数は変更しない)。
     * 「加算合算 → 総合値に乗算」の消費側契約をここに一元化する。
     */
    public Map<String, Double> applyMultipliers(Map<String, Double> merged) {
        if (multipliers.isEmpty() || merged.isEmpty()) {
            return merged;
        }
        Map<String, Double> result = new LinkedHashMap<>(merged);
        for (Map.Entry<String, Double> entry : result.entrySet()) {
            double m = multiplierFor(entry.getKey());
            if (m != 1.0) {
                entry.setValue(entry.getValue() * m);
            }
        }
        return result;
    }
}
