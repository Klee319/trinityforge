package com.trinityforge.mobs;

import java.util.Objects;

/**
 * ダンジョンゲートの明示的な入場先座標(2026-07-27 鍵アイテムGUI入場対応、{@code gates.yml} の
 * {@code entry-location:} セクション)。Bukkit-free なピュアな値オブジェクトで、実際の
 * {@link org.bukkit.Location} への変換(ワールド解決含む)は呼び出し側({@code DungeonEntryGui})が行う。
 *
 * @param world 転送先ワールド名(YAML側で省略された場合、ゲートID(=行き先ワールド名)がそのまま入る —
 *              デフォルト適用は {@code DungeonGateConfig} のパース時点で完了している)
 * @param x     ブロック座標X
 * @param y     ブロック座標Y
 * @param z     ブロック座標Z
 * @param yaw   向き(省略時0.0)
 * @param pitch 向き(省略時0.0)
 */
public record EntryLocation(String world, double x, double y, double z, float yaw, float pitch) {

    public EntryLocation {
        Objects.requireNonNull(world, "world");
    }
}
