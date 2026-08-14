package com.trinityforge.listeners;

import com.trinityforge.TrinityForge;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.MobLevelTableConfig;
import com.trinityforge.config.domains.SkillExpConfig.GatheringExpMode;
import com.trinityforge.farming.CropMaturity;
import com.trinityforge.gathering.ChainBreakSupport;
import com.trinityforge.gathering.StackingPlantChain;
import com.trinityforge.mining.VeinMiningAlgorithm.BlockPos;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.UseRequirementResolver;
import com.trinityforge.progression.catalog.ItemExpLookup;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.BreakVanillaExpBonusKeys;
import com.trinityforge.stats.StatKeys;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Beehive;
import org.bukkit.block.data.type.CaveVinesPlant;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Valhalla-independent EXP producers for gathering and enchanting. Combat and crafting EXP are
 * emitted by their existing TrinityForge listeners, which keeps duplicate awards impossible.
 */
public final class NativeSkillExperienceListener implements Listener {

    private static final String BREW_MODE_MANUAL = "manual";
    private static final String BREW_MODE_AUTO = "auto";

    /** 破壊時バニラEXP解放({@code break-vanilla-exp})1回分のベース付与量。倍率は各stat側でチューニング。 */
    private static final int BASE_BREAK_EXP = 1;
    private static final String FEATURE_BREAK_VANILLA_EXP = "break-vanilla-exp";
    private static final String VANILLA_EXP_BONUS = StatKeys.canonical("vanilla_exp_bonus");
    private static final String BREAK_VANILLA_EXP_BONUS = StatKeys.canonical("break_vanilla_exp_bonus");
    // 2026-08-14: enchant_exp_gain_bonus はここで消費していたが、職業EXP増加の共通機構
    // (enchanting_exp_bonus = <スキルID>_exp_bonus、NativeProgressionService#grant が適用)と
    // 同じ量に別経路で掛かる重複だったので廃止した。StatKeys のエイリアスで旧キーは
    // enchanting_exp_bonus へ読み替わるため、ここで読み直すと二重適用になる。
    private static final String POTION_QUALITY_BONUS = StatKeys.canonical("potion_quality_bonus");
    /** {@link #grantStackCollapseChain} が1回の破壊で辿る連鎖ブロック数の防御的上限。 */
    private static final int STACK_COLLAPSE_SCAN_LIMIT = 512;

    private final Plugin plugin;
    private final NativeExperienceDispatcher progression;
    private final NativeSkillCatalog catalog;
    private final PlacedBlockTracker placedBlockTracker;
    private final RoleBuffResolver roleBuffResolver;
    private final DedicatedEffectsConfig dedicatedEffects;
    private final PlayerStatAggregator aggregator;
    private final BrewOwnership brewOwnership;
    /**
     * {@code combat/mob-level-table.yml} の {@code no-skill-exp-mobs}(2026-07-27 牧場対策)。
     * null許容 — 未配線(以下の旧コンストラクタ経由、既存テスト互換)なら防具スキルEXP抑止は無効。
     */
    private final MobLevelTableConfig mobLevelTable;

    public NativeSkillExperienceListener(Plugin plugin, NativeExperienceDispatcher progression,
                                         NativeSkillCatalog catalog, PlacedBlockTracker placedBlockTracker) {
        this(plugin, progression, catalog, placedBlockTracker, null, null, null, null);
    }

    public NativeSkillExperienceListener(Plugin plugin, NativeExperienceDispatcher progression,
                                         NativeSkillCatalog catalog, PlacedBlockTracker placedBlockTracker,
                                         RoleBuffResolver roleBuffResolver) {
        this(plugin, progression, catalog, placedBlockTracker, roleBuffResolver, null, null, null);
    }

    public NativeSkillExperienceListener(Plugin plugin, NativeExperienceDispatcher progression,
                                         NativeSkillCatalog catalog, PlacedBlockTracker placedBlockTracker,
                                         RoleBuffResolver roleBuffResolver,
                                         DedicatedEffectsConfig dedicatedEffects,
                                         PlayerStatAggregator aggregator) {
        this(plugin, progression, catalog, placedBlockTracker, roleBuffResolver, dedicatedEffects, aggregator, null);
    }

