package com.trinityforge.items;

import com.trinityforge.pdc.ItemData;
import org.bukkit.inventory.ItemStack;

/**
 * 「EXP解呪の良薬」3種（2026-08-24 ユーザー要望）。
 *
 * <p>日次逓減（{@code stats/skill-exp.yml} の {@code daily-diminishing}。直近24時間に稼いだ量に応じて
 * スキルEXPの取得量が薄まる仕組み）を<b>飲んだ瞬間だけ解除する使い切り</b>。
 * 恒久的に下限を書き換えるものではないので、飲んだあとまた稼げば普通に下がる。
 *
 * <p><b>数字の向きに注意。</b> ユーザーの指定は「10%(-40%)まで減らせるもの／25%(-25%)／50%(初期値)」で、
 * これは<b>減る側（ペナルティ）の上限</b>。出荷設定の下限 {@code floor: 0.5} が
 * 「どれだけ稼いでも最悪50%は入る」＝<b>ペナルティ50%が初期値</b>なので、
 * ペナルティ10%まで削れる良薬＝<b>取得倍率90%まで引き戻す</b>良薬になる。
 * ここが逆だと「良薬を飲むほど稼げなくなる」ので、{@link #targetMultiplier()} は
 * 「引き戻す先の倍率」であることを名前で固定してある。
 *
 * <p>並（{@code 50%}）は倍率そのものは下限と同じで動かないが、無意味ではない —— 蓄積は下限へ
 * 張り付いたあとも増え続けるので、張り付き始める手前まで削れば<b>自然回復までの時間が実際に縮む</b>
 *（詳細は {@code DailyExpDiminishing#maxAmountFor}）。
 *
 * <p>効果は<b>全スキル一括</b>（2026-08-24 ユーザー選択）。
 */
public enum ExpCleanseTonic {

    /** 並: ペナルティを50%（＝初期値）まで。倍率は 0.50 以上へ。 */
    LESSER("exp_cleanse_tonic_lesser", 0.50),
    /** 上: ペナルティを25%まで。倍率は 0.75 以上へ。 */
    GREATER("exp_cleanse_tonic_greater", 0.75),
    /** 極: ペナルティを10%まで。倍率は 0.90 以上へ。 */
    SUPREME("exp_cleanse_tonic_supreme", 0.90);

    private final String catalogId;
    private final double targetMultiplier;

    ExpCleanseTonic(String catalogId, double targetMultiplier) {
        this.catalogId = catalogId;
        this.targetMultiplier = targetMultiplier;
    }

    /** {@code items/catalog.yml} 上のID。 */
    public String catalogId() {
        return catalogId;
    }

    /** 引き戻す先の<b>取得倍率</b>（0.90 なら「取得量は90%以上に戻る」）。 */
    public double targetMultiplier() {
        return targetMultiplier;
    }

    /** プレイヤーへ出す「◯%」（取得倍率の百分率）。 */
    public int targetPercent() {
        return (int) Math.round(targetMultiplier * 100.0);
    }

    /**
     * 手に持っているものが良薬なら対応する種別を返す。判定は表示名や素材ではなく
     * PDC のカタログID（{@link ItemData#catalogId()}） —— 表示名も素材もエディタでいつでも変わる。
     *
     * @return 該当しなければ {@code null}
     */
    public static ExpCleanseTonic of(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        String catalogId = ItemData.of(stack.getItemMeta()).catalogId().orElse(null);
        if (catalogId == null) {
            return null;
        }
        for (ExpCleanseTonic tonic : values()) {
            if (tonic.catalogId.equals(catalogId)) {
                return tonic;
            }
        }
        return null;
    }
}
