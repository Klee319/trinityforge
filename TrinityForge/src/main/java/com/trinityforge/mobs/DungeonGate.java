package com.trinityforge.mobs;

import java.util.List;
import java.util.Objects;

/**
 * One dungeon's entry gate (DUNGEON_SPEC D2, decision Q4): a combat-level requirement (primary) plus
 * an optional consumed key item. The primary lookup key is the destination world name; optional
 * {@code aliases} map EliteMobs content-package filenames (stable blueprint names) to the same gate
 * so TF and EliteMobs share one config ({@code dungeon/gates.yml}).
 *
 * <p>D3 topology: an optional {@code region} makes this a 区画ダンジョン gate — entry is enforced when
 * the player crosses INTO the region (move or teleport), instead of / in addition to a cross-world
 * teleport into {@code world}. Region gates use the gate name only as an identifier (it need not be a
 * real world name).
 *
 * <p>2026-07-27 カスタムアイテム鍵対応: {@code keyItem} は TF カタログID / ArsPaper 登録ID / バニラ
 * {@link org.bukkit.Material} 名のいずれかを表す文字列(解決は {@code CrossPluginItemResolver} に一本化、
 * {@link GateKeyMatcher} 参照)。以前は {@code Material} 型そのものだったが、カスタム品を鍵にできないと
 * いう欠落を解消するため文字列IDへ置き換えた(バニラ鍵は今まで通り {@code Material} 名の文字列として
 * 表現できる)。
 *
 * <p>2026-07-27 鍵アイテムGUI入場対応: 任意の {@code entryLocation} は、鍵アイテム右クリック→GUI確定
 * 経路での転送先を明示指定する({@code gates.yml} の {@code entry-location:})。{@code null} のときは
 * {@code DungeonEntryTargetResolver} が既定の解決規則(EliteMobs連携ダンジョンは委譲/区画ゲートは区画
 * 中心/それ以外はワールドスポーン)にフォールバックする。
 *
 * <p>A {@code requiredCombatLevel} of 0 disables the level gate; a null/blank {@code keyItem} disables
 * the key gate.
 *
 * <p>2026-08-02 表示名対応: {@code displayName} は {@code gates.yml} の任意 {@code display-name:} から
 * 読む(プレイヤー向けGUI専用、判定・スコープ解決には一切使わない — {@code mob-overrides.yml} の
 * {@code display-name} と同じ流儀)。未設定時は {@link #displayNameOrWorld()} が {@code world}
 * (ゲートID)へフォールバックする。
 *
 * @param world               primary lookup key (destination world name, or a label for region gates)
 * @param aliases             optional EliteMobs content-package names (case-insensitive lookup)
 * @param requiredCombatLevel minimum combat level to enter (0 = no level gate)
 * @param keyItem             item id consumed on entry (catalog id / ArsPaper id / vanilla Material
 *                            name), or {@code null} for no key gate
 * @param keyAmount           how many of {@code keyItem} are required/consumed (>= 1)
 * @param region              in-place dungeon boundary, or {@code null} for a world-keyed gate
 * @param entryLocation       explicit GUI-entry teleport target, or {@code null} for the default rule
 * @param displayName         optional player-facing name (表示専用), or {@code null} to fall back to
 *                            {@code world}
 */
public record DungeonGate(String world, List<String> aliases, int requiredCombatLevel,
                          String keyItem, int keyAmount, GateRegion region, EntryLocation entryLocation,
                          String displayName) {

    public DungeonGate {
        Objects.requireNonNull(world, "world");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        if (requiredCombatLevel < 0) {
            requiredCombatLevel = 0;
        }
        if (keyAmount < 1) {
            keyAmount = 1;
        }
        if (keyItem != null && keyItem.isBlank()) {
            keyItem = null;
        }
        if (displayName != null && displayName.isBlank()) {
            displayName = null;
        }
    }

    /** Back-compat constructor without a display name (pre-2026-08-02 callers/tests). */
    public DungeonGate(String world, List<String> aliases, int requiredCombatLevel,
                       String keyItem, int keyAmount, GateRegion region, EntryLocation entryLocation) {
        this(world, aliases, requiredCombatLevel, keyItem, keyAmount, region, entryLocation, null);
    }

    /** Back-compat constructor without an entry-location (pre-2026-07-27 GUI-entry callers). */
    public DungeonGate(String world, List<String> aliases, int requiredCombatLevel,
                       String keyItem, int keyAmount, GateRegion region) {
        this(world, aliases, requiredCombatLevel, keyItem, keyAmount, region, null, null);
    }

    /** Back-compat constructor without a region (world/alias-keyed gate). */
    public DungeonGate(String world, List<String> aliases, int requiredCombatLevel,
                       String keyItem, int keyAmount) {
        this(world, aliases, requiredCombatLevel, keyItem, keyAmount, null, null, null);
    }

    /** Back-compat constructor without aliases. */
    public DungeonGate(String world, int requiredCombatLevel, String keyItem, int keyAmount) {
        this(world, List.of(), requiredCombatLevel, keyItem, keyAmount, null, null, null);
    }

    public boolean keyRequired() {
        return keyItem != null;
    }

    public boolean hasRegion() {
        return region != null;
    }

    /** Whether this gate participates in an EliteMobs instanced dungeon (aliases/content-package set). */
    public boolean isElitemobsDungeon() {
        return !aliases.isEmpty();
    }

    public boolean hasEntryLocation() {
        return entryLocation != null;
    }

    /** プレイヤー向け表示名。未設定なら {@code world}(ゲートID)へフォールバックする。 */
    public String displayNameOrWorld() {
        return displayName != null ? displayName : world;
    }
}
