package com.trinityforge.listeners;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * ArsPaper が enable し切った後に、カタログのレシピ登録をもう一度走らせる (W-44)。
 *
 * <p><b>なぜ TF 側に要るのか</b>: {@code items/catalog.yml} の素材には ArsPaper の
 * {@code materials.yml} でしか定義されていない {@code custom:} id がある(例: {@code key_binder} が
 * 要求する {@code list:dungeon_seals} の 28 件はすべて Ars 側定義)。TF は ArsPaper より<b>先に</b>
 * enable するので、TF 自身の初回登録時点では ExternalItemRegistry に Ars の層がまだ無く、
 * これらのレシピは 1 件も登録できない。従来はこの再登録を <b>ArsPaper フォーク側の enable フック
 * ({@code TrinityForge#refreshCatalogRecipes})だけ</b>に頼っていたため、フォークが古い・その
 * 呼び出しが失敗した・そもそも別ビルドが載っている、のいずれでも「毎起動レシピ登録失敗 →
 * 永久にクラフト不可」に落ちた。TF 単体で閉じるように、TF 側でも Ars の enable を検知して
 * 再登録する。
 *
 * <p><b>いつ走るか</b>:
 * <ul>
 *   <li>{@link PluginEnableEvent} が ArsPaper のものだったとき ({@link EventPriority#MONITOR} なので
 *       ArsPaper の {@code onEnable} は完了済み = ExternalItemRegistry の Ars 層は構築済み)。</li>
 *   <li>保険として {@link ServerLoadEvent}(全プラグイン enable 後)。TF が ArsPaper より後に
 *       enable した場合など {@code PluginEnableEvent} を観測できない経路を拾う。すでに
 *       再登録済みなら何もしない。</li>
 * </ul>
 *
 * <p>再登録({@code registerAll})は冪等(既存キーを {@code removeRecipe} してから
 * {@code addRecipe} し直す)なので、フォーク側のフックと二重に走っても同じ状態に収束する。
 * 再登録後もまだ保留のカタログ id が残っていれば、それは本当に未知の id なので警告する。
 */
public final class ArsPaperRecipeRefreshListener implements Listener {

    /** Paper のプラグイン名(paper-plugin.yml の {@code name:})。 */
    public static final String ARSPAPER = "ArsPaper";

    private final Runnable refresh;
    private final Supplier<Set<String>> deferredIdsSupplier;
    private final Consumer<String> warn;
    private boolean refreshed;

    /**
     * @param refresh             再登録本体(通常 {@code TrinityForge::refreshCatalogRecipes})
     * @param deferredIdsSupplier 再登録後もまだ保留のカタログ id
     * @param warn                警告の出力先(通常 {@code plugin.getLogger()::warning})
     */
    public ArsPaperRecipeRefreshListener(Runnable refresh, Supplier<Set<String>> deferredIdsSupplier,
            Consumer<String> warn) {
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.deferredIdsSupplier = Objects.requireNonNull(deferredIdsSupplier, "deferredIdsSupplier");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin() == null || !ARSPAPER.equals(event.getPlugin().getName())) {
            return;
        }
        runRefresh();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onServerLoad(ServerLoadEvent event) {
        if (refreshed || !arsPaperEnabled()) {
            return;
        }
        runRefresh();
    }

    /** テスト/診断用: すでに再登録を走らせたか。 */
    public boolean hasRefreshed() {
        return refreshed;
    }

    private void runRefresh() {
        refreshed = true;
        refresh.run();
        Set<String> stillDeferred = deferredIdsSupplier.get();
        if (stillDeferred != null && !stillDeferred.isEmpty()) {
            warn.accept("[items/catalog.yml] ArsPaper の enable 後に再登録しても素材を解決できなかった"
                    + "レシピがあります: " + String.join(", ", stillDeferred)
                    + " — 参照している custom: id が ArsPaper の materials.yml にも "
                    + "items/external-items.yml にも無い可能性があります(該当レシピはクラフト不可のまま)。");
        }
    }

    private boolean arsPaperEnabled() {
        try {
            Plugin ars = Bukkit.getPluginManager().getPlugin(ARSPAPER);
            return ars != null && ars.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }
}
