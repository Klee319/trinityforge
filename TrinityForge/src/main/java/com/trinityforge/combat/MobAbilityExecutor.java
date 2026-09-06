package com.trinityforge.combat;

import com.trinityforge.config.PotionEffectTypes;
import com.trinityforge.pdc.MobData;
import net.kyori.adventure.text.Component;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * {@link MobAbility} を実際に撃つ側（2026-07-31、予告機構は2026-09-04）。
 *
 * <p><b>ダメージの流し方</b>: 素の {@code target.damage(n, mob)} は<b>使わない</b>。それだと
 * {@code CombatListener} のモブ→プレイヤー近接経路が走り、技の数値ではなく<b>刻印済みの通常攻撃の数値</b>で
 * 上書きされる（{@link MobAbilityDamage} の javadoc に書かれている既知の罠そのもの）。
 * ここでは先に {@link SymmetricCombatService} で技の最終ダメージを出し、
 * {@link MobAbilityDamage#mark()} で「この一撃はこちらが計算済み」と宣言してから適用する。
 *
 * <p>Bukkit 由来の例外（未知の {@code EntityType}、ワールド越え、消滅済みエンティティ）は
 * 1件ずつ握って読み飛ばす。周期タスクから呼ばれるので、1体の失敗で全モブの特殊攻撃が止まると困る。
 *
 * <p><b>予告（{@code cast-seconds} > 0）の実行経路</b>（機構2〜6、
 * {@code docs/design/2026-09-02-telegraph-mechanics-spec.md} + 2026-09-04 UXクロスレビュー追加指示）:
 * {@link #execute} は3経路に分かれる。
 * <ul>
 *   <li>{@code telegraphTicks() <= 0} → 現行どおり即時解決。396体の雑魚の挙動は変えない。</li>
 *   <li>{@code DELAYED_ZONE} → {@link #delayedZone} が予告予算を自前で確保・解放する
 *       （非同期に走るタスク自身が着地まで責任を持つ）。</li>
 *   <li>それ以外で {@code telegraphTicks() > 0} → {@link #startCast} が1tick間隔の詠唱ループを回し、
 *       向きを固定しつつ5tickごとに予告演出とアクションバー更新を出す。</li>
 * </ul>
 *
 * <p><b>幾何のスナップショット化</b>（追加指示1）: 詠唱開始時に {@link CastSnapshot} を1回だけ作り、
 * <b>描画も解決もそのスナップショットから座標を取る</b>。毎tickの向き固定はモブ本体に対して行うが、
 * 判定・演出の中心/向き/長さは詠唱開始時点で凍結する ―― でないと「描いた輪と実際に当たる範囲が
 * tick ごとにズレる」ことになる。
 */
public final class MobAbilityExecutor {

    /** 技名の告知が届く距離。即時経路（予告なし）でのみ使う。半径 32 は「同じ部屋にいる人には見える」目安。 */
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
    /** 予告演出・アクションバー更新の間隔（詠唱ループ・{@code DELAYED_ZONE} 共通）。 */
    private static final long TELEGRAPH_INTERVAL_TICKS = 5L;
    /** 詠唱終了の何tick前に着弾合図音を鳴らすか。 */
    private static final long PING_LEAD_TICKS = 5L;
    /** {@code DELAYED_ZONE} 印の点滅周期（tick）。5tick周期の予告更新のうち2回に1回だけ光らせる。 */
    private static final long MARK_BLINK_TICKS = 10L;
    /** 詠唱ループの tick を ms へ換算する係数（Bukkit は1tick=50ms固定）。 */
    private static final long MILLIS_PER_TICK = 50L;
    /** 脅威圏の受信者選定に使う anchor からの水平距離上限（追加指示4）。 */
    private static final double THREAT_ZONE_HORIZONTAL = 12.0;
    /** 着弾合図音（機構3追加指示: 共通の短い合図音）。 */
    private static final Sound IMPACT_WARNING_SOUND = Sound.BLOCK_NOTE_BLOCK_PLING;
    /** 詠唱ループ例外のログを同じ技IDにつき最短この間隔まで間引く。 */
    private static final long ERROR_LOG_INTERVAL_MILLIS = 60_000L;
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
    private final ToDoubleFunction<String> abilityDamageScaleOfWorld;
    private final ActionBarRouter actionBar;
    private final TelegraphBudget budget;
    /** {@code telegraph-lethal-atomic}（機構10）を毎回読み直す供給元。 */
    private final BooleanSupplier telegraphLethalAtomic;
    /** {@code telegraph-bar-style}（機構3）を毎回読み直す供給元。 */
    private final Supplier<ActionBarRouter.BarStyle> barStyle;

    /** 詠唱ループの例外ログ間引き（技IDごと・全インスタンス共通でよい静的表）。 */
    private static final java.util.Map<String, Long> lastErrorLogMillis = new ConcurrentHashMap<>();

    /** 同じモブが空振り硬直を再び受けるまでの最短間隔（機構7）。 */
    private static final long WHIFF_STAGGER_LOCKOUT_MILLIS = 8_000L;
    /** {@code FIXED_ZONE} の {@code duration-seconds} 未指定（0）時の既定（展開後の持続秒数）。 */
    private static final double FIXED_ZONE_DEFAULT_DURATION_SECONDS = 6.0;

    /** モブUUID → 直近の空振り硬直を課した時刻（機構7、間引き用）。 */
    private final Map<UUID, Long> lastStaggerMillis = new HashMap<>();

    /**
     * {@link #resolve} 呼び出し1回ぶんの同期的な命中数（機構7「空振り硬直」の判定用）。
     * 呼び手が呼び出し直前に 0 へリセットし、直後に読む。{@code CHARGE}/{@code AURA} は命中が
     * 非同期（遅延タスク）で起きるためこの数え方が成立せず、呼び手側で判定自体をスキップする。
     */
    private int syncHitCounter;

    /**
     * 進行中の詠唱台帳（機構8「中断」）。{@link MobAbilityInterrupts} から
     * {@link #onCasterDamaged} / {@link #onCasterStunned} 経由で書き込まれる。
     */
    private final Map<UUID, ActiveCast> activeCasts = new HashMap<>();

    /** 1件の進行中詠唱。{@code damageTaken} だけ可変（中断判定のため蓄積する）。 */
    private static final class ActiveCast {
        private final MobAbility ability;
        private final Consumer<String> finish;
        private final double maxHealthAtStart;
        private double damageTaken;

        private ActiveCast(MobAbility ability, Consumer<String> finish, double maxHealthAtStart) {
            this.ability = ability;
            this.finish = finish;
            this.maxHealthAtStart = maxHealthAtStart;
        }
    }

    /**
     * 候補収集のラッパー（機構1・仕様書「候補収集のラッパーは差し替え可能にしておく」）。既定は
     * {@code world.getNearbyEntities(center, dx, dy, dz)}。{@code getNearbyEntities} が
     * MockBukkit の実 {@code WorldMock} で動くかは未確認なので、テストから差し替えられるようにする。
     */
    @FunctionalInterface
    interface EntityLookup {
        List<Entity> nearby(Location center, double dx, double dy, double dz);
    }

    private EntityLookup entityLookup = (center, dx, dy, dz) -> {
        World world = center.getWorld();
        if (world == null) {
            return List.of();
        }
        return new ArrayList<>(world.getNearbyEntities(center, dx, dy, dz));
    };

    /**
     * 視線判定フック。既定は {@code mob.hasLineOfSight(target)} を try/catch で包んだもの
     * （MockBukkit が未実装のため、例外時は true 扱い＝視線判定を課さない）。
     */
    private BiPredicate<LivingEntity, Player> lineOfSightCheck = (mob, target) -> {
        try {
            return mob.hasLineOfSight(target);
        } catch (Throwable ignored) {
            return true;
        }
    };

    /** 現在時刻の供給元。{@link TelegraphBudget} の予約期限計算に使う（テストで差し替え可能）。 */
    private LongSupplier clock = System::currentTimeMillis;

    /**
     * 命中の観測フック（テスト用）。本番の既定は no-op —— ダメージ適用そのものは常に
     * {@link #applyHit} 本体が行うので、このフックは「誰に telegraphed=true/false で当たったか」を
     * 横から記録するためだけに存在する（仕様書の HitSink 案を簡略化したもの）。
     */
    private BiConsumer<Player, Boolean> hitObserver = (player, telegraphed) -> {
    };

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
        this(plugin, combat, elementBias, world -> 1.0);
    }

    /**
     * @param abilityDamageScaleOfWorld ワールド名 → 技ダメージ倍率。未設定ワールドは 1.0 を返すこと。
     *                                  {@code combat/mob-overrides.yml} の {@code ability-damage-scale}
     *                                  を毎回読み直す（reload 即反映のためサプライヤではなく関数で受ける）。
     */
    public MobAbilityExecutor(Plugin plugin, SymmetricCombatService combat,
                              java.util.function.DoubleSupplier elementBias,
                              ToDoubleFunction<String> abilityDamageScaleOfWorld) {
        this(plugin, combat, elementBias, abilityDamageScaleOfWorld, new ActionBarRouter(), new TelegraphBudget());
    }

    /**
     * 予告機構込みの正準コンストラクタ（2026-09-04）。
     *
     * @param actionBar 予告のアクションバー表示とスキルEXP表示等を調停するルータ。
     *                  {@link com.trinityforge.progression.SkillExpFeedbackService} と
     *                  <b>同じインスタンスを共有する</b>こと（TrinityForge.java の配線を参照）。
     * @param budget    プレイヤー単位の予告予算台帳。{@link MobAbilityTask} と同じインスタンスを共有し、
     *                  抽選段階の {@code canReserve} 判定に使う（{@link #budget()}）。
     */
    public MobAbilityExecutor(Plugin plugin, SymmetricCombatService combat,
                              java.util.function.DoubleSupplier elementBias,
                              ToDoubleFunction<String> abilityDamageScaleOfWorld,
                              ActionBarRouter actionBar, TelegraphBudget budget) {
        this(plugin, combat, elementBias, abilityDamageScaleOfWorld, actionBar, budget,
                () -> true, () -> ActionBarRouter.BarStyle.BLOCK);
    }

    /**
     * 機構10（致命予約の原子化）・機構3（バー表記切替）込みの正準コンストラクタ（2026-09-04）。
     *
     * @param telegraphLethalAtomic {@code combat/mob-abilities.yml} の {@code telegraph-lethal-atomic}
     *                              を毎回読み直す供給元。
     * @param barStyle              同 {@code telegraph-bar-style} を毎回読み直す供給元。
     */
    public MobAbilityExecutor(Plugin plugin, SymmetricCombatService combat,
                              java.util.function.DoubleSupplier elementBias,
                              ToDoubleFunction<String> abilityDamageScaleOfWorld,
                              ActionBarRouter actionBar, TelegraphBudget budget,
                              BooleanSupplier telegraphLethalAtomic,
                              Supplier<ActionBarRouter.BarStyle> barStyle) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combat = Objects.requireNonNull(combat, "combat");
        this.elementBias = Objects.requireNonNull(elementBias, "elementBias");
        this.abilityDamageScaleOfWorld = Objects.requireNonNull(abilityDamageScaleOfWorld,
                "abilityDamageScaleOfWorld");
        this.actionBar = Objects.requireNonNull(actionBar, "actionBar");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.telegraphLethalAtomic = Objects.requireNonNull(telegraphLethalAtomic, "telegraphLethalAtomic");
        this.barStyle = Objects.requireNonNull(barStyle, "barStyle");
    }

    /** {@link MobAbilityTask} が抽選段階で予告予算を照会するために公開する。 */
    public TelegraphBudget budget() {
        return budget;
    }

    /** {@code TrinityForge.java} が {@code SkillExpFeedbackService} と同じインスタンスを配るために公開する。 */
    /**
     * 抽選側のクールダウン台帳を受け取る（2026-09-04、機構8「中断」/機構7「空振り硬直」の準備）。
     * 中断された技の再詠唱ロックや空振り硬直の間合いは、抽選を止める側＝この台帳へ書くしかないため。
     * {@code MobAbilityTask} のコンストラクタが呼ぶ。未接続のときは null で、ロック系は黙って何もしない。
     */
    private MobAbilityCooldowns cooldowns;

    public void attachCooldowns(MobAbilityCooldowns cooldowns) {
        this.cooldowns = cooldowns;
    }

    public ActionBarRouter actionBar() {
        return actionBar;
    }

    // ------------------------------------------------------------------
    // 機構8: 中断（MobAbilityInterrupts から呼ばれる）
    // ------------------------------------------------------------------

    /** 進行中の詠唱があるか（{@link MobAbilityInterrupts} が毎回のダメージ通知を早期リターンするため）。 */
    boolean hasActiveCasts() {
        return !activeCasts.isEmpty();
    }

    /**
     * 術者がダメージを受けた（{@code MobAbilityCastDamageListener} 経由）。中断可能な詠唱を持つ
     * 術者だけを見て、自身の最大HPに対する累積被ダメージ割合が {@code interruptDamageFraction} を
     * 超えたら中断する。最大HPを読めない個体は判定できないので中断させない。
     */
    void onCasterDamaged(LivingEntity mob, double finalDamage) {
        if (mob == null || activeCasts.isEmpty()) {
            return;
        }
        ActiveCast cast = activeCasts.get(mob.getUniqueId());
        if (cast == null || !cast.ability.interruptible() || cast.maxHealthAtStart <= 0.0) {
            return;
        }
        cast.damageTaken += Math.max(0.0, finalDamage);
        if (cast.damageTaken >= cast.maxHealthAtStart * cast.ability.interruptDamageFraction()) {
            interruptCast(mob, cast);
        }
    }

    /** 術者がスタン（{@code stun_chance}）を受けた。中断可能な詠唱があれば即中断する。 */
    void onCasterStunned(LivingEntity mob) {
        if (mob == null || activeCasts.isEmpty()) {
            return;
        }
        ActiveCast cast = activeCasts.get(mob.getUniqueId());
        if (cast == null || !cast.ability.interruptible()) {
            return;
        }
        interruptCast(mob, cast);
    }

    private void interruptCast(LivingEntity mob, ActiveCast cast) {
        activeCasts.remove(mob.getUniqueId());
        cast.finish.accept("interrupted");
    }

    /** その術者の最大HP。読めない個体（属性なし・MockBukkit等）は -1 を返す。 */
    private static double readMaxHealth(LivingEntity mob) {
        try {
            org.bukkit.attribute.AttributeInstance max = mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            return max == null ? -1.0 : max.getValue();
        } catch (Throwable ignored) {
            return -1.0;
        }
    }

    // ------------------------------------------------------------------
    // テスト用フック（パッケージ非公開）
    // ------------------------------------------------------------------

    void setEntityLookup(EntityLookup lookup) {
        this.entityLookup = Objects.requireNonNull(lookup, "lookup");
    }

    void setLineOfSightCheck(BiPredicate<LivingEntity, Player> check) {
        this.lineOfSightCheck = Objects.requireNonNull(check, "check");
    }

    void setClock(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void setHitObserver(BiConsumer<Player, Boolean> observer) {
        this.hitObserver = observer == null ? (player, telegraphed) -> { } : observer;
    }

    /**
     * 型ごとの行動語（追加指示5）。アクションバーの予告行に {@code [語]} の形で前置する。
     * {@code Type.values()} 全部が語を持つことを {@code switch} の網羅性でコンパイル時に保証する。
     */
    public static String responseWordOf(MobAbility.Type type) {
        return switch (type) {
            case GROUND_SLAM, CHARGE, AURA -> "離れろ";
            case DELAYED_ZONE, FIXED_ZONE -> "床から退け";
            case BEAM, TELEPORT_STRIKE, PROJECTILE_VOLLEY, PROJECTILE_RAIN -> "横へ";
            case REPULSE, VORTEX_PULL -> "遮蔽へ";
            case SUMMON -> "止めろ";
        };
    }

    /**
     * 詠唱終了時に視線を再確認する型か（追加指示2）。<b>主対象を直接狙う技だけ</b>課す。
     * 自分中心の AoE（{@code GROUND_SLAM}/{@code AURA}/{@code REPULSE}/{@code VORTEX_PULL}）と
     * {@code DELAYED_ZONE}（印は発動時点で固定済み。「柱に隠れて消す」という第2の回避解を追加しない）は
     * 課さない。{@code CHARGE}/{@code SUMMON} は主対象を貫通させる技ではないので同様に課さない。
     */
    private static boolean lineOfSightRequired(MobAbility.Type type) {
        return switch (type) {
            case BEAM, TELEPORT_STRIKE, PROJECTILE_VOLLEY, PROJECTILE_RAIN -> true;
            default -> false;
        };
    }

    /**
     * 技の実効「魔法割合」。<b>技は自分の属性へ 100% 寄せない</b> —— そのモブの
     * {@code magic-ratio} を土台にして、{@code damage-type} のぶんだけ自分の属性側へ引き寄せる。
     */
    public static double effectiveMagicRatio(double mobMagicRatio, DamageType abilityType, double bias) {
        double r = Math.max(0.0, Math.min(1.0, mobMagicRatio));
        double b = Math.max(0.0, Math.min(1.0, bias));
        return abilityType == DamageType.MAGICAL ? r + (1.0 - r) * b : r * (1.0 - b);
    }

    /**
     * 技の {@code damage-percent} にワールド倍率を掛けた実効倍率。
     * 非正・非有限のワールド倍率は「未設定」と同じ 1.0 扱い（技を 0 倍にして消さない）。
     */
    static double scaledAbilityPercent(double damagePercent, double worldScale) {
        double scale = (!Double.isFinite(worldScale) || worldScale <= 0.0) ? 1.0 : worldScale;
        return damagePercent * scale;
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
            if (ability.type() == MobAbility.Type.DELAYED_ZONE) {
                return delayedZone(mob, target, ability);
            }
            if (ability.type() == MobAbility.Type.FIXED_ZONE) {
                return fixedZone(mob, target, ability);
            }
            int ticks = ability.telegraphTicks();
            if (ticks <= 0) {
                announce(mob, ability);
                playEffects(mob.getLocation(), ability);
                return resolve(mob, target, ability, false, buildSnapshot(mob, target, ability));
            }
            return startCast(mob, target, ability, ticks);
        } catch (RuntimeException ex) {
            // 設定ミス(未知のEntityType等)を毎tick叫ばせない。1件読み飛ばして次のモブへ。
            return false;
        }
    }

    /** {@code cast-seconds > 0} かつ {@code DELAYED_ZONE} 以外の型を解決する（型ごとの switch 本体）。 */
    private boolean resolve(LivingEntity mob, Player target, MobAbility ability, boolean telegraphed,
                            CastSnapshot snapshot) {
        Location anchor = snapshot.anchor();
        return switch (ability.type()) {
            case GROUND_SLAM -> groundSlam(mob, ability, anchor, telegraphed);
            case PROJECTILE_VOLLEY -> projectileVolley(mob, target, ability);
            case PROJECTILE_RAIN -> projectileRain(mob, target, ability);
            case CHARGE -> charge(mob, target, ability, telegraphed);
            case AURA -> aura(mob, ability, anchor, telegraphed);
            case TELEPORT_STRIKE -> teleportStrike(mob, target, ability, anchor, telegraphed);
            case BEAM -> beam(mob, ability, snapshot, telegraphed);
            case SUMMON -> summon(mob, ability);
            case REPULSE -> repulse(mob, ability, anchor, telegraphed);
            case VORTEX_PULL -> vortexPull(mob, ability, anchor, telegraphed);
            case DELAYED_ZONE -> throw new IllegalStateException(
                    "DELAYED_ZONE is handled by #delayedZone, not #resolve");
            case FIXED_ZONE -> throw new IllegalStateException(
                    "FIXED_ZONE is handled by #fixedZone, not #resolve");
        };
    }

    // ------------------------------------------------------------------
    // 機構1(補助): 幾何のスナップショット
    // ------------------------------------------------------------------

    /**
     * 詠唱開始時点（即時経路では解決の直前）に凍結する幾何情報（追加指示1）。
     * 描画（{@link #renderTelegraph}）も解決（{@link #resolve}）も、ここから座標を取る。
     *
     * @param origin {@code BEAM} の始点（術者の目線）。他の型では術者の足元と同じ値。
     * @param dir    {@code BEAM} の向き（正規化済み）。他の型では {@code null}。
     * @param anchor 判定・演出の中心。{@code TELEPORT_STRIKE} は出現予定地点（対象の背後）、
     *               {@code DELAYED_ZONE} は印の位置、他は術者の足元。
     * @param length {@code BEAM} の壁で止めた終点までの長さ。他の型では 0。
     */
    private record CastSnapshot(Location origin, Vector dir, Location anchor, double length) {
    }

    private CastSnapshot buildSnapshot(LivingEntity mob, Player target, MobAbility ability) {
        return switch (ability.type()) {
            case BEAM -> {
                Location origin = mob.getEyeLocation().clone();
                BeamGeometry geometry = beamGeometry(mob, target, ability);
                yield new CastSnapshot(origin, geometry.direction(), origin, geometry.length());
            }
            case TELEPORT_STRIKE -> {
                Location origin = mob.getLocation().clone();
                yield new CastSnapshot(origin, null, behindLocation(target), 0.0);
            }
            case DELAYED_ZONE, FIXED_ZONE -> {
                Location origin = mob.getLocation().clone();
                yield new CastSnapshot(origin, null, target.getLocation().clone(), 0.0);
            }
            default -> {
                Location origin = mob.getLocation().clone();
                yield new CastSnapshot(origin, null, origin.clone(), 0.0);
            }
        };
    }

    // ------------------------------------------------------------------
    // 機構2: 詠唱ループ
    // ------------------------------------------------------------------

    /**
     * 予約の一括確保（機構10「致命予約の原子化」、2026-09-04）。主対象は必須。それ以外の脅威圏内の
     * viewer は、致命技かつ {@code telegraph-lethal-atomic} が true のときだけ<b>全員ぶん取れなければ
     * 撃たない</b>（取れた分は release して空を返す）。非致命はベスト・エフォート（取れた分だけ使う）。
     */
    private Optional<List<TelegraphBudget.Reservation>> reserveAll(LivingEntity mob, Player target,
                                                                    MobAbility ability, long resolveAtMillis,
                                                                    CastSnapshot snapshot) {
        List<Player> viewers = threatZoneViewers(mob, target, ability, snapshot);
        boolean atomic = ability.lethal() && telegraphLethalAtomic.getAsBoolean();
        List<TelegraphBudget.Reservation> reservations = new ArrayList<>();
        Optional<TelegraphBudget.Reservation> primary = budget.tryReserve(
                target.getUniqueId(), mob.getUniqueId(), ability.id(), ability.lethal(), resolveAtMillis);
        if (primary.isEmpty()) {
            return Optional.empty();
        }
        reservations.add(primary.get());
        for (Player viewer : viewers) {
            if (viewer.equals(target)) {
                continue;
            }
            Optional<TelegraphBudget.Reservation> r = budget.tryReserve(viewer.getUniqueId(), mob.getUniqueId(),
                    ability.id(), ability.lethal(), resolveAtMillis);
            if (r.isPresent()) {
                reservations.add(r.get());
            } else if (atomic) {
                for (TelegraphBudget.Reservation done : reservations) {
                    budget.release(done);
                }
                return Optional.empty();
            }
        }
        return Optional.of(reservations);
    }

    /**
     * 予告付きの技（{@code DELAYED_ZONE} / {@code FIXED_ZONE} 以外）を開始する。予約が取れなければ
     * 何もせず false。予約が取れたら1tick間隔のループを開始し、その時点で true を返す（クールダウンを消費させる）。
     */
    private boolean startCast(LivingEntity mob, Player target, MobAbility ability, int ticks) {
        CastSnapshot snapshot = buildSnapshot(mob, target, ability);
        if (ability.type() == MobAbility.Type.BEAM && snapshot.dir() == null) {
            return false;
        }
        long totalMillis = ticks * MILLIS_PER_TICK;
        long resolveAtMillis = clock.getAsLong() + totalMillis;
        Optional<List<TelegraphBudget.Reservation>> reserved =
                reserveAll(mob, target, ability, resolveAtMillis, snapshot);
        if (reserved.isEmpty()) {
            return false;
        }
        List<TelegraphBudget.Reservation> reservations = reserved.get();
        String key = mob.getUniqueId() + ":" + ability.id();
        float startYaw = mob.getLocation().getYaw();
        float startPitch = mob.getLocation().getPitch();
        java.util.Set<java.util.UUID> notified = new java.util.HashSet<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        Consumer<String> finish = reason -> {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            activeCasts.remove(mob.getUniqueId());
            for (TelegraphBudget.Reservation r : reservations) {
                budget.release(r);
            }
            // 終了通知は「終了時点で脅威圏内にいる人」ではなく【更新を受け取った人全員】へ送る。
            // 予告中に圏外へ歩いた人へ送らないと、その人の画面には最終フレームが残り、
            // ルータの期限切れ(+1秒)まで EXP 等も捨てられ続ける。
            java.util.Set<java.util.UUID> endTargets = new java.util.HashSet<>(notified);
            for (Player viewer : threatZoneViewers(mob, target, ability, snapshot)) {
                endTargets.add(viewer.getUniqueId());
            }
            for (java.util.UUID id : endTargets) {
                Player viewer = org.bukkit.Bukkit.getPlayer(id);
                if (viewer != null) {
                    actionBar.telegraphEnd(viewer, key);
                }
            }
            if (("misfire".equals(reason) || "error".equals(reason))
                    && target.isValid() && target.isOnline() && !target.isDead()) {
                actionBar.notice(target, misfireNotice());
            } else if ("interrupted".equals(reason)) {
                notifyInterrupted(mob, ability, endTargets);
            }
        };
        activeCasts.put(mob.getUniqueId(), new ActiveCast(ability, finish, readMaxHealth(mob)));
        playSound(mob.getLocation(), ability); // 詠唱開始音(1回だけ)
        new BukkitRunnable() {
            private int elapsed = 0;
            private boolean pingPlayed = false;

            @Override
            public void run() {
                try {
                    if (castInterrupted(mob, target)) {
                        cancel();
                        finish.accept("misfire");
                        return;
                    }
                    mob.setRotation(startYaw, startPitch);
                    elapsed++;
                    if (elapsed % TELEGRAPH_INTERVAL_TICKS == 0) {
                        renderTelegraph(mob, target, ability, snapshot, elapsed);
                        long remaining = Math.max(0L, totalMillis - elapsed * MILLIS_PER_TICK);
                        notified.addAll(updateTelegraphActionBar(mob, target, ability, snapshot, key,
                                resolveAtMillis, remaining, totalMillis));
                    }
                    if (!pingPlayed && ticks - elapsed == PING_LEAD_TICKS) {
                        pingPlayed = true;
                        playPing(threatZoneViewers(mob, target, ability, snapshot));
                    }
                    if (elapsed >= ticks) {
                        cancel();
                        boolean losOk = !lineOfSightRequired(ability.type()) || lineOfSightCheck.test(mob, target);
                        if (!losOk) {
                            finish.accept("misfire");
                            return;
                        }
                        finish.accept("resolved");
                        playEffects(snapshot.anchor(), ability);
                        syncHitCounter = 0;
                        resolve(mob, target, ability, true, snapshot);
                        if (ability.type() != MobAbility.Type.CHARGE && ability.type() != MobAbility.Type.AURA) {
                            maybeWhiffStagger(mob, ability, snapshot, target, syncHitCounter > 0);
                        }
                    }
                } catch (RuntimeException ex) {
                    cancel();
                    finish.accept("error");
                    logCastError(ability.id(), ex);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    /**
     * 中断（機構8）の通知: viewer 全員へ「詠唱中断」とその場での不発音を出し、
     * 同じ技に {@code interruptLockoutSeconds} のロックを掛ける。
     */
    private void notifyInterrupted(LivingEntity mob, MobAbility ability, java.util.Set<java.util.UUID> viewerIds) {
        Component notice = MiniMessage.miniMessage().deserialize("<green>詠唱中断</green>");
        for (java.util.UUID id : viewerIds) {
            Player viewer = org.bukkit.Bukkit.getPlayer(id);
            if (viewer != null) {
                actionBar.notice(viewer, notice);
                viewer.playSound(viewer.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.2f);
            }
        }
        if (cooldowns != null) {
            cooldowns.arm(mob.getUniqueId(), ability.id(), Math.round(ability.interruptLockoutSeconds() * 1000.0));
        }
    }

    /**
     * 空振り硬直（機構7、2026-09-04）。詠唱付きの技が誰にも当たらなかったとき、術者へ短い硬直を課す。
     * 同じモブには {@value #WHIFF_STAGGER_LOCKOUT_MILLIS}ms に1回まで（連発防止）。
     * 雑魚に付けない規約は yml 側（{@code whiff-stagger-seconds} を書かない）の責務で、ここでは課さない。
     */
    private void maybeWhiffStagger(LivingEntity mob, MobAbility ability, CastSnapshot snapshot, Player target,
                                   boolean hitAnyone) {
        if (hitAnyone || ability.whiffStaggerSeconds() <= 0.0) {
            return;
        }
        long now = clock.getAsLong();
        Long last = lastStaggerMillis.get(mob.getUniqueId());
        if (last != null && now - last < WHIFF_STAGGER_LOCKOUT_MILLIS) {
            return;
        }
        lastStaggerMillis.put(mob.getUniqueId(), now);
        int ticks = (int) Math.round(ability.whiffStaggerSeconds() * 20.0);
        if (mob.isValid()) {
            // amplifier 255 で実質的に移動停止させる(鈍足そのものではなく「体勢を崩した」演出)。
            mob.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 255, false, true, true));
        }
        if (cooldowns != null && cooldowns.ready(mob.getUniqueId(), MobAbilityTask.GLOBAL_GAP_KEY)) {
            cooldowns.arm(mob.getUniqueId(), MobAbilityTask.GLOBAL_GAP_KEY, (long) ticks * MILLIS_PER_TICK);
        }
        Component notice = MiniMessage.miniMessage().deserialize("<yellow>体勢を崩した</yellow>");
        for (Player viewer : threatZoneViewers(mob, target, ability, snapshot)) {
            actionBar.notice(viewer, notice);
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.0f, 0.8f);
        }
    }

    /**
     * 詠唱の中断条件（機構2「主対象を再確認する」）。死亡・退出・ワールド変更のいずれかで不発にする。
     * 視線は<b>詠唱終了時にのみ、対象を狙う型だけ</b>見る（{@link #lineOfSightRequired}、追加指示2）。
     */
    private boolean castInterrupted(LivingEntity mob, Player target) {
        if (!mob.isValid() || mob.isDead()) {
            return true;
        }
        if (!target.isValid() || !target.isOnline() || target.isDead()) {
            return true;
        }
        return target.getWorld() != mob.getWorld();
    }

    private static Component misfireNotice() {
        return MiniMessage.miniMessage().deserialize("<gray>詠唱不発</gray>");
    }

    /** @return 今回更新を送ったプレイヤーの UUID（終了通知の宛先として呼び手が蓄積する） */
    private List<java.util.UUID> updateTelegraphActionBar(LivingEntity mob, Player target, MobAbility ability,
                                                          CastSnapshot snapshot, String key, long resolveAtMillis,
                                                          long remainingMillis, long totalMillis) {
        Component line = ActionBarRouter.telegraphLine(responseWordOf(ability.type()), ability.displayName(),
                ability.id(), remainingMillis, totalMillis, barStyle.get());
        List<java.util.UUID> sent = new ArrayList<>();
        for (Player viewer : threatZoneViewers(mob, target, ability, snapshot)) {
            actionBar.telegraphUpdate(viewer, key, ability.lethal(), resolveAtMillis, line);
            sent.add(viewer.getUniqueId());
        }
        return sent;
    }

    private void playPing(List<Player> viewers) {
        for (Player viewer : viewers) {
            viewer.playSound(viewer.getLocation(), IMPACT_WARNING_SOUND, 1.0f, 1.5f);
        }
    }

    private static void logCastError(String abilityId, RuntimeException ex) {
        long now = System.currentTimeMillis();
        Long last = lastErrorLogMillis.get(abilityId);
        if (last != null && now - last < ERROR_LOG_INTERVAL_MILLIS) {
            return;
        }
        lastErrorLogMillis.put(abilityId, now);
        java.util.logging.Logger.getLogger(MobAbilityExecutor.class.getName())
                .warning("mob-ability '" + abilityId + "' の詠唱ループで例外: " + ex);
    }

    // ------------------------------------------------------------------
    // 攻撃の型
    // ------------------------------------------------------------------

    private boolean groundSlam(LivingEntity mob, MobAbility ability, Location anchor, boolean telegraphed) {
        for (Player victim : playersNear(anchor, ability.radius(), ability.verticalRadius())) {
            applyHit(mob, victim, ability, telegraphed);
            pushAway(anchor, victim, ability.knockback());
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

    private boolean charge(LivingEntity mob, Player target, MobAbility ability, boolean telegraphed) {
        Vector toTarget = target.getLocation().toVector().subtract(mob.getLocation().toVector());
        if (toTarget.lengthSquared() < 1.0e-6) {
            return false;
        }
        // 上向き成分を少し足すのは「地面に引っかかって前に出ない」のを避けるため。
        Vector velocity = toTarget.normalize().multiply(1.4).setY(0.35);
        mob.setVelocity(velocity);
        // 突進の当たり判定は着地後。突進中に判定すると発動と同時に当たるだけで「避ける」余地が無い。
        // 突進は物理移動そのものが技なので、スナップショットではなく実際に着地した位置で判定する。
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!mob.isValid()) {
                    return;
                }
                playEffects(mob.getLocation(), ability);
                for (Player victim : playersNear(mob.getLocation(), Math.max(1.5, ability.radius()),
                        ability.verticalRadius())) {
                    applyHit(mob, victim, ability, telegraphed);
                    pushAway(mob.getLocation(), victim, ability.knockback());
                }
            }
        }.runTaskLater(plugin, 12L);
        return true;
    }

    private boolean aura(LivingEntity mob, MobAbility ability, Location anchor, boolean telegraphed) {
        int totalTicks = ability.durationTicks();
        if (totalTicks <= 0) {
            return groundSlam(mob, ability, anchor, telegraphed);
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
                playEffects(anchor, ability);
                for (Player victim : playersNear(anchor, ability.radius(), ability.verticalRadius())) {
                    applyHit(mob, victim, ability, telegraphed);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
        return true;
    }

    private boolean teleportStrike(LivingEntity mob, Player target, MobAbility ability, Location anchor,
                                   boolean telegraphed) {
        playEffects(mob.getLocation(), ability);
        mob.teleport(anchor);
        playEffects(anchor, ability);
        applyHit(mob, target, ability, telegraphed);
        pushAway(anchor, target, ability.knockback());
        return true;
    }

    /** {@code TELEPORT_STRIKE} の出現予定地点（対象の背後）。予告描画とも共有する。 */
    private static Location behindLocation(Player target) {
        Location behind = target.getLocation().clone()
                .subtract(target.getLocation().getDirection().setY(0).normalize().multiply(1.5));
        behind.setY(target.getLocation().getY());
        // 転移先が壁の中でも teleport は成功してしまうので、足元が固いかだけ見る。
        if (!passable(behind)) {
            behind = target.getLocation();
        }
        return behind;
    }

    /**
     * {@code Block#isPassable()} を例外安全に包んだもの。MockBukkit の {@code BlockMock} は未実装
     * ({@code UnimplementedOperationException}、JUnit の {@code TestAbortedException} 継承)で、
     * 素で呼ぶと実サーバでは起きない例外が {@link #execute} の try/catch に飲まれて「技が丸ごと
     * 不発扱い」になる（{@code hasLineOfSight} と同じ既知の罠）。例外時は「通れる」扱いにする —
     * 遮蔽判定を課さない方が、無言で技が死ぬより安全。
     */
    private static boolean passable(Location location) {
        try {
            return location.getBlock().isPassable();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** {@code BEAM} の向きと、壁で止まる位置までの長さ（判定・演出の両方で共有する）。 */
    private record BeamGeometry(Vector direction, double length) {
    }

    private BeamGeometry beamGeometry(LivingEntity mob, Player target, MobAbility ability) {
        Vector raw = target.getEyeLocation().toVector().subtract(mob.getEyeLocation().toVector());
        if (raw.lengthSquared() < 1.0e-6) {
            return new BeamGeometry(null, 0.0);
        }
        Vector direction = raw.normalize();
        int steps = Math.max(1, ability.count());
        Location from = mob.getEyeLocation();
        double length = steps * AbilityShapes.LINE_STEP;
        for (int i = 1; i <= steps; i++) {
            Location next = from.clone().add(direction.clone().multiply(i * AbilityShapes.LINE_STEP));
            if (!passable(next)) {
                length = (i - 1) * AbilityShapes.LINE_STEP; // 壁で止まる(遮蔽が意味を持つように)
                break;
            }
        }
        return new BeamGeometry(direction, length);
    }

    private boolean beam(LivingEntity mob, MobAbility ability, CastSnapshot snapshot, boolean telegraphed) {
        if (snapshot.dir() == null) {
            return false;
        }
        double thickness = Math.max(0.5, ability.radius());
        Location from = snapshot.origin();
        if (snapshot.length() > 0.0) {
            Location center = from.clone().add(snapshot.dir().clone().multiply(snapshot.length() / 2.0));
            double halfExtent = snapshot.length() / 2.0 + thickness;
            List<Player> alreadyHit = new ArrayList<>();
            for (Entity entity : entityLookup.nearby(center, halfExtent, halfExtent, halfExtent)) {
                if (entity instanceof Player victim && victim.isValid() && !alreadyHit.contains(victim)
                        && AbilityShapes.hitsLine(from.toVector(), snapshot.dir(), snapshot.length(), thickness,
                                playerCenter(victim))) {
                    alreadyHit.add(victim);
                    applyHit(mob, victim, ability, telegraphed);
                }
            }
        }
        for (Vector offset : AbilityShapes.lineOffsets(snapshot.dir(), snapshot.length(), AbilityShapes.LINE_STEP)) {
            playEffects(from.clone().add(offset), ability);
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
     * 複数人技の被害者ごとの視線（設計正本「複数人技の視線」、2026-09-04）。壁の向こうにいる味方まで
     * 巻き込まないよう、各被害者について {@link #lineOfSightCheck} が通らない相手は外す。
     */
    private boolean repulse(LivingEntity mob, MobAbility ability, Location anchor, boolean telegraphed) {
        for (Player victim : playersNear(anchor, ability.radius(), ability.verticalRadius())) {
            if (!lineOfSightCheck.test(mob, victim)) {
                continue;
            }
            applyHit(mob, victim, ability, telegraphed);
            if (ability.knockback() > 0.0) {
                // ダメージのあとに置くこと。victim.damage() 由来のバニラノックバックを上書きするため。
                victim.setVelocity(repulseVelocity(anchor.toVector(),
                        victim.getLocation().toVector(), ability.knockback()));
            }
        }
        return true;
    }

    /**
     * 周囲のプレイヤーを自分の方へ引きずり込む（{@link #repulse} の逆向き）。被害者ごとの視線判定も同様。
     */
    private boolean vortexPull(LivingEntity mob, MobAbility ability, Location anchor, boolean telegraphed) {
        for (Player victim : playersNear(anchor, ability.radius(), ability.verticalRadius())) {
            if (!lineOfSightCheck.test(mob, victim)) {
                continue;
            }
            applyHit(mob, victim, ability, telegraphed);
            if (ability.knockback() > 0.0) {
                victim.setVelocity(pullVelocity(anchor.toVector(),
                        victim.getLocation().toVector(), ability.knockback()));
            }
        }
        return true;
    }

    /**
     * 対象の足元へ印を置き、{@link MobAbility#delayTicks()} 後にその地点へ着弾する。
     *
     * <p><b>印の位置は発動時点で固定</b>する（対象を追尾させない）。追尾させると回避手段が
     * 「射程外へ逃げる」しか無くなり、予告を出す意味が消えるため。視線は見ない（追加指示2）が、
     * 術者の死亡・対象の退出/死亡/ワールド変更では他の自分中心技と同様に不発にする。
     */
    private boolean delayedZone(LivingEntity mob, Player target, MobAbility ability) {
        CastSnapshot snapshot = buildSnapshot(mob, target, ability);
        int delay = ability.delayTicks();
        long totalMillis = delay * MILLIS_PER_TICK;
        long resolveAtMillis = clock.getAsLong() + totalMillis;
        Optional<List<TelegraphBudget.Reservation>> reserved =
                reserveAll(mob, target, ability, resolveAtMillis, snapshot);
        if (reserved.isEmpty()) {
            return false;
        }
        List<TelegraphBudget.Reservation> reservations = reserved.get();
        String key = mob.getUniqueId() + ":" + ability.id();
        double radius = Math.max(1.0, ability.radius());
        java.util.Set<java.util.UUID> notified = new java.util.HashSet<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        Consumer<String> finish = reason -> {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            activeCasts.remove(mob.getUniqueId());
            for (TelegraphBudget.Reservation r : reservations) {
                budget.release(r);
            }
            // 終了通知は「終了時点で脅威圏内にいる人」ではなく【更新を受け取った人全員】へ送る。
            // 予告中に圏外へ歩いた人へ送らないと、その人の画面には最終フレームが残り、
            // ルータの期限切れ(+1秒)まで EXP 等も捨てられ続ける。
            java.util.Set<java.util.UUID> endTargets = new java.util.HashSet<>(notified);
            for (Player viewer : threatZoneViewers(mob, target, ability, snapshot)) {
                endTargets.add(viewer.getUniqueId());
            }
            for (java.util.UUID id : endTargets) {
                Player viewer = org.bukkit.Bukkit.getPlayer(id);
                if (viewer != null) {
                    actionBar.telegraphEnd(viewer, key);
                }
            }
            if (("misfire".equals(reason) || "error".equals(reason))
                    && target.isValid() && target.isOnline() && !target.isDead()) {
                actionBar.notice(target, misfireNotice());
            } else if ("interrupted".equals(reason)) {
                notifyInterrupted(mob, ability, endTargets);
            }
        };
        activeCasts.put(mob.getUniqueId(), new ActiveCast(ability, finish, readMaxHealth(mob)));
        playSound(mob.getLocation(), ability);
        new BukkitRunnable() {
            private int elapsed = 0;
            private boolean pingPlayed = false;

            @Override
            public void run() {
                try {
                    if (castInterrupted(mob, target)) {
                        cancel();
                        finish.accept("misfire");
                        return;
                    }
                    if (elapsed >= delay) {
                        cancel();
                        finish.accept("resolved");
                        playEffects(snapshot.anchor(), ability);
                        List<Player> victims = playersNear(snapshot.anchor(), radius, ability.verticalRadius());
                        for (Player victim : victims) {
                            applyHit(mob, victim, ability, true);
                            if (ability.knockback() > 0.0) {
                                victim.setVelocity(repulseVelocity(snapshot.anchor().toVector(),
                                        victim.getLocation().toVector(), ability.knockback()));
                            }
                        }
                        maybeWhiffStagger(mob, ability, snapshot, target, !victims.isEmpty());
                        return;
                    }
                    elapsed += TELEGRAPH_INTERVAL_TICKS;
                    // 予告。ここが見えないと「避けられる技」が成立しない。
                    renderTelegraph(mob, target, ability, snapshot, elapsed);
                    if (!pingPlayed && delay - elapsed <= PING_LEAD_TICKS) {
                        pingPlayed = true;
                        playPing(threatZoneViewers(mob, target, ability, snapshot));
                    }
                    long remaining = Math.max(0L, totalMillis - elapsed * MILLIS_PER_TICK);
                    notified.addAll(updateTelegraphActionBar(mob, target, ability, snapshot, key,
                            resolveAtMillis, remaining, totalMillis));
                } catch (RuntimeException ex) {
                    cancel();
                    finish.accept("error");
                    logCastError(ability.id(), ex);
                }
            }
        }.runTaskTimer(plugin, 0L, TELEGRAPH_INTERVAL_TICKS);
        return true;
    }

    /**
     * 床に固定された持続領域（機構「固定領域」、2026-09-04）。詠唱は {@code DELAYED_ZONE} と同じ形
     * （対象の足元に固定した anchor へ輪の予告）だが、展開後は術者が死んでも領域自体が残り、
     * {@code duration-seconds}（既定 {@value #FIXED_ZONE_DEFAULT_DURATION_SECONDS} 秒）の間
     * 1 秒ごとに判定を刻む。予告予算は<b>展開までを予告として数え、展開時に解放する</b>。
     */
    private boolean fixedZone(LivingEntity mob, Player target, MobAbility ability) {
        CastSnapshot snapshot = buildSnapshot(mob, target, ability);
        int ticks = ability.telegraphTicks();
        long totalMillis = ticks * MILLIS_PER_TICK;
        long resolveAtMillis = clock.getAsLong() + totalMillis;
        Optional<List<TelegraphBudget.Reservation>> reserved =
                reserveAll(mob, target, ability, resolveAtMillis, snapshot);
        if (reserved.isEmpty()) {
            return false;
        }
        List<TelegraphBudget.Reservation> reservations = reserved.get();
        String key = mob.getUniqueId() + ":" + ability.id();
        java.util.Set<java.util.UUID> notified = new java.util.HashSet<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        Consumer<String> finish = reason -> {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            activeCasts.remove(mob.getUniqueId());
            for (TelegraphBudget.Reservation r : reservations) {
                budget.release(r);
            }
            java.util.Set<java.util.UUID> endTargets = new java.util.HashSet<>(notified);
            for (Player viewer : threatZoneViewers(mob, target, ability, snapshot)) {
                endTargets.add(viewer.getUniqueId());
            }
            for (java.util.UUID id : endTargets) {
                Player viewer = org.bukkit.Bukkit.getPlayer(id);
                if (viewer != null) {
                    actionBar.telegraphEnd(viewer, key);
                }
            }
            if (("misfire".equals(reason) || "error".equals(reason))
                    && target.isValid() && target.isOnline() && !target.isDead()) {
                actionBar.notice(target, misfireNotice());
            } else if ("interrupted".equals(reason)) {
                notifyInterrupted(mob, ability, endTargets);
            }
        };
        activeCasts.put(mob.getUniqueId(), new ActiveCast(ability, finish, readMaxHealth(mob)));
        playSound(mob.getLocation(), ability);
        new BukkitRunnable() {
            private int elapsed = 0;
            private boolean pingPlayed = false;

            @Override
            public void run() {
                try {
                    if (castInterrupted(mob, target)) {
                        cancel();
                        finish.accept("misfire");
                        return;
                    }
                    elapsed++;
                    if (elapsed % TELEGRAPH_INTERVAL_TICKS == 0) {
                        renderTelegraph(mob, target, ability, snapshot, elapsed);
                        long remaining = Math.max(0L, totalMillis - elapsed * MILLIS_PER_TICK);
                        notified.addAll(updateTelegraphActionBar(mob, target, ability, snapshot, key,
                                resolveAtMillis, remaining, totalMillis));
                    }
                    if (!pingPlayed && ticks - elapsed == PING_LEAD_TICKS) {
                        pingPlayed = true;
                        playPing(threatZoneViewers(mob, target, ability, snapshot));
                    }
                    if (elapsed >= ticks) {
                        cancel();
                        // 展開: ここで予告予算を解放する(finish="resolved")。展開後の領域そのものは
                        // 予告済みの一撃ではなく、床に固定された持続効果として別枠で扱う。
                        finish.accept("resolved");
                        playEffects(snapshot.anchor(), ability);
                        expandFixedZone(mob, target, ability, snapshot);
                    }
                } catch (RuntimeException ex) {
                    cancel();
                    finish.accept("error");
                    logCastError(ability.id(), ex);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    /**
     * {@code FIXED_ZONE} の展開後。1秒(20tick)ごとに判定・輪の再描画を行う。<b>術者が死んでも続ける</b>
     * ——床に固定された領域であることが本質なので、術者の生死をチェックしない（ワールドの有無だけ見る）。
     */
    private void expandFixedZone(LivingEntity mob, Player target, MobAbility ability, CastSnapshot snapshot) {
        double durationSeconds = ability.durationSeconds() <= 0.0
                ? FIXED_ZONE_DEFAULT_DURATION_SECONDS : ability.durationSeconds();
        int totalTicks = (int) Math.round(durationSeconds * 20.0);
        Location anchor = snapshot.anchor();
        double radius = Math.max(1.0, ability.radius());
        new BukkitRunnable() {
            private int elapsed = 0;
            private boolean firstTick = true;

            @Override
            public void run() {
                World world = anchor.getWorld();
                if (world == null || elapsed >= totalTicks) {
                    cancel();
                    return;
                }
                elapsed += 20;
                Particle particle = particle(ability.particle());
                List<Player> viewers = playersNear(anchor, THREAT_ZONE_HORIZONTAL, THREAT_ZONE_HORIZONTAL);
                drawRingTo(viewers, anchor, radius, particle == null ? Particle.END_ROD : particle);
                List<Player> victims = playersNear(anchor, radius, ability.verticalRadius());
                for (Player victim : victims) {
                    applyHit(mob, victim, ability, true);
                }
                if (firstTick) {
                    firstTick = false;
                    maybeWhiffStagger(mob, ability, snapshot, target, !victims.isEmpty());
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    // ------------------------------------------------------------------
    // 機構3: 予告レンダラ
    // ------------------------------------------------------------------

    /**
     * 予告演出。判定と同じ {@link AbilityShapes} から座標を得るので、見えている輪/線と
     * 実際の判定が食い違わない。受信者は{@link #threatZoneViewers}（追加指示4：全ワールド放送ではなく
     * 主対象＋脅威圏内＋anchorから水平12m以内へ {@code player.spawnParticle} で個別送信）。
     */
    private void renderTelegraph(LivingEntity mob, Player target, MobAbility ability, CastSnapshot snapshot,
                                 int elapsed) {
        Particle particle = particle(ability.particle());
        if (particle == null) {
            particle = Particle.END_ROD;
        }
        List<Player> viewers = threatZoneViewers(mob, target, ability, snapshot);
        if (viewers.isEmpty()) {
            return;
        }
        if (ability.type() == MobAbility.Type.BEAM) {
            if (snapshot.dir() == null || snapshot.length() <= 0.0) {
                return;
            }
            double thickness = Math.max(0.5, ability.radius());
            renderBeamRails(viewers, snapshot.origin(), snapshot.dir(), snapshot.length(), thickness, particle);
            return;
        }
        Location anchor = snapshot.anchor();
        switch (ability.type()) {
            case VORTEX_PULL, REPULSE -> {
                drawRingTo(viewers, anchor, ability.radius(), particle);
                for (Player p : playersNear(anchor, ability.radius(), ability.verticalRadius())) {
                    drawLineTo(viewers, anchor, p.getLocation(), particle);
                }
            }
            case AURA -> {
                drawRingTo(viewers, anchor, ability.radius(), particle);
                drawLineTo(viewers, anchor, target.getLocation(), particle);
            }
            case PROJECTILE_VOLLEY, PROJECTILE_RAIN, SUMMON ->
                    drawRingTo(viewers, anchor, Math.max(1.5, ability.radius()), particle);
            case DELAYED_ZONE -> {
                drawRingTo(viewers, anchor, Math.max(1.0, ability.radius()), particle);
                // 中心の点を10tickごとに点滅させる(追加指示7)。この呼び出し自体が5tickごとなので、
                // 呼ばれた回数が偶数回目のときだけ光らせれば10tick周期になる。
                if ((elapsed / TELEGRAPH_INTERVAL_TICKS) % 2 == 0) {
                    for (Player viewer : viewers) {
                        viewer.spawnParticle(particle, anchor, 1, 0.0, 0.0, 0.0, 0.0);
                    }
                }
            }
            // FIXED_ZONE は輪のみ(中心の点滅は不要)。AURA と違い術者を追従しないので中心への線も引かない。
            case FIXED_ZONE -> drawRingTo(viewers, anchor, Math.max(1.0, ability.radius()), particle);
            default -> drawRingTo(viewers, anchor, ability.radius(), particle);
        }
    }

    /** 輪郭だけの円（内側は塗らない）。座標は {@link AbilityShapes#ringOffsets(double)} と共有する。 */
    private void drawRingTo(List<Player> viewers, Location anchor, double radius, Particle particle) {
        World world = anchor.getWorld();
        if (world == null) {
            return;
        }
        for (double[] offset : AbilityShapes.ringOffsets(radius)) {
            Location point = new Location(world, anchor.getX() + offset[0], anchor.getY() + 0.1,
                    anchor.getZ() + offset[1]);
            for (Player viewer : viewers) {
                viewer.spawnParticle(particle, point, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /** 引き寄せ・吹き飛ばし・追従領域が使う「術者から相手へ」の1m刻みの線。 */
    private void drawLineTo(List<Player> viewers, Location from, Location to, Particle particle) {
        Vector raw = to.toVector().subtract(from.toVector());
        if (raw.lengthSquared() < 1.0e-6) {
            return;
        }
        double length = raw.length();
        for (Vector offset : AbilityShapes.lineOffsets(raw, length, AbilityShapes.LINE_STEP)) {
            Location point = from.clone().add(offset);
            for (Player viewer : viewers) {
                viewer.spawnParticle(particle, point, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /**
     * {@code BEAM} の予告演出（追加指示6）: 中心線は描かず、射線の左右 {@code ±thickness} の
     * 2本のレールだけを1m刻みで描く。レールの向きは {@code dir} をY軸で90度回した水平ベクトル。
     */
    private void renderBeamRails(List<Player> viewers, Location from, Vector dir, double length, double thickness,
                                 Particle particle) {
        Vector perp = rotateAroundY(dir, Math.PI / 2.0).setY(0.0);
        if (perp.lengthSquared() < 1.0e-6) {
            perp = new Vector(1.0, 0.0, 0.0);
        } else {
            perp = perp.normalize();
        }
        for (int sign : new int[] {-1, 1}) {
            Vector railOffset = perp.clone().multiply(sign * thickness);
            for (Vector offset : AbilityShapes.lineOffsets(dir, length, AbilityShapes.LINE_STEP)) {
                Location point = from.clone().add(offset).add(railOffset);
                for (Player viewer : viewers) {
                    viewer.spawnParticle(particle, point, 1, 0.0, 0.0, 0.0, 0.0);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 脅威圏の受信者選定（追加指示4）
    // ------------------------------------------------------------------

    /**
     * 予告のアクションバー更新・予告粒子を受け取るプレイヤー。<b>主対象は無条件で含む</b>。
     * それ以外は「形状の脅威圏内（{@link AbilityShapes#hitsCircle}/{@link AbilityShapes#hitsLine}
     * で実際に当たる位置にいる）」かつ「anchor から水平 {@value #THREAT_ZONE_HORIZONTAL}m 以内」
     * の両方を満たす者だけ。全ワールド半径32一律の {@code World#spawnParticle} 放送はしない。
     */
    private List<Player> threatZoneViewers(LivingEntity mob, Player target, MobAbility ability,
                                           CastSnapshot snapshot) {
        List<Player> out = new ArrayList<>();
        if (target.isValid() && target.isOnline() && !target.isDead()) {
            out.add(target);
        }
        Location anchor = snapshot.anchor();
        World world = anchor.getWorld();
        if (world == null) {
            return out;
        }
        for (Entity entity : entityLookup.nearby(anchor, THREAT_ZONE_HORIZONTAL, THREAT_ZONE_HORIZONTAL,
                THREAT_ZONE_HORIZONTAL)) {
            if (entity instanceof Player player && player.isValid() && !player.isDead() && !out.contains(player)
                    && horizontalDistance(anchor, player.getLocation()) <= THREAT_ZONE_HORIZONTAL
                    && withinThreatZone(ability, snapshot, player)) {
                out.add(player);
            }
        }
        return out;
    }

    private boolean withinThreatZone(MobAbility ability, CastSnapshot snapshot, Player player) {
        if (ability.type() == MobAbility.Type.BEAM) {
            if (snapshot.dir() == null || snapshot.length() <= 0.0) {
                return false;
            }
            double thickness = Math.max(0.5, ability.radius());
            return AbilityShapes.hitsLine(snapshot.origin().toVector(), snapshot.dir(), snapshot.length(),
                    thickness, playerCenter(player));
        }
        double radius = switch (ability.type()) {
            case PROJECTILE_VOLLEY, PROJECTILE_RAIN, SUMMON -> Math.max(1.5, ability.radius());
            case DELAYED_ZONE, FIXED_ZONE -> Math.max(1.0, ability.radius());
            default -> ability.radius();
        };
        return AbilityShapes.hitsCircle(snapshot.anchor(), radius, ability.verticalRadius(), player.getLocation());
    }

    private static double horizontalDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** {@code hitsLine} の判定用のプレイヤー座標（追加指示6: 足元ではなく体の中心）。 */
    private static Vector playerCenter(Player player) {
        Location loc = player.getLocation();
        return loc.toVector().add(new Vector(0.0, player.getHeight() / 2.0, 0.0));
    }

    // ------------------------------------------------------------------
    // 共通部品
    // ------------------------------------------------------------------

    /** その技の最終ダメージを TF のパイプラインで出して適用する。 */
    private void applyHit(LivingEntity mob, Player victim, MobAbility ability, boolean telegraphed) {
        syncHitCounter++;
        hitObserver.accept(victim, telegraphed);
        if (ability.damagePercent() <= 0.0) {
            applyEffects(victim, ability);
            return;
        }
        AttackStats attack = MobData.of(mob).attackStats();
        // 通常攻撃の刻印値に倍率を掛けたものを「この技の基礎値」にする。
        // 刻印が無いモブ(バニラ)は Bukkit の既定近接ダメージ相当を 2.0 として扱う。
        double base = attack.defaultDamage() != 0 ? attack.defaultDamage() : 2.0;
        World world = mob.getWorld();
        double worldScale = 1.0;
        if (world != null) {
            worldScale = abilityDamageScaleOfWorld.applyAsDouble(world.getName());
        }
        double abilityBase = base * scaledAbilityPercent(ability.damagePercent(), worldScale);
        // 2026-08-21(W-181): 技も【そのモブの magic-ratio を土台に】物理/魔法へ分割する。
        AttackStats scaled = attack.withDefaultDamage(abilityBase)
                .withMagicRatio(effectiveMagicRatio(attack.magicRatio(), ability.damageType(),
                        elementBias.getAsDouble()));
        // 2026-09-02(機構6): 予告済みの一撃は回避ロールを通さない(SymmetricCombatService側でNEVERへ差替)。
        double finalDamage = combat.physicalFinalDamageFromMob(mob, victim, abilityBase, scaled, telegraphed);
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

    /** {@code announce} 用（即時経路のみ・半径32の告知）。 */
    private List<Player> playersInRadius(LivingEntity mob, double radius) {
        return playersNear(mob.getLocation(), radius, radius);
    }

    /**
     * 任意の地点を中心にした円判定の走査（機構1）。{@code DELAYED_ZONE} は<b>撃った本人ではなく置いた印</b>を
     * 中心に判定するので、モブ中心の走査だけでは足りない。
     *
     * <p>候補は箱（{@code entityLookup}）で広く取ってから {@link AbilityShapes#hitsCircle} で絞る。
     * 水平は円・垂直は {@code verticalRadius} の帯（箱時代は対角で最大 約1.41倍まで判定が伸びていた）。
     */
    private List<Player> playersNear(Location center, double radius, double verticalRadius) {
        List<Player> out = new ArrayList<>();
        World world = center.getWorld();
        if (world == null || radius <= 0.0) {
            return out;
        }
        for (Entity entity : entityLookup.nearby(center, radius, verticalRadius, radius)) {
            if (entity instanceof Player player && player.isValid() && !player.isDead()
                    && AbilityShapes.hitsCircle(center, radius, verticalRadius, player.getLocation())) {
                out.add(player);
            }
        }
        return out;
    }

    /** 技名を周囲へアクションバーで出す（<b>即時経路でのみ</b>使う。詠唱経路は{@link #threatZoneViewers}経由）。 */
    private void announce(LivingEntity mob, MobAbility ability) {
        if (ability.displayName().isEmpty()) {
            return;
        }
        var message = MiniMessage.miniMessage().deserialize(ability.displayName());
        // 直接 sendActionBar すると、その人が別のモブから受けている予告の行を上書きしてしまう。
        // 調停役の notice(予告より弱い・0.8 秒だけ下位を止める)を通す。
        for (Player nearby : playersInRadius(mob, ANNOUNCE_RADIUS)) {
            actionBar.notice(nearby, message);
        }
    }

    private void playEffects(Location location, MobAbility ability) {
        playParticles(location, ability);
        playSound(location, ability);
    }

    private void playParticles(Location location, MobAbility ability) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        Particle particle = particle(ability.particle());
        if (particle != null && ability.particleCount() >= 1) {
            double spread = Math.max(0.5, ability.radius() / 2.0);
            world.spawnParticle(particle, location, ability.particleCount(), spread, spread, spread, 0.0);
        }
    }

    /** 詠唱開始時に1回だけ鳴らす音（追加指示3「予告フレームは無音、開始時に ability.sound を1回」）。 */
    private void playSound(Location location, MobAbility ability) {
        World world = location.getWorld();
        if (world == null) {
            return;
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

    /** 基準ベクトルをY軸まわりに回す（扇状に撒く・レールの向きを作る）。 */
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
