package com.trinityforge.listeners;

import com.trinityforge.TrinityForge;
import com.trinityforge.TrinityForgeSingletonTestSupport;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 防具の被弾EXPにレベル差の足きりが掛かることを固定する
 * (2026-08-22 ユーザー指示「防具の被弾EXPも今回のlevel差調整の該当にする。(被弾した敵のlevelと比較)」)。
 *
 * <p><b>これが無いと何が起きるか。</b> 撃破EXP側(軽武器/重武器/弓術/魔法)は
 * {@link KillRewardAdjuster} の足きりを通しているのに、防具の被弾EXPだけが素通りしていた。
 * つまり<b>格上のモブに殴られるだけなら満額</b>で、「低レベルのまま高レベル帯へ連れて行ってもらう」
 * 抑制が防具の2職業でだけ効いていなかった。
 *
 * <p>ここで検査するのは<b>配線と掛け算</b> ── 足きりの倍率そのものの計算(どのレベルと比べるか、
 * 何差から何差で0になるか)は {@link KillRewardAdjusterSkillLevelExpTest} が実際の数で固定している。
 * 掛け先を消す/{@code setKillRewardAdjuster} の呼び出しを外すと下の2本が落ちる。
 *
 * <p>組み立ては {@link NativeSkillExperienceListenerArmorExpTest} と同じ(純 Mockito、MockBukkit なし)。
 * 素の一撃で 50.0 入る条件に揃えてあるので、期待値は「50.0 × 足きり」で読める。
 */
class NativeSkillExperienceListenerArmorLevelCutoffTest {

    /** 素の一撃で入る量(10 exp/damage/piece × ダメージ5 × 1部位)。 */
    private static final double FULL_HIT_EXP = 50.0;

    private record Wired(NativeSkillExperienceListener listener, NativeExperienceDispatcher dispatcher) {
    }

    @BeforeEach
    void stubTrinityForgeSingleton() {
        SkillExpConfig skillExp = mock(SkillExpConfig.class);
        when(skillExp.dungeonOnlyExp()).thenReturn(false);
        ConfigManager config = mock(ConfigManager.class);
        when(config.skillExp()).thenReturn(skillExp);
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.config()).thenReturn(config);
        TrinityForgeSingletonTestSupport.set(tf);
    }

    @AfterEach
    void clearTrinityForgeSingleton() {
        TrinityForgeSingletonTestSupport.clear();
    }

    private Wired newListener(KillRewardAdjuster adjuster) {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        PlacedBlockTracker tracker = mock(PlacedBlockTracker.class);

        Map<String, Double> rates = Map.of(
                "armor.exp_per_damage_piece", 10.0,
                "armor.exp_armor_point_multiplier", 0.05,
                "armor.pvp_multiplier", 0.1,
                "armor.pvp_multiplier_exponent", 2.0,
                "armor.exp_damage_piece_min_damage", 1.0,
                "armor.exp_damage_piece_cooldown_seconds", 10.0);
        Map<String, Double> actionExp = Map.of("entity_exp_multipliers.ZOMBIE", 1.0);
        when(catalog.get(SkillId.HEAVY_ARMOR)).thenReturn(new SkillCatalogEntry(
                SkillId.HEAVY_ARMOR, 100, "1", level -> 1L, actionExp, rates));

        Object plugin = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {org.bukkit.plugin.Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "TrinityForge";
                    case "namespace" -> "trinityforge";
                    case "toString" -> "FakePlugin";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        NativeSkillExperienceListener listener = new NativeSkillExperienceListener(
                (org.bukkit.plugin.Plugin) plugin, dispatcher, catalog, tracker, null, null, null, null);
        listener.setKillRewardAdjuster(adjuster);
        return new Wired(listener, dispatcher);
    }

    private Player heavyArmorPlayer() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        when(player.getHealth()).thenReturn(20.0);
        PlayerInventory inv = mock(PlayerInventory.class);
        org.bukkit.inventory.ItemStack chestplate = mock(org.bukkit.inventory.ItemStack.class);
        when(chestplate.getType()).thenReturn(org.bukkit.Material.DIAMOND_CHESTPLATE);
        when(inv.getArmorContents()).thenReturn(new org.bukkit.inventory.ItemStack[] {
                null, null, null, chestplate});
        when(player.getInventory()).thenReturn(inv);
        return player;
    }

    private LivingEntity zombie() {
        LivingEntity attacker = mock(LivingEntity.class);
        when(attacker.getUniqueId()).thenReturn(UUID.randomUUID());
        when(attacker.getType()).thenReturn(EntityType.ZOMBIE);
        return attacker;
    }

    private EntityDamageByEntityEvent damageEvent(Player victim, LivingEntity damager) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamager()).thenReturn(damager);
        when(event.getDamage()).thenReturn(5.0);
        when(event.getFinalDamage()).thenReturn(5.0);
        return event;
    }

    @Test
    @DisplayName("足きりの倍率が防具の被弾EXPに掛かる(比較相手は殴ってきた敵)")
    void armorHitExpIsScaledByTheLevelCutoff() {
        KillRewardAdjuster adjuster = mock(KillRewardAdjuster.class);
        LivingEntity attacker = zombie();
        Player victim = heavyArmorPlayer();
        when(adjuster.skillExpLevelCutoff(victim, attacker, SkillId.HEAVY_ARMOR)).thenReturn(0.5);
        Wired wired = newListener(adjuster);

        wired.listener().onArmorDamage(damageEvent(victim, attacker));

        verify(wired.dispatcher()).grant(victim.getUniqueId(), SkillId.HEAVY_ARMOR, FULL_HIT_EXP * 0.5);
    }

    @Test
    @DisplayName("足きりが0(帯を超えた格上)なら防具EXPは一切入らない")
    void fullyCutOffHitGrantsNothing() {
        KillRewardAdjuster adjuster = mock(KillRewardAdjuster.class);
        LivingEntity attacker = zombie();
        Player victim = heavyArmorPlayer();
        when(adjuster.skillExpLevelCutoff(victim, attacker, SkillId.HEAVY_ARMOR)).thenReturn(0.0);
        Wired wired = newListener(adjuster);

        wired.listener().onArmorDamage(damageEvent(victim, attacker));

        verify(wired.dispatcher(), never()).grant(any(), eq(SkillId.HEAVY_ARMOR), anyDouble());
    }

    @Test
    @DisplayName("足きり未注入(テスト/起動直後)でも落ちず、従来どおり満額入る")
    void missingAdjusterKeepsTheLegacyBehaviour() {
        LivingEntity attacker = zombie();
        Player victim = heavyArmorPlayer();
        Wired wired = newListener(null);

        wired.listener().onArmorDamage(damageEvent(victim, attacker));

        verify(wired.dispatcher()).grant(victim.getUniqueId(), SkillId.HEAVY_ARMOR, FULL_HIT_EXP);
    }
}
