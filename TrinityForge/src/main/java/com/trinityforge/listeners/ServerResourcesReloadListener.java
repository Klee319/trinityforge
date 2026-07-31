package com.trinityforge.listeners;

import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Objects;

/**
 * {@code /minecraft:reload}(データパック再読込)後に、サーバ側で作り直されてしまった TF の登録物を
 * 張り直す (2026-07-31 D10)。
 *
 * <p><b>なぜ必要か</b>: {@code PotionBrewing#reload(FeatureFlagSet)} は {@code bootstrap(...)} を
 * 返すだけで <b>customMixes を引き継がない</b>。呼び出し元は
 * {@code MinecraftServer#reloadResources} なので、{@code /minecraft:reload} 一発で
 * {@link com.trinityforge.stats.BrewPotionMixRegistrar} が登録した醸造 mix が全消滅し、
 * 「さっきまで置けていた討伐素材が上段に入らない」という原因の分かりにくい退行になる。
 *
 * <p>{@code MONITOR} で走らせるのは、他プラグインのリロード処理が終わってから張り直したいだけで
 * この経路は何もキャンセルしないため。
 */
public final class ServerResourcesReloadListener implements Listener {

    private final Runnable reapply;

    public ServerResourcesReloadListener(Runnable reapply) {
        this.reapply = Objects.requireNonNull(reapply, "reapply");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResourcesReloaded(ServerResourcesReloadedEvent event) {
        reapply.run();
    }
}
