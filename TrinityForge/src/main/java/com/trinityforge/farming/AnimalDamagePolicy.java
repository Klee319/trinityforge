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
     * 動物への与ダメージ倍率の対象となるか({@code Animals}実装 かつ 敵対でない)。
     * ハチ等の{@code Animals}実装は対象に含まれる(要調整: 個別除外が必要ならここに条件を追加する)。
     *
     * <p><b>敵対判定は Paper の {@code Enemy} で渡すこと。</b>2026-07-31 まで呼び出し側が Bukkit の
     * {@code Monster} を渡していたため、{@code Animals=true / Monster=false / Enemy=true} である
     * <b>HOGLIN が「動物」として 4 倍で殴れていた</b>（ネザーでの畜産倍率漏れ）。
     * {@code Monster}/{@code Animals} は敵対分類に使えない、というのはこのプロジェクトで既に
     * 明文化済みの落とし穴（{@code ResourceServerMobSimulationTest#isHostile}）。
     *
     * @param isAnimal  {@code victim instanceof org.bukkit.entity.Animals}
     * @param isHostile {@code victim instanceof org.bukkit.entity.Enemy}
     */
    public static boolean eligibleVictim(boolean isAnimal, boolean isHostile) {
        return isAnimal && !isHostile;
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
