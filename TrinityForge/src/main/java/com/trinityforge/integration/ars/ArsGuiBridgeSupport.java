package com.trinityforge.integration.ars;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ArsPaper の {@code BaseGui} 系GUI({@code new Gui(Player)} → {@code open()})を TF 側から
 * リフレクションで開くための共通実装(2026-07-28)。
 *
 * <p>TF は ArsPaper にコンパイル依存しない(依存方向は ArsPaper → TrinityForge の一方向)ため、
 * すべてリフレクション経由でアクセスし、すべての失敗モードを boolean へ握り潰す(fail-soft)。
 * ArsPaper 未導入のサーバでも TF が起動できることを最優先する。
 *
 * <p>Paper のプラグインクラスローダーは分離されているので、<b>必ず ArsPaper 自身をロードした
 * ローダー</b>から解決すること(TF のローダーからは Ars のクラスが見えない)。
 */
final class ArsGuiBridgeSupport {

    static final String PLUGIN_NAME = "ArsPaper";
    private static final String BASE_GUI_CLASS = "com.arspaper.gui.BaseGui";

    private static final Logger LOG = Logger.getLogger(ArsGuiBridgeSupport.class.getName());

    private ArsGuiBridgeSupport() {
    }

    /** ArsPaper プラグインのクラスローダー。未導入なら {@code null}。 */
    static ClassLoader arsPaperClassLoader() {
        Plugin arsPaper = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        return arsPaper == null ? null : arsPaper.getClass().getClassLoader();
    }

    /** GUIクラスが読め、{@code (Player)} コンストラクタと {@code open()} が揃っているか。 */
    static boolean isAvailable(ClassLoader arsPaperClassLoader, String guiClassName) {
        Objects.requireNonNull(arsPaperClassLoader, "arsPaperClassLoader");
        try {
            Class<?> guiClass = arsPaperClassLoader.loadClass(guiClassName);
            guiClass.getConstructor(Player.class);
            resolveOpenMethod(guiClass, arsPaperClassLoader);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            LOG.log(Level.FINE, "[ars-gui-bridge] " + guiClassName + " not readable", ex);
            return false;
        }
    }

    /** {@code player} に対して該当GUIを開く。失敗はすべて {@code false} に潰す。 */
    static boolean open(Player player, ClassLoader arsPaperClassLoader, String guiClassName) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(arsPaperClassLoader, "arsPaperClassLoader");
        try {
            Class<?> guiClass = arsPaperClassLoader.loadClass(guiClassName);
            Constructor<?> constructor = guiClass.getConstructor(Player.class);
            Object gui = constructor.newInstance(player);
            // open() は基底クラス BaseGui の public メソッド。EliteMobsInstanceBridge と同じ理由で、
            // 具象クラス(gui.getClass())からではなく解決したクラスオブジェクトから直接
            // getMethod(...) を取ること(具象が非publicだと IllegalAccessException に化けるため)。
            Method open = resolveOpenMethod(guiClass, arsPaperClassLoader);
            open.invoke(gui);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            LOG.log(Level.FINE, "[ars-gui-bridge] open failed for '" + player.getName()
                    + "' (" + guiClassName + ")", ex);
            return false;
        }
    }

    /**
     * {@code open()}(引数なし)を対象クラスから直接取得を試み、無ければ宣言元の {@code BaseGui}
     * から取得する。
     */
    private static Method resolveOpenMethod(Class<?> guiClass, ClassLoader arsPaperClassLoader)
            throws ReflectiveOperationException {
        try {
            return guiClass.getMethod("open");
        } catch (NoSuchMethodException ignored) {
            Class<?> baseGuiClass = arsPaperClassLoader.loadClass(BASE_GUI_CLASS);
            return baseGuiClass.getMethod("open");
        }
    }
}
