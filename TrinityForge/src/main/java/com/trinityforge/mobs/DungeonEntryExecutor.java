package com.trinityforge.mobs;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 「転送(またはEliteMobsへの委譲呼び出し)が成功したときに限り鍵を消費する」という順序を
 * {@link DungeonEntryGui} の確定処理から抽出したもの(2026-07-27)。実際の
 * {@code Player#teleport}/{@code EliteMobsDungeonBridge#teleport} 呼び出しを {@link BooleanSupplier}
 * として差し替え可能にすることで、Bukkitのテレポートを実行せずに「先に消費してから転送、は絶対にしない」
 * という順序をユニットテストできるようにする。
 */
public final class DungeonEntryExecutor {

    private DungeonEntryExecutor() {
    }

    /**
     * @param attempt     実際の転送/委譲呼び出しを試み、成否を返す処理(失敗時は自分自身では何もしない
     *                    前提 — 呼び出し側が既に「失敗時メッセージ」等を済ませている)
     * @param onSuccess   {@code attempt} が {@code true} を返したときにだけ呼ばれる処理(鍵消費)
     * @return {@code attempt} の戻り値をそのまま返す
     */
    public static boolean executeIfSuccessful(BooleanSupplier attempt, Runnable onSuccess) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(onSuccess, "onSuccess");
        boolean success = attempt.getAsBoolean();
        if (success) {
            onSuccess.run();
        }
        return success;
    }
}
