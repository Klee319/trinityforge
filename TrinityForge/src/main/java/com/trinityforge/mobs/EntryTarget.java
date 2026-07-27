package com.trinityforge.mobs;

/**
 * 鍵アイテムGUI確定時の転送先決定結果(2026-07-27)。{@link DungeonEntryTargetResolver#resolve} が
 * {@link DungeonGate} だけから純粋に導出する。実際の {@link org.bukkit.Location} への変換
 * (ワールドロード確認・区画中心のY探索など、Bukkit依存の処理)は呼び出し側({@code DungeonEntryGui})が
 * 行う。
 */
public sealed interface EntryTarget {

    /** EliteMobsの連携ダンジョン参加処理へ委譲する(TF側では転送しない)。 */
    record ElitemobsDelegate(String contentPackageId) implements EntryTarget {
    }

    /** {@code gates.yml} の {@code entry-location:} で明示された座標へ転送する。 */
    record ExplicitLocation(EntryLocation location) implements EntryTarget {
    }

    /** 区画ゲート(entry-location未設定)は区画の中心へ転送する。 */
    record RegionCenter(GateRegion region) implements EntryTarget {
    }

    /** ワールドゲート(entry-location未設定・区画なし)は行き先ワールドのスポーン地点へ転送する。 */
    record WorldSpawn(String worldName) implements EntryTarget {
    }
}
