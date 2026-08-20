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
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

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
 * <h2>2026-08-19 (W-153): 追従のズレは「クライアント騎乗」で構造的に消した</h2>
 * 実サーバ報告「称号の位置がネームタグの位置と同期していない。少し遅れてついてきている」。
 * <b>毎tickテレポート追従では原理的に直らない</b> ── クライアントはプレイヤー本体と表示体を
 * 別々に補間する(本体は移動パケットを既定3tickかけて補間、表示体は {@code teleport_duration} ぶん)ので、
 * 補間長をどう合わせても両者が同じ動きにはならない。W-135 で補間長を更新間隔に揃えて<b>揺れ</b>は
 * 消えたが、<b>ズレ</b>はこの理由で残っていた。
 *
 * <p>Paper のドキュメントが勧めるとおり表示体をパッセンジャーにすればズレは構造的に消えるが、
 * <b>サーバ側で本当に騎乗させると乗騎のテレポートが無言で失敗する</b>(PaperMC/Paper#10168。
 * {@code PlayerTeleportEvent} すら発火しないので他プラグインからは原因が見えない)。
 * そこで {@link TitleDisplayMountBridge} が<b>パケットだけ</b>で騎乗させる ──
 * サーバ側は独立エンティティのままなのでテレポートを一切妨げない。
 * 騎乗中の描画基準は取付点(高さ×0.75)になるので、{@link #mountTranslationY} が
 * 「置きたい絶対高さ − 取付点」を {@link Transformation} の平行移動として与える。
 * packetevents 未導入の環境では騎乗せず、従来のテレポート追従のまま動く(ズレは残るが表示は出る)。
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
    /**
     * テレポート間をクライアント側で補間するtick数。
     *
     * <p><b>{@link #PERIOD_TICKS} と必ず同じ値にすること(2026-08-19 / W-135)。</b>
     * 補間長が更新間隔より長いと、毎tick「まだ終わっていない補間」を新しい目的地で
     * 上書きし続けることになり、称号は常に本体より遅れて追いつけないまま<b>揺れて見える</b>
     * (実サーバ報告「少し揺れる」)。等しくしておけば、補間はちょうど次の更新が届く瞬間に
     * 完了するので、滑らかさを保ったまま遅れが出ない。
     */
    private static final int TELEPORT_DURATION_TICKS = (int) PERIOD_TICKS;
    /**
     * バニラがネームタグを描画する高さ(足元から {@code 高さ + この値})。Minecraft 側の定数であり
     * 設定値ではない。ここを config にすると「バニラの描画位置」という観測事実が設定ミスで
     * ずれ、称号がまた名前に重なる余地を作ってしまう。
     */
    private static final double VANILLA_NAMETAG_OFFSET = 0.5;
    /**
     * 頭上テキスト1行ぶんの高さ(ブロック)。称号を<b>ネームタグと重ねない</b>ために、
     * 余白(clearance)とは別に必ずこのぶんだけ持ち上げる。
     *
     * <p><b>2026-08-20 W-174: これが無かったのが「称号がネームタグを消す」の真因。</b>
     * {@code titleAnchorY} は {@code 高さ + 0.5}(＝ネームタグの<b>中心</b>)へ clearance を足すだけだった。
     * ところが config のコメントは「ネームタグの<b>上端</b>からさらに何ブロック離すか」と書いており、
     * <b>説明と実装が1行ぶんずれていた</b>。名前も称号も1行の高さが約 0.25 あるので、
     * 中心どうしを 0.25 未満しか離さないと必ず重なる ── 出荷値は 0.2、実サーバの設定は 0.1 で、
     * <b>どちらも重なる側</b>だった(名前が読めない＝「消えた」に見える)。
     *
     * <p>2026-08-03 に入れた不変条件は「称号がネームタグより<b>下</b>に来ない」だけで、
     * 等号(＝ぴったり重なる)を許していたため、この重なりを一度も検出できなかった。
     */
    private static final double NAMETAG_LINE_HEIGHT = 0.25;
    /** {@link #nametagClearance} が壊れた値(NaN/負)を返したときのフォールバック。 */
    private static final double FALLBACK_CLEARANCE = 0.4;
    /**
     * 乗客(パッセンジャー)の描画基準になる取付点の高さ比率。
     *
     * <p><b>2026-08-20 W-174: 0.75 は誤りだったので 1.0 に直した。</b>
     * 稼働サーバの {@code paper-1.21.11} を逆アセンブルして確定させた事実:
     * <ul>
     *   <li>{@code EntityAttachment.PASSENGER} の fallback は {@code Fallback.AT_HEIGHT}、
     *       その実体は {@code new Vec3(0, height, 0)}(= <b>高さそのもの</b>。
     *       {@code AT_CENTER} だけが {@code height / 2})。</li>
     *   <li>{@code EntityType.PLAYER} のビルダは
     *       {@code sized(0.6, 1.8) → eyeHeight(1.62) → vehicleAttachment(...)} だけで、
     *       <b>{@code passengerAttachments(...)} を呼んでいない</b>
     *       ＝プレイヤーは fallback をそのまま使う。</li>
     * </ul>
     * つまり取付点は {@code 高さ × 1.0}。0.75 のままだと平行移動を {@code 0.25 × 高さ}
     * (立ち状態で <b>0.45 ブロック</b>)引きすぎ、称号が<b>その分だけ高く浮く</b>。
     * 実サーバ報告「位置が従来より上によっている」がこれ。
     *
     * <p>Minecraft 側の定数であり設定値ではない(config にすると「バニラの描画位置」という
     * 観測事実が設定ミスでずれ、称号がまた名前に重なる余地を作る)。
     */
    private static final double VANILLA_PASSENGER_ATTACHMENT_RATIO = 1.0;
    /** 平行移動を metadata で撃ち直す閾値(ブロック)。これ未満の変化は無視して通信量を抑える。 */
    private static final double TRANSLATION_EPSILON = 0.01;

    private final Plugin plugin;
    /** プレイヤーの現在の装備称号MiniMessage文字列を返す(未装備/未保有なら null)。 */
    private final Function<Player, String> textResolver;
    /** ネームタグ上端からさらに上へ空ける余白(ブロック)。config駆動、reloadで次tickから反映。 */
    private final DoubleSupplier nametagClearance;
    private final Map<UUID, TextDisplay> active = new ConcurrentHashMap<>();
    /**
     * パケット層のクライアント騎乗に使う「乗騎(プレイヤー)と称号表示の entity id 対」
     * (2026-08-19 / W-153)。<b>キーは両者の entity id</b>(どちらの SPAWN_ENTITY を見ても
     * 同じ対が引けるようにするため)。値は {@code {乗騎id, 乗客id}}。
     *
     * <p>パケット層は netty のスレッドから読むので {@link ConcurrentHashMap} で持つ。
     * Bukkit の API をそちらから触らずに済むよう、必要な id だけをここへ写しておく。
     */
    private final Map<Integer, int[]> mountPairsByEntityId = new ConcurrentHashMap<>();
    /** 直近に適用した平行移動のY(表示ごと)。metadata パケットを毎tick撒かないための差分判定用。 */
    private final Map<UUID, Double> appliedTranslationY = new ConcurrentHashMap<>();
    /** パケット層のクライアント騎乗が実際に動いているか({@code TitleDisplayMountBridge} が立てる)。 */
    private volatile boolean mountBridgeActive;
    private BukkitTask task;

    public TitleDisplayService(Plugin plugin, Function<Player, String> textResolver, DoubleSupplier nametagClearance) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.textResolver = Objects.requireNonNull(textResolver, "textResolver");
        this.nametagClearance = Objects.requireNonNull(nametagClearance, "nametagClearance");
    }

    /**
     * 称号行を置く<b>足元からの</b>高さ。
     *
     * <p>{@code playerHeight + 0.5} がバニラのネームタグの描画高さ(<b>中心</b>)。そこへ
     * {@link #NAMETAG_LINE_HEIGHT}(名前の行を跨ぐぶん)と {@code clearance}(設定で足す余白)を
     * 足したところに称号の<b>中心</b>を置く。返り値が常に
     * {@code playerHeight + 0.5 + NAMETAG_LINE_HEIGHT} 以上であることが、
     * 「称号が名前に重ならない」＝報告されたバグが再発しないことの保証になる
     * (2026-08-20 W-174 で「下に来ない」から「重ならない」へ強めた)。
     *
     * <p>{@code playerHeight} は {@code player.getHeight()} をそのまま渡す。スニーク中(1.5)や
     * スケール変更にも自動追従し、立ち状態(1.8)を定数で埋め込まない。
     */
    static double titleAnchorY(double playerHeight, double clearance) {
        return titleAnchorY(playerHeight, 0.0, clearance);
    }

    /**
     * 目線の高さも見る版(2026-08-19 / W-135)。
     *
     * <p><b>なぜ要るか(実バグ)</b>: トロッコ搭乗などで姿勢が変わると
     * {@code player.getHeight()} は当たり判定の高さ(座り姿勢で 1.8 → 0.6 前後)まで縮む。
     * 一方 {@link org.bukkit.entity.Player#getLocation()} の原点は乗り物の座席側へ上がるので、
     * 「高さ + 0.5」で置いた称号が<b>そのまま目の前に来て視界を塞ぐ</b>。
     * 高さと目線のどちらか高い方を基準にすれば、姿勢が縮んでも称号は必ず目線より上に残る。
     *
     * <p>{@code eyeHeight} に 0 以下・非有限を渡すと従来どおり高さだけで決める
     * (＝この引数を知らない呼び出し元の挙動は変わらない)。
     */
    static double titleAnchorY(double playerHeight, double eyeHeight, double clearance) {
        double height = Double.isFinite(playerHeight) && playerHeight > 0 ? playerHeight : 1.8;
        double eyes = Double.isFinite(eyeHeight) && eyeHeight > 0 ? eyeHeight : 0.0;
        double gap = Double.isFinite(clearance) && clearance >= 0 ? clearance : FALLBACK_CLEARANCE;
        return Math.max(height, eyes) + VANILLA_NAMETAG_OFFSET + NAMETAG_LINE_HEIGHT + gap;
    }

    /**
     * クライアント騎乗させたときの、表示エンティティに与える<b>平行移動のY</b>(2026-08-19 / W-153)。
     *
     * <p>騎乗した乗客の描画基準は足元ではなく<b>乗騎のパッセンジャー取付点</b>
     * (バニラ既定は {@code 高さ × 0.75})になる。称号を置きたいのは
     * {@link #titleAnchorY(double, double, double)} が返す<b>足元からの絶対高さ</b>なので、
     * 差分だけを {@link Transformation} の平行移動で足す。
     *
     * <p><b>ここを当て推量で書いたのが 2026-08-02 以前のバグの正体</b>(オフセットに config 値を
     * そのまま入れていたため、ネームタグに重なって名前が読めなかった)。取付点を式に明示して
     * 引き算する形にしてあるので、どの clearance を入れてもネームタグより下には来ない。
     */
    static double mountTranslationY(double playerHeight, double eyeHeight, double clearance) {
        return titleAnchorY(playerHeight, eyeHeight, clearance)
                - passengerAttachmentY(playerHeight);
    }

    /**
     * 騎乗した乗客が描画される<b>足元からの高さ</b>(= バニラのパッセンジャー取付点)。
     *
     * <p>{@link #mountTranslationY} と表裏一体なので、テストが定数を書き写して
     * 「実装と同じ思い込み」を固定してしまわないよう<b>ここ1点を正</b>にする
     * (0.75 を実装にもテストにも書いていたせいで、間違いが誰にも検出されなかった)。
     */
    static double passengerAttachmentY(double playerHeight) {
        double height = Double.isFinite(playerHeight) && playerHeight > 0 ? playerHeight : 1.8;
        return height * VANILLA_PASSENGER_ATTACHMENT_RATIO;
    }

    /**
     * {@code entityId}(乗騎でも乗客でもよい)に対応する {@code {乗騎id, 乗客id}}。
     * 無ければ {@code null}。<b>パケット層(netty スレッド)から呼ばれる</b>ので Bukkit API を触らない。
     */
    public int[] mountPairFor(int entityId) {
        return mountPairsByEntityId.get(entityId);
    }

    /**
     * パケット層のクライアント騎乗が有効になったことを通知する({@code TitleDisplayMountBridge} が呼ぶ)。
     *
     * <p>true の間だけ表示体へ平行移動を載せる。false のまま平行移動を載せると、
     * 騎乗していない(＝実座標がそのまま描画位置になる)フォールバック経路で
     * <b>称号が二重にせり上がる</b>。
     */
    public void setMountBridgeActive(boolean active) {
        this.mountBridgeActive = active;
    }

    /** 現在の全ペア(新規に張り直すとき用)。 */
    public java.util.Collection<int[]> mountPairs() {
        return mountPairsByEntityId.values();
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
        mountPairsByEntityId.clear();
        appliedTranslationY.clear();
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
            // 実座標の追従はクライアント騎乗中も残す —— 描画位置はもう乗騎側で決まるが、
            // エンティティ追跡(誰に見えるか)は実座標で決まるので、置き去りにすると
            // 遠くのプレイヤーから称号が消える。
            syncMountTranslation(player, display);
        }
    }

    private Location anchorFor(Player player) {
        return player.getLocation().add(0.0,
                titleAnchorY(player.getHeight(), player.getEyeHeight(), nametagClearance.getAsDouble()), 0.0);
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
        // クライアント騎乗用のペア台帳(2026-08-19 / W-153)。乗騎・乗客どちらの id からも引けるようにする。
        int[] pair = {player.getEntityId(), display.getEntityId()};
        mountPairsByEntityId.put(pair[0], pair);
        mountPairsByEntityId.put(pair[1], pair);
        appliedTranslationY.remove(player.getUniqueId());
        syncMountTranslation(player, display);
    }

    /**
     * クライアント騎乗中の平行移動を現在の姿勢に合わせる(2026-08-19 / W-153)。
     *
     * <p>スニークや乗り物で {@code getHeight()} が縮むと取付点も称号の目標高さも動くので、
     * 差分である平行移動も動かす必要がある。metadata パケットになるため、
     * {@link #TRANSLATION_EPSILON} 以上動いたときだけ書く。
     *
     * <p>騎乗ブリッジが動いていない環境(packetevents 未導入)では<b>何もしない</b> ——
     * その場合の描画位置は実座標そのものなので、平行移動を足すと二重にせり上がる。
     */
    private void syncMountTranslation(Player player, TextDisplay display) {
        if (!mountBridgeActive) {
            return;
        }
        double translationY = mountTranslationY(
                player.getHeight(), player.getEyeHeight(), nametagClearance.getAsDouble());
        Double previous = appliedTranslationY.get(player.getUniqueId());
        if (previous != null && Math.abs(previous - translationY) < TRANSLATION_EPSILON) {
            return;
        }
        Transformation transformation = display.getTransformation();
        display.setTransformation(new Transformation(
                new Vector3f(0.0f, (float) translationY, 0.0f),
                transformation.getLeftRotation(),
                transformation.getScale(),
                transformation.getRightRotation()));
        appliedTranslationY.put(player.getUniqueId(), translationY);
    }

    private void despawn(UUID playerId) {
        TextDisplay display = active.remove(playerId);
        appliedTranslationY.remove(playerId);
        if (display != null) {
            int[] pair = mountPairsByEntityId.remove(display.getEntityId());
            if (pair != null) {
                mountPairsByEntityId.remove(pair[0]);
            }
        }
        safeRemove(display);
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
