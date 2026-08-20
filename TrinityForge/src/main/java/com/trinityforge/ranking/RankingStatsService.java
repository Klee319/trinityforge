package com.trinityforge.ranking;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.CollectionService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * ランキング用集計値の読み書き。バックエンド（メイン／資源）をまたいで同じ値を返すための層。
 *
 * <p>読み出しの方針は 2 段構え。
 * <ul>
 *   <li><b>このサーバにログイン中</b>なら PDC／統計から<b>その場で</b>読む（常に最新）。</li>
 *   <li>それ以外（他サーバにいる／オフライン）は共有 DB のミラーから読む。</li>
 * </ul>
 * ミラーの更新は「定期フラッシュ」と「ログアウト時」の 2 経路。
 * <b>ログイン時には書かない</b> — HuskSync がデータを流し込むのは {@code PlayerJoinEvent} より後なので、
 * その時点で読むと同期前のローカル値を掴む。仮に掴んでも
 * {@link RankingMirrorStore} 側が単調増加でしか更新しないので共有値は壊れないが、
 * 無駄な書き込みなので行わない。
 */
public final class RankingStatsService implements Listener {

    /** プレースホルダは 1 tick に何度も呼ばれうるので、ミラー読み出しは短時間だけ再利用する。 */
    private static final long READ_CACHE_TTL_MILLIS = 5_000L;

    /** 定期フラッシュの初回遅延（tick）。HuskSync の流し込みが終わるだけの余裕を取る。 */
    private static final long FLUSH_INITIAL_DELAY_TICKS = 20L * 30;

    /** 定期フラッシュの間隔（tick）。他サーバから見た値の最大遅延がこの間隔になる。 */
    private static final long FLUSH_PERIOD_TICKS = 20L * 60;

    /**
     * ArsPaper 側が解放済みグリフを書いている PDC キーの区切り文字（U+001F / UNIT SEPARATOR）。
     * <b>正規表現エスケープとして書いている</b>のが重要 — 生の制御文字をソースに置くと
     * エディタや差分ツールを通る間に消え、{@code split("")} に化けて 1 文字ずつ分割されてしまう。
     */
    private static final String GLYPH_LEGACY_DELIMITER_REGEX = "\\u001F";

    /**
     * ArsPaper の {@code ManaKeys.UNLOCKED_GLYPHS}。ここで複製しているのは TF が ArsPaper に
     * ソフト依存しかしていないため（{@link NamespacedKey} と PDC だけならクラス依存が要らない）。
     * {@code CrossPluginItemResolver} の {@code arspaper:custom_item_id} と同じ流儀。
     */
    private static final NamespacedKey ARS_UNLOCKED_GLYPHS =
            new NamespacedKey("arspaper", "unlocked_glyphs");

    private final Plugin plugin;
    private final RankingMirrorStore store;
    private final LongSupplier clockMillis;
    private final Map<UUID, CacheEntry> readCache = new ConcurrentHashMap<>();
    private BukkitTask flushTask;

    private record CacheEntry(RankingStats stats, long stampMillis) {}

    private record Pending(UUID playerId, String playerName, RankingStats stats) {}

    public RankingStatsService(Plugin plugin, RankingMirrorStore store) {
        this(plugin, store, System::currentTimeMillis);
    }

