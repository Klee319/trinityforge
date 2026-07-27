package com.trinityforge.progression;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * {@code progression/special-rewards.yml} から削除された報酬IDを、プレイヤーのPDC保持分
 * (直接付与リスト/装備中の称号・パーティクル)からも取り除く(2026-07-28)。
 *
 * <p>削除経路は2つあり、どちらも掃除しないと古いIDが残り続ける:
 * <ol>
 *   <li>直接付与リスト({@link PlayerData#unlockedSpecialRewards()}) — {@link PlayerData#revokeSpecialReward}
 *       が除去自体と、それが装備中だった場合の装備解除を一括で行う(二重実装を避けるためそのまま使う)。</li>
 *   <li>装備欄だけが未定義IDを指しているケース — スキルツリーの {@code reward:<id>} perk 経由で
 *       解放・装備した場合、そのIDは直接付与リストに一度も入らない。config から削除されると
 *       {@code SpecialRewardService#isUnlocked} は false になるが、装備欄のPDC値自体は誰も消さないため
 *       このケースは {@link PlayerData#revokeSpecialReward} の対象外(呼んでも「付与リストに無いので
 *       false を返して何もしない」)。ここだけは直接 {@link PlayerData#setEquippedTitle}/
 *       {@link PlayerData#setEquippedParticle} を呼んで別途掃除する必要がある。</li>
 * </ol>
 *
 * <p>安全弁: {@link SpecialRewardsConfig#pruneOrphanedGrants()}(運用トグル)と
 * {@link SpecialRewardsConfig#lastLoadOk()}(直近ロードの成否)の**両方**が true のときだけ prune する。
 * 判定はこのクラス自身が行う — 呼び出し側(join/reload)に委ねると発火点を1つ増やすたびに安全弁の
 * 掛け忘れが起きうるため、「壊れたYAMLを『全部未定義』と誤判定して全員の報酬を消し飛ばす」事故だけは
 * 発火点によらず必ず塞がる位置に置いている。
 */
public final class SpecialRewardPruner {

    /** @param revokedGrants 直接付与リストから除去した報酬ID @param clearedEquipped 装備欄から解除した報酬ID */
    public record PruneResult(List<String> revokedGrants, List<String> clearedEquipped) {
        public PruneResult {
            revokedGrants = List.copyOf(revokedGrants);
            clearedEquipped = List.copyOf(clearedEquipped);
        }

        public boolean isEmpty() {
            return revokedGrants.isEmpty() && clearedEquipped.isEmpty();
        }
    }

    private final SpecialRewardsConfig config;

    public SpecialRewardPruner(SpecialRewardsConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /** {@code player} のPDCから、config にもうIDが存在しない報酬の保持分を取り除く。 */
    public PruneResult prune(Player player) {
        Objects.requireNonNull(player, "player");
        // 安全弁: 運用トグル(pruneOrphanedGrants)がfalse、または直近のload()が失敗(壊れたYAML/破損
        // エントリ)していた場合は、config の titles/particles/particleSeeds が信用できないため
        // 一切pruneしない(「全部未定義」誤判定で全員の報酬を消し飛ばす事故を防ぐ)。
        if (!config.pruneOrphanedGrants() || !config.lastLoadOk()) {
            return new PruneResult(List.of(), List.of());
        }
        PlayerData data = PlayerData.of(player);

        List<String> orphanedGrants = orphanedIds(data.unlockedSpecialRewards(), config::isKnown);
        List<String> revoked = new ArrayList<>();
        for (String id : orphanedGrants) {
            // revokeSpecialReward 自身が「装備中ならそれも解除する」まで面倒を見る(PlayerData 実装参照)。
            if (data.revokeSpecialReward(id)) {
                revoked.add(id);
            }
        }

        // 直接付与リストには入っていない(=スキルツリー reward:<id> perk 経由の)装備だけが取り残される
        // ケース。revokeSpecialReward はここを見ないため、ここだけは個別に掃除する。
        List<String> clearedEquipped = new ArrayList<>();
        data.equippedTitle().filter(id -> !config.isKnown(id)).ifPresent(id -> {
            data.setEquippedTitle(null);
            clearedEquipped.add(id);
        });
        data.equippedParticle().filter(id -> !config.isKnown(id)).ifPresent(id -> {
            data.setEquippedParticle(null);
            clearedEquipped.add(id);
        });

        return new PruneResult(revoked, clearedEquipped);
    }

    /**
     * Pure: {@code grantedIds} のうち {@code isKnown} が false を返すもの(=configから消えたID)を
     * 抽出する。{@link Player} 不要のためテストから直接叩ける
     * (このリポジトリの「pure helper をテスト可能にする」慣習、例: {@code CatalogCraftGateListener#isBlocked})。
     */
    static List<String> orphanedIds(List<String> grantedIds, Predicate<String> isKnown) {
        List<String> orphaned = new ArrayList<>();
        for (String id : grantedIds) {
            if (!isKnown.test(id)) {
                orphaned.add(id);
            }
        }
        return List.copyOf(orphaned);
    }
}
