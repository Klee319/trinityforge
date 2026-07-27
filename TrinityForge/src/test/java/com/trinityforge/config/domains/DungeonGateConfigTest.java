package com.trinityforge.config.domains;

import com.trinityforge.mobs.DungeonGate;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless parse checks for the dungeon entry-gate config (D2, Q4). */
class DungeonGateConfigTest {

    private static final Logger LOG = Logger.getLogger("DungeonGateConfigTest");

    private static DungeonGateConfig.ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return DungeonGateConfig.parse(cfg.getConfigurationSection("gates"), LOG);
    }

    @Test
    void parsesLevelAndOptionalKeyGate() throws Exception {
        DungeonGateConfig.ParseResult result = parse("""
                gates:
                  dungeon_crypt:
                    required-combat-level: 40
                    key-material: TRIPWIRE_HOOK
                    key-amount: 2
                  dungeon_open:
                    required-combat-level: 10
                """);
        assertEquals(0, result.skipped());

        DungeonGate crypt = result.gatesByWorld().get("dungeon_crypt");
        assertEquals(40, crypt.requiredCombatLevel());
        assertEquals(Material.TRIPWIRE_HOOK, crypt.keyMaterial());
        assertEquals(2, crypt.keyAmount());
        assertTrue(crypt.keyRequired());

        DungeonGate open = result.gatesByWorld().get("dungeon_open");
        assertEquals(10, open.requiredCombatLevel());
        assertNull(open.keyMaterial());
        assertFalse(open.keyRequired());
    }

    @Test
    void unknownKeyMaterialKeepsLevelGate() throws Exception {
        DungeonGate gate = parse("""
                gates:
                  d:
                    required-combat-level: 5
                    key-material: NOT_A_REAL_ITEM
                """).gatesByWorld().get("d");
        assertEquals(5, gate.requiredCombatLevel());
        assertNull(gate.keyMaterial()); // unknown material -> no key gate; level gate remains
    }

    @Test
    void missingSectionYieldsNoGates() {
        assertEquals(0, DungeonGateConfig.parse(null, LOG).gatesByWorld().size());
    }

    @Test
    void parsesRegionGateAndIndexesByRegionWorld() throws Exception {
        DungeonGateConfig.ParseResult result = parse("""
                gates:
                  field_ruins:
                    required-combat-level: 25
                    region:
                      world: world
                      min: [1350, 128, -150]
                      max: [1200, 0, -300]
                """);
        assertEquals(0, result.skipped());
        DungeonGate gate = result.gatesByWorld().get("field_ruins");
        assertTrue(gate.hasRegion());
        // min/max は正規化される(対角を順不同で書ける)。
        assertTrue(gate.region().contains("world", 1200, 0, -300));
        assertTrue(gate.region().contains("world", 1350, 128, -150));
        assertTrue(gate.region().contains("world", 1300, 60, -200));
        assertFalse(gate.region().contains("world", 1199, 60, -200));
        assertFalse(gate.region().contains("other_world", 1300, 60, -200));
        // 移動チェック用インデックスは region.world 名で引く(ゲート名ではない)。
        assertEquals(1, result.regionGatesByWorld().get("world").size());
    }

    @Test
    void invalidRegionKeepsWorldGate() throws Exception {
        DungeonGateConfig.ParseResult result = parse("""
                gates:
                  broken_region:
                    required-combat-level: 10
                    region:
                      world: world
                      min: [1, 2]
                      max: [3, 4, 5]
                """);
        assertEquals(1, result.skipped());
        DungeonGate gate = result.gatesByWorld().get("broken_region");
        // 壊れたregionはregion経路のみ無効化し、レベルゲート(world/エイリアス経路)は残す。
        assertFalse(gate.hasRegion());
        assertEquals(10, gate.requiredCombatLevel());
        assertTrue(result.regionGatesByWorld().isEmpty());
    }
}
