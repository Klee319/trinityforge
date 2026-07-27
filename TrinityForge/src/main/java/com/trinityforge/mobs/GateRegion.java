package com.trinityforge.mobs;

import java.util.Objects;

/**
 * 区画ダンジョン(同一ワールド内 in-place ダンジョン)の入場境界 (DUNGEON_SPEC D3 topology)。
 * ワールド名 + ブロック座標のAABB。コンストラクタで min/max を正規化するので、config には
 * 任意の2対角を書けばよい。Bukkit-free なので純粋にユニットテスト可能。
 */
public record GateRegion(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public GateRegion {
        Objects.requireNonNull(world, "world");
        if (minX > maxX) {
            int t = minX;
            minX = maxX;
            maxX = t;
        }
        if (minY > maxY) {
            int t = minY;
            minY = maxY;
            maxY = t;
        }
        if (minZ > maxZ) {
            int t = minZ;
            minZ = maxZ;
            maxZ = t;
        }
    }

    /** ブロック座標がこの区画内(境界含む)にあるか。ワールド名は完全一致。 */
    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }
}
