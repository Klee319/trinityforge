package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * TF / ArsPaper が登録したレシピを<b>プレイヤーのレシピ帳へ解禁する</b>唯一の経路 (2026-07-31 D7)。
 *
 * <h2>なぜ必要か</h2>
 * {@code Bukkit.addRecipe} はレシピを<b>サーバに登録するだけ</b>で、レシピ帳(作業台左のレシピ候補)は
 * 「そのプレイヤーが discover 済みのレシピ」しか表示しない。バニラは
 * {@code minecraft:recipes/...} という隠し進捗でこの解禁を配っているため、対応する進捗を持たない
 * プラグイン登録レシピは<b>永久にレシピ帳に出ない</b>。2026-07-31 の調査時点でリポジトリ全体に
 * {@code discoverRecipe(s)} の呼び出しが 1 件も無く、TF/Ars の 277+ レシピが全部この状態だった。
 * {@code Bukkit.updateRecipes()} はレシピ定義とレシピ帳を送り直すだけで<b>解禁済み集合を増やさない</b>ので
 * 代用にならない。
 *
 * <h2>⚠️ 既知の限界: レシピ帳は「索引」であってワンクリック配置は成立しない</h2>
 * {@code custom:<カタログID>} 素材は {@code RecipeChoice.MaterialChoice}(=材質だけ)で登録されている
 * ({@code CatalogRecipeRegistrar#choiceFor})。レシピ帳の「クリックで材料を並べる」機能はこの Bukkit 側の
 * choice を使うため、<b>カスタム素材が要求されているスロットには素のバニラ基材が並ぶ</b>。その盤面は
 * {@code CatalogWorkbenchListener} の per-slot 検証が正しく弾くので、結果枠は空(または別のバニラ結果)に
 * なる。<b>これは仕様</b>: {@code ExactChoice} 化すれば配置は正しくなるが、品質差(PDC/lore)のある個体と
 * 一致しなくなり「持っているのに素材として認識されない」方向の重い退行になるため採らない
 * (オーケストレータ決定 2026-07-31)。
 * <b>レシピ帳は「何が作れるかの索引」として使い、実際に組むときは {@code /tf recipes} の
 * レシピGUIで素材を確認する</b>という割り切りで運用する。
 *
 * <h2>ゲート付きレシピの扱い</h2>
 * {@code recipe:<id>} ゲート(スキルツリーのノードが実際に配置している ID だけがゲート対象。
 * {@code CatalogCraftGateListener} 参照)が未解放のレシピは、解禁せず
 * {@code undiscoverRecipes} で明示的に隠す。隠さないと「レシピ帳に出るのにクラフトすると結果枠が空」
 * になり、進行度の設計が読めなくなる。<b>隠す集合を「実際にブロックされる集合」と必ず一致させる</b>ため、
 * ゲート ID の解決は {@link CatalogCraftGateListener#resolveGateId(NamespacedKey)} を共用する
 * (自前で綴りを組むと片方だけ直したときに静かにズレる)。
 *
 * <h2>再評価のタイミング</h2>
 * <ul>
 *   <li>ログイン時({@link #onJoin})。<b>次tickへ回す</b>のは、同じ join で
 *       {@code PerkMirrorListener}(MONITOR)が PDC の保持perkミラーを書くまで
 *       {@code dedicatedEffects.isActive} が空を返し、解放済みのゲート付きレシピまで隠してしまうため。
 *       同一優先度の登録順に依存しないよう、tick を跨いで確実に後にする。</li>
 *   <li>スキルノード取得直後 / ツリーリセット直後({@code TrinityForge} が
 *       {@code NativeSkillTreeMenu} の refresh コールバックから {@link #reconcile} を呼ぶ)。</li>
 *   <li>{@code /trinityforge reload} と {@code /minecraft:reload} 後({@link #reconcileAllOnline})。</li>
 * </ul>
 *
 * <h2>トグルの意味</h2>
 * {@code recipe-book.reveal-plugin-recipes} を false へ戻しても<b>既に解禁されたレシピは消えない</b>
 * (解禁状態は playerdata に永続する)。false は「今後解禁しない」だけで、ロールバックはしない
 * — 一度出したものを黙って奪うと「昨日まで見えていたレシピが消えた」という別の事故になるため。
 */
public final class RecipeDiscoveryListener implements Listener {

    /**
     * レシピ帳へ解禁する対象 namespace。TF 本体と ArsPaper フォークが登録するレシピだけを対象にし、
     * 他プラグイン/バニラのレシピ帳運用には触らない(バニラは {@code minecraft:recipes/} 進捗が
     * 従来どおり配っている)。config に出さないのは「どのプラグインと統合しているか」という
     * コード側の事実であってチューニング値ではないため。
     */
    static final Set<String> PLUGIN_NAMESPACES = Set.of("trinityforge", "arspaper");

    private static final String GATE_PREFIX = "recipe:";

    private final Plugin plugin;
    private final DedicatedEffectsConfig dedicatedEffects;
    private final CraftingFeaturesConfig features;
    private final Supplier<Collection<NamespacedKey>> catalogRecipeKeys;
    private final Supplier<Collection<NamespacedKey>> serverRecipeKeys;

    /**
     * 本番用。
     *
     * @param catalogRecipeKeys 通常は {@code catalogRecipeRegistrar::allRegisteredKeys}
     *                          ({@link CatalogRecipeRegistrar#allRegisteredKeys()})
     */
    public RecipeDiscoveryListener(Plugin plugin, DedicatedEffectsConfig dedicatedEffects,
                                   CraftingFeaturesConfig features,
                                   Supplier<Collection<NamespacedKey>> catalogRecipeKeys) {
        this(plugin, dedicatedEffects, features, catalogRecipeKeys,
                RecipeDiscoveryListener::scanServerRecipeKeys);
    }

    /**
     * @param serverRecipeKeys ArsPaper など他プラグインが {@code Bukkit.addRecipe} したキーの供給元。
     *                         テストから {@code Bukkit} を触らずに差し替えるためのシーム。
     */
    RecipeDiscoveryListener(Plugin plugin, DedicatedEffectsConfig dedicatedEffects,
                            CraftingFeaturesConfig features,
                            Supplier<Collection<NamespacedKey>> catalogRecipeKeys,
                            Supplier<Collection<NamespacedKey>> serverRecipeKeys) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.features = Objects.requireNonNull(features, "features");
        this.catalogRecipeKeys = Objects.requireNonNull(catalogRecipeKeys, "catalogRecipeKeys");
        this.serverRecipeKeys = Objects.requireNonNull(serverRecipeKeys, "serverRecipeKeys");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // クラスjavadoc「再評価のタイミング」参照: PerkMirrorListener が同じ join の MONITOR で
        // 保持perkのPDCミラーを書くため、同一tick内では isActive がまだ空を返しうる。
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                reconcile(player);
            }
        });
    }

    /** オンライン全員に再適用する({@code /trinityforge reload} / {@code /minecraft:reload} 後)。 */
    public void reconcileAllOnline() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            reconcile(player);
        }
    }

    /**
     * 1人分のレシピ帳を今の登録状態・解放状態へ合わせる。fail-soft(レシピ帳の解禁に失敗しても
     * ログイン処理やノード解放処理を落とさない)。
     */
    public void reconcile(Player player) {
        if (player == null || !features.recipeBookRevealPluginRecipes()) {
            return;
        }
        try {
            Set<String> gatedIds = dedicatedEffects.recipeGatePerks().keySet();
            Plan plan = plan(collectKeys(), gatedIds,
                    gateId -> dedicatedEffects.isActive(player, GATE_PREFIX + gateId));
            if (!plan.reveal().isEmpty()) {
                player.discoverRecipes(plan.reveal());
            }
            if (features.recipeBookHideLockedRecipes() && !plan.hide().isEmpty()) {
                player.undiscoverRecipes(plan.hide());
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[recipe-book] failed to reconcile the recipe book for " + player.getName(), ex);
        }
    }

    /**
     * 解禁対象のキー集合。TF 分は<b>自前台帳({@link CatalogRecipeRegistrar#allRegisteredKeys()})を正</b>とし、
     * ArsPaper 分はサーバのレシピ一覧から namespace で拾う。TF 分を台帳から取るのは、
     * {@code Bukkit.recipeIterator()} が「その時点で enable 済みのプラグインのレシピ」しか返さないため
     * (ArsPaper は TF に depend していて<b>後から</b> enable する。同じ順序制約が
     * {@link CatalogCraftGateListener#verifyRecipeGateIds} にも書かれている)。ログイン時点なら
     * 両方揃っているが、{@code /trinityforge reload} で TF 側だけ登録し直した直後でも
     * 台帳側は必ず正しい集合を返す。
     */
    private Collection<NamespacedKey> collectKeys() {
        LinkedHashSet<NamespacedKey> keys = new LinkedHashSet<>(catalogRecipeKeys.get());
        keys.addAll(serverRecipeKeys.get());
        return keys;
    }

    /** {@code PLUGIN_NAMESPACES} に属する、サーバへ実際に登録済みのレシピキー。 */
    static Collection<NamespacedKey> scanServerRecipeKeys() {
        List<NamespacedKey> out = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe = it.next();
            if (!(recipe instanceof Keyed keyed)) {
                continue;
            }
            NamespacedKey key = keyed.getKey();
            if (key != null && PLUGIN_NAMESPACES.contains(key.getNamespace())) {
                out.add(key);
            }
        }
        return out;
    }

    /** 解禁するキーと隠すキーへの振り分け結果。 */
    record Plan(List<NamespacedKey> reveal, List<NamespacedKey> hide) {}

    /**
     * 純関数の振り分け(Bukkit サーバ不要 = 直接テストできる)。
     *
     * @param keys        対象キー
     * @param gatedIds    実際にスキルツリーへ配置されているゲートID({@code recipeGatePerks().keySet()})。
     *                    <b>ここに無い ID は常に開放</b>なので隠さない。
     * @param holdsGate   そのゲートIDを対象プレイヤーが解放しているか
     */
    static Plan plan(Collection<NamespacedKey> keys, Set<String> gatedIds, Predicate<String> holdsGate) {
        List<NamespacedKey> reveal = new ArrayList<>();
        List<NamespacedKey> hide = new ArrayList<>();
        for (NamespacedKey key : keys) {
            String gateId = CatalogCraftGateListener.resolveGateId(key);
            if (gateId != null && gatedIds.contains(gateId) && !holdsGate.test(gateId)) {
                hide.add(key);
            } else {
                reveal.add(key);
            }
        }
        return new Plan(List.copyOf(reveal), List.copyOf(hide));
    }
}
