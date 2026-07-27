package com.trinityforge.progression;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.config.domains.ExpGrant;
import com.trinityforge.config.domains.ItemGrant;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.skilltree.runtime.PerkAttributeApplier;
import com.trinityforge.stats.CrossPluginItemResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * アチーブメント (2026-07-23-stat-gate-overhaul §6.2): バニラ統計(周期ポーリング)/バニラ進捗
 * ({@code PlayerAdvancementDoneEvent}) の達成判定と報酬付与。達成済みはプレイヤーPDCに永続化し
 * (再付与しない)、オフライン中の統計進行は次回ログイン後のポーリングで追い付き判定される
 * (統計値そのものがバニラ側に永続化されているため、追いつき用の特別な処理は不要)。
 */
public final class AchievementService {

    private final AchievementsConfig config;
    private final Logger log;
    private final CrossPluginItemResolver itemResolver;
    private final NativeExperienceDispatcher experienceDispatcher;
    private final PerkAttributeApplier perkAttributeApplier;
    private final CollectionService collectionService;

    public AchievementService(AchievementsConfig config, Logger log) {
        this(config, log, null, null, null, null);
    }

    /**
     * @param itemResolver         optional (may be {@code null}, e.g. existing tests that predate this
     *                              parameter): resolves {@code rewards.items[].id} to a built
     *                              {@link ItemStack}. {@code null} skips item rewards (fail-soft, matches
     *                              the existing special/commands failure-isolation policy).
     * @param experienceDispatcher optional (may be {@code null}): grants {@code rewards.job-exp[]}.
     *                              {@code null} skips job-exp rewards.
     */
    public AchievementService(AchievementsConfig config, Logger log, CrossPluginItemResolver itemResolver,
                              NativeExperienceDispatcher experienceDispatcher) {
        this(config, log, itemResolver, experienceDispatcher, null, null);
    }

