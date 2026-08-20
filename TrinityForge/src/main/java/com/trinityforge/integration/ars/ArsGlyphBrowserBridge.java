package com.trinityforge.integration.ars;

import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * Soft bridge to ArsPaper's {@code com.arspaper.gui.GlyphBrowserGui}({@code /tf glyphs}、2026-07-28)。
 * {@link ArsRecipeBrowserBridge} と同じ約束(fail-soft・ArsPaper のクラスローダーから解決)で動く。
 */
public final class ArsGlyphBrowserBridge {

    private static final String GLYPH_BROWSER_GUI_CLASS = "com.arspaper.gui.GlyphBrowserGui";

    private ArsGlyphBrowserBridge() {
    }

    /** {@code GlyphBrowserGui} クラスが読め、必要なコンストラクタ/メソッドが揃っているか。 */
    public static boolean isAvailable() {
        ClassLoader loader = ArsGuiBridgeSupport.arsPaperClassLoader();
        return loader != null && isAvailable(loader);
    }

    /** Package-private class-loader seam for regression testing of Paper's plugin isolation. */
    static boolean isAvailable(ClassLoader arsPaperClassLoader) {
        return ArsGuiBridgeSupport.isAvailable(arsPaperClassLoader, GLYPH_BROWSER_GUI_CLASS);
    }

    /**
     * {@code player} に対して ArsPaper のグリフ解放素材GUI({@code GlyphBrowserGui})を開く。
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
        return ArsGuiBridgeSupport.open(player, arsPaperClassLoader, GLYPH_BROWSER_GUI_CLASS);
    }
}
