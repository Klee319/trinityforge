package com.trinityforge.combat;

import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bleed DoT runtime (SKILL_TREE_SPEC 6.2, Q3 = (c)). Holds the set of active bleeds (one per victim)
 * and a single repeating main-thread task that, every {@code bleed.tick-interval-ticks}, deals one
 * bleed application per victim and expires finished/dead/despawned bleeds. Modelled on the existing
 * {@code HateService} lifecycle (start/shutdown, bounded self-eviction) so a disable/hot-reload leaks
 * nothing.
 *
 * <p><strong>LD-9</strong>: each application uses {@link SymmetricCombatService#bleedFinalDamageFlat}
 * so only the victim's {@code 被ダメージ軽減} applies. 回避/防御率/耐性/守備力/防具強度/ダメージ
 * 補正などの他の防御ステータスは意図的に無視し、ポストミティゲーション結果を直に
 * health へ書き込む（vanilla の再軽減を回避し TrinityForge を単一のダメージ権限に保つ）。
 *
 * <p><strong>Needs a live-server smoke test</strong>: the direct-health application (no vanilla
 * damage event) is the pragmatic way to avoid double-mitigation, but it bypasses knockback/hurt
 * animation and absorption/totem interactions — verify those are acceptable in-game.
 */
public final class BleedService {

    private final Plugin plugin;
    private final SymmetricCombatService combatService;
    private final CombatDamageConfig damageConfig;

    /** Active bleeds keyed by victim UUID; one bleed per victim (re-applying refreshes it). */
    private final Map<UUID, BleedInstance> active = new ConcurrentHashMap<>();
    private BukkitTask task;

    public BleedService(Plugin plugin, SymmetricCombatService combatService, CombatDamageConfig damageConfig) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combatService = Objects.requireNonNull(combatService, "combatService");
        this.damageConfig = Objects.requireNonNull(damageConfig, "damageConfig");
    }

    /** Schedules the repeating tick task (idempotent). Call once on enable. */
    public void start() {
        if (task != null) {
            return;
        }
        long period = Math.max(1L, damageConfig.bleedTickIntervalTicks());
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, period, period);
    }

    /** Cancels the task and drops all tracked bleeds so a disable/reload leaks nothing. */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        active.clear();
    }

    /**
     * Starts or refreshes a bleed on {@code victim}, credited to {@code attackerId}. Refresh replaces
     * the existing bleed (duration reset, not additive stack — Q3 default). A non-positive damage or
     * tick count is a no-op.
     */
    public void apply(LivingEntity victim, UUID attackerId, double damagePerTick, int ticks) {
        Objects.requireNonNull(victim, "victim");
        Objects.requireNonNull(attackerId, "attackerId");
        if (damagePerTick <= 0 || ticks <= 0 || !Double.isFinite(damagePerTick)) {
            return;
        }
        active.put(victim.getUniqueId(), new BleedInstance(attackerId, damagePerTick, ticks));
    }

    /** Visible for lifecycle/tests: how many victims currently bleed. */
    public int activeCount() {
        return active.size();
    }

    /**
     * 出血1tickあたりのダメージを、攻撃集約と「出血させた一撃の最終ダメージ」から決める唯一の場所
     * (2026-08-15, W-30)。近接({@code CombatListener})と魔法({@link #maybeApplyFromAggregate})の
     * 両方がここを呼ぶ ── 2箇所で式を書くと片方だけ率を落とすたぐいの食い違いが出る。
     *
     * <p><b>実数 + 率の合算</b>: {@code bleed-damage} は 1tick あたりの実数(アイテムが配る。帯とともに
     * 指数的に伸びるので実数のままで意味を持つ)、{@code bleed-damage-rate} はその一撃の最終ダメージに
     * 対する割合(スキルツリーが配る)。<b>固定値をツリーが配ると低帯で壊れ高帯で no-op になる</b>
     * ── 軽剣ツリー全取りの 150 は Lv0 で武器の +440%、Lv100 で +3.3% だった(W-30)。率は帯に依存しない。
     *
     * <p>基準を「その一撃の<b>最終</b>ダメージ」に採ったのは、近接・魔法のどちらの呼び出し元でも
     * 同じ意味の値が手元にある唯一の量だから。会心や貫通の結果もそのまま乗るので「重い一撃ほど
     * 深く出血する」になる。出血の各tick自体は被ダメージ軽減しか受けない(LD-9)ので、防御側の
     * ステが二重に効くのはこの基準ぶんだけ。
     *
     * @param attackerStats 攻撃集約(canonical key -&gt; 値)
     * @param hitDamage     出血を発生させた一撃の最終ダメージ(正値のみ有効)
     */
    public static double damagePerTick(Map<String, Double> attackerStats, double hitDamage) {
        if (attackerStats == null) {
            return 0.0;
        }
        double flat = attackerStats.getOrDefault(StatKeys.canonical("bleed-damage"), 0.0);
        double rate = attackerStats.getOrDefault(StatKeys.canonical("bleed-damage-rate"), 0.0);
        double fromRate = (rate > 0.0 && hitDamage > 0.0 && Double.isFinite(hitDamage)) ? hitDamage * rate : 0.0;
        return flat + fromRate;
    }

    /**
     * 課題1(魔法出血): 近接専用だった出血を、ArsPaperフォークの魔法ダメージ経路にも公開する唯一の口。
     * {@code attackerStats} から {@code bleed-chance}/{@code bleed-damage} を読み、成功時に
     * {@link #apply} で出血を開始する。呼び出し側(フォーク)には確率ロジックを一切書かせない
     * ({@link com.trinityforge.listeners.CombatListener#maybeApplyBleed} と同じ判定をここへ一本化)。
     *
     * <p>{@code finalDamage <= 0}(回復を含む)では絶対にロールしない — 回復と同時に出血DoTを付けるのは
     * 矛盾するため。{@code chance}/{@code damage} のどちらかが0以下でもロールしない。
     *
     * <p>ここで開始する出血自体は {@link #tickAll()}/{@link #applyOneTick} が health への直接書込みで
     * 処理する(バニラの {@link org.bukkit.event.entity.EntityDamageEvent} を発火させない) —
     * つまりこのメソッドが再び自分自身(魔法出血ロール)を誘発する経路は存在しない。
     *
     * @param attackerStats 魔法ダメージが読んだのと同じ攻撃集約(canonical key -&gt; 値)。呼び出し側は
     *                       メインハンド武器のステを混入させないこと(触媒が武器の代わりを務めるため)。
     * @param victim        出血を負わせる対象
     * @param attackerId    出血の帰属先(詠唱者)
     * @param finalDamage   実際に適用された(または適用予定の)最終魔法ダメージ
     */
    public void maybeApplyFromAggregate(Map<String, Double> attackerStats, LivingEntity victim,
                                         UUID attackerId, double finalDamage) {
        if (attackerStats == null || victim == null || attackerId == null) {
            return;
        }
        if (!(finalDamage > 0.0) || !Double.isFinite(finalDamage)) {
            return;
        }
        double chance = attackerStats.getOrDefault(StatKeys.canonical("bleed-chance"), 0.0);
        double damage = damagePerTick(attackerStats, finalDamage);
        if (chance <= 0.0 || damage <= 0.0) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            apply(victim, attackerId, damage, damageConfig.bleedTicks());
        }
    }

    private void tickAll() {
        for (Iterator<Map.Entry<UUID, BleedInstance>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, BleedInstance> entry = it.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity victim) || victim.isDead() || !victim.isValid()) {
                it.remove();
                continue;
            }
            applyOneTick(victim, entry.getValue());
            BleedInstance next = entry.getValue().afterTick();
            if (next.isExpired()) {
                it.remove();
            } else {
                entry.setValue(next);
            }
        }
    }

    private void applyOneTick(LivingEntity victim, BleedInstance bleed) {
        // Bleed must ignore every defender stat except 被ダメージ軽減(damageReduction).
        double finalDamage = combatService.bleedFinalDamageFlat(victim, bleed.damagePerTick());
        if (finalDamage <= 0) {
            return; // dodged this tick, or fully mitigated
        }
        // Post-pipeline final damage: apply straight to health so vanilla armor does not re-reduce it.
        double newHealth = Math.max(0.0, victim.getHealth() - finalDamage);
        AttributeInstance maxHealth = victim.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            newHealth = Math.min(newHealth, maxHealth.getValue());
        }
        victim.setHealth(newHealth);
    }
}
