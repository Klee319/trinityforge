package com.trinityforge.mobs;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Soft bridge to EliteMobs' {@code DungeonCommands.teleport(Player, String)}, mirroring
 * {@code com.trinityforge.stats.ArsItemGiveBridge}'s reflection idiom (2026-07-27 鍵アイテムGUI入場対応):
 * TF is not compile-dependent on EliteMobs, so all access goes through reflection and every failure
 * mode is swallowed into a boolean/Optional result rather than propagated.
 *
 * <p><b>{@code .yml} 正規化(実障害の再発防止)</b>: EliteMobs側の索引 {@code EMPackage.getEmPackages()}
 * は {@code ContentPackagesConfigFields#getFilename()} をキーにしており、これは常に {@code .yml}
 * 拡張子付き。一方TFの {@code gates.yml} に書く {@code content-package}/{@code aliases} は拡張子なし
 * 表記(規約は {@link MobIdNormalizer} の逆方向と対称)。そのまま渡すと索引に当たらず
 * 「ダンジョンが見つかりません」で無言に失敗するため、渡す直前に必ず {@link #normalizeContentPackageId}
 * で正規化する。
 *
 * <p><b>事前検証について(重要な既知の限界)</b>: {@code DungeonCommands.teleport} は戻り値のない
 * {@code void} メソッドで、失敗時(ダンジョンIDが存在しない/未インストール/既に他のインスタンスに
 * 参加中)は例外を投げず、プレイヤーへメッセージを送るだけで静かに戻る。そのため呼び出し結果だけからは
 * 成否を判定できない。{@link #canEnter} は {@code teleport} 内部が行う3つのガード
 * (EMPackage存在確認・{@code isInstalled()}・{@code MatchInstance.getAnyPlayerInstance} 不在確認)を
 * 同じ公開静的メソッド経由で事前に再現することで、鍵を誤って消費する主要な失敗経路(タイポ/未インストール/
 * 二重参加)を防ぐ。さらに「非インスタンス型ダンジョンでテレポート先が未設定」という teleport の最後の
 * 分岐({@code getTeleportLocation() == null} でメッセージだけ出して戻る)も
 * {@link #hasUsableTeleportTarget} で再現する — ここを見逃すと**鍵だけ消えて何も起きない**という、
 * プレイヤーがアイテムを取り戻せない事故になるため。
 *
 * <p>いずれの事前検証も、EliteMobs 側のクラス構成が想定と違って読めなかった場合は
 * **「検証できない」を「入場不可」ではなく「素通り」に倒す**(下記 {@code hasUsableTeleportTarget})。
 * 版差でTF側が勝手にダンジョンを封鎖するより、EM側の判定に委ねる方が害が小さい。
 */
public final class EliteMobsDungeonBridge {

    private static final String PLUGIN_NAME = "EliteMobs";
    private static final String DUNGEON_COMMANDS_CLASS = "com.magmaguy.elitemobs.commands.DungeonCommands";
    private static final String EM_PACKAGE_CLASS = "com.magmaguy.elitemobs.dungeons.EMPackage";
    private static final String MATCH_INSTANCE_CLASS = "com.magmaguy.elitemobs.instanced.MatchInstance";
    /** teleport が選択ブラウザGUIを開くだけで済む(＝テレポート先の事前確認が不要な)パッケージ型。 */
    private static final String[] BROWSER_BACKED_PACKAGE_CLASSES = {
            "com.magmaguy.elitemobs.dungeons.DynamicDungeonPackage",
            "com.magmaguy.elitemobs.dungeons.WorldInstancedDungeonPackage"
    };

    private static final Logger LOG = Logger.getLogger(EliteMobsDungeonBridge.class.getName());

    private EliteMobsDungeonBridge() {
    }

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) != null;
    }

    /**
     * {@code content-package}/{@code aliases} の表記(拡張子なし)を EliteMobs の索引キー(拡張子あり)へ
     * 揃える。既に {@code .yml}/{@code .yaml} で終わっていれば二重に付けない。{@code null} はそのまま
     * {@code null}。
     */
    public static String normalizeContentPackageId(String rawId) {
        if (rawId == null) {
            return null;
        }
        String lower = rawId.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return rawId;
        }
        return rawId + ".yml";
    }

    /**
     * {@code DungeonCommands.teleport} が内部で行う3つのガードを事前に再現する
     * (存在確認 → インストール済み確認 → 既に他インスタンス参加中でないことの確認)。
     * すべて満たしていれば {@code true}(=teleport呼び出しが実際にプレイヤーを動かす見込みが高い)。
     */
    public static boolean canEnter(Player player, String contentPackageId) {
        if (!isAvailable() || player == null || contentPackageId == null || contentPackageId.isBlank()) {
            return false;
        }
        String normalized = normalizeContentPackageId(contentPackageId);
        try {
            Class<?> emPackageClass = Class.forName(EM_PACKAGE_CLASS);
            Map<?, ?> emPackages = (Map<?, ?>) emPackageClass.getMethod("getEmPackages").invoke(null);
            Object emPackage = emPackages.get(normalized);
            if (emPackage == null) {
                return false;
            }
            boolean installed = Boolean.TRUE.equals(emPackage.getClass().getMethod("isInstalled").invoke(emPackage));
            if (!installed) {
                return false;
            }
            Class<?> matchInstanceClass = Class.forName(MATCH_INSTANCE_CLASS);
            Method getAnyPlayerInstance = matchInstanceClass.getMethod("getAnyPlayerInstance", Player.class);
            Object existingInstance = getAnyPlayerInstance.invoke(null, player);
            if (existingInstance != null) {
                return false;
            }
            return hasUsableTeleportTarget(emPackage);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            LOG.log(Level.WARNING, "[elitemobs-dungeon-bridge] canEnter check failed for '"
                    + normalized + "'", ex);
            return false;
        }
    }

    /**
     * インストール済み EliteMobs コンテンツパッケージのIDを、<b>TF表記(拡張子なし)</b>で列挙する
     * (2026-07-28: {@code /tf dungeon} のサジェストに「インポート済みだが gates.yml に未登録の
     * ダンジョン」が出てこなかった不具合の修正)。
     *
     * <p>索引 {@code EMPackage.getEmPackages()} のキーは {@code getFilename()} = 常に {@code .yml}
     * 付きなので、ここで剥がして {@code gates.yml} の {@code content-package}/{@code aliases} と
     * 同じ語彙へ揃える({@link #normalizeContentPackageId} の逆方向)。**この非対称は EM 連携で
     * 繰り返し踏んでいる罠**なので、入口(ここ)と出口(normalize)の両方で必ず変換すること。
     *
     * <p>{@code isInstalled()} が false のパッケージ(ダウンロードだけして未展開)は除外する —
     * サジェストに出しても {@link #canEnter} で弾かれるだけで役に立たないため。
     *
     * <p>EliteMobs 不在・クラス構成の版差など、読めなかった場合は<b>空集合</b>を返す(fail-soft)。
     * サジェストが減るだけで、gates.yml 由来の候補と手打ちは影響を受けない。
     */
    public static Set<String> installedContentPackageIds() {
        if (!isAvailable()) {
            return Set.of();
        }
        try {
            Class<?> emPackageClass = Class.forName(EM_PACKAGE_CLASS);
            Map<?, ?> emPackages = (Map<?, ?>) emPackageClass.getMethod("getEmPackages").invoke(null);
            if (emPackages == null || emPackages.isEmpty()) {
                return Set.of();
            }
            Set<String> ids = new LinkedHashSet<>();
            for (Map.Entry<?, ?> entry : emPackages.entrySet()) {
                if (!(entry.getKey() instanceof String filename) || entry.getValue() == null) {
                    continue;
                }
                Object emPackage = entry.getValue();
                if (!Boolean.TRUE.equals(emPackage.getClass().getMethod("isInstalled").invoke(emPackage))) {
                    continue;
                }
                ids.add(stripYamlExtension(filename));
            }
            return Set.copyOf(ids);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            LOG.log(Level.WARNING, "[elitemobs-dungeon-bridge] インストール済みパッケージの列挙に失敗", ex);
            return Set.of();
        }
    }

    /** {@link #normalizeContentPackageId} の逆: 末尾の {@code .yml}/{@code .yaml} を1つだけ剥がす。 */
    static String stripYamlExtension(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yml")) {
            return filename.substring(0, filename.length() - ".yml".length());
        }
        if (lower.endsWith(".yaml")) {
            return filename.substring(0, filename.length() - ".yaml".length());
        }
        return filename;
    }

    /**
     * {@code DungeonCommands.teleport} の最後の分岐を再現する。動的ダンジョン
     * ({@code DynamicDungeonPackage}) とワールドインスタンス型 ({@code WorldInstancedDungeonPackage})
     * は選択ブラウザGUIを開くだけなので必ず「動く」。それ以外は
     * {@code getContentPackagesConfigFields().getTeleportLocation()} が {@code null} だと
     * メッセージを出して何もせず戻るため、ここで弾かないと鍵だけが消える。
     *
     * <p>EliteMobs のクラス構成が想定と違って判定できなかった場合は {@code true}(素通り)を返す。
     * 版差でTF側が勝手に全ダンジョンを封鎖する方が、EM側の判定に委ねるより害が大きいため。
     */
    private static boolean hasUsableTeleportTarget(Object emPackage) {
        try {
            for (String browserBackedClass : BROWSER_BACKED_PACKAGE_CLASSES) {
                if (Class.forName(browserBackedClass).isInstance(emPackage)) {
                    return true;
                }
            }
            Object fields = emPackage.getClass().getMethod("getContentPackagesConfigFields").invoke(emPackage);
            if (fields == null) {
                return false;
            }
            return fields.getClass().getMethod("getTeleportLocation").invoke(fields) != null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            LOG.log(Level.FINE, "[elitemobs-dungeon-bridge] テレポート先の事前確認ができなかったため"
                    + "EliteMobs側の判定に委ねる", ex);
            return true;
        }
    }

    /**
     * {@code canEnter} で事前検証した上で {@code DungeonCommands.teleport(Player, String)} を呼ぶ。
     * 呼び出し前に必ず {@link #canEnter} を通すこと(このメソッド単体は再検証しない — 呼び出し側の
     * 二重手間を避けるため、GUI側の確定フローが1回だけ呼ぶ設計)。
     *
     * @return 反射呼び出し自体が例外なく完了したか。teleport() は void のため、EliteMobs側の内部状態
     *         (getTeleportLocation() 未設定等)による更に奥の失敗までは検出できない(クラスjavadoc参照)。
     */
    public static boolean teleport(Player player, String contentPackageId) {
        Objects.requireNonNull(player, "player");
        if (!isAvailable() || contentPackageId == null || contentPackageId.isBlank()) {
            return false;
        }
        String normalized = normalizeContentPackageId(contentPackageId);
        try {
            Class<?> clazz = Class.forName(DUNGEON_COMMANDS_CLASS);
            Method method = clazz.getMethod("teleport", Player.class, String.class);
            method.invoke(null, player, normalized);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            LOG.log(Level.WARNING, "[elitemobs-dungeon-bridge] teleport failed for '" + normalized + "'", ex);
            return false;
        }
    }
}
