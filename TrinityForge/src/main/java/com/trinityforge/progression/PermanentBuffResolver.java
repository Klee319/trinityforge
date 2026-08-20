package com.trinityforge.progression;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.stats.StatKeys;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 達成/図鑑ティア解放に紐づく永続ステータスバフ({@code rewards.permanent-buffs})の都度再計算
 * リゾルバ({@link RoleBuffResolver} を手本とした使い捨て集計パターン)。
 *
 * <p>達成/解放そのものは既存の {@code AchievementService#claim}/{@code CollectionService#grantPendingTiers}
 * が {@link PlayerData#claimedAchievementIds()}/{@link PlayerData#claimedCollectionTiers()} へ記録する処理だけで
 * 完結しており、permanent-buffs 自体は付与時に何も書き込まない — 本リゾルバが呼び出しの都度
 * config(achievements.yml/collection.yml)を読み、その時点の解放集合と突き合わせて合算するため、
 * config reload や解放状態の変化がリロード不要で即座に反映される。
 *
 * <p>2026-08-04: 判定基準を「達成済み」から「解放済み(claimed)」へ変更した(手動解放方式導入)。
 * permanent-buffs は報酬なので、条件が成立しただけで {@code /achievement} からまだ解放していない
 * アチーブメントは寄与させない。図鑑ティアは元々「解放(claimed)」だけを見ていたので変更なし。
 */
public final class PermanentBuffResolver {

    private final AchievementsConfig achievements;
    private final CollectionConfig collection;

    public PermanentBuffResolver(AchievementsConfig achievements, CollectionConfig collection) {
        this.achievements = Objects.requireNonNull(achievements, "achievements");
        this.collection = Objects.requireNonNull(collection, "collection");
    }

    /**
     * プレイヤーの達成済みアチーブメント + 解放済み図鑑ティアの permanent-buffs を合算する
     * (canonicalキー→合算値)。{@code player} が {@code null} なら空マップ。
     */
    public Map<String, Double> buffsFor(Player player) {
        if (player == null) {
            return Map.of();
        }
        PlayerData data = PlayerData.of(player);
        Map<String, Double> merged = new LinkedHashMap<>();

        // #9 O(1)化: achievements()/tiers()のループ内でclaimedAchievementIds()/claimedCollectionTiers()を
        // 毎ヒットcontains()するとO(n)のList走査になるため、一度だけSet化してから判定する。
        Set<String> claimedAchievementIds = Set.copyOf(data.claimedAchievementIds());
        for (AchievementsConfig.Achievement achievement : achievements.achievements()) {
            if (claimedAchievementIds.contains(achievement.id())) {
                mergeCanonical(merged, achievement.rewards().permanentBuffs());
            }
        }

        Set<String> claimedTiers = Set.copyOf(data.claimedCollectionTiers());
        for (CollectionConfig.RewardTier tier : collection.tiers()) {
            if (claimedTiers.contains(tier.id())) {
                mergeCanonical(merged, tier.permanentBuffs());
            }
        }

        return Map.copyOf(merged);
    }

    private static void mergeCanonical(Map<String, Double> target, Map<String, Double> source) {
        source.forEach((key, value) -> target.merge(StatKeys.canonical(key), value, Double::sum));
    }
}
