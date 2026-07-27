package com.trinityforge.listeners;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code VanillaAdvancementBlockListener#shouldBlock} の判定順(2026-07-28、achievements.yml
 * vanilla-advancements): disabled → namespace → recipes/ → keep前方一致 → ブロック の5分岐を1件ずつ検証する。
 */
class VanillaAdvancementBlockListenerTest {

    // 1. disabled が false → 素通り
    @Test
    void disabledFalse_neverBlocks() {
        assertFalse(VanillaAdvancementBlockListener.shouldBlock(
                false, "minecraft", "story/mine_diamond", true, List.of()));
    }

    // 2. namespace が minecraft でない → 素通り(データパック/他プラグインは巻き込まない)
    @Test
    void nonMinecraftNamespace_isNeverBlocked() {
        assertFalse(VanillaAdvancementBlockListener.shouldBlock(
                true, "somepack", "custom/thing", true, List.of()));
    }

    // 3. keepRecipeAdvancements=true かつ path が recipes/ で始まる → 素通り
    @Test
    void recipeAdvancement_isKeptWhenFlagEnabled() {
        assertFalse(VanillaAdvancementBlockListener.shouldBlock(
                true, "minecraft", "recipes/decorations/torch", true, List.of()));
    }

    // recipes/ でも keepRecipeAdvancements=false ならブロックされる(レシピ本解禁が止まる警告どおり)。
    @Test
    void recipeAdvancement_isBlockedWhenFlagDisabled() {
        assertTrue(VanillaAdvancementBlockListener.shouldBlock(
                true, "minecraft", "recipes/decorations/torch", false, List.of()));
    }

    // 4. keep のいずれか(namespace:path のフルキー)に前方一致 → 素通り
    @Test
    void keepPrefixMatch_isNeverBlocked() {
        assertFalse(VanillaAdvancementBlockListener.shouldBlock(
                true, "minecraft", "story/mine_diamond", true, List.of("minecraft:story/")));
    }

    @Test
    void keepPrefixNoMatch_fallsThroughToBlock() {
        assertTrue(VanillaAdvancementBlockListener.shouldBlock(
                true, "minecraft", "nether/root", true, List.of("minecraft:story/")));
    }

    // 5. それ以外 → ブロック
    @Test
    void otherwiseBlocked() {
        assertTrue(VanillaAdvancementBlockListener.shouldBlock(
                true, "minecraft", "end/kill_dragon", true, List.of()));
    }

    @Test
    void nullKeepListIsTreatedAsEmpty() {
        assertTrue(VanillaAdvancementBlockListener.shouldBlock(
                true, "minecraft", "end/kill_dragon", true, null));
    }
}
