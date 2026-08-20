package com.trinityforge.listeners;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks player-placed block positions per chunk via the chunk's {@link PersistentDataContainer},
 * so the mark survives a server restart. Used to prevent placed blocks from awarding gathering
 * EXP (place+break farming), per the mining/woodcutting/digging progression configs.
 *
 * <p>Positions are packed into a {@code long} using LOCAL chunk coordinates: {@code localX = blockX
 * & 15} (4 bits), {@code localZ = blockZ & 15} (4 bits), and the signed world {@code y} (kept in the
 * remaining bits, well within range for the -64..319 build limit). The per-chunk array is stored as
 * {@link PersistentDataType#LONG_ARRAY}.
 *
 * <p>Each operation ({@link #markPlaced}, {@link #isPlaced}, {@link #clearIfPlaced}) reads the
 * existing array (or treats it as empty), does a linear membership check, and — when changed —
 * writes back a new array. This is O(placed-in-chunk) per call, so the store is capped at
 * {@link #MAX_MARKS_PER_CHUNK} entries (see below).
 *
 * <p><b>2026-07-26 上限の追加（性能/DoS対策）:</b> 以前は1チャンクあたりの記録数が無制限だった。
 * チャンクは 16×16×384 = 98,304 ブロックあるので、埋め立てれば配列は約10万要素（約786KB）まで育つ。
 * 全操作が線形走査＋配列コピーなので、これは
 * <ul>
 *   <li>設置1回ごとにメインスレッドで10万回比較＋786KBコピー</li>
 *   <li>チャンクPDCが約786KBに膨らみ、リージョンファイルとチャンクのロード/セーブを圧迫</li>
 *   <li>とりわけ一括伐採/一括採掘は<b>1ブロックごとに</b> {@link #isPlaced} を呼ぶので、
 *       500ブロックの木×10万件＝5000万回比較となり、1tickのスパイクになる</li>
 * </ul>
 * を招く。上限に達したら<b>最古のものから捨てる</b>（FIFO）。捨てられたマークは「自然生成扱い」に
 * 戻るが、そのために同一チャンクへ {@value #MAX_MARKS_PER_CHUNK} 個もブロックを設置する必要があり、
 * 消費するブロック数のほうが得られる経験値より遥かに多いので、置き直しファームの旨味は生じない。
 *
 * <p><b>2026-07-31 G1 round2 指摘3（アロケーション側の穴）:</b> 上限だけでは
 * <em>比較回数</em>しか抑えられていなかった。PDC の {@code LONG_ARRAY} は copy-on-read なので
 * {@link #isPlaced} 1回ごとに配列の複製が1本できる（上限いっぱいなら64KB）。走査のように
 * 何百回も呼ぶ経路では {@link #newScanLookup()} を使い、<b>チャンクごとに1回だけ読む</b>こと。
 */
public final class PlacedBlockTracker implements Listener {

    /**
     * 1チャンクあたりに記録する設置済みブロックの上限。8192 × 8バイト = 64KB。
     * 通常の建築（1チャンク内で数千個）は収まり、線形走査も数µsで済む水準。
     */
    static final int MAX_MARKS_PER_CHUNK = 8192;

    /** マークが無いチャンクで返す共有の空配列。<b>呼び出し側が書き換えないこと。</b> */
    private static final long[] EMPTY_MARKS = new long[0];

    private final NamespacedKey placedBlocksKey;

    /**
     * PDC の {@code long[]} を実際に読んだ回数。{@link #chunkReadCount()} で観測する診断カウンタで、
     * 「走査1回のPDC読みが走査ブロック数に比例しない」ことをテストで縛るために使う
     * (2026-07-31 G1 round2 指摘3)。非同期から読まれ得るので {@link AtomicLong}。
     */
    private final AtomicLong chunkReads = new AtomicLong();

    public PlacedBlockTracker(Plugin plugin) {
        this.placedBlocksKey = new NamespacedKey(plugin, "placed_blocks");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        markPlaced(event.getBlock());
    }

    public void markPlaced(Block block) {
        long packed = pack(block);
        Chunk chunk = block.getChunk();
        long[] existing = marksIn(chunk);
        for (long value : existing) {
            if (value == packed) return;
        }
        chunk.getPersistentDataContainer()
                .set(placedBlocksKey, PersistentDataType.LONG_ARRAY, appendCapped(existing, packed));
    }

    /**
     * チャンクPDCの生の {@code long[]} を1回読む。<b>PDC の {@code LONG_ARRAY} は copy-on-read</b>
     * なので、この呼び出し1回ごとに配列の複製が1本できる(8192件なら64KB)。したがって
     * <em>呼ぶ回数がそのままアロケーション量</em>になる — 走査中は
     * {@link ScanLookup} でチャンクごとに1回だけ読むこと(2026-07-31 G1 round2 指摘3)。
     *
     * <p>返る配列は呼び出し側が書き換えてはいけない(欠損時は共有の空配列が返る)。
     */
    long[] marksIn(Chunk chunk) {
        chunkReads.incrementAndGet();
        return chunk.getPersistentDataContainer()
                .getOrDefault(placedBlocksKey, PersistentDataType.LONG_ARRAY, EMPTY_MARKS);
    }

    /**
     * PDC の {@code long[]} を実際に読んだ累計回数(診断/テスト用)。一括伐採1回でこの値が
     * 「走査したブロック数」ではなく「触ったチャンク数」だけ増えることが不変条件。
     */
    public long chunkReadCount() {
        return chunkReads.get();
    }

    /**
     * 走査1回のスコープで使う<b>読み取り専用ビュー</b>(2026-07-31 G1 round2 指摘3)。
     *
     * <p><b>なぜ必要か</b>: {@link #isPlaced(Block)} は呼ぶたびにチャンクPDCの {@code long[]} を
     * 丸ごと複製する(copy-on-read)。一括伐採の走査述語はマテリアルが一致した位置ごとに
     * — 最大 {@code scan-limit} 本 — これを呼ぶので、8192件のマークがあるチャンク(64KB)では
     * <b>斧を1回振るだけで 512 × 64KB ≒ 32MB</b> の短命オブジェクトがメインスレッドに乗っていた。
     * ここでチャンクごとに1回だけ読んでローカルに持てば、アロケーション量は
     * 「走査したブロック数」ではなく「触ったチャンク数」に比例する。
     *
     * <p>キャッシュは走査が終わったら捨てる(このオブジェクトを保持しないこと) —
     * 走査中に新しく設置されるブロックは無いが、跨いで持つと古い判定を返す。
     */
    public ScanLookup newScanLookup() {
        return new ScanLookup(this);
    }

    /** {@link #newScanLookup()} が返す、チャンク単位でキャッシュする {@code isPlaced} 判定器。 */
    public static final class ScanLookup {

        private final PlacedBlockTracker tracker;
        /** チャンク座標(x,z を1つの long へ詰めたもの) -&gt; そのチャンクのマーク配列。 */
        private final Map<Long, long[]> byChunk = new HashMap<>();

        private ScanLookup(PlacedBlockTracker tracker) {
            this.tracker = tracker;
        }

        /** {@link PlacedBlockTracker#isPlaced(Block)} と同じ判定。同じチャンクは1回しか読まない。 */
        public boolean isPlaced(Block block) {
            Chunk chunk = block.getChunk();
            long chunkKey = (((long) chunk.getX()) << 32) | (chunk.getZ() & 0xFFFFFFFFL);
            long[] marks = byChunk.get(chunkKey);
            if (marks == null) {
                marks = tracker.marksIn(chunk);
                byChunk.put(chunkKey, marks);
            }
            long packed = pack(block);
            for (long value : marks) {
                if (value == packed) return true;
            }
            return false;
        }
    }

    /**
     * {@code packed} を末尾に足した新しい配列を返す。上限を超える場合は先頭（＝最古）を落として
     * 長さを {@link #MAX_MARKS_PER_CHUNK} に保つ。上限以下の場合は単純な追記。
     */
    static long[] appendCapped(long[] existing, long packed) {
        if (existing.length < MAX_MARKS_PER_CHUNK) {
            long[] updated = new long[existing.length + 1];
            System.arraycopy(existing, 0, updated, 0, existing.length);
            updated[existing.length] = packed;
            return updated;
        }
        // 既に上限（もしくは旧バージョンが書いた上限超えの配列）。末尾側の MAX-1 件だけを残す。
        long[] updated = new long[MAX_MARKS_PER_CHUNK];
        System.arraycopy(existing, existing.length - (MAX_MARKS_PER_CHUNK - 1),
                updated, 0, MAX_MARKS_PER_CHUNK - 1);
        updated[MAX_MARKS_PER_CHUNK - 1] = packed;
        return updated;
    }

    /**
     * 単発の判定。<b>ループの中から呼ばないこと</b> — 1回ごとにチャンクPDCの {@code long[]} を複製する
     * ので、走査のように何百回も呼ぶ経路では {@link #newScanLookup()} を使う(2026-07-31 G1 round2 指摘3)。
     */
    public boolean isPlaced(Block block) {
        long packed = pack(block);
        for (long value : marksIn(block.getChunk())) {
            if (value == packed) return true;
        }
        return false;
    }

    /**
     * Removes the mark for {@code block} if present, keeping the per-chunk store bounded and
     * letting a future genuine-generation block placed at the same position reward EXP again.
     *
     * @return true if the block WAS marked as player-placed (and has now been cleared).
     */
    public boolean clearIfPlaced(Block block) {
        long packed = pack(block);
        Chunk chunk = block.getChunk();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        long[] existing = marksIn(chunk);
        int index = -1;
        for (int i = 0; i < existing.length; i++) {
            if (existing[i] == packed) {
                index = i;
                break;
            }
        }
        if (index < 0) return false;
        long[] updated = new long[existing.length - 1];
        System.arraycopy(existing, 0, updated, 0, index);
        System.arraycopy(existing, index + 1, updated, index, existing.length - index - 1);
        pdc.set(placedBlocksKey, PersistentDataType.LONG_ARRAY, updated);
        return true;
    }

    private static long pack(Block block) {
        int localX = block.getX() & 0xF;
        int localZ = block.getZ() & 0xF;
        long y = block.getY() & 0xFFFFFL;
        return (y << 8) | ((long) localX << 4) | localZ;
    }
}