    /**
     * @param perkAttributeApplier optional (may be {@code null}, e.g. existing tests/call sites that
     *                              predate this parameter): re-applies vanilla-Attribute perk buffs
     *                              (move_speed/attack_speed/attack_reach/knockback_resistance/max_health)
     *                              immediately after a grant whose {@code rewards.permanent-buffs} contains
     *                              an ATTRIBUTE-channel key ({@link com.trinityforge.stats.StatVocabulary}).
     *                              Without this, such a key only takes effect on the player's next
     *                              join/armor-change re-apply, not immediately (unlike ATTACK/DEFENSE/GENERAL
     *                              channel keys, which {@link com.trinityforge.combat.PlayerStatAggregator}
     *                              re-derives live on every read). {@code null} skips the re-apply
     *                              (fail-soft, matches the existing optional-dependency pattern).
     */
    public AchievementService(AchievementsConfig config, Logger log, CrossPluginItemResolver itemResolver,
                              NativeExperienceDispatcher experienceDispatcher,
                              PerkAttributeApplier perkAttributeApplier) {
        this(config, log, itemResolver, experienceDispatcher, perkAttributeApplier, null);
    }
    public AchievementService(AchievementsConfig config, Logger log, CrossPluginItemResolver itemResolver,
                              NativeExperienceDispatcher experienceDispatcher,
                              PerkAttributeApplier perkAttributeApplier, CollectionService collectionService) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        this.itemResolver = itemResolver;
        this.experienceDispatcher = experienceDispatcher;
        this.perkAttributeApplier = perkAttributeApplier;
        this.collectionService = collectionService;
    }

    /** 全オンラインプレイヤーの statistic 型アチーブメントをポーリング判定する(1分毎想定)。 */
    public void pollStatistics() {
        List<AchievementsConfig.Achievement> targets = config.statisticAchievements();
        List<AchievementsConfig.Achievement> collectionTargets = config.staticAchievements();
        if (targets.isEmpty() && collectionTargets.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            List<String> done = PlayerData.of(player).achievedIds();
            for (AchievementsConfig.Achievement achievement : targets) {
                if (done.contains(achievement.id())) {
                    continue;
                }
                if (statisticReached(player, achievement)) {
                    grant(player, achievement);
                }
            }
            for (AchievementsConfig.Achievement achievement : collectionTargets) {
                if (!done.contains(achievement.id()) && collectionReached(player, achievement)) grant(player, achievement);
            }
        }
    }

    private boolean collectionReached(Player player, AchievementsConfig.Achievement achievement) {
        if (collectionService == null) return false;
        int[] progress = collectionService.progress(player, achievement.trigger().collectionScope(), achievement.trigger().collectionTarget());
        if (achievement.trigger().collectionPercent()) {
            return progress[1] > 0 && progress[0] * 100L >= achievement.trigger().threshold() * progress[1];
        }
        return progress[0] >= achievement.trigger().threshold();
    }

    private boolean statisticReached(Player player, AchievementsConfig.Achievement achievement) {
        try {
            long value = player.getStatistic(achievement.trigger().statistic());
            return value >= achievement.trigger().threshold();
        } catch (IllegalArgumentException ex) {
            // 対象Statisticがqualifier(Material/EntityType)必須の型だった場合の設定ミス。
            // 1プレイヤー分の失敗でポーリング全体を止めない。
            log.log(Level.WARNING, "[achievements] statistic '" + achievement.trigger().statistic()
                    + "' requires a qualifier and cannot be read without one (achievement="
                    + achievement.id() + ")", ex);
            return false;
        }
    }

    /** {@code PlayerAdvancementDoneEvent} から呼ぶ: {@code advancementKey} に一致するadvancement型を判定。 */
    public void onAdvancementDone(Player player, String advancementKey) {
        if (advancementKey == null || advancementKey.isBlank()) {
            return;
        }
        List<String> done = PlayerData.of(player).achievedIds();
        for (AchievementsConfig.Achievement achievement : config.advancementAchievements()) {
            if (done.contains(achievement.id())) {
                continue;
            }
            if (advancementKey.equals(achievement.trigger().advancement())) {
                grant(player, achievement);
            }
        }
    }

    private void grant(Player player, AchievementsConfig.Achievement achievement) {
        PlayerData data = PlayerData.of(player);
        if (!data.markAchieved(achievement.id())) {
            return; // 別経路で同tick中に既に達成済み扱いになっていた(冪等)。
        }
        Component message = Component.text("アチーブメント達成: ", NamedTextColor.GOLD)
                .append(Component.text(achievement.displayName(), NamedTextColor.YELLOW));
        if (achievement.broadcast()) {
            Bukkit.getServer().sendMessage(Component.text(player.getName() + " が", NamedTextColor.GOLD)
                    .append(message));
        } else {
            player.sendMessage(message);
        }
        for (String specialId : achievement.rewards().special()) {
            data.grantSpecialReward(specialId);
        }
        grantItems(player, achievement.rewards().items());
        if (achievement.rewards().vanillaExp() > 0) {
            player.giveExp(achievement.rewards().vanillaExp());
        }
        grantJobExp(player, achievement.rewards().jobExp());
        for (String command : achievement.rewards().commands()) {
            String resolved = command.replace("%player%", player.getName());
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
            } catch (RuntimeException ex) {
                // 1コマンド失敗で達成状態自体は維持する(CollectionServiceと同じ方針: 再実行しない、
                // オペレーターが手動対応する前提でログに残す)。
                log.log(Level.WARNING, "[achievements] reward command failed (achievement="
                        + achievement.id() + "): " + resolved, ex);
            }
        }
        // ATTRIBUTE系永続バフ(max_health/move_speed等)は次回join/防具変更まで反映されないため、
        // 達成成功後に即座に再適用する(ATTACK/DEFENSE/GENERALはaggregatorが毎回再計算するので不要)。
        if (perkAttributeApplier != null) {
            perkAttributeApplier.apply(player);
        }
    }

    /**
     * {@code rewards.items[]} を付与する。{@link #itemResolver} 未注入なら何もしない(fail-soft)。
     * インベントリが満杯ならその場にドロップする({@code GiveItemCommand} と同じパターン)。
     */
    private void grantItems(Player player, List<ItemGrant> items) {
        if (itemResolver == null || items.isEmpty()) {
            return;
        }
        for (ItemGrant grant : items) {
            var built = itemResolver.create(grant.id());
            if (built.isEmpty()) {
                log.warning("[achievements] reward item id '" + grant.id() + "' could not be resolved; skipped");
                continue;
            }
            ItemStack stack = built.get();
            stack.setAmount(grant.amount());
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    /** {@code rewards.job-exp[]} を付与する。{@link #experienceDispatcher} 未注入なら何もしない(fail-soft)。 */
    private void grantJobExp(Player player, List<ExpGrant> jobExp) {
        if (experienceDispatcher == null || jobExp.isEmpty()) {
            return;
        }
        for (ExpGrant grant : jobExp) {
            experienceDispatcher.grant(player.getUniqueId(), grant.skill(), grant.amount());
        }
    }
}
