package com.trinityforge.combat;

import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 「{@code EntityDamageEvent} の<b>最終ダメージ</b>を目標値へ合わせる」ための共通操作
 * （2026-07-31 F5 指摘1 の修正で新設）。
 *
 * <h2>踏んだ罠: BASE を書き換えても他の modifier は再計算されない</h2>
 * <p>Paper 1.21.11 の実 API をバイトコードで確認した事実（憶測ではない）:
 * <ul>
 *   <li>{@code EntityDamageEvent#setDamage(DamageModifier, double)} は
 *       <b>{@code modifiers.put(type, damage)} だけ</b>を行う。他の modifier は一切触らない
 *       （{@code modifiers} に無いキーを渡すと {@code UnsupportedOperationException}）。</li>
 *   <li>1引数の {@code setDamage(double)} <b>だけ</b>が {@code modifierFunctions} を使って
 *       全 modifier を新しいダメージ量から再導出する。</li>
 *   <li>{@code getFinalDamage()} は<b>全 modifier の単純和</b>で、0 でクランプされない
 *       （負の最終ダメージが素通りする）。</li>
 * </ul>
 *
 * <p>したがって <b>{@code setDamage(BASE, 縮めた値)} だけを呼ぶのは危険</b>である。バニラの
 * 各軽減 modifier（{@code MAGIC} = 防護／飛び道具耐性エンチャント、{@code ABSORPTION} = 吸収ハート、
 * {@code BLOCKING} = 盾、{@code RESISTANCE}、{@code ARMOR} …）は
 * <b>縮める前の BASE から算出された「絶対値」</b>のまま残るので、最終ダメージが 0 以下へ潰れる。
 * 実害（F5 指摘1）: Lv100 帯の魔法 BASE 21222 を PvP 抑制で 3.0 へ縮めたところ、
 * 防護IVフルセットの {@code MAGIC} modifier が {@code -13582}（21222 基準）のまま残り、
 * 最終ダメージ {@code 3.0 - 13582 < 0} ＝ <b>魔法が当たっても常に0ダメージ</b>になった。
 * 吸収ハート4だけでも {@code 3.0 - 4 = -1} で同じ結末になる。
 *
 * <h2>正しい形: 全 modifier を同一係数で縮める</h2>
 * <p>{@code getFinalDamage()} が単純和なので、<b>適用中の全 modifier に同じ係数を掛ける</b>と
 * 最終ダメージがちょうど同じ係数で縮む。これで
 * <ul>
 *   <li>最終ダメージ = 目標値 に<b>一致</b>する（0 以下へ潰れることが構造的に起こらない）。</li>
 *   <li>各バニラ軽減の<b>割合</b>（防護が何%減らしていたか）が保たれる。</li>
 *   <li>吸収ハートの<b>消費量も同じ比率で縮む</b>（CraftBukkit は
 *       {@code -event.getDamage(ABSORPTION)} を吸収量から引く）ので、上限が効いている間は
 *       吸収プールが比例して長持ちする＝プレイヤーの資源が無駄に溶けない。</li>
 * </ul>
 *
 * <p><b>1引数 {@code setDamage(double)} を使わない理由</b>: あちらは「新しい BASE を渡して
 * 軽減を再導出する」API なので、最終ダメージを目標値へ<b>一致させられない</b>
 * （軽減関数が単調なだけで逆関数を解析的に持たない）。「最終ダメージへ上限を掛ける」という
 * PvP 抑制の意味論には係数スケールの方が合う。
 *
 * <p>Bukkit の {@code DamageModifier} は deprecated-for-removal だが、{@code CombatListener} /
 * {@code DotDamageListener} / {@code MagicResistanceFoldListener} と同じ扱いで当面使い続ける
 * （移行は {@code CombatListener} 冒頭の TODO(M2+) と同時に行う）。
 */
public final class FinalDamageScaling {

    /** {@code Enum.values()} は毎回配列をコピーするのでキャッシュする（{@code CombatListener} と同じ流儀）。 */
    @SuppressWarnings("deprecation")
    private static final EntityDamageEvent.DamageModifier[] MODIFIERS =
            EntityDamageEvent.DamageModifier.values();

    private FinalDamageScaling() {
    }

    /**
     * 最終ダメージを {@code finalBefore} から {@code target} へ移すための係数（純関数）。
     *
     * <p>「掛けても何も変わらない」ときは必ず {@code 1.0} を返す契約にしてある
     * （呼び出し側が「1.0 なら event を触らない」で早期returnできるようにするため）。
     *
     * @param finalBefore 抑制前の最終ダメージ（{@code <= 0} は「縮める余地なし」で 1.0）
     * @param target      抑制後に一致させたい最終ダメージ（負値は 0 として扱う）
     * @return {@code [0, ∞)} の係数。非有限な入力では 1.0（＝何もしない安全側）
     */
    public static double scaleFactor(double finalBefore, double target) {
        if (!Double.isFinite(finalBefore) || !Double.isFinite(target) || finalBefore <= 0.0) {
            return 1.0;
        }
        double clampedTarget = Math.max(0.0, target);
        if (clampedTarget >= finalBefore) {
            return 1.0; // 増幅方向は PvP 抑制の役目ではない（multiplier>1 でも素通しにする）
        }
        return clampedTarget / finalBefore;
    }

    /**
     * 適用中の全 modifier へ {@code scale} を掛ける。結果として
     * {@code getFinalDamage()} が {@code scale} 倍になる。
     *
     * @return 実際に書き換えたら true（{@code scale == 1.0} なら何もせず false）
     */
    @SuppressWarnings("deprecation")
    public static boolean scaleAllModifiers(EntityDamageEvent event, double scale) {
        if (!Double.isFinite(scale) || scale == 1.0) {
            return false;
        }
        boolean changed = false;
        for (EntityDamageEvent.DamageModifier modifier : MODIFIERS) {
            if (!event.isApplicable(modifier)) {
                continue;
            }
            double current = event.getDamage(modifier);
            if (current == 0.0) {
                continue; // 0 を縮めても 0（0化済みの折り込み modifier を無駄に書き戻さない）
            }
            event.setDamage(modifier, current * scale);
            changed = true;
        }
        return changed;
    }
}
