package com.trinityforge.combat;

import com.trinityforge.config.PotionEffectTypes;
import com.trinityforge.pdc.MobData;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@link MobAbility} を実際に撃つ側（2026-07-31）。
 *
 * <p><b>ダメージの流し方</b>: 素の {@code target.damage(n, mob)} は<b>使わない</b>。それだと
 * {@code CombatListener} のモブ→プレイヤー近接経路が走り、技の数値ではなく<b>刻印済みの通常攻撃の数値</b>で
 * 上書きされる（{@link MobAbilityDamage} の javadoc に書かれている既知の罠そのもの）。
 * ここでは先に {@link SymmetricCombatService} で技の最終ダメージを出し、
 * {@link MobAbilityDamage#mark()} で「この一撃はこちらが計算済み」と宣言してから適用する。
 *
 * <p>Bukkit 由来の例外（未知の {@code EntityType}、ワールド越え、消滅済みエンティティ）は
 * 1件ずつ握って読み飛ばす。周期タスクから呼ばれるので、1体の失敗で全モブの特殊攻撃が止まると困る。
 */
public final class MobAbilityExecutor {

    /** 技名の告知が届く距離。半径 32 は「同じ部屋にいる人には見える」程度の目安。 */
    private static final double ANNOUNCE_RADIUS = 32.0;

    private final Plugin plugin;
    private final SymmetricCombatService combat;

    public MobAbilityExecutor(Plugin plugin, SymmetricCombatService combat) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combat = Objects.requireNonNull(combat, "combat");
    }

    /**
     * 技を1回撃つ。
     *
     * @return 実際に発動したら true（false のときはクールダウンを消費させない）
     */
    public boolean execute(LivingEntity mob, Player target, MobAbility ability) {
        if (mob == null || !mob.isValid() || target == null || !target.isValid()) {
            return false;
        }
        if (mob.getWorld() != target.getWorld()) {
            return false;
        }
        try {
            announce(mob, ability);
            playEffects(mob.getLocation(), ability);
            return switch (ability.type()) {
                case GROUND_SLAM -> groundSlam(mob, ability);
                case PROJECTILE_VOLLEY -> projectileVolley(mob, target, ability);
                case CHARGE -> charge(mob, target, ability);
                case AURA -> aura(mob, ability);
                case TELEPORT_STRIKE -> teleportStrike(mob, target, ability);
                case BEAM -> beam(mob, target, ability);
                case SUMMON -> summon(mob, ability);
            };
        } catch (RuntimeException ex) {
            // 設定ミス(未知のEntityType等)を毎tick叫ばせない。1件読み飛ばして次のモブへ。
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 攻撃の型
    // ------------------------------------------------------------------

    private boolean groundSlam(LivingEntity mob, MobAbility ability) {
        for (Player victim : playersInRadius(mob, ability.radius())) {
            applyHit(mob, victim, ability);
            pushAway(mob.getLocation(), victim, ability.knockback());
        }
        // 誰にも当たらなくても「発動した」扱いにしてクールダウンを消費させる。空振りを許さないと
        // 「離れている間は技が溜まったまま出てこず、近づいた瞬間に全部飛んでくる」待ち伏せになる。
        return true;
    }

    private boolean projectileVolley(LivingEntity mob, Player target, MobAbility ability) {
        EntityType type = entityType(ability.projectile());
        if (type == null || ability.count() <= 0) {
            return false;
        }
        Vector base = target.getLocation().toVector().subtract(mob.getEyeLocation().toVector());
        if (base.lengthSquared() < 1.0e-6) {
            return false;
        }
        base = base.normalize();
        int count = ability.count();
        // count=1 のときは真正面へ1発。2発以上のときだけ扇に開く。
        double step = count <= 1 ? 0.0 : ability.spreadDegrees() / (count - 1);
        double start = count <= 1 ? 0.0 : -ability.spreadDegrees() / 2.0;
        for (int i = 0; i < count; i++) {
            Vector direction = rotateAroundY(base, Math.toRadians(start + step * i));
            Entity spawned = mob.getWorld().spawnEntity(mob.getEyeLocation().add(direction.clone().multiply(0.6)), type);
            if (spawned instanceof Projectile projectile) {
                projectile.setShooter(mob);
                projectile.setVelocity(direction.multiply(1.6));
            } else {
                spawned.setVelocity(direction.multiply(1.6));
            }
        }
        return true;
    }

    private boolean charge(LivingEntity mob, Player target, MobAbility ability) {
        Vector toTarget = target.getLocation().toVector().subtract(mob.getLocation().toVector());
        if (toTarget.lengthSquared() < 1.0e-6) {
            return false;
        }
        // 上向き成分を少し足すのは「地面に引っかかって前に出ない」のを避けるため。
        Vector velocity = toTarget.normalize().multiply(1.4).setY(0.35);
        mob.setVelocity(velocity);
        // 突進の当たり判定は着地後。突進中に判定すると発動と同時に当たるだけで「避ける」余地が無い。
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!mob.isValid()) {
                    return;
                }
                playEffects(mob.getLocation(), ability);
                for (Player victim : playersInRadius(mob, Math.max(1.5, ability.radius()))) {
                    applyHit(mob, victim, ability);
                    pushAway(mob.getLocation(), victim, ability.knockback());
                }
            }
        }.runTaskLater(plugin, 12L);
        return true;
    }

    private boolean aura(LivingEntity mob, MobAbility ability) {
        int totalTicks = ability.durationTicks();
        if (totalTicks <= 0) {
            return groundSlam(mob, ability);
        }
        // 20 tick ごとに1回刻む。ダメージ倍率は「1秒ぶん」として扱うので、
        // duration-seconds を伸ばすほど総ダメージが増える(＝立ち続けるほど痛い)。
        new BukkitRunnable() {
            private int elapsed = 0;

            @Override
            public void run() {
                if (!mob.isValid() || elapsed >= totalTicks) {
                    cancel();
                    return;
                }
                elapsed += 20;
                playEffects(mob.getLocation(), ability);
                for (Player victim : playersInRadius(mob, ability.radius())) {
                    applyHit(mob, victim, ability);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
        return true;
    }

    private boolean teleportStrike(LivingEntity mob, Player target, MobAbility ability) {
        Location behind = target.getLocation().clone()
                .subtract(target.getLocation().getDirection().setY(0).normalize().multiply(1.5));
        behind.setY(target.getLocation().getY());
        // 転移先が壁の中でも teleport は成功してしまうので、足元が固いかだけ見る。
        if (!behind.getBlock().isPassable()) {
            behind = target.getLocation();
        }
        playEffects(mob.getLocation(), ability);
        mob.teleport(behind);
        playEffects(behind, ability);
        applyHit(mob, target, ability);
        pushAway(mob.getLocation(), target, ability.knockback());
        return true;
    }

    private boolean beam(LivingEntity mob, Player target, MobAbility ability) {
        Vector direction = target.getEyeLocation().toVector().subtract(mob.getEyeLocation().toVector());
        if (direction.lengthSquared() < 1.0e-6) {
            return false;
        }
        direction = direction.normalize();
        // count は「刻み数」。刻みごとに半径 radius の球で判定するので、
        // count x 1m の直線 x 太さ radius のカプセルになる。
        int steps = Math.max(1, ability.count());
        double thickness = Math.max(0.5, ability.radius());
        List<Player> alreadyHit = new ArrayList<>();
        Location cursor = mob.getEyeLocation();
        for (int i = 0; i < steps; i++) {
            cursor = cursor.add(direction);
            if (!cursor.getBlock().isPassable()) {
                break; // 壁で止まる(遮蔽が意味を持つように)
            }
            playEffects(cursor, ability);
            for (Entity entity : cursor.getWorld().getNearbyEntities(cursor, thickness, thickness, thickness)) {
                if (entity instanceof Player victim && victim.isValid() && !alreadyHit.contains(victim)) {
                    alreadyHit.add(victim);
                    applyHit(mob, victim, ability);
                }
            }
        }
        return true;
    }

    private boolean summon(LivingEntity mob, MobAbility ability) {
        EntityType type = entityType(ability.summonType());
        if (type == null || ability.count() <= 0) {
            return false;
        }
        int mobLevel = MobData.of(mob).level();
        for (int i = 0; i < ability.count(); i++) {
            double angle = 2.0 * Math.PI * i / ability.count();
            double spawnRadius = Math.max(1.0, ability.radius());
            Location spot = mob.getLocation().clone()
                    .add(Math.cos(angle) * spawnRadius, 0.0, Math.sin(angle) * spawnRadius);
            Entity spawned = mob.getWorld().spawnEntity(spot, type);
            if (spawned instanceof LivingEntity minion) {
                // 増援は「呼んだ本人と同じレベル帯」にする。無印のままだと Lv0 扱いで
                // ワンパンできる置物になり、増援を呼ぶ意味が消える。
                MobData.stamp(minion, mobLevel,
                        MobData.of(mob).defenseFor(DamageType.PHYSICAL),
                        MobData.of(mob).defenseFor(DamageType.MAGICAL));
                // 呼び主が消えたあとも延々と残らないよう、ワールド保存には乗せない。
                minion.setPersistent(false);
            }
            playEffects(spot, ability);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 共通部品
    // ------------------------------------------------------------------

    /** その技の最終ダメージを TF のパイプラインで出して適用する。 */
    private void applyHit(LivingEntity mob, Player victim, MobAbility ability) {
        if (ability.damagePercent() <= 0.0) {
            applyEffects(victim, ability);
            return;
        }
        AttackStats attack = MobData.of(mob).attackStats();
        // 通常攻撃の刻印値に倍率を掛けたものを「この技の基礎値」にする。
        // 刻印が無いモブ(バニラ)は Bukkit の既定近接ダメージ相当を 2.0 として扱う。
        double base = attack.defaultDamage() != 0 ? attack.defaultDamage() : 2.0;
        double abilityBase = base * ability.damagePercent();
        AttackStats scaled = attack.withDefaultDamage(abilityBase);
        double finalDamage = ability.damageType() == DamageType.MAGICAL
                ? combat.magicalFinalDamageFromMob(mob, victim, abilityBase, scaled)
                : combat.physicalFinalDamageFromMob(mob, victim, abilityBase, scaled);
        if (finalDamage <= 0.0) {
            applyEffects(victim, ability);
            return;
        }
        MobAbilityDamage.mark();
        try {
            victim.damage(finalDamage, mob);
        } finally {
            MobAbilityDamage.clear();
        }
        applyEffects(victim, ability);
    }

    private void applyEffects(Player victim, MobAbility ability) {
        for (MobAbility.EffectSpec spec : ability.effects()) {
            PotionEffectType type = PotionEffectTypes.resolve(spec.type());
            if (type == null || spec.durationTicks() <= 0) {
                continue;
            }
            victim.addPotionEffect(new PotionEffect(type, spec.durationTicks(), spec.amplifier(), false, true));
        }
    }

    private void pushAway(Location from, Player victim, double strength) {
        if (strength <= 0.0) {
            return;
        }
        Vector away = victim.getLocation().toVector().subtract(from.toVector());
        if (away.lengthSquared() < 1.0e-6) {
            away = new Vector(0.0, 1.0, 0.0);
        }
        victim.setVelocity(victim.getVelocity().add(away.normalize().multiply(strength).setY(0.35)));
    }

    private List<Player> playersInRadius(LivingEntity mob, double radius) {
        List<Player> out = new ArrayList<>();
        if (radius <= 0.0) {
            return out;
        }
        for (Entity entity : mob.getWorld().getNearbyEntities(mob.getLocation(), radius, radius, radius)) {
            if (entity instanceof Player player && player.isValid() && !player.isDead()) {
                out.add(player);
            }
        }
        return out;
    }

    /** 技名を周囲へアクションバーで出す。頭上ではなく action bar なのは統合版でも読めるため。 */
    private void announce(LivingEntity mob, MobAbility ability) {
        if (ability.displayName().isEmpty()) {
            return;
        }
        var message = MiniMessage.miniMessage().deserialize(ability.displayName());
        for (Player nearby : playersInRadius(mob, ANNOUNCE_RADIUS)) {
            nearby.sendActionBar(message);
        }
    }

    private void playEffects(Location location, MobAbility ability) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        Particle particle = particle(ability.particle());
        if (particle != null && ability.particleCount() >= 1) {
            double spread = Math.max(0.5, ability.radius() / 2.0);
            world.spawnParticle(particle, location, ability.particleCount(), spread, spread, spread, 0.0);
        }
        Sound sound = sound(ability.sound());
        if (sound != null) {
            world.playSound(location, sound, 1.0f, 1.0f);
        }
    }

    /** 基準ベクトルをY軸まわりに回す（扇状に撒くため）。 */
    static Vector rotateAroundY(Vector base, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double x = base.getX() * cos - base.getZ() * sin;
        double z = base.getX() * sin + base.getZ() * cos;
        return new Vector(x, base.getY(), z).normalize();
    }

    private static EntityType entityType(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * データ必須のパーティクル（{@code FLASH}/{@code DUST} 等）は受け付けない。
     * データ引数なしの {@code spawnParticle} を呼ぶと発生の瞬間に例外になり、
     * 周期タスクごと道連れにする（{@code SpecialRewardsConfig#parseParticle} と同じガード）。
     */
    private static Particle particle(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            Particle particle = Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
            return particle.getDataType() == Void.class ? particle : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * {@code Sound} は 1.21 系で enum ではなく {@code Keyed} なので {@code valueOf} は使わず
     * Registry から引く。未知の名前は無音に落とす（設定ミスで戦闘が止まるより静かな方がまし）。
     */
    private static Sound sound(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return org.bukkit.Registry.SOUNDS.get(
                    org.bukkit.NamespacedKey.minecraft(name.trim().toLowerCase(Locale.ROOT)));
        } catch (RuntimeException ex) {
            return null;
        }
    }

}
