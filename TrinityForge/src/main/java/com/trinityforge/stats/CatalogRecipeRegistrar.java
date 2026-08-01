package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmithingTrimRecipe;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

/**
 * Registers a Bukkit crafting {@link Recipe} for every {@code items/catalog.yml} entry that declares
 * workbench recipes ({@code recipe:} and/or {@code recipes:} list — an item may have several, e.g. a
 * compression 3x3 plus a decompression shapeless).
 *
 * <p><strong>Result item:</strong> when ArsPaper is enabled and registers a custom item under the
 * same id (spellbooks, source materials, berries, wand …), the registered result is the Ars-built
 * stack so it carries the functional {@code arspaper:*} PDC (book tier, custom item id). Otherwise
 * the result is {@link ItemFactory#createIdentityOnly} — identity only, no baked rollSeed, so
 * {@code CraftQualityListener} stamps a per-crafter quality on actual craft. Because Ars enables
 * after TrinityForge, the fork calls {@code TrinityForge#refreshCatalogRecipes()} from its own
 * enable hook so the results are rebuilt Ars-side (this class stays idempotent).
 *
 * <p><strong>{@code custom:} ingredients:</strong> registered as {@link RecipeChoice.MaterialChoice}
 * of the referenced entry's base material so vanilla matching still selects the recipe; the precise
 * per-slot identity check (catalog PDC / material+CMD, and "plain material slots must NOT hold a
 * catalog item") is enforced — and cross-recipe mismatches corrected — by
 * {@code CatalogWorkbenchListener} on {@code PrepareItemCraftEvent}.
 *
 * <p>Every registered recipe is keyed {@code trinityforge:catalog_<id>} (first workbench recipe) or
 * {@code trinityforge:catalog_<id>_<n>} (subsequent ones). {@link #registerAll()} is idempotent and
 * reload-safe. Fail-soft per entry, as before.
 *
 * <p><strong>{@code method: netherite}:</strong> スミス台の {@link SmithingTransformRecipe} として
 * {@code trinityforge:catalog_<id>_smithing} で登録する({@link #registerNetheriteOne})。
 * 登録しないと base スロットにアイテムを置けず {@code CatalogSmithingListener} まで到達しない —
 * 詳細と「なぜ全件は登録しないのか」は {@link #registerNetheriteOne} の javadoc を参照。
 */
public final class CatalogRecipeRegistrar {

    private static final String NAMESPACE = "trinityforge";
    private static final String KEY_PREFIX = "catalog_";
    /**
     * {@code method: netherite} のスミス台レシピキー接尾辞。バニラのネザライト強化が
     * {@code minecraft:netherite_sword_smithing} という命名なのに合わせてある
     * ({@code CatalogCraftGateListener} の javadoc もこの接尾辞を前提に書かれている)。
     */
    private static final String SMITHING_SUFFIX = "_smithing";

    private final Plugin plugin;
    private final ItemCatalogConfig catalog;
    private final ItemFactory itemFactory;
    private final java.util.function.Supplier<List<com.trinityforge.config.domains.CraftingFeaturesConfig.AddedRecipe>>
            addedRecipesSupplier;
    private final Set<NamespacedKey> registeredKeys = new HashSet<>();
    /** Live view of what is currently registered, for {@code CatalogWorkbenchListener}. */
    private final Map<NamespacedKey, RegisteredRecipe> registeredSpecs = new LinkedHashMap<>();

    /** One registered catalog workbench recipe: owning catalog entry + the parsed spec. */
    public record RegisteredRecipe(NamespacedKey key, ItemTemplate template, RecipeSpec spec) {
    }

    /** Back-compat overload (existing tests / call sites): no {@code added-recipes} supplier. */
    public CatalogRecipeRegistrar(Plugin plugin, ItemCatalogConfig catalog, ItemFactory itemFactory) {
        this(plugin, catalog, itemFactory, null);
    }

