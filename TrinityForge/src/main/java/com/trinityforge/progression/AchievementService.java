package com.trinityforge.progression;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.config.domains.ExpGrant;
import com.trinityforge.config.domains.ItemGrant;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.skilltree.runtime.PerkAttributeApplier;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.text.MiniText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
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
        List<AchievementsConfig.Achievement> advancementTargets = config.advancementAchievements();
        if (targets.isEmpty() && collectionTargets.isEmpty() && advancementTargets.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            // 1周の中で連鎖的に前提が解けるよう、達成済み集合はローカルで持ち回す
            // (「親を達成した同じ周で子も達成できる」= 直感どおりに動かすため)。
            List<String> done = new ArrayList<>(PlayerData.of(player).achievedIds());
            boolean progressed = true;
            while (progressed) {
                progressed = false;
                for (AchievementsConfig.Achievement achievement : targets) {
                    if (!done.contains(achievement.id()) && gateOpen(achievement, done)
                            && statisticReached(player, achievement) && grant(player, achievement)) {
                        done.add(achievement.id());
                        progressed = true;
                    }
                }
                for (AchievementsConfig.Achievement achievement : collectionTargets) {
                    if (!done.contains(achievement.id()) && gateOpen(achievement, done)
                            && collectionReached(player, achievement) && grant(player, achievement)) {
                        done.add(achievement.id());
                        progressed = true;
                    }
                }
                // advancement 型の取りこぼし回収 (2026-07-29): 前提未達成の時点でバニラ進捗を
                // 完了していると PlayerAdvancementDoneEvent は二度と飛ばないため、イベントだけに
                // 頼ると「前提を後から満たしても永久に達成できない」状態になる。
                // 進捗の完了状態はバニラ側に永続化されているので、ここで読み直して追いつく。
                for (AchievementsConfig.Achievement achievement : advancementTargets) {
                    if (!done.contains(achievement.id()) && gateOpen(achievement, done)
                            && advancementDone(player, achievement.trigger().advancement())
                            && grant(player, achievement)) {
                        done.add(achievement.id());
                        progressed = true;
                    }
                }
            }
        }
    }

    /**
     * 前提アチーブメントを満たしているか。<b>達成そのものを縛る</b>(2026-07-29 ユーザー確定)ので、
     * ここが false の間はトリガー条件を満たしていても達成にしない(報酬も出ない)。
     */
    private static boolean gateOpen(AchievementsConfig.Achievement achievement, List<String> achievedIds) {
        return AchievementsConfig.prerequisitesMet(achievement, achievedIds);
    }

    /** バニラ進捗が完了済みか。キーが解決できない(未知/データパック未導入)場合は false。 */
    private boolean advancementDone(Player player, String advancementKey) {
        if (advancementKey == null || advancementKey.isBlank()) {
            return false;
        }
        NamespacedKey key = NamespacedKey.fromString(advancementKey);
        if (key == null) {
            return false;
        }
        try {
            Advancement advancement = Bukkit.getAdvancement(key);
            // 2026-07-29: getAdvancement 自体が投げる実装(MockBukkit)があるので try の内側に置く。
            // 外に出すとテストが「失敗」ではなく「中断」になり、静かに素通りする。
            return advancement != null && player.getAdvancementProgress(advancement).isDone();
        } catch (RuntimeException ex) {
            // 進捗APIが未実装/未登録キーの環境では静かに「未達成」扱いにする(fail-soft)。
            return false;
        }
    }

    private boolean collectionReached(Player player, AchievementsConfig.Achievement achievement) {
        if (collectionService == null) return false;
        int[] progress = collectionService.progress(player, achievement.trigger().collectionScope(),
                achievement.trigger().collectionTargets());
        if (achievement.trigger().collectionPercent()) {
            return progress[1] > 0 && progress[0] * 100L >= achievement.trigger().threshold() * progress[1];
        }
        return progress[0] >= achievement.trigger().threshold();
    }

    private boolean statisticReached(Player player, AchievementsConfig.Achievement achievement) {
        try {
            long value = achievement.trigger().statisticQualifier()
                    .read(player, achievement.trigger().statistic());
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
                // 前提未達成ならここでは何もしない。前提が後から満たされた分は
                // pollStatistics() の advancement 回収パスが拾う(イベントは二度と飛ばないため)。
                if (gateOpen(achievement, done)) {
                    grant(player, achievement);
                }
            }
        }
    }

    /** @return 実際に達成扱いになったら true(既に達成済みなら false)。 */
    private boolean grant(Player player, AchievementsConfig.Achievement achievement) {
        PlayerData data = PlayerData.of(player);
        if (!data.markAchieved(achievement.id())) {
            return false; // 別経路で同tick中に既に達成済み扱いになっていた(冪等)。
        }
        Component message = Component.text("アチーブメント達成: ", NamedTextColor.GOLD)
                .append(MiniText.render(achievement.displayName(), NamedTextColor.YELLOW));
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
        return true;
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
