package com.trinityforge.combat;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;

/**
 * ValhallaMMO {@code EntityFlash}-equivalent VFX for TrinityForge crits: white {@link Particle#FLASH}
 * at the victim's mid-body.
 *
 * <p>Paper 1.21.11 では {@code Particle.FLASH} が {@link Color} データを必須とする
 * ({@code getDataType() == Color.class})。データ無しの3引数 {@code spawnParticle} を呼ぶと
 * CraftParticle が「missing required data class org.bukkit.Color」で例外を投げ、これが
 * {@code EntityDamageByEntityEvent} ハンドラを巻き込んでダメージ処理全体を中断させていた。
 * パーティクルのデータ要件はバージョンで変わりうるため {@code getDataType()} で分岐し、さらに
 * <b>cosmetic な演出は戦闘イベントを決して落とさない</b>よう {@code try/catch} で保護する。
 */
public final class CritFlash {

    private CritFlash() {
    }

    /** Spawns the crit flash on {@code victim}; no-op when world is unavailable or VFX fails. */
    public static void play(LivingEntity victim) {
        if (victim == null || victim.getWorld() == null) {
            return;
        }
        Location location = victim.getEyeLocation().add(0, -victim.getHeight() / 2.0, 0);
        try {
            // count=0 is the MC idiom for a single FLASH at the exact point (matches Valhalla EntityFlash).
            Class<?> dataType = Particle.FLASH.getDataType();
            if (dataType == Color.class) {
                victim.getWorld().spawnParticle(Particle.FLASH, location, 0, Color.WHITE);
            } else if (dataType == Void.class) {
                victim.getWorld().spawnParticle(Particle.FLASH, location, 0);
            }
            // それ以外(未知のデータ要件)は演出をスキップ — 戦闘を止めないことを最優先する。
        } catch (RuntimeException ignored) {
            // VFX 失敗は無視して戦闘を継続する(演出はあくまで cosmetic)。
        }
    }
}
