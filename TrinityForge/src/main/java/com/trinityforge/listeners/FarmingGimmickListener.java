package com.trinityforge.listeners;

import com.trinityforge.config.domains.FarmingGimmickConfig;
import com.trinityforge.farming.CropMaturity;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/**
 * 農業(作物収穫)ギミック: 2026-08-01 に新設した drop-table 経路
 * ({@code stats/farming-gimmick.yml drop-tables.categories} からの追加ドロップ抽選)は
 * 2026-08-09 に機構ごと撤去した。このリスナーは現在、農業のブロック破壊イベントに対して
 * 「置く→壊す」対策ガード({@link #passesFarmingAntiLoopGuard})のフックだけを残しているのではなく、
 * 抽選ロジック自体が無くなったため実質的に no-op(将来 drop-table 以外の農業限定フックを
 * 足す場合の土台として残置)。
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
 */
public final class FarmingGimmickListener implements Listener {

    private static final String PROF_FARMING = "farming";

    /** {@code farming_progression.yml} 側の採取表名。ブロック破壊による農業EXPはこの表から引く。 */
    private static final String ACTION_BLOCK_DROPS = "block_drops";

    private final FarmingGimmickConfig gimmickConfig;
    private final NativeSkillCatalog catalog;
    private final PlacedBlockTracker placedBlockTracker;

    public FarmingGimmickListener(FarmingGimmickConfig gimmickConfig,
                                  NativeSkillCatalog catalog, PlacedBlockTracker placedBlockTracker) {
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.placedBlockTracker = Objects.requireNonNull(placedBlockTracker, "placedBlockTracker");
    }

    /**
     * 2026-08-09: drop-table 撤去に伴い抽選処理を削除。現在は対象判定(anti-loop guard含む)を
     * 通すだけの no-op。{@link EventPriority#MONITOR} + {@code ignoreCancelled=true} は
     * {@link DiggingGimmickListener} と同じ理由(HIGHEST でキャンセルする保護プラグインとの整合)で
     * 維持している。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (SpellBreakGuard.isSpellBreak(event.getBlock())) {
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
        // drop-tables 機構は撤去済み。これ以上の処理は無い(将来の農業限定フックの土台)。
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
}
