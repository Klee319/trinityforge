package com.trinityforge.progression.event;

import com.trinityforge.progression.DailyExpDiminishing;

import java.util.UUID;

/**
 * 日次逓減（{@link DailyExpDiminishing}）で<b>EXP取得倍率の段が動いた</b>ときの受け口。
 * {@link SkillLevelUpSink} と同じ理由でドメイン層から Bukkit を切り離してある
 * （進行サービスの単体テストをサーバ無しで回すため）。
 *
 * <p><b>なぜ要るのか</b>: 逓減そのものは 2026-07-31 から動いていたが、
 * <b>プレイヤーへ知らせる経路が1つも無かった</b>。倍率を離散の段にした設計意図が
 * 「あと何EXPで落ちるのか・何をすれば戻るのかが数えられること」だったのに、
 * 表示側が丸ごと欠けていて「なんとなくEXPが渋い」としか分からない状態だった。
 */
@FunctionalInterface
public interface DailyExpRateChangeSink {

    /** 何もしない受け口（未配線時の既定）。 */
    DailyExpRateChangeSink NOOP = (playerId, skillId, applied) -> { };

    /**
     * 段が動いた付与を1回ぶん通知する。<b>倍率が変わったときだけ</b>呼ばれる
     * （毎回のEXP付与では呼ばれない ── 呼ぶとチャットが流れて逆に読まれなくなる）。
     *
     * @param skillId 大文字へ正規化済みのスキル ID
     * @param applied この付与に掛かった倍率と直前の倍率
     */
    void onDailyExpRateChanged(UUID playerId, String skillId, DailyExpDiminishing.Applied applied);
}
