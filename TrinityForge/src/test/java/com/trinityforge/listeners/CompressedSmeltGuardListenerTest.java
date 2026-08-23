package com.trinityforge.listeners;

import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Recipe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保険リスナーの「差し替えるべきか」判定。ここを間違えると症状が真逆に出る:
 * <ul>
 *   <li>TF のレシピを他人扱いすると、正常に焼けているのに毎回結果を差し替え、
 *       結果スロットの中身と食い違って<b>連続精錬が止まる</b>。</li>
 *   <li>バニラのレシピを TF 扱いすると保険が効かず、<b>9 個分が 1 個へ消える</b>。</li>
 * </ul>
 */
class CompressedSmeltGuardListenerTest {

    private static Recipe keyedRecipe(NamespacedKey key) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getKey" -> key;
            case "getResult" -> null;
            case "toString" -> "FakeRecipe(" + key + ")";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Recipe) Proxy.newProxyInstance(Recipe.class.getClassLoader(),
                new Class<?>[] {Recipe.class, Keyed.class}, handler);
    }

    @Test
    @DisplayName("trinityforge 名前空間のレシピ = 本命なので差し替えない")
    void ownRecipeIsRecognized() {
        assertTrue(CompressedSmeltGuardListener.isOurRecipe(
                keyedRecipe(new NamespacedKey("trinityforge", "compressed_smelt_potato_1x_furnace"))));
    }

    @Test
    @DisplayName("バニラ/他プラグインのレシピは差し替え対象")
    void foreignRecipesAreNotOurs() {
        assertFalse(CompressedSmeltGuardListener.isOurRecipe(
                keyedRecipe(new NamespacedKey("minecraft", "baked_potato"))));
    }

    @Test
    @DisplayName("キーを持たない/null のレシピも差し替え対象(fail-safe)")
    void unkeyedRecipeIsNotOurs() {
        assertFalse(CompressedSmeltGuardListener.isOurRecipe(null));
    }
}
