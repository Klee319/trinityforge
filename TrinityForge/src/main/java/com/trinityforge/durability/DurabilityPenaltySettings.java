package com.trinityforge.durability;

/**
 * TF独自の装備耐久ペナルティのつまみ一式({@code combat/damage.yml durability})。
 *
 * <p>存在理由: EliteMobs のインスタンスダンジョンは致死ダメージを
 * {@code MatchInstance.MatchInstanceEvents.onPlayerDamage} でキャンセルして「ダウン」状態へ移すため、
 * ダンジョン内では {@code PlayerDeathEvent} が一度も発火しない。その結果
 * <ul>
 *   <li>死亡時の耐久ペナルティが一切かからない(EliteMobs 自前の
 *       {@code AlternativeDurabilityLoss} は EliteMobs 製アイテムしか対象にしないので TF 装備は無傷)</li>
 *   <li>キャンセルされた致死の一撃分のバニラ防具耐久消費も消える</li>
 * </ul>
 * という状態だった。この設定はそれを TF 側で補うためのもの。
 *
 * @param dungeonOnly         インスタンスダンジョンワールドの中だけで適用するか
 * @param respectUnbreaking   耐久力(UNBREAKING)エンチャントで減少量を {@code 1/(Lv+1)} に縮めるか
 * @param preventBreak        このペナルティでは装備を壊さない(残耐久1で止める)か
 * @param onHitEnabled        被弾時の上乗せを行うか
 * @param onHitPercentOfMax   被弾1回あたりの減少量(最大耐久に対する割合)
 * @param onHitMinDamage      被弾1回あたりの減少量の下限(割合が端数で0になる装備向け)
 * @param onHitIncludeOffhand 被弾時にオフハンド(盾など)も対象に含めるか
 * @param onDeathEnabled      死亡(ダンジョンのダウンを含む)時のペナルティを行うか
 * @param onDeathPercentOfMax 死亡1回あたりの減少量(最大耐久に対する割合)
 * @param onDeathMinDamage    死亡1回あたりの減少量の下限
 * @param onDeathIncludeHands 死亡時に両手の装備も対象に含めるか
 */
public record DurabilityPenaltySettings(
        boolean dungeonOnly,
        boolean respectUnbreaking,
        boolean preventBreak,
        boolean onHitEnabled,
        double onHitPercentOfMax,
        int onHitMinDamage,
        boolean onHitIncludeOffhand,
        boolean onDeathEnabled,
        double onDeathPercentOfMax,
        int onDeathMinDamage,
        boolean onDeathIncludeHands) {
}
