package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FarmingGimmickConfig;
import com.trinityforge.farming.CropMaturity;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DropTableConfig;
import com.trinityforge.stats.DropTablePolicy;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 農業(作物収穫)ギミック: {@code farming} drop-table (2026-07-23 stat-gate-overhaul §4) を
 * {@code stats/farming-gimmick.yml drop-tables.categories} から評価する新設リスナー(2026-08-01)。
 *
 * <p><b>なぜ後から足したか</b>: 採掘/伐採/掘削には §4 の drop-table 経路があったのに、
 * <b>農業だけ yml に {@code drop-tables} セクションが存在せず、読む Java も無かった</b>。
 * 4職の中でここだけ「トリガー型の追加ドロップ」という機構が丸ごと欠けており、
 * 追加コンテンツ詳細プラン §8 が「最大の空き経路」と呼んでいたのがこれ。
 *
 * <p>対象判定は {@link NativeSkillExperienceListener#grantGathering} と同じ分類ロジックを
 * {@link DiggingGimmickListener} と同じ形で流用する: {@code farming_progression.yml} の
 * {@code block_drops} 表にブロック自身が載っており(値&gt;0)、かつ実際のドロップの中に同表へ
 * 載っている素材が1つ以上ある場合のみ「農業の対象」。
 *
 * <p><b>「置く→壊す」対策だけは掘削と同じにできない</b>(詳細は
 * {@link #passesFarmingAntiLoopGuard})。作物は必ずプレイヤーが植える = 種の設置が
 * {@code BlockPlaceEvent} を通るので {@link PlacedBlockTracker} に必ずマークが付き、
 * 掘削と同じ {@code isPlaced} ガードを素で掛けると
 * <b>自分の畑での収穫が1つ残らず除外されてこの機能はどこでも発動しない</b>。
 *
 * <p><b>既知の適用範囲</b>: 発火するのは「プレイヤーが実際に壊した1ブロック」だけ。
 * 範囲収穫({@code area-harvest})が周囲を刈る分は {@code breakNaturally} 経路で
 * {@link BlockBreakEvent} を発火しないので抽選も回らない(採掘の vein-mining と同じ扱い)。
 * 「範囲収穫のぶんも抽選したい」は仕様変更なので、やるなら
 * {@code ChainBreakExpGrant} と同じ形で明示的に呼び出すこと。
 */
public final class FarmingGimmickListener implements Listener {

    private static final String PROF_FARMING = "farming";

    /** {@code farming_progression.yml} 側の採取表名。ブロック破壊による農業EXPはこの表から引く。 */
    private static final String ACTION_BLOCK_DROPS = "block_drops";

    private final DedicatedEffectsConfig dedicatedEffects;
    private final FarmingGimmickConfig gimmickConfig;
    private final NativeSkillCatalog catalog;
    private final PlacedBlockTracker placedBlockTracker;
    private final CrossPluginItemResolver itemResolver;

    public FarmingGimmickListener(DedicatedEffectsConfig dedicatedEffects, FarmingGimmickConfig gimmickConfig,
                                  NativeSkillCatalog catalog, PlacedBlockTracker placedBlockTracker,
                                  CrossPluginItemResolver itemResolver) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.placedBlockTracker = Objects.requireNonNull(placedBlockTracker, "placedBlockTracker");
        this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");
    }

    /**
     * Drop-table roll only — {@link DiggingGimmickListener} と同じ理由で
     * {@link EventPriority#MONITOR} + {@code ignoreCancelled=true}
     * (HIGHEST でキャンセルする保護プラグインが景品も確実に抑止できること)。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (SpellBreakGuard.isSpellBreak(event.getBlock())) {
            // 魔法(Ars)破壊の合成イベントにはドロップテーブルを一切与えない。
            return;
        }
        Map<String, DropTableConfig.Category> categories = gimmickConfig.dropTables();
        if (categories.isEmpty()) {
            return;
        }
        Block block = event.getBlock();
        Material type = block.getType();
        if (!passesFarmingAntiLoopGuard(block, type)) {
            return;
        }
        Player player = event.getPlayer();
        SkillCatalogEntry entry = catalog.get(SkillId.FARMING);
        ItemStack tool = player.getInventory().getItemInMainHand();
        double exp = NativeSkillExperienceListener.gatheringExp(
                entry, ACTION_BLOCK_DROPS, type.name(), block.getDrops(tool, player));
        if (exp <= 0.0) {
            // no-op fallback: block_drops 表に載っていない = 農業の収穫対象ではない。
            return;
        }

        rollDropTables(player, block);
    }

    /**
     * 「置く→壊す」ループで景品を無限に引けないようにするガード。<b>農業では掘削/伐採と同じ
     * {@link PlacedBlockTracker#isPlaced} を素で使ってはいけない</b>。
     *
     * <p><b>なぜ分岐が要るか</b>: 作物は必ずプレイヤーが植える = 種の設置は {@code BlockPlaceEvent} を
     * 通るので {@link PlacedBlockTracker} に必ずマークが付く。掘削と同じく {@code isPlaced} で弾くと、
     * <b>自分の畑で収穫した作物が1つ残らず除外され、農業のドロップテーブルは実質どこでも発動しない</b>
     * (=作ったのに誰にも届かない)。
     *
     * <p>そこで判定を2系統に分ける:
     * <ul>
     *   <li><b>成熟ガード対象の作物</b>({@link com.trinityforge.farming.CropMaturity#isMaturityGated}
     *       — 小麦/ニンジン/ジャガイモ/ビートルート/ネザーウォート等): 設置マークは<b>見ない</b>。
     *       代わりに<b>完熟していること</b>を要求する。植えた直後の age 0 を壊しても引けないので、
     *       「種を植えて即壊す」ループは成立しない(成長時間が実質のレート制限になる)。</li>
     *   <li><b>それ以外</b>(サトウキビ/竹/サボテン/コンブ、および表に載る非作物ブロック):
     *       成熟の概念が無く、しかも<b>設置しても壊すと手元に戻る</b>ので置く→壊すが無コストで回る。
     *       こちらは掘削と同じ {@code isPlaced} ガードを掛ける。</li>
     * </ul>
     */
    private boolean passesFarmingAntiLoopGuard(Block block, Material type) {
        if (CropMaturity.isMaturityGated(type)) {
            return !CropMaturity.isImmatureCrop(block);
        }
        return !placedBlockTracker.isPlaced(block);
    }

    private void rollDropTables(Player player, Block block) {
        Set<String> heldPerks = DropTableGateSupport.heldPerksOf(player);
        Map<String, Set<String>> dropGatePerks = dedicatedEffects.dropGatePerks();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (DropTableConfig.Category category : gimmickConfig.dropTables().values()) {
            Optional<DropTableConfig.Entry> drawn = DropTablePolicy.evaluateCategory(
                    PROF_FARMING, category, heldPerks, dropGatePerks, rng.nextDouble(), rng.nextDouble());
            drawn.ifPresent(prizeEntry -> dropEntry(block, prizeEntry));
        }
    }

    private void dropEntry(Block block, DropTableConfig.Entry entry) {
        Optional<ItemStack> built = itemResolver.create(entry.item());
        if (built.isEmpty()) {
            // Fail-safe: an unresolvable item id must never throw out of a block-break handler.
            return;
        }
        ItemStack prize = built.get();
        prize.setAmount(Math.max(1, entry.amount()));
        block.getWorld().dropItemNaturally(block.getLocation(), prize);
    }
}
