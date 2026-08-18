package com.trinityforge.mob;

import com.trinityforge.integration.TrainingDummies;
import com.trinityforge.pdc.MobData;
import com.trinityforge.pdc.PdcKeys;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cosmetic-only focus-target HP display: while any player looks at a non-player {@link
 * LivingEntity}, a single shared {@link TextDisplay} showing its level/name/HP floats above it.
 *
 * <p>Tracking is keyed by TARGET (not by viewer): multiple players targeting the same mob share
 * one label. The display uses {@link Display.Billboard#CENTER} so it always faces the camera
 * regardless of mob/player yaw. Death / entity-remove / player-death immediately despawn labels
 * so they never linger at the death site.
 */
public final class FocusHpDisplay implements Listener {

    private static final long PERIOD_TICKS = 1L;
    private static final int MAX_TARGET_DISTANCE_BLOCKS = 20;
    /** Client-side lerp ticks between teleports (smooth follow while the scheduler runs every tick). */
    private static final int TELEPORT_DURATION_TICKS = 2;

    private final Plugin plugin;
    private final MobDisplayNames displayNames;
    private final Map<UUID, TrackedDisplay> active = new ConcurrentHashMap<>();
    private BukkitTask task;

    public FocusHpDisplay(Plugin plugin, MobDisplayNames displayNames) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.displayNames = Objects.requireNonNull(displayNames, "displayNames");
    }

    public void start() {
        if (task != null) {
            return;
        }
        sweepOrphans();
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (TrackedDisplay tracked : active.values()) {
            safeRemove(tracked.display);
        }
        active.clear();
    }

    /** Mob died — drop its HP label immediately (do not wait for the next look-tick). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        removeFor(event.getEntity().getUniqueId());
    }

    /** Entity left the world (despawn / unload) — same immediate cleanup. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRemove(EntityRemoveEvent event) {
        removeFor(event.getEntity().getUniqueId());
    }

    /**
     * Player death can leave a stale look-target for a tick or two; clear every label that was only
     * kept alive by that viewer by re-running a full orphan pass next tick is heavy — instead drop
     * all labels whose target is dead/invalid, and sweep TF-tagged orphans near the death site.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        pruneInvalidTargets();
        Location death = event.getEntity().getLocation();
        World world = death.getWorld();
        if (world == null) {
            return;
        }
        for (Entity entity : world.getNearbyEntities(death, 4.0, 4.0, 4.0)) {
            if (entity instanceof TextDisplay display
                    && display.getPersistentDataContainer().has(PdcKeys.FOCUS_HP_DISPLAY, PersistentDataType.BYTE)) {
                UUID id = display.getUniqueId();
                active.values().removeIf(tracked -> tracked.display.getUniqueId().equals(id));
                safeRemove(display);
            }
        }
    }

    private void removeFor(UUID targetId) {
        TrackedDisplay tracked = active.remove(targetId);
        if (tracked != null) {
            safeRemove(tracked.display);
        }
    }

    private void pruneInvalidTargets() {
        for (Iterator<Map.Entry<UUID, TrackedDisplay>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, TrackedDisplay> entry = it.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
                safeRemove(entry.getValue().display);
                it.remove();
            }
        }
    }

    private static void safeRemove(Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private void sweepOrphans() {
        Set<UUID> trackedIds = new HashSet<>();
        for (TrackedDisplay tracked : active.values()) {
            trackedIds.add(tracked.display.getUniqueId());
        }
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof TextDisplay display
                        && display.getPersistentDataContainer().has(PdcKeys.FOCUS_HP_DISPLAY, PersistentDataType.BYTE)
                        && !trackedIds.contains(display.getUniqueId())) {
                    safeRemove(display);
                }
            }
        }
    }

    private void tick() {
        pruneInvalidTargets();
        Set<UUID> targetedThisTick = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead()) {
                continue;
            }
            LivingEntity livingTarget = findFocusTarget(player);
            if (livingTarget == null) {
                continue;
            }
            targetedThisTick.add(livingTarget.getUniqueId());
            updateFor(livingTarget);
        }
        for (Iterator<Map.Entry<UUID, TrackedDisplay>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, TrackedDisplay> entry = it.next();
            if (!targetedThisTick.contains(entry.getKey())) {
                safeRemove(entry.getValue().display);
                it.remove();
            }
        }
    }

    /**
     * 視線がそのモブの<b>ヒットボックスを貫くか</b>で判定する(2026-08-18 ユーザー確定要件:
     * 「ヒットボックスのどこを見ても表示」「外したら表示されない」)。
     *
     * <p><b>旧実装が大型モブで機能しなかった理由</b>: 当たらなかったときの救済が
     * 「目の位置どうしの角度が 25°以内」という<b>点と点の円錐</b>だったため、
     * ヒットボックスが大きいほど<b>体の端を見ても中心方向から外れて落ちる</b>。
     * エンダードラゴンやガストで「モブを向いているのに出ない」のはこれ。
     * 逆に小さいモブでは円錐が広すぎて「外したのに出る」側にも外れていた。
     *
     * <p>そこで2段構えにする:
     * <ol>
     *   <li>{@code World#rayTrace} — ブロック遮蔽も見る本来のレイキャスト。壁越しに出さない。</li>
     *   <li>ヒットボックス({@link #focusBox})への AABB レイキャスト。
     *       エンダードラゴンのように<b>本体の当たり判定が小さく、実体はパーツ側にある</b>モブは
     *       1 段目が素通りするので、ここで拾う。遮蔽ブロックより手前だけを有効にする。</li>
     * </ol>
     *
     * <p>自分が<b>騎乗しているモブは対象外</b>(ユーザー報告: ハッピーガスト/馬/ストライダーで
     * ラベルが視界の真ん中に居座る)。乗り物の乗り物まで辿るのは、馬に乗ってボートに乗るような
     * 多段騎乗でも同じ理由が成立するため。
     */
    private LivingEntity findFocusTarget(Player player) {
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        Set<UUID> ridden = riddenEntities(player);
        double maxDist = MAX_TARGET_DISTANCE_BLOCKS;

        RayTraceResult hit = player.getWorld().rayTrace(eye, look, maxDist,
                FluidCollisionMode.NEVER, true, 0.0,
                entity -> entity instanceof LivingEntity living && isFocusable(living, ridden));
        if (hit != null && hit.getHitEntity() instanceof LivingEntity living) {
            return living;
        }
        // 1段目がブロックに当たっていたら、そこまでが視線の届く距離。
        double limit = maxDist;
        if (hit != null && hit.getHitBlock() != null) {
            limit = hit.getHitPosition().distance(eye.toVector());
        }

        Vector origin = eye.toVector();
        LivingEntity best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Entity entity : player.getNearbyEntities(maxDist, maxDist, maxDist)) {
            // ArmorStand implements LivingEntity in the Bukkit API but is a decoration with no meaningful
            // level/HP; excluded unconditionally (type-correctness fix, not a policy toggle — see
            // DamagePopupDisplay's identical exclusion for the matching rationale).
            if (!(entity instanceof LivingEntity living) || !isFocusable(living, ridden)) {
                continue;
            }
            double distance = FocusHitbox.lookDistance(focusBox(living), origin, look, limit);
            if (!FocusHitbox.isHit(distance)) {
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best;
    }

    private static boolean isFocusable(LivingEntity living, Set<UUID> ridden) {
        return !(living instanceof Player)
                && !(living instanceof ArmorStand)
                && !living.isDead() && living.isValid()
                && !TrainingDummies.isTrainingDummy(living)
                && !ridden.contains(living.getUniqueId());
    }

    /** 自分が乗っている乗り物(多段騎乗なら全段)。 */
    private static Set<UUID> riddenEntities(Player player) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>();
        // 乗り物の連鎖は現実的に浅いが、循環していても落ちないように既出で打ち切る。
        while (vehicle != null && ids.add(vehicle.getUniqueId())) {
            vehicle = vehicle.getVehicle();
        }
        return ids;
    }

    /**
     * ラベルの位置と視線判定に使う当たり判定。
     *
     * <p>エンダードラゴンは<b>本体の {@code getBoundingBox()} が実際の見た目より遥かに小さく</b>、
     * 当たり判定は {@code getParts()}(頭・首・胴・翼・尾)側にある。パーツを合併しないと
     * 「体を見ているのに判定に入らない」「ラベルが体に埋まる」の両方が起きる。
     */
    private static BoundingBox focusBox(LivingEntity living) {
        BoundingBox box = living.getBoundingBox();
        if (living instanceof EnderDragon dragon) {
            for (Entity part : dragon.getParts()) {
                box.union(part.getBoundingBox());
            }
        }
        return box;
    }

    private void updateFor(LivingEntity livingTarget) {
        if (livingTarget.isDead() || !livingTarget.isValid()) {
            removeFor(livingTarget.getUniqueId());
            return;
        }
        UUID targetId = livingTarget.getUniqueId();
        Component text = formatFor(livingTarget);
        // ヒットボックスの最上面の中心の少し上(2026-08-18 ユーザー確定)。目の高さ基準だと
        // ガストやエンダードラゴンのように「目より上に体が続く」モブでラベルが体に埋まる。
        BoundingBox box = focusBox(livingTarget);
        Location location = new Location(livingTarget.getWorld(),
                box.getCenterX(), FocusHitbox.labelY(box), box.getCenterZ());

        TrackedDisplay tracked = active.get(targetId);
        if (tracked == null) {
            TextDisplay display = livingTarget.getWorld().spawn(location, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.getPersistentDataContainer().set(PdcKeys.FOCUS_HP_DISPLAY, PersistentDataType.BYTE, (byte) 1);
                d.setBillboard(Display.Billboard.CENTER);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setShadowed(true);
                d.setSeeThrough(false);
                d.setDefaultBackground(false);
                d.setBackgroundColor(Color.fromARGB(140, 16, 16, 20));
                d.setTextOpacity((byte) 210);
                // Smooth client interpolation between server teleports (avoids 2-tick stutter).
                d.setTeleportDuration(TELEPORT_DURATION_TICKS);
                d.text(text);
            });
            active.put(targetId, new TrackedDisplay(display, text, location.clone()));
            return;
        }
        if (!tracked.display.isValid()) {
            active.remove(targetId);
            updateFor(livingTarget);
            return;
        }
        if (!text.equals(tracked.lastText)) {
            tracked.display.text(text);
            tracked.lastText = text;
        }
        tracked.display.setBillboard(Display.Billboard.CENTER);
        tracked.display.setTeleportDuration(TELEPORT_DURATION_TICKS);
        if (!sameCoordinates(location, tracked.lastLocation)) {
            tracked.display.teleport(location);
            tracked.lastLocation = location.clone();
        }
    }

    private static boolean sameCoordinates(Location a, Location b) {
        return Objects.equals(a.getWorld(), b.getWorld())
                && a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }

    /**
     * 2026-07-25バグ修正: 種族名フォールバックが {@code EntityType} の内部ID(例: "ZOMBIE")のまま出て
     * いた。カスタム名(ネームタグ付き個体・EliteMobsカスタムボス等)を最優先で温存しつつ、それが無い
     * 個体だけ種族の翻訳可能Component({@code Component.translatable(EntityType)})へフォールバックする。
     * これはJava側に独自の英語→日本語対応表を新設せず、クライアント自身の言語設定でMinecraft本体の
     * 公式ローカライズが解決する(未対応言語・MOD entity typeでも安全にフォールバックする)ため、
     * config-editorの{@code VANILLA_MOBS}/{@code MOB_LABELS_JA}(あちらはeditor UIのプルダウン表示用
     * データであり、ランタイムのJavaプラグインからは参照できない別ランタイムの資産)を複製する二重管理を
     * 避けられる、最も筋の良い解として選択した。
     *
     * <p>2026-07-26: 上記の2段構えの手前に {@code combat/mob-overrides.yml} の {@code display-name}
     * を足した({@link MobDisplayNames} が一元解決)。EliteMobsのカスタムボスは英語名を
     * {@code customName()} に持つため、出荷ymlに入れた396体ぶんの日本語名がここで優先される。
     */
    private Component formatFor(LivingEntity target) {
        MobData data = MobData.of(target);
        int level = data.hasProfile() ? data.level() : 0;
        Component nameComponent = displayNames.displayName(target);
        // 2026-08-14: int だと double→int の narrowing が Integer.MAX_VALUE で「飽和」する(JLS 5.1.3。
        // 負値に巻き戻りはしないが 2,147,483,647 に張り付いて "2.15B" と嘘の値を出す)。現状の最大HP
        // 69,371,755 は int の範囲内なので今すぐ壊れてはいないが、HP倍率は層をまたいで掛け合わさる
        // (default × dungeon × mob)ので上限が読めない。long にしておけば 9.2e18 まで飽和しない。
        long curHp = (long) Math.ceil(target.getHealth());
        AttributeInstance maxHealthAttr = target.getAttribute(Attribute.MAX_HEALTH);
        long maxHp = maxHealthAttr != null ? (long) Math.ceil(maxHealthAttr.getValue()) : curHp;
        // どちらの型に寄って耐性/防御が設定されているかを示す任意タグ(依頼2)。既存の
        // combat/mob-defaults.yml 系キー(PDCへ既に焼かれている)から導出するだけで、新しい設定面は
        // 増やさない — MobData#defenseFor はプロファイル無しモブでは全項目0を返すので、その場合は
        // FocusHpText.leanFrom が自動的にNONEへ落ちる(明示的なhasProfile()分岐は不要)。
        FocusHpText.ResistanceLean lean = FocusHpText.leanFrom(
                data.defenseFor(com.trinityforge.combat.DamageType.PHYSICAL),
                data.defenseFor(com.trinityforge.combat.DamageType.MAGICAL));
        // 実装2(2026-08-02): モブの通常攻撃タイプ(attack.magic-ratio)を耐性タグとは別のタグで表示する。
        // hasAttackProfile()の有無に関わらず読める(MobData#attackMagicRatio javadoc参照) — EliteMobs
        // ダンジョンモブの大多数はTFの攻撃プロファイルを持たないが、magic-ratioだけは独立に設定できる。
        FocusHpText.AttackLean attackLean = FocusHpText.attackLeanFrom(data.attackMagicRatio());
        return FocusHpText.format(level, nameComponent, curHp, maxHp, lean, attackLean);
    }

    private static final class TrackedDisplay {
        private final TextDisplay display;
        private Component lastText;
        private Location lastLocation;

        private TrackedDisplay(TextDisplay display, Component lastText, Location lastLocation) {
            this.display = display;
            this.lastText = lastText;
            this.lastLocation = lastLocation;
        }
    }
}
