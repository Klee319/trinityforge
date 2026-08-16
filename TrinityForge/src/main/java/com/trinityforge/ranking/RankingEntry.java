package com.trinityforge.ranking;

import java.util.UUID;

/**
 * ランキング上位 N 件の 1 行。外部プラグイン（UserRankBoard 等）が
 * {@code TrinityForge#rankingTop(String, int)} から受け取る公開型。
 *
 * <p><b>{@code name} は空文字になりうる。</b> 表示名は {@code player_ranking_stats} 行にしか無く、
 * スキルレベルのランキングは {@code player_skill_state} が主表なので、
 * 「一度もランキングミラーへ書き込まれていないプレイヤー」は名前が引けない。
 * その場合は空文字を返すので、呼び出し側で {@code Bukkit.getOfflinePlayer(uuid).getName()} などへ
 * フォールバックすること（例外にはしない ── 名前が無いだけで順位表から消すのは損失が大きい）。
 *
 * @param uuid  プレイヤー UUID
 * @param name  最後に観測した表示名。不明なら空文字（{@code null} にはならない）
 * @param value 集計値（図鑑登録数・討伐数・スキルレベルなど）
 */
public record RankingEntry(UUID uuid, String name, long value) {

    public RankingEntry {
        java.util.Objects.requireNonNull(uuid, "uuid");
        name = name == null ? "" : name;
    }
}
