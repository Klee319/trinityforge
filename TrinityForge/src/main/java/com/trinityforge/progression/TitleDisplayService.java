package com.trinityforge.progression;

import com.trinityforge.pdc.PdcKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * 称号(titles) の頭上表示 (2026-07-23-stat-gate-overhaul §6.1): 装備中プレイヤーの<b>頭上の別行</b>に
 * {@link TextDisplay} を1体浮かべ、MiniMessage文字列をそのまま描画する。
 *
 * <h2>2026-08-03: 「頭上の別行」に戻したうえで、名前が隠れる真因を潰した</h2>
 * バグ報告「称号を付けている人にネームタグが表示されなかった」に対して 2026-08-02 に
 * スコアボードチームの {@code suffix} 方式(＝称号を名前と同じ行に出す)へ書き換えたが、
 * <b>別行のままで良い</b>という判断に戻った。そこで表示位置の決め方だけを作り直している。
 *
 * <p><b>旧実装が名前を隠していた理由(算数で確定できる)</b>: 旧実装は
 * {@code player.addPassenger(display)} でマウントし、{@link org.bukkit.util.Transformation} の
 * 平行移動に config 値をそのまま入れていた。しかしパッセンジャーの描画基準は足元ではなく
 * <b>マウント点</b>(バニラ既定 {@code 高さ×0.75} = 立ち状態で 1.35)で、バニラのネームタグは
 * <b>足元から {@code 高さ+0.5} = 2.3</b> に出る。
 * <ul>
 *   <li>オフセット 0.35 → 称号は 1.70。ネームタグのはるか下。</li>
 *   <li>オフセット 0.75 → 称号は 2.10。1行の文字高は約 0.25 なので称号は 1.98〜2.23、
 *       ネームタグは 2.18〜2.43 ── <b>2.18〜2.23 で重なる</b>。</li>
 * </ul>
 * どちらの当て推量も「ネームタグより上」には届いておらず、背景なし・不透明度200・
 * {@code seeThrough} の称号がネームタグに重なって名前を読めなくしていた。
 *
 * <p><b>今の実装</b>: パッセンジャーをやめ、{@code FocusHpDisplay} と同じ「毎tickテレポートで
 * 追従させる独立エンティティ」にした。位置は {@link #titleAnchorY(double, double)} が
 * <b>足元からの絶対高さ</b>で決めるので、マウント点という未知数が式から消えている。
 * config で調整するのは「ネームタグ上端からさらに空ける余白」だけになり、
 * どの値を入れてもネームタグより下には来ない。副次的に、
 * <b>パッセンジャーが付いたエンティティはプラグインからのテレポートを妨げる</b>という
 * 旧実装のもう1つの欠陥(ダンジョン入口の転送が失敗しうる)も同時に消えている。
 *
 * <p>{@code FocusHpDisplay} と同じ「死亡位置に浮遊残留させない」規律も維持する: 死亡/リスポーン/
 * ログアウトで確実に despawn し、リスポーン/参加では {@code textResolver} 経由で現在の装備称号を
 * 取得して張り直す。非永続 + {@link PdcKeys#TITLE_DISPLAY} タグ付けで孤児掃除
 * ({@link #sweepOrphans}) にも対応する。
 */
public final class TitleDisplayService implements Listener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /** 追従tick間隔。{@code FocusHpDisplay} と揃える。 */
    private static final long PERIOD_TICKS = 1L;
    /** テレポート間をクライアント側で補間するtick数(2tick毎のカクつきを消す)。 */
    private static final int TELEPORT_DURATION_TICKS = 2;
    /**
     * バニラがネームタグを描画する高さ(足元から {@code 高さ + この値})。Minecraft 側の定数であり
     * 設定値ではない。ここを config にすると「バニラの描画位置」という観測事実が設定ミスで
     * ずれ、称号がまた名前に重なる余地を作ってしまう。
     */
    private static final double VANILLA_NAMETAG_OFFSET = 0.5;
    /** {@link #nametagClearance} が壊れた値(NaN/負)を返したときのフォールバック。 */
    private static final double FALLBACK_CLEARANCE = 0.4;

    private final Plugin plugin;
    /** プレイヤーの現在の装備称号MiniMessage文字列を返す(未装備/未保有なら null)。 */
    private final Function<Player, String> textResolver;
    /** ネームタグ上端からさらに上へ空ける余白(ブロック)。config駆動、reloadで次tickから反映。 */
    private final DoubleSupplier nametagClearance;
    private final Map<UUID, TextDisplay> active = new ConcurrentHashMap<>();
    private BukkitTask task;

    public TitleDisplayService(Plugin plugin, Function<Player, String> textResolver, DoubleSupplier nametagClearance) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.textResolver = Objects.requireNonNull(textResolver, "textResolver");
        this.nametagClearance = Objects.requireNonNull(nametagClearance, "nametagClearance");
    }

    /**
     * 称号行を置く<b>足元からの</b>高さ。
     *
     * <p>{@code playerHeight + 0.5} がバニラのネームタグの描画高さ(中心)で、そこへ
     * {@code clearance} を足したところに称号の中心を置く。返り値が常に
     * {@code playerHeight + 0.5} より大きい(clearance>=0 のとき等号)ことが、
     * 「称号がネームタグより下に来ない」＝報告されたバグが再発しないことの保証になる。
     *
     * <p>{@code playerHeight} は {@code player.getHeight()} をそのまま渡す。スニーク中(1.5)や
     * スケール変更にも自動追従し、立ち状態(1.8)を定数で埋め込まない。
     */
    static double titleAnchorY(double playerHeight, double clearance) {
        double height = Double.isFinite(playerHeight) && playerHeight > 0 ? playerHeight : 1.8;
        double gap = Double.isFinite(clearance) && clearance >= 0 ? clearance : FALLBACK_CLEARANCE;
        return height + VANILLA_NAMETAG_OFFSET + gap;
    }

    public void start() {
        sweepOrphans();
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
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

    /**
     * 追従の本体。位置だけを毎tick更新し、テキストは {@link #refresh} 側でしか触らない
     * (毎tick {@code textResolver} を呼ぶと装備解決のコストが人数×20/秒で乗るため)。
     * ワールドを跨いだ個体はテレポートで運ばず張り直す ── 別ワールドへの
     * {@code Entity#teleport} は失敗しうるので、失敗時に旧ワールドへ置き去りにしないため。
     */
    private void tick() {
        for (Map.Entry<UUID, TextDisplay> entry : active.entrySet()) {
            UUID playerId = entry.getKey();
            TextDisplay display = entry.getValue();
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || player.isDead()) {
                despawn(playerId);
                continue;
            }
            if (display == null || !display.isValid()) {
                active.remove(playerId);
                refresh(player);
                continue;
            }
            Location anchor = anchorFor(player);
            if (!Objects.equals(display.getWorld(), anchor.getWorld())) {
                refresh(player);
                continue;
            }
            display.setTeleportDuration(TELEPORT_DURATION_TICKS);
            display.teleport(anchor);
        }
    }

    private Location anchorFor(Player player) {
        return player.getLocation().add(0.0, titleAnchorY(player.getHeight(), nametagClearance.getAsDouble()), 0.0);
    }

    private void spawn(Player player, Component text) {
        World world = player.getWorld();
        Location location = anchorFor(player);
        TextDisplay display = world.spawn(location, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(PdcKeys.TITLE_DISPLAY, PersistentDataType.BYTE, (byte) 1);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setSeeThrough(true);
            d.setDefaultBackground(false);
            d.setTextOpacity((byte) 200);
            d.setTeleportDuration(TELEPORT_DURATION_TICKS);
            d.text(text);
        });
        active.put(player.getUniqueId(), display);
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

    /**
     * ワールド間移動は {@link #tick} でも検出して張り直すが、こちらでも拾って1tick早く追随させる
     * (移動直後の1tickだけ旧ワールドに表示体が残るのを避ける)。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }
}
