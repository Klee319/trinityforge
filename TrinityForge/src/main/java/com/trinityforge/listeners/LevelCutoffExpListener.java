package com.trinityforge.listeners;

import com.trinityforge.pdc.MobData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.Objects;

/**
 * レベル差による足きりを<b>バニラの経験値オーブ</b>へ適用する(2026-08-09、
 * {@code combat/damage.yml} の {@code level-cutoff})。
 *
 * <p><b>なぜ独立したリスナーなのか。</b> 足きりは 2026-07-27 に
 * {@link MobOverrideExpListener} の中で実装されたが、設定が {@code combat/mob-overrides.yml} に
 * あった都合上「EliteMobsが {@code MOB_PROFILE_ID} をスタンプしたモブ」= ダンジョンモブにしか
 * 掛からなかった。フィールドの野良モブはどれだけレベル差があっても素通りしていた。
 * 設定を共通の {@code combat/damage.yml} へ移したので、適用側も profileId に依存しない
 * このリスナーへ移している。
 *
 * <p><b>優先度と登録順。</b> {@link EventPriority#MONITOR} で、EXPを書き込む2つのリスナー
 * ({@code MobLevelTableListener} の {@code vanilla-exp} = {@code HIGH}、
 * {@link MobOverrideExpListener} のモブ別ランプ = {@code MONITOR}) より<b>後に登録する</b>こと。
 * 同一優先度は登録順に走るので、この順序だけが「確定したEXPに倍率を掛ける」ことを保証する。
 *
 * <p><b>プレイヤーキル限定。</b> バニラ自体が非プレイヤーキルでは {@code droppedExp == 0} を返すので
 * 実害は無いが、他の討伐系リスナーと判定を揃えて早期returnする。
 *
 * <p><b>レベル刻印の無いモブは対象外。</b> {@link MobData#hasProfile()} が false のモブは
 * {@link MobData#level()} が 0 を返し、そのまま比べると「プレイヤーが格上」が常に成立してしまう
 * (詳細は {@link KillRewardAdjuster} のクラスjavadoc)。
 *
 * <p>TFの戦闘スキルEXP側の足きりはこのリスナーではなく {@code CombatListener#onCombatKill} が
 * 掛ける。あちらは「止めを刺した1人」ではなくダメージ寄与のあった各プレイヤーへ配る仕組みなので、
 * 受取人ごとに個別のレベルで判定する必要があるため。
 */
public final class LevelCutoffExpListener implements Listener {

    private final KillRewardAdjuster adjuster;

    public LevelCutoffExpListener(KillRewardAdjuster adjuster) {
        this.adjuster = Objects.requireNonNull(adjuster, "adjuster");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null) {
            return;
        }
        int exp = event.getDroppedExp();
        if (exp <= 0) {
            return;
        }
        double multiplier = adjuster.expMultiplier(killer, entity);
        if (multiplier == 1.0) {
            // 未発動、または exp-rate が無干渉(1.0)。「未設定 = 触らない」という既存の契約を守る。
            return;
        }
        event.setDroppedExp((int) Math.max(0, Math.round(exp * multiplier)));
    }
}
