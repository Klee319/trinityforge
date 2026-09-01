package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.DisassemblyRule;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.MaterialLists;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.RecipeIngredient;
import com.trinityforge.stats.RecipeSpec;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Disassembles configured catalog equipment when an anvil placed by its owner lands on it.
 * The target rule selects the catalog id (or, for unstamped items, material) with an exact/trailing-* pattern.
 */
public final class DisassemblyListener implements Listener {

    private static final String UNLOCK = "dismantle-unlock";
    private static final long ANVIL_OWNER_TTL_MILLIS = 30L * 60L * 1000L;
    private static final int MAX_RETURN_STACKS_PER_RULE = 64;
    /** T3(2026-07-25経済連携): 解体の戻り量に乗る追加倍率(プレイヤー単位)。0以下/未保有は影響なし。 */
    private static final String DISASSEMBLY_RETURN_BONUS_KEY = StatKeys.canonical("disassembly_return_bonus");
    private final DedicatedEffectsConfig dedicatedEffects;
    private final CraftingFeaturesConfig features;
    private final ItemCatalogConfig catalog;
    private final ItemFactory itemFactory;
    private final CrossPluginItemResolver itemResolver;
    private final PlayerStatAggregator aggregator;
    private final Map<BlockKey, AnvilPlacement> placedAnvils = new HashMap<>();
    private final Map<UUID, AnvilPlacement> fallingAnvilOwners = new HashMap<>();

