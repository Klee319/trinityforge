package com.trinityforge.combat;

import com.trinityforge.pdc.PdcKeys;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;

/**
 * Carries the firing weapon (bow/crossbow/trident) with the projectile it launches so the attack
 * stats resolved at impact come from the weapon that actually fired the shot, not from whatever the
 * shooter happens to be holding when the projectile lands (High bug: switching hands after loosing an
 * arrow, or throwing a trident, mis-derived crit / penetration / attack-power).
 *
 * <p>The weapon {@link ItemStack} is serialized with {@link ItemStack#serializeAsBytes()} and stamped
 * onto the projectile's {@link PersistentDataContainer} at launch, then re-inflated at impact with
 * {@link ItemStack#deserializeBytes(byte[])} and re-derived through the normal stat layers
 * ({@code DerivedItemStats.resolve}). Re-derivation is deterministic (same rollSeed + quality), so the
 * stored bytes are just the derivation inputs, matching the "stats are never baked" PDC contract.
 *
 * <p>This class is intentionally a thin Bukkit-serialization seam: both methods require a live server
 * (the item serializer touches the registry), so they are covered by the implementation + integration
 * rather than pure unit tests. The pure derivation math it feeds is already unit-tested via
 * {@code AttackStatBridge} / {@code DerivedItemStats}.
 */
public final class ProjectileWeapon {

    private ProjectileWeapon() {
    }

    /**
     * Stamps {@code weapon} onto {@code projectile} for impact-time re-derivation. Any non-AIR firing
     * weapon is retained regardless of whether it carries item meta: even a bare-material bow/crossbow/
     * trident is a valid derivation input (material-base + item-stats are keyed off the material alone),
     * so gating on {@link ItemStack#hasItemMeta()} wrongly dropped valid weapons and let impact fall back
     * to the shooter's mainhand (High bug). A null / air weapon still stores nothing.
     *
     * <p>Serialization and the PDC write are guarded: {@link ItemStack#serializeAsBytes()} and the
     * container write touch the live registry and can throw on an exotic item / registry state. A failure
     * must never propagate onto the shoot event (it would cancel the shot), so on any {@link RuntimeException}
     * nothing is stored and the impact path falls back to its default exactly as if no weapon were retained.
     */
    public static void store(Projectile projectile, ItemStack weapon) {
        if (projectile == null || weapon == null || weapon.getType().isAir()) {
            return;
        }
        try {
            byte[] bytes = weapon.serializeAsBytes();
            projectile.getPersistentDataContainer()
                    .set(PdcKeys.PROJECTILE_WEAPON, PersistentDataType.BYTE_ARRAY, bytes);
        } catch (RuntimeException failed) {
            // Best-effort retention: leave the projectile unstamped so impact falls back gracefully.
        }
    }

    /**
     * The firing weapon previously {@link #store stored} on {@code projectile}, or empty when none was
     * stored (non-TF weapon, spawned/dispensed projectile) or the stored bytes fail to deserialize
     * (a stale format from a server/plugin change must never throw on the combat path).
     */
    public static Optional<ItemStack> read(Projectile projectile) {
        if (projectile == null) {
            return Optional.empty();
        }
        PersistentDataContainer container = projectile.getPersistentDataContainer();
        byte[] bytes = container.get(PdcKeys.PROJECTILE_WEAPON, PersistentDataType.BYTE_ARRAY);
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(ItemStack.deserializeBytes(bytes));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    /**
     * 2026-07-25バグ修正: {@code EntityShootBowEvent#getForce()}(弓の引き絞り量, 0.0〜1.0)を
     * {@code weapon} と同じ projectile PDC へ retain する。{@link #store} と同じく最善努力(発射イベントを
     * 落とさないため、失敗しても何も書き込まず {@link #readDrawForce} が既定の1.0にフォールバックする)。
     * {@code force} は既に呼び出し側(Bukkit)でクランプ済みだが、防御的に [0,1] へ再クランプする。
     */
    public static void storeDrawForce(Projectile projectile, float force) {
        if (projectile == null) {
            return;
        }
        double clamped = Math.max(0.0, Math.min(1.0, force));
        try {
            projectile.getPersistentDataContainer()
                    .set(PdcKeys.PROJECTILE_DRAW_FORCE, PersistentDataType.DOUBLE, clamped);
        } catch (RuntimeException failed) {
            // Best-effort retention: leave unstamped, readDrawForce falls back to full draw (1.0).
        }
    }

    /**
     * The draw force previously {@link #storeDrawForce stored} on {@code projectile}, or {@code 1.0}
     * (full draw / no scaling) when none was stored — covers non-bow projectiles (trident: vanilla has
     * no draw-time damage scaling for it) and any projectile that predates this stamp.
     */
    public static double readDrawForce(Projectile projectile) {
        if (projectile == null) {
            return 1.0;
        }
        Double stored = projectile.getPersistentDataContainer()
                .get(PdcKeys.PROJECTILE_DRAW_FORCE, PersistentDataType.DOUBLE);
        if (stored == null || !Double.isFinite(stored)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, stored));
    }
}
