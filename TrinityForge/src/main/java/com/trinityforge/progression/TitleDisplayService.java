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

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;

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
 * {@code player.addPassenger(display)} でマウントし、{@code Transformation} の
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
 * <h2>2026-08-21: クライアント騎乗(W-153)は撤去した —— <b>名前が消える代償が大きすぎた</b></h2>
 * 実サーバ報告「ネームタグが表示されていない(他人の名前も見えない)」。切り分けで
 * <b>「称号を外している人のネームタグは出る」</b>ことが確認され、称号表示が原因と確定した。
 *
 * <p><b>重なり(W-174)ではない。</b> W-174 の幾何修正
 * ({@code 高さ + 0.5 + 0.25 + clearance}) が稼働 jar に入っていることは逆アセンブルで確認済みで、
 * 実サーバの clearance 0.1 でも称号はネームタグの 0.1 ブロック上に居る。それでも名前は出ない。
 *
 * <p><b>残った差分は騎乗だけだった。</b> 2026-08-03({@code a1dd403})〜2026-08-19 の間、称号は
 * 「毎tickテレポートで追従する独立エンティティ」で、同じ高さに出ていて<b>名前も見えていた</b>。
 * 2026-08-19(W-153)でクライアント騎乗を足した<b>翌日</b>に「称号が名前を消す」が報告され、
 * 高さの問題として直した(W-174)あとも消えたままだった ──
 * つまりプレイヤーを乗騎にすること自体がクライアント側でネームタグの描画を止めている。
 * 描画側の判定はサーバ jar に無いので機構そのものは断定できないが、
 * <b>騎乗の有無だけが「名前が出る/出ない」を分けている</b>ことは実サーバで確認済みの事実である。
 *
 * <p><b>だから騎乗は戻さないこと。</b> 騎乗が消していたのは「1tickぶんの追従の遅れ」であって、
 * 引き換えに消えるのは<b>プレイヤーの名前</b>という、この表示より遥かに重要な情報だった。
 * 追従は {@code teleport} + {@code teleport_duration} 補間に戻す(下の W-135 の記述どおり
 * 補間長を更新間隔に揃えてあるので<b>揺れ</b>は出ない。残るのは僅かな<b>遅れ</b>だけ)。
 * 再発防止の実行可能なガードは {@code TitleDisplayServiceTest} にある。
 *
 * <h2>2026-08-19 (W-153): 追従のズレを「クライアント騎乗」で消していた(撤去済み・記録のみ)</h2>
 * 実サーバ報告「称号の位置がネームタグの位置と同期していない。少し遅れてついてきている」。
 * <b>毎tickテレポート追従では原理的に直らない</b> ── クライアントはプレイヤー本体と表示体を
 * 別々に補間する(本体は移動パケットを既定3tickかけて補間、表示体は {@code teleport_duration} ぶん)ので、
 * 補間長をどう合わせても両者が同じ動きにはならない。W-135 で補間長を更新間隔に揃えて<b>揺れ</b>は
 * 消えたが、<b>ズレ</b>はこの理由で残っていた。
 *
 * <p>Paper のドキュメントが勧めるとおり表示体をパッセンジャーにすればズレは構造的に消えるが、
 * <b>サーバ側で本当に騎乗させると乗騎のテレポートが無言で失敗する</b>(PaperMC/Paper#10168。
 * {@code PlayerTeleportEvent} すら発火しないので他プラグインからは原因が見えない)。
 * そこで {@code TitleDisplayMountBridge}(撤去済み) が<b>パケットだけ</b>で騎乗させる ──
 * サーバ側は独立エンティティのままなのでテレポートを一切妨げない。
 * 騎乗中の描画基準は取付点(高さ×0.75)になるので、{@code mountTranslationY} が
 * 「置きたい絶対高さ − 取付点」を {@code Transformation} の平行移動として与える。
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
     * テレポート間をクライアント側で補間するtick数の<b>フォールバック</b>。
     * 実際の値は {@code progression/special-rewards.yml} の
     * {@code display.title-teleport-duration}（既定 3）から毎tick読む。
     *
     * <p><b>2026-08-24(W-212)に「補間長 = 更新間隔(1)」という規約をやめた。</b>
     * 2026-08-19(W-135)の理屈は「補間長が更新間隔より長いと、まだ終わっていない補間を
     * 毎tick上書きし続けて揺れる」だったが、<b>それはクライアントがプレイヤー本体に対して
     * やっていることそのもの</b>(移動パケットが届くたびに約3tickの補間をやり直す)で、
     * 本体は揺れて見えない。つまり揃えるべき相手は「自分が teleport を呼ぶ間隔」ではなく
     * <b>本体の補間長</b>だった。1 にすると称号だけが先に目的地へ着くので、
     * 走り出し・停止・方向転換のたびに頭からズレる。
     *
     * <p>⚠ 「パケットが数tickに1回しか来ない」たぐいの遅れではないことは確認済み ――
     * 稼働中の paper-1.21.11 の {@code EntityType} を逆アセンブルすると
     * {@code text_display} は {@code updateInterval(1)} で登録されており、位置更新は毎tick届く。
     *
     * <p>⚠ <b>自分の称号を F5(三人称)で見たときの遅れはここを何にしても消えない。</b>
     * 自分の本体だけはクライアントが予測して即座に描くのに対し、称号はサーバ由来なので
     * 必ず往復ぶん遅れる。他人から見えている位置はズレていない。
     */
    private static final int FALLBACK_TELEPORT_DURATION_TICKS = 3;
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

    private final Plugin plugin;
    /** プレイヤーの現在の装備称号MiniMessage文字列を返す(未装備/未保有なら null)。 */
    private final Function<Player, String> textResolver;
    /**
     * ネームタグ上端からさらに上へ空ける余白(ブロック)。config駆動、reloadで次tickから反映。
     * <b>負値も来る</b>(2026-08-24 / W-212。下限は {@code SpecialRewardsConfig} 側でクランプ済み)。
     */
    private final DoubleSupplier nametagClearance;
    /** 追従補間の長さ(tick)。config駆動、reloadで次tickから反映。 */
    private final IntSupplier teleportDurationTicks;
    private final Map<UUID, TextDisplay> active = new ConcurrentHashMap<>();
    /**
     * 迷子掃除の間隔(tick)。5秒 —— 二重表示に気付く前に消える速さと、
     * 近傍検索を毎tick回さない安さの折衷。
     */
    private static final long ORPHAN_SWEEP_PERIOD_TICKS = 100L;
    /**
     * 迷子掃除で見る半径(ブロック)。称号はプレイヤーの位置に湧くので、
     * 「今誰かに見えている迷子」はプレイヤーの近傍にしか居ない。
     * 全ワールド総なめ({@link #sweepOrphans})を定期実行すると人数と規模で効いてくるので使わない。
     */
    private static final double ORPHAN_SWEEP_RADIUS = 16.0;

    private BukkitTask task;
    private BukkitTask orphanSweepTask;

    public TitleDisplayService(Plugin plugin, Function<Player, String> textResolver, DoubleSupplier nametagClearance) {
        this(plugin, textResolver, nametagClearance, () -> FALLBACK_TELEPORT_DURATION_TICKS);
    }

    public TitleDisplayService(Plugin plugin, Function<Player, String> textResolver,
                               DoubleSupplier nametagClearance, IntSupplier teleportDurationTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.textResolver = Objects.requireNonNull(textResolver, "textResolver");
        this.nametagClearance = Objects.requireNonNull(nametagClearance, "nametagClearance");
        this.teleportDurationTicks = Objects.requireNonNull(teleportDurationTicks, "teleportDurationTicks");
    }

    /**
     * config から補間長を読む。壊れた値(負)はフォールバックへ落とす ――
     * {@code setTeleportDuration} に負値を渡すと {@code IllegalArgumentException} で
     * 毎tick例外を吐き、称号の追従が丸ごと止まる。
     */
    private int resolvedTeleportDuration() {
        int ticks = teleportDurationTicks.getAsInt();
        return ticks >= 0 ? ticks : FALLBACK_TELEPORT_DURATION_TICKS;
    }

    /**
     * 称号行を置く<b>足元からの</b>高さ。
     *
     * <p>{@code playerHeight + 0.5} がバニラのネームタグの描画高さ(<b>中心</b>)。そこへ
     * {@link #NAMETAG_LINE_HEIGHT}(名前の行を跨ぐぶん)と {@code clearance}(設定で足す余白)を
     * 足したところに称号の<b>中心</b>を置く。
     *
     * <p><b>2026-08-24(W-212)に不変条件を緩めた。</b> それまでは
     * 「返り値は常に {@code playerHeight + 0.5 + NAMETAG_LINE_HEIGHT} 以上」＝
     * 名前に絶対重ならないことを保証していた(W-174 の再発防止)。しかし実サーバの余白は既に 0 で、
     * <b>それでも実機では高すぎる</b>という報告(「y座標をあと0.3くらい下げたい」)が来た。
     * この式の {@code NAMETAG_LINE_HEIGHT} は「1行の高さ 0.25」という<b>見積り</b>であって
     * 実測ではないので、見積りが過大なら余白 0 でも隙間が残る。
     * そこで負の余白を許し、下限は {@code SpecialRewardsConfig} 側
     * ({@code MIN_TITLE_NAMETAG_CLEARANCE = -0.35}) で持つことにした。
     * 今の保証は「<b>名前を完全に覆う位置までは下げられない</b>」に弱まっている ――
     * 名前が読みにくくなったら {@code display.nametag-clearance} を 0 へ戻すこと。
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
        // ⚠ 2026-08-24(W-212): 負値を FALLBACK へ落とさない。
        //   落としていたせいで「0 まで下げた人が更に下げようとすると逆に 0.4 上がる」という
        //   最悪の挙動になっていた。下限のクランプは SpecialRewardsConfig 側の責務。
        //   ここで弾くのは非有限値(NaN/∞)だけ ―― teleport 先が NaN になると追従が丸ごと壊れる。
        double gap = Double.isFinite(clearance) ? clearance : FALLBACK_CLEARANCE;
        return Math.max(height, eyes) + VANILLA_NAMETAG_OFFSET + NAMETAG_LINE_HEIGHT + gap;
    }

    public void start() {
        sweepOrphans();
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
        }
        if (orphanSweepTask == null) {
            orphanSweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweepNearbyOrphans,
                    ORPHAN_SWEEP_PERIOD_TICKS, ORPHAN_SWEEP_PERIOD_TICKS);
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (orphanSweepTask != null) {
            orphanSweepTask.cancel();
            orphanSweepTask = null;
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
                // ⚠️ 追跡から外すだけでは【実体が残る】(2026-08-21 実サーバ報告「称号が二個付いている」)。
                // isValid() が false になるのは「死んだ」ときだけではない ── CraftEntity#isValid() は
                // チャンクがロード済みでワールドのエンティティリストに載っていることまで見るので、
                // 遠距離テレポート直後や湧かせた直後にも false になる。そこで実体を消さずに
                // 張り直すと、誰も追跡していない称号が世界に残り、新しいほうと二段に並ぶ。
                despawn(playerId);
                refresh(player);
                continue;
            }
            Location anchor = anchorFor(player);
            if (!Objects.equals(display.getWorld(), anchor.getWorld())) {
                refresh(player);
                continue;
            }
            // 補間長は config 駆動(既定3 = クライアントがプレイヤー本体を補間するのと同じ長さ)。
            // 毎tick読み直しているので /trinityforge reload が次tickから効く(W-212)。
            display.setTeleportDuration(resolvedTeleportDuration());
            display.teleport(anchor);
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
            d.setTeleportDuration(resolvedTeleportDuration());
            d.text(text);
        });
        active.put(player.getUniqueId(), display);
    }

    private void despawn(UUID playerId) {
        safeRemove(active.remove(playerId));
    }

    /**
     * 表示体を確実に消す。
     *
     * <p><b>{@code isValid()} で門を張ってはいけない</b>(2026-08-21「称号が二個付いている」の真因の片割れ)。
     * {@code CraftEntity#isValid()} は「生きている」に加えて<b>チャンクがロード済みで、ワールドの
     * エンティティリストに登録済み</b>まで要求する。つまり<b>消したい相手が一番消えにくい状況</b>
     * (プレイヤーが遠くへ飛んだ直後・湧かせた直後でまだ登録前・ワールド跨ぎの最中)でだけ false になり、
     * そこで諦めると表示体は誰にも追跡されないまま残る。
     * {@code Entity#remove()} は既に消えている個体へ呼んでも安全なので、素通しでよい。
     */
    static void safeRemove(Entity entity) {
        if (entity != null) {
            entity.remove();
        }
    }

    /**
     * 近傍の<b>迷子の称号</b>(TF の印を持つのに誰の追跡下にも無い {@link TextDisplay})を消す。
     *
     * <p>{@link #sweepOrphans} が起動時の1回きりなのに対し、こちらは常時走る安全網。
     * 迷子を作る経路を個別に塞いでも、称号の表示体は「消し損ねても何のエラーも出ない」ので、
     * 次に同種の穴が空いたときに気付けるのは<b>プレイヤーの目視だけ</b>になる。
     * 追跡中の個体は entity id で除外する ── ここを間違えると自分の称号を毎5秒消して回る。
     */
    void sweepNearbyOrphans() {
        Set<Integer> tracked = trackedEntityIds();
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeOrphans(player.getNearbyEntities(
                    ORPHAN_SWEEP_RADIUS, ORPHAN_SWEEP_RADIUS, ORPHAN_SWEEP_RADIUS), tracked);
        }
    }

    /** 今このサービスが追跡している表示体の entity id。 */
    Set<Integer> trackedEntityIds() {
        Set<Integer> ids = new HashSet<>();
        for (TextDisplay display : active.values()) {
            if (display != null) {
                ids.add(display.getEntityId());
            }
        }
        return ids;
    }

    /**
     * {@code candidates} のうち「TF の称号の印を持つのに {@code trackedEntityIds} に居ない」個体を消す。
     * Bukkit の生成に触れないので単体で試験できる(MockBukkit は {@code TextDisplay} の spawn を
     * 未実装で、踏むとテストが FAILED ではなく SKIPPED に化ける)。
     *
     * @return 消した数
     */
    static int removeOrphans(Collection<Entity> candidates, Set<Integer> trackedEntityIds) {
        if (candidates == null) {
            return 0;
        }
        int removed = 0;
        for (Entity entity : candidates) {
            if (!(entity instanceof TextDisplay display)) {
                continue;
            }
            if (!display.getPersistentDataContainer().has(PdcKeys.TITLE_DISPLAY, PersistentDataType.BYTE)) {
                continue;
            }
            if (trackedEntityIds != null && trackedEntityIds.contains(display.getEntityId())) {
                continue;
            }
            safeRemove(display);
            removed++;
        }
        return removed;
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
