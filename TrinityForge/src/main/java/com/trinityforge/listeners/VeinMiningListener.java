package com.trinityforge.listeners;

import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.MiningGimmickConfig;
import com.trinityforge.gathering.ChainBreakExpGrant;
import com.trinityforge.gathering.ChainBreakSupport;
import com.trinityforge.gathering.GatheringToolMatcher;
import com.trinityforge.mining.VeinMiningAlgorithm;
import com.trinityforge.mining.VeinMiningAlgorithm.BlockPos;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DropTableConfig;
import com.trinityforge.stats.DropTablePolicy;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ore-break-triggered dedicated-effect consumers (all scoped to
 * {@code stats/mining-gimmick.yml vein-mining.ore-blocks} so stone/dirt/anything outside the ore
 * list is never affected — no config, no plugin behaviour change):
 *
 * <ul>
 *   <li>{@code vein-mining} (SCALE, 2026-07-25 gather-rework-active-framework §1): chain-breaks connected
 *       same-type ore ({@link VeinMiningAlgorithm}), capped by the player's resolved tier
 *       ({@code stats/mining-gimmick.yml vein-mining.tiers}, floor lookup via
 *       {@code DedicatedEffectsConfig#valueMax}).
 *       <b>2026-08-24</b>: 手置きの鉱石でも連鎖するようになった(ユーザー要望)。
 *       「すでに1回採掘済み」なので報酬側は全部落とす —— 採取EXP/破壊時バニラEXPは
 *       {@code NativeSkillExperienceListener} の設置マークガードが、バニラEXPオーブは
 *       {@link #onBlockBreak} の {@code setExpToDrop(0)} が、ドロップテーブルは
 *       {@link #onBlockBreakDropTables} の除外がそれぞれ担う。</li>
 *   <li>{@code mining} drop-table (2026-07-23 stat-gate-overhaul §4): each configured category
 *       ({@code stats/mining-gimmick.yml drop-tables.categories}) independently rolls its own
 *       trigger-chance, then draws one weighted+gated prize ({@link DropTablePolicy}) — replaces the old
 *       hardcoded {@code gacha-ticket-1/2/3}/{@code ancient-debris-drop} dedicated-effect consumers.</li>
 * </ul>
 *
 * <p>Bundled into one listener (rather than 2) because both are gated on the exact same trigger
 * (an ore-block break) and share the same "is this an ore block" guard.
 */
public final class VeinMiningListener implements Listener {

    private static final String EFFECT_VEIN_MINING = "vein-mining";
    private static final String PROF_MINING = "mining";

    private final DedicatedEffectsConfig dedicatedEffects;
    private final MiningGimmickConfig gimmickConfig;
    private final CrossPluginItemResolver itemResolver;
    private final PlacedBlockTracker placedBlockTracker;
    private final FeedbackLayer feedback;

    /** 連鎖破壊分の採取EXP付与口(2026-07-28)。null 可 — 旧5引数コンストラクタ経由では EXP のみ入らない。 */
    private final ChainBreakExpGrant chainBreakExp;

    /** @deprecated 連鎖破壊分のEXPが入らない旧配線。8引数版ではなく6引数版を使うこと。 */
    @Deprecated
    public VeinMiningListener(DedicatedEffectsConfig dedicatedEffects, MiningGimmickConfig gimmickConfig,
                               CrossPluginItemResolver itemResolver, PlacedBlockTracker placedBlockTracker,
                               FeedbackLayer feedback) {
        this(dedicatedEffects, gimmickConfig, itemResolver, placedBlockTracker, feedback, null);
    }

    public VeinMiningListener(DedicatedEffectsConfig dedicatedEffects, MiningGimmickConfig gimmickConfig,
                               CrossPluginItemResolver itemResolver, PlacedBlockTracker placedBlockTracker,
                               FeedbackLayer feedback, ChainBreakExpGrant chainBreakExp) {
        this.chainBreakExp = chainBreakExp;
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
        this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");
        this.placedBlockTracker = Objects.requireNonNull(placedBlockTracker, "placedBlockTracker");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    /**
     * Vein-mining chain-break only (existing mechanic, priority unchanged — 2026-07-23 verifier指摘⑧:
     * the drop-table roll moved out to {@link #onBlockBreakDropTables} at {@link EventPriority#MONITOR}
     * so a protection plugin cancelling at {@code HIGHEST} can no longer suppress the break yet still
     * have a prize drop out of it).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (SpellBreakGuard.isSpellBreak(event.getBlock())) {
            // 魔法(Ars)破壊の合成イベントには一括破壊/ドロップテーブルを一切与えない。
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material type = block.getType();
        if (!gimmickConfig.oreBlocks().contains(type)) {
            // no-op fallback: everything below is scoped to the configured ore list only.
            return;
        }
        if (placedBlockTracker.isPlaced(block)) {
            // 2026-08-24 ユーザー要望「鉱石の一括破壊は手置きのものにも適用されるようにしてほしい。
            // ただすでに一回採掘済みなのでバニラEXPと職業EXPは反映されないようにする必要がある」。
            //
            // 【変更前】ここで return しており、手置きの鉱石を壊しても連鎖が一切起きなかった
            // (GTH-02: シルクタッチで回収 → 並べて設置 → 幸運で1個割ると全部が幸運付きで連鎖破壊
            //  される、という増殖経路を止めるための門だった)。
            //
            // 【変更後】連鎖は通す。要望の「EXPは入らない」側は<b>既に3経路とも成立している</b>:
            //   ・起点の採取EXPと破壊時バニラEXP →
            //     NativeSkillExperienceListener#blockedByPlaceBreakGuard が設置マークで弾く
            //   ・連鎖分の採取EXP → 同じガードを ChainBreakExpGrant 経由で1ブロックずつ通る
            //     (手置きの段だけが0になり、巻き込まれた自然生成の段は従来どおり入る)
            //   ・連鎖分のバニラEXPオーブ → ChainBreakSupport#breakOnce は setType(AIR) で壊すので
            //     そもそもオーブが出ない
            // 唯一残っていたのが<b>起点のバニラEXPオーブ</b>で、これはバニラが expToDrop に載せてくる。
            // ここで落とす。HIGH で読んでいるのは、設置マークを消すのが MONITOR の
            // NativeSkillExperienceListener だから(MONITOR で isPlaced を読むと登録順によっては
            // 消去後になる。GatheringExtraDropListener と同じ理由)。
            //
            // ⚠ ドロップテーブル抽選(onBlockBreakDropTables)は設置ブロックを除外したまま据え置く
            //   ── あれも「採掘の報酬」なので、EXP と同じ扱いにしておく。
            // ⚠ GTH-02 の増殖経路そのものは開く。シルクタッチ回収 → 再設置 → 幸運で割る、は
            //   バニラでも1個ずつなら可能な手順で、ここでは連鎖するぶん速くなる。
            //   幸運を手置きの段だけ無効化するなら別途指示が要る(要望の範囲外なので触っていない)。
            event.setExpToDrop(0);
        }
        if (!PlayerData.of(player).veinMiningEnabled()) {
            // 2026-07-25 §2 B-2: プレイヤートグルOFF(選択採掘したい場面向け)。
            return;
        }
        if (!GatheringToolMatcher.matches(player.getInventory().getItemInMainHand(),
                GatheringToolMatcher.MINING)) {
            // 2026-07-27: 従来ここには**ツール判定が一切無く**、素手でも杖でも伐採斧でも一括破壊が
            // 発動していた(一括伐採だけが斧を要求しており非対称だった)。use-skill タグ優先・
            // 素のバニラのツルハシはマテリアル推論で許可、という規則は GatheringToolMatcher 参照。
            return;
        }

        OptionalDouble tier = dedicatedEffects.valueMax(player, EFFECT_VEIN_MINING);
        if (tier.isPresent()) {
            handleVeinMining(player, block, type, (int) tier.getAsDouble());
        }
    }

    /**
     * Drop-table roll only (2026-07-23 verifier指摘⑧): {@link EventPriority#MONITOR} +
     * {@code ignoreCancelled=true} so a protection plugin's {@code HIGHEST} cancel (or any other plugin's
     * cancel) reliably suppresses the prize too; also excludes player-placed ore blocks
     * ({@link PlacedBlockTracker#isPlaced}) from ever contributing to the drop table.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreakDropTables(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (SpellBreakGuard.isSpellBreak(block)) {
            // 魔法(Ars)破壊の合成イベントには一括破壊/ドロップテーブルを一切与えない。
            return;
        }
        if (!gimmickConfig.oreBlocks().contains(block.getType()) || placedBlockTracker.isPlaced(block)) {
            return;
        }
        rollDropTables(event.getPlayer(), block);
    }

    /**
     * Chain-breaks up to {@link MiningGimmickConfig#veinMiningMaxExtraBlocks()} connected same-type
     * ore blocks via {@link VeinMiningAlgorithm#collect}. The originally-broken block ({@code origin})
     * is left to the event itself (never cancelled, never re-broken here). Each extra block is broken
     * with {@link Block#breakNaturally(ItemStack)} using the player's held tool, so vanilla
     * fortune/silk-touch on that tool still applies to the drop exactly as a normal break would.
     */
    private void handleVeinMining(Player player, Block origin, Material type, int tier) {
        World world = origin.getWorld();
        BlockPos start = new BlockPos(origin.getX(), origin.getY(), origin.getZ());
        ItemStack tool = player.getInventory().getItemInMainHand();

        List<BlockPos> extra = VeinMiningAlgorithm.collect(
                start,
                pos -> world.getBlockAt(pos.x(), pos.y(), pos.z()).getType() == type,
                gimmickConfig.veinMiningMaxExtraBlocks(tier));

        // 2026-07-28: 連鎖分の採取EXPと道具耐久は ChainBreakSupport が担う(旧実装は breakNaturally
        // だけで、EXPも耐久も一切処理されていなかった)。
        int broken = ChainBreakSupport.breakChain(player, world, extra, type, tool, chainBreakExp);
        if (broken > 0) {
            // 2026-07-25 §2 B-1: 発動フィードバック(控えめなactionbar、旧仕様の無告知を解消)。
            feedback.subtle(player, "一括破壊 x" + broken);
        }
    }

    /** Evaluates every {@code mining} drop-table category independently (§4). */
    private void rollDropTables(Player player, Block block) {
        Map<String, DropTableConfig.Category> categories = gimmickConfig.dropTables();
        if (categories.isEmpty()) {
            return;
        }
        Set<String> heldPerks = DropTableGateSupport.heldPerksOf(player);
        Map<String, Set<String>> dropGatePerks = dedicatedEffects.dropGatePerks();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (DropTableConfig.Category category : categories.values()) {
            Optional<DropTableConfig.Entry> drawn = DropTablePolicy.evaluateCategory(
                    PROF_MINING, category, heldPerks, dropGatePerks, rng.nextDouble(), rng.nextDouble());
            drawn.ifPresent(entry -> dropEntry(block, entry));
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
