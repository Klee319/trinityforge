package com.trinityforge.listeners;

import com.trinityforge.config.domains.CombatDamageConfig;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 日光による炎上ダメージを「被弾モブの最大HP × 割合」へ置き換える(2026-07-28)。
 *
 * <p><b>なぜ必要か</b>: バニラの炎上ダメージは1発1.0固定。一方 TF のモブ最大HPは
 * {@code combat/mob-types.yml} で Lv0 のゾンビでも 400、高レベル帯では数万に達する。その結果
 * 「朝になっても敵が炎上で死なない」(400秒以上燃え続ける)という実害が出ていた。ここでは
 * <b>日光で燃えている間の1発だけ</b>を最大HP割合へ置き換えるので、モブHPカーブを触らずに
 * 「日光で焼き切れるまでの時間」を一定(既定10%→約10秒)にできる。
 *
 * <p><b>対象を絞る理由</b>: {@code DamageCause.FIRE_TICK} は「日光炎上」と「火属性エンチャント
 * などによる着火」を区別しない。全モブを対象にすると、野外・昼間に火打石や火属性で着火した
 * だけでボスまで毎秒10%ずつ溶ける(火属性が最強の攻撃手段に化ける)。そのため
 * {@code combat/damage.yml} の {@code sunlight-burn.mobs} で「バニラで日光焼却される種別」
 * だけを既定の対象にしている(空リストにすれば全モブへ広げられる)。
 *
 * <p>置換値がバニラのダメージより小さい場合はバニラ値のままにする(下げる方向には決して働かない)。
 */
public final class SunlightBurnListener implements Listener {

    /** 空からの明るさがこの値のときだけ「日光が直接当たっている」と見なす(バニラの焼却条件相当)。 */
    private static final int FULL_SKY_LIGHT = 15;

    /** 置換値を素通しさせるために 0 にする軽減系修飾子({@link #clearReductionModifiers} 参照)。 */
    @SuppressWarnings("deprecation") // DamageModifier は非推奨だが、修飾子を潰す API は他に無い
    private static final List<EntityDamageEvent.DamageModifier> REDUCTION_MODIFIERS = List.of(
            EntityDamageEvent.DamageModifier.ARMOR,
            EntityDamageEvent.DamageModifier.RESISTANCE,
            EntityDamageEvent.DamageModifier.MAGIC,
            EntityDamageEvent.DamageModifier.ABSORPTION);

    private final CombatDamageConfig damageConfig;

    public SunlightBurnListener(CombatDamageConfig damageConfig) {
        this.damageConfig = Objects.requireNonNull(damageConfig, "damageConfig");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FIRE_TICK) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity victim) || victim instanceof Player) {
            return;
        }
        if (!damageConfig.sunlightBurnEnabled()) {
            return;
        }
        double percent = damageConfig.sunlightBurnDamagePercentOfMaxHealth();
        if (!(percent > 0.0) || !Double.isFinite(percent)) {
            return;
        }
        if (!isTargeted(victim.getType(), damageConfig.sunlightBurnMobs())) {
            return;
        }
        if (!isInDaylight(victim)) {
            return;
        }
        AttributeInstance maxHealth = victim.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        double replacement = maxHealth.getValue() * percent;
        if (!Double.isFinite(replacement) || replacement <= event.getDamage()) {
            // バニラの方が大きい(小型モブ等)ならバニラのまま — この置換は上げる方向にだけ働く。
            return;
        }
        clearReductionModifiers(event);
        event.setDamage(replacement);
    }

    /**
     * 軽減系の {@link EntityDamageEvent.DamageModifier} を 0 にしてから置換値を書く。
     *
     * <p><b>2026-08-17 修正 (ユーザー報告「日光のダメージがカスでいつまでも死なないスケルトンがいた」)</b>:
     * {@code setDamage(double)} が書き換えるのは {@code BASE} だけで、最終ダメージは
     * そのあとに掛かる修飾子(防具・耐性・<b>火炎耐性/ダメージ軽減エンチャント = MAGIC</b>)で削られる。
     * EliteMobs のスケルトンはエンチャント付き防具を着ていることがあり、火炎耐性が乗っていると
     * 「最大HPの10%」と書いたつもりの1発がほぼ 0 まで削られて<b>永久に焼け死ななかった</b>。
     *
     * <p>ここで狙っているのは「日光で焼き切れるまでの時間を一定にする」ことなので、
     * 軽減を通さずそのまま入れるのが正しい(この置換は元から上げる方向にしか働かない)。
     * 適用不能な修飾子に触ると {@link UnsupportedOperationException} が飛ぶため必ず
     * {@code isApplicable} で確認する。
     */
    @SuppressWarnings("deprecation") // 上の REDUCTION_MODIFIERS と同じ理由
    private static void clearReductionModifiers(EntityDamageEvent event) {
        for (EntityDamageEvent.DamageModifier modifier : REDUCTION_MODIFIERS) {
            if (event.isApplicable(modifier)) {
                event.setDamage(modifier, 0.0);
            }
        }
    }

    /**
     * {@code mobs} が空なら全モブ対象。未知の EntityType 名は黙って無視する
     * (config読込側で警告する類のものだが、ここで例外にすると戦闘イベントが落ちるため)。
     */
    static boolean isTargeted(EntityType type, List<String> mobs) {
        if (mobs == null || mobs.isEmpty()) {
            return true;
        }
        Set<EntityType> targets = EnumSet.noneOf(EntityType.class);
        for (String raw : mobs) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                targets.add(EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // 未知のEntityType名: このエントリだけスキップ
            }
        }
        return targets.isEmpty() || targets.contains(type);
    }

    /**
     * 「日光で焼かれている」と見なせる状況か。バニラの焼却条件(通常世界 / 昼 / 晴れ / 空が見えている)
     * と同じ条件を使うので、夜間・屋内・雨天・ネザー/エンドで燃えている場合は置換されない。
     */
    private static boolean isInDaylight(LivingEntity entity) {
        World world = entity.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return false;
        }
        if (!world.isDayTime() || world.hasStorm() || world.isThundering()) {
            return false;
        }
        Block block = entity.getLocation().getBlock();
        return block.getLightFromSky() >= FULL_SKY_LIGHT;
    }
}