    /**
     * @param addedRecipesSupplier supplies {@code progression/crafting-features.yml}'s
     *                              {@code added-recipes} (plain-Material-result Bukkit recipes),
     *                              registered by {@link #registerAll()} after all catalog recipes.
     *                              {@code null} is treated as "no added recipes".
     */
    public CatalogRecipeRegistrar(Plugin plugin, ItemCatalogConfig catalog, ItemFactory itemFactory,
            java.util.function.Supplier<List<com.trinityforge.config.domains.CraftingFeaturesConfig.AddedRecipe>>
                    addedRecipesSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
        this.addedRecipesSupplier = addedRecipesSupplier;
    }

    /**
     * Removes every recipe this registrar previously added, then registers each catalog entry's
     * workbench recipes. Safe to call repeatedly (initial enable + every {@code /trinityforge
     * reload} + the ArsPaper enable callback).
     */
    public void registerAll() {
        removeAll();
        NetheriteUpgradeGuard netheriteGuard = NetheriteUpgradeGuard.snapshot(plugin);
        catalog.all().forEach((id, template) -> {
            int workbenchIndex = 0;
            int smithingIndex = 0;
            for (RecipeSpec spec : template.recipes()) {
                if (!spec.shouldRegister()) {
                    continue;
                }
                if (spec.isNetherite()) {
                    smithingIndex++;
                    String smithingKey = smithingIndex == 1
                            ? KEY_PREFIX + id + SMITHING_SUFFIX
                            : KEY_PREFIX + id + SMITHING_SUFFIX + "_" + smithingIndex;
                    registerNetheriteOne(new NamespacedKey(NAMESPACE, smithingKey), template, spec, netheriteGuard);
                    continue;
                }
                if (!spec.isBukkitCrafting()) {
                    continue;
                }
                workbenchIndex++;
                String keyName = workbenchIndex == 1
                        ? KEY_PREFIX + id
                        : KEY_PREFIX + id + "_" + workbenchIndex;
                registerOne(new NamespacedKey(NAMESPACE, keyName), template, spec);
                if (spec.reversible()) {
                    registerReverseOne(new NamespacedKey(NAMESPACE, keyName + "_decompress"), template, spec);
                }
            }
        });
        registerAddedRecipes();
    }

    /**
     * {@code method: netherite} を Bukkit の {@link SmithingTransformRecipe} として登録する。
     *
     * <p><b>なぜ登録が要るのか(2026-08-01 U7)</b>: 1.21.2 以降、スミス台の各入力スロットが
     * 受け付けるアイテムは {@code RecipePropertySet}(SMITHING_TEMPLATE / SMITHING_BASE /
     * SMITHING_ADDITION)で決まり、これは「読み込み済みスミスレシピ全部の ingredient」から
     * {@code RecipeManager#finalizeRecipeLoading} が毎回組み直す(固定タグではない)。
     * CraftBukkit の {@code Bukkit.addRecipe} は内部で {@code finalizeRecipeLoading()} を呼ぶので、
     * ここで base に BOW / CROSSBOW / TRIDENT / MACE / BLAZE_ROD を持つレシピを足すと、
     * その材質が base スロットに<b>置けるようになる</b>。逆に登録しない限り、
     * バニラのネザライト強化 9 種(ダイヤ装備/道具)以外の材質は物理的に置けず、
     * {@code CatalogSmithingListener} の {@code PrepareSmithingEvent} まで到達しない。
     *
     * <p><b>なぜ全件は登録しないのか</b>: CraftBukkit の {@code RecipeManager#getRecipeFor} は
     * 一致した中の<b>最後</b>を採用する(SPIGOT-4638「last recipe gets priority」)。プラグインが
     * 足したレシピは常にバニラより後ろに積まれるので、base=DIAMOND_SWORD のレシピを足すと
     * 「ただのダイヤの剣＋ネザライトインゴット」がバニラのネザライトの剣ではなく TF の
     * カタログアイテムに化ける。ダイヤ装備系 base は<b>すでにバニラのレシピが一致するおかげで</b>
     * {@code PrepareSmithingEvent} が飛び listener が結果を差し替えられている(＝今も動いている)ため、
     * 「同じ 3 点(ネザライトテンプレ/この base/ネザライトインゴット)に一致する他所のレシピが既にある
     * 材質には登録しない」= {@link NetheriteUpgradeGuard} で衝突を避ける。
     *
     * <p>結果アイテムはあくまで「置ける・イベントが飛ぶ」ための土台で、実際にプレイヤーへ渡る
     * スタックは {@code CatalogSmithingListener} が品質込みで作り直す。base の
     * data component を引き継ぐと素材側の PDC が混ざるだけなので {@code copyDataComponents=false}。
     */
    private void registerNetheriteOne(NamespacedKey key, ItemTemplate template, RecipeSpec spec,
            NetheriteUpgradeGuard guard) {
        try {
            String sourceId = spec.sourceItem();
            ItemTemplate source = catalog.template(sourceId).orElse(null);
            if (source == null) {
                // listener 側 (CatalogSmithingListener#match) がカタログ定義の source しか照合できない。
                // カタログ外 id を登録すると「置けるのに永久に完成しない」レシピになるので登録しない。
                plugin.getLogger().log(Level.WARNING,
                        "[items/catalog.yml] netherite recipe for '" + template.id() + "' points at source-item '"
                        + sourceId + "' which is not a catalog entry; smithing recipe skipped");
                return;
            }
            Material base = source.material();
            if (!guard.mayRegister(base)) {
                plugin.getLogger().log(Level.FINE,
                        "[items/catalog.yml] netherite recipe for '" + template.id() + "' keeps using the existing "
                        + base + " smithing recipe (registering ours would shadow it)");
                return;
            }
            ItemStack result = buildResult(template, spec);
            SmithingTransformRecipe recipe = new SmithingTransformRecipe(key, result,
                    new RecipeChoice.MaterialChoice(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                    new RecipeChoice.MaterialChoice(base),
                    new RecipeChoice.MaterialChoice(Material.NETHERITE_INGOT),
                    false);
            Bukkit.addRecipe(recipe);
            registeredKeys.add(key);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[items/catalog.yml] failed to register netherite smithing recipe for '"
                    + template.id() + "'; skipped", ex);
        }
    }

