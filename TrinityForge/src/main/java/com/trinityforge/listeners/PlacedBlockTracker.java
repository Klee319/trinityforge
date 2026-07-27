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
 */
public final class PlacedBlockTracker implements Listener {

    /**
     * 1チャンクあたりに記録する設置済みブロックの上限。8192 × 8バイト = 64KB。
     * 通常の建築（1チャンク内で数千個）は収まり、線形走査も数µsで済む水準。
     */
    static final int MAX_MARKS_PER_CHUNK = 8192;

    private final NamespacedKey placedBlocksKey;

    public PlacedBlockTracker(Plugin plugin) {
        this.placedBlocksKey = new NamespacedKey(plugin, "placed_blocks");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        markPlaced(event.getBlock());
    }

    public void markPlaced(Block block) {
        long packed = pack(block);
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        long[] existing = pdc.getOrDefault(placedBlocksKey, PersistentDataType.LONG_ARRAY, new long[0]);
        for (long value : existing) {
            if (value == packed) return;
        }
        pdc.set(placedBlocksKey, PersistentDataType.LONG_ARRAY, appendCapped(existing, packed));
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

    public boolean isPlaced(Block block) {
        long packed = pack(block);
        long[] existing = block.getChunk().getPersistentDataContainer()
                .getOrDefault(placedBlocksKey, PersistentDataType.LONG_ARRAY, new long[0]);
        for (long value : existing) {
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
        long[] existing = pdc.getOrDefault(placedBlocksKey, PersistentDataType.LONG_ARRAY, new long[0]);
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
