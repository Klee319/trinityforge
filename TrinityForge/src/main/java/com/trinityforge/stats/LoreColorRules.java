package com.trinityforge.stats;

import java.util.Objects;

/**
 * Value-color rules for stat lore lines, loaded from {@code stats/lore.yml layout.colors}.
 * 固定値ステ(fixed/per-quality)とロールステ(random)で正負の色を別々に設定でき、さらに
 * 付与確率 (grant-chances) が設定されたステ専用の色を「高度なオプション」として上書きできる。
 * chance系がnull/空の場合は通常色へフォールバックする。Immutable。
 *
 * @param fixedPositive       固定値ステのプラス値色
 * @param fixedNegative       固定値ステのマイナス値色
 * @param rollPositive        ロールステのプラス値色
 * @param rollNegative        ロールステのマイナス値色
 * @param fixedChancePositive grant-chances付き固定値ステのプラス値色 (空=fixedPositive)
 * @param fixedChanceNegative grant-chances付き固定値ステのマイナス値色 (空=fixedNegative)
 * @param rollChancePositive  grant-chances付きロールステのプラス値色 (空=rollPositive)
 * @param rollChanceNegative  grant-chances付きロールステのマイナス値色 (空=rollNegative)
 */
public record LoreColorRules(String fixedPositive,
                             String fixedNegative,
                             String rollPositive,
                             String rollNegative,
                             String fixedChancePositive,
                             String fixedChanceNegative,
                             String rollChancePositive,
                             String rollChanceNegative) {

    public LoreColorRules {
        Objects.requireNonNull(fixedPositive, "fixedPositive");
        Objects.requireNonNull(fixedNegative, "fixedNegative");
        Objects.requireNonNull(rollPositive, "rollPositive");
        Objects.requireNonNull(rollNegative, "rollNegative");
        fixedChancePositive = fixedChancePositive == null ? "" : fixedChancePositive;
        fixedChanceNegative = fixedChanceNegative == null ? "" : fixedChanceNegative;
        rollChancePositive = rollChancePositive == null ? "" : rollChancePositive;
        rollChanceNegative = rollChanceNegative == null ? "" : rollChanceNegative;
    }

    /** 従来のハードコード(FIXED=white/RANDOM=green/負=red)と同じ見た目のデフォルト。 */
    public static LoreColorRules defaults() {
        return new LoreColorRules("white", "red", "green", "red", "", "", "", "");
    }

    /**
     * Resolves the value color for one stat line.
     *
     * @param roll      true=ロールステ(random由来), false=固定値ステ(fixed/per-quality由来)
     * @param hasChance そのステに付与確率(grant-chances)が設定されているか
     */
    public String colorFor(double value, boolean roll, boolean hasChance) {
        boolean negative = value < 0;
        if (hasChance) {
            String chance = roll
                    ? (negative ? rollChanceNegative : rollChancePositive)
                    : (negative ? fixedChanceNegative : fixedChancePositive);
            if (!chance.isBlank()) {
                return chance;
            }
        }
        if (roll) {
            return negative ? rollNegative : rollPositive;
        }
        return negative ? fixedNegative : fixedPositive;
    }
}
