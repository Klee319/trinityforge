package com.trinityforge.progression;

import com.trinityforge.progression.infrastructure.sqlite.DailyExpWindowStore;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 日次EXP逓減の蓄積を「メモリ({@link DailyExpDiminishing}) ⇄ 共有DB({@link DailyExpWindowStore})」で
 * 出し入れする配線（2026-08-18）。
 *
 * <p><b>Bukkit に依存させない</b>のは意図的。ここには「いつ読むか・いつ書くか・失敗したらどうするか」
 * という判断だけが入っており、そこが壊れると<b>プレイヤーの蓄積が黙って消える</b>。
 * リスナーやスケジューラから切り離しておけば素の JUnit で確かめられる。
 *
 * <p>例外はすべて握って警告ログに落とす。逓減の永続化はゲーム進行の本体ではないので、
 * DB が一時的に書けないくらいでログインや退出を失敗させてはいけない。
 */
public final class DailyExpWindowPersistence {

    private final DailyExpDiminishing diminishing;
    private final DailyExpWindowStore store;
    private final Supplier<DailyExpDiminishing.Settings> settings;
    private final Consumer<String> warn;

    public DailyExpWindowPersistence(DailyExpDiminishing diminishing,
                                     DailyExpWindowStore store,
                                     Supplier<DailyExpDiminishing.Settings> settings,
                                     Consumer<String> warn) {
        this.diminishing = Objects.requireNonNull(diminishing, "diminishing");
        this.store = Objects.requireNonNull(store, "store");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.warn = warn == null ? message -> { } : warn;
    }

    /**
     * 保存済みの蓄積をメモリへ戻す。<b>逓減が無効な間も読む</b> ── 設定を切っている間に
     * 蓄積が消えるわけではないし、あとから有効化したときに「昨日の稼ぎ」が正しく効く。
     */
    public void load(UUID playerId) {
        if (playerId == null) {
            return;
        }
        DailyExpDiminishing.Settings current = currentSettings();
        try {
            List<DailyExpDiminishing.WindowSnapshot> rows = store.load(playerId);
            if (!rows.isEmpty()) {
                diminishing.restore(current, playerId, rows);
            }
        } catch (SQLException | RuntimeException ex) {
            warn.accept("[daily-exp] 逓減の蓄積を読み込めませんでした: " + playerId + " / " + ex);
        }
    }

    /** メモリ上の蓄積を書き出す（状態は捨てない。定期保存とサーバ停止時に使う）。 */
    public void save(UUID playerId) {
        if (playerId == null) {
            return;
        }
        Collection<DailyExpDiminishing.WindowSnapshot> rows = diminishing.snapshot(playerId);
        if (rows.isEmpty()) {
            return;
        }
        try {
            store.save(playerId, rows, currentSettings().windowMillis());
        } catch (SQLException | RuntimeException ex) {
            warn.accept("[daily-exp] 逓減の蓄積を保存できませんでした: " + playerId + " / " + ex);
        }
    }

    /**
     * 退出時。<b>必ず書いてから捨てる</b> ── 順序を逆にすると保存対象が空になり、
     * 「永続化したのに何も残らない」という気づきにくい壊れ方をする。
     */
    public void saveAndForget(UUID playerId) {
        save(playerId);
        diminishing.forget(playerId);
    }

    /** 追跡中の全プレイヤーを書き出す（{@code onDisable} の最終フラッシュ）。 */
    public void saveAll() {
        for (UUID playerId : diminishing.trackedPlayerIds()) {
            save(playerId);
        }
    }

    private DailyExpDiminishing.Settings currentSettings() {
        DailyExpDiminishing.Settings current = settings.get();
        return current == null ? DailyExpDiminishing.Settings.DISABLED : current;
    }
}
