package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CatalogIdentity;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import com.trinityforge.stats.DerivedItemStats;
import com.trinityforge.stats.ExternalItemRegistry;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.MaterialLists;
import com.trinityforge.stats.RecipeIngredient;
import com.trinityforge.stats.RecipeSpec;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-slot identity enforcement for catalog workbench recipes with {@code custom:} ingredients.
 *
 * <p>{@code CatalogRecipeRegistrar} registers {@code custom:} ingredients as material choices (so
 * Bukkit still selects the recipe even after an item gained extra PDC — quality stamp, owner bind).
 * That makes vanilla matching too permissive in both directions:
 * <ul>
 *   <li>a plain-material grid can select a recipe that wanted catalog items (9 vanilla stones
 *       selecting the {@code stone_2x} recipe), and</li>
 *   <li>a catalog-item grid can select a sibling recipe that wanted plain materials (9 compressed
 *       {@code stone_1x} selecting the {@code stone_1x} recipe again).</li>
 * </ul>
 * This listener re-validates the selected catalog recipe against the actual grid (catalog identity
 * per slot: PDC {@code catalogId} first, material+CustomModelData fallback; plain-material cells
 * must NOT hold a catalog item). On mismatch it re-matches the grid across all registered catalog
 * workbench recipes and swaps in the correct result, or clears the preview when nothing fits.
 */
public final class CatalogWorkbenchListener implements Listener {

    private final CatalogRecipeRegistrar registrar;
    private final ItemCatalogConfig catalog;

    public CatalogWorkbenchListener(CatalogRecipeRegistrar registrar, ItemCatalogConfig catalog) {
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Recipe selected = event.getRecipe();
        Optional<CatalogRecipeRegistrar.RegisteredRecipe> ours = registeredOf(selected);
        ItemStack[] matrix = event.getInventory().getMatrix();
        boolean gridHasCatalogItem = gridHasCatalogItem(matrix);

        if (ours.isEmpty()) {
            // Not our recipe. Only intervene when NO recipe matched but catalog items sit in the
            // grid — a catalog recipe may still fit (vanilla matching can miss nothing here since
            // we register material choices, but stay defensive for other plugins clearing it).
            if (selected != null || !gridHasCatalogItem) {
                return;
            }
            rematch(event, matrix);
            return;
        }

        CatalogRecipeRegistrar.RegisteredRecipe registered = ours.get();
        // workbench(作業台専用)レシピは2×2グリッドでは成立しない。早期returnをここで抑止して
        // 下の matches() (= isWorkbench && 2×2 で false を返す) まで必ず通す。
        boolean workbenchBlockedIn2x2 = registered.spec().isWorkbench() && matrix.length == 4;
        if (!workbenchBlockedIn2x2
                && !registered.spec().hasCustomIngredient() && !gridHasCatalogItem
                && !needsOrientationCheck(registered.spec())) {
            return; // plain recipe, plain grid, mirror irrelevant — vanilla matching is exact enough
        }
        if (matches(matrix, matrix.length == 4 ? 2 : 3, registered.spec())) {
            return;
        }
        // Wrong pick — try the sibling catalog recipes, then the vanilla recipe we shadowed.
        if (rematch(event, matrix)) {
            return;
        }
        ItemStack vanilla = shadowedVanillaResult(matrix, matrix.length == 4 ? 2 : 3);
        event.getInventory().setResult(vanilla); // null = クラフト不可(従来どおり)
    }

