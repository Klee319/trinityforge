package com.trinityforge.stats;

import com.trinityforge.combat.PlayerStatAggregator;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.logging.Logger;

/**
 * Reads the killer's {@code mob_drop_quality} stat (装備+perk合算, 2026-07-23 stat-gate-overhaul §2 移行B6;
 * formerly the perk-only {@code power_mobdropbonus_add}) and converts it to a quality-mode bump for
 * mob-drop equipment stamps (+1 mode per whole point, fractional part applied stochastically —
 * same EV model as 幸運/{@link PlayerLootLuckSource}).
 *
 * <p><b>2026-08-25 (W-253): バニラの幸運（ポーション）もここへ合算する。</b>
 * それまでこのクラスは {@code mob_drop_quality} だけを見ており、javadoc には
 * 「幸運は意図的にモブドロップを対象外にしている」と書いてあった。ユーザー確定要件
 * 「幸運のポーションのエフェクトはドロップ品質に乗るように」でその線引きを撤回した
 * ── <b>釣り・拾得・作業台・儀式・醸造には既に乗っていて、モブドロップだけが乗っていない</b>
 * という状態は、プレイヤーからは「幸運が効く場所と効かない場所がある」ではなく
 * 「幸運が効いていない」に見える。
 *
 * <p>換算レートは作業台・儀式・醸造と同じ {@code stats/quality.yml} の
 * {@code luck-potion-quality-per-level}（{@link #luckPerLevel} で注入する）。
 * 釣り・拾得側の「1レベル=+1.0 固定」に揃えなかったのは、あちらが
 * {@code loot_luck} と単位を共有していて動かせないのに対し、こちらは
 * {@code mob_drop_quality} と単位を共有しており、つまみで絞れる方が望ましいため。
 * レート未注入（{@code null}）のときは 1.0 として扱う。
 */
public final class PlayerMobDropBonusSource {

    private static final String STAT = "MOB_DROP_BONUS";
    private static final String MOB_DROP_QUALITY_KEY = StatKeys.canonical("mob_drop_quality");

    private final boolean available;
    private final PlayerStatAggregator aggregator;
    /** {@code stats/quality.yml} の {@code luck-potion-quality-per-level}。null = 1.0 として扱う。 */
    private final DoubleSupplier luckPerLevel;

    public PlayerMobDropBonusSource(Logger log, PlayerStatAggregator aggregator) {
        this(log, aggregator, null);
    }

    public PlayerMobDropBonusSource(Logger log, PlayerStatAggregator aggregator,
                                    DoubleSupplier luckPerLevel) {
        Objects.requireNonNull(log, "log");
        this.aggregator = aggregator;
        this.available = aggregator != null;
        this.luckPerLevel = luckPerLevel;
    }

    boolean available() {
        return available;
    }

    String statKey() {
        return STAT;
    }

    /** Stochastic integer bonus from the fractional total (same EV model as 幸運). */
    public int qualityModeBonus(Player player, double unitRandom) {
        return GatheringPolicy.expectedExtra(totalBonus(player), unitRandom);
    }

    /** Convenience overload mirroring {@link PlayerLootLuckSource}'s fork-facing signature. */
    public int qualityModeBonus(Player player, java.util.concurrent.ThreadLocalRandom rng) {
        return qualityModeBonus(player, rng.nextDouble());
    }

    /**
     * {@code mob_drop_quality}装備+perk合算に、バニラ幸運（ポーション）の寄与を足した値。
     *
     * <p><b>装備ステが読めない環境でも幸運ぶんは返す</b>（W-253）。合算器が未配線だと 0 を返す
     * 旧実装のままにすると、`aggregator == null` のテスト・軽量構成で「幸運を足したのに
     * 何も変わらない」という取りこぼしになる。幸運はプレイヤーのポーション効果だけで決まるので
     * 合算器に依存しない。
     */
    public double totalBonus(Player player) {
        if (player == null) {
            return 0.0;
        }
        double stat = available
                ? Math.max(0.0, aggregator.aggregate(player).totalOf(MOB_DROP_QUALITY_KEY))
                : 0.0;
        return stat + vanillaLuckContribution(player);
    }

    /**
     * バニラ幸運（{@link VanillaLuckEffect}）の寄与。効果レベル × {@code luck-potion-quality-per-level}。
     * レート未注入・負値は 1.0 / 0.0 へ倒す。
     */
    double vanillaLuckContribution(Player player) {
        double level = VanillaLuckEffect.levelOf(player);
        if (level <= 0.0) {
            return 0.0;
        }
        double rate = luckPerLevel == null ? 1.0 : luckPerLevel.getAsDouble();
        if (!Double.isFinite(rate) || rate <= 0.0) {
            return 0.0;
        }
        return level * rate;
    }
}
