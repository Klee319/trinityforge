package com.trinityforge.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.listeners.CatalogCraftGateListener;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import com.trinityforge.stats.ExternalItemRegistry;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.MaterialLists;
import com.trinityforge.stats.RecipeIngredient;
import com.trinityforge.stats.RecipeSpec;
import org.bukkit.Material;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code items/catalog.yml} の作業台レシピを {@link BedrockRecipeTable} へ落として
 * {@code plugins/TrinityForge/bedrock-recipes.json} に書き出す。
 *
 * <p>なぜこの表が要るのかは {@link BedrockRecipeTable} の javadoc を参照。
 * ここはその「供給側」で、<b>受け取り側 (GeyserExtra) のクラスには一切依存しない</b> ──
 * 既知のパスへ JSON を置くだけ。GeyserExtra が入っていなければ、
 * 誰も読まないファイルが 1 つ出来るだけで害は無い。
 *
 * <h2>書き出す対象</h2>
 * <ul>
 *   <li>Bukkit のクラフト台に乗るレシピ ({@link RecipeSpec#isBukkitCrafting()}) と、
 *       <b>スミス台のネザライト強化</b> ({@link RecipeSpec#isNetherite()})。
 *       儀式({@code method: ritual})と金床合成({@code method: combine})は対象外 ──
 *       どちらも統合版クライアントのレシピ表に載る形が無く、サーバ側イベントだけで完結している。</li>
 *   <li><b>素材にカスタム識別があるものだけ。</b> 素材が全部バニラなら Geyser の既定変換で
 *       正しく照合できる（結果だけカスタムでも結果側は正しく変換される）。</li>
 *   <li>{@code reversible: true} の<b>逆レシピ(解凍)も書く。</b>
 *       これは {@code CatalogRecipeRegistrar#allRegistered()} に載らない
 *       ({@code registerReverseOne} が Bukkit へ直接入れるだけ) ので、
 *       ここで spec から組み直さないと丸ごと落ちる ── そして逆レシピの素材は
 *       「正レシピの完成品」＝必ずカスタム品なので、<b>落とすと解凍が直らない</b>。</li>
 *   <li><b>スミス台は「サーバが完成させられるものだけ」</b>
 *       ({@link CatalogRecipeRegistrar#allCompletableSmithing()})。
 *       <b>「TF が登録したものだけ」ではない</b> ── バニラのネザライト強化レシピが
 *       代わりに一致するせいで TF が登録を見送ったケース(出荷カタログでは 12 件中 8 件)でも、
 *       {@code PrepareSmithingEvent} は飛んで {@code CatalogSmithingListener} が結果を差し替えるので
 *       プレイヤーは完成品を受け取れる。そして<b>統合版クライアントが持っているのは
 *       Geyser が変換したバニラのレシピ(base = 素のダイヤの剣)だけ</b>なので、
 *       補正レシピはむしろこちら側にこそ要る。ここで条件を組み直さず、
 *       registrar が持っている判定結果をそのまま使うこと。</li>
 * </ul>
 *
 * <h2>スミス台の base に「カタログ品の CMD」を書く理由</h2>
 * Bukkit 側の登録は {@code MaterialChoice(素材の material)} ＝<b>材質だけの緩い判定</b>で、
 * 実際に何が出来るかは {@code CatalogSmithingListener} が CMD 込みで決めている。
 * 統合版クライアントへ「材質だけ」を渡すと<b>素のバニラ弓でも完成すると表示される</b>ので、
 * ここでは {@code source-item} のカタログエントリを解決して material + CMD を書く。
 */
public final class BedrockRecipeExporter {

    /** 書き出し先のファイル名。受け取り側はこの名前で各プラグインのデータフォルダを探す。 */
    public static final String FILE_NAME = "bedrock-recipes.json";

    /** この表の出し手。受け取り側が複数プラグイン分をマージするときの出所表示に使う。 */
    public static final String SOURCE = "TrinityForge";

    private BedrockRecipeExporter() {
    }

    /**
     * {@code custom:<id>} を「実際の material + CustomModelData」へ落とす手続き。
     * 差し替え可能にしてあるのは、この表の正しさが<b>カタログの読み込みとは独立に</b>
     * 決まるようにするため (テストが catalog.yml を用意せずに済む)。
     */
    @FunctionalInterface
    public interface CustomItemResolver {
        Optional<BedrockRecipeTable.ItemRef> resolve(String catalogId);
    }

    /**
     * 出荷カタログ用の解決器。カタログ → {@link ExternalItemRegistry}(ArsPaper 等) の順に見る。
     *
     * <p><b>2 段目を落とすと圧縮素材が丸ごと消える</b> ── {@code oak_wood_1x} のような圧縮品は
     * Ars 側の実体で、TF のカタログには存在しない。
     */
    public static CustomItemResolver catalogResolver(ItemCatalogConfig catalog) {
        Objects.requireNonNull(catalog, "catalog");
        return id -> {
            Optional<ItemTemplate> template = catalog.template(id);
            if (template.isPresent()) {
                ItemTemplate found = template.get();
                return Optional.of(BedrockRecipeTable.ItemRef.of(found.material(), found.customModelData()));
            }
            return ExternalItemRegistry.find(id)
                    .map(definition -> BedrockRecipeTable.ItemRef.of(
                            definition.material(), definition.customModelData()));
        };
    }

    /** 出荷カタログでの書き出し。ゲートは掛かっていない前提(テスト用)。 */
    public static BedrockRecipeTable.Table build(
            Collection<CatalogRecipeRegistrar.RegisteredRecipe> registered,
            Collection<CatalogRecipeRegistrar.RegisteredRecipe> completableSmithing,
            ItemCatalogConfig catalog) {
        return build(registered, completableSmithing, catalogResolver(catalog), Set.of());
    }

    /** 出荷カタログ＋ゲート集合での書き出し。 */
    public static BedrockRecipeTable.Table build(
            Collection<CatalogRecipeRegistrar.RegisteredRecipe> registered,
            Collection<CatalogRecipeRegistrar.RegisteredRecipe> completableSmithing,
            ItemCatalogConfig catalog,
            Set<String> configuredRecipeGateIds) {
        return build(registered, completableSmithing, catalogResolver(catalog), configuredRecipeGateIds);
    }

    /**
     * レシピ表を組む。<b>ファイル I/O も Bukkit のサーバ実装も触らない</b>ので、
     * テストから直接叩ける ({@link Material} は enum なので参照してよい)。
     */
    public static BedrockRecipeTable.Table build(
            Collection<CatalogRecipeRegistrar.RegisteredRecipe> registered,
            Collection<CatalogRecipeRegistrar.RegisteredRecipe> completableSmithing,
            CustomItemResolver catalog) {
        return build(registered, completableSmithing, catalog, Set.of());
    }

    /**
     * レシピ表を組む。
     *
     * @param configuredRecipeGateIds いずれかのスキルツリーノードが {@code recipe:<id>} として
     *     配っているゲート id の集合。ここに載っている強化は<b>表から外す</b> ── 統合版は結果を
     *     クライアント側で計算するので、サーバが {@code setResult(null)} で塞いでも
     *     <b>完成品が見えたまま取れない</b>という、ユーザーには純粋なバグにしか見えない状態になる。
     *     外しておけば Geyser 自身の動的合成(サーバが結果を出したときだけ走る)に委ねられ、
     *     ゲートの有無がそのまま表示に反映される。
     */
    public static BedrockRecipeTable.Table build(
            Collection<CatalogRecipeRegistrar.RegisteredRecipe> registered,
            Collection<CatalogRecipeRegistrar.RegisteredRecipe> completableSmithing,
            CustomItemResolver catalog,
            Set<String> configuredRecipeGateIds) {
        List<BedrockRecipeTable.Recipe> recipes = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (CatalogRecipeRegistrar.RegisteredRecipe entry : completableSmithing) {
            String id = entry.key().toString();
            smithing(id, entry.template(), entry.spec(), catalog, configuredRecipeGateIds)
                    .ifPresentOrElse(
                    recipe -> {
                        if (recipe.needsBedrockFix()) {
                            recipes.add(recipe);
                        }
                    },
                    () -> skipped.add(id));
        }
        for (CatalogRecipeRegistrar.RegisteredRecipe entry : registered) {
            RecipeSpec spec = entry.spec();
            if (!spec.isBukkitCrafting() || !spec.shouldRegister()) {
                continue;
            }
            String id = entry.key().toString();
            forward(id, entry.template(), spec, catalog).ifPresentOrElse(
                    recipe -> {
                        if (recipe.needsBedrockFix()) {
                            recipes.add(recipe);
                        }
                    },
                    () -> skipped.add(id));
            if (spec.reversible()) {
                String reverseId = entry.key() + "_decompress";
                reverse(reverseId, entry.template(), spec, catalog).ifPresentOrElse(
                        recipe -> {
                            if (recipe.needsBedrockFix()) {
                                recipes.add(recipe);
                            }
                        },
                        () -> skipped.add(reverseId));
            }
        }
        return BedrockRecipeTable.Table.of(SOURCE, recipes, skipped);
    }

    /** 正レシピ。結果は必ずエントリ自身 ({@code CatalogRecipeRegistrar#buildResult} の前提と同じ)。 */
    private static Optional<BedrockRecipeTable.Recipe> forward(
            String id, ItemTemplate template, RecipeSpec spec, CustomItemResolver catalog) {
        BedrockRecipeTable.ItemRef result = new BedrockRecipeTable.ItemRef(
                template.material(), template.customModelData(), Math.max(1, spec.amount()));
        if (spec.type() == RecipeSpec.Type.SHAPED) {
            return shaped(id, spec, catalog, result);
        }
        return shapeless(id, spec, catalog, result);
    }

    /**
     * 逆レシピ(解凍)。素材はエントリ自身 1 個、結果は元素材 N 個。
     * 元素材が {@code custom:} 参照なら参照先のカタログ/Ars 品、プレーンならバニラ素材。
     */
    private static Optional<BedrockRecipeTable.Recipe> reverse(
            String id, ItemTemplate template, RecipeSpec spec, CustomItemResolver catalog) {
        RecipeIngredient ingredient = spec.reversibleIngredient();
        if (ingredient == null) {
            return Optional.empty();
        }
        int count = spec.reversibleMaterialCount();
        if (count < 1) {
            return Optional.empty();
        }
        List<BedrockRecipeTable.ItemRef> resolved = resolve(ingredient, catalog);
        if (resolved.size() != 1) {
            // list: 素材は「どれで解凍しても同じ物が返る」と決められないので逆レシピを作れない
            // (registrar 側も reversibleIngredient() が単一 ingredient を要求している)。
            return Optional.empty();
        }
        BedrockRecipeTable.ItemRef unit = resolved.get(0);
        BedrockRecipeTable.ItemRef result =
                new BedrockRecipeTable.ItemRef(unit.material(), unit.customModelData(), count);
        BedrockRecipeTable.Slot source = BedrockRecipeTable.Slot.of(
                BedrockRecipeTable.ItemRef.of(template.material(), template.customModelData()));
        return Optional.of(new BedrockRecipeTable.Recipe(
                id, BedrockRecipeTable.Type.SHAPELESS, 0, 0, List.of(source), result));
    }

    /**
     * スミス台のネザライト強化 1 件。
     *
     * <p>テンプレと追加素材は {@link CatalogRecipeRegistrar} の定数から採る ──
     * 登録側と別の値を書くと、クライアントだけが成立すると信じる盤面ができる。
     * base は {@code source-item} のカタログエントリ(＝{@code CatalogSmithingListener} が
     * CMD 込みで照合している相手)。
     *
     * @return source-item が解決できないときは {@code empty}(呼び出し側が skipped に積む)
     */
    private static Optional<BedrockRecipeTable.Recipe> smithing(
            String id, ItemTemplate template, RecipeSpec spec, CustomItemResolver catalog,
            Set<String> configuredRecipeGateIds) {
        if (template == null) {
            return Optional.empty();
        }
        String sourceId = spec.sourceItem();
        if (sourceId == null || sourceId.isBlank()) {
            return Optional.empty();
        }
        Optional<BedrockRecipeTable.ItemRef> base = resolveCustom(sourceId, catalog);
        if (base.isEmpty()) {
            return Optional.empty();
        }
        String gateId = CatalogCraftGateListener.netheriteGateIdFor(base.get().material());
        if (gateId != null && configuredRecipeGateIds.contains(gateId)) {
            // パーク未取得なら PrepareSmithingEvent で結果が消される強化。
            // 表へ載せるとクライアントが勝手に完成品を描いてしまうので載せない。
            return Optional.empty();
        }
        BedrockRecipeTable.ItemRef result = new BedrockRecipeTable.ItemRef(
                template.material(), template.customModelData(), Math.max(1, spec.amount()));
        List<BedrockRecipeTable.Slot> slots = List.of(
                BedrockRecipeTable.Slot.of(BedrockRecipeTable.ItemRef.of(
                        CatalogRecipeRegistrar.SMITHING_TEMPLATE_MATERIAL, null)),
                BedrockRecipeTable.Slot.of(base.get()),
                BedrockRecipeTable.Slot.of(BedrockRecipeTable.ItemRef.of(
                        CatalogRecipeRegistrar.SMITHING_ADDITION_MATERIAL, null)));
        return Optional.of(new BedrockRecipeTable.Recipe(
                id, BedrockRecipeTable.Type.SMITHING, 0, 0, slots, result));
    }

    private static Optional<BedrockRecipeTable.Recipe> shaped(
            String id, RecipeSpec spec, CustomItemResolver catalog, BedrockRecipeTable.ItemRef result) {
        List<String> shape = spec.shape();
        if (shape == null || shape.isEmpty()) {
            return Optional.empty();
        }
        int height = shape.size();
        int width = 0;
        for (String row : shape) {
            width = Math.max(width, row.length());
        }
        if (width < 1) {
            return Optional.empty();
        }
        Map<Character, RecipeIngredient> symbols = spec.shapedIngredients();
        List<BedrockRecipeTable.Slot> slots = new ArrayList<>(width * height);
        for (String row : shape) {
            for (int x = 0; x < width; x++) {
                char symbol = x < row.length() ? row.charAt(x) : ' ';
                if (symbol == ' ') {
                    slots.add(BedrockRecipeTable.Slot.empty());
                    continue;
                }
                RecipeIngredient ingredient = symbols.get(symbol);
                if (ingredient == null) {
                    return Optional.empty();
                }
                List<BedrockRecipeTable.ItemRef> refs = resolve(ingredient, catalog);
                if (refs.isEmpty()) {
                    return Optional.empty(); // 解決できない素材 — 中途半端な表を出すより落とす
                }
                slots.add(new BedrockRecipeTable.Slot(refs));
            }
        }
        return Optional.of(new BedrockRecipeTable.Recipe(
                id, BedrockRecipeTable.Type.SHAPED, width, height, slots, result));
    }

    private static Optional<BedrockRecipeTable.Recipe> shapeless(
            String id, RecipeSpec spec, CustomItemResolver catalog, BedrockRecipeTable.ItemRef result) {
        List<RecipeIngredient> ingredients = spec.shapelessIngredients();
        if (ingredients == null || ingredients.isEmpty()) {
            return Optional.empty();
        }
        List<BedrockRecipeTable.Slot> slots = new ArrayList<>(ingredients.size());
        for (RecipeIngredient ingredient : ingredients) {
            List<BedrockRecipeTable.ItemRef> refs = resolve(ingredient, catalog);
            if (refs.isEmpty()) {
                return Optional.empty();
            }
            slots.add(new BedrockRecipeTable.Slot(refs));
        }
        return Optional.of(new BedrockRecipeTable.Recipe(
                id, BedrockRecipeTable.Type.SHAPELESS, 0, 0, slots, result));
    }

    /**
     * 素材トークン 1 つ → 受理できるアイテムの列。
     *
     * <p>{@code custom:} はカタログ → {@link ExternalItemRegistry}(ArsPaper 等) の順で解決する。
     * 圧縮素材は Ars 側の実体なのでカタログには居ない ── ここで 2 段目を見ないと
     * 圧縮系のレシピが丸ごと落ちる。
     */
    private static List<BedrockRecipeTable.ItemRef> resolve(
            RecipeIngredient ingredient, CustomItemResolver catalog) {
        if (ingredient.isCustom()) {
            return resolveCustom(ingredient.catalogId(), catalog)
                    .map(List::of)
                    .orElseGet(List::of);
        }
        if (ingredient.isList()) {
            // Set なので順序が保証されない。名前で並べ替えて出力を安定させる
            // (揺れると差分が毎回出て、補正パケットを撃ち直す判断が付かなくなる)。
            List<BedrockRecipeTable.ItemRef> refs = new ArrayList<>();
            for (Material material : sortedByName(MaterialLists.resolve(ingredient.listId()))) {
                refs.add(BedrockRecipeTable.ItemRef.of(material, null));
            }
            for (String customId : new TreeSet<>(MaterialLists.resolveCustomIds(ingredient.listId()))) {
                resolveCustom(customId, catalog).ifPresent(refs::add);
            }
            return refs;
        }
        return List.of(BedrockRecipeTable.ItemRef.of(ingredient.material(), null));
    }

    /** {@link MaterialLists#resolve} は Set なので、出力を安定させるために名前で並べ替える。 */
    private static Collection<Material> sortedByName(Collection<Material> materials) {
        Map<String, Material> byName = new TreeMap<>();
        for (Material material : materials) {
            byName.put(material.name(), material);
        }
        return byName.values();
    }

    private static Optional<BedrockRecipeTable.ItemRef> resolveCustom(String id, CustomItemResolver catalog) {
        return catalog.resolve(id);
    }

    // ------------------------------------------------------------------
    // 書き出し
    // ------------------------------------------------------------------

    /** テストから中身を突き合わせられるように、JSON 化も純関数にしておく。 */
    public static JsonObject toJson(BedrockRecipeTable.Table table) {
        JsonObject root = new JsonObject();
        root.addProperty("version", table.version());
        root.addProperty("source", table.source());
        JsonArray recipes = new JsonArray();
        for (BedrockRecipeTable.Recipe recipe : table.recipes()) {
            recipes.add(toJson(recipe));
        }
        root.add("recipes", recipes);
        JsonArray skipped = new JsonArray();
        table.skipped().forEach(skipped::add);
        root.add("skipped", skipped);
        return root;
    }

    private static JsonObject toJson(BedrockRecipeTable.Recipe recipe) {
        JsonObject object = new JsonObject();
        object.addProperty("id", recipe.id());
        object.addProperty("type", switch (recipe.type()) {
            case SHAPED -> "shaped";
            case SHAPELESS -> "shapeless";
            case SMITHING -> "smithing";
        });
        if (recipe.type() == BedrockRecipeTable.Type.SHAPED) {
            object.addProperty("width", recipe.width());
            object.addProperty("height", recipe.height());
        }
        JsonArray slots = new JsonArray();
        for (BedrockRecipeTable.Slot slot : recipe.slots()) {
            if (slot.isEmpty()) {
                slots.add((String) null); // 空欄。shaped の行優先配置を崩さないため null を入れる
                continue;
            }
            JsonArray candidates = new JsonArray();
            for (BedrockRecipeTable.ItemRef ref : slot.items()) {
                candidates.add(toJson(ref));
            }
            slots.add(candidates);
        }
        object.add("slots", slots);
        object.add("result", toJson(recipe.result()));
        return object;
    }

    private static JsonObject toJson(BedrockRecipeTable.ItemRef ref) {
        JsonObject object = new JsonObject();
        object.addProperty("material", ref.material().name());
        if (ref.customModelData() != null) {
            object.addProperty("cmd", ref.customModelData());
        }
        if (ref.count() != 1) {
            object.addProperty("count", ref.count());
        }
        return object;
    }

    /**
     * {@code <dataFolder>/bedrock-recipes.json} へ書く。
     *
     * <p>同じフォルダの一時ファイルへ書いてから置き換える ── 受け取り側(別プロセス)が
     * 書きかけを読むと、壊れた JSON で読み込みごと落ちる。
     */
    public static void write(Path dataFolder, BedrockRecipeTable.Table table) throws IOException {
        Files.createDirectories(dataFolder);
        Path target = dataFolder.resolve(FILE_NAME);
        Path temp = dataFolder.resolve(FILE_NAME + ".tmp");
        // UTF-8 で BOM 無し。Java の writeString は BOM を付けない
        // (PowerShell のリダイレクトで作ると BOM が付いて読み手が壊れる、という既知の罠がある)。
        Files.writeString(temp, toJson(table).toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
