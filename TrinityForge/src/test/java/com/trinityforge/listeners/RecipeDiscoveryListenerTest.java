package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RecipeDiscoveryListener} (2026-07-31 D7): レシピ帳に TF/Ars のレシピが1件も出ない件の修正。
 * 真因は「{@code Bukkit.addRecipe} しても discover していない」ことなので、ここで固定するのは
 * 「解禁対象の振り分け」と「ゲート未解放は隠す」の2点。
 */
class RecipeDiscoveryListenerTest {

    private static final NamespacedKey OPEN = new NamespacedKey("trinityforge", "catalog_compressed_wood_1x");
    private static final NamespacedKey GATED = new NamespacedKey("trinityforge", "catalog_tf_core_wood");
    private static final NamespacedKey DECOMPRESS =
            new NamespacedKey("trinityforge", "catalog_compressed_wood_1x_decompress");
    private static final NamespacedKey ADDED = new NamespacedKey("trinityforge", "added_1");
    private static final NamespacedKey ARS = new NamespacedKey("arspaper", "source_gem_block");
    private static final NamespacedKey NON_CATALOG = new NamespacedKey("trinityforge", "skill_gui_marker");

    // ---- 純関数の振り分け (Bukkit サーバ不要) ----

    @Test
    void unGatedKeysAreAllRevealed() {
        RecipeDiscoveryListener.Plan plan = RecipeDiscoveryListener.plan(
                List.of(OPEN, DECOMPRESS, ADDED, ARS), Set.of(), id -> false);

        assertEquals(List.of(OPEN, DECOMPRESS, ADDED, ARS), plan.reveal());
        assertTrue(plan.hide().isEmpty());
    }

    @Test
    void gatedKeyIsHiddenWhilePlayerLacksThePerk() {
        RecipeDiscoveryListener.Plan plan = RecipeDiscoveryListener.plan(
                List.of(OPEN, GATED), Set.of("tf_core_wood"), id -> false);

        assertEquals(List.of(OPEN), plan.reveal());
        assertEquals(List.of(GATED), plan.hide(),
                "解放していない recipe:<id> ゲートを解禁すると「レシピ帳に出るのに結果枠が空」になる");
    }

    @Test
    void gatedKeyIsRevealedOnceThePlayerHoldsThePerk() {
        RecipeDiscoveryListener.Plan plan = RecipeDiscoveryListener.plan(
                List.of(OPEN, GATED), Set.of("tf_core_wood"), "tf_core_wood"::equals);

        assertEquals(List.of(OPEN, GATED), plan.reveal());
        assertTrue(plan.hide().isEmpty());
    }

    @Test
    void anIdNoSkillTreeReferencesStaysOpenEvenWithoutThePerk() {
        // CatalogCraftGateListener の既定: どのノードも配置していない recipe:<id> は常に開放。
        RecipeDiscoveryListener.Plan plan = RecipeDiscoveryListener.plan(
                List.of(GATED), Set.of("some_other_recipe"), id -> false);

        assertEquals(List.of(GATED), plan.reveal());
        assertTrue(plan.hide().isEmpty());
    }

    @Test
    void arsNamespaceKeysUseTheKeyPathAsTheGateId() {
        RecipeDiscoveryListener.Plan plan = RecipeDiscoveryListener.plan(
                List.of(ARS), Set.of("source_gem_block"), id -> false);

        assertEquals(List.of(ARS), plan.hide(),
                "arspaper: のレシピも CatalogCraftGateListener と同じ path=gateId 規約でゲートされる");
    }

    @Test
    void nonCatalogTrinityforgeKeysAreNeverGateable() {
        RecipeDiscoveryListener.Plan plan = RecipeDiscoveryListener.plan(
                List.of(NON_CATALOG), Set.of("skill_gui_marker"), id -> false);

        assertEquals(List.of(NON_CATALOG), plan.reveal(),
                "trinityforge: で catalog_ 接頭辞を持たないキーは gate id へ解決しない");
    }

    // ---- リスナー本体 (Player はモック) ----

    @Test
    void reconcileDiscoversRevealedAndUndiscoversLockedRecipes() {
        Player player = mock(Player.class);
        RecipeDiscoveryListener listener = listener(
                features(true, true), gatedPerks(player, "tf_core_wood", false),
                List.of(OPEN, GATED), List.of());

        listener.reconcile(player);

        verify(player).discoverRecipes(List.of(OPEN));
        verify(player).undiscoverRecipes(List.of(GATED));
    }

    @Test
    void reconcileDoesNothingWhenTheToggleIsOff() {
        Player player = mock(Player.class);
        RecipeDiscoveryListener listener = listener(
                features(false, true), gatedPerks(player, "tf_core_wood", false),
                List.of(OPEN, GATED), List.of());

        listener.reconcile(player);

        verify(player, never()).discoverRecipes(anyCollection());
        verify(player, never()).undiscoverRecipes(anyCollection());
    }

    @Test
    void hideLockedRecipesOffStillRevealsButNeverUndiscovers() {
        Player player = mock(Player.class);
        RecipeDiscoveryListener listener = listener(
                features(true, false), gatedPerks(player, "tf_core_wood", false),
                List.of(OPEN, GATED), List.of());

        listener.reconcile(player);

        verify(player).discoverRecipes(List.of(OPEN));
        verify(player, never()).undiscoverRecipes(anyCollection());
    }

    @Test
    void catalogLedgerAndServerScanAreUnionedWithoutDuplicates() {
        Player player = mock(Player.class);
        RecipeDiscoveryListener listener = listener(
                features(true, true), gatedPerks(player, "nothing", true),
                List.of(OPEN, ARS), List.of(ARS, ADDED));

        listener.reconcile(player);

        verify(player).discoverRecipes(List.of(OPEN, ARS, ADDED));
    }

    @Test
    void pluginNamespacesCoverBothTrinityforgeAndArspaper() {
        assertTrue(RecipeDiscoveryListener.PLUGIN_NAMESPACES.contains("trinityforge"));
        assertTrue(RecipeDiscoveryListener.PLUGIN_NAMESPACES.contains("arspaper"));
        assertFalse(RecipeDiscoveryListener.PLUGIN_NAMESPACES.contains("minecraft"),
                "バニラのレシピ帳解禁は minecraft:recipes/ 進捗が配っているので触らない");
    }

    private static RecipeDiscoveryListener listener(CraftingFeaturesConfig features,
                                                    DedicatedEffectsConfig dedicatedEffects,
                                                    Collection<NamespacedKey> catalogKeys,
                                                    Collection<NamespacedKey> serverKeys) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("RecipeDiscoveryListenerTest"));
        return new RecipeDiscoveryListener(plugin, dedicatedEffects, features,
                () -> catalogKeys, () -> serverKeys);
    }

    private static CraftingFeaturesConfig features(boolean reveal, boolean hideLocked) {
        CraftingFeaturesConfig features = mock(CraftingFeaturesConfig.class);
        when(features.recipeBookRevealPluginRecipes()).thenReturn(reveal);
        when(features.recipeBookHideLockedRecipes()).thenReturn(hideLocked);
        return features;
    }

    private static DedicatedEffectsConfig gatedPerks(Player player, String gateId, boolean active) {
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.recipeGatePerks()).thenReturn(Map.of(gateId, Set.of("perk")));
        when(dedicatedEffects.isActive(any(Player.class), eq("recipe:" + gateId))).thenReturn(active);
        return dedicatedEffects;
    }
}
