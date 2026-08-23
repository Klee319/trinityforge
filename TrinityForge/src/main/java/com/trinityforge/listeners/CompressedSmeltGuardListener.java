package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.CompressedSmelt;
import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.Keyed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 圧縮素材({@code progression/crafting-features.yml} の {@code compressed-smelting})を焼いたのに
 * <b>TF のレシピが選ばれなかった</b>ときだけ結果を差し替える保険 (2026-08-23)。
 *
 * <h2>通常経路では何もしない</h2>
 * <p>本命は {@code CatalogRecipeRegistrar#registerCompressedSmelting} が登録する
 * かまど/燻製器/焚き火のレシピ({@code trinityforge:compressed_smelt_*})で、そちらが選ばれていれば
 * このリスナーは即 return する。素材の照合は {@code RecipeChoice.ExactChoice} =
 * 「型 + data component が完全一致」なので、<b>何らかの理由で圧縮素材の中身がレシピ登録時と
 * ずれると一致しなくなり</b>、代わりにバニラのレシピ(素のジャガイモ→ベイクドポテト)が選ばれる。
 * その状態を放置すると <b>9 個分の圧縮素材がバニラ 1 個に化けて 8 個分が消える</b>。
 *
 * <h2>保険の挙動と、その副作用をあえて選んだ理由</h2>
 * <p>結果だけを設定どおりの圧縮焼き物へ差し替える。ただしバニラの
 * {@code AbstractFurnaceBlockEntity#canBurn} は<b>結果スロットの中身とレシピの結果</b>を
 * {@code isSameItemSameComponents} で比べるため、差し替えた圧縮焼き物が結果スロットに残っている間は
 * 2 個目以降が焼けない(＝取り出すまで 1 個ずつになる)。それでも「消滅させない」方を採る。
 * この経路に入ったこと自体が異常なので、{@link Logger} に 1 素材につき 1 回だけ警告を残す。
 */
public final class CompressedSmeltGuardListener implements Listener {

    /** TF が登録したレシピの名前空間。ここのレシピが選ばれていれば差し替えは不要。 */
    private static final String NAMESPACE = "trinityforge";

    private static final Logger LOG = Logger.getLogger(CompressedSmeltGuardListener.class.getName());

    private final CraftingFeaturesConfig features;
    /** 警告済みの入力素材 id。かまどは 1 個ごとに発火するのでログを溢れさせない。 */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public CompressedSmeltGuardListener(CraftingFeaturesConfig features) {
        this.features = Objects.requireNonNull(features, "features");
    }

    /**
     * {@link EventPriority#HIGH} なのは、{@code FurnaceSmeltListener} の精錬ボーナス
     * ({@link EventPriority#MONITOR})が<b>差し替え後の結果</b>を複製するようにするため。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSmelt(FurnaceSmeltEvent event) {
        Optional<String> inputId = CrossPluginItemResolver.idOf(event.getSource());
        if (inputId.isEmpty()) {
            return;
        }
        CompressedSmelt smelt = features.compressedSmelt(inputId.get());
        if (smelt == null) {
            return;
        }
        if (isOurRecipe(event.getRecipe())) {
            return; // 本命のレシピが選ばれている = 差し替え不要(連続精錬もそのまま動く)。
        }
        ItemStack replacement = CrossPluginItemResolver.createArs(smelt.resultId()).orElse(null);
        if (replacement == null) {
            warnOnce(inputId.get(), "結果 '" + smelt.resultId() + "' を ArsPaper から組めませんでした");
            return;
        }
        replacement.setAmount(1);
        event.setResult(replacement);
        warnOnce(inputId.get(), "TF のかまどレシピが選ばれなかったため結果を差し替えました"
                + "(素材の data component がレシピ登録時とずれている可能性があります)");
    }

    /** Package-private for direct unit testing (a live furnace is not needed to check the namespace). */
    static boolean isOurRecipe(Recipe recipe) {
        return recipe instanceof Keyed keyed && NAMESPACE.equals(keyed.getKey().getNamespace());
    }

    private void warnOnce(String inputId, String detail) {
        if (warned.add(inputId)) {
            LOG.warning("[compressed-smelting] " + inputId + ": " + detail);
        }
    }
}
