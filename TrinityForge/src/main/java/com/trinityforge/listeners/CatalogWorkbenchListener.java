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
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.CraftingInventory;
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
 * This listener re-validates the selected catalog recipe against the actual grid. A stack without
 * CustomModelData is always treated as vanilla; otherwise catalog identity uses PDC
 * {@code catalogId} first and material+CustomModelData as fallback. Plain-material cells must NOT
 * hold a catalog item. On mismatch it re-matches the grid across all registered catalog
 * workbench recipes and swaps in the correct result, or clears the preview when nothing fits.
 *
 * <p><strong>Foreign-plugin recipes are exempt</strong> — see
 * {@link #foreignRecipeOwnsGridItems}. That rule may only be applied to identities TF owns; a stack
 * that is only "known" through {@link ExternalItemRegistry} belongs to the plugin that registered
 * it, and that plugin's own per-slot guard is authoritative for its own recipes.
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
            if (!gridHasCatalogItem) {
                return;
            }
            // 選択レシピの所有プラグイン自身のカスタム品しか乗っていないなら、そのプラグインの
            // per-slot ガードへ委譲する (D5)。詳細は foreignRecipeOwnsGridItems の javadoc。
            if (foreignRecipeOwnsGridItems(selected, matrix)) {
                return;
            }
            // 装備(スタック不可)のカタログ品はバニラ/他プラグインのレシピに使わせる (2026-08-17)。
            // 革防具の染色は「同じ個体の続き」なので、色だけバニラ結果を採用して identity を残す。
            if (gridCatalogItemsAreAllGear(matrix)) {
                preserveLeatherDyeResult(event.getInventory(), matrix);
                return;
            }
            // A catalog stack may only be consumed by a recipe that explicitly opted into its
            // identity. Try our registered recipes first; otherwise clear a vanilla/plugin result
            // that matched only because the stack shares its base Material.
            if (!rematch(event, matrix)) {
                event.getInventory().setResult(null);
                if (shouldNotifyBlocked(selected)) {
                    notifyBlockedByCatalogItem(event, matrix);
                }
            }
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
     * 通知してよいのは「実際に見えていた結果を奪ったとき」だけ (2026-08-24、統合版の実サーバ報告)。
     *
     * <p>{@code PrepareItemCraftEvent} は<b>マスを1つ触るたびに飛ぶ</b>。カタログ品を作業台へ
     * 置いている途中の盤面はどのレシピにも一致しないので {@code selected} が null になり、
     * そこで名指しすると<b>まだ何も奪っていないのに「材料にできません」と言う</b>ことになる。
     *
     * <p>統合版ではこれが常に起きる ── Geyser はクラフト要求をマスへの1つずつの配置へ翻訳するので、
     * <b>成立するレシピであっても組み立ての途中で必ず不一致の盤面を通る</b>。実サーバでは
     * 「クラフトはできたのに材料にできませんの通知が出る」という形で報告された。
     *
     * <p>selected が非 null のときだけ通知すれば、W-87 が守りたかった場面
     * (バニラのレシピが成立していて、カタログ品のせいでそれを消した) はそのまま残る。
     */
    static boolean shouldNotifyBlocked(Recipe selectedBeforeClear) {
        return selectedBeforeClear != null;
    }

    /**
     * 「カタログ品が乗っているせいで結果が消えた」ことを本人へ伝える(2026-08-18)。
     *
     * <p>それまでは<b>結果枠が黙って空になるだけ</b>で、プレイヤーからは「バニラのレシピが壊れている」
     * ようにしか見えなかった。とくに<b>見た目がバニラそのままのカタログ品</b>
     * (リソースパックに item 定義が無い CMD。例: {@code thread_excavation} は
     * ネザライトアップグレードの鍛冶型と区別が付かない)を材料に入れた場合、
     * 「ネザライトの鍛冶型が複製できない」という報告になる。
     *
     * <p>アクションバーなのは兄弟の {@link CatalogCraftGateListener#onPrepareCraft} と揃えるため
     * (このイベントはマス目を触るたびに飛ぶので、チャットへ書くと即座に流れて読めなくなる)。
     */
    private void notifyBlockedByCatalogItem(PrepareItemCraftEvent event, ItemStack[] matrix) {
        // view が無い経路(自動作業台やテストの合成イベント)では宛先が無いので何もしない。
        // 通知は付け足しであって、ここで落として結果クリア自体を巻き添えにしてはいけない。
        if (event.getView() != null
                && event.getView().getPlayer() instanceof org.bukkit.entity.Player player) {
            player.sendActionBar(blockedMessage(matrix));
        }
    }

    /**
     * 「どのアイテムのせいで止まったか」を名指しする (2026-08-18、W-87 の実物確認を受けて)。
     *
     * <p><b>名前を出さないと解決しない</b>ことが実サーバで判明した ── 詰まる原因になるカスタム品は
     * <b>リソースパックに item 定義が無く、見た目がバニラと1ピクセルも変わらない</b>ものばかりで
     * (例: {@code netherite_block_1x}「9倍圧縮ネザライトブロック」、{@code thread_excavation} は
     * ネザライトアップグレードの鍛冶型)、材料欄では<b>ホバーしない限り区別が付かない</b>。
     * 「専用アイテムが入っています」だけだと、プレイヤーからは全部バニラに見えるので
     * 「バニラのレシピが壊れている」という結論にしかならない。
     */
    net.kyori.adventure.text.Component blockedMessage(ItemStack[] matrix) {
        Optional<net.kyori.adventure.text.Component> name = firstCatalogItemName(matrix);
        if (name.isEmpty()) {
            return CATALOG_INGREDIENT_MESSAGE;
        }
        return net.kyori.adventure.text.Component.text("", RED)
                .append(name.get())
                .append(net.kyori.adventure.text.Component.text(
                        " はこのレシピの材料にできません（見た目が同じでも別のアイテムです）", RED));
    }

    /** 盤面で最初に見つかったカタログ品の表示名。表示名が無ければカタログ id で代用する。 */
    private Optional<net.kyori.adventure.text.Component> firstCatalogItemName(ItemStack[] matrix) {
        for (ItemStack item : matrix) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            Optional<String> identity = catalogIdentityOf(item);
            if (identity.isEmpty()) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                net.kyori.adventure.text.Component displayName = meta.displayName();
                if (displayName != null) {
                    return Optional.of(displayName);
                }
            }
            return Optional.of(net.kyori.adventure.text.Component.text(identity.get(), RED));
        }
        return Optional.empty();
    }

    private static final net.kyori.adventure.text.format.NamedTextColor RED =
            net.kyori.adventure.text.format.NamedTextColor.RED;

    private static final net.kyori.adventure.text.Component CATALOG_INGREDIENT_MESSAGE =
            net.kyori.adventure.text.Component.text(
                    "このレシピは専用アイテムを材料にできません（材料欄のアイテム名を確認してください）", RED);

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
        ItemStack[] matrix;
        try {
            if (!(event.getBlock().getState() instanceof org.bukkit.block.Crafter crafter)) {
                return;
            }
            matrix = crafter.getInventory().getContents();
        } catch (RuntimeException ex) {
            return; // 盤面を取得できない場合は介入しない (バニラ挙動へフォールバック)
        }
        if (ours.isEmpty()) {
            if (!gridHasCatalogItem(matrix)) {
                return;
            }
            // 作業台と同じ委譲 (D5)。3箇所を揃えないと「手ではできるが Crafter では止まる」になる。
            if (foreignRecipeOwnsGridItems(event.getRecipe(), matrix)) {
                return;
            }
            if (gridCatalogItemsAreAllGear(matrix)) {
                ItemStack result = event.getResult();
                if (CatalogCosmeticPreserve.isLeatherDyeCraft(matrix, result)) {
                    ItemStack armor = CatalogCosmeticPreserve.leatherArmorIngredient(matrix, result.getType());
                    event.setResult(CatalogCosmeticPreserve.applyColorOnto(armor, result));
                }
                return; // 作業台側と同じ線引き (2026-08-17)
            }
            for (CatalogRecipeRegistrar.RegisteredRecipe candidate : registrar.allRegistered()) {
                if (matches(matrix, 3, candidate.spec())) {
                    event.setResult(registrar.resultOf(candidate));
                    return;
                }
            }
            event.setCancelled(true);
            return;
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
                event.setResult(registrar.resultOf(candidate));
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

    /** Defensive take-result gate in case another plugin restores a vanilla preview after prepare. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        Recipe selected = event.getRecipe();
        if (registeredOf(selected).isPresent()) {
            return;
        }
        ItemStack[] matrix = event.getInventory().getMatrix();
        if (!gridHasCatalogItem(matrix)) {
            return;
        }
        // onPrepareCraft と同じ委譲 (D5)。ここを直し忘れると「結果枠は出るが取り出せない」になる。
        if (foreignRecipeOwnsGridItems(selected, matrix)) {
            return;
        }
        // 2026-08-18: 装備カタログ品の素通し (2026-08-17) をこの取り出しゲートに入れ忘れていた。
        // onPrepareCraft だけ直したので結果枠にはディスペンサーが出るのに、クリックした瞬間
        // ここで setCancelled(true) され「見えるのに取れない」状態のままだった
        // ── 直上のコメントがまさに警告している失敗の仕方を、そのまま踏んでいた。
        if (gridCatalogItemsAreAllGear(matrix)) {
            preserveLeatherDyeResult(event.getInventory(), matrix);
            return;
        }
        int gridWidth = matrix.length == 4 ? 2 : 3;
        for (CatalogRecipeRegistrar.RegisteredRecipe candidate : registrar.allRegistered()) {
            if (matches(matrix, gridWidth, candidate.spec())) {
                return;
            }
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
                event.getInventory().setResult(registrar.resultOf(candidate));
                return true;
            }
        }
        return false;
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
     * One slot vs one ingredient. Items without CMD are vanilla regardless of residual PDC.
     * Otherwise catalog references match by catalog identity (stamped PDC id first, material+CMD
     * fallback so freshly Ars-built items match too); plain materials additionally require the item
     * NOT to be a catalog item (a compressed stone must never satisfy a plain STONE cell).
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

    /** The catalog id this stack is identified as, if any (CMD required; PDC first thereafter). */
    private Optional<String> catalogIdentityOf(ItemStack item) {
        if (!item.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        Integer cmd = DerivedItemStats.customModelDataOf(meta);
        if (cmd == null) {
            return Optional.empty();
        }
        Optional<String> stamped = ItemData.of(meta).catalogId();
        if (stamped.isPresent()) {
            return stamped;
        }
        Optional<String> catalogId = CatalogIdentity.find(catalog, item.getType(), cmd).map(ItemTemplate::id);
        if (catalogId.isPresent()) return catalogId;
        return ExternalItemRegistry.find(item.getType(), cmd).map(ExternalItemRegistry.Definition::id);
    }

    /** 革防具＋染料の染色は同じ個体の続き。色だけバニラ結果を写し、rollSeed / 品質 / bind は残す。 */
    private static void preserveLeatherDyeResult(CraftingInventory inventory, ItemStack[] matrix) {
        ItemStack result = inventory.getResult();
        if (!CatalogCosmeticPreserve.isLeatherDyeCraft(matrix, result)) {
            return;
        }
        ItemStack armor = CatalogCosmeticPreserve.leatherArmorIngredient(matrix, result.getType());
        inventory.setResult(CatalogCosmeticPreserve.applyColorOnto(armor, result));
    }

    /**
     * 盤面のカタログ品が<b>すべてスタック不可(＝装備・道具)</b>か。
     *
     * <p><b>2026-08-17 (ユーザー報告「ディスペンサーがクラフトできない」)</b>: 直上の
     * 「カタログ品を消費できるのはオプトインした TF レシピだけ」という規則は、
     * <b>圧縮素材が個数を偽ってバニラレシピに食われる</b>のを防ぐためのもので、その例も
     * {@code foreignRecipeOwnsGridItems} の javadoc のとおり「圧縮鉄ブロック → 鉄9個」だ。
     * ところが判定はカタログ品かどうかしか見ていなかったため、TF 製の弓のような
     * <b>装備カタログ品もバニラレシピから締め出されて</b>いた
     * (バニラのディスペンサーは弓を1本要求するので、TF の弓しか持っていないと永久に作れない)。
     *
     * <p>個数を偽れるのは重ねられる品だけなので、スタック上限1のカタログ品は素通しする。
     * カタログ品が1つでも重ねられるなら従来どおり TF が守る。
     */
    private boolean gridCatalogItemsAreAllGear(ItemStack[] matrix) {
        boolean sawCatalogItem = false;
        for (ItemStack item : matrix) {
            if (item == null || item.getType().isAir() || catalogIdentityOf(item).isEmpty()) {
                continue;
            }
            if (item.getMaxStackSize() > 1) {
                return false;
            }
            sawCatalogItem = true;
        }
        return sawCatalogItem;
    }

    private boolean gridHasCatalogItem(ItemStack[] matrix) {
        for (ItemStack item : matrix) {
            if (item != null && !item.getType().isAir() && catalogIdentityOf(item).isPresent()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 他プラグイン所有レシピへの委譲 (D5, 2026-07-31)
    // ------------------------------------------------------------------

    /**
     * TF が<b>所有する</b>カタログ identity か。{@link #catalogIdentityOf} の3段目
     * ({@link ExternalItemRegistry}) を意図的に見ない版。
     *
     * <p>{@code ExternalItemRegistry} は「TF が外部プラグインの品を<em>認識</em>するため」の
     * レジストリで、「TF がその品を<em>所有</em>する」宣言ではない。両者を混同すると下の
     * {@link #foreignRecipeOwnsGridItems} が成立しなくなる。
     */
    private boolean isTfOwnedCatalogItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        Integer cmd = DerivedItemStats.customModelDataOf(meta);
        if (cmd == null) {
            return false; // CMD の無いスタックは常にバニラ扱い (既存規約)
        }
        if (ItemData.of(meta).catalogId().isPresent()) {
            return true;
        }
        return CatalogIdentity.find(catalog, item.getType(), cmd).isPresent();
    }

    /**
     * <b>「ArsPaper 自身のレシピの結果枠が TF に消される」バグの修正 (D5, 2026-07-31)</b>。
     *
     * <p>{@link #catalogIdentityOf} は PDC → {@code catalog.yml} → {@link ExternalItemRegistry} の
     * 3段で解決するので、ArsPaper が自分の全カスタム品を登録した結果<b>他プラグインの品まで
     * 「カタログ品」に化ける</b>。すると「カタログ品を消費できるのはオプトインした TF レシピだけ」
     * という規則が ArsPaper 自身のレシピにも当たり、{@code ours.isEmpty()} → rematch 失敗 →
     * {@code setResult(null)} で結果枠が毎回空になっていた
     * (ソースジャー / 台座 / 写字台が作れず Ars 進行が丸ごと立ち上がらない状態)。
     *
     * <p>そこで<b>盤面のカスタム品がすべて選択レシピの所有プラグインのものである</b>場合に限り、
     * TF は介入せずそのプラグイン自身の per-slot ガード
     * ({@code ArsPaper CustomIngredientCraftGuardListener}) に委譲する。所有の判定は
     * 「レシピキーの namespace == {@link ExternalItemRegistry#pluginSourceOf} の layer 名」。
     *
     * <p><b>namespace 一致を要求するのが要点</b>で、「外部品が乗っていれば常に見送る」まで緩めると
     * {@code minecraft:*} のバニラレシピが ArsPaper の圧縮品を素材として食えるようになる
     * (圧縮鉄ブロックがバニラの分解レシピで鉄9個に溶ける)。TF 所有のカタログ品が1つでも
     * 混ざっていれば従来どおり TF が守る (圧縮ブロックの tier 誤爆防止)。
     *
     * @return 盤面に外部品が1つ以上あり、そのすべてが選択レシピの所有プラグインのものなら true
     */
    private boolean foreignRecipeOwnsGridItems(Recipe selected, ItemStack[] matrix) {
        if (!(selected instanceof Keyed keyed)) {
            return false;
        }
        String namespace = keyed.getKey().getNamespace();
        boolean sawForeign = false;
        for (ItemStack item : matrix) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (isTfOwnedCatalogItem(item)) {
                return false; // TF 所有品は従来どおり TF が守る
            }
            if (catalogIdentityOf(item).isEmpty()) {
                continue; // 素のバニラ素材 — 誰の所有物でもない
            }
            Integer cmd = DerivedItemStats.customModelDataOf(item.getItemMeta());
            boolean ownedBySelectedRecipesPlugin = ExternalItemRegistry
                    .pluginSourceOf(item.getType(), cmd)
                    .filter(source -> source.equalsIgnoreCase(namespace))
                    .isPresent();
            if (!ownedBySelectedRecipesPlugin) {
                return false; // 別プラグインの品 / バニラレシピが食おうとしている → 保護継続
            }
            sawForeign = true;
        }
        return sawForeign;
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
