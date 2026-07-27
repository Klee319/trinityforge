package com.trinityforge.stats;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A crafting recipe authored on a {@code items/catalog.yml} entry ({@code recipe:} section, or one
 * element of the {@code recipes:} list when an item has several).
 *
 * <p>{@link Method#WORKBENCH} — Bukkit shaped/shapeless (registered by {@code CatalogRecipeRegistrar}).
 * Ingredients are {@link RecipeIngredient}s: plain materials or {@code custom:<catalogId>} references
 * matched by catalog identity at craft time ({@code CatalogWorkbenchListener}).
 * <p>{@link Method#INVENTORY} — インベントリ2×2クラフト。shape最大2行×2文字/shapeless最大4個。
 * {@code workbench} は今後「作業台専用(3×3)」= 2×2グリッドでは成立しない
 * ({@code CatalogWorkbenchListener} が明示的に弾く)。Bukkit登録自体は workbench と同じ経路
 * (ShapedRecipe/ShapelessRecipe) — 2×2形状ならバニラ同様インベントリでも作業台でも成立する。
 * <p>{@link Method#RITUAL} — Ars ritual (core / pedestals / source); registered via Ars
 * {@code CatalogRitualRegistrar}. Shape fields may be present for editor UX but are not used by the
 * ritual engine.
 * <p>{@link Method#COMBINE} — anvil (金床) combine:
 * {@code source-item} (left) + {@code addition-item} (right) → this catalog entry (result).
 * <p>{@link Method#NETHERITE} — smithing-table netherite upgrade (vanilla template + ingot required).
 *
 * <p>{@code reversible} — true のとき、圧縮レシピ(このエントリ = 結果)に対する逆レシピ(このエントリ
 * 1個 → 元の素材N個)を {@code CatalogRecipeRegistrar} が自動登録する。付与できるのは
 * workbench/inventory かつ「素材が全て同一アイテム」のレシピのみ — 前提を満たさない場合は
 * コンストラクタで黙って {@code false} に落ちる(fail-soft。呼び出し元の {@code ItemCatalogConfig}
 * が実際に効いたかを確認して警告する)。
 */
public record RecipeSpec(Method method,
                         Type type,
                         List<String> shape,
                         Map<Character, RecipeIngredient> shapedIngredients,
                         List<RecipeIngredient> shapelessIngredients,
                         int amount,
                         String coreItem,
                         List<String> pedestalItems,
                         int source,
                         boolean register,
                         String sourceItem,
                         String additionItem,
                         int combineExp,
                         boolean inheritSourceQuality,
                         boolean strictOrientation,
                         boolean reversible) {

    public static final int MAX_SHAPE_DIMENSION = 3;
    public static final int MAX_SHAPELESS_INGREDIENTS = 9;
    public static final int MAX_INVENTORY_DIMENSION = 2;
    public static final int MAX_INVENTORY_SHAPELESS_INGREDIENTS = 4;
    private static final char EMPTY_SLOT = ' ';

    public RecipeSpec {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(type, "type");
        shape = shape == null ? List.of() : List.copyOf(shape);
        shapedIngredients = shapedIngredients == null ? Map.of() : Map.copyOf(shapedIngredients);
        shapelessIngredients = shapelessIngredients == null ? List.of() : List.copyOf(shapelessIngredients);
        pedestalItems = pedestalItems == null ? List.of() : List.copyOf(pedestalItems);
        if (amount < 1) {
            throw new IllegalArgumentException("recipe amount must be >= 1: " + amount);
        }
        if (source < 0) {
            throw new IllegalArgumentException("ritual source must be >= 0: " + source);
        }
        if (combineExp < 0) {
            throw new IllegalArgumentException("combine-exp must be >= 0: " + combineExp);
        }
        if (sourceItem != null && sourceItem.isBlank()) {
            sourceItem = null;
        }
        if (additionItem != null && additionItem.isBlank()) {
            additionItem = null;
        }

        if (method == Method.WORKBENCH || method == Method.INVENTORY) {
            int maxDimension = method == Method.INVENTORY ? MAX_INVENTORY_DIMENSION : MAX_SHAPE_DIMENSION;
            int maxShapeless = method == Method.INVENTORY
                    ? MAX_INVENTORY_SHAPELESS_INGREDIENTS : MAX_SHAPELESS_INGREDIENTS;
            if (type == Type.SHAPED) {
                validateShaped(shape, shapedIngredients, maxDimension);
            } else {
                if (shapelessIngredients.isEmpty()) {
                    throw new IllegalArgumentException("shapeless recipe needs at least one ingredient");
                }
                if (shapelessIngredients.size() > maxShapeless) {
                    throw new IllegalArgumentException("shapeless recipe has more than "
                            + maxShapeless + " ingredients: " + shapelessIngredients.size());
                }
            }
        } else if (method == Method.RITUAL) {
            // Ritual: pedestal list is the primary input; empty pedestals + no core is allowed but useless.
            if (coreItem != null && coreItem.isBlank()) {
                coreItem = null;
            }
        } else if (method == Method.COMBINE) {
            if (sourceItem == null) {
                throw new IllegalArgumentException("combine recipe needs a non-blank source-item");
            }
            if (additionItem == null) {
                throw new IllegalArgumentException("combine recipe needs a non-blank addition-item");
            }
        } else if (method == Method.NETHERITE) {
            if (sourceItem == null) {
                throw new IllegalArgumentException("netherite recipe needs a non-blank source-item");
            }
        }

        // reversible: workbench/inventory かつ「素材が全て同一アイテム」の場合のみ有効。前提を
        // 満たさない場合はここで黙って false に落とす (fail-soft; ItemCatalogConfig が要求値との
        // 差分を見て警告する)。
        if (reversible) {
            boolean eligibleMethod = method == Method.WORKBENCH || method == Method.INVENTORY;
            reversible = eligibleMethod
                    && uniformIngredient(type, shape, shapedIngredients, shapelessIngredients).isPresent();
        }
    }

    private static void validateShaped(List<String> shape, Map<Character, RecipeIngredient> ingredients,
                                       int maxDimension) {
        if (shape.isEmpty()) {
            throw new IllegalArgumentException("shaped recipe needs a non-empty shape");
        }
        if (shape.size() > maxDimension) {
            throw new IllegalArgumentException("shaped recipe has more than "
                    + maxDimension + " rows: " + shape.size());
        }
        // Bukkit の ShapedRecipe#shape は全行が同じ長さ(矩形)であることを要求し、違反すると
        // IllegalArgumentException("Crafting recipes must be rectangular") を投げる。ここで先に
        // 検査して原因を明示するテスト/エディタ環境でも同じ例外を投げることで、CatalogRecipeRegistrar
        // 側の catch (RuntimeException) による黙殺(WARN 1行のみ)より前に、設定投入時点(config
        // 読み込み・テスト)で気づけるようにする。
        int expectedWidth = shape.get(0).length();
        for (String row : shape) {
            if (row.length() != expectedWidth) {
                throw new IllegalArgumentException("shaped recipe is not rectangular: row '" + row
                        + "' has length " + row.length() + " but first row has length " + expectedWidth
                        + " (all rows must be the same length; use spaces for empty slots)");
            }
        }
        for (String row : shape) {
            if (row.length() > maxDimension) {
                throw new IllegalArgumentException("shaped recipe row '" + row + "' is wider than "
                        + maxDimension + " columns");
            }
            for (char symbol : row.toCharArray()) {
                if (symbol == EMPTY_SLOT) {
                    continue;
                }
                if (!ingredients.containsKey(symbol)) {
                    throw new IllegalArgumentException(
                            "shaped recipe symbol '" + symbol + "' has no matching ingredient entry");
                }
            }
        }
    }

    /**
     * shaped の全非空スロット (shapeless は全要素) が単一の {@link RecipeIngredient} に揃っている
     * ときだけその ingredient を返す。{@code list:} ingredient は複数素材を許容し「同一アイテム」を
     * 一意に確定できないため対象外。
     */
    private static Optional<RecipeIngredient> uniformIngredient(Type type, List<String> shape,
            Map<Character, RecipeIngredient> shapedIngredients, List<RecipeIngredient> shapelessIngredients) {
        List<RecipeIngredient> used = new ArrayList<>();
        if (type == Type.SHAPED) {
            for (String row : shape) {
                for (int i = 0; i < row.length(); i++) {
                    char symbol = row.charAt(i);
                    if (symbol == EMPTY_SLOT) {
                        continue;
                    }
                    RecipeIngredient ingredient = shapedIngredients.get(symbol);
                    if (ingredient == null) {
                        return Optional.empty();
                    }
                    used.add(ingredient);
                }
            }
        } else {
            used.addAll(shapelessIngredients);
        }
        if (used.isEmpty()) {
            return Optional.empty();
        }
        RecipeIngredient first = used.get(0);
        if (first.isList() || used.stream().anyMatch(ingredient -> !ingredient.equals(first))) {
            return Optional.empty();
        }
        return Optional.of(first);
    }

    public enum Method {
        WORKBENCH,
        INVENTORY,
        RITUAL,
        COMBINE,
        NETHERITE
    }

    public enum Type {
        SHAPED,
        SHAPELESS
    }

    public boolean isRitual() {
        return method == Method.RITUAL;
    }

    /** 作業台専用(3×3)。2×2グリッドでは成立しない — {@code isBukkitCrafting()} と使い分けること。 */
    public boolean isWorkbench() {
        return method == Method.WORKBENCH;
    }

    /** インベントリ2×2クラフト(shape最大2×2/shapeless最大4)。作業台でも成立する。 */
    public boolean isInventory() {
        return method == Method.INVENTORY;
    }

    /** Bukkitの通常クラフト(ShapedRecipe/ShapelessRecipe)として登録すべきか = workbench || inventory。 */
    public boolean isBukkitCrafting() {
        return isWorkbench() || isInventory();
    }

    public boolean isCombine() {
        return method == Method.COMBINE;
    }

    public boolean isNetherite() {
        return method == Method.NETHERITE;
    }

    /** When false, recipe data is kept for editor/migration but not registered at runtime. */
    public boolean shouldRegister() {
        return register;
    }

    public static RecipeSpec shaped(List<String> shape, Map<Character, RecipeIngredient> ingredients, int amount) {
        return shaped(Method.WORKBENCH, shape, ingredients, amount);
    }

    /** {@code method} must be {@link Method#WORKBENCH} or {@link Method#INVENTORY}. */
    public static RecipeSpec shaped(Method method, List<String> shape,
                                    Map<Character, RecipeIngredient> ingredients, int amount) {
        return new RecipeSpec(method, Type.SHAPED, shape, new LinkedHashMap<>(ingredients),
                List.of(), amount, null, List.of(), 0, true, null, null, 0, false, false, false);
    }

    /** Convenience for material-only shaped recipes (tests / legacy callers). */
    public static RecipeSpec shapedMaterials(List<String> shape, Map<Character, Material> ingredients, int amount) {
        Map<Character, RecipeIngredient> converted = new LinkedHashMap<>();
        ingredients.forEach((symbol, material) -> converted.put(symbol, RecipeIngredient.ofMaterial(material)));
        return shaped(shape, converted, amount);
    }

    public static RecipeSpec shapeless(List<RecipeIngredient> ingredients, int amount) {
        return shapeless(Method.WORKBENCH, ingredients, amount);
    }

    /** {@code method} must be {@link Method#WORKBENCH} or {@link Method#INVENTORY}. */
    public static RecipeSpec shapeless(Method method, List<RecipeIngredient> ingredients, int amount) {
        return new RecipeSpec(method, Type.SHAPELESS, List.of(), Map.of(), ingredients, amount,
                null, List.of(), 0, true, null, null, 0, false, false, false);
    }

    /** Convenience for material-only shapeless recipes (tests / legacy callers). */
    public static RecipeSpec shapelessMaterials(List<Material> ingredients, int amount) {
        return shapeless(ingredients.stream().map(RecipeIngredient::ofMaterial).toList(), amount);
    }

    /** True when any workbench ingredient is a {@code custom:} catalog reference. */
    public boolean hasCustomIngredient() {
        return shapedIngredients.values().stream().anyMatch(RecipeIngredient::isCustom)
                || shapelessIngredients.stream().anyMatch(RecipeIngredient::isCustom);
    }

    public static RecipeSpec ritual(String coreItem, List<String> pedestalItems, int source, int amount) {
        return new RecipeSpec(Method.RITUAL, Type.SHAPELESS, List.of(), Map.of(), List.of(),
                Math.max(1, amount), coreItem, pedestalItems == null ? List.of() : pedestalItems,
                Math.max(0, source), true, null, null, 0, false, false, false);
    }

    /**
     * Anvil combine: left={@code sourceItem}, right={@code additionItem}, result=catalog entry
     * that owns this recipe.
     */
    public static RecipeSpec combine(String sourceItem, String additionItem, int combineExp,
                                     boolean inheritSourceQuality, int amount) {
        return new RecipeSpec(Method.COMBINE, Type.SHAPELESS, List.of(), Map.of(), List.of(),
                Math.max(1, amount), null, List.of(), 0, true, sourceItem, additionItem,
                Math.max(0, combineExp), inheritSourceQuality, false, false);
    }

    public static RecipeSpec netherite(String sourceItem, int amount) {
        return new RecipeSpec(Method.NETHERITE, Type.SHAPELESS, List.of(), Map.of(), List.of(),
                Math.max(1, amount), null, List.of(), 0, true, sourceItem, null, 0, false, false, false);
    }

    public RecipeSpec withRegister(boolean registerFlag) {
        return new RecipeSpec(method, type, shape, shapedIngredients, shapelessIngredients, amount,
                coreItem, pedestalItems, source, registerFlag, sourceItem, additionItem, combineExp,
                inheritSourceQuality, strictOrientation, reversible);
    }

    /**
     * 「登録した向き以外を拒否」フラグ付きコピー。shaped workbench のみ意味を持つ。
     * デフォルト false = バニラ同様に左右反転配置でもクラフト可能。
     * {@code strict-orientation: true} で {@code CatalogWorkbenchListener} が
     * 登録した向き以外 (左右反転配置) のクラフトを拒否する。
     */
    public RecipeSpec withStrictOrientation(boolean strictFlag) {
        return new RecipeSpec(method, type, shape, shapedIngredients, shapelessIngredients, amount,
                coreItem, pedestalItems, source, register, sourceItem, additionItem, combineExp,
                inheritSourceQuality, strictFlag, reversible);
    }

    /**
     * 逆レシピ(圧縮結果1個 → 元の素材N個)自動登録フラグ付きコピー。前提(workbench/inventory かつ
     * 素材が全て同一アイテム)を満たさない場合はコンストラクタが黙って {@code false} に落とす。
     */
    public RecipeSpec withReversible(boolean reversibleFlag) {
        return new RecipeSpec(method, type, shape, shapedIngredients, shapelessIngredients, amount,
                coreItem, pedestalItems, source, register, sourceItem, additionItem, combineExp,
                inheritSourceQuality, strictOrientation, reversibleFlag);
    }

    /**
     * 逆レシピの唯一の素材ingredient。{@link #reversible()} が false のときは {@code null}。
     * (コンストラクタで前提検証済みなので、reversible=true ならこの呼び出しは必ず非null。)
     */
    public RecipeIngredient reversibleIngredient() {
        if (!reversible) {
            return null;
        }
        return uniformIngredient(type, shape, shapedIngredients, shapelessIngredients).orElse(null);
    }

    /** 逆レシピの結果個数 = このレシピが消費した同一素材スロット数。reversible=false のときは 0。 */
    public int reversibleMaterialCount() {
        if (!reversible) {
            return 0;
        }
        if (type == Type.SHAPED) {
            int count = 0;
            for (String row : shape) {
                for (int i = 0; i < row.length(); i++) {
                    if (row.charAt(i) != EMPTY_SLOT) {
                        count++;
                    }
                }
            }
            return count;
        }
        return shapelessIngredients.size();
    }
}
