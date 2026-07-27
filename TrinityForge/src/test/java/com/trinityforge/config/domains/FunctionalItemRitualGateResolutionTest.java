package com.trinityforge.config.domains;

import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.effects.DedicatedEffectGateIndex;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 機能アイテム(functional-items.yml)統合(2026-07-25)後、{@code ars_smithing.yml} の
 * waystone/teleport_compass クラフト解放ゲートが正しいチャンネル(RITUAL_GATE)へ解決されることを
 * 固定するリグレッションテスト。
 *
 * <p>背景: 移行前の {@code recipe:waystone_craft} / {@code recipe:teleport_compass} は
 * {@link com.trinityforge.skilltree.effects.GateEffectId} により {@code RECIPE_GATE}
 * (作業台 {@code PrepareItemCraftEvent} 専用) にルーティングされていたが、儀式クラフトの権限判定
 * ({@code com.arspaper.recipe.UnlockGate#hasRitualPermission}、fork側)は {@code RITUAL_GATE}
 * ({@code ritual:} プレフィックス)しか見ないため、このゲートは実質無効化(fail-open)されていた。
 * 2026-07-25の統合で {@code ritual:waystone} / {@code ritual:teleport_compass} へ修正した
 * (idも "waystone_craft"→"waystone" にリネーム、functional-items.yml統合後のレシピidと一致させた)。
 * このテストは実際に出荷される {@code skilltree/ars_smithing.yml} を読み込み、正しいチャンネル・
 * targetへ解決されることを確認する。
 */
class FunctionalItemRitualGateResolutionTest {

    private static Plugin fakePlugin(File dataFolder, Logger logger) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> logger;
            case "saveResource" -> throw new AssertionError("saveResource() must not run headlessly");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static void copyAllSkillTrees(File dataFolder) throws IOException {
        File dir = new File(dataFolder, SkillTreeConfig.DIR);
        Files.createDirectories(dir.toPath());
        String[] fileNames = {
            "light_weapons.yml", "heavy_weapons.yml", "archery.yml", "light_armor.yml",
            "heavy_armor.yml", "ars_magic.yml", "mining.yml", "woodcutting.yml", "farming.yml",
            "enchanting.yml", "digging.yml", "smithing.yml", "alchemy.yml", "fishing.yml",
            "ars_smithing.yml", "power.yml"
        };
        for (String fileName : fileNames) {
            try (InputStream in = FunctionalItemRitualGateResolutionTest.class.getClassLoader()
                    .getResourceAsStream(SkillTreeConfig.DIR + "/" + fileName)) {
                assertNotNull(in, "bundled skilltree/" + fileName + " must be on the test classpath");
                Files.copy(in, new File(dir, fileName).toPath());
            }
        }
    }

    @Test
    @DisplayName("ars_smithing.yml: waystone/teleport_compass gates resolve into RITUAL_GATE, not RECIPE_GATE")
    void waystoneAndTeleportCompassResolveToRitualGate(@TempDir File dataFolder) throws IOException {
        copyAllSkillTrees(dataFolder);

        SkillTreeConfig config = new SkillTreeConfig();
        config.load(fakePlugin(dataFolder, Logger.getLogger("FunctionalItemRitualGateResolutionTest")));

        SkillTree arsSmithing = config.tree("ARS_SMITHING")
                .orElseThrow(() -> new AssertionError("ARS_SMITHING tree must load"));

        DedicatedEffectGateIndex index = DedicatedEffectGateIndex.build(List.of(arsSmithing));

        // 儀式クラフトの権限チェック(UnlockGate.hasRitualPermission, fork側)が読むのは
        // ritualGatePerks() のみ。ここに waystone/teleport_compass が乗っていることを確認する。
        Set<String> waystonePerks = index.ritualGatePerks().get("waystone");
        Set<String> compassPerks = index.ritualGatePerks().get("teleport_compass");
        assertNotNull(waystonePerks, "ritual:waystone must resolve to at least one gating perk");
        assertNotNull(compassPerks, "ritual:teleport_compass must resolve to at least one gating perk");
        assertTrue(!waystonePerks.isEmpty(), "waystone ritual gate perk set must not be empty");
        assertTrue(!compassPerks.isEmpty(), "teleport_compass ritual gate perk set must not be empty");
        assertEquals(waystonePerks, compassPerks,
                "both are declared on the same node (D-1), so they must gate on the identical perk set");

        // 旧経路(RECIPE_GATE)には、もう waystone/teleport_compass は乗っていないはず
        // (作業台PrepareItemCraftEventのゲート判定はritual craftのパスを一切見ないため、
        // ここに残っていると「意図せず二重定義・混乱の元」になる)。
        assertEquals(null, index.recipeGatePerks().get("waystone"));
        assertEquals(null, index.recipeGatePerks().get("waystone_craft"));
        assertEquals(null, index.recipeGatePerks().get("teleport_compass"));
    }
}
