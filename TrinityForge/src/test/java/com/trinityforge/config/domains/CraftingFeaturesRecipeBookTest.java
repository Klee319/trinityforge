package com.trinityforge.config.domains;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code progression/crafting-features.yml} の {@code recipe-book} セクション (2026-07-31 D7)。
 * レシピ帳へのプラグインレシピ解禁を editor から on/off できるようにするためのトグル。
 */
class CraftingFeaturesRecipeBookTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CraftingFeaturesRecipeBookTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not be called when the file already exists on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static void write(File file, String content) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }

    @Test
    void bothTogglesDefaultToTrueWhenTheSectionIsAbsent(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        write(file, "coating:\n  base-max-stacks: 3\n");

        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        assertTrue(config.load(fakePlugin(tempDir)));
        assertTrue(config.recipeBookRevealPluginRecipes(),
                "セクション省略時は解禁ON (既存 yml をそのまま読んでも D7 の修正が効くこと)");
        assertTrue(config.recipeBookHideLockedRecipes());
    }

    @Test
    void togglesAreConfigDriven(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        write(file, "recipe-book:\n  reveal-plugin-recipes: false\n  hide-locked-recipes: false\n");

        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        assertTrue(config.load(fakePlugin(tempDir)));
        assertFalse(config.recipeBookRevealPluginRecipes());
        assertFalse(config.recipeBookHideLockedRecipes());
    }

    @Test
    void shippedYamlDeclaresTheSectionSoOperatorsAndTheEditorCanSeeIt() throws IOException {
        String shipped = Files.readString(
                new File("src/main/resources/" + CraftingFeaturesConfig.PATH).toPath(),
                StandardCharsets.UTF_8);

        assertTrue(shipped.contains("recipe-book:"),
                "出荷 yml にキーが無いと editor のフォームにも現れず、運営者はトグルの存在に気づけない");
        assertTrue(shipped.contains("reveal-plugin-recipes:"));
        assertTrue(shipped.contains("hide-locked-recipes:"));
    }

    @Test
    void shippedYamlKeepsBothTogglesEnabled() throws IOException {
        // 出荷値そのものを固定する(値だけ true→false へ書き換えられると D7 の修正が黙って無効化される)。
        // 出荷 yml 全体の load は Registry.ENCHANTMENT を要するためここでは走らせず、
        // recipe-book セクションのテキストだけを検証する。
        String shipped = Files.readString(
                new File("src/main/resources/" + CraftingFeaturesConfig.PATH).toPath(),
                StandardCharsets.UTF_8);
        int start = shipped.indexOf("recipe-book:");
        assertTrue(start >= 0);
        String section = shipped.substring(start);

        assertTrue(section.contains("reveal-plugin-recipes: true"));
        assertTrue(section.contains("hide-locked-recipes: true"));
    }
}