    RankingStatsService(Plugin plugin, RankingMirrorStore store, LongSupplier clockMillis) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.store = Objects.requireNonNull(store, "store");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** 定期フラッシュを開始する。{@code onEnable} から 1 回だけ呼ぶ。 */
    public void start() {
        if (flushTask != null) {
            return;
        }
        this.flushTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> flushAsync(plugin.getServer().getOnlinePlayers()),
                FLUSH_INITIAL_DELAY_TICKS, FLUSH_PERIOD_TICKS);
    }

    /**
     * 最後のフラッシュを<b>同期で</b>行ってから停止する。
     * {@code onDisable} 中は非同期タスクを投げられない（{@code IllegalPluginAccessException}）ため、
     * ここだけはメインスレッドで書き切る。
     */
    public void shutdown() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        writeAll(collect(plugin.getServer().getOnlinePlayers()));
        readCache.clear();
    }

    // ---- 読み出し ------------------------------------------------------------------

    /**
     * ランキング表示用の集計値を返す。このサーバにログイン中なら実データ、
     * そうでなければ共有 DB のミラーから読む。
     */
    public RankingStats stats(OfflinePlayer player) {
        if (player == null) {
            return RankingStats.EMPTY;
        }
        Player online = player.getPlayer();
        // PDC / 統計の読み出しは Bukkit API なのでメインスレッド限定。
        // PlaceholderAPI は非同期からも呼ぶため、プライマリスレッド確認は必須。
        if (online != null && Bukkit.isPrimaryThread()) {
            return capture(online);
        }
        return stats(player.getUniqueId());
    }

    /** UUID 指定でミラーから読む（オフライン／他サーバのプレイヤー）。 */
    public RankingStats stats(UUID playerId) {
        if (playerId == null) {
            return RankingStats.EMPTY;
        }
        long now = clockMillis.getAsLong();
        CacheEntry hit = readCache.get(playerId);
        if (hit != null && now - hit.stampMillis() < READ_CACHE_TTL_MILLIS) {
            return hit.stats();
        }
        try {
            RankingStats loaded = store.load(playerId);
            readCache.put(playerId, new CacheEntry(loaded, now));
            return loaded;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "ランキングミラーの読み出しに失敗しました: " + playerId, e);
            // 直前の値があればそれを使う。無ければ 0（＝まだ写されていない扱い）。
            return hit != null ? hit.stats() : RankingStats.EMPTY;
        }
    }

    /**
     * ログイン中のプレイヤーから集計値を組み立てる。<b>メインスレッド専用</b>
     * （PDC とバニラ統計はどちらも Bukkit API）。
     */
    public RankingStats capture(Player player) {
        Objects.requireNonNull(player, "player");
        int items = 0;
        int mobs = 0;
        for (String entry : PlayerData.of(player).collectionEntries()) {
            if (entry.startsWith(CollectionService.ENTRY_PREFIX_ITEM)) {
                items++;
            } else if (entry.startsWith(CollectionService.ENTRY_PREFIX_MOB)) {
                mobs++;
            }
        }
        return new RankingStats(items, mobs, glyphsUnlocked(player), mobKills(player));
    }

    // ---- 書き込み ------------------------------------------------------------------

    /** 進行リセット時に呼ぶ。単調増加更新のため、消さないと古い値が残り続ける。 */
    public void forget(UUID playerId) {
        if (playerId == null) {
            return;
        }
        readCache.remove(playerId);
        try {
            store.delete(playerId);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "ランキングミラーの削除に失敗しました: " + playerId, e);
        }
    }

    /**
     * ログアウト時に写す。この時点ではまだ {@code player} から PDC も統計も読めるので、
     * 他サーバへ移動した場合でも移動前の値が共有 DB に残る。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        flushAsync(List.of(event.getPlayer()));
    }

    private void flushAsync(Collection<? extends Player> players) {
        List<Pending> pending = collect(players);
        if (pending.isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> writeAll(pending));
    }

    /** メインスレッドで Bukkit API から値を集める。SQLite には触らない。 */
    private List<Pending> collect(Collection<? extends Player> players) {
        List<Pending> pending = new ArrayList<>(players.size());
        for (Player player : players) {
            try {
                pending.add(new Pending(player.getUniqueId(), player.getName(), capture(player)));
            } catch (RuntimeException e) {
                // 1 人分の失敗で全員分のフラッシュを落とさない。
                plugin.getLogger().log(Level.WARNING,
                        "ランキング集計の取得に失敗しました: " + player.getName(), e);
            }
        }
        return pending;
    }

    /** SQLite へ書く。非同期スレッド、またはシャットダウン中のメインスレッドから呼ばれる。 */
    private void writeAll(List<Pending> pending) {
        for (Pending entry : pending) {
            try {
                store.save(entry.playerId(), entry.playerName(), entry.stats());
                // 次の読み出しで確実に新しい値を返すため、キャッシュは捨てる。
                readCache.remove(entry.playerId());
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "ランキングミラーの書き込みに失敗しました: " + entry.playerName(), e);
            }
        }
    }

    // ---- 個別の読み取り ------------------------------------------------------------

    private static int mobKills(Player player) {
        try {
            return player.getStatistic(Statistic.MOB_KILLS);
        } catch (RuntimeException | LinkageError e) {
            // MockBukkit は統計を未実装。本番では起きない。
            return 0;
        }
    }

    private int glyphsUnlocked(Player player) {
        String raw = player.getPersistentDataContainer()
                .get(ARS_UNLOCKED_GLYPHS, PersistentDataType.STRING);
        return countGlyphs(raw);
    }

    /**
     * ArsPaper の解放済みグリフ数を数える。
     *
     * <p><b>同じ PDC キーに 2 つの形式が書かれうる。</b> 現行の書き込み側
     * （{@code ScribingTableGui#saveUnlockedGlyphs}）は Gson の JSON 配列だが、
     * fork には同じキーを U+001F 区切りで読む {@code UnlockedGlyphs} も残っている。
     * どちらを掴んでも数えられるようにしておく（片方しか見ないと解放数が黙って 0 になる）。
     */
    static int countGlyphs(String raw) {
        if (raw == null) {
            return 0;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        if (trimmed.charAt(0) == '[') {
            try {
                JsonElement parsed = JsonParser.parseString(trimmed);
                if (parsed.isJsonArray()) {
                    return parsed.getAsJsonArray().size();
                }
            } catch (RuntimeException ignored) {
                // 壊れた JSON。0 として扱う（例外にすると解放数だけでログインが荒れる）。
            }
            return 0;
        }
        int count = 0;
        for (String token : trimmed.split(GLYPH_LEGACY_DELIMITER_REGEX)) {
            if (!token.isBlank()) {
                count++;
            }
        }
        return count;
    }
}
