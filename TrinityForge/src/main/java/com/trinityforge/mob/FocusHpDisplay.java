package com.trinityforge.mob;

import com.trinityforge.integration.TrainingDummies;
import com.trinityforge.pdc.MobData;
import com.trinityforge.pdc.PdcKeys;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
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
    private static final double EYE_HEIGHT_OFFSET = 0.55;
    /** Client-side lerp ticks between teleports (smooth follow while the scheduler runs every tick). */
    private static final int TELEPORT_DURATION_TICKS = 2;
    /** Cosine of half-cone (~25°) for look-direction fallback when raycast misses. */
    private static final double LOOK_CONE_DOT = 0.90;

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
     * Prefer Bukkit raycast; if it misses (oblique angle / partial block), fall back to the nearest
     * living entity in the player's look cone so the label is not direction-gated to a few yaw slices.
     */
    private LivingEntity findFocusTarget(Player player) {
        Entity direct = player.getTargetEntity(MAX_TARGET_DISTANCE_BLOCKS);
        if (direct instanceof LivingEntity living && !(direct instanceof Player)
                && !(direct instanceof ArmorStand)
                && !living.isDead() && living.isValid() && !TrainingDummies.isTrainingDummy(living)) {
            return living;
        }
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        LivingEntity best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        double maxDist = MAX_TARGET_DISTANCE_BLOCKS;
        for (Entity entity : player.getNearbyEntities(maxDist, maxDist, maxDist)) {
            // ArmorStand implements LivingEntity in the Bukkit API but is a decoration with no meaningful
            // level/HP; excluded unconditionally (type-correctness fix, not a policy toggle — see
            // DamagePopupDisplay's identical exclusion for the matching rationale).
            if (!(entity instanceof LivingEntity living) || entity instanceof Player
                    || entity instanceof ArmorStand
                    || living.isDead() || !living.isValid() || TrainingDummies.isTrainingDummy(living)) {
                continue;
            }
            Vector to = living.getEyeLocation().toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist < 0.2 || dist > maxDist) {
                continue;
            }
            double dot = look.dot(to.multiply(1.0 / dist));
            if (dot < LOOK_CONE_DOT) {
                continue;
            }
            double score = dist / Math.max(0.01, dot);
            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    private void updateFor(LivingEntity livingTarget) {
        if (livingTarget.isDead() || !livingTarget.isValid()) {
            removeFor(livingTarget.getUniqueId());
            return;
        }
        UUID targetId = livingTarget.getUniqueId();
        Component text = formatFor(livingTarget);
        Location location = livingTarget.getLocation();
        location.add(0.0, livingTarget.getEyeHeight() + EYE_HEIGHT_OFFSET, 0.0);

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
        int curHp = (int) Math.ceil(target.getHealth());
        AttributeInstance maxHealthAttr = target.getAttribute(Attribute.MAX_HEALTH);
        int maxHp = maxHealthAttr != null ? (int) Math.ceil(maxHealthAttr.getValue()) : curHp;
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