    /**
     * Crafter (自動作業台, 1.21) 経路の防御。{@code PrepareItemCraftEvent} はCrafterでは発火しない
     * ため、ここで同じ per-slot 識別 + 向き検証を行う。これが無いと (a) バニラ素材だけで
     * {@code custom:} 素材要求レシピが成立する識別バイパス (Bukkit登録はMaterialChoiceのため。
     * 例: バニラ石9個→圧縮石)、(b) {@code strict-orientation} の反転配置拒否の回避、が可能になる。
     * 盤面が仕様に合わない場合は兄弟カタログレシピへ再マッチし、無ければクラフトをキャンセルする。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafterCraft(org.bukkit.event.block.CrafterCraftEvent event) {
        Optional<CatalogRecipeRegistrar.RegisteredRecipe> ours = registeredOf(event.getRecipe());
        if (ours.isEmpty()) {
            return; // バニラ/他プラグインのレシピには介入しない (Prepare経路と同方針)
        }
        ItemStack[] matrix;
        try {
            if (!(event.getBlock().getState() instanceof org.bukkit.block.Crafter crafter)) {
                return;
            }
            matrix = crafter.getInventory().getContents();
        } catch (RuntimeException ex) {
            return; // 盤面を取得できない場合は介入しない (バニラ挙動へフォールバック)
        }
        CatalogRecipeRegistrar.RegisteredRecipe registered = ours.get();
        if (!registered.spec().hasCustomIngredient() && !gridHasCatalogItem(matrix)
                && !needsOrientationCheck(registered.spec())) {
            return;
        }
        if (matches(matrix, 3, registered.spec())) {
            return;
        }
        for (CatalogRecipeRegistrar.RegisteredRecipe candidate : registrar.allRegistered()) {
            if (matches(matrix, 3, candidate.spec())) {
                event.setResult(registrarResult(candidate.template(), candidate.spec()));
                return;
            }
        }
        ItemStack vanilla = shadowedVanillaResult(matrix, 3);
        if (vanilla != null) {
            event.setResult(vanilla);
            return;
        }
        event.setCancelled(true);
    }

    // ------------------------------------------------------------------
    // 影に入ったバニラレシピの復元 (2026-07-28)
    // ------------------------------------------------------------------

    /**
     * <b>「バニラのレシピが無言で作れなくなる」バグの修正 (2026-07-28)</b>。
     *
     * <p>{@code custom:}/{@code list:} 素材は {@link CatalogRecipeRegistrar} で
     * {@link org.bukkit.inventory.RecipeChoice.MaterialChoice}(=材質のみ照合)として登録されるため、
     * カタログレシピは<b>素のバニラ素材だけの盤面にも Bukkit 側では一致してしまう</b>。
     * Bukkit は一致した中から 1 つしかレシピを返さないので、そこでカタログレシピが選ばれると
     * 同じ盤面に一致する<b>バニラレシピは選択肢ごと消える</b>。そのあと per-slot 検証が
     * 「カタログ品ではない」と正しく弾き、兄弟にも合わずに結果をクリアしていたため、
     * バニラレシピが結果枠の空白として無言で死んでいた
     * (ArsPaper 側で {@code plank_scrap} が {@code minecraft:crafting_table} を潰し
     * 「作業台が作れない」として実サーバで表面化したのと同じ原理)。
     *
     * <p>盤面に<b>カタログ品が1つも無い</b>場合(=純粋に材質だけで誤選択された場合)に限り、
     * TF 登録レシピを除外して盤面を再照合し、本来選ばれるはずだったレシピの結果を戻す。
     * カタログ品が混ざる盤面は従来どおりクラフト不可のまま — 圧縮ブロックの tier 誤爆
     * (over-match)を再び開けないため、この非対称は意図的。
     *
     * @return 復元すべき結果、または該当なしの {@code null}
     */
    ItemStack shadowedVanillaResult(ItemStack[] matrix, int gridWidth) {
        if (gridHasCatalogItem(matrix)) {
            return null;
        }
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe = it.next();
            if (registeredOf(recipe).isPresent()) {
                continue; // TF カタログレシピ自身(=今まさに弾いたもの)は候補から外す
            }
            if (matchesBukkitRecipe(recipe, matrix, gridWidth)) {
                return recipe.getResult();
            }
        }
        return null;
    }

    /** Bukkit 登録レシピ(shaped/shapeless のみ)を盤面と照合する。それ以外の型は常に false。 */
    private static boolean matchesBukkitRecipe(Recipe recipe, ItemStack[] matrix, int gridWidth) {
        if (recipe instanceof ShapedRecipe shaped) {
            return matchesBukkitShaped(shaped, matrix, gridWidth);
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            return matchesBukkitShapeless(shapeless, matrix);
        }
        return false;
    }

    private static boolean matchesBukkitShaped(ShapedRecipe recipe, ItemStack[] matrix, int gridWidth) {
        List<String> box = boundingBox(List.of(recipe.getShape()));
        if (box.isEmpty()) {
            return false;
        }
        Map<Character, RecipeChoice> choices = recipe.getChoiceMap();
        int rows = box.size();
        int cols = box.get(0).length();
        int gridHeight = matrix.length / gridWidth;
        if (rows > gridHeight || cols > gridWidth) {
            return false;
        }
        // バニラの shaped は左右反転配置も受理する。
        for (boolean mirrored : new boolean[] {false, true}) {
            List<String> shape = mirrored ? mirror(box) : box;
            for (int dy = 0; dy + rows <= gridHeight; dy++) {
                for (int dx = 0; dx + cols <= gridWidth; dx++) {
                    if (matchesBukkitShapedAt(matrix, gridWidth, gridHeight, shape, dy, dx, choices)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean matchesBukkitShapedAt(ItemStack[] matrix, int gridWidth, int gridHeight,
                                                 List<String> shape, int dy, int dx,
                                                 Map<Character, RecipeChoice> choices) {
        int rows = shape.size();
        int cols = shape.get(0).length();
        for (int y = 0; y < gridHeight; y++) {
            for (int x = 0; x < gridWidth; x++) {
                ItemStack item = matrix[y * gridWidth + x];
                boolean inside = y >= dy && y < dy + rows && x >= dx && x < dx + cols;
                char symbol = inside ? shape.get(y - dy).charAt(x - dx) : ' ';
                RecipeChoice choice = symbol == ' ' ? null : choices.get(symbol);
                if (symbol != ' ' && choice == null) {
                    return false; // 記号に対応する素材が無い(壊れたレシピ) — 触らない
                }
                if (choice == null) {
                    if (item != null && !item.getType().isAir()) {
                        return false; // 空セルに物がある
                    }
                    continue;
                }
                if (item == null || item.getType().isAir() || !choice.test(item)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean matchesBukkitShapeless(ShapelessRecipe recipe, ItemStack[] matrix) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : matrix) {
            if (item != null && !item.getType().isAir()) {
                items.add(item);
            }
        }
        List<RecipeChoice> choices = recipe.getChoiceList();
        if (items.size() != choices.size()) {
            return false;
        }
        int n = items.size();
        int[] assignedItem = new int[n];
        java.util.Arrays.fill(assignedItem, -1);
        for (int i = 0; i < n; i++) {
            if (!assignChoice(items, choices, i, new boolean[n], assignedItem)) {
                return false;
            }
        }
        return true;
    }

    /** {@link #assignItem} と同じ二部マッチングを {@link RecipeChoice} に対して行う版。 */
    private static boolean assignChoice(List<ItemStack> items, List<RecipeChoice> choices, int itemIdx,
                                        boolean[] visited, int[] assignedItem) {
        for (int j = 0; j < choices.size(); j++) {
            if (visited[j] || !choices.get(j).test(items.get(itemIdx))) {
                continue;
            }
            visited[j] = true;
            if (assignedItem[j] < 0
                    || assignChoice(items, choices, assignedItem[j], visited, assignedItem)) {
                assignedItem[j] = itemIdx;
                return true;
            }
        }
        return false;
    }

    /** Re-matches the grid across every registered catalog workbench recipe. */
    private boolean rematch(PrepareItemCraftEvent event, ItemStack[] matrix) {
        int gridWidth = matrix.length == 4 ? 2 : 3;
        for (CatalogRecipeRegistrar.RegisteredRecipe candidate : registrar.allRegistered()) {
            if (matches(matrix, gridWidth, candidate.spec())) {
                event.getInventory().setResult(
                        registrarResult(candidate.template(), candidate.spec()));
                return true;
            }
        }
        return false;
    }

    private ItemStack registrarResult(ItemTemplate template, RecipeSpec spec) {
        return registrar.buildResult(template, spec);
    }

    private Optional<CatalogRecipeRegistrar.RegisteredRecipe> registeredOf(Recipe recipe) {
        if (!(recipe instanceof Keyed keyed)) {
            return Optional.empty();
        }
        return registrar.registered(keyed.getKey());
    }

    // ------------------------------------------------------------------
    // Grid matching
    // ------------------------------------------------------------------

    boolean matches(ItemStack[] matrix, int gridWidth, RecipeSpec spec) {
        // workbench(作業台専用, 3×3)は2×2インベントリグリッドでは絶対に成立しない。
        // inventory method は2×2/3×3どちらでも成立する (D2 契約)。
        if (spec.isWorkbench() && matrix.length == 4) {
            return false;
        }
        if (spec.type() == RecipeSpec.Type.SHAPED) {
            return matchesShaped(matrix, gridWidth, spec);
        }
        return matchesShapeless(matrix, spec);
    }

    /**
     * バニラのshaped照合は左右反転配置も常に受理するため、{@code strict-orientation: true} の
     * 非対称shapedレシピは「バニラは選ぶがカタログ仕様では拒否すべき」向きがありうる。
     * その場合のみこのリスナーによる向き再検証 ({@link #matches}) を必須にする。
     */
    boolean needsOrientationCheck(RecipeSpec spec) {
        if (spec.type() != RecipeSpec.Type.SHAPED || !spec.strictOrientation()) {
            return false;
        }
        List<String> box = boundingBox(spec.shape());
        return !box.isEmpty() && !box.equals(mirror(box));
    }

    private boolean matchesShaped(ItemStack[] matrix, int gridWidth, RecipeSpec spec) {
        List<String> box = boundingBox(spec.shape());
        if (box.isEmpty()) {
            return false;
        }
        int rows = box.size();
        int cols = box.get(0).length();
        int gridHeight = matrix.length / gridWidth;
        if (rows > gridHeight || cols > gridWidth) {
            return false;
        }
        // strict-orientation のレシピは正配置のみ受理 (バニラの自動ミラー受理をここで打ち消す)。
        // デフォルトはバニラ同様に両向き受理。
        boolean[] orientations = spec.strictOrientation()
                ? new boolean[] {false}
                : new boolean[] {false, true};
        for (boolean mirrored : orientations) {
            List<String> shape = mirrored ? mirror(box) : box;
            for (int dy = 0; dy + rows <= gridHeight; dy++) {
                for (int dx = 0; dx + cols <= gridWidth; dx++) {
                    if (matchesAt(matrix, gridWidth, gridHeight, shape, dy, dx, spec)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean matchesAt(ItemStack[] matrix, int gridWidth, int gridHeight,
                              List<String> shape, int dy, int dx, RecipeSpec spec) {
        int rows = shape.size();
        int cols = shape.get(0).length();
        for (int y = 0; y < gridHeight; y++) {
            for (int x = 0; x < gridWidth; x++) {
                ItemStack item = matrix[y * gridWidth + x];
                boolean inside = y >= dy && y < dy + rows && x >= dx && x < dx + cols;
                char symbol = inside ? shape.get(y - dy).charAt(x - dx) : ' ';
                if (symbol == ' ') {
                    if (item != null && !item.getType().isAir()) {
                        return false;
                    }
                    continue;
                }
                RecipeIngredient required = spec.shapedIngredients().get(symbol);
                if (required == null || !matchesIngredient(item, required)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean matchesShapeless(ItemStack[] matrix, RecipeSpec spec) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : matrix) {
            if (item != null && !item.getType().isAir()) {
                items.add(item);
            }
        }
        List<RecipeIngredient> ingredients = spec.shapelessIngredients();
        if (items.size() != ingredients.size()) {
            return false;
        }
        // 二部マッチング (Kuhnの拡張パス法)。貪欲割当だと list:planks と OAK_PLANKS のように
        // 受理集合が重なる ingredient で、先取りされた側が合致できず偽陰性になるため、
        // 完全マッチングの存在で判定する (n<=9 なので計算量は問題にならない)。
        int n = items.size();
        int[] assignedItem = new int[n]; // ingredient index -> item index (-1 = 未割当)
        java.util.Arrays.fill(assignedItem, -1);
        for (int i = 0; i < n; i++) {
            if (!assignItem(items, ingredients, i, new boolean[n], assignedItem)) {
                return false;
            }
        }
        return true;
    }

    /** item i を空きまたは付け替え可能な ingredient へ割り当てる (拡張パス探索)。 */
    private boolean assignItem(List<ItemStack> items, List<RecipeIngredient> ingredients,
                               int itemIdx, boolean[] visited, int[] assignedItem) {
        for (int j = 0; j < ingredients.size(); j++) {
            if (visited[j] || !matchesIngredient(items.get(itemIdx), ingredients.get(j))) {
                continue;
            }
            visited[j] = true;
            if (assignedItem[j] < 0
                    || assignItem(items, ingredients, assignedItem[j], visited, assignedItem)) {
                assignedItem[j] = itemIdx;
                return true;
            }
        }
        return false;
    }

    /**
     * One slot vs one ingredient. Catalog references match by catalog identity (stamped PDC id
     * first, material+CMD fallback so freshly Ars-built items match too); plain materials
     * additionally require the item NOT to be a catalog item (a compressed stone must never satisfy
     * a plain STONE cell).
     */
    private boolean matchesIngredient(ItemStack item, RecipeIngredient required) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        Optional<String> identity = catalogIdentityOf(item);
        if (required.isCustom()) {
            if (identity.map(id -> id.equals(required.catalogId())).orElse(false)) {
                return true;
            }
            return ExternalItemRegistry.matches(required.catalogId(), item, DerivedItemStats.customModelDataOf(item.getItemMeta()));
        }
        if (required.isList()) {
            if (identity.filter(id -> MaterialLists.resolveCustomIds(required.listId()).contains(id)).isPresent()) {
                return true;
            }
            Integer cmd = DerivedItemStats.customModelDataOf(item.getItemMeta());
            for (String externalId : MaterialLists.resolveCustomIds(required.listId())) {
                if (ExternalItemRegistry.matches(externalId, item, cmd)) return true;
            }
        }
        return required.acceptsMaterial(item.getType()) && identity.isEmpty();
    }

    /** The catalog id this stack is identified as, if any (PDC first, material+CMD fallback). */
    private Optional<String> catalogIdentityOf(ItemStack item) {
        if (!item.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        Optional<String> stamped = ItemData.of(meta).catalogId();
        if (stamped.isPresent()) {
            return stamped;
        }
        Integer cmd = DerivedItemStats.customModelDataOf(meta);
        if (cmd == null) {
            return Optional.empty();
        }
        Optional<String> catalogId = CatalogIdentity.find(catalog, item.getType(), cmd).map(ItemTemplate::id);
        if (catalogId.isPresent()) return catalogId;
        return ExternalItemRegistry.find(item.getType(), cmd).map(ExternalItemRegistry.Definition::id);
    }

    private boolean gridHasCatalogItem(ItemStack[] matrix) {
        for (ItemStack item : matrix) {
            if (item != null && !item.getType().isAir() && catalogIdentityOf(item).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Trims a recipe shape to its non-space bounding box (padded to equal-width rows). */
    private static List<String> boundingBox(List<String> shape) {
        int top = Integer.MAX_VALUE;
        int bottom = -1;
        int left = Integer.MAX_VALUE;
        int right = -1;
        for (int y = 0; y < shape.size(); y++) {
            String row = shape.get(y);
            for (int x = 0; x < row.length(); x++) {
                if (row.charAt(x) != ' ') {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
        }
        if (bottom < 0) {
            return List.of();
        }
        List<String> box = new ArrayList<>();
        for (int y = top; y <= bottom; y++) {
            String row = shape.get(y);
            StringBuilder sb = new StringBuilder();
            for (int x = left; x <= right; x++) {
                sb.append(x < row.length() ? row.charAt(x) : ' ');
            }
            box.add(sb.toString());
        }
        return box;
    }

    private static List<String> mirror(List<String> shape) {
        List<String> mirrored = new ArrayList<>(shape.size());
        for (String row : shape) {
            mirrored.add(new StringBuilder(row).reverse().toString());
        }
        return mirrored;
    }
}
