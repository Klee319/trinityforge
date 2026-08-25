package com.trinityforge.stats;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * バニラの幸運({@link PotionEffectType#LUCK})効果レベルの唯一の読み取り点。
 *
 * <p>「幸運のポーション」は TF では複数の入手経路の品質へ効く。
 * <ul>
 *   <li>釣り・拾得: {@link PlayerLootLuckSource} が {@code loot_luck} へ <b>1 レベル = +1.0</b> で合算する
 *       （このレートは装備・パーク由来の {@code loot_luck} と同じ単位なので固定）。</li>
 *   <li>作業台・儀式・醸造: {@code stats/quality.yml} の {@code luck-potion-quality-per-level} 倍して
 *       品質ポイントへ加算する（2026-08-20 ユーザー要望）。</li>
 *   <li>モブドロップ: {@link PlayerMobDropBonusSource} が {@code mob_drop_quality} へ同じレートで
 *       合算する（2026-08-25 / W-253）。<b>かつては「幸運はモブドロップを意図的に対象外」と
 *       書いてあったが、その線引きはユーザー確定要件で撤回された</b>ので復元しないこと。</li>
 * </ul>
 *
 * <p>読み取りを 1 箇所に集約しているのは、{@code amplifier + 1} の変換を書き間違えても
 * <b>「幸運が 1 段ぶんずれる」という誰も気づかない形でしか現れない</b>ため。
 * 増幅度は 0 始まり（amplifier 0 = 幸運 I）で、負値は 0 に倒す。
 */
public final class VanillaLuckEffect {

    private VanillaLuckEffect() {
    }

    /** 幸運の効果レベル（幸運 I = 1.0）。未付与は 0.0。 */
    public static double levelOf(Player player) {
        if (player == null) {
            return 0.0;
        }
        PotionEffect effect = player.getPotionEffect(PotionEffectType.LUCK);
        return effect == null ? 0.0 : Math.max(0, effect.getAmplifier() + 1);
    }
}
