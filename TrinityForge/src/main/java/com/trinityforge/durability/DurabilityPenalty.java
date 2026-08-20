package com.trinityforge.durability;

/**
 * 装備耐久ペナルティの純粋計算(Bukkit 非依存)。副作用は
 * {@link EquipmentDurabilityService} が持ち、ここは「何点減らすか」だけを決める。
 */
public final class DurabilityPenalty {

    private DurabilityPenalty() {
    }

    /**
     * 「最大耐久 × 割合(最低 {@code minDamage})」の減少量。
     *
     * <p>切り捨て後に下限を適用するので、割合が小さすぎて0になる装備(例: 最大耐久250 × 0.1% = 0.25)でも
     * {@code minDamage} 分は必ず減る。逆に {@code minDamage=0} なら「割合が1点に届かない装備は減らない」
     * という設定も作れる。
     *
     * @param maxDurability 装備の最大耐久(0以下なら耐久の概念が無い装備 = 0を返す)
     * @param percentOfMax  最大耐久に対する割合(0以下なら0を返す)
     * @param minDamage     減少量の下限(負値は0扱い)
     */
    public static int amountFor(int maxDurability, double percentOfMax, int minDamage) {
        if (maxDurability <= 0 || !(percentOfMax > 0.0) || !Double.isFinite(percentOfMax)) {
            return 0;
        }
        int floor = (int) Math.floor(maxDurability * percentOfMax);
        int amount = Math.max(floor, Math.max(0, minDamage));
        return Math.min(amount, maxDurability);
    }

    /**
     * 耐久力(UNBREAKING)エンチャントを反映した減少量。
     *
     * <p>減少量を {@code 1/(Lv+1)} に縮め、端数は {@code roll} で確率的に切り上げる(バニラの
     * 「Lv+1回に1回だけ減る」と期待値が一致する)。決定的に切り捨てると、減少量1・Lv3のとき
     * 常に0点=耐久力IIIが被弾ペナルティを完全に無効化してしまうため、端数は必ず確率で扱う。
     *
     * @param amount          縮める前の減少量
     * @param unbreakingLevel UNBREAKING のレベル(0以下でそのまま返す)
     * @param roll            {@code [0,1)} の乱数(テストのため外から渡す)
     */
    public static int afterUnbreaking(int amount, int unbreakingLevel, double roll) {
        if (amount <= 0) {
            return 0;
        }
        if (unbreakingLevel <= 0) {
            return amount;
        }
        double scaled = amount / (double) (unbreakingLevel + 1);
        int whole = (int) Math.floor(scaled);
        double fraction = scaled - whole;
        if (fraction > 0.0 && roll < fraction) {
            whole++;
        }
        return whole;
    }

    /**
     * 減少を適用した後の damage 値と、それによって装備が壊れるかどうか。
     *
     * @param currentDamage 現在の damage(消耗値。0が新品)
     * @param amount        減らす量
     * @param maxDurability 最大耐久
     * @param preventBreak  true のとき残耐久1(damage = maxDurability - 1)で止め、壊さない
     */
    public static Result apply(int currentDamage, int amount, int maxDurability, boolean preventBreak) {
        if (maxDurability <= 0 || amount <= 0) {
            return new Result(currentDamage, false);
        }
        int next = currentDamage + amount;
        if (next < maxDurability) {
            return new Result(next, false);
        }
        if (preventBreak) {
            // すでに残耐久1まで消耗している装備はこれ以上減らさない(damage が減る方向へ動かないよう max)。
            return new Result(Math.max(currentDamage, maxDurability - 1), false);
        }
        return new Result(next, true);
    }

    /**
     * {@link #apply} の結果。
     *
     * @param damage 適用後の damage 値(壊れる場合は最大耐久以上になりうる)
     * @param broken この適用で装備が壊れる(スロットから消える)か
     */
    public record Result(int damage, boolean broken) {
    }
}
