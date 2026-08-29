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

    /** yml の {@code knockback}(0〜5) → 水平速度(ブロック/tick)の換算。 */
    static final double REPULSE_HORIZONTAL_SCALE = 0.4;
    /** 同 → 上向き速度の換算。 */
    static final double REPULSE_VERTICAL_SCALE = 0.18;
    /** 上向き速度の上限。落下ダメージだけで殺せる高さまで打ち上げないための天井。 */
    static final double REPULSE_MAX_VERTICAL = 0.9;
    /** 引き寄せの水平速度換算。 */
    static final double PULL_HORIZONTAL_SCALE = 0.7;
    /** この距離以上離れていれば引き寄せは最大強度。 */
    static final double PULL_FULL_DISTANCE = 12.0;
    /** 引き寄せ時のわずかな浮き。地面の摩擦・段差で引っ掛からないため。 */
    static final double PULL_LIFT = 0.2;
    /** {@code DELAYED_ZONE} の予告パーティクルを撒く間隔。 */
    private static final long TELEGRAPH_INTERVAL_TICKS = 5L;
    /**
     * {@code PROJECTILE_RAIN} の発射高さ。着弾まで約1秒あることが「動けば避けられる」の根拠なので、
     * 低くしすぎると回避不能技になる。天井のある部屋では屋根に刺さって不発になるが、
     * それは「屋内では雨が降らない」という直感どおりの結果なので許容する。
     */
    static final double RAIN_SPAWN_HEIGHT = 9.0;
    /** 同、投射物の初速（ブロック/tick）。 */
    static final double RAIN_SPEED = 1.1;
    /** 同、着弾点のばらつき。0 にすると全弾が同一点へ収束して回避不能になる。 */
    static final double RAIN_AIM_JITTER = 0.6;

    private final Plugin plugin;
    private final SymmetricCombatService combat;
    private final java.util.function.DoubleSupplier elementBias;

    public MobAbilityExecutor(Plugin plugin, SymmetricCombatService combat) {
        this(plugin, combat, () -> com.trinityforge.config.domains.MobAbilitiesConfig.DEFAULT_ELEMENT_BIAS);
    }

    /**
     * @param elementBias {@code combat/mob-abilities.yml} の {@code ability-element-bias} を
     *                    <b>毎回読み直す</b>供給元（{@code /trinityforge reload} で即反映させるため、
     *                    値ではなくサプライヤで受ける）。詳細は
     *                    {@link com.trinityforge.config.domains.MobAbilitiesConfig#elementBias()}。
     */
    public MobAbilityExecutor(Plugin plugin, SymmetricCombatService combat,
                              java.util.function.DoubleSupplier elementBias) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combat = Objects.requireNonNull(combat, "combat");
        this.elementBias = Objects.requireNonNull(elementBias, "elementBias");
    }

    /**
     * 技の実効「魔法割合」。<b>技は自分の属性へ 100% 寄せない</b> —— そのモブの
     * {@code magic-ratio} を土台にして、{@code damage-type} のぶんだけ自分の属性側へ引き寄せる。
     *
     * <p>2026-08-21(W-181) 以前は {@code damage-type} の側へ 100% 寄せていて、
     * {@code magic-ratio} の上限 0.45（＝「魔法防御を持たないプレイヤーが何発耐えるか」で
     * 校正した安全弁）を技だけが素通りしていた。{@code damage-percent: 2.0} の魔法技は
     * 実質 magic-ratio 2.0 相当で、難易度1のダンジョンでも最大HPの7割以上を1発で奪っていた。
     * さらに「敵は物理型」のダンジョンで魔法技だけが即死級になり、
     * <b>正しく物理防御を積んだプレイヤーほど理不尽に死ぬ</b>という逆転が起きていた。
     */
    public static double effectiveMagicRatio(double mobMagicRatio, DamageType abilityType, double bias) {
        double r = Math.max(0.0, Math.min(1.0, mobMagicRatio));
        double b = Math.max(0.0, Math.min(1.0, bias));
        return abilityType == DamageType.MAGICAL ? r + (1.0 - r) * b : r * (1.0 - b);
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
                case PROJECTILE_RAIN -> projectileRain(mob, target, ability);
                case CHARGE -> charge(mob, target, ability);
                case AURA -> aura(mob, ability);
                case TELEPORT_STRIKE -> teleportStrike(mob, target, ability);
                case BEAM -> beam(mob, target, ability);
                case SUMMON -> summon(mob, ability);
                case REPULSE -> repulse(mob, ability);
                case VORTEX_PULL -> vortexPull(mob, ability);
                case DELAYED_ZONE -> delayedZone(mob, target, ability);
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

    /**
     * 対象の頭上から投射物を降らせる（2026-08-17、ユーザー報告「矢の雨が当たらない」）。
     *
     * <p>{@link #projectileVolley} との違いは<b>発射位置</b>。あちらはモブの目線から水平に扇状へ撒くので、
     * 開き角のぶんだけ中央以外は最初から相手を向いておらず、さらに水平発射した矢は落下で下へ逸れる。
     * こちらは相手の頭上に散らして出し、着弾点を相手の足元付近へ<b>収束</b>させる。
     *
     * <p>ねらいは「当たる技」ではなく「<b>その場に立っていると当たる技</b>」。着弾までに約1秒あり、
     * 発射時の座標へ向けて落ちてくるので、動けば外れる。避けさせるために着弾点へ演出を出す
     * （頭上から降る技は音だけでは反応できない）。
     */
    private boolean projectileRain(LivingEntity mob, Player target, MobAbility ability) {
        EntityType type = entityType(ability.projectile());
        if (type == null || ability.count() <= 0) {
            return false;
        }
        Location center = target.getLocation().clone();
        double scatter = Math.max(0.5, ability.radius());
        // 着弾点の予告。ここが見えないと「頭上から降ってくる」ことに気づけない。
        playEffects(center, ability);
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < ability.count(); i++) {
            // 円板上に一様分布させる（sqrt を取らないと中心に偏って「雨」に見えない）。
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double distance = scatter * Math.sqrt(rng.nextDouble());
            Location spawn = center.clone().add(
                    Math.cos(angle) * distance, RAIN_SPAWN_HEIGHT, Math.sin(angle) * distance);
            Vector aim = center.toVector()
                    .add(new Vector(rng.nextDouble(-RAIN_AIM_JITTER, RAIN_AIM_JITTER), 0.0,
                            rng.nextDouble(-RAIN_AIM_JITTER, RAIN_AIM_JITTER)))
                    .subtract(spawn.toVector());
            if (aim.lengthSquared() < 1.0e-6) {
                aim = new Vector(0.0, -1.0, 0.0);
            }
            Entity spawned = mob.getWorld().spawnEntity(spawn, type);
            if (spawned instanceof Projectile projectile) {
                projectile.setShooter(mob);
            }
            // 降ってきた矢を拾えると「被弾するほど矢が増える」ので拾得を禁じる。
            if (spawned instanceof org.bukkit.entity.AbstractArrow arrow) {
                arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
            }
            spawned.setVelocity(aim.normalize().multiply(RAIN_SPEED));
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

    /**
     * 全方位の強ノックバック（2026-08-16）。{@link #pushAway} は「当たったついでに少し押す」味付け
     * （Y は 0.35 固定・現在の速度へ加算）なので、強度をいくら上げても<b>真横に滑るだけ</b>で
     * 崖・溶岩・落下が脅威にならない。こちらは {@link #repulseVelocity} で速度を<b>置き換え</b>、
     * 強度に応じて上方向にも飛ばす。
     */
    private boolean repulse(LivingEntity mob, MobAbility ability) {
        Location center = mob.getLocation();
        for (Player victim : playersNear(center, ability.radius())) {
            applyHit(mob, victim, ability);
            if (ability.knockback() > 0.0) {
                // ダメージのあとに置くこと。victim.damage() 由来のバニラノックバックを上書きするため。
                victim.setVelocity(repulseVelocity(center.toVector(),
                        victim.getLocation().toVector(), ability.knockback()));
            }
        }
        return true;
    }

    /** 周囲のプレイヤーを自分の方へ引きずり込む（{@link #repulse} の逆向き）。 */
    private boolean vortexPull(LivingEntity mob, MobAbility ability) {
        Location center = mob.getLocation();
        for (Player victim : playersNear(center, ability.radius())) {
            applyHit(mob, victim, ability);
            if (ability.knockback() > 0.0) {
                victim.setVelocity(pullVelocity(center.toVector(),
                        victim.getLocation().toVector(), ability.knockback()));
            }
        }
        return true;
    }

    /**
     * 対象の足元へ印を置き、{@link MobAbility#delayTicks()} 後にその地点へ着弾する。
     *
     * <p><b>印の位置は発動時点で固定</b>する（対象を追尾させない）。追尾させると回避手段が
     * 「射程外へ逃げる」しか無くなり、予告を出す意味が消えるため。撃った本人が着弾前に死んだ場合は
     * 不発にする（死体から技だけ飛んでくるのを避ける）。
     */
    private boolean delayedZone(LivingEntity mob, Player target, MobAbility ability) {
        Location mark = target.getLocation().clone();
        int delay = ability.delayTicks();
        double radius = Math.max(1.0, ability.radius());
        new BukkitRunnable() {
            private int elapsed = 0;

            @Override
            public void run() {
                if (elapsed >= delay) {
                    cancel();
                    if (!mob.isValid()) {
                        return;
                    }
                    playEffects(mark, ability);
                    for (Player victim : playersNear(mark, radius)) {
                        applyHit(mob, victim, ability);
                        if (ability.knockback() > 0.0) {
                            victim.setVelocity(repulseVelocity(mark.toVector(),
                                    victim.getLocation().toVector(), ability.knockback()));
                        }
                    }
                    return;
                }
                elapsed += TELEGRAPH_INTERVAL_TICKS;
                playEffects(mark, ability); // 予告。ここが見えないと「避けられる技」が成立しない
            }
        }.runTaskTimer(plugin, 0L, TELEGRAPH_INTERVAL_TICKS);
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
        // 2026-08-21(W-181): 技も【そのモブの magic-ratio を土台に】物理/魔法へ分割する。
        // physicalFinalDamageFromMob は magicRatio が 0 / 1 / 中間 のいずれでも正しく捌く
        // (0=完全物理・1=完全魔法・中間=1回の回避ロールで両成分へ通す hybrid)ので、
        // 属性ごとに呼び分けず、実効比率を載せた AttackStats を1本で渡す。
        AttackStats scaled = attack.withDefaultDamage(abilityBase)
                .withMagicRatio(effectiveMagicRatio(attack.magicRatio(), ability.damageType(),
                        elementBias.getAsDouble()));
        double finalDamage = combat.physicalFinalDamageFromMob(mob, victim, abilityBase, scaled);
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
        return playersNear(mob.getLocation(), radius);
    }

    /**
     * 任意の地点を中心にした走査。{@code DELAYED_ZONE} は<b>撃った本人ではなく置いた印</b>を
     * 中心に判定するので、モブ中心の走査だけでは足りない。
     */
    private List<Player> playersNear(Location center, double radius) {
        List<Player> out = new ArrayList<>();
        World world = center.getWorld();
        if (world == null || radius <= 0.0) {
            return out;
        }
        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
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

    /**
     * {@code REPULSE} の吹き飛ばし速度（純関数）。
     *
     * <p><b>yml の {@code knockback}(0〜5) をそのまま速度に使ってはいけない。</b> Bukkit の速度は
     * ブロック/tick なので、5 をそのまま入れると毎秒 100 ブロックで場外まで吹き飛ぶ
     * （バニラのノックバックは約 0.4）。ここで水平 {@value #REPULSE_HORIZONTAL_SCALE} 倍・
     * 垂直 {@value #REPULSE_VERTICAL_SCALE} 倍（上限 {@value #REPULSE_MAX_VERTICAL}）へ換算する。
     * 垂直に上限を置くのは、上げ過ぎると落下ダメージだけで殺せてしまい防具の意味が消えるため。
     *
     * <p>同じ座標に重なっている（水平距離ゼロ）ときは真上へ。正規化できないベクトルを
     * {@code normalize()} に渡すと NaN 速度になり、<b>プレイヤーが操作不能になる</b>。
     */
    static Vector repulseVelocity(Vector from, Vector victim, double strength) {
        double power = Math.max(0.0, strength);
        double lift = Math.min(REPULSE_MAX_VERTICAL, REPULSE_VERTICAL_SCALE * power);
        Vector away = victim.clone().subtract(from).setY(0.0);
        if (away.lengthSquared() < 1.0e-6) {
            return new Vector(0.0, lift, 0.0);
        }
        return away.normalize().multiply(power * REPULSE_HORIZONTAL_SCALE).setY(lift);
    }

    /**
     * {@code VORTEX_PULL} の引き寄せ速度（純関数）。
     *
     * <p>遠い相手ほど強く引く（{@value #PULL_FULL_DISTANCE} m で最大）。距離に依らず一定にすると、
     * 密着している相手を押し込むだけの無意味な速度が付き、逆に遠い相手は届かない。
     * わずかに上向き成分を足すのは、地面の摩擦で 1 ブロックの段差にも引っ掛かるため。
     */
    static Vector pullVelocity(Vector center, Vector victim, double strength) {
        double power = Math.max(0.0, strength);
        Vector toward = center.clone().subtract(victim).setY(0.0);
        double distance = toward.length();
        if (distance < 1.0e-3) {
            return new Vector(0.0, PULL_LIFT, 0.0);
        }
        double scale = power * PULL_HORIZONTAL_SCALE * Math.min(1.0, distance / PULL_FULL_DISTANCE);
        return toward.normalize().multiply(scale).setY(PULL_LIFT);
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
     * yml に書いた効果音名（enum 定数名の綴り）→ {@code Sound} の対応表。
     *
     * <p><b>なぜ表を作るのか</b>（2026-07-31 修正）: {@code Sound} は 1.21 系で enum ではなく
     * {@code Keyed} になったため {@code valueOf} が使えない。しかし
     * <b>enum 定数名とレジストリキーは機械的に変換できない</b>:
     * <ul>
     *   <li>{@code ENTITY_GENERIC_EXPLODE} ⇔ {@code entity.generic.explode}（区切りが {@code .}）</li>
     *   <li>{@code ENTITY_IRON_GOLEM_ATTACK} ⇔ {@code entity.iron_golem.attack}
     *       （<b>モブ名の中の {@code _} は残る</b>ので、全部 {@code .} に置換すると壊れる）</li>
     * </ul>
     * 当初の実装はアンダースコアのままキーを組んでいたため
     * <b>出荷11テンプレート全部が無音</b>（{@code get} が null を返すだけで例外もログも出ない）。
     * 素朴に {@code _}→{@code .} 変換しても {@code ENTITY_IRON_GOLEM_ATTACK} と
     * {@code ENTITY_ZOMBIE_VILLAGER_CONVERTED} の2件が解けない。
     *
     * <p>そこでレジストリを1回だけ走査して「キーの {@code .} を {@code _} に直して大文字化」を
     * 索引にする。これは enum 定数名の綴りとちょうど一致するので、変換規則を推測せずに済む。
     * {@code minecraft:} 名前空間のフルキー表記（{@code entity.generic.explode}）でも引ける。
     */
    private static volatile java.util.Map<String, Sound> soundIndex;

    private static java.util.Map<String, Sound> soundIndex() {
        java.util.Map<String, Sound> cached = soundIndex;
        if (cached != null) {
            return cached;
        }
        java.util.Map<String, Sound> built = new java.util.HashMap<>();
        for (Sound sound : org.bukkit.Registry.SOUNDS) {
            String key = sound.getKey().getKey();
            built.put(key.toUpperCase(Locale.ROOT).replace('.', '_'), sound);
            built.put(key.toUpperCase(Locale.ROOT), sound);
        }
        java.util.Map<String, Sound> immutable = java.util.Map.copyOf(built);
        soundIndex = immutable;
        return immutable;
    }

    /** 未知の名前は無音に落とす（設定ミスで戦闘が止まるより静かな方がまし）。 */
    private static Sound sound(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return soundIndex().get(name.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            return null;
        }
    }

}
