package com.trinityforge.config.domains;

import com.trinityforge.mobs.DungeonGate;
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
        assertEquals("TRIPWIRE_HOOK", crypt.keyItem());
        assertEquals(2, crypt.keyAmount());
        assertTrue(crypt.keyRequired());

        DungeonGate open = result.gatesByWorld().get("dungeon_open");
        assertEquals(10, open.requiredCombatLevel());
        assertNull(open.keyItem());
        assertFalse(open.keyRequired());
    }

    @Test
    void keyItemTakesPrecedenceOverLegacyKeyMaterial() throws Exception {
        // 2026-07-27 カスタムアイテム鍵対応: key-item と旧 key-material が両方あれば key-item が勝つ。
        DungeonGate gate = parse("""
                gates:
                  dungeon_crypt:
                    required-combat-level: 40
                    key-item: tf_crypt_sigil
                    key-material: TRIPWIRE_HOOK
                """).gatesByWorld().get("dungeon_crypt");
        assertEquals("tf_crypt_sigil", gate.keyItem());
    }

    @Test
    void legacyKeyMaterialAloneStillLoads() throws Exception {
        // 後方互換: key-item が無ければ旧 key-material をそのまま keyItem として読む。
        DungeonGate gate = parse("""
                gates:
                  dungeon_crypt:
                    required-combat-level: 40
                    key-material: TRIPWIRE_HOOK
                """).gatesByWorld().get("dungeon_crypt");
        assertEquals("TRIPWIRE_HOOK", gate.keyItem());
        assertTrue(gate.keyRequired());
    }

    @Test
    void customCatalogKeyItemLoadsAsPlainString() throws Exception {
        // 2026-07-27: key-item はカタログID/ArsPaper IDでもよい。config ロード時点では解決せず、文字列
        // をそのまま保持する(解決は実行時の GateKeyMatcher に委ねる)。
        DungeonGate gate = parse("""
                gates:
                  d:
                    required-combat-level: 5
                    key-item: tf_core_meat
                """).gatesByWorld().get("d");
        assertEquals(5, gate.requiredCombatLevel());
        assertEquals("tf_core_meat", gate.keyItem());
        assertTrue(gate.keyRequired());
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
    void parsesEntryLocationWithExplicitWorld() throws Exception {
        // 2026-07-27 鍵アイテムGUI入場対応。
        DungeonGate gate = parse("""
                gates:
                  dungeon_sanctum:
                    required-combat-level: 0
                    entry-location:
                      world: instance_world
                      x: 100.5
                      y: 64.0
                      z: -20.5
                      yaw: 90.0
                      pitch: 10.0
                """).gatesByWorld().get("dungeon_sanctum");
        assertTrue(gate.hasEntryLocation());
        assertEquals("instance_world", gate.entryLocation().world());
        assertEquals(100.5, gate.entryLocation().x());
        assertEquals(64.0, gate.entryLocation().y());
        assertEquals(-20.5, gate.entryLocation().z());
        assertEquals(90.0f, gate.entryLocation().yaw());
        assertEquals(10.0f, gate.entryLocation().pitch());
    }

    @Test
    void entryLocationDefaultsWorldToGateId() throws Exception {
        DungeonGate gate = parse("""
                gates:
                  dungeon_sanctum:
                    required-combat-level: 0
                    entry-location:
                      x: 1.0
                      y: 2.0
                      z: 3.0
                """).gatesByWorld().get("dungeon_sanctum");
        assertTrue(gate.hasEntryLocation());
        assertEquals("dungeon_sanctum", gate.entryLocation().world());
        // yaw/pitch省略時は0.0。
        assertEquals(0.0f, gate.entryLocation().yaw());
        assertEquals(0.0f, gate.entryLocation().pitch());
    }

    @Test
    void entryLocationMissingCoordinateIsIgnoredButGateSurvives() throws Exception {
        // x/y/zが3つ揃っていないentry-locationは無視され警告のみ(ゲート自体はskipped扱いにしない)。
        DungeonGateConfig.ParseResult result = parse("""
                gates:
                  dungeon_sanctum:
                    required-combat-level: 5
                    entry-location:
                      x: 1.0
                      y: 2.0
                """);
        assertEquals(0, result.skipped());
        DungeonGate gate = result.gatesByWorld().get("dungeon_sanctum");
        assertFalse(gate.hasEntryLocation());
        assertNull(gate.entryLocation());
        assertEquals(5, gate.requiredCombatLevel());
    }

    @Test
    void noEntryLocationSectionYieldsNullEntryLocation() throws Exception {
        DungeonGate gate = parse("""
                gates:
                  dungeon_open:
                    required-combat-level: 10
                """).gatesByWorld().get("dungeon_open");
        assertFalse(gate.hasEntryLocation());
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
