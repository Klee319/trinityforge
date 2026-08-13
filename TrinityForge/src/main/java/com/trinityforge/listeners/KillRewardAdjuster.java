package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.mobs.MobDropRoller;
import com.trinityforge.mobs.MobLevelCutoff;
import com.trinityforge.pdc.MobData;
import com.trinityforge.stats.StatKeys;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * 「モブを倒したときの報酬」へ共通で掛かる2つの調整を1か所へまとめたもの(2026-08-09)。
 *
 * <ol>
 *   <li><b>レベル差による足きり</b> ({@code combat/damage.yml} の {@code level-cutoff})。
 *       移設前は {@code combat/mob-overrides.yml} にあり、EliteMobsがスタンプしたモブ
 *       (=ダンジョンモブ)にしか掛からなかった。共通設定へ移したので、レベル刻印を持つ
 *       全モブに掛かる。経験値とTF追加ドロップの両方が対象。</li>
 *   <li><b>ドロップ増加ステ</b> ({@code mob_drop_bonus} の装備+perk合算)。
 *       {@code NativeSurvivalPerkListener} は {@code EntityDeathEvent#getDrops()} の中身にしか
 *       掛けられず、しかも同じ {@code MONITOR} 優先度で<b>先に</b>走るため、あとから追加される
 *       TF追加ドロップには一度も掛かっていなかった。ここで各リスナーが自前で掛ける。</li>
 * </ol>
 *
 * <p><b>バニラ本来のドロップは対象外</b>。足きりが止めるのもドロップ増加が増やすのも、TFが
 * {@code drops:} / {@code add-drops:} で足したアイテムだけ。バニラドロップまで止めると
 * モブトラップが完全に死んで「足きり」の域を超えるため(2026-07-27 設計判断)。
 *
 * <p><b>レベル刻印の無いモブは足きりの対象外</b>。{@link MobData#hasProfile()} が false のモブは
 * {@link MobData#level()} が 0 を返すので、そのまま判定すると「プレイヤーが格上」が常に成立して
 * しまう。刻印が無い = レベルを比べる根拠が無い、として素通りさせる。
 */
public final class KillRewardAdjuster {

    private static final String MOB_DROP_BONUS = StatKeys.canonical("mob_drop_bonus");

    private final CombatDamageConfig damageConfig;
    private final SymmetricCombatService combatService;
    private final PlayerStatAggregator aggregator;

    public KillRewardAdjuster(CombatDamageConfig damageConfig, SymmetricCombatService combatService,
                              PlayerStatAggregator aggregator) {
        this.damageConfig = Objects.requireNonNull(damageConfig, "damageConfig");
        this.combatService = Objects.requireNonNull(combatService, "combatService");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    /** 現在の共通設定の足きり。 */
    public MobLevelCutoff cutoff() {
        return damageConfig.levelCutoff();
    }

    /**
     * このキルではTF追加ドロップを一切付けないべきか(under-level発動、または over-level発動で
     * {@code drop-rate == -1})。
     */
    public boolean blocksItems(Player killer, LivingEntity mob) {
        MobData data = MobData.of(mob);
        if (killer == null || !data.hasProfile()) {
            return false;
        }
        return cutoff().blocksItems(combatService.combatLevelOf(killer.getUniqueId()), data.level());
    }

    /** TF追加ドロップの各エントリの {@code chance} に掛ける倍率。 */
    public double chanceMultiplier(Player killer, LivingEntity mob) {
        MobData data = MobData.of(mob);
        if (killer == null || !data.hasProfile()) {
            return 1.0;
        }
        return cutoff().dropChanceMultiplier(combatService.combatLevelOf(killer.getUniqueId()), data.level());
    }

    /**
     * 経験値に掛ける倍率。バニラの経験値オーブとTFの戦闘スキルEXPの両方でこれを使う。
     *
     * <p>スキルEXPは「止めを刺した1人」ではなく<b>ダメージ寄与のあった各プレイヤー</b>へ配られるので、
     * 呼び出し側はキル者ではなくその受取人を {@code player} に渡すこと(各自のレベルで判定される)。
     */
    public double expMultiplier(Player player, LivingEntity mob) {
        MobData data = MobData.of(mob);
        if (player == null || !data.hasProfile()) {
            return 1.0;
        }
        return cutoff().expMultiplier(combatService.combatLevelOf(player.getUniqueId()), data.level());
    }

    /**
     * ドロップ増加ステ({@code mob_drop_bonus})の合算値。0以上 +200% 以下へクランプ済み。
     *
     * <p>2026-08-13 以降、これは<b>倍率ではない</b>。使い方はドロップの形で分かれる:
     * 1個固定のドロップは {@link MobDropRoller#boostedChance}(抽選確率を上げる)、
     * ランダム個数のドロップは {@link MobDropRoller#extraCount}(個数を足す)。
     * どちらを使うかは {@link MobDropRoller#isSingleFixed} で決める。
     */
    public double dropBonus(Player killer) {
        if (killer == null) {
            return 0.0;
        }
        return MobDropRoller.clampBonus(aggregator.aggregate(killer).totalOf(MOB_DROP_BONUS));
    }
}
