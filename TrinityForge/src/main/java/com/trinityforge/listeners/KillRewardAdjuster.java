package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.mobs.DungeonLevelReward;
import com.trinityforge.mobs.MobDropRoller;
import com.trinityforge.mobs.MobLevelCutoff;
import com.trinityforge.pdc.MobData;
import com.trinityforge.stats.StatKeys;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * 「モブを倒したときの報酬」へ共通で掛かる調整を1か所へまとめたもの(2026-08-09、2026-08-18 に3つ目を追加)。
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
 *   <li><b>ダンジョンの挑戦レベルに応じた報酬の上乗せ</b> ({@code combat/damage.yml} の
 *       {@code dungeon-level-reward}、2026-08-18 W-80)。EMダイナミックダンジョンで選んだ挑戦レベルは
 *       敵の強さにしか効いておらず報酬には無関係だったので、一番低いレベルを選んで回すのが常に最適だった。
 *       <b>ダンジョンワールドで倒したモブにだけ</b>、そのモブのレベル(= 選んだレベル)に比例して
 *       TF追加ドロップ確率と撃破EXPを増やす。プレイヤーとのレベル差では判定しない ── レベル差で書くと
 *       オーバーワールドの高レベルモブにも効いてしまい、1.の {@code under-level}(W-73)と衝突するため
 *       (2026-08-18 に一度その実装で差し戻された)。</li>
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
    /** そのワールドが EliteMobs のダンジョンインスタンスか。ダンジョン限定の上乗せの唯一のゲート。 */
    private final Predicate<World> inDungeon;

    /**
     * ダンジョン判定を持たない版。上乗せ({@code dungeon-level-reward})は<b>常に効かない</b>ので、
     * 足きりとドロップ増加ステだけを見たいテストのための入口。実運用の配線は必ず4引数のほうを使う。
     */
    public KillRewardAdjuster(CombatDamageConfig damageConfig, SymmetricCombatService combatService,
                              PlayerStatAggregator aggregator) {
        this(damageConfig, combatService, aggregator, world -> false);
    }

    public KillRewardAdjuster(CombatDamageConfig damageConfig, SymmetricCombatService combatService,
                              PlayerStatAggregator aggregator, Predicate<World> inDungeon) {
        this.damageConfig = Objects.requireNonNull(damageConfig, "damageConfig");
        this.combatService = Objects.requireNonNull(combatService, "combatService");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.inDungeon = Objects.requireNonNull(inDungeon, "inDungeon");
    }

    /** 現在の共通設定の足きり。 */
    public MobLevelCutoff cutoff() {
        return damageConfig.levelCutoff();
    }

    /** 現在の共通設定の「ダンジョンの挑戦レベルに応じた報酬の上乗せ」(2026-08-18 W-80)。 */
    public DungeonLevelReward dungeonLevelReward() {
        return damageConfig.dungeonLevelReward();
    }

    /**
     * このモブに掛かるダンジョン上乗せの倍率。{@code drops} が true ならTF追加ドロップ側、
     * false なら経験値側。ダンジョンワールド以外では必ず {@code 1.0}。
     *
     * <p><b>プレイヤーのレベルは一切見ない。</b> 見るのは「倒したモブのレベル」だけで、それが
     * EMダイナミックダンジョンで選んだ挑戦レベルそのものになる。レベル差で書くと
     * オーバーワールドの高レベルモブにも効いてしまい {@code level-cutoff.under-level} と衝突する
     * (2026-08-18 差し戻しの理由)。
     */
    private double dungeonBonus(LivingEntity mob, boolean drops) {
        if (mob == null) {
            return 1.0;
        }
        MobData data = MobData.of(mob);
        if (!data.hasProfile() || !inDungeon.test(mob.getWorld())) {
            return 1.0;
        }
        DungeonLevelReward reward = dungeonLevelReward();
        return drops ? reward.dropMultiplierAt(data.level()) : reward.expMultiplierAt(data.level());
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

    /**
     * TF追加ドロップの各エントリの {@code chance} に掛ける倍率。
     *
     * <p>足きり(縮小)を掛けた<b>あと</b>にダンジョン上乗せ(拡大)を掛ける。足きりが完全遮断で 0 を
     * 返した場合は上乗せしても 0 のままなので、連れて行かれた低レベルが上乗せで抜け穴を作ることはない。
     */
    public double chanceMultiplier(Player killer, LivingEntity mob) {
        MobData data = MobData.of(mob);
        if (killer == null || !data.hasProfile()) {
            return 1.0;
        }
        double rate = cutoff().dropChanceMultiplier(combatService.combatLevelOf(killer.getUniqueId()), data.level());
        return rate * dungeonBonus(mob, true);
    }

    /**
     * <b>バニラの経験値オーブ</b>に掛ける倍率。基準は<b>戦闘レベル</b>。
     *
     * <p>バニラEXPには帰属する職業が無いので、ここだけは戦闘レベルで判定するしかない。
     * 職業EXPは {@link #skillExpMultiplier} を使うこと(2026-08-22 に分離)。閾値と逓減は
     * どちらも同じ({@code exp-threshold} / {@code exp-decay-per-level})で、<b>違うのは
     * 比較に使うレベルだけ</b>。
     */
    public double expMultiplier(Player player, LivingEntity mob) {
        return expMultiplierAt(player, mob, player == null ? 0
                : combatService.combatLevelOf(player.getUniqueId()));
    }

    /**
     * <b>職業(スキル)EXP</b>に掛ける倍率。基準は<b>そのEXPが入る職業のレベル</b>
     * (2026-08-22 ユーザー指示)。
     *
     * <p>以前は戦闘レベルで判定していたが、戦闘レベルは全スキルを pillar 写像で 1 つに畳んだ値で、
     * <b>畳んだ結果と EXP の帰属先が別物</b>だった ── 軽武器 100 の純特化プレイヤーは戦闘レベルが
     * 67 (top1 の divisor が 1.5) にしかならないので、軽武器スキルがちょうど 100 でも
     * Lv100 モブとのレベル差が 33 と判定されて軽武器EXPが 0.68 倍まで削られていた。
     * 逆向きの穴もあり、伸びている柱に引っ張られて<b>遅れている職業ほど足きりが甘くなる</b>
     * (軽武器80・魔法1の人が高レベルダンジョンで魔法を振ると戦闘Lv53 で判定される)。
     * 本来の狙い「低レベルのままハメ殺しで高レベルのモブを狩るのを抑制する」は、職業ごとに見た方が
     * 直感にも狙いにも合う。
     *
     * <p>スキルEXPは「止めを刺した1人」ではなく<b>ダメージ寄与のあった各プレイヤー</b>へ配られるので、
     * 呼び出し側はキル者ではなくその受取人を {@code player} に渡すこと(各自のレベルで判定される)。
     *
     * @param skillId {@link com.trinityforge.progression.core.SkillId} の定数。{@code null}/空なら
     *                レベル 0 扱い(＝未習得の職業を高レベル帯で一気に育てるのは抑制される側)。
     */
    public double skillExpMultiplier(Player player, LivingEntity mob, String skillId) {
        return expMultiplierAt(player, mob, player == null ? 0
                : combatService.skillLevelOf(player.getUniqueId(), skillId));
    }

    /** 経験値側の足きり本体。基準レベルだけを呼び出し側が決める。 */
    private double expMultiplierAt(Player player, LivingEntity mob, int playerLevel) {
        MobData data = MobData.of(mob);
        if (player == null || !data.hasProfile()) {
            return 1.0;
        }
        return cutoff().expMultiplier(playerLevel, data.level()) * dungeonBonus(mob, false);
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
