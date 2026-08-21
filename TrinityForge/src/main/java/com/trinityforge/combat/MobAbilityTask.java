package com.trinityforge.combat;

import com.trinityforge.config.domains.MobAbilitiesConfig;
import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.pdc.MobData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 特殊攻撃の発動判定を回す周期タスク（2026-07-31）。
 *
 * <p><b>走査の起点はプレイヤー</b>で、モブ側からは走査しない。理由は作業量の上限が
 * 「オンライン人数 × 半径内エンティティ数」で決まるため — 全ワールドの全エンティティを舐めると、
 * EliteMobs のインスタンスワールドが増えるほど無関係なモブまで数えることになる。
 * そもそもプレイヤーから見えない場所で技を撃つ意味も無い。
 *
 * <p>クールダウン台帳の掃除も同じループで行う。インスタンスダンジョンは<b>入場ごとに
 * ワールドを作って捨てる</b>ので、死亡イベントの取りこぼしは必ず起きる。
 */
public final class MobAbilityTask implements Runnable {

    /** プレイヤーの周囲この距離までを走査対象にする。テンプレートの range はこの中で更に絞る。 */
    private static final double SCAN_RADIUS = 32.0;
    /** クールダウン台帳を掃除する間隔（このタスクの実行回数）。 */
    private static final int PURGE_EVERY = 60;

    /**
     * 「技の種類によらずモブ単位で効く共通クールダウン」を技IDと同じ台帳に載せるための予約キー
     * （2026-08-17）。
     *
     * <p><b>先頭の空白は必須。</b> 技IDは {@code MobAbilitiesConfig#parse} で
     * {@code trim().toLowerCase()} されるので、先頭に空白を持つIDは<b>どう書いても生成されない</b>。
     * つまりこのキーは yml 側の技IDと衝突しえない。
     */
    static final String GLOBAL_GAP_KEY = " global-gap";

    private final Plugin plugin;
    private final MobAbilitiesConfig abilitiesConfig;
    private final MobOverridesConfig overrides;
    private final MobAbilityExecutor executor;
    private final MobAbilityCooldowns cooldowns;
    private final Random random;

    private BukkitTask task;
    private int runCount;

    public MobAbilityTask(Plugin plugin, MobAbilitiesConfig abilitiesConfig, MobOverridesConfig overrides,
                          MobAbilityExecutor executor, MobAbilityCooldowns cooldowns, Random random) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.abilitiesConfig = Objects.requireNonNull(abilitiesConfig, "abilitiesConfig");
        this.overrides = Objects.requireNonNull(overrides, "overrides");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.cooldowns = Objects.requireNonNull(cooldowns, "cooldowns");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** 判定間隔は config 由来。無効なら何も開始しない（タスク自体を作らない）。 */
    public void start() {
        if (task != null) {
            return;
        }
        int interval = abilitiesConfig.checkIntervalTicks();
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @Override
    public void run() {
        if (!abilitiesConfig.enabled() || abilitiesConfig.abilities().isEmpty()) {
            return;
        }
        runCount++;
        Set<UUID> seen = new HashSet<>();
        // 同じモブが複数プレイヤーの走査範囲に入っても、1周で撃つのは1回だけ。
        Set<UUID> firedThisRound = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            for (Entity entity : player.getWorld()
                    .getNearbyEntities(player.getLocation(), SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS)) {
                if (!(entity instanceof LivingEntity mob) || mob instanceof Player || !mob.isValid()) {
                    continue;
                }
                seen.add(mob.getUniqueId());
                if (firedThisRound.contains(mob.getUniqueId())) {
                    continue;
                }
                if (tryFire(mob, player)) {
                    firedThisRound.add(mob.getUniqueId());
                }
            }
        }
        if (runCount % PURGE_EVERY == 0) {
            cooldowns.purge(seen);
        }
    }

    /**
     * そのモブが今この相手へ技を撃てるなら撃つ。
     *
     * <p>パッケージ非公開なのはテストのため（2026-08-18、W-62）。交戦条件を
     * {@link #engagementAllows(boolean, boolean, boolean, boolean, boolean)} の純関数として
     * 検証するだけでは<b>「判定は正しいが呼ばれていない」no-op 修正を見逃す</b>ので、
     * 実体を渡してここまでの配線ごと固定する。
     */
    boolean tryFire(LivingEntity mob, Player target) {
        // 技ごとのクールダウンより先に、モブ単位の共通クールダウンを見る(2026-08-17)。
        // これが無いと、技を複数持つモブは「どれか1つは必ず明けている」状態が途切れず、
        // 判定のたびに抽選が走って技が常時発動しているように見える。
        if (!cooldowns.ready(mob.getUniqueId(), GLOBAL_GAP_KEY)) {
            return false;
        }
        // 2026-08-18 (W-62): 交戦条件。走査がプレイヤー起点の「半径32m以内の全 LivingEntity」なので、
        // ここで絞らないと【こちらに気づいてすらいないモブが壁越しに撃ってくる】。
        if (!engagementAllows(mob, target)) {
            return false;
        }
        List<MobAbility> candidates = candidatesFor(mob, target);
        if (candidates.isEmpty()) {
            return false;
        }
        // 候補の中からランダムに1つ選び、その1つだけ抽選する。
        // 「全候補を順に抽選」にすると技を多く持つモブほど毎周期で何か撃つようになり、
        // 技を足すこと自体が難易度の急上昇になってしまう。
        MobAbility ability = candidates.get(random.nextInt(candidates.size()));
        if (random.nextDouble() >= ability.chance()) {
            return false;
        }
        if (!executor.execute(mob, target, ability)) {
            return false;
        }
        cooldowns.arm(mob.getUniqueId(), ability.id(), ability.cooldownMillis());
        cooldowns.arm(mob.getUniqueId(), GLOBAL_GAP_KEY, abilitiesConfig.globalCooldownMillis());
        return true;
    }

