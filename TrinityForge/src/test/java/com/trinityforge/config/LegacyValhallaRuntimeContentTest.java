package com.trinityforge.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

class LegacyValhallaRuntimeContentTest {

    private static final Set<String> FORBIDDEN_CMDS = Set.of(
            "1981820", "1981821", "1981822", "1981825", "1981826", "1981827",
            "8778210", "8778212", "8778214", "8778216", "8778218",
            "8778220", "8778224", "8778226", "8778228", "8778232",
            "9928310", "9928311", "9928312", "9928313", "9928314",
            "8892630", "1998270");

    @Test
    void operationalConfigsContainNoNonGuiValhallaItemsRecipesOrCmds() throws IOException {
        List<Path> files = new ArrayList<>();
        files.add(Path.of("src/main/resources/items/catalog.yml"));
        files.add(Path.of("src/main/resources/stats/item-stats.yml"));
        try (var progression = Files.list(Path.of("src/main/resources/skills/base"))) {
            progression.filter(path -> path.toString().endsWith(".yml")).forEach(files::add);
        }
        files.add(Path.of("../fork-handoff/arspaper/fork/src/main/resources/materials.yml"));

        for (Path file : files) {
            String content = Files.readString(file);
            assertFalse(content.contains("recipes_unlock"), file + " retains Valhalla recipe unlocks");
            assertFalse(content.contains("valhallammo:"), file + " retains Valhalla recipe keys");
            assertFalse(content.contains("choral_leather"), file + " retains choral leather");
            for (String cmd : FORBIDDEN_CMDS) {
                assertFalse(content.contains(cmd), file + " retains Valhalla CMD " + cmd);
            }
        }
    }

    @Test
    void catalogContainsNoLegacyValhallaItemIds() throws IOException {
        // 2026-07-23: dagger/rapier/morningstar/warhammer/long_spear/greataxe の各ティアは
        // TFネイティブ武器として catalog に正式再実装されたため禁止リストから除外した
        // (Valhalla撤廃時の残骸検出は arrows/vial 系のみ継続)。
        String catalog = Files.readString(Path.of("src/main/resources/items/catalog.yml"));
        List<String> ids = new ArrayList<>(List.of(
                "wooden_arrows", "flint_arrows", "stone_arrows", "copper_arrows",
                "golden_arrows", "iron_arrows", "diamond_arrows", "teleport_arrows",
                "netherite_arrows", "removeimmunity_arrows",
                "vial", "vial_antiheal", "vial_poison", "vial_hurt", "vial_holy"));
        for (String id : ids) {
            assertFalse(catalog.contains(id), "catalog retains Valhalla item " + id);
        }
    }

    @Test
    void catalogContainsNativeWeaponSeries() throws IOException {
        // ネイティブ再実装した武器シリーズが誤って削除されないことのガード。
        // 2026-07-23: 槍(spear_tf)/長槍(long_spear)シリーズはユーザーが意図的にカタログから
        // 削除したためガード対象から除外 (dagger/rapier/morningstar/warhammer/greataxe は継続)。
        // 2026-07-25: モーニングスター(morningstar)シリーズも同様にユーザーがエディタ経由で
        // 全ティア削除したためガード対象から除外 (dagger/rapier/warhammer/greataxe は継続)。
        String catalog = Files.readString(Path.of("src/main/resources/items/catalog.yml"));
        for (String tier : List.of(
                "wooden", "stone", "copper", "golden", "iron", "diamond", "netherite")) {
            for (String family : List.of(
                    "dagger", "rapier", "warhammer", "greataxe")) {
                org.junit.jupiter.api.Assertions.assertTrue(
                        catalog.contains(tier + "_" + family + ":"),
                        "catalog missing native weapon " + tier + "_" + family);
            }
        }
    }
}
