package com.trinityforge.stats;

import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewUnlockGroup;
import io.papermc.paper.potion.PotionMix;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code progression/crafting-features.yml} の {@code brew-unlocks} を Paper の醸造 customMixes
 * ({@code PotionBrewer#addPotionMix})へ登録する (2026-07-31 D10 = K-13)。
 *
 * <h2>なぜこれが必要か(K-13 の真因は 2 段)</h2>
 * <ol>
 *   <li><b>上段スロットに入らない</b>: 醸造台の素材スロットは
 *       {@code PotionBrewing#isIngredient}(= 容器素材 or バニラ素材 or <b>customMixes の素材</b>)しか
 *       受け付けない。ホッパー経路({@code canPlaceItem})も同条件。つまり CMD 付きの討伐素材を
 *       上段へ置く手段は {@code addPotionMix} <b>ただ一つ</b>。</li>
 *   <li><b>{@code base: THICK} はそもそも醸造が始まらない</b>: バニラの mix 表に
 *       「THICK を出発点とする」組み合わせは<b>1件も無い</b>(THICK は WATER+グロウストーンの
 *       行き先としてだけ現れる)。よって {@code hasMix} が false → {@code BrewingStandBlockEntity#serverTick}
 *       が次tickで {@code brewTime} を 0 に戻し、{@code BrewEvent} は永久に発火しない。
 *       <b>{@code setBrewingTime(400)} で押し込む旧実装は原理的に効かなかった</b>
 *       (加えて燃料も減らないので、仮に動けば無燃料醸造になっていた)。</li>
 * </ol>
 * mix を登録すると {@code isIngredient} に {@code isCustomIngredient} が入り、{@code hasMix} も
 * {@code hasCustomMix} を最初に見るので、この 2 つが同時に解ける。
 *
 * <h2>素材照合に述語を使う理由</h2>
 * {@link PotionMix#createPredicateChoice} を使う。
 * {@code RecipeChoice.ExactChoice} は「材質 + 全 data component の完全一致」まで見るので
 * lore/表示名/耐久が1つ違うだけで外れ、{@code MaterialChoice} は逆に材質だけなので
 * <b>素の骨や糸まで醸造素材にしてしまう</b>。CMD/PDC 付きアイテムを素材にする実用手段は述語だけ。
 *
 * <h2>⚠️ バニラの mix を潰さないための登録スキップ</h2>
 * {@code PotionBrewing#mix} は customMixes を<b>バニラより先に</b>評価する。したがって
 * バニラで既に成立する {@code (base, ingredient)} に登録すると<b>そのバニラレシピがサーバ全体で
 * 作れなくなる</b>。{@link #vanillaCollision} が該当ペアを検出して WARNING 付きで登録から外す
 * (個別対処ではなく経路で塞ぐ — yml に新しい組み合わせが増えるたびに再発する型なので)。
 *
 * <h2>⚠️ {@code /minecraft:reload} で customMixes は全消滅する</h2>
 * {@code PotionBrewing#reload} は {@code bootstrap()} を返すだけで customMixes を引き継がない。
 * {@code ServerResourcesReloadListener} 経由で {@link #registerAll()} を張り直すこと。
 * また {@code PotionBrewer#resetPotionMixes()} は<b>他プラグインの mix まで消す</b>実装なので
 * 絶対に使わず、自分のキーを {@code removePotionMix} で個別に外す({@link MixSink} に
 * reset を生やしていないのはこのため)。
 *
 * <h2>テスト用シーム</h2>
 * MockBukkit の {@code ServerMock#getPotionBrewer()} は {@code UnimplementedOperationException} を
 * 投げ、TF の skip ガードがそれをビルド失敗へ変える。そのため
 * <b>本番実装が {@code Bukkit.getPotionBrewer()} に触るのは {@link #serverSink()} の中だけ</b>に隔離し、
 * 登録内容の組み立て({@link #plan}/{@link #ingredientChoice}/{@link #inputChoice})は
 * サーバ非依存の純粋な処理として単体で検証できる形にしてある。
 */
public final class BrewPotionMixRegistrar {

    public static final String NAMESPACE = "trinityforge";
    private static final String KEY_PREFIX = "brew_";

    /**
     * バニラが「どのポーションからでも」変換する素材。ベースに関係なく必ず衝突する
     * (延長 / 強化 / 反転 / スプラッシュ化 / 残留化)。
     */
    private static final Set<Material> ANY_BASE_VANILLA_INGREDIENTS = Set.of(
            Material.REDSTONE, Material.GLOWSTONE_DUST, Material.FERMENTED_SPIDER_EYE,
            Material.GUNPOWDER, Material.DRAGON_BREATH);

    /** {@code AWKWARD} を出発点とするバニラの mix 素材(1.21.11 の {@code addVanillaMixes} 相当)。 */
    private static final Set<Material> AWKWARD_VANILLA_INGREDIENTS = Set.of(
            Material.SUGAR, Material.RABBIT_FOOT, Material.GLISTERING_MELON_SLICE,
            Material.SPIDER_EYE, Material.PUFFERFISH, Material.MAGMA_CREAM,
            Material.GOLDEN_CARROT, Material.BLAZE_POWDER, Material.GHAST_TEAR,
            Material.TURTLE_SCUTE, Material.PHANTOM_MEMBRANE, Material.BREEZE_ROD,
            Material.SLIME_BLOCK, Material.STONE, Material.COBWEB);

    /** 登録の宛先。テストから Bukkit を触らずに差し替えるためのシーム(reset は意図的に無い)。 */
    public interface MixSink {
        void add(PotionMix mix);

        void remove(NamespacedKey key);
    }

    /** 1件の登録計画(Bukkit サーバ不要な純データ)。 */
    public record MixPlan(NamespacedKey key, String groupId, BrewPotionSpec spec) {}

    private final Plugin plugin;
    private final Supplier<Map<String, BrewUnlockGroup>> brewUnlocks;
    private final MixSink sink;
    private final Function<BrewPotionSpec, ItemStack> resultFactory;
    private final Set<NamespacedKey> registered = new LinkedHashSet<>();

    public BrewPotionMixRegistrar(Plugin plugin, Supplier<Map<String, BrewUnlockGroup>> brewUnlocks,
                                  MixSink sink) {
        this(plugin, brewUnlocks, sink, spec -> BrewRecipeSupport.customPotion(Material.POTION, spec));
    }

    /**
     * @param resultFactory mix の結果スタックの組み立て。{@code Bukkit.getItemFactory()} を要するため
     *                      テストから差し替えられるようにしてある。<b>結果はビン種別に依らず
     *                      {@code POTION} 固定でよい</b> — スプラッシュ/残留への追随は
     *                      {@code BrewUnlockListener} の per-slot 差し替えが担う。
     */
    BrewPotionMixRegistrar(Plugin plugin, Supplier<Map<String, BrewUnlockGroup>> brewUnlocks,
                           MixSink sink, Function<BrewPotionSpec, ItemStack> resultFactory) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.brewUnlocks = Objects.requireNonNull(brewUnlocks, "brewUnlocks");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.resultFactory = Objects.requireNonNull(resultFactory, "resultFactory");
    }

    /** 本番の登録先。{@code Bukkit.getPotionBrewer()} は呼び出しごとに遅延解決する。 */
    public static MixSink serverSink() {
        return new MixSink() {
            @Override
            public void add(PotionMix mix) {
                Bukkit.getPotionBrewer().addPotionMix(mix);
            }

            @Override
            public void remove(NamespacedKey key) {
                Bukkit.getPotionBrewer().removePotionMix(key);
            }
        };
    }

    /**
     * 前回このレジストラが登録した mix を全部外してから、現在の {@code brew-unlocks} を登録し直す。
     * 冪等(初回 enable / {@code /trinityforge reload} / {@code /minecraft:reload} から何度でも呼べる)。
     * fail-soft: 1件が落ちても残りは登録する。
     */
    public void registerAll() {
        for (NamespacedKey key : registered) {
            try {
                sink.remove(key);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.FINE, "[brew-unlocks] failed to remove mix " + key, ex);
            }
        }
        registered.clear();

        int ok = 0;
        for (MixPlan mixPlan : plan(brewUnlocks.get(), plugin.getLogger())) {
            try {
                sink.add(new PotionMix(mixPlan.key(), resultFactory.apply(mixPlan.spec()),
                        inputChoice(mixPlan.spec().base()), ingredientChoice(mixPlan.spec().ingredient())));
                registered.add(mixPlan.key());
                ok++;
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "[progression/crafting-features.yml] brew-unlocks."
                        + mixPlan.groupId() + ": failed to register potion mix " + mixPlan.key()
                        + "; skipped", ex);
            }
        }
        plugin.getLogger().info("[progression/crafting-features.yml] brew-unlocks: registered "
                + ok + " custom potion mix(es)");
    }

    /** 現在登録しているキー(reload の対称性テスト用)。 */
    public Set<NamespacedKey> registeredKeys() {
        return Set.copyOf(registered);
    }

    /**
     * 登録計画を組む純関数。キーは {@code trinityforge:brew_<groupId>_<n>}
     * ({@code addPotionMix} は<b>同一キーで {@code IllegalArgumentException}</b> を投げるので、
     * グループ内連番で必ず一意にする。groupId のハイフンは NamespacedKey に使えないため {@code _} へ倒す)。
     *
     * <p>同じ {@code (base, ingredient)} を持つ spec が複数グループにあっても<b>それぞれ登録する</b>
     * (mix の結果は {@code BrewUnlockListener} が per-slot で上書きするので、どれが先に一致しても
     * 最終結果は「そのプレイヤーが解放している spec」になる。1件でも登録されていれば醸造は始まる)。
     */
    static List<MixPlan> plan(Map<String, BrewUnlockGroup> groups, Logger log) {
        List<MixPlan> plans = new ArrayList<>();
        if (groups == null) {
            return plans;
        }
        for (Map.Entry<String, BrewUnlockGroup> entry : groups.entrySet()) {
            String groupId = entry.getKey();
            int index = 0;
            for (BrewPotionSpec spec : entry.getValue().potions()) {
                index++;
                String ingredient = spec.ingredient();
                if (ingredient == null || ingredient.isBlank()) {
                    log.warning("[progression/crafting-features.yml] brew-unlocks." + groupId
                            + " potion #" + index + " has no ingredient; no potion mix registered "
                            + "(the ingredient can never be placed in a brewing stand)");
                    continue;
                }
                if (!BrewRecipeSupport.isCustomKey(ingredient)
                        && Material.matchMaterial(ingredient.trim()) == null) {
                    log.warning("[progression/crafting-features.yml] brew-unlocks." + groupId
                            + " potion #" + index + ": unknown ingredient material '" + ingredient
                            + "'; no potion mix registered");
                    continue;
                }
                String collision = vanillaCollision(spec.base(), ingredient);
                if (collision != null) {
                    log.warning("[progression/crafting-features.yml] brew-unlocks." + groupId
                            + " potion #" + index + ": " + collision
                            + " — no custom potion mix registered for this pair (registering it would "
                            + "shadow the vanilla recipe server-wide). Move this entry to a base vanilla "
                            + "never brews from (THICK / MUNDANE) if it should become a TF-only recipe.");
                    continue;
                }
                plans.add(new MixPlan(key(groupId, index), groupId, spec));
            }
        }
        return List.copyOf(plans);
    }

    private static NamespacedKey key(String groupId, int index) {
        String sanitized = groupId == null ? "" : groupId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_./-]", "_").replace('-', '_');
        return new NamespacedKey(NAMESPACE, KEY_PREFIX + sanitized + "_" + index);
    }

    /**
     * {@code (base, ingredient)} がバニラの mix と衝突するなら理由を返す(衝突しないなら {@code null})。
     *
     * <p>判定モデル:
     * <ul>
     *   <li>{@code custom:<id>} 素材は<b>述語照合(PDC 一致)</b>なので、基材が偶然バニラ素材と同じ材質
     *       ({@code endermite_soot} = 火薬 など)でも素のバニラ素材には当たらない → 衝突しない。</li>
     *   <li>{@link #ANY_BASE_VANILLA_INGREDIENTS} はベースに関係なく衝突。</li>
     *   <li>{@code base} 空欄は「任意のビン」= AWKWARD/WATER も含むので、バニラ素材なら必ず衝突。</li>
     *   <li>{@code WATER} はバニラがほぼ全素材から MUNDANE / ネザーウォートから AWKWARD を作るので、
     *       バニラ素材なら衝突扱い。</li>
     *   <li>{@code AWKWARD} は {@link #AWKWARD_VANILLA_INGREDIENTS} と衝突。</li>
     *   <li>{@code THICK} / {@code MUNDANE} / 実ポーションをベースにする場合は、上記の
     *       「どのポーションでも」素材以外では衝突しない(バニラに出発点の mix が無い)。</li>
     * </ul>
     */
    static String vanillaCollision(String base, String ingredient) {
        if (BrewRecipeSupport.isCustomKey(ingredient)) {
            return null;
        }
        Material mat = ingredient == null ? null : Material.matchMaterial(ingredient.trim());
        if (mat == null) {
            return null; // 未知素材は plan() 側で別途スキップ済み
        }
        if (ANY_BASE_VANILLA_INGREDIENTS.contains(mat)) {
            return "'" + mat + "' はバニラが「どのポーションでも」変換する素材(延長/強化/反転/"
                    + "スプラッシュ化/残留化)なので、どのベースに割り当てても衝突する";
        }
        String normalized = base == null ? "" : base.trim().toUpperCase(Locale.ROOT);
        boolean vanillaIngredient = AWKWARD_VANILLA_INGREDIENTS.contains(mat)
                || mat == Material.NETHER_WART;
        if (normalized.isEmpty() && vanillaIngredient) {
            return "base 未指定は「任意のビン」を意味するため、バニラの醸造素材 '" + mat
                    + "' と必ず衝突する";
        }
        if ("WATER".equals(normalized) && vanillaIngredient) {
            return "WATER + '" + mat + "' はバニラが MUNDANE / AWKWARD を作る組み合わせ";
        }
        if ("AWKWARD".equals(normalized) && AWKWARD_VANILLA_INGREDIENTS.contains(mat)) {
            return "AWKWARD + '" + mat + "' はバニラのポーションを作る組み合わせ";
        }
        return null;
    }

    /**
     * 素材側の {@link RecipeChoice}。{@code custom:<id>} は
     * {@link BrewRecipeSupport#matchesIngredient} 経由で TF/Ars 両方の PDC を見る
     * ({@code BrewUnlockListener} の照合と<b>同じ1本</b>を通す)。
     */
    static RecipeChoice ingredientChoice(String expected) {
        return PotionMix.createPredicateChoice(stack -> BrewRecipeSupport.matchesIngredient(stack, expected));
    }

    /** ビン側の {@link RecipeChoice}。{@code base} 空欄は「任意のベース」(ただしポーション容器に限る)。 */
    static RecipeChoice inputChoice(String base) {
        return PotionMix.createPredicateChoice(stack -> stack != null
                && BrewRecipeSupport.isPotionContainer(stack.getType())
                && stack.getItemMeta() instanceof PotionMeta meta
                && BrewRecipeSupport.matchesBase(meta, base));
    }
}
