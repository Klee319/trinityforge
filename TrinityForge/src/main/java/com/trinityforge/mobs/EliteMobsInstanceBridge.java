package com.trinityforge.mobs;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Soft bridge to EliteMobs' {@code com.magmaguy.elitemobs.instanced.MatchInstance}, mirroring
 * {@link EliteMobsDungeonBridge}'s reflection idiom(2026-07-27 {@code /tf start}/{@code /tf stop}
 * 対応): TF は EliteMobs にコンパイル依存しないため、すべてリフレクション経由でアクセスし、
 * すべての失敗モードを boolean へ握り潰す(fail-soft)。
 *
 * <p>{@link #startMatch} は {@code MatchInstance.getPlayerInstance(Player)}(static)でプレイヤーが
 * 待機中のインスタンス(アリーナ/ダンジョン)を取得し、見つかれば {@code countdownMatch()}(引数なし、
 * インスタンスメソッド)を呼んで開始する。{@link #leaveMatch} は
 * {@code MatchInstance.getAnyPlayerInstance(Player)}(static)で参加中のインスタンスを取得し、
 * 見つかれば {@code removeAnyKind(Player)} で離脱させる。どちらも該当インスタンスが見つからない
 * (=待機中/参加中でない)場合は静かに {@code false} を返す — 呼び出し側(コマンド)がユーザー向けの
 * メッセージを出す。
 */
public final class EliteMobsInstanceBridge {

    private static final String PLUGIN_NAME = "EliteMobs";
    private static final String MATCH_INSTANCE_CLASS = "com.magmaguy.elitemobs.instanced.MatchInstance";

    private static final Logger LOG = Logger.getLogger(EliteMobsInstanceBridge.class.getName());

    private EliteMobsInstanceBridge() {
    }

    /** EliteMobs が導入済みで、かつ {@code MatchInstance} クラスが読めるか。 */
    public static boolean isAvailable() {
        if (Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) == null) {
            return false;
        }
        try {
            Class.forName(MATCH_INSTANCE_CLASS);
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            LOG.log(Level.FINE, "[elitemobs-instance-bridge] MatchInstance class not readable", ex);
            return false;
        }
    }

    /**
     * {@code player} が待機中のインスタンス(アリーナ/ダンジョン)を開始する。
     *
     * @return 該当インスタンスが見つかり、開始呼び出しが例外なく完了したら {@code true}。
     *         待機中のインスタンスが無い、または反射呼び出しに失敗したら {@code false}。
     */
    public static boolean startMatch(Player player) {
        Objects.requireNonNull(player, "player");
        try {
            Class<?> matchInstanceClass = Class.forName(MATCH_INSTANCE_CLASS);
            Method getPlayerInstance = matchInstanceClass.getMethod("getPlayerInstance", Player.class);
            Object instance = getPlayerInstance.invoke(null, player);
            if (instance == null) {
                return false;
            }
            // メソッドは具象サブクラス(ArenaInstance/DungeonInstance)ではなく public な抽象基底
            // MatchInstance から取ること。具象側が非publicだったりオーバーライドしていた場合、
            // instance.getClass() 経由だと declaringClass が非publicになり IllegalAccessException に
            // 化けるため。invoke 時の仮想ディスパッチはこの取り方でも通常どおり効く。
            Method countdownMatch = matchInstanceClass.getMethod("countdownMatch");
            countdownMatch.invoke(instance);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            LOG.log(Level.FINE, "[elitemobs-instance-bridge] startMatch failed for '"
                    + player.getName() + "'", ex);
            return false;
        }
    }

    /**
     * {@code player} が参加中のインスタンスから離脱させる。
     *
     * @return 該当インスタンスが見つかり、離脱呼び出しが例外なく完了したら {@code true}。
     *         参加中のインスタンスが無い、または反射呼び出しに失敗したら {@code false}。
     */
    public static boolean leaveMatch(Player player) {
        Objects.requireNonNull(player, "player");
        try {
            Class<?> matchInstanceClass = Class.forName(MATCH_INSTANCE_CLASS);
            Method getAnyPlayerInstance = matchInstanceClass.getMethod("getAnyPlayerInstance", Player.class);
            Object instance = getAnyPlayerInstance.invoke(null, player);
            if (instance == null) {
                return false;
            }
            // startMatch と同じ理由で、具象サブクラスではなく public な抽象基底から取る。
            Method removeAnyKind = matchInstanceClass.getMethod("removeAnyKind", Player.class);
            removeAnyKind.invoke(instance, player);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            LOG.log(Level.FINE, "[elitemobs-instance-bridge] leaveMatch failed for '"
                    + player.getName() + "'", ex);
            return false;
        }
    }
}
