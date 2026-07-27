package com.trinityforge.progression;

import com.trinityforge.pdc.PdcKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * 称号(titles) 頭上表示 (2026-07-23-stat-gate-overhaul §6.1): 装備中プレイヤーへパッセンジャーの
 * {@link TextDisplay} を1体マウントし、MiniMessage文字列をそのまま描画する。
 *
 * <p>{@code FocusHpDisplay} と同じ「死亡位置に浮遊残留させない」規律: 死亡/リスポーン/ワールド移動/
 * ログアウトの全てで確実に despawn し、リスポーン/ワールド移動/参加では{@code textResolver}経由で
 * 現在の装備称号テキストを取得して張り直す。非永続 + {@link PdcKeys#TITLE_DISPLAY} タグ付けで、
 * クラッシュ後の孤児掃除({@link #sweepOrphans})にも対応する。
 */
public final class TitleDisplayService implements Listener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Plugin plugin;
    /** プレイヤーの現在の装備称号MiniMessage文字列を返す(未装備/未保有なら null)。 */
    private final Function<Player, String> textResolver;
    /**
     * パッセンジャーの既定マウント点から頭上へ持ち上げる追加オフセット(ブロック単位、見た目調整用)。
     * バグ報告B1: 固定値 0.35 だとネームタグに重なり名前を隠していたため、config駆動化した
     * ({@code progression/special-rewards.yml} の {@code display.head-offset-y}, 既定 0.75)。
     * {@link #spawn} を呼ぶたびに最新値を読むので {@code /trinityforge reload} が次回の
     * 張り直し(参加/リスポーン/ワールド移動/テレポート)から反映される。
     */
    private final DoubleSupplier headOffsetY;
    private final Map<UUID, TextDisplay> active = new ConcurrentHashMap<>();

    public TitleDisplayService(Plugin plugin, Function<Player, String> textResolver, DoubleSupplier headOffsetY) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.textResolver = Objects.requireNonNull(textResolver, "textResolver");
        this.headOffsetY = Objects.requireNonNull(headOffsetY, "headOffsetY");
    }

    public void start() {
        sweepOrphans();
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    public void shutdown() {
        for (TextDisplay display : active.values()) {
            safeRemove(display);
        }
        active.clear();
    }

    /** 装備状態(称号テキスト)に合わせて表示を張り直す。称号未装備/死亡中/オフラインなら消すのみ。 */
    public void refresh(Player player) {
        despawn(player.getUniqueId());
        if (!player.isOnline() || player.isDead()) {
            return;
        }
        String display = textResolver.apply(player);
        if (display == null || display.isBlank()) {
            return;
        }
        Component text;
        try {
            text = MINI_MESSAGE.deserialize(display);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[title-display] invalid MiniMessage for " + player.getName()
                    + ": " + ex.getMessage());
            return;
        }
        spawn(player, text);
    }

    private void spawn(Player player, Component text) {
        World world = player.getWorld();
        // config読み出しをspawnコールバックの外で1回だけ行う: 副作用のない値なので早期評価で問題なく、
        // Bukkit側の例外(テストダブル等)がspawn中に起きても headOffsetY が読まれたことをテストで
        // 直接確認できる(値そのものを spawn 後に読み戻せない TextDisplay実装差異を避ける)。
        float offsetY = (float) resolveHeadOffsetY();
        TextDisplay display = world.spawn(player.getLocation(), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(PdcKeys.TITLE_DISPLAY, PersistentDataType.BYTE, (byte) 1);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setSeeThrough(true);
            d.setDefaultBackground(false);
            d.setTextOpacity((byte) 200);
            d.text(text);
            d.setTransformation(new Transformation(
                    new Vector3f(0f, offsetY, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(1f, 1f, 1f),
                    new AxisAngle4f(0f, 0f, 0f, 1f)));
        });
        player.addPassenger(display);
        active.put(player.getUniqueId(), display);
    }

    /** {@link #headOffsetY} を安全に読む(非有限値は既定 0.75 相当のフォールバック無しで単に0扱いにしない)。 */
    private double resolveHeadOffsetY() {
        double value = headOffsetY.getAsDouble();
        return Double.isFinite(value) ? value : 0.75;
    }

    private void despawn(UUID playerId) {
        safeRemove(active.remove(playerId));
    }

    private static void safeRemove(Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private void sweepOrphans() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof TextDisplay display
                        && display.getPersistentDataContainer().has(PdcKeys.TITLE_DISPLAY, PersistentDataType.BYTE)) {
                    safeRemove(display);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        despawn(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        // EMのCombatLevelDisplay浮遊残留バグの再発防止: 死亡直後に必ず消す。リスポーンで張り直す。
        despawn(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        despawn(player.getUniqueId());
        // リスポーン座標が確定するのは次tick以降(このイベント内ではteleport未反映のことがある)。
        new BukkitRunnable() {
            @Override
            public void run() {
                refresh(player);
            }
        }.runTask(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // パッセンジャーはワールド跨ぎ移動で失われることがあるため、必ず張り直す。
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    /**
     * 2026-07-23 verifier指摘⑨: テレポート(同一ワールドtp/エンダーパール/waystone等)はパッセンジャーを
     * eject するため張り直さないと頭上表示が瞬間移動元に置き去りになる。次tickでrefresh(座標確定後)。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        new BukkitRunnable() {
            @Override
            public void run() {
                refresh(player);
            }
        }.runTask(plugin);
    }
}