    public DisassemblyListener(DedicatedEffectsConfig dedicatedEffects, CraftingFeaturesConfig features,
                               ItemCatalogConfig catalog, ItemFactory itemFactory, PlayerStatAggregator aggregator) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.features = Objects.requireNonNull(features, "features");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.itemResolver = new CrossPluginItemResolver(catalog, itemFactory);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAnvilPlace(BlockPlaceEvent event) {
        if (isAnvil(event.getBlockPlaced().getType())) {
            long now = System.currentTimeMillis();
            discardExpiredTracking(now);
            placedAnvils.put(BlockKey.of(event.getBlockPlaced().getLocation()),
                    new AnvilPlacement(event.getPlayer().getUniqueId(), now));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFallingAnvilSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof FallingBlock falling) || !isAnvil(falling.getBlockData().getMaterial())) return;
        long now = System.currentTimeMillis();
        discardExpiredTracking(now);
        AnvilPlacement placement = placedAnvils.remove(BlockKey.of(falling.getLocation()));
        if (placement != null) fallingAnvilOwners.put(falling.getUniqueId(), placement);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvilLand(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock falling) || !isAnvil(falling.getBlockData().getMaterial())) return;
        discardExpiredTracking(System.currentTimeMillis());
        AnvilPlacement placement = fallingAnvilOwners.remove(falling.getUniqueId());
        if (placement == null) return; // dispenser/world-generated anvils have no accountable unlock owner.
        Player owner = falling.getServer().getPlayer(placement.owner());
        int level = owner == null ? 0 : dismantleLevel(dedicatedEffects, owner);
        if (level <= 0) return;

        Location impact = event.getBlock().getLocation().add(0.5, -0.25, 0.5);
        List<Item> targets = impact.getWorld().getNearbyEntities(impact, 0.75, 1.0, 0.75,
                        entity -> entity instanceof Item).stream()
                .map(Item.class::cast).filter(item -> !item.getItemStack().getType().isAir()).toList();
        // T3(2026-07-25経済連携): 解体戻り係数バフはこの1回のアンビル着地に参加した本人(owner)の
        // 総合ステータスから一度だけ読む(対象ごとにブレさせない)。
        double buffMultiplier = Math.max(0.0, aggregator.aggregate(owner).totalOf(DISASSEMBLY_RETURN_BONUS_KEY));
        for (Item target : targets) disassemble(target, level, buffMultiplier);
    }

    private void disassemble(Item target, int level, double buffMultiplier) {
        ItemStack stack = target.getItemStack();
        String catalogId = CrossPluginItemResolver.idOf(stack).orElse(null);
        String targetId = catalogId == null ? stack.getType().name() : catalogId;
        List<DisassemblyRule> rules = features.disassemblyRulesFor(targetId);
        if (rules.isEmpty()) return;
        ItemTemplate template = catalogId == null ? null : catalog.template(catalogId).orElse(null);

        List<ItemStack> returns = new ArrayList<>();
        for (DisassemblyRule rule : rules) {
            // 2026-07-27: base-amount を書いたルールはレシピを一切引かない。クラフトレシピを持たない
            // アイテム(釣りのゴミ等)はレシピ由来の材料数が必ず 0 になり、従来は永久に戻りが出なかった。
            double ingredientCount;
            if (rule.hasBaseAmount()) {
                ingredientCount = rule.baseAmount();
            } else {
                ingredientCount = template == null ? vanillaIngredientCount(stack, rule.input())
                        : ingredientCount(template.recipes(), rule.input());
            }
            // 2026-07-27: 戻り先が複数あるルールは weight で1件だけ当てる(全部は出さない)。
            CraftingFeaturesConfig.DisassemblyOutput chosen =
                    rule.pick(ThreadLocalRandom.current().nextDouble());
            if (chosen == null) continue; // 有効な戻り先が無いルールは何もしない(素材も消費しない)。
            // 2026-07-28: 戻り総%は features.disassemblyPercentFor(level) が解決する(disassembly.tiers
            // の完全一致優先、無ければ従来どおり percentPerLevel × level の線形式)。
            int totalPercent = features.disassemblyPercentFor(level);
            long amount = scaleByStack(
                    returnAmount(ingredientCount, totalPercent, chosen.multiplier(), buffMultiplier),
                    stack.getAmount());
            if (amount < 0) return; // malformed or excessive config must never consume the source item.
            if (amount == 0) continue;
            List<ItemStack> returned = resolveReturnStacks(chosen.item(), amount);
            if (returned == null) return;
            returns.addAll(returned);
        }
        if (returns.isEmpty()) return; // no fallback: nothing is consumed unless a configured return exists.

        // 地上ドロップは同種が1つの Item にマージされる。1着だけ消費すると
        // 「同じ部位を固めてスクラップしたら1着分」になる（2026-08-29）。
        target.remove();
        for (ItemStack returned : returns) {
            target.getWorld().dropItemNaturally(target.getLocation(), returned);
        }
    }

    private double ingredientCount(List<RecipeSpec> recipes, String input) {
        // Crafted items do not retain recipe provenance. Use the lowest matching input cost per one
        // output item across all recipes: this prevents a cheaper alternate recipe from being
        // disassembled as if it had used the most expensive recipe.
        double lowestPerItem = Double.POSITIVE_INFINITY;
        for (RecipeSpec recipe : recipes) {
            int count = countInRecipe(recipe, input);
            if (count > 0) {
                lowestPerItem = Math.min(lowestPerItem, count / (double) Math.max(1, recipe.amount()));
            }
        }
        return Double.isFinite(lowestPerItem) ? lowestPerItem : 0;
    }

    private double vanillaIngredientCount(ItemStack result, String input) {
        if (input == null || input.regionMatches(true, 0, "custom:", 0, 7)) return 0;
        if (!isMaterialInput(input)) return 0;
        return lowestMatchingIngredientCount(result, input, Bukkit.getRecipesFor(result));
    }

    /**
     * バニラ（未刻印）品の素材数。{@code getRecipesFor} は material だけでマッチするので、
     * 同じ革チェストでもカタログの骨の守護（革2）などが混ざる。それを min すると
     * バニラ8枠が 1〜2 に潰れ、Lv3 60%×2 が 6 や 2 になる。
     * {@link ShapedRecipe#getIngredientMap()} は文字キー1件なので、形を歩いてマスを数える。
     */
    static double lowestMatchingIngredientCount(ItemStack result, String input, Iterable<Recipe> recipes) {
        double lowestPerItem = Double.POSITIVE_INFINITY;
        for (Recipe recipe : recipes) {
            if (!isVanillaRecipeForUnstampedItem(result, recipe)) continue;
            int count = craftingOrNetheriteUpgradeCount(recipe, input);
            if (count > 0) {
                lowestPerItem = Math.min(lowestPerItem,
                        count / (double) Math.max(1, recipe.getResult().getAmount()));
            }
        }
        return Double.isFinite(lowestPerItem) ? lowestPerItem : 0;
    }

    /**
     * 未刻印スタックに対しては {@code minecraft:} 名前空間で、成果物に CMD が無いレシピだけを使う。
     * プラグインが同じ material で登録したカタログ品は別物。
     */
    static boolean isVanillaRecipeForUnstampedItem(ItemStack disassembled, Recipe recipe) {
        if (disassembled == null || recipe == null) return false;
        if (hasCustomModelData(disassembled)) return false;
        if (recipe instanceof Keyed keyed && !"minecraft".equals(keyed.getKey().getNamespace())) {
            return false;
        }
        ItemStack recipeResult = recipe.getResult();
        if (recipeResult == null || recipeResult.getType() != disassembled.getType()) {
            return false;
        }
        return !hasCustomModelData(recipeResult);
    }

    @SuppressWarnings("deprecation")
    static boolean hasCustomModelData(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        return stack.getItemMeta().hasCustomModelData();
    }

    /**
     * 作業台の形/ばらレシピと、ネザライト強化の鍛冶だけを数える。
     *
     * <p>{@code SmithingTransformRecipe} のうち、成果物がすでに同じネザライト防具なら装飾、
     * 基材がダイヤモンドなら強化。解体の素材数は「その防具を作ったときの素材」だけが正なので、
     * 装飾は数えない。1.21 の防具装飾は {@code smithing_trim} が型ごとに18本あり、
     * addition はタグ {@code #trim_materials}、成果物は空なので {@code getRecipesFor} には
     * 通常乗らない。8+16=24 は 1.20 の型数16を足した誤診（正は18本、かつ足し方そのものが違う）。
     */
    static int craftingOrNetheriteUpgradeCount(Recipe recipe, String input) {
        if (recipe instanceof ShapedRecipe shaped) {
            return countShapedSlots(shaped, input);
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            int count = 0;
            for (ItemStack ingredient : shapeless.getIngredientList()) {
                if (ingredient != null && materialMatchesInput(ingredient.getType(), input)) {
                    count++;
                }
            }
            return count;
        }
        if (recipe instanceof SmithingTransformRecipe smithing
                && matchesSmithingAddition(smithing, input)) {
            ItemStack resultStack = smithing.getResult();
            Material resultType = resultStack.getType();
            if (resultType == null || !resultType.name().startsWith("NETHERITE_")) {
                return 0;
            }
            // 装飾: 基材がすでにネザライト防具。強化: 基材はダイヤモンドで成果物と別物。
            RecipeChoice base = smithing.getBase();
            if (base != null && base.test(new ItemStack(resultType))) {
                return 0;
            }
            return 1;
        }
        return 0;
    }

    /**
     * 形のマスを数える。{@code getIngredientMap().values()} は文字キーが1件なので、
     * 革チェスト {@code X X / XXX / XXX} が 8 ではなく 1 になる。
     */
    static int countShapedSlots(ShapedRecipe shaped, String input) {
        var choices = shaped.getChoiceMap();
        int count = 0;
        for (String row : shaped.getShape()) {
            for (int i = 0; i < row.length(); i++) {
                char symbol = row.charAt(i);
                if (symbol == ' ') continue;
                RecipeChoice choice = choices.get(symbol);
                if (choiceMatchesInput(choice, input)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean choiceMatchesInput(RecipeChoice choice, String input) {
        if (choice == null || input == null) return false;
        if (input.regionMatches(true, 0, "list:", 0, 5)) {
            return MaterialLists.resolve(input.substring(5).trim()).stream()
                    .anyMatch(material -> choice.test(new ItemStack(material)));
        }
        Material material = Material.matchMaterial(input);
        return material != null && choice.test(new ItemStack(material));
    }

    private int countInRecipe(RecipeSpec recipe, String input) {
        int count = 0;
        if (recipe.type() == RecipeSpec.Type.SHAPED) {
            for (String row : recipe.shape()) for (char symbol : row.toCharArray()) {
                RecipeIngredient ingredient = recipe.shapedIngredients().get(symbol);
                if (ingredient != null && tokenMatches(ingredient, input)) count++;
            }
        } else {
            for (RecipeIngredient ingredient : recipe.shapelessIngredients()) {
                if (tokenMatches(ingredient, input)) count++;
            }
        }
        if (recipe.isCombine()) {
            if (tokenMatchesCatalogId(recipe.sourceItem(), input)) count++;
            if (tokenMatchesCatalogId(recipe.additionItem(), input)) count++;
        } else if (recipe.isNetherite() && tokenMatchesCatalogId(recipe.sourceItem(), input)) {
            count++;
        }
        return count;
    }

    private boolean tokenMatches(RecipeIngredient ingredient, String input) {
        if (ingredient.isCustom()) return tokenMatchesCatalogId(ingredient.catalogId(), input);
        if (ingredient.isList()) {
            return MaterialLists.resolve(ingredient.listId()).stream()
                    .anyMatch(material -> materialMatchesInput(material, input));
        }
        return ingredient.material() != null && materialMatchesInput(ingredient.material(), input);
    }

    private static boolean isMaterialInput(String input) {
        if (input.regionMatches(true, 0, "list:", 0, 5)) {
            return !MaterialLists.resolve(input.substring(5).trim()).isEmpty();
        }
        return Material.matchMaterial(input) != null;
    }

    /** Whether a concrete vanilla ingredient is covered by a Material or {@code list:<id>} rule input. */
    static boolean materialMatchesInput(Material material, String input) {
        if (material == null || input == null) return false;
        if (input.regionMatches(true, 0, "list:", 0, 5)) {
            return MaterialLists.resolve(input.substring(5).trim()).contains(material);
        }
        Material expected = Material.matchMaterial(input);
        return material == expected;
    }

    private static boolean matchesSmithingAddition(SmithingTransformRecipe recipe, String input) {
        if (input.regionMatches(true, 0, "list:", 0, 5)) {
            return MaterialLists.resolve(input.substring(5).trim()).stream()
                    .anyMatch(material -> recipe.getAddition().test(new ItemStack(material)));
        }
        Material material = Material.matchMaterial(input);
        return material != null && recipe.getAddition().test(new ItemStack(material));
    }

    private boolean tokenMatchesCatalogId(String catalogId, String input) {
        return catalogId != null && input != null && input.regionMatches(true, 0, "custom:", 0, 7)
                && catalogId.equalsIgnoreCase(input.substring(7).trim());
    }

    static long returnAmount(double ingredientCount, int level, int percentPerLevel, double multiplier) {
        double base = Math.floor(ingredientCount * (double) level * percentPerLevel / 100.0);
        double amount = Math.floor(base * multiplier);
        if (!Double.isFinite(amount) || amount < 0 || amount > Integer.MAX_VALUE) return -1;
        return (long) amount;
    }

    /**
     * 地上ドロップのスタック数ぶん戻りを倍にする。1個だけ計算すると、同種防具がマージされた
     * スタックをスクラップしたときに1着分しか出ない（2026-08-29）。
     */
    static long scaleByStack(long amount, int stackCount) {
        if (amount < 0) {
            return amount;
        }
        int count = Math.max(1, stackCount);
        if (count == 1) {
            return amount;
        }
        if (amount > Integer.MAX_VALUE / count) {
            return -1;
        }
        return amount * count;
    }

    /**
     * T3(2026-07-25経済連携): {@code disassembly_return_bonus} プレイヤーバフを、上記4引数版が算出した
     * 戻り量に対する追加乗算項として適用する。既存のグローバル設定値({@code percentPerLevel}/
     * {@code multiplier})の意味は一切変えない — バフはそれらの計算結果の上に乗るだけ。
     *
     * <p>安全弁は多重に保たれる: 4引数版が既に -1(不正/過大)を返した場合はバフを適用せずそのまま -1 を
     * 伝播する(バフ側で握りつぶして安全弁を無効化しない)。バフ適用後の値も同じ有限/非負/{@code
     * Integer.MAX_VALUE}以下チェックを通す。
     */
    static long returnAmount(double ingredientCount, int level, int percentPerLevel, double multiplier,
                             double buffMultiplier) {
        long base = returnAmount(ingredientCount, level, percentPerLevel, multiplier);
        return applyBuffMultiplier(base, buffMultiplier);
    }

    /**
     * 2026-07-28: {@code CraftingFeaturesConfig#disassemblyPercentFor} が解決した戻り総%(tierの
     * 完全一致 or 従来の線形式)を直接受け取る版。旧4引数版({@code level, percentPerLevel}を別々に受ける)
     * は既存テストが直接呼んでいるため残し、こちらは新しい呼び出し経路(DisassemblyListener本体)専用。
     * 計算式は旧来と等価: {@code floor(floor(ingredientCount × totalPercent / 100) × multiplier)}。
     * tiers未設定時は totalPercent = level × percentPerLevel と等しくなるため、数値は1ビットも変わらない。
     */
    static long returnAmount(double ingredientCount, int totalPercent, double multiplier, double buffMultiplier) {
        double base = Math.floor(ingredientCount * (double) totalPercent / 100.0);
        double amount = Math.floor(base * multiplier);
        long baseAmount = (!Double.isFinite(amount) || amount < 0 || amount > Integer.MAX_VALUE) ? -1 : (long) amount;
        return applyBuffMultiplier(baseAmount, buffMultiplier);
    }

    /**
     * T3(2026-07-25経済連携): {@code disassembly_return_bonus} プレイヤーバフを {@code base} に対する
     * 追加乗算項として適用する。{@code base < 0}(不正/過大)なら安全弁が既に発火済みとしてそのまま伝播する。
     */
    private static long applyBuffMultiplier(long base, double buffMultiplier) {
        if (base < 0) {
            return -1;
        }
        if (!Double.isFinite(buffMultiplier) || buffMultiplier <= 0.0) {
            return base;
        }
        double boosted = Math.floor(base * (1.0 + buffMultiplier));
        if (!Double.isFinite(boosted) || boosted < 0 || boosted > Integer.MAX_VALUE) {
            return -1;
        }
        return (long) boosted;
    }

    /** Returns null for invalid/unreasonably large output, so the source is kept intact. */
    private List<ItemStack> resolveReturnStacks(String token, long amount) {
        ItemStack prototype;
        if (token.regionMatches(true, 0, "custom:", 0, 7)) {
            prototype = itemResolver.create(token.substring(7).trim(), ThreadLocalRandom.current().nextLong(), 0)
                    .orElse(null);
        } else {
            Material material = Material.matchMaterial(token);
            prototype = material == null ? null : new ItemStack(material);
        }
        if (prototype == null || prototype.getType().isAir()) return null;
        int maxStack = Math.max(1, prototype.getMaxStackSize());
        long maxAmount = (long) maxStack * MAX_RETURN_STACKS_PER_RULE;
        if (amount > maxAmount) return null;
        List<ItemStack> stacks = new ArrayList<>();
        while (amount > 0) {
            ItemStack part = prototype.clone();
            int partAmount = (int) Math.min(amount, maxStack);
            part.setAmount(partAmount);
            stacks.add(part);
            amount -= partAmount;
        }
        return stacks;
    }

    /**
     * C/D/E ノードの value は加算量ではなく絶対tier (1/2/3)。
     * 直列取得した3ノードを合算すると6になり、未定義tierの線形フォールバック(150%)へ入るため、
     * 段階効果の契約どおり最大値だけを採用する。
     */
    static int dismantleLevel(DedicatedEffectsConfig dedicatedEffects, Player player) {
        OptionalDouble highest = dedicatedEffects.valueMax(player, UNLOCK);
        int value = highest.isPresent() ? (int) Math.floor(highest.getAsDouble()) : 0;
        return value > 0 ? value : (dedicatedEffects.isActive(player, UNLOCK) ? 1 : 0);
    }

    private static boolean isAnvil(Material material) {
        return material == Material.ANVIL || material == Material.CHIPPED_ANVIL || material == Material.DAMAGED_ANVIL;
    }

    private void discardExpiredTracking(long now) {
        placedAnvils.entrySet().removeIf(entry -> now - entry.getValue().placedAtMillis() > ANVIL_OWNER_TTL_MILLIS);
        fallingAnvilOwners.entrySet().removeIf(entry -> now - entry.getValue().placedAtMillis() > ANVIL_OWNER_TTL_MILLIS);
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Location location) {
            return new BlockKey(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }

    private record AnvilPlacement(UUID owner, long placedAtMillis) {}
}