    /**
     * 交戦条件を満たすか（2026-08-18、W-62「死角・非追跡状態でも発動する」）。
     *
     * <p>実体から必要な事実だけを取り出して {@link #engagementAllows(boolean, boolean, boolean,
     * boolean, boolean)} に渡す。分けてあるのは<b>MockBukkit が {@code hasLineOfSight} も
     * {@code Mob#getTarget} も実装していない</b>ため — ここを直接テストすると
     * 「未実装APIで SKIPPED に化けて、判定ロジックが一度も検証されない」既知の罠を踏む。
     */
    private boolean engagementAllows(LivingEntity mob, Player target) {
        boolean requireTarget = abilitiesConfig.requireTarget();
        boolean requireLineOfSight = abilitiesConfig.requireLineOfSight();
        if (!requireTarget && !requireLineOfSight) {
            return true; // どちらも切ってあるなら実体に一切触らない（2026-08-18 以前の挙動）。
        }
        boolean hasAi = mob instanceof org.bukkit.entity.Mob;
        boolean targetsThisPlayer =
                hasAi && target.equals(((org.bukkit.entity.Mob) mob).getTarget());
        if (!engagementAllows(requireTarget, hasAi, targetsThisPlayer, false, true)) {
            return false;
        }
        // 視線判定はレイトレースで重いので、ターゲット条件を通った相手にだけ引く。
        return !requireLineOfSight || mob.hasLineOfSight(target);
    }

    /**
     * 交戦条件の判定本体（純関数）。
     *
     * @param requireTarget      「狙っている相手にだけ撃つ」を要求するか
     * @param mobHasAi           そのモブが {@code org.bukkit.entity.Mob}（＝狙う対象を持てる）か
     * @param targetsThisPlayer  {@code Mob#getTarget()} がこの相手本人か
     * @param requireLineOfSight 「遮蔽越しには撃たない」を要求するか
     * @param hasLineOfSight     実際に視線が通っているか
     */
    static boolean engagementAllows(boolean requireTarget, boolean mobHasAi,
                                    boolean targetsThisPlayer, boolean requireLineOfSight,
                                    boolean hasLineOfSight) {
        // AI を持たない LivingEntity には「狙う対象」の概念自体が無いので、この条件は課さない。
        // ここを課すと、AI を切られたボスや実体だけのギミックモブが技を一生撃たなくなる。
        if (requireTarget && mobHasAi && !targetsThisPlayer) {
            return false;
        }
        return !requireLineOfSight || hasLineOfSight;
    }

    /** 発動可能な技（テンプレート実在 + クールダウン明け + 射程内）。 */
    private List<MobAbility> candidatesFor(LivingEntity mob, Player target) {
        List<String> ids = overrides.abilitiesFor(mob.getWorld().getName(), mobIdOf(mob));
        if (ids.isEmpty()) {
            return List.of();
        }
        double distanceSquared = mob.getLocation().distanceSquared(target.getLocation());
        double healthFraction = healthFractionOf(mob);
        List<MobAbility> out = new ArrayList<>();
        for (String id : ids) {
            MobAbility ability = abilitiesConfig.ability(id);
            if (ability == null) {
                continue; // 未定義IDは黙って読み飛ばす(ロード順に依存させない)
            }
            if (distanceSquared > ability.range() * ability.range()) {
                continue;
            }
            // 残HPの門(health-below / health-above)。「瀕死になると出す大技」を作るための条件で、
            // ここで落とすと【その技だけ】が候補から消える(他の技は今までどおり撃てる)。
            if (!ability.allowedAtHealth(healthFraction)) {
                continue;
            }
            if (!cooldowns.ready(mob.getUniqueId(), ability.id())) {
                continue;
            }
            out.add(ability);
        }
        return out;
    }

    /**
     * 残HP割合（{@code getHealth() / GENERIC_MAX_HEALTH}）。
     *
     * <p>最大HPを読めない個体では {@code NaN} を返し、{@link MobAbility#allowedAtHealth(double)}
     * 側で<b>門を課さない</b>扱いにする。MockBukkit は属性を実装していないことがあり、
     * ここで 0 を返すと「常に瀕死」と誤判定して瀕死技が常時発動する。
     */
    private static double healthFractionOf(LivingEntity mob) {
        org.bukkit.attribute.AttributeInstance max;
        try {
            max = mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        } catch (Throwable ignored) {
            return Double.NaN;
        }
        if (max == null || max.getValue() <= 0.0) {
            return Double.NaN;
        }
        return mob.getHealth() / max.getValue();
    }

    /**
     * オーバーライドを引くキー。EliteMobs のカスタムボスは PDC に焼かれた profile id を使い、
     * <b>それが無いモブ（バニラ）は {@code EntityType} 名を id として扱う</b>。
     *
     * <p>後者を足したのは、特殊攻撃をダンジョン専用にしたくなかったため。
     * {@code overrides.default.mobs.zombie.abilities: [shockwave]} と書けばオーバーワールドの
     * ゾンビにも技が付く。EM 側の mob id は必ず設定ファイル名（英小文字）なので、
     * {@code EntityType} 名と衝突しても「同じ名前のモブに同じ技」という直感どおりの結果になる。
     */
    static String mobIdOf(LivingEntity mob) {
        return MobData.of(mob).profileId()
                .orElseGet(() -> mob.getType().name().toLowerCase(java.util.Locale.ROOT));
    }
}
