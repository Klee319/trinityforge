package com.trinityforge.stats;

import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewUnlockGroup;
import com.trinityforge.skilltree.DedicatedEffectEntry;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillTree;
import io.papermc.paper.potion.PotionMix;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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
    /** skilltree 側の gate id 接頭辞({@code BrewUnlockListener} と同じ規約)。 */
    private static final String GATE_PREFIX = "brew:";

    /**
     * バニラの<b>容器 mix</b>(スプラッシュ化 / 残留化)の素材。{@code addContainerRecipe} は
     * 「どのポーションからでも」成立するので、ベースに関係なく必ず衝突する。
     */
    private static final Set<Material> CONTAINER_VANILLA_INGREDIENTS = Set.of(
            Material.GUNPOWDER, Material.DRAGON_BREATH);

    /**
     * 延長({@code REDSTONE}) / 強化({@code GLOWSTONE_DUST}) / 反転({@code FERMENTED_SPIDER_EYE})。
     *
     * <p><b>ベース非依存ではない</b> (2026-07-31 D10 レビュー指摘#3 の修正): バニラはこの3種を
     * 「{@code WATER}」と「効果付きポーション」を出発点にする mix としてしか定義していない
     * ({@code WATER + REDSTONE → MUNDANE} / {@code WATER + GLOWSTONE_DUST → THICK} /
     * {@code WATER + FERMENTED_SPIDER_EYE → WEAKNESS}、あとは各 PotionType の延長・強化・反転)。
     * {@code THICK} / {@code MUNDANE} / {@code AWKWARD} を出発点にする mix は<b>1件も無い</b>ので、
     * 以前のようにベースを見ずに衝突扱いすると<b>実在しないバニラレシピを守るために登録を拒否する</b>
     * (= 運営者が editor で書いた組が「無言で成立しない」= K-13 と同じ症状の再発)。
     */
    private static final Set<Material> POTION_MODIFIER_VANILLA_INGREDIENTS = Set.of(
            Material.REDSTONE, Material.GLOWSTONE_DUST, Material.FERMENTED_SPIDER_EYE);

    /**
     * バニラに「これを出発点にする mix」が1件も無いベース。TF 独自の醸造の置き場所。
     * ({@code THICK} は {@code WATER + グロウストーン}、{@code MUNDANE} は {@code WATER + 各種素材} の
     * <b>行き先</b>としてだけ現れる。)
     */
    private static final Set<String> VANILLA_DEAD_END_BASES = Set.of("THICK", "MUNDANE");

    /** {@code AWKWARD} を出発点とするバニラの mix 素材(1.21.11 の {@code addVanillaMixes} 相当)。 */
    private static final Set<Material> AWKWARD_VANILLA_INGREDIENTS = Set.of(
            Material.SUGAR, Material.RABBIT_FOOT, Material.GLISTERING_MELON_SLICE,
            Material.SPIDER_EYE, Material.PUFFERFISH, Material.MAGMA_CREAM,
            Material.GOLDEN_CARROT, Material.BLAZE_POWDER, Material.GHAST_TEAR,
            Material.TURTLE_SCUTE, Material.PHANTOM_MEMBRANE, Material.BREEZE_ROD,
            Material.SLIME_BLOCK, Material.STONE, Material.COBWEB);

    /**
     * {@code WATER} を出発点とするバニラの mix 素材(1.21.11 の {@code addVanillaMixes} の
     * {@code WATER} 行そのまま。<b>これで全部</b>)。
     *
     * <p>2026-07-31 レビュー指摘#3 の修正: 以前は「AWKWARD 起点の素材 or 延長/強化/反転 or ネザーウォート」
     * を WATER の衝突条件にしていたため、<b>バニラに WATER mix が存在しない8素材</b>
     * ({@code GOLDEN_CARROT} / {@code PUFFERFISH} / {@code TURTLE_SCUTE} / {@code PHANTOM_MEMBRANE} /
     * {@code BREEZE_ROD} / {@code SLIME_BLOCK} / {@code STONE} / {@code COBWEB} — いずれも
     * AWKWARD 起点しか無い)でも登録を拒否していた。実在しないバニラレシピを守るための拒否は
     * 「運営者が editor で書いた組が無言で成立しない」= K-13 と同じ症状の再発なので、
     * <b>実際にバニラが醸造する組だけ</b>を拒否する。
     */
    private static final Set<Material> WATER_VANILLA_INGREDIENTS = Set.of(
            // → ありふれた(MUNDANE)
            Material.GLISTERING_MELON_SLICE, Material.GHAST_TEAR, Material.RABBIT_FOOT,
            Material.BLAZE_POWDER, Material.SPIDER_EYE, Material.SUGAR, Material.MAGMA_CREAM,
            Material.REDSTONE,
            // → 濃厚(THICK) / 弱化(WEAKNESS) / 奇妙(AWKWARD)
            Material.GLOWSTONE_DUST, Material.FERMENTED_SPIDER_EYE, Material.NETHER_WART);

    /** 登録の宛先。テストから Bukkit を触らずに差し替えるためのシーム(reset は意図的に無い)。 */
    public interface MixSink {
        void add(PotionMix mix);

        void remove(NamespacedKey key);
    }

    /**
     * 1件の登録計画(Bukkit サーバ不要な純データ)。
     *
     * @param requirementLevel {@code brew:<groupId>} を置いているノードの最小レベル。
     *                         重複した {@code (base, ingredient)} の勝敗と、
     *                         {@code BrewUnlockListener} が「どの段の効果を出すか」の判断に使う。
     */
    public record MixPlan(NamespacedKey key, String groupId, BrewPotionSpec spec, int requirementLevel) {}

    private final Plugin plugin;
    private final Supplier<Map<String, BrewUnlockGroup>> brewUnlocks;
    private final Supplier<Map<String, Integer>> requirementLevels;
    private final MixSink sink;
    private final Function<BrewPotionSpec, ItemStack> resultFactory;
    private final Set<NamespacedKey> registered = new LinkedHashSet<>();
    /** 最後に実際に登録できた計画。{@code BrewUnlockListener} のゲート/差し替えが参照する唯一の一覧。 */
    private volatile List<MixPlan> livePlans = List.of();

    public BrewPotionMixRegistrar(Plugin plugin, Supplier<Map<String, BrewUnlockGroup>> brewUnlocks,
                                  Supplier<Map<String, Integer>> requirementLevels, MixSink sink) {
        this(plugin, brewUnlocks, requirementLevels, sink,
                spec -> BrewRecipeSupport.customPotion(Material.POTION, spec));
    }

    /**
     * @param resultFactory mix の結果スタックの組み立て。{@code Bukkit.getItemFactory()} を要するため
     *                      テストから差し替えられるようにしてある。<b>結果はビン種別に依らず
     *                      {@code POTION} 固定でよい</b> — スプラッシュ/残留への追随は
     *                      {@code BrewUnlockListener} の per-slot 差し替えが担う。
     */
    BrewPotionMixRegistrar(Plugin plugin, Supplier<Map<String, BrewUnlockGroup>> brewUnlocks,
                           Supplier<Map<String, Integer>> requirementLevels,
                           MixSink sink, Function<BrewPotionSpec, ItemStack> resultFactory) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.brewUnlocks = Objects.requireNonNull(brewUnlocks, "brewUnlocks");
        this.requirementLevels = Objects.requireNonNull(requirementLevels, "requirementLevels");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.resultFactory = Objects.requireNonNull(resultFactory, "resultFactory");
    }

    /**
     * {@code brew:<groupId>} を置いているノードの<b>最小</b>レベル(= そのグループが到達可能になる
     * スキルレベル)。同じ {@code (base, ingredient)} を複数グループが宣言したときの勝敗判定に使う。
     *
     * <p>最小を採るのは、プレイヤーは「その gate を置いているノードのどれか1つ」を取れば解放されるため
     * (複数ノードが同じ gate を置いていれば、最初に届くノードのレベルが実際の要求レベル)。
     * 未参照グループは {@code 0}(= 永久ロックなので優先度も最下位)。
     */
    public static Map<String, Integer> requirementLevels(Collection<SkillTree> trees) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (trees == null) {
            return out;
        }
        for (SkillTree tree : trees) {
            if (tree == null) {
                continue;
            }
            for (SkillNode node : tree.nodes().values()) {
                if (node == null) {
                    continue;
                }
                for (DedicatedEffectEntry entry : node.dedicatedEffects()) {
                    String id = entry == null ? null : entry.id();
                    if (id == null || !id.trim().startsWith(GATE_PREFIX)) {
                        continue;
                    }
                    String groupId = id.trim().substring(GATE_PREFIX.length()).trim();
                    if (groupId.isEmpty()) {
                        continue;
                    }
                    out.merge(groupId, node.level(), Math::min);
                }
            }
        }
        return out;
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

        List<MixPlan> live = new ArrayList<>();
        for (MixPlan mixPlan : plan(brewUnlocks.get(), requirementLevels.get(), plugin.getLogger())) {
            try {
                sink.add(new PotionMix(mixPlan.key(), resultFactory.apply(mixPlan.spec()),
                        inputChoice(mixPlan.spec().base()), ingredientChoice(mixPlan.spec().ingredient())));
                registered.add(mixPlan.key());
                live.add(mixPlan);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "[progression/crafting-features.yml] brew-unlocks."
                        + mixPlan.groupId() + ": failed to register potion mix " + mixPlan.key()
                        + "; skipped", ex);
            }
        }
        this.livePlans = List.copyOf(live);
        plugin.getLogger().info("[progression/crafting-features.yml] brew-unlocks: registered "
                + live.size() + " custom potion mix(es)");
    }

    /** 現在登録しているキー(reload の対称性テスト用)。 */
    public Set<NamespacedKey> registeredKeys() {
        return Set.copyOf(registered);
    }

    /**
     * 実際に mix として登録できた計画の一覧。<b>{@code BrewUnlockListener} のゲート判定と結果差し替えは
     * これだけを見る</b> (2026-07-31 D10 レビュー指摘#1/#3)。
     *
     * <p><b>なぜ生の {@code brewUnlocks()} を見せないのか</b>: 登録されなかった組
     * (バニラ衝突・素材名の綴り間違い・重複の敗者)は<b>そもそも醸造が始まらない</b>か
     * <b>バニラのレシピとして成立する</b>ので、ゲートを掛けると
     * 「バニラの俊敏のポーションが作れない」型の誤爆になるだけで、守るものが無い。
     * 生の config を見ていた実装では実際にこの誤爆が残っていた。
     */
    public List<MixPlan> livePlans() {
        return livePlans;
    }

    /**
     * 登録計画を組む純関数。キーは {@code trinityforge:brew_<groupId>_<n>}
     * ({@code addPotionMix} は<b>同一キーで {@code IllegalArgumentException}</b> を投げるので、
     * グループ内連番で必ず一意にする。groupId のハイフンは NamespacedKey に使えないため {@code _} へ倒す)。
     *
     * <p><b>同じ {@code (base, ingredient)} は1件だけ残す</b> (2026-07-31 D10 レビュー指摘#2)。
     * 重複していると「yml で先に書いた側」が常に勝ってしまい、上位段(高レベルノードの amplifier+1)が
     * <b>絶対に出ない</b>。勝者は {@code requirementLevels} が高い方(= より深いノードが解放するもの)で、
     * 同値なら yml 順の先頭。敗者は WARNING に「どちらを勝たせたか」を出して登録しない。
     */
    static List<MixPlan> plan(Map<String, BrewUnlockGroup> groups, Logger log) {
        return plan(groups, Map.of(), log);
    }

    static List<MixPlan> plan(Map<String, BrewUnlockGroup> groups,
                              Map<String, Integer> requirementLevels, Logger log) {
        List<MixPlan> plans = new ArrayList<>();
        if (groups == null) {
            return plans;
        }
        Map<String, Integer> levels = requirementLevels == null ? Map.of() : requirementLevels;
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
                plans.add(new MixPlan(key(groupId, index), groupId, spec,
                        levels.getOrDefault(groupId, 0)));
            }
        }
        return List.copyOf(dropDuplicatePairs(plans, log));
    }

    /**
     * 同じ {@code (base, ingredient)} を宣言している計画から<b>要求レベルが最も高い1件だけ</b>を残す
     * (2026-07-31 D10 レビュー指摘#2)。
     *
     * <p>残さないと、Paper は最初に一致した mix で醸造を成立させ、{@code BrewUnlockListener} も
     * 一致した先頭の spec で結果を確定するため、<b>上位段は永久に出ない</b>
     * (実害: Lv80「回復・体力増強の調合」の amplifier 1 が Lv60 の amplifier 0 に食われていた)。
     * 同値のときは yml 順の先頭を残す(順序を変えたら結果が変わる、を避けるため決定的にする)。
     */
    private static List<MixPlan> dropDuplicatePairs(List<MixPlan> plans, Logger log) {
        Map<String, MixPlan> winners = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (MixPlan candidate : plans) {
            String pair = BrewRecipeSupport.pairKey(candidate.spec().base(), candidate.spec().ingredient());
            MixPlan current = winners.get(pair);
            if (current == null) {
                winners.put(pair, candidate);
                order.add(pair);
                continue;
            }
            MixPlan winner = candidate.requirementLevel() > current.requirementLevel() ? candidate : current;
            MixPlan loser = winner == candidate ? current : candidate;
            winners.put(pair, winner);
            log.warning("[progression/crafting-features.yml] brew-unlocks: duplicate pair '" + pair
                    + "' declared by both '" + current.groupId() + "' (required level "
                    + current.requirementLevel() + ") and '" + candidate.groupId() + "' (required level "
                    + candidate.requirementLevel() + "); keeping '" + winner.groupId()
                    + "' and dropping '" + loser.groupId() + "' (a duplicated pair means the other group's"
                    + " potion can never be brewed — give each group its own base/ingredient instead)");
        }
        List<MixPlan> out = new ArrayList<>(order.size());
        for (String pair : order) {
            out.add(winners.get(pair));
        }
        return out;
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
     *   <li>{@link #CONTAINER_VANILLA_INGREDIENTS} はベースに関係なく衝突(容器 mix は
     *       「どのポーションからでも」成立する)。</li>
     *   <li>{@code base} 空欄は「任意のビン」= WATER/AWKWARD/効果付きポーションを全部含むので、
     *       それらのいずれかで衝突する素材なら衝突。</li>
     *   <li>{@code WATER} は {@link #WATER_VANILLA_INGREDIENTS}(バニラの WATER 行11件)と衝突。
     *       <b>それ以外のバニラ素材は WATER 起点の mix を持たないので衝突しない</b>
     *       (例: {@code WATER + GOLDEN_CARROT} はバニラに存在しない)。</li>
     *   <li>{@code AWKWARD} は {@link #AWKWARD_VANILLA_INGREDIENTS} と衝突。</li>
     *   <li>{@code THICK} / {@code MUNDANE} をベースにする場合は容器 mix 以外では衝突しない
     *       (バニラに出発点の mix が無い = {@link #VANILLA_DEAD_END_BASES})。</li>
     *   <li>効果付きポーションをベースにする場合は
     *       {@link #POTION_MODIFIER_VANILLA_INGREDIENTS}(延長/強化/反転)と衝突。</li>
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
        if (CONTAINER_VANILLA_INGREDIENTS.contains(mat)) {
            return "'" + mat + "' はバニラの容器 mix(スプラッシュ化/残留化)の素材で、"
                    + "どのポーションからでも成立するのでどのベースに割り当てても衝突する";
        }
        String normalized = base == null ? "" : base.trim().toUpperCase(Locale.ROOT);
        boolean startsAWKWARD = AWKWARD_VANILLA_INGREDIENTS.contains(mat);
        boolean startsWATER = WATER_VANILLA_INGREDIENTS.contains(mat);
        boolean modifier = POTION_MODIFIER_VANILLA_INGREDIENTS.contains(mat);
        if (normalized.isEmpty()) {
            return startsWATER || startsAWKWARD || modifier
                    ? "base 未指定は「任意のビン」を意味するため、バニラの醸造素材 '" + mat
                            + "' と必ず衝突する"
                    : null;
        }
        if (VANILLA_DEAD_END_BASES.contains(normalized)) {
            // THICK / MUNDANE を from とする mix はバニラに1件も無い(容器 mix だけが上で弾かれる)。
            return null;
        }
        if ("WATER".equals(normalized)) {
            return startsWATER
                    ? "WATER + '" + mat + "' はバニラが MUNDANE / THICK / AWKWARD / 弱化 を作る組み合わせ"
                    : null;
        }
        if ("AWKWARD".equals(normalized)) {
            return startsAWKWARD
                    ? "AWKWARD + '" + mat + "' はバニラのポーションを作る組み合わせ"
                    : null;
        }
        // 効果付きポーションをベースにする場合、延長/強化/反転はそのまま当たる。
        return modifier
                ? "'" + mat + "' はバニラが効果付きポーションを延長/強化/反転する素材なので、"
                        + normalized + " ベースでは衝突する"
                : null;
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
