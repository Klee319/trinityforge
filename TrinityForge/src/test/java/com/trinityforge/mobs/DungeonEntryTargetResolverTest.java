package com.trinityforge.mobs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@link DungeonEntryTargetResolver}(2026-07-27 鍵アイテムGUI入場対応): {@link DungeonGate} だけから
 * 転送先の種類を決める純粋なルールをBukkit無しで検証する。
 */
class DungeonEntryTargetResolverTest {

    @Test
    void elitemobsDungeonDelegatesRegardlessOfEntryLocation() {
        // aliases/content-packageを1つ以上持つゲートは、entry-locationが設定されていてもEM委譲が優先。
        EntryLocation entryLocation = new EntryLocation("some_world", 1, 2, 3, 0f, 0f);
        DungeonGate gate = new DungeonGate("dungeon_abyss", List.of("dungeon_abyss", "abyss_blueprint"),
                80, null, 1, null, entryLocation);

        EntryTarget target = DungeonEntryTargetResolver.resolve(gate);

        EntryTarget.ElitemobsDelegate delegate = assertInstanceOf(EntryTarget.ElitemobsDelegate.class, target);
        assertEquals("dungeon_abyss", delegate.contentPackageId());
    }

    @Test
    void explicitEntryLocationWins() {
        EntryLocation entryLocation = new EntryLocation("instance_world", 100.5, 64.0, -20.5, 0f, 0f);
        DungeonGate gate = new DungeonGate("dungeon_sanctum", List.of(), 60, "tf_crypt_sigil", 1,
                null, entryLocation);

        EntryTarget target = DungeonEntryTargetResolver.resolve(gate);

        EntryTarget.ExplicitLocation explicit = assertInstanceOf(EntryTarget.ExplicitLocation.class, target);
        assertEquals(entryLocation, explicit.location());
    }

    @Test
    void regionGateWithoutEntryLocationFallsBackToRegionCenter() {
        GateRegion region = new GateRegion("world", 1200, 0, -300, 1350, 128, -150);
        DungeonGate gate = new DungeonGate("field_ruins", List.of(), 25, null, 1, region, null);

        EntryTarget target = DungeonEntryTargetResolver.resolve(gate);

        EntryTarget.RegionCenter regionCenter = assertInstanceOf(EntryTarget.RegionCenter.class, target);
        assertEquals(region, regionCenter.region());
    }

    @Test
    void plainWorldGateFallsBackToWorldSpawn() {
        DungeonGate gate = new DungeonGate("dungeon_crypt_world", List.of(), 40, "TRIPWIRE_HOOK", 1);

        EntryTarget target = DungeonEntryTargetResolver.resolve(gate);

        EntryTarget.WorldSpawn worldSpawn = assertInstanceOf(EntryTarget.WorldSpawn.class, target);
        assertEquals("dungeon_crypt_world", worldSpawn.worldName());
    }
}
