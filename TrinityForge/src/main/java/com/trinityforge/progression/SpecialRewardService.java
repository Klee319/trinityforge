package com.trinityforge.progression;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Set;

/**
 * 特殊報酬(称号/パーティクル/パーティクルシード)の保有・装備判定 (2026-07-23-stat-gate-overhaul §6.1)。
 *
 * <p>保有経路は2つ: (a) スキルツリーで {@code reward:<id>} 効果を置いたノードのperkを保有(動的ゲート、
 * {@link DedicatedEffectsConfig#isActive}) — {@code special-rewards.yml} で定義したIDそのものがそのまま
 * dedicated-effect の {@code reward:} 接頭辞のターゲットになる。(b) アチーブメント/図鑑報酬ティアから
 * 直接付与({@link PlayerData#grantSpecialReward})。どちらか一方でも満たせば保有扱い。
 */
public final class SpecialRewardService {

    private static final String REWARD_PREFIX = "reward:";

    private final SpecialRewardsConfig config;
    private final DedicatedEffectsConfig dedicatedEffects;

    public SpecialRewardService(SpecialRewardsConfig config, DedicatedEffectsConfig dedicatedEffects) {
        this.config = Objects.requireNonNull(config, "config");
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
    }

    /** {@code id} を(スキルツリー経由 or 直接付与のいずれかで)保有しているか。 */
    public boolean isUnlocked(Player player, String id) {
        if (player == null || id == null || id.isBlank()) {
            return false;
        }
        if (dedicatedEffects.isActive(player, REWARD_PREFIX + id)) {
            return true;
        }
        return PlayerData.of(player).unlockedSpecialRewards().contains(id);
    }

    /**
     * {@link #isUnlocked(Player, String)} の {@link Player} 不要版(テスト、または呼び出し側が既に
     * 両方の集合を読んでいる場合用)。
     */
    public boolean isUnlocked(Set<String> heldPerks, Set<String> directGrants, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        if (dedicatedEffects.isActiveByPerks(heldPerks, REWARD_PREFIX + id)) {
            return true;
        }
        return directGrants != null && directGrants.contains(id);
    }

    /** 称号を装備する。{@code id} が {@code null}/空なら解除。未定義または未保有IDは拒否({@code false})。 */
    public boolean equipTitle(Player player, String id) {
        if (id == null || id.isBlank()) {
            PlayerData.of(player).setEquippedTitle(null);
            return true;
        }
        if (!config.titles().containsKey(id) || !isUnlocked(player, id)) {
            return false;
        }
        PlayerData.of(player).setEquippedTitle(id);
        return true;
    }

    /** パーティクルを装備する。{@code id} が {@code null}/空なら解除。未定義または未保有IDは拒否({@code false})。 */
    public boolean equipParticle(Player player, String id) {
        if (id == null || id.isBlank()) {
            PlayerData.of(player).setEquippedParticle(null);
            return true;
        }
        if (!config.particles().containsKey(id) || !isUnlocked(player, id)) {
            return false;
        }
        PlayerData.of(player).setEquippedParticle(id);
        return true;
    }

    /** 現在装備中の称号の表示テキスト(MiniMessage文字列)。非装備/未定義なら空。 */
    public java.util.Optional<String> equippedTitleDisplay(Player player) {
        return PlayerData.of(player).equippedTitle()
                .map(id -> config.titles().get(id))
                .map(SpecialRewardsConfig.Title::display);
    }

    /** 現在装備中のパーティクル定義。非装備/未定義なら空。 */
    public java.util.Optional<SpecialRewardsConfig.ParticleEffect> equippedParticleEffect(Player player) {
        return PlayerData.of(player).equippedParticle()
                .map(id -> config.particles().get(id));
    }
}
