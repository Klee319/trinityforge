package com.trinityforge.progression;

import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.config.domains.ExpGrant;
import com.trinityforge.config.domains.ItemGrant;
import com.trinityforge.items.ItemStackDrops;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.skilltree.runtime.PerkAttributeApplier;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.text.MiniText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * コレクション図鑑 (M7): 発見済みエントリの記録と、登録数しきい値による報酬ティアの段階解放。
 * 進捗の真実はプレイヤーPDC ({@link PlayerData#collectionEntries()} /
 * {@link PlayerData#claimedCollectionTiers()})。報酬は 称号表示 + 任意コンソールコマンドで、
 * 縦強化にしない運用は {@code progression/collection.yml} 側の責務 (DUNGEON_SPEC §5)。
 *
 * <p>エントリID規約: {@code item:<catalogId>}(カタログアイテム入手) / {@code mob:<ENTITY_TYPE>}
 * (プレイヤー討伐)。IDの発行は呼び出し側({@code CollectionListener})が行い、本サービスは
 * 重複排除・永続化・ティア評価のみを担う。
 */
public final class CollectionService {

    // public: ランキング用の集計(RankingStatsService)がアイテム/モブを数え分けるのに同じ接頭辞を使う。
    // 二重定義すると片方だけ変えたときに集計が黙ってゼロになるので、定義はここ1箇所だけに置く。
    public static final String ENTRY_PREFIX_ITEM = "item:";
    public static final String ENTRY_PREFIX_MOB = "mob:";

    private final CollectionConfig config;
    private final Logger log;
    private final CrossPluginItemResolver itemResolver;
    private final NativeExperienceDispatcher experienceDispatcher;
    private final PerkAttributeApplier perkAttributeApplier;
    private final CollectionEntryNames entryNames;
    /** null 可(fail-soft)。配線順の都合で setter 注入。詳細は {@link #setParticleSeedDelivery}。 */
    private volatile ParticleSeedDelivery particleSeedDelivery;

    /**
     * パーティクルシード報酬の実体配布を後付けで注入する(2026-08-21)。
     *
     * <p>コンストラクタ引数にしていないのは配線順の都合 ── {@code CollectionService} は
     * {@code SpecialRewardsConfig} 由来のサービス群より先に組み上がる
     * ({@code AchievementService#setLevelSources} と同じ理由)。未注入なら配布をスキップするが、
     * その場合<b>シード報酬は「IDが1つ増えるだけで何も起きない」着手前の状態に戻る</b>ので、
     * 本番配線を外さないこと。
     */
    public void setParticleSeedDelivery(ParticleSeedDelivery particleSeedDelivery) {
        this.particleSeedDelivery = particleSeedDelivery;
    }

    public CollectionService(CollectionConfig config, Logger log) {
        this(config, log, null, null);
    }

    /**
     * @param itemResolver         optional (may be {@code null}, e.g. existing tests that predate this
     *                              parameter): resolves {@code rewards.items[].id} to a built
     *                              {@link ItemStack}. {@code null} skips item rewards (fail-soft, matches
     *                              the existing special/commands failure-isolation policy).
     * @param experienceDispatcher optional (may be {@code null}): grants {@code rewards.job-exp[]}.
     *                              {@code null} skips job-exp rewards.
     */
    public CollectionService(CollectionConfig config, Logger log, CrossPluginItemResolver itemResolver,
                             NativeExperienceDispatcher experienceDispatcher) {
        this(config, log, itemResolver, experienceDispatcher, null);
    }

    /**
     * @param perkAttributeApplier optional (may be {@code null}, e.g. existing tests/call sites that
     *                              predate this parameter): re-applies vanilla-Attribute perk buffs
     *                              immediately after a tier unlock whose {@code rewards.permanent-buffs}
     *                              contains an ATTRIBUTE-channel key ({@link com.trinityforge.stats.StatVocabulary})
     *                              — see {@link AchievementService}'s matching parameter for the full
     *                              rationale (same gap, same fix, mirrored here for collection tiers).
     *                              {@code null} skips the re-apply (fail-soft).
     */
    public CollectionService(CollectionConfig config, Logger log, CrossPluginItemResolver itemResolver,
                             NativeExperienceDispatcher experienceDispatcher,
                             PerkAttributeApplier perkAttributeApplier) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        this.itemResolver = itemResolver;
        this.experienceDispatcher = experienceDispatcher;
        this.perkAttributeApplier = perkAttributeApplier;
        this.entryNames = new CollectionEntryNames(itemResolver);
    }

    /**
     * 図鑑エントリの表示名 (2026-07-27)。{@code display-names.*} の明示上書きが最優先で、
     * 無ければアイテムの display-name / モブの翻訳キーへ落ちる。どちらも解決できないときだけ
     * 生ID({@link #displayOf(String)} と同じ文字列)になる。
     */
    public Component displayComponent(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return Component.empty();
        }
        String override = null;
        if (entryId.startsWith(ENTRY_PREFIX_ITEM)) {
            override = config.itemDisplayNames().get(entryId.substring(ENTRY_PREFIX_ITEM.length()));
        } else if (entryId.startsWith(ENTRY_PREFIX_MOB)) {
            override = config.mobDisplayNames().get(entryId.substring(ENTRY_PREFIX_MOB.length()));
        }
        if (override != null && !override.isBlank()) {
            return Component.text(override);
        }
        return entryNames.display(entryId);
    }

    public static String itemEntryId(String catalogId) {
        return ENTRY_PREFIX_ITEM + catalogId;
    }

    public static String mobEntryId(String entityTypeName) {
        return ENTRY_PREFIX_MOB + entityTypeName;
    }

    /** Returns collection progress for an achievement trigger: {@code [owned, total]}. */
    public int[] progress(Player player, String scope, String target) {
        return progress(player, scope, target == null || target.isBlank() ? List.of() : List.of(target));
    }

    /**
     * 複数ターゲット版 (2026-07-27): 候補集合は列挙した各ターゲットの<b>和</b>。
     * {@code scope=item/mob} なら「列挙したアイテム/モブのうち何種類を登録済みか」、
     * {@code scope=category} なら「列挙したカテゴリの全エントリのうち何種類か」になる。
     * これにより「アイテムA・B・Cが全部そろったら達成」を1つのアチーブメントで書ける。
     */
    public int[] progress(Player player, String scope, List<String> targets) {
        String normalizedScope = scope == null ? "all" : scope;
        List<String> normalizedTargets = targets == null ? List.of() : targets;
        Set<String> candidates = new LinkedHashSet<>();
        if (normalizedScope.equals("item")) {
            normalizedTargets.forEach(target -> addItemCandidate(candidates, target));
        } else if (normalizedScope.equals("mob")) {
            normalizedTargets.forEach(target -> candidates.add(mobEntryId(target)));
        } else if (normalizedScope.equals("category")) {
            for (CollectionConfig.Category category : config.itemCategories()) {
                if (normalizedTargets.contains(category.id())) {
                    category.entries().forEach(id -> addItemCandidate(candidates, id));
                }
            }
            for (CollectionConfig.Category category : config.mobCategories()) {
                if (normalizedTargets.contains(category.id())) {
                    category.entries().forEach(id -> candidates.add(mobEntryId(id)));
                }
            }
        } else {
            for (CollectionConfig.Category category : config.itemCategories()) category.entries().forEach(id -> addItemCandidate(candidates, id));
            for (CollectionConfig.Category category : config.mobCategories()) category.entries().forEach(id -> candidates.add(mobEntryId(id)));
        }
        Set<String> owned = new LinkedHashSet<>();
        for (String raw : PlayerData.of(player).collectionEntries()) {
            CollectionRecord record = CollectionRecord.parse(raw);
            if (record != null) owned.add(record.id());
        }
        int count = (int) candidates.stream().filter(owned::contains).count();
        return new int[]{count, candidates.size()};
    }

    /**
     * 図鑑の母数(候補集合)へ item エントリを足す。{@code draft: true}(準備中)のカタログIDは除外する
     * ── 敵対的レビュー指摘4(2026-08-02)。
     *
     * <p>{@code collection.yml} の {@code categories.items} は draft ID を含んだまま(消してはいけない
     * ── editor 内での参照はユーザー意図どおりの正常状態)なので、母数計算のこちら側で落とす必要がある。
     * draft は {@code ItemCatalogConfig#load} が配布経路そのものから外しているため、対象アイテムは
     * <b>プレイヤーが理論上も入手不可能</b>。分母に残したままだと {@code scope: all}/{@code category}
     * の図鑑進捗が永久に100%へ到達しない(2026-08-02時点の出荷 collection.yml で abyss_* 13件 +
     * binder_* 17件 = 30件が該当)。
     *
     * <p>{@link #itemResolver} が未注入(2引数コンストラクタを使う既存テスト等)の場合は draft 判定が
     * できないため、従来どおり無条件で候補に含める(fail-open、既存呼び出し側の挙動を変えない)。
     */
    private void addItemCandidate(Set<String> candidates, String itemId) {
        if (itemResolver != null && itemResolver.isDraft(itemId)) {
            return;
        }
        candidates.add(itemEntryId(itemId));
    }

    /**
     * 未発見のエントリを図鑑へ登録する(品質pt無し=0、mob討伐等quality概念のないエントリ用)。
     *
     * @param entryIds 記録するエントリID群(呼び出し側で規約に従い発行済み)
     * @return 新規登録されたエントリ数
     */
    public int record(Player player, Collection<String> entryIds) {
        if (entryIds.isEmpty()) {
            return 0;
        }
        Map<String, Integer> withQuality = new LinkedHashMap<>();
        for (String id : entryIds) {
            withQuality.put(id, 0);
        }
        return record(player, withQuality);
    }

    /**
     * 未発見のエントリを図鑑へ登録し、既知エントリでも品質ptが既録より高ければ更新する
     * (2026-07-23-stat-gate-overhaul §6.3: PDCエントリ {@code id|epochMillis|maxQualityPt})。
     * 新規登録があった場合はプレイヤーへ通知し、到達した未解放ティアを解放する。
     *
     * @param entryIdsWithQuality エントリID -&gt; そのアイテムの品質pt(0-100、mob討伐等は0)
     * @return 新規登録されたエントリ数(品質pt更新のみのエントリは含まない)
     */
    public int record(Player player, Map<String, Integer> entryIdsWithQuality) {
        return record(player, entryIdsWithQuality, true);
    }

    /**
     * @param announce {@code false} = <b>遡り登録</b>(バグ修正やconfig追加で「既に持っていた物」が
     *                 後から一斉に記録可能になる場合)。1件ごとの「図鑑に登録」チャットと、
     *                 到達した報酬ティアの<b>サーバー全体ブロードキャスト</b>を抑止する。
     *                 報酬そのもの(称号/アイテム/EXP/コマンド)は通常どおり付与し、ティア解放は
     *                 プレイヤー本人にだけ知らせる — 付与されたのに何も表示されないと、
     *                 称号が増えた理由が分からなくなるため。
     *                 <p>2026-07-31 (K-11): 素のバニラ品が1件も記録されていなかった不具合を直した
     *                 結果、既存プレイヤーの参加時に最大16件が一括登録され、t3(60)/t4(120)/t5(200)
     *                 を跨いだ人数分の全体告知が連続発火する。これが事故に見えるため入れた口。
     */
    public int record(Player player, Map<String, Integer> entryIdsWithQuality, boolean announce) {
        if (!config.enabled() || entryIdsWithQuality.isEmpty()) {
            return 0;
        }
        PlayerData data = PlayerData.of(player);
        Map<String, CollectionRecord> known = new LinkedHashMap<>();
        for (String raw : data.collectionEntries()) {
            CollectionRecord parsed = CollectionRecord.parse(raw);
            if (parsed != null) {
                known.put(parsed.id(), parsed);
            }
        }
        long now = System.currentTimeMillis();
        List<String> newlyAdded = new ArrayList<>();
        boolean changed = false;
        for (Map.Entry<String, Integer> entry : entryIdsWithQuality.entrySet()) {
            String id = entry.getKey();
            if (id == null || id.isBlank()) {
                continue;
            }
            int quality = entry.getValue() == null ? 0 : entry.getValue();
            CollectionRecord existing = known.get(id);
            if (existing == null) {
                known.put(id, new CollectionRecord(id, now, quality));
                newlyAdded.add(id);
                changed = true;
            } else if (quality > existing.maxQualityPt()) {
                known.put(id, existing.merge(now, quality));
                changed = true;
            }
        }
        if (!changed) {
            return 0;
        }
        data.setCollectionEntries(known.values().stream().map(CollectionRecord::encode).toList());
        if (announce) {
            for (String id : newlyAdded) {
                player.sendMessage(Component.text("図鑑に登録: ", NamedTextColor.AQUA)
                        .append(displayComponent(id).colorIfAbsent(NamedTextColor.WHITE)));
            }
        }
        grantPendingTiers(player, data, known.size(), announce);
        return newlyAdded.size();
    }

    /**
     * 到達済みで未解放の報酬ティアを解放する。config編集で後からティアが追加された場合の
     * 追い付き用に {@code /tf collection} 表示時にも呼ばれる(冪等)。
     */
    public void grantPendingTiers(Player player) {
        if (!config.enabled()) {
            return;
        }
        PlayerData data = PlayerData.of(player);
        grantPendingTiers(player, data, data.collectionEntries().size(), true);
    }

    /**
     * 修正E(冪等性/at-most-once): claimed への追加+永続化を各ティアの報酬付与の"前"に行う
     * (AchievementService#grant の markAchieved-first と同じ順序)。旧実装はループ全体の報酬付与が
     * 終わってから最後に一括永続化していたため、複数ティアが同時到達した状態で報酬付与中に例外が
     * 起きると、既に付与済みの先行ティアの claimed 書き込みも失われ、次回再評価で重複付与され得た。
     * クラッシュ窓で「一度きり報酬(称号/コマンド等)が失われる」設計方針自体はAchievementServiceと
     * 同様に踏襲する(permanent-buffsは達成/解放フラグからの都度再計算なので影響を受けない)。
     */
    private void grantPendingTiers(Player player, PlayerData data, int entryCount, boolean broadcastAllowed) {
        List<String> claimed = new ArrayList<>(data.claimedCollectionTiers());
        boolean anyChanged = false;
        for (CollectionConfig.RewardTier tier : config.tiers()) {
            if (entryCount < tier.threshold() || claimed.contains(tier.id())) {
                continue;
            }
            claimed.add(tier.id());
            data.setClaimedCollectionTiers(claimed);
            anyChanged = true;
            announce(player, tier, broadcastAllowed);
            runCommands(player, tier);
            for (String specialId : tier.special()) {
                data.grantSpecialReward(specialId);
                if (particleSeedDelivery != null) {
                    particleSeedDelivery.deliver(player, specialId);
                }
            }
            grantItems(player, tier.items());
            if (tier.vanillaExp() > 0) {
                player.giveExp(tier.vanillaExp());
            }
            grantJobExp(player, tier.jobExp());
        }
        // ATTRIBUTE系永続バフ(max_health/move_speed等)は次回join/防具変更まで反映されないため、
        // ティア解放成功後に即座に再適用する(AchievementServiceと同じ理由・同じ対策)。
        if (anyChanged && perkAttributeApplier != null) {
            perkAttributeApplier.apply(player);
        }
    }

    /** プレイヤーの図鑑エントリを {@link CollectionRecord} として返す(GUI表示用)。壊れた行は無視。 */
    public List<CollectionRecord> records(Player player) {
        List<CollectionRecord> out = new ArrayList<>();
        for (String raw : PlayerData.of(player).collectionEntries()) {
            CollectionRecord parsed = CollectionRecord.parse(raw);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return out;
    }

    /**
     * @param broadcastAllowed false のときは {@code tier.broadcast()} が true でも本人通知に落とす
     *                         (遡り登録。{@link #record(Player, Map, boolean)} の javadoc 参照)
     */
    private void announce(Player player, CollectionConfig.RewardTier tier, boolean broadcastAllowed) {
        // title は collection.yml 由来で MiniMessage 記法を持つ(出荷値に <aqua>記録者</aqua> 等)。
        // Component.text() に渡すとタグがそのまま見えるので必ず MiniText で描画する
        // (AchievementService#grantRewards が同じ形。表示名系の描画は全部ここに揃える)。
        Component message = Component.text("コレクション報酬解放: ", NamedTextColor.GOLD)
                .append(MiniText.render(tier.title() != null ? tier.title() : tier.id(),
                        NamedTextColor.YELLOW))
                .append(Component.text(" (図鑑 " + tier.threshold() + " 種到達)", NamedTextColor.GRAY));
        if (tier.broadcast() && broadcastAllowed) {
            Bukkit.getServer().sendMessage(Component.text(player.getName() + " が", NamedTextColor.GOLD)
                    .append(message));
        } else {
            player.sendMessage(message);
        }
    }

    private void runCommands(Player player, CollectionConfig.RewardTier tier) {
        for (String command : tier.commands()) {
            String resolved = command.replace("%player%", player.getName());
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
            } catch (RuntimeException ex) {
                // 1コマンドの失敗で残りの報酬付与やゲームループを壊さない(claimedは維持=再実行しない。
                // 失敗コマンドの再付与はオペレーターが手動対応する前提でログに残す)。
                log.log(Level.WARNING, "[collection] reward command failed (tier=" + tier.id()
                        + "): " + resolved, ex);
            }
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
                log.warning("[collection] reward item id '" + grant.id() + "' could not be resolved; skipped");
                continue;
            }
            ItemStack stack = built.get();
            stack.setAmount(grant.amount());
            // 2026-09-04 W-312: grant.amount() が99を超える設定でも、アイテムエンティティの
            // コーデック上限(99)以下へ分割してから付与/床落ちさせる(ItemStackDrops 参照)。
            ItemStackDrops.giveOrDropSplit(player, stack);
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

    /** 表示名: item:/mob: プレフィクスを日本語ラベルに置き換える。 */
    public static String displayOf(String entryId) {
        if (entryId.startsWith(ENTRY_PREFIX_ITEM)) {
            return entryId.substring(ENTRY_PREFIX_ITEM.length());
        }
        if (entryId.startsWith(ENTRY_PREFIX_MOB)) {
            return entryId.substring(ENTRY_PREFIX_MOB.length()) + " 討伐";
        }
        return entryId;
    }
}
