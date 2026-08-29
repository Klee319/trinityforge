package com.trinityforge.integration.ars;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ArsPaper の {@code com.arspaper.item.ArmorManaListener#recalculateArmorBonus(Player)}(static)を
 * リフレクションで叩く、プル型フォールバック(2026-08-25)。
 *
 * <p><b>背景</b>: 装備スレッド/マナ系ステは ArsPaper の {@code ArmorManaListener} が<b>プッシュ型</b>で
 * (防具変更・スレッドGUIを閉じる・インベントリクリック(防具/シフト/ホットバー選択枠/オフハンド枠)・
 * ホットバー選択変更・F持ち替え・参加、の6経路で)プレイヤーのPDC({@code AddonCombatStats})へ
 * 書き込む。{@code PlayerStatAggregator#computeAggregate} はそのPDCを読むだけ(自分で再計算しない)ため、
 * 武器を「選択スロット番号が変わらない差し替え」(コマンド/外部プラグイン経由の直接セット等)で
 * 入れ替えると上記6経路のどれも踏まず、{@code /tf status} が古い値のまま表示され続ける。
 *
 * <p>このブリッジは表示直前(/tf status を開く直前)と {@code /trinityforge reload} 直後に
 * 同期的に再計算を促す「保険」。プッシュ型の6経路は一切変更しない。
 *
 * <p>TF は ArsPaper にコンパイル依存しない(依存方向は ArsPaper → TrinityForge の一方向、
 * {@link ArsGuiBridgeSupport} と同じ理由)ため、すべてリフレクション経由でアクセスし、
 * すべての失敗モードを boolean へ握り潰す(fail-soft)。ArsPaper 未導入/未 enable のサーバでも
 * 何もせず {@code /tf status} は通常どおり動く。
 */
public final class ArsArmorStatRefreshBridge {

    private static final String LISTENER_CLASS = "com.arspaper.item.ArmorManaListener";
    private static final String METHOD_NAME = "recalculateArmorBonus";

    private static final Logger LOG = Logger.getLogger(ArsArmorStatRefreshBridge.class.getName());

    private ArsArmorStatRefreshBridge() {
    }

    /**
     * {@code player} のスレッド/マナ系PDCを同期的に最新化する。ArsPaper 未導入/未 enable/
     * リフレクション失敗は全部 {@code false} へ握り潰す(fail-soft、呼び出し元は戻り値を無視してよい)。
     */
    public static boolean refresh(Player player) {
        Objects.requireNonNull(player, "player");
        ClassLoader loader = ArsGuiBridgeSupport.arsPaperClassLoader();
        return loader != null && refresh(player, loader);
    }

    /** Package-private class-loader seam for regression testing of Paper's plugin isolation. */
    static boolean refresh(Player player, ClassLoader arsPaperClassLoader) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(arsPaperClassLoader, "arsPaperClassLoader");
        try {
            Class<?> listenerClass = arsPaperClassLoader.loadClass(LISTENER_CLASS);
            Method method = listenerClass.getMethod(METHOD_NAME, Player.class);
            method.invoke(null, player);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            LOG.log(Level.FINE, "[ars-armor-stat-refresh] recalc failed for '" + player.getName() + "'", ex);
            return false;
        }
    }
}
