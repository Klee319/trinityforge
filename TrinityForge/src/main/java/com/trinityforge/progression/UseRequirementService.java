package com.trinityforge.progression;

import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.UseRequirementsConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;

/**
 * 装備使用ゲートの共有評価 ({@code progression/use-requirements.yml} の {@code enforce} +
 * {@link UseRequirementResolver} + {@link UseRequirementPolicy})。近接/弓/ツールの既存ゲートと
 * 同じ規則・同じ文言を、防具装備ゲートと ArsPaper フォークの触媒詠唱ゲートからも使えるように
 * 一箇所へまとめたもの。TrinityForge 本体が {@code useRequirementGate()} で公開する。
 */
public final class UseRequirementService {

    private final UseRequirementsConfig useRequirements;
    private final ItemStatsConfig itemStats;
    private final SkillLevelSource skillLevelSource;

    public UseRequirementService(UseRequirementsConfig useRequirements,
                                 ItemStatsConfig itemStats,
                                 SkillLevelSource skillLevelSource) {
        this.useRequirements = Objects.requireNonNull(useRequirements, "useRequirements");
        this.itemStats = Objects.requireNonNull(itemStats, "itemStats");
        this.skillLevelSource = Objects.requireNonNull(skillLevelSource, "skillLevelSource");
    }

    /**
     * {@code player} が {@code item} の使用要件を満たさない場合、その未達要件を返す。
     * enforce オフ・要件なし・要件達成のいずれでも empty(=使用可)。
     */
    public Optional<UseRequirementResolver.Resolved> denialFor(Player player, ItemStack item) {
        if (!useRequirements.enforce() || player == null) {
            return Optional.empty();
        }
        return UseRequirementResolver.resolve(item, itemStats)
                .filter(req -> !UseRequirementPolicy.meets(
                        req.skill(), req.level(), skillLevelSource.levelsOf(player.getUniqueId())));
    }

    /**
     * 使用不可のときアクションバーへ標準文言(近接/弓/ツールゲートと同一)を送って {@code true} を返す。
     */
    public boolean blockedWithMessage(Player player, ItemStack item) {
        return denialFor(player, item)
                .map(req -> {
                    player.sendActionBar(denialMessage(req));
                    return true;
                })
                .orElse(false);
    }

    /** 標準の拒否文言 (既存の近接/弓/ツールゲートと同一書式)。 */
    public static Component denialMessage(UseRequirementResolver.Resolved req) {
        return Component.text(
                "この装備を使うには " + req.skill() + " Lv" + req.level() + " が必要です",
                NamedTextColor.RED);
    }
}
