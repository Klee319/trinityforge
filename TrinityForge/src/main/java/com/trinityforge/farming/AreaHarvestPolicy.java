package com.trinityforge.farming;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code area-harvest}(範囲収穫)の対象オフセット算出。Bukkit非依存
 * ({@link com.trinityforge.mining.VeinMiningAlgorithm}/{@link com.trinityforge.mining.MiningGimmickPolicy}と
 * 同様、Worldを持たない純ロジックとして切り出し単体テスト可能にする)。
 *
 * <p>作物は常に耕地(同じY)の上にあるため、対象は水平X/Zのみの正方形: 半径{@code radius}のとき
 * {@code (2*radius+1)^2 - 1}マス(起点自身を除く)。既定値radius=1で3x3(8マス追加)。
 */
public final class AreaHarvestPolicy {

    private AreaHarvestPolicy() {
    }

    /** 起点からの相対オフセット(X/Zのみ、Yは起点と同じ前提)。 */
    public record Offset(int dx, int dz) {
    }

    /** {@code radius <= 0} は空リスト(=追加範囲なし、起点のみ収穫)。 */
    public static List<Offset> squareOffsets(int radius) {
        List<Offset> offsets = new ArrayList<>();
        if (radius <= 0) {
            return offsets;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                offsets.add(new Offset(dx, dz));
            }
        }
        return offsets;
    }
}
