package com.trinityforge.skilltree.runtime;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.ProjectileWeapon;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StunDurationStatNormalize;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies combat-oriented perk totals at damage / bow events (2026-07-23 stat-gate-overhaul §2 移行B:
 * every numeric perk consumer here reads the装備+perk合算 ({@link PlayerStatAggregator#aggregate}) instead
 * of the old perk-only native reward resolver, so the same buffs can now also be authored on
 * equipment. Charged-shot availability is also a regular perk-buff stat, so this listener has no
 * separate native-reward dependency.
 */
public final class NativeCombatPerkListener implements Listener {

    private static final String BOW_ACCURACY = StatKeys.canonical("bow_accuracy");
    private static final String AMMO_SAVE_CHANCE = StatKeys.canonical("ammo_save_chance");
    // distance_damage_bonus はこのクラスでは扱わない(2026-07-26 に CombatListener へ移設。
    // 同一 priority の登録順に依存して毎回上書き消去されていたため)。定数も CombatListener 側にある。
    private static final String ARROW_PIERCING = StatKeys.canonical("arrow_piercing");
    private static final String ARROW_VELOCITY = StatKeys.canonical("arrow_velocity");
    // 2026-07-31: bow_cooldown_reduction を撤去。アイテムCT短縮(cooldown_reduction)と同じ
    // Player#setCooldown を二重に掛ける設計で、しかも item-stats.yml の BOW/CROSSBOW に
    // item-cooldown が無いため shooter.getCooldown(bow) が常に0 = 完全な no-op だった。
    // 弓のCT短縮はアイテムCT短縮(CombatListener.startItemCooldown)へ一本化する。
    private static final String ARROW_KNOCKBACK = StatKeys.canonical("arrow_knockback");
    private static final String MELEE_KNOCKBACK = StatKeys.canonical("melee_knockback");
    private static final String STUN_CHANCE = StatKeys.canonical("stun_chance");
    private static final String STUN_DURATION_BONUS = StatKeys.canonical("stun_duration_bonus");
    // 2026-07-27: charged_shot_unlocked を撤去。「解放フラグ」を名乗りながら、それが解放するはずの
    // 効果(貫通/初速/CT短縮/ノックバック)はいずれも**自分自身のステが非0であること**を個別に
    // 要求しており、フラグの OR 条件にもその同じステが並んでいた。つまり
    // 「フラグが解放するもの」は「そのステ自身が既に解放している」もので、完全な同語反復だった。
    // 撤去しても挙動は一切変わらない(挙動の変化を伴わないキー削除)。

    /**
     * スタン継続時間の絶対上限(tick)。5秒 = 100tick。{@code stun_duration_bonus} を加算しても
     * これを超えない — 上限なしにするとハメ殺し(行動不能の連続化)になるため。stats/lore.yml
     * {@code stats.stun-duration-bonus.limits} から {@code java:} cap-ref で参照される
     * (CapRefResolver 拘束テスト対象)。{@code public} でないと cap-ref から参照できない。
     */
    public static final int MAX_STUN_DURATION_TICKS = 100;

    /**
     * ノックバック内部値1あたりの初速(blocks/tick)。近接と矢で係数が違う。
     *
     * <p>2026-07-31 レビュー指摘4: {@code stats/lore.yml} の単位は {@code m} だが内部値は
     * <b>距離ではなく velocity への加算</b>なので、表示側で
     * {@code display-scale} を掛けて桁を合わせている。空中の水平減衰 0.91/tick を等比級数で
     * 積むと総移動距離は初速の {@link #VELOCITY_TO_BLOCKS} 倍なので、
     * lore の {@code display-scale} は「この係数 × {@link #VELOCITY_TO_BLOCKS}」で決めること
     * (整合は {@code KnockbackDisplayScaleDriftTest} が機械照合する)。接地中は摩擦が強い
     * (0.6×0.91)ため実距離は表示より短くなる概算値である点は docs/config-reference に明記済み。
     */
    public static final double MELEE_KNOCKBACK_VELOCITY_PER_UNIT = 0.35;

    /** 矢ノックバック内部値1あたりの初速(blocks/tick)。{@link #MELEE_KNOCKBACK_VELOCITY_PER_UNIT} 参照。 */
    public static final double ARROW_KNOCKBACK_VELOCITY_PER_UNIT = 0.4;

    /**
     * 初速(blocks/tick)→総移動距離(blocks)の換算係数。バニラの空中水平減衰 0.91/tick を
     * 等比級数で積んだ {@code 1/(1-0.91)}。
     */
    public static final double VELOCITY_TO_BLOCKS = 1.0 / (1.0 - 0.91);

    /**
     * 「実際に殴った」と見なすDamageCause。{@code CombatListener} の同名の集合と意図的に同一に保つこと
     * (片方だけ広げると、近接専用ステが魔法/反射などへ漏れる)。
     */
    private static final Set<EntityDamageEvent.DamageCause> MELEE_CAUSES = EnumSet.of(
            EntityDamageEvent.DamageCause.ENTITY_ATTACK,
            EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK);

    private final PlayerStatAggregator aggregator;

    public NativeCombatPerkListener(PlayerStatAggregator aggregator) {
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    /**
     * 近接専用の {@code melee_knockback} / {@code stun_chance} を適用する。
     *
     * <p><b>2026-07-26 修正</b>: 以前は「ダメージ元がPlayer」だけで通していたため、
     * ArsPaperの魔法ダメージ({@code TrinityForgeBridge#applyMagicDamage} が causingEntity=詠唱者で
     * {@link EntityDamageByEntityEvent} を発火する)や、プラグインがプレイヤー起因で発火する任意の
     * ダメージにも**近接専用のノックバック/スタンが乗っていた**。
     * {@code CombatListener} の近接判定と同じ {@link #MELEE_CAUSES}(ENTITY_ATTACK /
     * ENTITY_SWEEP_ATTACK)でゲートし、実際に「殴った」ときだけ効くようにする。
     * {@code power-attack-*} は元から {@code CombatListener} 側の同じゲートの内側にあり、この漏れは無かった。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent event) {
        if (!MELEE_CAUSES.contains(event.getCause())) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        Random rng = ThreadLocalRandom.current();

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        PlayerCombatAggregate agg = aggregator.aggregate(attacker, weapon);

        double knock = agg.totalOf(MELEE_KNOCKBACK);
        if (knock > 0.0) {
            Vector vel = victim.getVelocity();
            Vector push = attacker.getLocation().getDirection().normalize()
                    .multiply(MELEE_KNOCKBACK_VELOCITY_PER_UNIT * knock);
            victim.setVelocity(vel.add(push));
        }

        double stunChance = agg.totalOf(STUN_CHANCE);
        if (stunChance > 0.0 && rng.nextDouble() < Math.min(0.75, stunChance)) {
            int ticks = stunTicks(agg.totalOf(STUN_DURATION_BONUS));
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 5, false, true, true));
            victim.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, ticks, 2, false, true, true));
            victim.setFreezeTicks(Math.max(victim.getFreezeTicks(), ticks));
            // 予告機構の中断(機構8、2026-09-04): 殴ってスタンを与えたら、詠唱中の敵はそこで中断する。
            com.trinityforge.combat.MobAbilityInterrupts.notifyStun(victim);
        }

    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile)) return;
        ProjectileSource source = projectile.getShooter();
        if (!(source instanceof Player shooter)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        ItemStack bow = firingWeapon(projectile, shooter);
        // 2026-08-13バグ修正: オフハンドから撃たれた弓/クロスボウでも offhand-stats-apply の門が正しく
        // 掛かるよう、発射時に retain された値を渡す(トライデントは常に false、CombatListener.
        // resolveContributorIsOffhand と同じ理由)。
        PlayerCombatAggregate agg = aggregator.aggregate(shooter, bow, ProjectileWeapon.readFiredFromOffhand(projectile));

        // 2026-07-26 バグ修正: distance-damage-bonus の適用をここから CombatListener のパイプライン内
        // ({@code CombatListener.distanceDamage}) へ移設した。
        //
        // 旧実装は event.setDamage(event.getDamage() * 係数) をこのリスナー(HIGH)で掛けていたが、
        // CombatListener も**同じ HIGH** で、しかも TrinityForge.java の登録順が
        // NativeCombatPerkListener(378行) → CombatListener(423行) のため **こちらが先に走る**。
        // CombatListener はアイテムに attack-power があるとき(tfBaseReplaces=true)
        // vanillaBaseDamage を丸ごと捨ててTF算出値で baseDamage を作り直し、最後に
        // setDamage(BASE, total) で上書きするので、ここで掛けた距離ボーナスは**毎回消えていた**。
        // item-stats.yml の BOW は attack-power:69、CROSSBOW は 270.5 を持つため常に上書き側に入り、
        // archery.yml のα路線(5段すべて distance-damage-bonus)が丸ごと死んでいた。
        //
        // 同一 priority の登録順に依存する設計自体をやめ、power-attack-damage と同じく
        // 「total 算出後に一度だけ掛ける」パイプライン内の位置へ統一する。
        // 2026-07-27: 旧 charged_shot_unlocked ゲートを撤去した。条件は
        //   (flag != 0 || chargedKb > 0 || piercing > 0) && chargedKb > 0
        // という**同語反復**で、chargedKb > 0 なら左辺は必ず真になるため、実質 chargedKb > 0 だけが
        // 効いていた(=フラグはあってもなくても挙動が変わらない死にキーだった)。
        double chargedKb = agg.totalOf(ARROW_KNOCKBACK);
        if (chargedKb > 0.0 && projectile.getVelocity().lengthSquared() > 1.0) {
            Vector push = projectile.getVelocity().normalize()
                    .multiply(ARROW_KNOCKBACK_VELOCITY_PER_UNIT * chargedKb);
            event.getEntity().setVelocity(event.getEntity().getVelocity().add(push));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player shooter)) return;
        Random rng = ThreadLocalRandom.current();

        ItemStack bow = event.getBow();
        // 2026-08-13バグ修正: onProjectileDamage と同じ理由(offhand-stats-apply 門迂回防止)。ここでは
        // PDC 経由ではなく event.getHand() から直接判定できる(EntityShootBowEvent 本体を持っているため)。
        //
        // 2026-08-13再修正: EntityShootBowEvent#getBow() は @Nullable であり、bow が null のときの
        // 寄与アイテムは(下の三項演算子で)常に「実メインハンドのアイテム」にフォールバックする。
        // そのフォールバック経路で hand == OFF_HAND だった場合、寄与アイテムはメインハンドなのに
        // フラグだけ true になり、集計器がオフハンドスロットを不要に除外してしまう(オフハンド側の
        // 寄与が無言で落ちる)。bow != null のとき「実際にオフハンドの武器を寄与アイテムとして渡している」
        // ときだけ true にする。
        boolean firedFromOffhand = bow != null && ProjectileWeapon.isOffhand(event.getHand());
        PlayerCombatAggregate agg = aggregator.aggregate(shooter,
                bow != null ? bow : shooter.getInventory().getItemInMainHand(),
                firedFromOffhand);

        double ammoSave = agg.totalOf(AMMO_SAVE_CHANCE);
        if (ammoSave > 0.0 && rng.nextDouble() < Math.min(0.9, ammoSave)) {
            ItemStack consumable = event.getConsumable();
            if (consumable != null && consumable.getAmount() > 0
                    && consumable.getType() == org.bukkit.Material.ARROW) {
                consumable.setAmount(consumable.getAmount() + 1);
            }
        }

        // 弓精度(bow_accuracy)は符号反転済み語彙: 正の値ほど高精度 → jitter = max(0, 基準0.08 − 精度)。
        double accuracy = agg.totalOf(BOW_ACCURACY);
        // 2026-07-27: 旧 chargedUnlocked ゲートを撤去。OR 条件に並んでいた4ステ
        // (ARROW_PIERCING / ARROW_VELOCITY / 旧 BOW_COOLDOWN_REDUCTION / ARROW_KNOCKBACK)は、
        // ゲートの内側でそれぞれ「自分が非0か」を再度検査していたため、ゲートは常に無条件で
        // 開いているのと同じだった(同語反復)。各ステ自身の判定だけを残す。

        if (event.getProjectile() instanceof Projectile projectile) {
            Vector v = projectile.getVelocity();
            double jitter = bowJitter(accuracy);
            if (jitter > 0.0) {
                v.add(new Vector(
                        (rng.nextDouble() - 0.5) * jitter,
                        (rng.nextDouble() - 0.5) * jitter * 0.5,
                        (rng.nextDouble() - 0.5) * jitter));
            }
            double velocityBonus = agg.totalOf(ARROW_VELOCITY);
            if (velocityBonus != 0.0) {
                v.multiply(1.0 + velocityBonus);
            }
            projectile.setVelocity(v);
        }

        int pierce = (int) Math.round(agg.totalOf(ARROW_PIERCING));
        if (pierce > 0 && event.getProjectile() instanceof org.bukkit.entity.AbstractArrow arrow) {
            arrow.setPierceLevel(Math.min(127, arrow.getPierceLevel() + pierce));
        }
    }

    static double bowJitter(double accuracy) {
        if (!Double.isFinite(accuracy)) return 0.08;
        return Math.max(0.0, 0.08 - accuracy);
    }

    /**
     * スタン継続時間(tick)。{@code combat/base-stats.yml} の初期値、装備、パークを
     * {@code stun_duration_bonus} として加算した総tick数を直接使う。発動確率
     * ({@code stun_chance})は継続時間へ影響しない。{@link #MAX_STUN_DURATION_TICKS} を超えないよう
     * クランプし、最低でも1tickは残す(0tick以下のPotionEffectは無効なため)。
     */
    static int stunTicks(double durationTicks) {
        double finiteTicks = Double.isNaN(durationTicks)
                ? StunDurationStatNormalize.DEFAULT_TICKS : durationTicks;
        double clamped = Math.max(1.0, Math.min(MAX_STUN_DURATION_TICKS, finiteTicks));
        return (int) Math.round(clamped);
    }

    /** The firing bow/crossbow/trident retained on {@code projectile} at launch, falling back to mainhand. */
    private static ItemStack firingWeapon(Projectile projectile, Player shooter) {
        return ProjectileWeapon.read(projectile).orElseGet(() -> shooter.getInventory().getItemInMainHand());
    }
}
