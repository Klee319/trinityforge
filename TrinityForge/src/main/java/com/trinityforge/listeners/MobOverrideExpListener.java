package com.trinityforge.listeners;

import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.mobs.MobLevelCutoff;
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
 * <p><b>レベル差による足きり(2026-07-27):</b> {@link MobOverridesConfig#levelCutoffFor} で解決した
 * {@link MobLevelCutoff} の {@link MobLevelCutoff#expMultiplier} を、ランプ済みEXP(未設定なら
 * {@code event.getDroppedExp()} そのもの)に乗算してから {@code setDroppedExp} する。ランプも足きりも
 * どちらも未設定/無効なときだけ、従来どおり {@code setDroppedExp} を一切呼ばない(「未設定 = 触らない」
 * という既存の契約を壊さないため — 足きりの倍率が実質1.0のときも同様に「触らない」を優先する)。
 * 適用順は「ランプ/基準EXP → 足きり倍率 → {@link com.trinityforge.progression.LocationExpDiminishing}」。
 */
public final class MobOverrideExpListener implements Listener {

    private final MobOverridesConfig mobOverrides;
    private final SymmetricCombatService combatService;

    public MobOverrideExpListener(MobOverridesConfig mobOverrides, SymmetricCombatService combatService) {
        this.mobOverrides = Objects.requireNonNull(mobOverrides, "mobOverrides");
        this.combatService = Objects.requireNonNull(combatService, "combatService");
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
        MobLevelCutoff cutoff = mobOverrides.levelCutoffFor(worldName, profileId.get());
        int playerLevel = combatService.combatLevelOf(entity.getKiller().getUniqueId());
        double multiplier = cutoff.expMultiplier(playerLevel, mobLevel);
        if (ramp.isEmpty() && multiplier == 1.0) {
            // 2026-07-27: ランプ未設定 かつ 足きりも実質無効(未発動、または発動していてもexp-rate未設定
            // で倍率1.0)なら、従来どおり一切触らない — このキルのEXPが何であれ(EliteMobsフォークが
            // 既に計算した値含め)そのまま生かす。
            return;
        }
        int baseExp = ramp.isPresent() ? ramp.getAsInt() : event.getDroppedExp();
        int finalExp = (int) Math.max(0, Math.round(baseExp * multiplier));
        // TT/放置対策: 同一地点で稼ぎ続けたぶんだけ経験値オーブを減らす(ダンジョンは既定で対象外)。
        event.setDroppedExp(com.trinityforge.progression.LocationExpDiminishing.applyIfRunning(finalExp, entity));
    }
}