    /**
     * @param mobLevelTable {@code combat/mob-level-table.yml} の {@code no-skill-exp-mobs}(2026-07-27
     *                      牧場対策)。防具スキルEXP付与時に「攻撃してきた側」の EntityType がここに
     *                      載っていれば付与しない。null可(その場合は抑止しない、旧挙動)。
     */
    public NativeSkillExperienceListener(Plugin plugin, NativeExperienceDispatcher progression,
                                         NativeSkillCatalog catalog, PlacedBlockTracker placedBlockTracker,
                                         RoleBuffResolver roleBuffResolver,
                                         DedicatedEffectsConfig dedicatedEffects,
                                         PlayerStatAggregator aggregator,
                                         MobLevelTableConfig mobLevelTable) {
        this.plugin = plugin;
        this.progression = progression;
        this.catalog = catalog;
        this.placedBlockTracker = placedBlockTracker;
        this.roleBuffResolver = roleBuffResolver;
        this.dedicatedEffects = dedicatedEffects;
        this.aggregator = aggregator;
        this.mobLevelTable = mobLevelTable;
        // 醸造所有者PDCは BrewOwnership へ一本化(二重実装防止)。キー文字列だけでなく
        // 書き込み規則(先着優先・差し替え・自動マーク・完了時クリア)もあちら側にしかない。
        this.brewOwnership = new BrewOwnership(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (SpellBreakGuard.isSpellBreak(event.getBlock())) {
            // 魔法(Ars)破壊の合成イベントには採取スキルEXP/バニラEXP解放を一切与えない。
            return;
        }
        Player player = event.getPlayer();
        if (excluded(player)) return;
        Block block = event.getBlock();
        ItemStack tool = player.getInventory().getItemInMainHand();
        // サトウキビ/竹/コンブ/サボテン/ツタの連鎖崩壊分(下記メソッド参照)。起点自体が
        // 「置く→壊す」ガードで0になる場合でも、上(下)に育った段は別途EXP対象になるため、
        // 起点のガード判定より先に処理する。
        grantStackCollapseChain(player, block, tool);
        if (blockedByPlaceBreakGuard(block)) return;
        // 破壊時バニラEXPは「どの採取スキルとして扱われた破壊か」でツリーを絞る必要があるので、
        // grantGathering が確定させたスキルIDをそのまま受け取る(null=採取扱いでない破壊)。
        String gatheringSkill = grantGathering(player, block,
                block.getDrops(tool, player),
                false, tool);
        if (gatheringSkill != null) {
            grantBreakVanillaExp(player, gatheringSkill);
        }
    }

    /**
     * サトウキビ/竹/コンブ/サボテン/ねじれツタ/泣きツタの<b>連鎖崩壊</b>分に採取EXPを付与する
     * (2026-08-03 実サーバ報告「サトウキビの根元を壊すと経験値が入らなかった」の修正)。
     *
     * <p><b>機構</b>: これらは支持ブロック(下、泣きツタのみ上)を失うとバニラの物理挙動で連結した
     * 段がまとめて消える。この消滅は{@code BlockBreakEvent}を伴わない自動破壊なので、
     * {@link #onBlockBreak}が一度も呼ばれず<b>育った段のEXPがまるごと失われていた</b>
     * ({@link StackingPlantChain}のjavadoc参照)。ここでバニラが崩す<b>前</b>(このハンドラは
     * MONITORだが、イベント発火時点ではまだ実際のワールド除去は行われていないため{@code block}は
     * まだ元のMaterialのまま読める)に連鎖対象を自前で確定し、{@link ChainBreakSupport}で1段ずつ
     * 崩してEXPを付与してしまう。処理後にバニラが起点を実際に除去したときは、上(下)は既に空気に
     * なっているため二重ドロップは起きない。
     *
     * <p><b>抜け道が無い根拠</b>: 連鎖対象の各ブロックも{@link #grantChainBreak}(=
     * {@link #blockedByPlaceBreakGuard})を個別に通る。サトウキビ等は手植えで積み上げることも
     * できるが、その場合は積んだ1段ずつが{@code BlockPlaceEvent}で個別に設置マークを持つため、
     * 「手植えで積んで根元だけ壊す」も各段が個別にガードされ0のまま — 育った(＝設置マークの無い)
     * 段だけがEXP対象になる。
     */
    private void grantStackCollapseChain(Player player, Block origin, ItemStack tool) {
        StackingPlantChain.Family family = StackingPlantChain.familyOf(origin.getType());
        if (family == null) return;
        BlockFace direction = family.direction();
        List<BlockPos> positions = new ArrayList<>();
        Block cursor = origin.getRelative(direction);
        // cursor==null: ワールド境界(あるいはgetRelativeを配線していないテストダブル)を防御。
        // ワールド高さ(既定 -64〜320 = 384段)を大きく超える探索上限も併せて防御的に掛ける。
        while (cursor != null && positions.size() < STACK_COLLAPSE_SCAN_LIMIT
                && family.members().contains(cursor.getType())) {
            positions.add(new BlockPos(cursor.getX(), cursor.getY(), cursor.getZ()));
            cursor = cursor.getRelative(direction);
        }
        if (positions.isEmpty()) return;
        ChainBreakSupport.breakChain(player, origin.getWorld(), positions,
                family.members()::contains, tool, this::grantChainBreak, false);
    }

    /**
     * 一括伐採/一括破壊/範囲収穫の <em>連鎖破壊分</em> に採取スキルEXPを付与する
     * ({@link com.trinityforge.gathering.ChainBreakExpGrant} の実装)。
     *
     * <p>2026-07-28: 連鎖分は {@code Block#breakNaturally} で壊されており {@link BlockBreakEvent} が
     * 飛ばないため、{@link #onBlockBreak} が一度も走らず <strong>起点1ブロック分のEXPしか入って
     * いなかった</strong>。爆破採掘({@link #onEntityExplode})と同じく「イベントの無い破壊」なので、
     * そちらと同じ形で {@link #grantGathering} を直接呼ぶ。
     *
     * <p>破壊時バニラEXP({@code break-vanilla-exp})は意図的に付けない — 起点1回分のままにする
     * (連鎖ぶんまでバニラEXPオーブを配ると、一括破壊がそのままバニラEXP増殖装置になる)。
     */
    public void grantChainBreak(Player player, Block block, Collection<ItemStack> drops, ItemStack tool) {
        if (player == null || block == null || excluded(player)) return;
        // 設置ブロックの連鎖破壊はEXP対象外(起点と同じ規則、成熟ガード作物の例外も同じ)。
        if (blockedByPlaceBreakGuard(block)) return;
        grantGathering(player, block, drops, false, tool);
    }

    /**
     * 破壊時バニラEXP解放({@code break-vanilla-exp}, dedicated-effect機能フラグ)。既存の
     * FARMING/WOODCUTTING/DIGGING/MINING分類(={@link #grantGathering}が採取扱いと判定したブロック)
     * かつ非設置ブロックに限って、バニラEXPオーブを{@code BASE_BREAK_EXP}を基準に上乗せする。
     * vanilla_exp_bonus/break_vanilla_exp_bonusはどちらもフラクション値(0.2=+20%)であり、パーセント
     * 変換は行わない。dedicatedEffects/aggregatorが未配線(旧コンストラクタ経由)の場合は何もしない。
     *
     * <p><b>2026-08-01 実サーバ報告の修正 — 解放判定はツリー横断してはいけない</b>:
     * {@code feature:break-vanilla-exp} は mining/woodcutting/digging/farming の<b>4ツリーすべて</b>が
     * A ノードに置いている。以前はツリーを問わない {@code isActive(player, id)} で見ていたため、
     * <b>採掘ツリーの A しか取っていないプレイヤーが作物・原木・土でもバニラEXPを得ていた</b>
     * (=3ツリー分の解放をタダ取りできる)。破壊が属する採取スキルで絞る。
     *
     * <p><b>2026-08-15 実サーバ報告の修正 — 倍率も職業間で漏れていた</b>:
     * 上の解放ゲートは職業別になったが、倍率の {@code break_vanilla_exp_bonus} はスコープを持たない
     * 総合ステのままで、出荷スキルツリーの6ノード全部がそこへ {@code 0.5} を配っていた。
     * その結果<b>採掘で取った +50% が伐採・整地・農業の破壊EXPにもそのまま乗っていた</b>
     * (説明文は「破壊で1.5倍」等とツリー内で完結する前提の書き方)。採取スキル別の
     * {@link BreakVanillaExpBonusKeys} を導入し、出荷ノードはそちらへ移した。
     * スコープ無しの {@code break_vanilla_exp_bonus} は「採取全般」の意味で引き続き加算する。
     *
     * @param gatheringSkill {@link #grantGathering} が確定させた採取スキルID
     *                       (FARMING/WOODCUTTING/DIGGING/MINING)。このツリーに置かれた配置だけを見る。
     */
    private void grantBreakVanillaExp(Player player, String gatheringSkill) {
        if (dedicatedEffects == null || aggregator == null) return;
        if (!dedicatedEffects.isActive(player, FEATURE_BREAK_VANILLA_EXP, gatheringSkill)) return;
        var totals = aggregator.aggregate(player);
        double bonus = totals.totalOf(VANILLA_EXP_BONUS) + totals.totalOf(BREAK_VANILLA_EXP_BONUS);
        // 採取スキル別の倍率(2026-08-15)。破壊が属するツリーのキーだけを足す。
        String perSkillKey = BreakVanillaExpBonusKeys.forSkill(gatheringSkill);
        if (perSkillKey != null) {
            bonus += totals.totalOf(perSkillKey);
        }
        int amount = (int) Math.round(BASE_BREAK_EXP * (1.0 + Math.max(0.0, bonus)));
        if (amount > 0) {
            player.giveExp(amount);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Player player = responsiblePlayer(event.getEntity());
        if (player == null || excluded(player)) return;
        for (Block block : event.blockList()) {
            if (blockedByPlaceBreakGuard(block)) continue;
            // 爆破採掘には使用可能レベル連動EXPを掛けない(tool=null)。ツールで壊していないので
            // 「使用したツールの使用可能レベル」という要件の前提を満たさないし、高レベルツルハシを
            // 持ったままTNTを起爆するだけで倍率が乗る抜け道にもなるため。
            grantGathering(player, block, block.getDrops(), true, null);
        }
    }

    /**
     * 「置いてから壊す」EXPファーム対策の判定(2026-08-03 実サーバ報告の修正)。
     *
     * <p><b>成熟ガード対象の作物({@link CropMaturity#isMaturityGated})は必ず例外にする</b>:
     * 小麦/ニンジン/ジャガイモ/ビートルート/ネザーウォート/ココア/スイートベリー等は
     * <b>プレイヤーが種を植えることでしか存在しない</b>ため、種の設置が {@code BlockPlaceEvent} を
     * 通って {@link PlacedBlockTracker} に必ずマークを付ける。ここで設置マークをそのまま
     * ガードに使うと、<b>自分の畑で育てて収穫した作物が(例外なく)EXP対象から除外される</b>
     * ——農業というスキルの主経路そのものが常時0EXPになるバグだった
     * (`onBlockBreak`/`grantChainBreak`/`onEntityExplode` の3経路すべてが同じ書き方だったため
     * 単体破壊・一括収穫・爆破のいずれでも再現した)。同種のガードを先に実装していた
     * {@link FarmingGimmickListener#passesFarmingAntiLoopGuard} は最初からこの例外を持っており、
     * 本メソッドはそれと同じ規則をEXP経路にも揃える。
     *
     * <p>成熟ガード対象の作物は代わりに {@link #grantGathering} 内の
     * {@link CropMaturity#isImmatureCrop} が「植えた直後(age0)を壊しても付与しない」という
     * 別の抑止を既に持っているため、設置マークで弾かなくても「種を植えて即壊す」ループへの
     * 耐性は失われない(成長時間が実質のレート制限になる)。
     *
     * <p>それ以外のブロック(石/丸太/土等、および成熟の概念を持たない植物)は
     * 従来通り設置マークで弾く。マークは(ヒットしたときは常に){@link PlacedBlockTracker#clearIfPlaced}
     * で消費するので、成熟ガード作物であっても記録自体は溜め続けない。
     */
    private boolean blockedByPlaceBreakGuard(Block block) {
        boolean wasPlaced = placedBlockTracker.clearIfPlaced(block);
        return wasPlaced && !CropMaturity.isMaturityGated(block.getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        // Bed/respawn-anchor style; no reliable player — skip attribution.
    }

    /**
     * Valhalla {@code woodcutting_strip}: right-clicking a natural log/wood with an axe rewards the
     * configured value of the resulting {@code STRIPPED_*} material. The source-to-result mapping is
     * derived from the Bukkit material name; every numeric value remains in the progression YAML.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWoodStrip(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (excluded(player)) return;
        ItemStack tool = event.getItem();
        if (tool == null || !tool.getType().name().endsWith("_AXE")) return;
        Block source = event.getClickedBlock();
        if (placedBlockTracker.isPlaced(source)) return;

        double exp = woodStripExp(catalog.get(SkillId.WOODCUTTING), source.getType());
        if (exp <= 0.0) return;
        grant(player, SkillId.WOODCUTTING,
                exp * useLevelExpMultiplier(SkillId.WOODCUTTING, tool));
    }

    /**
     * Valhalla {@code farming.block_interact}: mature berry/vine harvests and full-honey hive
     * harvests use the configured block value. Eligibility is gameplay state; the EXP amount itself
     * is always read from the editable action table.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFarmingInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (excluded(player)) return;
        Block block = event.getClickedBlock();
        if (!isHarvestableFarmingInteraction(block, event.getItem())) return;
        SkillCatalogEntry farming = catalog.get(SkillId.FARMING);
        double exp = farming == null ? 0.0
                : farming.expFor("block_interact", block.getType().name());
        if (exp > 0.0) {
            grant(player, SkillId.FARMING, exp);
        }
    }

    /** Valhalla {@code farming.entity_breed}: species-specific configured EXP. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFarmingBreed(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player breeder) || excluded(breeder)) return;
        SkillCatalogEntry farming = catalog.get(SkillId.FARMING);
        double exp = farming == null ? 0.0
                : farming.expFor("entity_breed", event.getEntity().getType().name());
        if (exp > 0.0) {
            grant(breeder, SkillId.FARMING, exp);
        }
    }

    /** Valhalla {@code farming.entity_shear}: entity-kind-specific configured EXP. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFarmingShear(PlayerShearEntityEvent event) {
        Player player = event.getPlayer();
        if (excluded(player)) return;
        SkillCatalogEntry farming = catalog.get(SkillId.FARMING);
        double exp = farming == null ? 0.0
                : farming.expFor("entity_shear", event.getEntity().getType().name());
        if (exp > 0.0) {
            grant(player, SkillId.FARMING, exp);
        }
    }

    /**
     * Valhalla {@code farming.entity_drops}: only species listed by the editable breed table are
     * farming mobs; their configured drop values are multiplied by actual stack amounts.
     *
     * <p><b>敵対除外(2026-08-03)</b>: {@code entity_breed}の表は「配合可能な生物」の一覧であって
     * 「敵対しない家畜」の一覧ではない。HOGLIN はクリムゾン菌糸で配合できる({@code Animals}実装)が
     * 実際は Paper の {@code Enemy}(=敵対)でもあるため、この表に載っているだけで討伐時に農業EXPが
     * 出ていた({@link com.trinityforge.farming.AnimalDamagePolicy}と同種の
     * Monster/Animals誤判定罠、{@code Monster}/{@code Enemy}の判定は必ずPaperの{@code Enemy}で行う)。
     * 討伐対象が{@code Enemy}なら家畜討伐EXPの対象から除外する。ZOGLIN は{@code entity_breed}に
     * 未掲載のため元々このゲートを通らないが、将来同表へ敵対モブが追加された場合の保険として
     * ここでも弾く。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFarmingMobDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || excluded(killer)) return;
        if (event.getEntity() instanceof Enemy) return;
        SkillCatalogEntry farming = catalog.get(SkillId.FARMING);
        if (farming == null
                || farming.expFor("entity_breed", event.getEntityType().name()) <= 0.0) {
            return;
        }
        double exp = dropActionExp(farming, "entity_drops", event.getDrops());
        if (exp > 0.0) {
            grant(killer, SkillId.FARMING, exp);
        }
    }

    /**
     * Valhalla {@code digging.archaeology_brush}: Paper exposes completed brush loot through the
     * block-drop event. Suspicious block type is the trigger; each resulting material and amount is
     * valued by the editable table.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArchaeologyDrop(BlockDropItemEvent event) {
        Material source = event.getBlockState().getType();
        if (source != Material.SUSPICIOUS_SAND && source != Material.SUSPICIOUS_GRAVEL) return;
        Player player = event.getPlayer();
        if (excluded(player)) return;
        SkillCatalogEntry digging = catalog.get(SkillId.DIGGING);
        double exp = dropActionExp(digging, "archaeology_brush",
                event.getItems().stream().map(Item::getItemStack).toList());
        if (exp > 0.0) {
            grant(player, SkillId.DIGGING, exp);
        }
    }

    /** @return true if this break was recognized as a FARMING/WOODCUTTING/DIGGING/MINING gathering break. */
    /**
     * 採取EXPを付与し、<b>そのブロックがどの採取スキルとして扱われたか</b>を返す。
     *
     * @return FARMING/WOODCUTTING/DIGGING/MINING のいずれか。採取扱いでない(=EXP対象外の)破壊なら
     *         {@code null}。呼び出し側はこの戻り値をそのまま破壊時バニラEXPのツリー限定に使う
     *         (2026-08-01。以前は boolean で「採取扱いか」だけを返しており、どのツリーかが失われていた)。
     */
    private String grantGathering(Player player, Block block, Collection<ItemStack> drops, boolean blast,
                                  ItemStack tool) {
        Material material = block.getType();
        String name = material.name();
        // タスク1(2026-07-26): 採取EXPの算出方式はconfig駆動(gathering.exp-mode、既定drop_sum=現行挙動)。
        GatheringExpMode mode = gatheringExpMode();

        double exp = gatheringExp(SkillId.FARMING, "block_drops", name, drops, mode);
        String skill = SkillId.FARMING;
        // 成熟ガードは「成熟しないと収穫できない作物」だけに掛ける(2026-08-01 U9)。以前は
        // instanceof Ageable で弾いていたが、Ageable の age はブロックごとに意味が違い、サトウキビ/
        // コンブ/竹/ねじれツタ/泣きツタ/光ツタは age が周回する成長カウンタなので「収穫できる状態でも
        // ほぼ常に age < maximumAge」だった。結果、これらは農業EXPも(return falseなので)破壊時
        // バニラEXPも永久に0だった。判定の一元化と全ブロックの分類は CropMaturity 参照。
        if (exp > 0.0 && CropMaturity.isImmatureCrop(block)) return null;
        if (exp <= 0.0) {
            exp = gatheringExp(SkillId.WOODCUTTING, "woodcutting_break", name, drops, mode);
            skill = SkillId.WOODCUTTING;
        }
        if (exp <= 0.0) {
            exp = gatheringExp(SkillId.DIGGING, "digging_break", name, drops, mode);
            skill = SkillId.DIGGING;
        }
        if (exp <= 0.0) {
            SkillCatalogEntry mining = catalog.get(SkillId.MINING);
            exp = gatheringExp(SkillId.MINING, "mining_break", name, drops, mode);
            if (exp > 0.0 && mining != null) {
                double mult = blast
                        ? mining.rate("mining.blast_mult", 1.5)
                        : mining.rate("mining.mine_mult", 1.0);
                exp *= Math.max(0.0, mult);
            }
            skill = SkillId.MINING;
        }
        if (exp <= 0.0) return null;
        // 使用可能レベル連動EXP (2026-07-28): FARMINGは対象外(要件どおり)、爆破採掘(blast=true, tool=null)
        // にも掛からない(resolveUseLevelがtool==nullで常に0=倍率1.0を返す)。
        if (!SkillId.FARMING.equals(skill)) {
            exp *= useLevelExpMultiplier(skill, tool);
        }
        grant(player, skill, exp);
        return skill;
    }

    /**
     * 採取EXPの算出方式({@code gathering.exp-mode})。{@link TrinityForge#getInstance()} が null
     * (プラグイン未起動=ユニットテスト等)のときは既定の {@link GatheringExpMode#DROP_SUM} を返す —
     * {@link #worldExpRate} が同じ状況でワールドゲートを掛けないのと同じ理由で、ここで NPE を投げると
     * 破壊経路そのものがテストから叩けなくなる。
     */
    private static GatheringExpMode gatheringExpMode() {
        var tf = TrinityForge.getInstance();
        return tf == null ? GatheringExpMode.DROP_SUM : tf.config().skillExp().gatheringExpMode();
    }

    /**
     * {@code skill}(WOODCUTTING/DIGGING/MINING/SMITHING)の使用可能レベル連動EXP倍率。
     * {@code tool} が null(爆破採掘/未解決)、または {@link TrinityForge#getInstance()} が null
     * (ユニットテスト環境等でプラグイン未起動)のときは常に1.0(倍率なし)を返す — 倍率の実計算は
     * 純粋関数 {@link SkillExpConfig#useLevelExpMultiplier} 側に置いてあるので、そちらでテストできる。
     */
    private static double useLevelExpMultiplier(String skill, ItemStack tool) {
        if (tool == null) {
            return 1.0;
        }
        var tf = TrinityForge.getInstance();
        if (tf == null) {
            return 1.0;
        }
        int useLevel = UseRequirementResolver.resolve(tool, tf.config().itemStats())
                .map(UseRequirementResolver.Resolved::level)
                .orElse(0);
        return tf.config().skillExp().useLevelExpMultiplier(skill, useLevel);
    }

    /**
     * Block must be listed. When drops exist, at least one drop material must also be listed
     * (except in {@link GatheringExpMode#BLOCK_VALUE} mode, where the block's own value is used
     * once the tool-requirement guard below has passed). Fortune-safe: each listed drop material
     * counts once (stack size ignored) — this guarantee is preserved for every mode, since a
     * mode never multiplies by stack size or drop count.
     *
     * <p>Back-compat overload (no mode) always uses {@link GatheringExpMode#DROP_SUM}, matching the
     * pre-2026-07-26 behaviour byte-for-byte (existing tests call this overload directly).
     */
    static double gatheringExp(SkillCatalogEntry entry, String action, String blockName,
                               Collection<ItemStack> drops) {
        return gatheringExp(entry, action, blockName, drops, GatheringExpMode.DROP_SUM);
    }

    static double gatheringExp(SkillCatalogEntry entry, String action, String blockName,
                               Collection<ItemStack> drops, GatheringExpMode mode) {
        if (entry == null) return 0.0;
        double blockExp = entry.expFor(action, blockName);
        if (blockExp <= 0.0) return 0.0;
        // Empty drops mean the block was broken without the required tool (every listed gathering
        // block in the configs drops something with the correct tool) — award nothing rather than
        // the full block EXP, closing a hand-break-for-EXP loophole. This guard applies identically
        // in every mode (task brief: must be preserved regardless of algorithm).
        if (drops == null || drops.isEmpty()) {
            return 0.0;
        }
        Set<String> seen = new HashSet<>();
        double dropExp = 0.0;
        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir()) continue;
            // 2026-07-28: 重複判定もカスタムID込みで行う(土台Materialが同じ別アイテムを潰さない)。
            if (!seen.add(ItemExpLookup.dedupeKey(drop))) continue;
            double per = ItemExpLookup.expFor(entry, action, drop);
            if (per > 0.0) dropExp += per;
        }
        GatheringExpMode effectiveMode = mode == null ? GatheringExpMode.DROP_SUM : mode;
        return switch (effectiveMode) {
            // 既定・現行挙動: ドロップ品の合計(dropExp<=0なら0、Prefer drop table sum, never scale by stack size)。
            case DROP_SUM -> dropExp;
            // タスク1本題: ブロックそのものに設定された値。深層岩バリアント等、ドロップ品が同じでも
            // ブロック側の値が違う設計を反映する(ツール要件ガードは上のdrops.isEmpty()で既に通過済み)。
            case BLOCK_VALUE -> blockExp;
            // 両者の大きい方。
            case MAX -> Math.max(dropExp, blockExp);
        };
    }

    private double gatheringExp(String skillId, String action, String blockName,
                                Collection<ItemStack> drops, GatheringExpMode mode) {
        return gatheringExp(catalog.get(skillId), action, blockName, drops, mode);
    }

    private static Player responsiblePlayer(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Player player = event.getPlayer();
        if (excluded(player)) return;
        SkillCatalogEntry fishing = catalog.get(SkillId.FISHING);
        double exp = 0.0;
        if (event.getCaught() instanceof org.bukkit.entity.Item item) {
            // 2026-07-28: カスタム釣果(custom:<id>)の行を優先。未設定ならバニラ Material 行。
            exp = ItemExpLookup.expFor(fishing, "fishing_catch", item.getItemStack());
        }
        if (exp <= 0.0) {
            double fallback = fishing.rate("fishing.catch", 20.0);
            exp = fallback;
        }
        if (exp <= 0.0) return;
        grant(player, SkillId.FISHING, exp);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Player enchanter = event.getEnchanter();
        if (excluded(enchanter)) return;
        SkillCatalogEntry entry = catalog.get(SkillId.ENCHANTING);
        int spentLevels = event.whichButton() + 1;
        Map<String, Integer> enchants = new java.util.LinkedHashMap<>();
        Map<Enchantment, Integer> added = event.getEnchantsToAdd();
        if (added != null) {
            for (Map.Entry<Enchantment, Integer> enchant : added.entrySet()) {
                enchants.put(enchant.getKey().getKey().getKey().toLowerCase(Locale.ROOT),
                        enchant.getValue());
            }
        }
        ItemStack enchantedItem = event.getItem();
        double amount = enchantingExp(entry, enchants,
                enchantedItem == null ? null : enchantedItem.getType(), spentLevels);
        // 2026-08-14: ここに enchant_exp_gain_bonus の乗算があったが廃止した。増減は
        // enchanting_exp_bonus(職業EXP増加(エンチャント))として NativeProgressionService#grant が
        // skill_exp_bonus と【加算】で合成してから1回だけ掛ける。ここで先に掛けると、
        // 表記どおりの合計にならない (1+全スキル+職業別)×(1+ここ) の三層になるうえ、
        // StatKeys のエイリアスで旧キーが enchanting_exp_bonus へ読み替わる今は二重適用になる。
        grant(enchanter, SkillId.ENCHANTING, amount);
    }

    static double enchantingExp(SkillCatalogEntry entry, Map<String, Integer> enchants,
                                 Material material, int spentLevels) {
        if (entry == null) return 0.0;
        double typeMultiplier = configuredMultiplier(entry,
                "exp_gain.enchantment_type_multiplier", enchantMaterialType(material));
        double itemMultiplier = configuredMultiplier(entry,
                "exp_gain.enchantment_item_multiplier", enchantItemType(material));
        double amount = 0.0;
        if (enchants != null) {
            for (Map.Entry<String, Integer> enchant : enchants.entrySet()) {
                if (enchant.getKey() == null || enchant.getValue() == null || enchant.getValue() <= 0) continue;
                double base = entry.expFor("exp_gain.enchantment_base",
                        enchant.getKey().toLowerCase(Locale.ROOT));
                if (base <= 0.0) continue;
                double levelMultiplier = configuredMultiplier(entry,
                        "exp_gain.enchantment_level_multiplier", Integer.toString(enchant.getValue()));
                amount += base * levelMultiplier * typeMultiplier * itemMultiplier;
            }
        }
        double spentConversion = Math.max(0.0,
                entry.rate("enchant.level_cost_multiplier", 0.0));
        double total = Math.max(0.0, amount + Math.max(0, spentLevels) * spentConversion);
        // Preserve the previous fractional-sink guard for unusually small operator values. The
        // Valhalla tables are normally hundreds of EXP, so this only affects the spent-level-only
        // fallback (or deliberately tiny custom tables).
        return total > 0.0 ? Math.max(1.0, total) : 0.0;
    }

    static double dropActionExp(SkillCatalogEntry entry, String action, Collection<ItemStack> drops) {
        if (entry == null || action == null || drops == null) return 0.0;
        double total = 0.0;
        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir() || drop.getAmount() <= 0) continue;
            // 2026-07-28: カスタムアイテム(custom:<id>)の行を優先して引く。未設定ならバニラ行。
            double perItem = ItemExpLookup.expFor(entry, action, drop);
            if (perItem > 0.0) {
                total += perItem * drop.getAmount();
            }
        }
        return total;
    }

    static double woodStripExp(SkillCatalogEntry entry, Material source) {
        if (entry == null || source == null) return 0.0;
        Material result = Material.matchMaterial("STRIPPED_" + source.name());
        return result == null ? 0.0 : entry.expFor("woodcutting_strip", result.name());
    }

    private static double configuredMultiplier(SkillCatalogEntry entry, String action, String key) {
        if (key == null) return 1.0;
        double configured = entry.expFor(action, key);
        return configured > 0.0 ? configured : 1.0;
    }

    private static String enchantMaterialType(Material material) {
        if (material == null) return null;
        String name = material.name();
        if ("BOW".equals(name) || "CROSSBOW".equals(name)) return name;
        if (name.startsWith("WOODEN_")) return "WOOD";
        if (name.startsWith("GOLDEN_")) return "GOLD";
        int separator = name.indexOf('_');
        return separator > 0 ? name.substring(0, separator) : name;
    }

    private static String enchantItemType(Material material) {
        if (material == null) return null;
        String name = material.name();
        if ("FISHING_ROD".equals(name) || "CROSSBOW".equals(name) || "BOW".equals(name)) return name;
        int separator = name.lastIndexOf('_');
        return separator >= 0 ? name.substring(separator + 1) : name;
    }

    static boolean isHarvestableFarmingInteraction(Block block, ItemStack usedItem) {
        Material type = block.getType();
        // Bone meal right-clicks the same mature blocks but performs growth/fertilization rather
        // than harvesting. Treating that click as a harvest would allow repeated EXP without
        // consuming the crop.
        if (usedItem != null && usedItem.getType() == Material.BONE_MEAL) return false;
        if (type == Material.SWEET_BERRY_BUSH && block.getBlockData() instanceof Ageable ageable) {
            return ageable.getAge() > 1;
        }
        if ((type == Material.CAVE_VINES || type == Material.CAVE_VINES_PLANT)
                && block.getBlockData() instanceof CaveVinesPlant vines) {
            return vines.hasBerries();
        }
        if ((type == Material.BEEHIVE || type == Material.BEE_NEST)
                && block.getBlockData() instanceof Beehive hive && usedItem != null) {
            Material item = usedItem.getType();
            return hive.getHoneyLevel() >= hive.getMaximumHoneyLevel()
                    && (item == Material.GLASS_BOTTLE || item == Material.SHEARS);
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void rememberBrewer(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getView().getTopInventory().getHolder() instanceof BrewingStand stand)) {
            return;
        }
        if (excluded(player)) return;
        // Only treat this as a manual insertion when the click actually moved an item INTO one of
        // the brewing stand's own slots (ingredient slot 3 or a potion/fuel slot 0-2), not merely
        // any click while the stand GUI happens to be open (e.g. clicking the player's own
        // inventory, or withdrawing a finished potion) — a single stray click must not flip a
        // hopper-fed automated brewer to the 8x manual rate forever.
        int rawSlot = event.getRawSlot();
        boolean directPlacement = switch (event.getAction()) {
            // InventoryClickEvent exposes the pre-click state. For placement into an empty brewing
            // slot currentItem is null; the item being inserted is still on the cursor.
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR ->
                    event.getCursor() != null && !event.getCursor().getType().isAir();
            case HOTBAR_SWAP -> {
                int hotbarButton = event.getHotbarButton();
                ItemStack inserted = hotbarButton >= 0
                        ? player.getInventory().getItem(hotbarButton)
                        : player.getInventory().getItemInOffHand();
                yield inserted != null && !inserted.getType().isAir();
            }
            default -> false;
        };
        boolean intoStandSlot = rawSlot >= 0
                && rawSlot < event.getView().getTopInventory().getSize()
                && directPlacement;
        if (!intoStandSlot) return;
        // 先着優先(BrewOwnership の書き込み規則1)。最後に触った人が上書きできると、他人の醸造の
        // 報酬を最後にクリックするだけで奪えるうえ、未解放プレイヤーが最後に触るだけで
        // 解放済みの台のゲート付き醸造を止められる(同じ1本のキーを解放ゲートも読むため)。
        brewOwnership.rememberOwner(stand, player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void markAutomatedBrew(InventoryMoveItemEvent event) {
        InventoryHolder holder = event.getDestination().getHolder();
        if (!(holder instanceof BrewingStand stand)) return;
        brewOwnership.markAutomated(stand);
    }

    /**
     * MONITORで動く(=このプラグインが購読する醸造関連ハンドラの中で最後)。品質/速度など醸造所有者PDCを
     * 読む他のリスナー({@link com.trinityforge.listeners.PotionQualityListener} 等)は、この消去より
     * 前の優先度(HIGH)で読み取ること — {@link BrewOwnership} のjavadoc参照。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        if (!(event.getBlock().getState() instanceof BrewingStand stand)) return;
        Optional<UUID> ownerId = brewOwnership.ownerOf(stand);
        boolean automated = brewOwnership.isAutomated(stand);
        // Always clear brewer/mode attribution after this brew completes, win or lose, so credit
        // (and the manual/automated mode flag) can never persist onto a later, unrelated brew —
        // this is what closed the "one manual click sticks the 8x rate forever" exploit.
        // 2026-07-31: 醸造解放ゲートも同じ記録を読むので、このクリアは
        // 「ゲート付き醸造はサイクルごとに解放済みプレイヤーの手投入が必要(=完全なホッパー自動化は
        // できない)」も同時に意味する(BrewUnlockListener のクラスjavadoc参照)。
        brewOwnership.clear(stand);
        if (ownerId.isEmpty()) return;
        SkillCatalogEntry alchemy = catalog.get(SkillId.ALCHEMY);
        ItemStack ingredient = brewIngredient(event);
        List<String> potionTypes = new ArrayList<>();
        for (ItemStack result : event.getResults()) {
            if (result != null && result.getItemMeta() instanceof PotionMeta potion
                    && potion.hasBasePotionType()) {
                potionTypes.add(potion.getBasePotionType().name());
            }
        }
        double brew = alchemyBrewExpForIngredient(alchemy, ingredient, potionTypes);
        Player owner = plugin.getServer().getPlayer(ownerId.get());
        if (aggregator != null && owner != null) {
            double quality = Math.max(0.0,
                    aggregator.aggregate(owner).totalOf(POTION_QUALITY_BONUS));
            brew *= 1.0 + quality * Math.max(0.0,
                    alchemy.rate("alchemy.quality_mult", 0.0));
        }
        double mult = automated
                ? alchemy.rate("alchemy.auto_mult", 0.25)
                : alchemy.rate("alchemy.manual_mult", 2.0);
        if (mult <= 0.0) mult = automated ? 0.25 : 1.0;
        progression.grant(ownerId.get(), SkillId.ALCHEMY, brew * mult);
    }

    /**
     * Paper returns {@code null}/AIR when the ingredient was consumed, while MockBukkit can throw
     * {@link IllegalStateException} for the same empty post-brew slot. Both mean "no ingredient
     * stage available", so fall back to the result table/base brew value. Attribution PDC is
     * intentionally cleared before this helper is called and therefore remains one-shot even if a
     * third-party inventory implementation rejects the read.
     */
    private static ItemStack brewIngredient(BrewEvent event) {
        try {
            ItemStack ingredient = event.getContents().getIngredient();
            return ingredient == null || ingredient.getType().isAir() ? null : ingredient;
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    /**
     * 2026-07-28: 醸造素材はカスタムアイテム({@code custom:<id>})でも設定できる。
     * {@link ItemExpLookup} が custom 行 → バニラ Material 行の順で解決する。
     */
    static double alchemyBrewExpForIngredient(SkillCatalogEntry entry, ItemStack ingredient,
                                              Collection<String> resultPotionTypes) {
        return alchemyBrewExpFrom(entry,
                ItemExpLookup.expFor(entry, "brew_ingredient", ingredient), resultPotionTypes);
    }

    static double alchemyBrewExp(SkillCatalogEntry entry, String ingredient,
                                 Collection<String> resultPotionTypes) {
        double ingredientExp = (entry == null || ingredient == null)
                ? 0.0 : entry.expFor("brew_ingredient", ingredient);
        return alchemyBrewExpFrom(entry, ingredientExp, resultPotionTypes);
    }

    private static double alchemyBrewExpFrom(SkillCatalogEntry entry, double ingredientExp,
                                             Collection<String> resultPotionTypes) {
        if (entry == null) return 0.0;
        if (ingredientExp > 0.0) return ingredientExp;
        double resultExp = 0.0;
        if (resultPotionTypes != null) {
            for (String potionType : resultPotionTypes) {
                if (potionType != null) {
                    resultExp = Math.max(resultExp, entry.expFor("brew_result", potionType));
                }
            }
        }
        if (resultExp > 0.0) return resultExp;
        return Math.max(0.0, entry.rate("alchemy.brew", 0.0));
    }

    // 2026-07-25 ユーザー判断: SMITHING EXP は耐久消耗ベースの付与を全廃し、武器/防具/ツールのクラフト時
    // (CraftQualityListener#onCraft、categorySkill: weapon/armor/tool -> SMITHING)に一本化した。
    //
    // 経緯 (PRG-13): 旧実装は `onItemDamage`(PlayerItemDamageEvent, MONITOR)で耐久が1減るたび
    // `Math.max(0.01, damage * stackRate)` を付与しており、この「1tickあたり最低0.01保証」が
    // stackRateをどれだけ小さく設定しても無限EXP経路になっていた(監査PRG-13)。フロア自体は不要
    // (EXPはdouble精度で蓄積されるため端数が消える心配は無い)と判断してフロアだけ撤去したが、その直後に
    // ユーザーから「採掘のEXP取得時に鍛冶のボスバーも出る」という報告があり、調査の結果これは表示バグでは
    // なく「ツルハシ使用→耐久減少→SMITHING EXP」という設計どおりの挙動と判明。ユーザーの決定で、
    // 耐久消耗によるSMITHING EXP付与そのものを廃止し、クラフト時付与(ARS_SMITHINGと同じ方式、
    // stats/skill-exp.yml smithing.exp-per-craft)に置き換えた。既存プレイヤーのSMITHINGレベルは
    // そのまま(EXP付与経路が変わるだけでリセットしない)。

    /**
     * Exploit fix (semi-AFK 防具EXP farm): per-(victim,attacker) cooldowns for armor-hit EXP.
     * Separate trackers are required because a mixed set legitimately awards both Valhalla armor
     * skills; one skill must not consume the other skill's cooldown.
     */
    private final AttackerTargetCooldown heavyArmorExpCooldown = new AttackerTargetCooldown();
    private final AttackerTargetCooldown lightArmorExpCooldown = new AttackerTargetCooldown();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getFinalDamage() <= 0.0) return;
        // 2026-07-30 デスルーラー対策: 致死ダメージの一撃では防具スキルEXPを付与しない。
        // 「死ぬまで殴られる」がEXP稼ぎとして成立していた(EMダンジョン内では特に悪質 —
        // MatchInstance が致死ダメージを MONITOR でキャンセルして「ダウン」扱いにするため、
        // TF(先に登録されるので先に走る)はEXPを配りきった後で死亡そのものが取り消され、
        // 蘇生してまた殴られる、を無限に繰り返せる)。
        if (lethalHit(event.getFinalDamage(), player.getHealth())) return;
        if (excluded(player)) return;
        // 2026-07-27 牧場対策: 「攻撃してきた側」(飛び道具なら発射者)の EntityType が
        // no-skill-exp-mobs に載っていれば防具スキルEXPは一切付与しない。武器スキルEXP側
        // (CombatListener#maybeGrantCombatSkillExp)と対で塞がないと、反撃してくる牧場動物に
        // 殴られるだけの被弾EXP farmが残ってしまう(javadoc参照)。
        if (mobLevelTable != null) {
            Entity attackerType = armorExpAttackerEntity(event);
            if (attackerType != null && mobLevelTable.suppressesSkillExp(attackerType.getType())) {
                return;
            }
        }
        // ワールド倍率(2026-07-26 オーバーワールドEXP開放): ダンジョン内=1.0、ダンジョン外=
        // outside-dungeon-exp-rate(dungeon-only-exp: true なら0.0で従来どおり完全遮断)。
        // TrinityForge.getInstance()経由(本クラスはSkillExpConfig未注入)。
        double worldRate = worldExpRate(player.getWorld());
        if (worldRate <= 0.0) return;
        int light = 0;
        int heavy = 0;
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor == null || armor.getType().isAir()) continue;
            String name = armor.getType().name();
            if (name.startsWith("LEATHER_") || name.startsWith("CHAINMAIL_")) light++;
            else if (isArmor(armor.getType())) heavy++;
        }
        if (light == 0 && heavy == 0) return;
        Entity attacker = armorExpAttackerEntity(event);
        UUID attackerId = attacker == null ? null : attacker.getUniqueId();
        boolean pvp = attacker instanceof Player;
        // TT/放置対策: 防具EXPは「被弾」で入るため、TTに立って殴られ続けるだけで無限に稼げる経路に
        // なりうる。撃破EXPと同じカウンタで同一地点の逓減を掛ける(判定地点はプレイヤー自身の位置)。
        var tf = TrinityForge.getInstance();
        double spot = 1.0;
        // world が null になり得るのはモック環境だけだが、そこで落ちると本筋と無関係なテストが
        // 巻き添えで倒れる。逓減は「掛からない」が安全側なので素通しする。
        if (tf != null && player.getWorld() != null) {
            spot = tf.locationExpDiminishing().multiplierAt(player, player.getLocation(),
                    tf.config().skillExp(),
                    tf.dungeonWorldRegistry().isDungeonWorld(player.getWorld().getUID()));
        }
        long now = System.currentTimeMillis();
        if (heavy > 0) {
            grantArmorHit(player, SkillId.HEAVY_ARMOR, heavy, armorPoints(player, true),
                    attacker, attackerId, pvp,
                    event, heavyArmorExpCooldown, worldRate, spot, now);
        }
        if (light > 0) {
            grantArmorHit(player, SkillId.LIGHT_ARMOR, light, armorPoints(player, false),
                    attacker, attackerId, pvp,
                    event, lightArmorExpCooldown, worldRate, spot, now);
        }
    }

    private void grantArmorHit(Player player, String skill, int pieces, double armorPoints,
                               Entity attacker, UUID attackerId, boolean pvp,
                               EntityDamageByEntityEvent event, AttackerTargetCooldown cooldown,
                               double worldMultiplier, double locationMultiplier, long nowMillis) {
        SkillCatalogEntry entry = catalog.get(skill);
        double minDamage = Math.max(0.0, entry.rate("armor.exp_damage_piece_min_damage", 1.0));
        double cooldownSeconds = Math.max(
                0.0, entry.rate("armor.exp_damage_piece_cooldown_seconds", 10.0));
        // TF's anti-farm guard applies to the complete Valhalla formula. Letting only one addend be
        // cooled down would still permit the original damage-rate path to award every mob hit.
        if (event.getFinalDamage() < minDamage
                || (attackerId != null && cooldown.isOnCooldownAndRefresh(
                        player.getUniqueId(), attackerId, cooldownSeconds, nowMillis))) {
            return;
        }
        // 2026-07-28 ユーザー要望「モブ定義にないモブは経験値なし」: entity_exp_multipliers に行が
        // 無いモブ(および攻撃者の EntityType が取れないケース)は倍率0 = 防具EXPを付与しない。
        // 討伐EXP側(SkillExpConfig#entityMultiplier)と同じ規則に揃えている。旧挙動(未定義=満額)に
        // 戻したい場合は各 *_armor_progression.yml の entity_exp_multipliers に行を足す。
        double entityMultiplier = 0.0;
        if (attacker != null && attacker.getType() != null) {
            entityMultiplier = entry.actionExp().getOrDefault(
                    "entity_exp_multipliers." + attacker.getType().name(), 0.0);
        }
        if (entityMultiplier <= 0.0) {
            return;
        }
        double pvpMultiplier = pvp
                ? Math.max(0.0, entry.rate("armor.pvp_multiplier", 0.1))
                : 1.0;
        double pvpExponent = pvp
                ? Math.max(0.0, entry.rate("armor.pvp_multiplier_exponent", 1.0))
                : 1.0;
        double total = armorHitExp(
                event.getDamage(),
                pieces,
                entry.rate("armor.exp_per_damage_piece", 10.0),
                armorPoints,
                // 2026-08-15: 入力が防具値(点数, 最良4部位で約20)から防御率([0,1], 同 0.30)へ変わったので
                // 既定倍率を 1/0.015 倍した(0.05 → 3.33)。最良装備での係数 1+20*0.05=2.0 が
                // 1+0.30*3.33≒2.0 のまま保たれる。
                entry.rate("armor.exp_armor_point_multiplier", 3.33),
                entityMultiplier,
                pvpMultiplier,
                pvpExponent);
        if (total > 0.0) {
            double spot = entry.rate("armor.location_diminishing_enabled", 1.0) > 0.0
                    ? locationMultiplier
                    : 1.0;
            grant(player, skill, total * worldMultiplier * spot);
        }
    }

    /**
     * この一撃が致死かどうか(2026-07-30、デスルーラー対策)。{@link EntityDamageByEntityEvent} は
     * ダメージ適用<em>前</em>に発火するので {@code health} はまだ削られていない値であり、
     * {@code finalDamage >= health} なら「この一撃で死ぬ」= 防具EXPを与えない。
     *
     * <p>不死のトーテムで生き残るケースもこの判定では致死扱いになる(=EXPが入らない)。
     * これは意図的: トーテムを持って死に続ける farm も同じ抜け道になるため、
     * 「生き延びたかどうか」ではなく「致死量を受けたかどうか」で切る方が塞ぎ方として堅い。
     * 純関数なのでユニットテストから直接叩ける。
     */
    static boolean lethalHit(double finalDamage, double health) {
        if (!Double.isFinite(finalDamage) || !Double.isFinite(health)) {
            return false;
        }
        return finalDamage >= health;
    }

    /**
     * ValhallaMMO 1.9.3 armor-hit formula. Its heavy-armor bytecode applies the PvP multiplier
     * twice while light armor applies it once, so the exponent is data-driven rather than hidden in
     * this method ({@code heavy=2}, {@code light=1} in the shipped progression files).
     */
    static double armorHitExp(double rawDamage, int wornPieces, double expPerDamagePiece,
                              double totalArmorPoints, double armorPointMultiplier,
                              double entityMultiplier, double pvpMultiplier,
                              double pvpMultiplierExponent) {
        if (!Double.isFinite(rawDamage) || rawDamage <= 0.0 || rawDamage > 1_000_000.0
                || wornPieces <= 0) {
            return 0.0;
        }
        double perDamage = nonNegativeFinite(expPerDamagePiece);
        double armorPoints = nonNegativeFinite(totalArmorPoints);
        double pointMultiplier = nonNegativeFinite(armorPointMultiplier);
        double entity = nonNegativeFinite(entityMultiplier);
        double pvp = nonNegativeFinite(pvpMultiplier);
        double exponent = nonNegativeFinite(pvpMultiplierExponent);
        double result = perDamage * rawDamage * wornPieces
                * (1.0 + armorPoints * pointMultiplier)
                * entity
                * Math.pow(pvp, exponent);
        return Double.isFinite(result) && result > 0.0 ? result : 0.0;
    }

    /**
     * 防具EXPの「装備の硬さ」係数の入力。2026-08-15 に防具値(armor-defense-rate, 点数)を廃止したので
     * 防御率({@code defense-rate}, [0,1])を読む。数値の桁が 1/66.7 になるため、呼び出し側の既定倍率も
     * 同じ比で引き上げてある(armorHitExp の {@code armor.exp_armor_point_multiplier} 参照)。
     */
    private double armorPoints(Player player, boolean heavy) {
        if (aggregator == null) {
            return 0.0;
        }
        try {
            return nonNegativeFinite(
                    aggregator.equippedArmorStatTotal(player, "defense-rate", stack ->
                            heavy ? isArmor(stack.getType()) && !isLightArmor(stack.getType())
                                    : isLightArmor(stack.getType())));
        } catch (RuntimeException ignored) {
            // Invalid/unresolved equipment must not break the damage event. The base Valhalla
            // multiplier remains 1.0, so the hit can still award its configured base EXP.
            return 0.0;
        }
    }

    private static double nonNegativeFinite(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }

    /** Applies the selected support-role multiplier to every player-attributed native EXP grant. */
    private void grant(Player player, String skill, double amount) {
        if (player == null || !(amount > 0.0)) {
            return;
        }
        double multiplier = roleBuffResolver == null
                ? 1.0
                : roleBuffResolver.expMultiplierForSkill(player, skill).orElse(1.0);
        progression.grant(player.getUniqueId(), skill, amount * multiplier);
    }

    /**
     * このワールドで防具スキルEXPに掛ける倍率。ダンジョンワールド
     * ({@link com.trinityforge.dungeon.DungeonWorldRegistry})なら1.0、それ以外は
     * {@code outside-dungeon-exp-rate}({@code dungeon-only-exp: true} のときは0.0=従来の完全遮断)。
     */
    private static double worldExpRate(World world) {
        var tf = TrinityForge.getInstance();
        if (tf == null || world == null) {
            // ダンジョン判定の材料が無い(プラグイン未起動=ユニットテスト等)ときはワールドゲートを
            // 掛けない。旧 expAllowedInWorld が同じ状況で true を返していたのと同じ挙動。
            return 1.0;
        }
        return tf.config().skillExp()
                .worldExpRate(tf.dungeonWorldRegistry().isDungeonWorld(world.getUID()));
    }

    /** The attacker's identity for the armor-EXP cooldown key: the shooter for a projectile, else the damager. */
    private static UUID armorExpAttackerId(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            return shooter.getUniqueId();
        }
        return damager.getUniqueId();
    }

    /**
     * 2026-07-27 牧場対策: {@code no-skill-exp-mobs} 判定用の「攻撃してきた側」Entity。
     * {@link #armorExpAttackerId} と同じ解決規則(飛び道具なら発射者、それ以外はダメージ源そのもの)。
     */
    private static Entity armorExpAttackerEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }

    private static boolean isArmor(Material material) {
        String n = material.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
    }

    private static boolean isLightArmor(Material material) {
        String name = material.name();
        return isArmor(material) && (name.startsWith("LEATHER_") || name.startsWith("CHAINMAIL_"));
    }

    private static boolean excluded(Player p) {
        var gm = p.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }
}
