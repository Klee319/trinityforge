package com.trinityforge.items;

import com.trinityforge.pdc.ItemData;
import org.bukkit.inventory.ItemStack;

/**
 * 「EXP解呪の良薬」3種。日次逓減（{@code stats/skill-exp.yml} の {@code daily-diminishing}）を
 * 全スキル一括で動かす使い切り。
 *
 * <ul>
 *   <li>並: 減衰量を 10% 減らす。取得倍率は 70% までしか戻せない。</li>
 *   <li>上: 減衰量を 15% 減らす（上限なし）。</li>
 *   <li>極: 減衰を 2 時間無効化する（蓄積は増やさない）。</li>
 * </ul>
 *
 * <p>恒久的に下限を書き換えるものではない。並・上は飲んだあとまた稼げば普通に下がる。
 * 極は期限が切れたら、その時点の蓄積で逓減が再開する。
 */
public enum ExpCleanseTonic {

    LESSER("exp_cleanse_tonic_lesser", 0.10, 0.70, 0L),
    GREATER("exp_cleanse_tonic_greater", 0.15, 1.0, 0L),
    SUPREME("exp_cleanse_tonic_supreme", 0.0, 1.0, 2L * 60L * 60L * 1000L);

    private final String catalogId;
    private final double decayReduceFraction;
    private final double multiplierCeiling;
    private final long immunityMillis;

    ExpCleanseTonic(String catalogId, double decayReduceFraction, double multiplierCeiling,
                    long immunityMillis) {
        this.catalogId = catalogId;
        this.decayReduceFraction = decayReduceFraction;
        this.multiplierCeiling = multiplierCeiling;
        this.immunityMillis = immunityMillis;
    }

    public String catalogId() {
        return catalogId;
    }

    /** 減衰量のうち消す割合。極は 0（無効化側）。 */
    public double decayReduceFraction() {
        return decayReduceFraction;
    }

    /** 戻せる取得倍率の上限。1.0 は上限なし。 */
    public double multiplierCeiling() {
        return multiplierCeiling;
    }

    public boolean grantsImmunity() {
        return immunityMillis > 0L;
    }

    public long immunityMillis() {
        return immunityMillis;
    }

    public int decayReducePercent() {
        return (int) Math.round(decayReduceFraction * 100.0);
    }

    public int ceilingPercent() {
        return (int) Math.round(multiplierCeiling * 100.0);
    }

    /**
     * 手に持っているものが良薬なら対応する種別を返す。判定は表示名や素材ではなく
     * PDC のカタログID（{@link ItemData#catalogId()}）。
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
