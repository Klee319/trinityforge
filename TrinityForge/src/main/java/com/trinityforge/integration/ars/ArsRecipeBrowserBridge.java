package com.trinityforge.integration.ars;

import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * Soft bridge to ArsPaper's {@code com.arspaper.gui.RecipeBrowserGui}, mirroring
 * {@code com.trinityforge.mobs.EliteMobsInstanceBridge}'s reflection idiom(2026-07-28
 * {@code /tf recipes} 新設): TF は ArsPaper にコンパイル依存しない(依存方向は
 * ArsPaper → TrinityForge の一方向)ため、すべてリフレクション経由でアクセスし、
 * すべての失敗モードを boolean へ握り潰す(fail-soft)。ArsPaper 未導入のサーバでも
 * TF が起動できることを最優先する。
 *
 * <p>リフレクションの実体は {@link ArsGuiBridgeSupport}({@code /tf glyphs} の
 * {@link ArsGlyphBrowserBridge} と共有)。
 */
public final class ArsRecipeBrowserBridge {

    private static final String RECIPE_BROWSER_GUI_CLASS = "com.arspaper.gui.RecipeBrowserGui";

    private ArsRecipeBrowserBridge() {
    }

    /**
     * {@code RecipeBrowserGui} クラスが読め、必要なコンストラクタ/メソッドが揃っているか。
     */
    public static boolean isAvailable() {
        ClassLoader loader = ArsGuiBridgeSupport.arsPaperClassLoader();
        return loader != null && isAvailable(loader);
    }

    /**
     * Paper plugin class loaders are isolated. ArsPaper depends on TrinityForge, not vice versa, so
     * TF's own loader cannot see Ars classes even while the plugin is installed. Always resolve the
     * reflective API through the loader which loaded ArsPaper itself.
     */
    static boolean isAvailable(ClassLoader arsPaperClassLoader) {
        return ArsGuiBridgeSupport.isAvailable(arsPaperClassLoader, RECIPE_BROWSER_GUI_CLASS);
    }

    /**
     * {@code player} に対して ArsPaper のレシピ一覧GUI({@code RecipeBrowserGui})を開く。
     *
     * @return 生成・{@code open()} 呼び出しが例外なく完了したら {@code true}。
     *         ArsPaper 未導入、またはリフレクション呼び出しに失敗したら {@code false}。
     */
    public static boolean open(Player player) {
        Objects.requireNonNull(player, "player");
        ClassLoader loader = ArsGuiBridgeSupport.arsPaperClassLoader();
        return loader != null && open(player, loader);
    }

    /** Package-private class-loader seam for regression testing of Paper's plugin isolation. */
    static boolean open(Player player, ClassLoader arsPaperClassLoader) {
        return ArsGuiBridgeSupport.open(player, arsPaperClassLoader, RECIPE_BROWSER_GUI_CLASS);
    }
}
