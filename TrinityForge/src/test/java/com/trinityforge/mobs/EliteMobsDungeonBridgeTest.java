package com.trinityforge.mobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link EliteMobsDungeonBridge#normalizeContentPackageId}(2026-07-27): TFの{@code content-package}/
 * {@code aliases}表記(拡張子なし)を、EliteMobsの{@code EMPackage.getEmPackages()}索引キー(常に
 * {@code .yml}拡張子付き)へ揃える正規化。実障害("getFilename()は必ず.yml付き")の再発防止テスト。
 */
class EliteMobsDungeonBridgeTest {

    @Test
    void appendsYmlWhenExtensionMissing() {
        assertEquals("dungeon_crypt.yml", EliteMobsDungeonBridge.normalizeContentPackageId("dungeon_crypt"));
    }

    @Test
    void doesNotDoubleAppendWhenYmlExtensionAlreadyPresent() {
        assertEquals("dungeon_crypt.yml", EliteMobsDungeonBridge.normalizeContentPackageId("dungeon_crypt.yml"));
    }

    @Test
    void doesNotDoubleAppendWhenYamlExtensionAlreadyPresent() {
        assertEquals("dungeon_crypt.yaml", EliteMobsDungeonBridge.normalizeContentPackageId("dungeon_crypt.yaml"));
    }

    @Test
    void isCaseInsensitiveWhenDetectingExistingExtension() {
        assertEquals("dungeon_crypt.YML", EliteMobsDungeonBridge.normalizeContentPackageId("dungeon_crypt.YML"));
    }

    @Test
    void nullInputYieldsNull() {
        assertNull(EliteMobsDungeonBridge.normalizeContentPackageId(null));
    }
}