    /**
     * 登録済みスミス台レシピのうち「ネザライトテンプレ + ネザライトインゴット」の 3 点に一致しうる
     * ものを 1 度だけ走査したスナップショット。TF 名前空間のものは {@link #removeAll()} 済みなので
     * 出てこないが、名前空間でも弾いている。
     *
     * <p>走査自体に失敗した場合は「既存レシピを把握できていない」ので fail-closed
     * (=1 件も登録しない)。fail-open にするとバニラのネザライト強化を奪う可能性があり、
     * 「作れない(現状維持)」より「バニラが壊れる」方が高くつくため。
     */
    private static final class NetheriteUpgradeGuard {

        private final List<SmithingRecipe> conflicting;
        private final boolean scanned;

        private NetheriteUpgradeGuard(List<SmithingRecipe> conflicting, boolean scanned) {
            this.conflicting = conflicting;
            this.scanned = scanned;
        }

        static NetheriteUpgradeGuard snapshot(Plugin plugin) {
            ItemStack templateProbe = new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
            ItemStack additionProbe = new ItemStack(Material.NETHERITE_INGOT);
            List<SmithingRecipe> conflicting = new ArrayList<>();
            try {
                Iterator<Recipe> it = Bukkit.recipeIterator();
                while (it.hasNext()) {
                    Recipe recipe = it.next();
                    if (!(recipe instanceof SmithingRecipe smithing)) {
                        continue;
                    }
                    if (recipe instanceof Keyed keyed && NAMESPACE.equals(keyed.getKey().getNamespace())) {
                        continue;
                    }
                    RecipeChoice templateChoice = switch (smithing) {
                        case SmithingTransformRecipe transform -> transform.getTemplate();
                        case SmithingTrimRecipe trim -> trim.getTemplate();
                        default -> null;
                    };
                    // テンプレ/追加素材スロットが空を要求するレシピ(choice が null)は、
                    // ネザライトテンプレ+インゴットを置いた状態では成立しないので衝突しない。
                    if (templateChoice == null || !templateChoice.test(templateProbe)) {
                        continue;
                    }
                    RecipeChoice additionChoice = smithing.getAddition();
                    if (additionChoice == null || !additionChoice.test(additionProbe)) {
                        continue;
                    }
                    conflicting.add(smithing);
                }
                return new NetheriteUpgradeGuard(List.copyOf(conflicting), true);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING,
                        "既存スミス台レシピを走査できなかったため、items/catalog.yml の method: netherite は"
                        + "1件も登録しません(バニラのネザライト強化を奪う事故を避けるための fail-closed)", t);
                return new NetheriteUpgradeGuard(List.of(), false);
            }
        }

