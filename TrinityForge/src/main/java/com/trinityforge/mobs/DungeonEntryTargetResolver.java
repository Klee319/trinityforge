package com.trinityforge.mobs;

import java.util.Objects;

/**
 * 鍵アイテムGUI確定時、{@link DungeonGate} だけから転送先の種類を決める純粋なルール(2026-07-27)。
 * Bukkit に一切依存しないため単体テストが容易。優先順位:
 * <ol>
 *   <li>{@link DungeonGate#isElitemobsDungeon()}(aliases/content-packageを1つ以上持つ) →
 *       {@link EntryTarget.ElitemobsDelegate} — TF側では転送せずEliteMobsの参加処理へ委譲する。</li>
 *   <li>{@link DungeonGate#hasEntryLocation()} → {@link EntryTarget.ExplicitLocation}。</li>
 *   <li>{@link DungeonGate#hasRegion()} → {@link EntryTarget.RegionCenter}。</li>
 *   <li>いずれでもない → {@link EntryTarget.WorldSpawn}(行き先ワールドのスポーン地点)。</li>
 * </ol>
 */
public final class DungeonEntryTargetResolver {

    private DungeonEntryTargetResolver() {
    }

    public static EntryTarget resolve(DungeonGate gate) {
        Objects.requireNonNull(gate, "gate");
        if (gate.isElitemobsDungeon()) {
            // aliases はEliteMobsのcontent-package名の並び(aliases:リストが先、content-packageが末尾)。
            // どれもEMPackage索引上は同じダンジョンを指すので先頭を代表として使う。
            return new EntryTarget.ElitemobsDelegate(gate.aliases().get(0));
        }
        if (gate.hasEntryLocation()) {
            return new EntryTarget.ExplicitLocation(gate.entryLocation());
        }
        if (gate.hasRegion()) {
            return new EntryTarget.RegionCenter(gate.region());
        }
        return new EntryTarget.WorldSpawn(gate.world());
    }
}
