package com.trinityforge.listeners;

import com.trinityforge.combat.MagicPipelineDamage;
import com.trinityforge.config.domains.MobLevelTableConfig;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.integration.ars.ArsProgressionBridge;
import com.trinityforge.pdc.MobData;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.GameMode;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 魔法の討伐EXPにも武器・弓術と同じ<b>レベル差の足きり</b>が掛かることの回帰ガード
 * (2026-08-19 W-148)。
 *
 * <p><b>なぜ要るか</b>: {@code CombatListener#onCombatKill} は
 * {@code KillRewardAdjuster#expMultiplier} を掛けているのに、こちらは掛けていなかった。
 * その結果<b>同じモブを倒しても魔法だけ満額で入る</b>という非対称が生まれ、
 * 実サーバでは「軽武器78でLv80エンダーマンを倒しても1000程度か0しか入らないのに、
 * 魔法だけは入る」という形で報告された。ここを外すとその非対称が戻る。
 *
 * <p>足きりの倍率そのものの正しさ({@code exp-threshold} からの逓減カーブ)は
 * {@code MobLevelCutoff} 側のテストが持つ。ここが固定するのは<b>掛け忘れないこと</b>だけ。
 *
 * <p><b>2026-08-22</b>: 判定に使うレベルが戦闘レベルから【そのEXPが入る職業のレベル】へ変わったので、
 * 呼ぶ先も {@code expMultiplier}(バニラEXP用・戦闘レベル基準)から
 * {@link KillRewardAdjuster#skillExpMultiplier} へ移った。<b>{@code ARS_MAGIC} を渡すこと自体が
 * 検査対象</b> ── ここで別のスキルidや戦闘レベル版を呼ぶと、武器を伸ばした人が魔法1のまま
 * 高レベル帯で魔法を育てられる穴(戦闘レベル基準のときに実在した)が戻る。
 * 基準レベルの選び分けそのものは {@code KillRewardAdjusterSkillLevelExpTest} が固定する。
 */
class ArsMagicExperienceListenerLevelCutoffTest {

    private static final double BASE_AMOUNT = 1_000.0;

    @AfterEach
    void clearMarker() {
        while (MagicPipelineDamage.isActive()) MagicPipelineDamage.clear();
    }

    @Test
    void magicKillExpIsScaledByTheLevelCutoff() {
        Fixture f = new Fixture();
        when(f.adjuster.skillExpMultiplier(f.killer, f.dead, SkillId.ARS_MAGIC)).thenReturn(0.25);

        try (MockedStatic<MobData> mobData = mockStatic(MobData.class);
             MockedStatic<ArsProgressionBridge> bridge = mockStatic(ArsProgressionBridge.class)) {
            mobData.when(() -> MobData.of(f.dead)).thenReturn(f.deadData);
            MagicPipelineDamage.mark();
            f.listener.onMagicKill(f.event);

            // 足きり 0.25 がそのまま掛かった額で付与されること(掛け忘れると 1000 のまま渡る)。
            bridge.verify(() -> ArsProgressionBridge.grantMagicExp(
                    f.plugin, f.killer, BASE_AMOUNT * 0.25));
        }
        verify(f.adjuster).skillExpMultiplier(f.killer, f.dead, SkillId.ARS_MAGIC);
    }

    @Test
    void magicKillExpIsSuppressedEntirelyWhenTheCutoffReturnsZero() {
        Fixture f = new Fixture();
        when(f.adjuster.skillExpMultiplier(f.killer, f.dead, SkillId.ARS_MAGIC)).thenReturn(0.0);

        try (MockedStatic<MobData> mobData = mockStatic(MobData.class);
             MockedStatic<ArsProgressionBridge> bridge = mockStatic(ArsProgressionBridge.class)) {
            mobData.when(() -> MobData.of(f.dead)).thenReturn(f.deadData);
            MagicPipelineDamage.mark();
            f.listener.onMagicKill(f.event);

            bridge.verifyNoInteractions();
        }
    }

    @Test
    void missingAdjusterKeepsTheOldFullGrantInsteadOfSwallowingTheExp() {
        // 配線し忘れ(null)は「足きり無効＝満額」へ倒れる。EXPが黙って消える方向には倒さない。
        Fixture f = new Fixture();
        f.listener.setKillRewardAdjuster(null);

        try (MockedStatic<MobData> mobData = mockStatic(MobData.class);
             MockedStatic<ArsProgressionBridge> bridge = mockStatic(ArsProgressionBridge.class)) {
            mobData.when(() -> MobData.of(f.dead)).thenReturn(f.deadData);
            MagicPipelineDamage.mark();
            f.listener.onMagicKill(f.event);

            bridge.verify(() -> ArsProgressionBridge.grantMagicExp(f.plugin, f.killer, BASE_AMOUNT));
        }
        verify(f.adjuster, never()).skillExpMultiplier(any(), any(), any());
    }

    /** マーカー付き魔法キル1件ぶんの最小構成。 */
    private static final class Fixture {
        final Plugin plugin = mock(Plugin.class);
        final SkillExpConfig skillExp = mock(SkillExpConfig.class);
        final MobLevelTableConfig mobLevelTable = mock(MobLevelTableConfig.class);
        final KillRewardAdjuster adjuster = mock(KillRewardAdjuster.class);
        final LivingEntity dead = mock(LivingEntity.class);
        final Player killer = mock(Player.class);
        final MobData deadData = mock(MobData.class);
        final EntityDeathEvent event = mock(EntityDeathEvent.class);
        final ArsMagicExperienceListener listener;

        Fixture() {
            UUID killerId = UUID.randomUUID();
            when(killer.getUniqueId()).thenReturn(killerId);
            when(killer.getGameMode()).thenReturn(GameMode.SURVIVAL);

            when(dead.getType()).thenReturn(EntityType.ENDERMAN);
            when(dead.getKiller()).thenReturn(killer);
            when(dead.getAttribute(any())).thenReturn(null);
            when(dead.getHealth()).thenReturn(100.0);
            when(deadData.level()).thenReturn(80);

            DamageSource source = mock(DamageSource.class);
            when(source.getCausingEntity()).thenReturn(killer);
            EntityDamageByEntityEvent last = mock(EntityDamageByEntityEvent.class, RETURNS_DEEP_STUBS);
            when(last.getDamageSource()).thenReturn(source);
            when(last.getCause()).thenReturn(EntityDamageEvent.DamageCause.MAGIC);
            when(dead.getLastDamageCause()).thenReturn(last);
            when(event.getEntity()).thenReturn(dead);

            when(skillExp.arsMagicKillExpEnabled()).thenReturn(true);
            when(skillExp.arsMagicKillExp(anyString(), anyInt(), anyDouble())).thenReturn(BASE_AMOUNT);
            when(mobLevelTable.suppressesSkillExp(eq(EntityType.ENDERMAN))).thenReturn(false);

            listener = new ArsMagicExperienceListener(plugin, skillExp,
                    mock(NativeSkillCatalog.class), mock(PlacedBlockTracker.class), mobLevelTable);
            listener.setKillRewardAdjuster(adjuster);
        }
    }
}
