package com.trinityforge.integration.ars;

import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Soft bridge to ArsPaper's {@code com.arspaper.gui.RecipeBrowserGui}, mirroring
 * {@code com.trinityforge.mobs.EliteMobsInstanceBridge}'s reflection idiom(2026-07-28
 * {@code /tf recipes} 新設): TF は ArsPaper にコンパイル依存しない(依存方向は
 * ArsPaper → TrinityForge の一方向)ため、すべてリフレクション経由でアクセスし、
 * すべての失敗モードを boolean へ握り潰す(fail-soft)。ArsPaper 未導入のサーバでも
 * TF が起動できることを最優先する。
 */
public final class ArsRecipeBrowserBridge {

    private static final String RECIPE_BROWSER_GUI_CLASS = "com.arspaper.gui.RecipeBrowserGui";
    private static final String BASE_GUI_CLASS = "com.arspaper.gui.BaseGui";

    private static final Logger LOG = Logger.getLogger(ArsRecipeBrowserBridge.class.getName());

    private ArsRecipeBrowserBridge() {
    }

    /**
     * {@code RecipeBrowserGui} クラスが読め、必要なコンストラクタ/メソッドが揃っているか。
     */
    public static boolean isAvailable() {
        try {
            Class<?> guiClass = Class.forName(RECIPE_BROWSER_GUI_CLASS);
            guiClass.getConstructor(Player.class);
            resolveOpenMethod(guiClass);
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            LOG.log(Level.FINE, "[ars-recipe-browser-bridge] RecipeBrowserGui class not readable", ex);
            return false;
        }
    }

    /**
     * {@code player} に対して ArsPaper のレシピ一覧GUI({@code RecipeBrowserGui})を開く。
     *
     * @return 生成・{@code open()} 呼び出しが例外なく完了したら {@code true}。
     *         ArsPaper 未導入、またはリフレクション呼び出しに失敗したら {@code false}。
     */
    public static boolean open(Player player) {
        Objects.requireNonNull(player, "player");
        try {
            Class<?> guiClass = Class.forName(RECIPE_BROWSER_GUI_CLASS);
            Constructor<?> constructor = guiClass.getConstructor(Player.class);
            Object gui = constructor.newInstance(player);
            // open() は基底クラス BaseGui の public メソッド。EliteMobsInstanceBridge と同じ理由で、
            // 具象クラス(gui.getClass())からではなく解決したクラスオブジェクトから直接
            // getMethod(...) を取ること(具象が非publicだと IllegalAccessException に化けるため)。
            Method open = resolveOpenMethod(guiClass);
            open.invoke(gui);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            LOG.log(Level.FINE, "[ars-recipe-browser-bridge] open failed for '"
                    + player.getName() + "'", ex);
            return false;
        }
    }

    /**
     * {@code open()}(引数なし)を {@code RecipeBrowserGui} から直接取得を試み、無ければ
     * 宣言元の {@code BaseGui} から取得する。
     */
    private static Method resolveOpenMethod(Class<?> guiClass) throws ReflectiveOperationException {
        try {
            return guiClass.getMethod("open");
        } catch (NoSuchMethodException ignored) {
            Class<?> baseGuiClass = Class.forName(BASE_GUI_CLASS);
            return baseGuiClass.getMethod("open");
        }
    }
}
