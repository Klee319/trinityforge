package com.trinityforge.listeners;

import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.pdc.MobData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Applies {@code combat/mob-overrides.yml}'s per-(ダンジョンワールド × EliteMobsモブid) vanilla EXP ramp
 * (2026-07-26 「モブごとにレベルに応じた経験値の式を設定したい」要望) at mob death.
 *
 * <p>Gated exactly like {@link MobOverrideDropListener}: only entities the EliteMobs fork stamped with
 * {@code MOB_PROFILE_ID} are eligible, and the EXP is evaluated from the SAME stamped combat level
 * ({@link MobData#level()}) the rest of the combat pipeline uses — so a {@code level: dynamic} dungeon mob
 * pays out EXP matching the level the player picked on entry rather than a value frozen at import time.
 *
 * <p><b>Priority: {@link EventPriority#MONITOR}</b>, i.e. strictly after {@code MobLevelTableListener}
 * ({@code HIGH}), which is what makes this the more specific of the two EXP sources: a mob covered by both
 * {@code mob-level-table.yml}'s band-wide {@code vanilla-exp} and its own {@code mob-overrides.yml} ramp
 * ends up with the per-mob ramp's value. A mob with no ramp configured in any scope is left completely
 * untouched (the band value, or vanilla's own EXP, survives) — "not configured" is never read as "0 EXP".
 *
 * <p>Writing at {@code MONITOR} deviates from that priority's read-only convention for the same reason
 * {@link MobOverrideDropListener} does (EliteMobs clears the drop list from inside its own
 * {@code NORMAL}-priority handler); keeping both override effects on one priority also keeps their
 * relative ordering against every other plugin identical.
 *
 * <p><b>Player-kill gate (2026-07-26 H2 レビュー指摘):</b> {@link EntityDeathEvent#getEntity()}{@code
 * .getKiller()} must be non-null (a {@code Player}) or this listener does nothing. Vanilla itself only
 * ever drops EXP for a player kill (non-player kills — mob infighting, lava, fall damage, etc. — get
 * {@code droppedExp == 0} from the base game); without this gate the MONITOR-priority
 * {@code setDroppedExp} here unconditionally overwrote that 0 with the full ramp value regardless of who
 * (or what) landed the kill, turning any AFK/automated non-player kill into a free EXP farm.
 *
 * <p><b>レベル差による足きりはここには無い(2026-08-09):</b> 2026-07-27 に一度このクラスへ入れたが、
 * {@code combat/mob-overrides.yml} 由来の設定だったためEliteMobsスタンプ済みモブ(=ダンジョンモブ)にしか
 * 掛からなかった。共通設定({@code combat/damage.yml} の {@code level-cutoff})へ移し、全モブに効く
 * {@link LevelCutoffExpListener} が担当する。適用順は「ランプ →
 * {@link com.trinityforge.progression.LocationExpDiminishing} → 足きり倍率」で、後段の足きりは
 * このリスナーより後に登録された別リスナーとして走る(どちらも乗算なので順序で結果は変わらない)。
 */
public final class MobOverrideExpListener implements Listener {

    private final MobOverridesConfig mobOverrides;

    public MobOverrideExpListener(MobOverridesConfig mobOverrides) {
        this.mobOverrides = Objects.requireNonNull(mobOverrides, "mobOverrides");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.getKiller() == null) {
            // 2026-07-26 H2: プレイヤーがキルした場合のみランプEXPを適用する(class javadoc「Player-kill
            // gate」参照)。モブ同士の相打ち・溶岩・落下死等はここで何もせず、バニラの既定挙動
            // (droppedExp == 0)をそのまま通す。
            return;
        }
        MobData mobData = MobData.of(entity);
        Optional<String> profileId = mobData.profileId();
        if (profileId.isEmpty()) {
            return;
        }
        String worldName = entity.getWorld().getName();
        int mobLevel = mobData.level();
        OptionalInt ramp = mobOverrides.vanillaExpFor(worldName, profileId.get(), mobLevel);
        if (ramp.isEmpty()) {
            // ランプ未設定なら一切触らない — このキルのEXPが何であれ(EliteMobsフォークが既に計算した
            // 値含め)そのまま生かす。2026-08-09: レベル差の足きりはこのクラスから外し、全モブに効く
            // LevelCutoffExpListener(このリスナーより後に登録)へ移した。
            return;
        }
        // TT/放置対策: 同一地点で稼ぎ続けたぶんだけ経験値オーブを減らす(ダンジョンは既定で対象外)。
        event.setDroppedExp(com.trinityforge.progression.LocationExpDiminishing
                .applyIfRunning(Math.max(0, ramp.getAsInt()), entity));
    }
}
