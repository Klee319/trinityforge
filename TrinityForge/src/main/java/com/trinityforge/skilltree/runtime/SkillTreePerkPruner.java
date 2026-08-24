package com.trinityforge.skilltree.runtime;

import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.repository.LoadResult;
import com.trinityforge.progression.repository.ProgressionRepository;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.generator.PerkNaming;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * スキルツリーからノードが消えたときに、そのノードのperkを保持しているプレイヤーへ
 * 支払い済みのスキルポイント(SP)を返す掃除役。
 *
 * <p><b>直した問題 (2026-08-24 / W-213)</b>
 * 「スキルツリーを編集してノード構成が変わるとその分がリセットされるが、SPが消失している」。
 * 実データで裏を取った時点の被害は、伐採 {@code A-2-1..A-2-4} と 鍛冶(Ars) {@code A-3} の
 * 計30件で、14人が1〜4点ずつ死蔵していた。
 *
 * <p><b>機構</b> perk の所有は {@code player_perk_states} 行、支払いは
 * {@code player_point_balances.spent_points} という別勘定で持っている。ノードを消しても
 * perk 行はそのまま残るが、
 * <ul>
 *   <li>GUI・効果・バフはすべて「現在のツリーのノード」を起点に引くので、ノードが無い perk は
 *       <b>存在しないのと同じ</b>(プレイヤーからは「リセットされた」ように見える)</li>
 *   <li>それでも {@code spent_points} には残り続け、
 *       {@code ProgressionCurveReconciler} が毎回 {@code available = earned - spent} を
 *       書き戻すので、その分の SP が<b>永久に戻ってこない</b></li>
 *   <li>振り直し({@link NativePerkService#resetTree})も管理コマンドの
 *       レベル編集も「今あるノード」だけを見て返却するため、
 *       <b>どちらを実行しても救済されない</b></li>
 * </ul>
 *
 * <p><b>安全弁</b> 「ノードが無い」の判定は、スキルツリーconfigが正しく読めていることが前提。
 * そこが崩れた回に走らせると、一時的に読めなかったノードのperkを剥がしてしまう。よって:
 * <ul>
 *   <li>直前のスキルツリー読み込みが<b>完全成功</b>したときだけ走る
 *       ({@code SkillTreeConfig#lastLoadOk})。1ファイルでも警告が出た回は何もしない</li>
 *   <li>ツリーが読めていないスキルのperkには<b>触らない</b>
 *       (プレフィックスが一致するツリーが無いものは判定不能として放置)</li>
 *   <li>プレステージperk({@code *_perk_ng<段>})とルートperkはノードを持たないので対象外</li>
 * </ul>
 * 返却は「SPを戻して perk 行を消す」だけで、レベル・EXP・プレステージ段は一切触らない。
 */
public final class SkillTreePerkPruner {

    private static final Logger LOG = Logger.getLogger(SkillTreePerkPruner.class.getName());

    /** {@code <compact>_perk_} の後ろがこれに一致するものはプレステージperk(ノードを持たない)。 */
    private static final Pattern PRESTIGE_SUFFIX = Pattern.compile("ng[0-9]+");

    private final NativeProgressionService progression;
    private final ProgressionRepository repository;
    private final Supplier<Collection<SkillTree>> trees;
    private final BooleanSupplier treesLoadedCleanly;

    public SkillTreePerkPruner(NativeProgressionService progression,
                               Supplier<Collection<SkillTree>> trees,
                               BooleanSupplier treesLoadedCleanly) {
        this.progression = Objects.requireNonNull(progression, "progression");
        this.repository = progression.repository();
        this.trees = Objects.requireNonNull(trees, "trees");
        this.treesLoadedCleanly = Objects.requireNonNull(treesLoadedCleanly, "treesLoadedCleanly");
    }

    /** 掃除の結果。 */
    public record PruneResult(int players, int perks, long refundedPoints) {
        public static final PruneResult NOTHING = new PruneResult(0, 0, 0L);

        public boolean isEmpty() {
            return perks == 0;
        }
    }

    /**
     * 全プレイヤーを掃除する。起動時と {@code /trinityforge reload} の直後に呼ぶ。
     * スキルツリーの読み込みに問題があった回は何もしない(安全弁)。
     */
    public PruneResult pruneAll() {
        if (!treesLoadedCleanly.getAsBoolean()) {
            LOG.info("[progression] 孤児perkの掃除を見送りました:"
                    + " 直前のスキルツリー読み込みに問題があったため(壊れたymlのノードを"
                    + "「削除された」と誤判定して剥がすのを防ぐ)。");
            return PruneResult.NOTHING;
        }
        Collection<SkillTree> loaded = trees.get();
        if (loaded == null || loaded.isEmpty()) {
            return PruneResult.NOTHING;
        }
        int players = 0;
        int perks = 0;
        long refunded = 0L;
        for (UUID playerId : repository.listPlayerIds()) {
            try {
                PruneResult one = prunePlayer(playerId);
                if (!one.isEmpty()) {
                    players++;
                    perks += one.perks();
                    refunded += one.refundedPoints();
                }
            } catch (RuntimeException ex) {
                // 1人の失敗で全員の掃除を止めない(ProgressionCurveReconciler と同じ方針)。
                LOG.log(Level.WARNING, "[progression] 孤児perkの掃除に失敗: " + playerId, ex);
            }
        }
        if (perks > 0) {
            LOG.info("[progression] 消えたノードのperkを " + perks + " 件剥がし、SP " + refunded
                    + " 点を " + players + " 人へ返却しました");
        }
        return new PruneResult(players, perks, refunded);
    }

    /** 1人ぶんの掃除。安全弁の判定は {@link #pruneAll()} 側で済ませてある。 */
    public PruneResult prunePlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return progression.playerLocks().withLock(playerId, () -> pruneUnderLock(playerId));
    }

    private PruneResult pruneUnderLock(UUID playerId) {
        LoadResult<Set<String>> ownedLoad = repository.loadPerkIds(playerId);
        if (ownedLoad.isFailed()) {
            LOG.log(Level.WARNING, "[progression] 孤児perk判定のためのperk読み込みに失敗: "
                    + playerId, ownedLoad.error());
            return PruneResult.NOTHING;
        }
        Set<String> orphans = orphanPerkIds(ownedLoad.orElseThrow(), trees.get());
        if (orphans.isEmpty()) {
            return PruneResult.NOTHING;
        }

        LoadResult<Map<String, Long>> costsLoad = repository.loadPerkCosts(playerId);
        if (costsLoad.isFailed()) {
            LOG.log(Level.WARNING, "[progression] 孤児perkの支払額の読み込みに失敗: "
                    + playerId, costsLoad.error());
            return PruneResult.NOTHING;
        }
        Map<String, Long> storedCosts = costsLoad.orElseThrow();

        long refund = 0L;
        for (String perkId : orphans) {
            // ノードが既に無いので node.cost() で代替できない。購入時に記録した支払額だけが根拠。
            Long stored = storedCosts.get(perkId);
            refund += stored != null ? Math.max(0L, stored) : 0L;
        }

        PlayerProgression snapshot = progression.snapshot(playerId);
        // saveAdminProgressionEdit は「1スキルの行を書き換えつつ、perkを消して残高を書く」原子操作。
        // strip する perk_id は skillId で絞られない(DELETE ... WHERE player_id AND perk_id)ので、
        // 別スキルの孤児もまとめて1トランザクションで処理できる。残高を2回書かないためにこうする。
        // 渡す skillProgress は【現在値そのまま】= レベル/EXP/プレステージ段は書き換えない。
        Map.Entry<String, SkillProgress> anchor = anchorSkill(snapshot);
        if (anchor == null) {
            LOG.warning("[progression] 孤児perk " + orphans.size() + " 件を持つ " + playerId
                    + " にスキル行が1つも無いため掃除を見送りました");
            return PruneResult.NOTHING;
        }
        try {
            repository.saveAdminProgressionEdit(
                    playerId, anchor.getKey(), anchor.getValue(), null,
                    snapshot.availablePoints() + refund,
                    Math.max(0L, snapshot.spentPoints() - refund),
                    null, 0, List.copyOf(orphans));
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "[progression] 孤児perkの返却に失敗: " + playerId, ex);
            return PruneResult.NOTHING;
        }
        LOG.info("[progression] " + playerId + ": 消えたノードのperk " + orphans.size()
                + " 件を剥がし SP " + refund + " 点を返却 " + orphans);
        return new PruneResult(1, orphans.size(), refund);
    }

    /**
     * 残高を書くための「書き換えても何も変わらないスキル行」を1つ選ぶ。
     * 現在値をそのまま書き戻すので、どのスキルを選んでも意味は変わらない。
     */
    private static Map.Entry<String, SkillProgress> anchorSkill(PlayerProgression snapshot) {
        for (var entry : snapshot.skills().entrySet()) {
            if (entry.getValue() != null) return entry;
        }
        return null;
    }

    /**
     * 「今のツリーに対応ノードが無い」perk ID を返す(純粋関数・テスト対象)。
     *
     * <p>判定できないものは<b>返さない</b>。具体的には、どのツリーのプレフィックスにも一致しない
     * perk(=そのスキルのツリーが読めていない)、プレステージperk、ルートperkは対象外。
     */
    static Set<String> orphanPerkIds(Set<String> ownedPerkIds, Collection<SkillTree> trees) {
        if (ownedPerkIds == null || ownedPerkIds.isEmpty() || trees == null || trees.isEmpty()) {
            return Set.of();
        }
        // プレフィックス(<compact>_perk_) -> そのツリーで正当な perk ID 全部
        Map<String, Set<String>> namespaces = new LinkedHashMap<>();
        for (SkillTree tree : trees) {
            if (tree == null || tree.skill() == null) continue;
            String prefix = PerkNaming.compact(tree.skill()) + "_perk_";
            Set<String> valid = new HashSet<>();
            valid.add(PerkNaming.rootPerkId(tree.skill()));
            for (SkillNode node : tree.nodes().values()) {
                if (node != null && node.id() != null) {
                    valid.add(PerkNaming.perkId(tree.skill(), node.id()));
                }
            }
            // 同じスキルのツリーが2つ来ることは無い(configがskill idで一意化している)が、
            // 来た場合は「どちらかに在れば正当」= 剥がさない側へ倒す。
            namespaces.computeIfAbsent(prefix, k -> new HashSet<>()).addAll(valid);
        }

        Set<String> orphans = new LinkedHashSet<>();
        for (String perkId : ownedPerkIds) {
            if (perkId == null || perkId.isBlank()) continue;
            String prefix = longestPrefix(namespaces.keySet(), perkId);
            if (prefix == null) continue; // ツリー未ロード = 判定不能なので触らない
            String suffix = perkId.substring(prefix.length());
            if (PRESTIGE_SUFFIX.matcher(suffix).matches()) continue; // 恒久perk
            if (namespaces.get(prefix).contains(perkId)) continue;   // 現役ノード
            orphans.add(perkId);
        }
        return Set.copyOf(orphans);
    }

    /**
     * {@code perkId} が始まるプレフィックスのうち最長のものを返す。
     * 「あるスキルのcompact名が別のスキルのcompact名の接尾辞」(smithing と arssmithing)では
     * 前方一致なので取り違えないが、将来 {@code mining} と {@code mining2} のような
     * 前方一致するIDが増えても取り違えないよう最長一致で選ぶ。
     */
    private static String longestPrefix(Collection<String> prefixes, String perkId) {
        String best = null;
        for (String prefix : prefixes) {
            if (perkId.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best;
    }
}
