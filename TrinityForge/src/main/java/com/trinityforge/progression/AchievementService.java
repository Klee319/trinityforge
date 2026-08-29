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
 * ({@code PlayerAdvancementDoneEvent}) の達成判定と、{@code /achievement} GUI からの手動解放
 * (2026-08-04 導入)。オフライン中の統計進行は次回ログイン後のポーリングで追い付き判定される
 * (統計値そのものがバニラ側に永続化されているため、追いつき用の特別な処理は不要)。
 *
 * <p><b>達成(条件成立)と解放(受け取り)は別状態</b>(2026-08-04 ユーザー確定、ロードマップを
 * 見に行く習慣づけが目的): このクラスの {@code pollStatistics}/{@code onAdvancementDone} は
 * 条件成立を {@link PlayerData#markAchieved} へ記録するだけで<b>報酬を一切付与しない</b>。
 * 報酬付与の唯一の経路は {@link #claim}(GUI からの明示的な解放操作)。前提判定
 * ({@link AchievementsConfig#prerequisitesMet}) は意図的に達成集合({@code achievedIds}) を見る
 * ── 解放を忘れていても次のアチーブメントの条件は満たせる、という要件の実体がここ。
 */
public final class AchievementService {

    private final AchievementsConfig config;
    private final Logger log;
    private final CrossPluginItemResolver itemResolver;
    private final NativeExperienceDispatcher experienceDispatcher;
    private final PerkAttributeApplier perkAttributeApplier;
    private final CollectionService collectionService;
    private volatile ParticleSeedDelivery particleSeedDelivery;
    private volatile SkillLevelSource skillLevelSource;
    private volatile CombatLevelSource combatLevelSource;

    public AchievementService(AchievementsConfig config, Logger log) {
        this(config, log, null, null, null, null);
    }

    /**
     * {@code trigger.type: skill-level} の判定に使うレベル源を後付けで注入する(2026-08-16)。
     *
     * <p>コンストラクタ引数にしていないのは、{@code TrinityForge#onEnable} の配線順で
     * {@code AchievementService} の方が {@code SkillLevelSource}/{@code CombatLevelSource} より
     * 先に組み上がるため。未注入のままなら skill-level 型は<b>常に未達成</b>として扱う
     * (fail-soft。既存の itemResolver/experienceDispatcher と同じ方針)。
     */
    public void setLevelSources(SkillLevelSource skillLevelSource, CombatLevelSource combatLevelSource) {
        this.skillLevelSource = skillLevelSource;
        this.combatLevelSource = combatLevelSource;
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

    /**
     * パーティクルシード報酬の実体配布を後付けで注入する(2026-08-21)。
     *
     * <p>コンストラクタ引数にしていないのは配線順の都合({@code AchievementService#setLevelSources}
     * と同じ理由 ── {@code CollectionService} は {@code SpecialRewardsConfig} 由来のサービス群より
     * 先に組み上がる)。未注入なら配布をスキップする(fail-soft)が、その場合
     * <b>シード報酬は「IDが1つ増えるだけで何も起きない」着手前の状態に戻る</b>ので、
     * 本番配線を外さないこと。
     */
    public void setParticleSeedDelivery(ParticleSeedDelivery particleSeedDelivery) {
        this.particleSeedDelivery = particleSeedDelivery;
    }

    /** 全オンラインプレイヤーの statistic 型アチーブメントをポーリング判定する(1分毎想定)。 */
    public void pollStatistics() {
        List<AchievementsConfig.Achievement> targets = config.statisticAchievements();
        List<AchievementsConfig.Achievement> collectionTargets = config.staticAchievements();
        List<AchievementsConfig.Achievement> advancementTargets = config.advancementAchievements();
        List<AchievementsConfig.Achievement> counterTargets = config.counterAchievements();
        List<AchievementsConfig.Achievement> gearUseTargets = config.gearUseAchievements();
        List<AchievementsConfig.Achievement> skillLevelTargets = config.skillLevelAchievements();
        if (targets.isEmpty() && collectionTargets.isEmpty() && advancementTargets.isEmpty()
                && counterTargets.isEmpty() && gearUseTargets.isEmpty() && skillLevelTargets.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            // 手動解放方式への移行(2026-08-04): 導入前に達成済みだったプレイヤーは既に報酬を
            // 受け取っているため、claimed集合が空のままだと「未受領」に見えて二重取りになる。
            // このポーリングは60秒間隔で、参加直後ではなくオンライン中の周期処理として走るため
            // (資源サーバ構成のHuskSync同期が参加直後より確実に先に終わっている)、ここを
            // 移行の実施場所にする。1プレイヤーにつき1回だけ実施される(冪等、詳細は
            // migrateClaimIfNeeded を参照)。
            migrateClaimIfNeeded(player);
            // 1周の中で連鎖的に前提が解けるよう、達成済み集合はローカルで持ち回す
            // (「親を達成した同じ周で子も達成できる」= 直感どおりに動かすため)。
            List<String> done = new ArrayList<>(PlayerData.of(player).achievedIds());
            boolean progressed = true;
            while (progressed) {
                progressed = false;
                for (AchievementsConfig.Achievement achievement : targets) {
                    if (!done.contains(achievement.id()) && gateOpen(achievement, done)
                            && statisticReached(player, achievement) && markAchieved(player, achievement)) {
                        done.add(achievement.id());
                        progressed = true;
                    }
                }
                for (AchievementsConfig.Achievement achievement : collectionTargets) {
                    if (!done.contains(achievement.id()) && gateOpen(achievement, done)
                            && collectionReached(player, achievement) && markAchieved(player, achievement)) {
                        done.add(achievement.id());
                        progressed = true;
                    }
                }
                // 累計カウンタ型(2026-07-31)。イベント駆動にしていないのは、加算元が
                // ArsPaper フォーク(儀式のソース消費)側にあり、TF はそこへフックを持たないため。
                // statistic 型と同じ周期ポーリングで拾う。
                for (AchievementsConfig.Achievement achievement : counterTargets) {
                    if (!done.contains(achievement.id()) && gateOpen(achievement, done)
                            && counterReached(player, achievement) && markAchieved(player, achievement)) {
                        done.add(achievement.id());
                        progressed = true;
                    }
                }
                // 装備使用型(2026-08-16)。記録は GearUseListener がイベントで書き込むが、判定は
                // 他の型と同じくこの1周に混ぜる ── ここで判定しておけば「装備を使った瞬間に前提が
                // 解けて次のノードも同じ周で達成できる」という連鎖が他型と揃う。
                for (AchievementsConfig.Achievement achievement : gearUseTargets) {
                    if (!done.contains(achievement.id()) && gateOpen(achievement, done)
                            && gearUseReached(player, achievement) && markAchieved(player, achievement)) {
                        done.add(achievement.id());
                        progressed = true;
                    }
                }
                for (AchievementsConfig.Achievement achievement : skillLevelTargets) {
                    if (!done.contains(achievement.id()) && gateOpen(achievement, done)
                            && skillLevelReached(player, achievement) && markAchieved(player, achievement)) {
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
                            && markAchieved(player, achievement)) {
                        done.add(achievement.id());
                        progressed = true;
                    }
                }
            }
        }
    }

    /**
     * 手動解放方式導入(2026-08-04)前に達成済みだったプレイヤーの「解放済み集合」を1回だけ
     * 埋める。未移行(既定)のプレイヤーは {@code achievements_done} の内容をそのまま
     * {@code achievements_claimed} へコピーする ── 導入前の達成は旧仕様下で既に報酬を渡し終えて
     * いるので、コピーしても再付与にはならない({@link #claim} を経由しないため)。新規プレイヤーは
     * 両方空のままコピーされるだけで無害。{@link #claimedIds} からも同じ処理を呼ぶため、
     * このポーリングに一度も乗らない(60秒未満で退出する等の)プレイヤーでも、GUIを開いた瞬間に
     * 移行が完了する。
     */
    private void migrateClaimIfNeeded(Player player) {
        PlayerData data = PlayerData.of(player);
        if (data.achievementClaimMigrationDone()) {
            return;
        }
        for (String id : data.achievedIds()) {
            data.markAchievementClaimed(id);
        }
        data.markAchievementClaimMigrationDone();
    }

    /**
     * 解放済みアチーブメントID一覧(移行込み)。GUIの表示・クリック判定はこちらを経由すること。
     */
    public List<String> claimedIds(Player player) {
        migrateClaimIfNeeded(player);
        return PlayerData.of(player).claimedAchievementIds();
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

    /** 累計カウンタ(PDC)がしきい値に届いたか。カウンタID未指定は常に false(読み込み時に弾いている)。 */
    private boolean counterReached(Player player, AchievementsConfig.Achievement achievement) {
        String counter = achievement.trigger().counter();
        if (counter.isEmpty()) {
            return false;
        }
        return PlayerData.of(player).lifetimeCounter(counter) >= achievement.trigger().threshold();
    }

    /**
     * 列挙した装備のうち、しきい値以上を「実戦で使った」か(2026-08-16)。
     *
     * <p>記録側({@code GearUseListener})が {@code weapon:<id>} / {@code armor:<id>} で書くので、
     * ここでもスロット接頭辞を付けて突き合わせる。<b>接頭辞を落として比較してはいけない</b> ──
     * 同じ Material 名が武器にも防具にも現れうる(例: カスタム品が同じベース Material を共有する)ため、
     * 落とすと「防具を着ただけで武器のティアが解ける」経路ができる。
     */
    boolean gearUseReached(Player player, AchievementsConfig.Achievement achievement) {
        AchievementsConfig.Trigger trigger = achievement.trigger();
        if (trigger.gearSlot() == null || trigger.gearItems().isEmpty()) {
            return false;
        }
        java.util.Set<String> used = new java.util.HashSet<>(PlayerData.of(player).gearUsed());
        String prefix = trigger.gearSlot().tokenPrefix();
        long matched = 0;
        for (String item : trigger.gearItems()) {
            if (used.contains(prefix + item)) {
                matched++;
            }
        }
        return matched >= trigger.threshold();
    }

    /**
     * 列挙したスキルのうち、必要レベルに達しているものが {@code count} 種類以上あるか(2026-08-16)。
     *
     * <p>{@code COMBAT} は総合戦闘レベル。レベル源が未注入なら常に false(fail-soft) ── ここで
     * 例外にすると、レベル源より先に組み上がる配線順のせいで起動そのものが落ちる。
     */
    boolean skillLevelReached(Player player, AchievementsConfig.Achievement achievement) {
        AchievementsConfig.SkillLevelRequirement requirement = achievement.trigger().skillLevel();
        if (requirement.skills().isEmpty()) {
            return false;
        }
        SkillLevelSource levels = this.skillLevelSource;
        CombatLevelSource combat = this.combatLevelSource;
        Map<String, Integer> bySkill = levels == null ? Map.of() : levels.levelsOf(player.getUniqueId());
        int reached = 0;
        for (String skill : requirement.skills()) {
            int level;
            if (AchievementsConfig.SkillLevelRequirement.COMBAT.equals(skill)) {
                if (combat == null) {
                    continue;
                }
                level = combat.combatLevelOf(player.getUniqueId());
            } else {
                if (levels == null) {
                    continue;
                }
                level = bySkill.getOrDefault(skill, 0);
            }
            if (level >= requirement.level()) {
                reached++;
            }
        }
        return reached >= requirement.count();
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
                    markAchieved(player, achievement);
                }
            }
        }
    }

    /**
     * 条件成立(達成)を記録するだけの処理(2026-08-04 手動解放方式)。<b>報酬はここでは一切
     * 付与しない</b> ── 唯一の付与経路は {@link #claim}。達成した瞬間に一度だけ、控えめに
     * 「解放できます」を知らせる({@link #notifyClaimable}、内容は出さない・スパム防止で再通知しない)。
     *
     * @return 新たに達成扱いになったら true(既に達成済みなら false・冪等)。
     */
    private boolean markAchieved(Player player, AchievementsConfig.Achievement achievement) {
        PlayerData data = PlayerData.of(player);
        if (!data.markAchieved(achievement.id())) {
            return false; // 別経路で同tick中に既に達成済み扱いになっていた(冪等)。
        }
        notifyClaimable(player, data, achievement);
        return true;
    }

    /** 達成直後に1回だけ、ロードマップ(/achievement)を見に行くよう控えめに促す。 */
    private void notifyClaimable(Player player, PlayerData data, AchievementsConfig.Achievement achievement) {
        if (data.pendingClaimNotified(achievement.id())) {
            return; // スパム防止: 同じアチーブメントへは二度と通知しない。
        }
        data.markPendingClaimNotified(achievement.id());
        player.sendActionBar(Component.text("アチーブメントを解放できます（/achievement）", NamedTextColor.YELLOW));
    }

    /**
     * GUI からの明示的な解放操作(2026-08-04)。条件が成立済みで、まだ解放していない場合のみ
     * 報酬を付与して解放済みにする。2回目以降の呼び出しは {@link ClaimResult#ALREADY_CLAIMED}
     * を返すだけで何もしない(＝報酬は1回だけ)。
     */
    public ClaimResult claim(Player player, String achievementId) {
        AchievementsConfig.Achievement achievement = findById(achievementId);
        if (achievement == null) {
            return ClaimResult.UNKNOWN_ACHIEVEMENT;
        }
        migrateClaimIfNeeded(player);
        PlayerData data = PlayerData.of(player);
        if (!data.achievedIds().contains(achievementId)) {
            return ClaimResult.NOT_ACHIEVED;
        }
        if (!data.markAchievementClaimed(achievementId)) {
            return ClaimResult.ALREADY_CLAIMED;
        }
        grantRewards(player, achievement);
        return ClaimResult.CLAIMED;
    }

    private AchievementsConfig.Achievement findById(String achievementId) {
        for (AchievementsConfig.Achievement achievement : config.achievements()) {
            if (achievement.id().equals(achievementId)) {
                return achievement;
            }
        }
        return null;
    }

    /** {@link #claim} からのみ呼ぶ実際の報酬付与(アナウンスもここに含む: 未受領のものを全体
     *  通知しても意味が通らないため、達成時ではなく解放時に出す)。 */
    private void grantRewards(Player player, AchievementsConfig.Achievement achievement) {
        PlayerData data = PlayerData.of(player);
        Component message = Component.text("アチーブメント解放: ", NamedTextColor.GOLD)
                .append(MiniText.render(achievement.displayName(), NamedTextColor.YELLOW));
        if (achievement.broadcast()) {
            Bukkit.getServer().sendMessage(Component.text(player.getName() + " が", NamedTextColor.GOLD)
                    .append(message));
        } else {
            player.sendMessage(message);
        }
        for (String specialId : achievement.rewards().special()) {
            data.grantSpecialReward(specialId);
            if (particleSeedDelivery != null) {
                particleSeedDelivery.deliver(player, specialId);
            }
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
                // 1コマンド失敗で解放状態自体は維持する(CollectionServiceと同じ方針: 再実行しない、
                // オペレーターが手動対応する前提でログに残す)。
                log.log(Level.WARNING, "[achievements] reward command failed (achievement="
                        + achievement.id() + "): " + resolved, ex);
            }
        }
        // ATTRIBUTE系永続バフ(max_health/move_speed等)は次回join/防具変更まで反映されないため、
        // 解放成功後に即座に再適用する(ATTACK/DEFENSE/GENERALはaggregatorが毎回再計算するので不要)。
        if (perkAttributeApplier != null) {
            perkAttributeApplier.apply(player);
        }
    }

    /** {@link #claim} の結果。 */
    public enum ClaimResult { CLAIMED, ALREADY_CLAIMED, NOT_ACHIEVED, UNKNOWN_ACHIEVEMENT }

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
