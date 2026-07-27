package com.trinityforge.farming;

/**
 * {@code animal-damage-4x}の純ロジック(対象判定と倍率計算)。Bukkit非依存
 * ({@link com.trinityforge.listeners.CombatListener#aoeEligible}と同様、instanceof結果をbooleanで
 * 受け取ることでBukkitエンティティ無しに単体テストできる)。
 */
public final class AnimalDamagePolicy {

    private AnimalDamagePolicy() {
    }

    /**
     * 動物への与ダメージ倍率の対象となるか({@code Animals}実装 かつ {@code Monster}非実装、
     * 敵対mob除く)。ハチ等の{@code Animals}実装は対象に含まれる(要調整: 個別除外が必要なら
     * ここに条件を追加する)。
     */
    public static boolean eligibleVictim(boolean isAnimal, boolean isMonster) {
        return isAnimal && !isMonster;
    }

    /**
     * 最終ダメージに倍率を乗算する。{@code multiplier}が非有限/0以下、または{@code damage}が0以下なら
     * {@code damage}をそのまま返す(フェイルセーフ: 無効configで攻撃が消えたり負値になったりしない)。
     * 結果は負値にならないようクランプする。
     */
    public static double multiply(double damage, double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier <= 0.0 || damage <= 0.0) {
            return damage;
        }
        return Math.max(0.0, damage * multiplier);
    }
}
