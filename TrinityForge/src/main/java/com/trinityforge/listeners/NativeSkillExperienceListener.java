package com.trinityforge.listeners;

import com.trinityforge.TrinityForge;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.MobLevelTableConfig;
import com.trinityforge.config.domains.SkillExpConfig.GatheringExpMode;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.StatKeys;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.HashSet;
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
    /** enchanting.yml A/C/A-alpha/A-beta の「エンチャントEXPの増加/減少」用、プレイヤー単位の新規stat。 */
    private static final String ENCHANT_EXP_GAIN_BONUS = StatKeys.canonical("enchant_exp_gain_bonus");

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
        this.progression = progression;
        this.catalog = catalog;
        this.placedBlockTracker = placedBlockTracker;
        this.roleBuffResolver = roleBuffResolver;
        this.dedicatedEffects = dedicatedEffects;
        this.aggregator = aggregator;
        this.mobLevelTable = mobLevelTable;
        // 醸造所有者PDCキーの定義は BrewOwnership へ一本化(二重実装防止)。書き込み側はこのクラス
        // (rememberBrewer/markAutomatedBrew/onBrew)が引き続き担うが、キー文字列そのものは共有クラスから。
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
        if (placedBlockTracker.clearIfPlaced(block)) return;
        boolean qualifyingGatheringBreak = grantGathering(player, block,
                block.getDrops(player.getInventory().getItemInMainHand(), player),
                false);
        if (qualifyingGatheringBreak) {
            grantBreakVanillaExp(player);
        }
    }

    /**
     * 破壊時バニラEXP解放({@code break-vanilla-exp}, dedicated-effect機能フラグ)。既存の
     * FARMING/WOODCUTTING/DIGGING/MINING分類(={@link #grantGathering}が採取扱いと判定したブロック)
     * かつ非設置ブロックに限って、バニラEXPオーブを{@code BASE_BREAK_EXP}を基準に上乗せする。
     * vanilla_exp_bonus/break_vanilla_exp_bonusはどちらもフラクション値(0.2=+20%)であり、パーセント
     * 変換は行わない。dedicatedEffects/aggregatorが未配線(旧コンストラクタ経由)の場合は何もしない。
     */
    private void grantBreakVanillaExp(Player player) {
        if (dedicatedEffects == null || aggregator == null) return;
        if (!dedicatedEffects.isActive(player, FEATURE_BREAK_VANILLA_EXP)) return;
        var totals = aggregator.aggregate(player);
        double bonus = totals.totalOf(VANILLA_EXP_BONUS) + totals.totalOf(BREAK_VANILLA_EXP_BONUS);
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
            if (placedBlockTracker.clearIfPlaced(block)) continue;
            grantGathering(player, block, block.getDrops(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        // Bed/respawn-anchor style; no reliable player — skip attribution.
    }

    /** @return true if this break was recognized as a FARMING/WOODCUTTING/DIGGING/MINING gathering break. */
    private boolean grantGathering(Player player, Block block, Collection<ItemStack> drops, boolean blast) {
        Material material = block.getType();
        String name = material.name();
        // タスク1(2026-07-26): 採取EXPの算出方式はconfig駆動(gathering.exp-mode、既定drop_sum=現行挙動)。
        GatheringExpMode mode = TrinityForge.getInstance().config().skillExp().gatheringExpMode();

        double exp = gatheringExp(SkillId.FARMING, "block_drops", name, drops, mode);
        String skill = SkillId.FARMING;
        if (exp > 0.0 && block.getBlockData() instanceof Ageable ageable
                && ageable.getAge() < ageable.getMaximumAge()) return false;
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
        if (exp <= 0.0) return false;
        grant(player, skill, exp);
        return true;
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
            String mat = drop.getType().name();
            if (!seen.add(mat)) continue;
            double per = entry.expFor(action, mat);
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
            exp = fishing.expFor("fishing_catch", item.getItemStack().getType().name());
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
        // Catalog maps YAML exp_gain.experience_spent_conversion onto the internal rate key
        // "enchant.level_cost_multiplier" (see NativeSkillCatalog#load), so read that (default 0.5).
        double mult = entry.rate("enchant.level_cost_multiplier", 0.5);
        // Slot button 0/1/2 spends 1/2/3 levels regardless of the displayed level requirement
        // (getExpLevelCost()), which overstated true spend by up to ~10x.
        int spentLevels = event.whichButton() + 1;
        double amount = Math.max(1.0, spentLevels * mult);
        // enchant_exp_gain_bonus (enchanting.yml A/C/A-alpha/A-beta の「エンチャントEXPの増加/減少」):
        // 既存の enchant.level_cost_multiplier はグローバル設定値でプレイヤー差が無いため、その上に
        // プレイヤー単位で乗る新規stat。正=増加, 負=減少。合計が0を下回らないようクランプする。
        if (aggregator != null) {
            double bonus = aggregator.aggregate(enchanter).totalOf(ENCHANT_EXP_GAIN_BONUS);
            amount = Math.max(0.0, amount * (1.0 + bonus));
        }
        grant(enchanter, SkillId.ENCHANTING, amount);
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
        boolean intoStandSlot = rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize()
                && event.getCurrentItem() != null && !event.getCurrentItem().getType().isAir()
                && (event.getAction() == org.bukkit.event.inventory.InventoryAction.PLACE_ALL
                        || event.getAction() == org.bukkit.event.inventory.InventoryAction.PLACE_ONE
                        || event.getAction() == org.bukkit.event.inventory.InventoryAction.PLACE_SOME
                        || event.getAction() == org.bukkit.event.inventory.InventoryAction.SWAP_WITH_CURSOR
                        || event.getAction() == org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP);
        if (!intoStandSlot) return;
        stand.getPersistentDataContainer().set(
                brewOwnership.lastBrewerKey(), PersistentDataType.STRING, player.getUniqueId().toString());
        stand.getPersistentDataContainer().set(
                brewOwnership.brewModeKey(), PersistentDataType.STRING, BrewOwnership.MODE_MANUAL);
        stand.update();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void markAutomatedBrew(InventoryMoveItemEvent event) {
        InventoryHolder holder = event.getDestination().getHolder();
        if (!(holder instanceof BrewingStand stand)) return;
        stand.getPersistentDataContainer().set(
                brewOwnership.brewModeKey(), PersistentDataType.STRING, BrewOwnership.MODE_AUTO);
        stand.update();
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
        stand.getPersistentDataContainer().remove(brewOwnership.lastBrewerKey());
        stand.getPersistentDataContainer().remove(brewOwnership.brewModeKey());
        stand.update();
        if (ownerId.isEmpty()) return;
        SkillCatalogEntry alchemy = catalog.get(SkillId.ALCHEMY);
        double brew = alchemy.rate("alchemy.brew", 25.0);
        if (brew <= 0.0) {
            Double legacy = alchemy.actionExp().get("alchemy.brew");
            brew = legacy == null || legacy <= 0.0 ? 25.0 : legacy;
        }
        double mult = automated
                ? alchemy.rate("alchemy.auto_mult", 0.25)
                : alchemy.rate("alchemy.manual_mult", 2.0);
        if (mult <= 0.0) mult = automated ? 0.25 : 1.0;
        progression.grant(ownerId.get(), SkillId.ALCHEMY, brew * mult);
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
     * Exploit fix (semi-AFK 防具EXP farm): per-(victim,attacker) cooldown for the piece-flat armor EXP
     * grant below. Shared pure-Java tracker ({@link AttackerTargetCooldown}) — see its Javadoc.
     */
    private final AttackerTargetCooldown armorExpCooldown = new AttackerTargetCooldown();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getFinalDamage() <= 0.0) return;
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
        boolean useHeavy = heavy >= light;
        String skill = useHeavy ? SkillId.HEAVY_ARMOR : SkillId.LIGHT_ARMOR;
        SkillCatalogEntry entry = catalog.get(skill);
        double rate = entry.rate("armor.damage_exp_rate", 0.05);
        int pieces = useHeavy ? heavy : light;
        // Flat addend per worn piece (YAML should use small TF values, e.g. 0.25 — not Valhalla's 10).
        // Exploit fix: a near-zero final hit (grazing arrow etc.) below the configured threshold, or a
        // repeat hit from the SAME attacker within the configured cooldown, grants no piece-flat EXP —
        // closing the semi-AFK passive-farm loophole. The damage-proportional `rate` component is
        // unaffected (it already scales down to ~0 on a near-zero hit).
        double pieceFlat = Math.max(0.0, entry.rate("armor.exp_damage_piece", 0.0)) * pieces;
        if (pieceFlat > 0.0) {
            double minDamage = Math.max(0.0, entry.rate("armor.exp_damage_piece_min_damage", 1.0));
            double cooldownSeconds = Math.max(0.0, entry.rate("armor.exp_damage_piece_cooldown_seconds", 10.0));
            UUID attackerId = armorExpAttackerId(event);
            if (event.getFinalDamage() < minDamage
                    || (attackerId != null && armorExpCooldown.isOnCooldownAndRefresh(
                            player.getUniqueId(), attackerId, cooldownSeconds, System.currentTimeMillis()))) {
                pieceFlat = 0.0;
            }
        }
        double total = event.getFinalDamage() * Math.max(0.0, rate) + pieceFlat;
        if (total <= 0.0) return; // 0 EXPをdispatcherへ流さない(閾値/CD棄却時のキュー無駄を防ぐ)
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
        grant(player, skill, total * worldRate * spot);
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

    private static boolean excluded(Player p) {
        var gm = p.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }
}