        boolean mayRegister(Material base) {
            if (!scanned) {
                return false;
            }
            ItemStack probe = new ItemStack(base);
            for (SmithingRecipe recipe : conflicting) {
                if (recipe.getBase().test(probe)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Registers {@code progression/crafting-features.yml}'s {@code added-recipes}: extra
     * workbench/inventory Bukkit recipes whose result is a plain vanilla {@link Material} (no
     * catalog identity). Runs after all catalog recipes so catalog ids always win a key collision
     * (there should not be one — added recipes use a distinct {@code added_<n>} key space).
     * Fail-soft per entry, same convention as {@link #registerOne}.
     */
    private void registerAddedRecipes() {
        List<com.trinityforge.config.domains.CraftingFeaturesConfig.AddedRecipe> added =
                addedRecipesSupplier == null ? List.of() : addedRecipesSupplier.get();
        if (added == null) {
            added = List.of();
        }
        int index = 0;
        for (com.trinityforge.config.domains.CraftingFeaturesConfig.AddedRecipe addedRecipe : added) {
            index++;
            NamespacedKey key = new NamespacedKey(NAMESPACE, "added_" + index);
            try {
                ItemStack result = new ItemStack(addedRecipe.result(), Math.max(1, addedRecipe.amount()));
                Recipe recipe = buildStandaloneRecipe(key, result, addedRecipe.spec());
                Bukkit.addRecipe(recipe);
                registeredKeys.add(key);
            } catch (PendingArsIngredientException ex) {
                plugin.getLogger().log(Level.FINE,
                        "[progression/crafting-features.yml] deferring added-recipes entry #" + index
                        + " until ArsPaper enables (ingredient '" + ex.getMessage() + "')");
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "[progression/crafting-features.yml] failed to register added-recipes entry #"
                        + index + "; skipped", ex);
            }
        }
    }

    /**
     * Builds a standalone Bukkit {@link Recipe} for an {@code added-recipes} entry: same
     * shaped/shapeless construction as {@link #buildRecipe}, but with an explicit {@code result}
     * instead of a catalog template's build (added recipes have no catalog identity).
     */
    Recipe buildStandaloneRecipe(NamespacedKey key, ItemStack result, RecipeSpec spec) {
        if (spec.type() == RecipeSpec.Type.SHAPED) {
            ShapedRecipe recipe = new ShapedRecipe(key, result);
            recipe.shape(spec.shape().toArray(new String[0]));
            for (Map.Entry<Character, RecipeIngredient> entry : spec.shapedIngredients().entrySet()) {
                recipe.setIngredient(entry.getKey(), choiceFor(entry.getValue()));
            }
            return recipe;
        }
        ShapelessRecipe recipe = new ShapelessRecipe(key, result);
        for (RecipeIngredient ingredient : spec.shapelessIngredients()) {
            recipe.addIngredient(choiceFor(ingredient));
        }
        return recipe;
    }

    /** The registered catalog recipe behind {@code key}, if it is one of ours. */
    public Optional<RegisteredRecipe> registered(NamespacedKey key) {
        return Optional.ofNullable(registeredSpecs.get(key));
    }

    /** All currently registered catalog workbench recipes (registration order). */
    public Collection<RegisteredRecipe> allRegistered() {
        return List.copyOf(registeredSpecs.values());
    }

    /**
     * このレジストラが <b>実際に {@code Bukkit.addRecipe} した全キー</b>。
     *
     * <p>{@link #allRegistered()} との違いに注意: あちらは {@code registeredSpecs} 由来なので
     * {@link #registerOne} が入れた「正レシピ」だけで、
     * <b>{@code _decompress} 逆レシピと {@code added_<n>}(crafting-features.yml の
     * {@code added-recipes})を含まない</b>。「レシピ帳へ解禁する対象」のように
     * <b>登録した全キー</b>が欲しい用途はこちらを使う
     * ({@code RecipeDiscoveryListener} が唯一の利用者)。
     */
    public Set<NamespacedKey> allRegisteredKeys() {
        return Set.copyOf(registeredKeys);
    }

    private void removeAll() {
        for (NamespacedKey key : registeredKeys) {
            Bukkit.removeRecipe(key);
        }
        registeredKeys.clear();
        registeredSpecs.clear();
    }

    private void registerOne(NamespacedKey key, ItemTemplate template, RecipeSpec spec) {
        try {
            Recipe recipe = buildRecipe(key, template, spec);
            Bukkit.addRecipe(recipe);
            registeredKeys.add(key);
            registeredSpecs.put(key, new RegisteredRecipe(key, template, spec));
        } catch (PendingArsIngredientException ex) {
            // ArsPaper がまだ enable していないだけの一過性未解決。ArsPaper.onEnable の
            // refreshCatalogRecipes() で全レシピが再登録され解決するため、警告ではなく FINE で静かに残す
            // (「正しく設定したのに毎回スタックトレース警告が出る」誤アラームを防ぐ)。
            plugin.getLogger().log(Level.FINE,
                    "[items/catalog.yml] deferring recipe for '" + template.id()
                    + "' until ArsPaper enables (ingredient '" + ex.getMessage() + "')");
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[items/catalog.yml] failed to register recipe for '" + template.id() + "'; skipped", ex);
        }
    }

    /**
     * {@code reversible: true} な圧縮レシピの逆レシピ(このエントリ1個 → 元の素材N個)を登録する。
     * 単一ingredientのshapelessとして {@code trinityforge:catalog_<id>[_<n>]_decompress} キーで
     * 素のまま {@code Bukkit.addRecipe} するだけで、{@code registeredSpecs} には載せない —
     * {@code CatalogWorkbenchListener} の per-slot 補正/結果再計算の対象から意図的に外す設計判断。
     * 理由: 逆レシピの結果は「エントリ自身」ではなく素材そのものであり、既存の
     * {@code registrarResult}/{@code buildResult} 前提(結果は常にエントリ自身)を壊さずに逆レシピを
     * 扱うには per-slot 補正側の全面拡張が要る。
     *
     * <p>逆レシピの ingredient(=正レシピの結果アイテムそのもの)は {@link RecipeChoice.ExactChoice} で
     * 厳密照合する。{@code template.material()} 型のみの {@code MaterialChoice} だと、同一
     * material・別CMDのカタログアイテム(圧縮系tier違い等)やバニラ素材そのものが誤って一致し、
     * 逆レシピが本来の対象と異なる安価な素材で発火する複製exploitになるため使わない
     * (このエントリの完成品と完全に一致するスタックのみを受理する)。
     */
    private void registerReverseOne(NamespacedKey key, ItemTemplate template, RecipeSpec spec) {
        try {
            ItemStack result = buildReverseResult(template, spec);
            ItemStack forwardResult = buildResult(template, spec);
            forwardResult.setAmount(1);
            ShapelessRecipe recipe = new ShapelessRecipe(key, result);
            recipe.addIngredient(new RecipeChoice.ExactChoice(forwardResult));
            Bukkit.addRecipe(recipe);
            registeredKeys.add(key);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[items/catalog.yml] failed to register reverse recipe for '" + template.id() + "'; skipped",
                    ex);
        }
    }

    /**
     * 逆レシピの結果スタック: {@link RecipeSpec#reversibleIngredient()} が {@code custom:} 参照なら
     * その参照先のカタログ/Ars結果を個数分、プレーン素材ならバニラ {@link ItemStack} を個数分返す。
     */
    private ItemStack buildReverseResult(ItemTemplate template, RecipeSpec spec) {
        RecipeIngredient ingredient = spec.reversibleIngredient();
        if (ingredient == null) {
            throw new IllegalStateException(
                    "reversible recipe for '" + template.id() + "' has no uniform ingredient");
        }
        int count = spec.reversibleMaterialCount();
        if (ingredient.isCustom()) {
            ItemTemplate subTemplate = catalog.template(ingredient.catalogId()).orElseThrow(
                    () -> new IllegalArgumentException(
                            "reversible ingredient '" + ingredient.catalogId() + "' is not a known catalog item"));
            ItemStack stack = arsBuiltResult(subTemplate.id()).orElseGet(() -> itemFactory.createIdentityOnly(subTemplate));
            stack.setAmount(count);
            return stack;
        }
        return new ItemStack(ingredient.material(), count);
    }

    /**
     * Builds the Bukkit {@link Recipe} object for one {@link RecipeSpec} of {@code template}. Pure
     * aside from result building (no Bukkit server access — {@code ShapedRecipe} / {@code
     * ShapelessRecipe} construction is plain object building), so this step is unit-testable
     * without a live server.
     */
    Recipe buildRecipe(NamespacedKey key, ItemTemplate template, RecipeSpec spec) {
        ItemStack result = buildResult(template, spec);

        if (spec.type() == RecipeSpec.Type.SHAPED) {
            ShapedRecipe recipe = new ShapedRecipe(key, result);
            recipe.shape(spec.shape().toArray(new String[0]));
            for (Map.Entry<Character, RecipeIngredient> entry : spec.shapedIngredients().entrySet()) {
                recipe.setIngredient(entry.getKey(), choiceFor(entry.getValue()));
            }
            return recipe;
        }

        ShapelessRecipe recipe = new ShapelessRecipe(key, result);
        for (RecipeIngredient ingredient : spec.shapelessIngredients()) {
            recipe.addIngredient(choiceFor(ingredient));
        }
        return recipe;
    }

    /** Back-compat single-recipe overload (tests / legacy callers). */
    Recipe buildRecipe(NamespacedKey key, ItemTemplate template) {
        RecipeSpec spec = template.recipe();
        if (spec == null) {
            throw new IllegalArgumentException("template '" + template.id() + "' has no recipe");
        }
        return buildRecipe(key, template, spec);
    }

    /**
     * Builds the RESULT stack for a catalog recipe: the ArsPaper-registered custom item when one
     * shares this catalog id (so books/threads/materials keep their functional Ars PDC), else the
     * TF identity-only build. Public-ish (package) so {@code CatalogWorkbenchListener} builds
     * identical results when it corrects a cross-recipe mismatch.
     */
    public ItemStack buildResult(ItemTemplate template, RecipeSpec spec) {
        ItemStack result = arsBuiltResult(template.id())
                .orElseGet(() -> itemFactory.createIdentityOnly(template));
        result.setAmount(spec.amount());
        return result;
    }

    private Optional<ItemStack> arsBuiltResult(String catalogId) {
        try {
            Plugin ars = Bukkit.getPluginManager().getPlugin("ArsPaper");
            if (ars == null || !ars.isEnabled()) {
                return Optional.empty();
            }
            return ArsItemGiveBridge.create(catalogId);
        } catch (Throwable t) {
            // MockBukkit/unit tests or a mid-enable Ars: fall back to the TF identity build.
            return Optional.empty();
        }
    }

    /**
     * Bukkit-level choice for an ingredient. {@code custom:} references resolve to their base
     * material (per-slot identity is enforced by {@code CatalogWorkbenchListener}); an unknown
     * reference throws so the whole recipe is skipped with a warning instead of silently
     * over-matching.
     */
    private RecipeChoice choiceFor(RecipeIngredient ingredient) {
        if (!ingredient.isCustom()) {
            // list:<id> は互換リスト全体を Bukkit 候補にする (精密判定は Listener 側)。
            // 未定義リストは空集合 → throw して当該レシピだけ警告付きスキップ。
            if (ingredient.isList() && ingredient.acceptedMaterials().isEmpty()) {
                if (!MaterialLists.exists(ingredient.listId())) {
                    throw new IllegalArgumentException("material list '" + ingredient.listId()
                            + "' is not defined in items/material-lists.yml");
                }
            }
            java.util.LinkedHashSet<Material> choices = new java.util.LinkedHashSet<>(ingredient.acceptedMaterials());
            if (ingredient.isList()) for (String id : MaterialLists.resolveCustomIds(ingredient.listId())) {
                Material material = materialOfCustom(id);
                if (material == null) throw new IllegalArgumentException("custom list member '" + id + "' is unknown");
                choices.add(material);
            }
            if (choices.isEmpty()) throw new IllegalArgumentException("material list '" + ingredient.listId() + "' has no usable members");
            return new RecipeChoice.MaterialChoice(List.copyOf(choices));
        }
        Material base = materialOfCustom(ingredient.catalogId());
        if (base == null) {
            // ArsPaper 定義アイテム(materials.yml の圧縮素材等)は TF より後に enable するため、
            // TF.onEnable の初回登録では未解決になる。ArsPaper 導入済みでまだ未enableなら、直後の
            // ArsPaper.onEnable → refreshCatalogRecipes() 再登録で解決する「保留」とみなし、当該レシピ
            // だけ警告なしでスキップする(そのパスで登録される)。ArsPaper が enable 済みなのに未解決なら
            // 本当に未知の id なので従来どおり警告付きスキップ。
            if (arsPaperLoadingPending()) {
                throw new PendingArsIngredientException(ingredient.catalogId());
            }
            throw new IllegalArgumentException(
                    "custom ingredient '" + ingredient.catalogId() + "' is not a known catalog item");
        }
        return new RecipeChoice.MaterialChoice(base);
    }

    /**
     * ArsPaper が導入済みだが、まだ enable し切っていない(=TF が先に enable した初回登録タイミング)か。
     * この間に未解決な {@code custom:} 素材は「ArsPaper 定義でこれから解決される保留」とみなす。
     * ArsPaper.onEnable で {@code TrinityForge#refreshCatalogRecipes()} が呼ばれ registerAll() が
     * 再実行される時には ArsPaper の ItemRegistry が構築済みで解決できる。
     */
    private boolean arsPaperLoadingPending() {
        Plugin ars = Bukkit.getPluginManager().getPlugin("ArsPaper");
        return ars != null && !ars.isEnabled();
    }

    /** ArsPaper 未enableのために一時的に未解決なだけの {@code custom:} 素材を表す(=警告不要の保留)。 */
    private static final class PendingArsIngredientException extends RuntimeException {
        PendingArsIngredientException(String ingredientId) {
            super(ingredientId);
        }
    }

    /**
     * Base material of a {@code custom:} reference: catalog first, then {@link ExternalItemRegistry}
     * (the identity {@code CatalogWorkbenchListener} can actually verify per-slot at craft time),
     * then {@link ArsItemGiveBridge} as a last-resort resolver so the recipe still registers.
     *
     * <p>The bridge tier only proves a base material for {@code RecipeChoice.MaterialChoice}
     * registration — it builds a brand-new stack and cannot be used to verify an existing grid
     * slot's identity, which is exactly what {@code CatalogWorkbenchListener.matchesIngredient}
     * needs. So an id that resolves ONLY via the bridge (not via the catalog or the registry) would
     * register a recipe that Bukkit accepts by material but the listener can never confirm — a
     * silently dead/over-permissive recipe. Warn loudly here instead of letting that trap hide.
     */
    private Material materialOfCustom(String id) {
        Optional<ItemTemplate> ref = catalog.template(id);
        if (ref.isPresent()) {
            return ref.get().material();
        }
        Optional<ExternalItemRegistry.Definition> external = ExternalItemRegistry.find(id);
        if (external.isPresent()) {
            return external.get().material();
        }
        try {
            Material bridged = ArsItemGiveBridge.create(id).map(ItemStack::getType).orElse(null);
            if (bridged != null) {
                plugin.getLogger().log(Level.WARNING,
                        "[items/catalog.yml] custom ingredient '" + id + "' resolved only via the "
                        + "ArsItemGiveBridge fallback (no ExternalItemRegistry entry) — "
                        + "CatalogWorkbenchListener cannot verify this identity at craft time, so "
                        + "the recipe may never produce a result. Register '" + id
                        + "' into ExternalItemRegistry (e.g. via the owning plugin's TrinityForgeBridge "
                        + "push) to fix.");
            }
            return bridged;
        } catch (Throwable t) {
            return null;
        }
    }
}
