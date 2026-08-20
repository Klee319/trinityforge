package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.fishing.XpBottlePolicy;
import com.trinityforge.pdc.PdcKeys;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * {@code xp-bottle-store-unlock}(flag, enchanting.yml B-3「EXPフリーザー」): 経験値の格納/取出。
 * 効果を保有するプレイヤーが、
 *
 * <ul>
 *   <li><b>ガラス瓶({@link Material#GLASS_BOTTLE})を右クリック</b>: 自身の経験値のうち
 *       {@link CraftingFeaturesConfig#xpBottleStoreAmount(int)}(または保有量が少なければ保有量全て)を
 *       格納し、ガラス瓶1本を「充填済み経験値瓶」に変える。</li>
 *   <li><b>充填済み経験値瓶を右クリック</b>: 格納された経験値を
 *       {@link CraftingFeaturesConfig#xpBottleReturnRate(int)} 倍で取り出し、<b>ガラス瓶に戻す</b>。</li>
 * </ul>
 *
 * <p><b>2026-08-15</b>: 数値設定 {@code xp-bottle-store} の読み手を {@code FishingGimmickConfig} から
 * {@link CraftingFeaturesConfig}(progression/crafting-features.yml)へ移設した(この機能はエンチャント
 * ツリーの機能で、釣りとは無関係な設定が誤って釣りギミックのファイルに置かれていたため)。
 *
 * <p><b>2026-08-05 仕様変更</b>: 格納の起点を「経験値瓶の sneak+右クリック」から
 * 「<b>ガラス瓶の通常右クリック</b>」へ変更した(ユーザー指示)。取出でガラス瓶を返すのは対称性のためだけ
 * ではなく、<b>ガラス瓶→バニラの経験値瓶という無償の変換路を作らないため</b>
 * (経験値瓶はバニラでは交易/戦利品でしか手に入らない。取出で経験値瓶を返すと
 * 「ガラス瓶を入れて経験値瓶が出てくる」アイテム増殖になる)。
 *
 * <p>「充填済み」かどうかはMaterialではなく{@link PdcKeys#ITEM_XP_BOTTLE_AMOUNT}の有無で判定する。
 * <strong>充填済みの瓶は sneak 状態に関わらず常にイベントをキャンセルする</strong>: バニラの「投げる」
 * 動作に流れると、瓶に格納した経験値量とは無関係な少量のバニラXPオーブ(投擲時の固定量)に化けて格納値が
 * 消滅してしまうため、誤投げによる経験値消失を防ぐガード。PDCを持たない素の経験値瓶は一切触らない
 * (バニラの投擲のまま)。
 *
 * <p><strong>ガラス瓶はバニラの用途を持つ道具なので、バニラの操作を絶対に奪わない</strong>
 * ({@link #wouldVanillaUseTheBottle}): 水汲み(水源/水没ブロック/水入り大釜)・蜂の巣からの蜜採取・
 * 甘いベリーの収穫・そもそも右クリックで何かが起きるブロック({@link Material#isInteractable()}=
 * チェスト/ドア/作業台など)に向けたクリックでは<b>何もせずバニラに譲る</b>。水汲みだけはクリック先が
 * ブロックとして届かない(バニラの瓶が流体だけを別途レイトレースする)ので、こちらも同じ探索を行う。
 *
 * <p><strong>実装メモ(要調整)</strong>: Bukkitに「経験値を直接減算するAPI」が無いため、格納時は
 * {@link Player#setLevel(int)}/{@link Player#setExp(float)}/{@link Player#setTotalExperience(int)}で
 * 一旦0にリセットしてから{@link Player#giveExp(int)}で残り分を再付与する、広く使われる回避策を採用。
 */
public final class XpBottleListener implements Listener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String EFFECT_XP_BOTTLE_STORE = "xp-bottle-store-unlock";
    /**
     * バニラの瓶が水源を探す距離。1.21 の {@code block_interaction_range} 既定値(4.5)に合わせてある
     * ——バニラの {@code BottleItem} はプレイヤーのブロック操作リーチで流体レイトレースを行うため。
     */
    private static final double BLOCK_INTERACTION_RANGE = 4.5;

    private final DedicatedEffectsConfig dedicatedEffects;
    private final CraftingFeaturesConfig gimmickConfig;

    /**
     * 視線上の「バニラの瓶が汲める流体ブロック」を返す。既定は実レイトレース。
     *
     * <p>差し替え可能にしてあるのは <b>MockBukkit が {@code rayTraceBlocks} を未実装
     * ({@code UnimplementedOperationException})で、テストが失敗ではなく SKIPPED に化けるため</b>。
     * どのブロックを「汲める」と見るかの判定({@link #isWaterFillTarget})は本物のまま検証する。
     */
    private java.util.function.Function<Player, org.bukkit.block.Block> waterTargetLookup =
            XpBottleListener::rayTraceWaterTarget;

    public XpBottleListener(DedicatedEffectsConfig dedicatedEffects, CraftingFeaturesConfig gimmickConfig) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
    }

    /** テスト専用: 視線上の水源探索を差し替える(理由は {@link #waterTargetLookup})。 */
    void waterTargetLookupForTest(java.util.function.Function<Player, org.bukkit.block.Block> lookup) {
        this.waterTargetLookup = Objects.requireNonNull(lookup, "lookup");
    }

    /**
     * <b>{@code ignoreCancelled} を付けてはいけない(2026-08-03)。</b>{@link PlayerInteractEvent} は
     * クリックしたブロックが {@code null}(= {@code RIGHT_CLICK_AIR})のとき、誰もキャンセルしていなくても
     * 生成時点から {@code isCancelled() == true} になるため、{@code ignoreCancelled = true} を付けると
     * 空クリックが一切配送されない(=ブロックに向けたときしか経験値瓶を扱えない)。理由の詳細は
     * {@link GachaListener#onInteract} の javadoc。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            // Paper fires this event for both hands; only handle the main-hand instance so an
            // off-hand bottle does not double-process.
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack heldStack = player.getInventory().getItemInMainHand();
        Material heldType = heldStack.getType();
        if (heldType != Material.GLASS_BOTTLE && heldType != Material.EXPERIENCE_BOTTLE) {
            return;
        }
        // 2026-08-19 W-134: 取り出しは【解放判定より前】に処理する。
        // 以前はここで解放ゲートを通していたため、未解放のプレイヤーが充填済みの瓶を右クリックすると
        // そのままバニラの投擲に流れ、格納した経験値が投擲時の固定量に化けて消えていた
        // (実サーバ報告「未解放だとエンチャント瓶として投げてしまう」)。
        // 充填(=瓶に詰める)には従来どおり解放が要るが、取り出しは誰でもできてよい ——
        // 瓶そのものは受け渡し・保管される持ち物で、解放者しか開けられない道理が無いため。
        if (heldType == Material.EXPERIENCE_BOTTLE) {
            Integer storedAmount = readStoredAmount(heldStack);
            if (storedAmount == null) {
                // PDCを持たない素のバニラ経験値瓶: 投擲のまま(この機構は一切触らない)。
                return;
            }
            // Always cancel: see class javadoc (protects the stored amount from vanilla's throw).
            event.setCancelled(true);
            handleWithdraw(player, heldStack, storedAmount, withdrawTier(heldStack, player));
            return;
        }

        // 2026-07-26 tier-expand: xp-bottle-store-unlock は feature:<id> SCALE化(旧NONE)。valueMax の
        // present/emptyそのものが従来の isActive() 相当のゲートを兼ねる(SCALEはvalue省略時にtier1が
        // 自動補完されるため、既存の単一解放ノードは無改変のまま従来どおり動作する — VeinMiningListener
        // と同じidiom)。ここから先(=充填)は解放者だけ。
        OptionalDouble tierValue = dedicatedEffects.valueMax(player, EFFECT_XP_BOTTLE_STORE);
        if (tierValue.isEmpty()) {
            return;
        }
        int tier = (int) tierValue.getAsDouble();

        // ガラス瓶: バニラの用途(水汲み/蜜採取/ブロック操作)が成立するクリックでは何もしない。
        if (wouldVanillaUseTheBottle(event, player, heldStack)) {
            return;
        }
        handleStore(event, player, heldStack, tier);
    }

    /**
     * このクリックが「バニラのガラス瓶の用途」または「ブロックそのものの操作」に当たるか。
     *
     * <p>当たるなら格納は行わない ── ガラス瓶は水汲み・蜜採取に日常的に使う道具で、チェストや
     * ドアを開ける手にも握られているため、<b>奪うと元の操作が壊れる</b>。判定は3段:
     *
     * <ol>
     *   <li>右クリックで何かが起きるブロック({@link Material#isInteractable()}: チェスト/ドア/作業台/
     *       大釜/蜂の巣など)</li>
     *   <li>収穫が成立するクリック(蜂の巣の蜜・甘いベリー・光る果実。
     *       {@link NativeSkillExperienceListener#isHarvestableFarmingInteraction} を再利用 ──
     *       採取スキルEXPの判定と食い違わせない)</li>
     *   <li>水汲み。クリック先のブロックとしては届かない(バニラの瓶は流体だけを別途レイトレースする)
     *       ので、視線上の水源も同じ距離で探す</li>
     * </ol>
     *
     * <p>{@code isInteractable()} は Paper で「網羅的でない」として非推奨だが代替が無く、
     * <b>外す方向の誤りしか起こさない</b>(true に化けても格納しないだけ)ので保守的な網として使う。
     * ガラス瓶自身が持つアイテム側の用途は水と蜜だけで、それは2/3段で個別に見ている。
     */
    @SuppressWarnings("deprecation")
    private boolean wouldVanillaUseTheBottle(PlayerInteractEvent event, Player player, ItemStack heldStack) {
        org.bukkit.block.Block clicked = event.getClickedBlock();
        if (clicked != null) {
            if (clicked.getType().isInteractable()
                    || isWaterFillTarget(clicked)
                    || NativeSkillExperienceListener.isHarvestableFarmingInteraction(clicked, heldStack)) {
                return true;
            }
        }
        return isWaterFillTarget(waterTargetLookup.apply(player));
    }

    /** バニラのガラス瓶が水を汲めるブロックか(水源・水没ブロック・水入り大釜)。 */
    static boolean isWaterFillTarget(org.bukkit.block.Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        if (type == Material.WATER || type == Material.WATER_CAULDRON) {
            return true;
        }
        return block.getBlockData() instanceof org.bukkit.block.data.Waterlogged waterlogged
                && waterlogged.isWaterlogged();
    }

    private static org.bukkit.block.Block rayTraceWaterTarget(Player player) {
        org.bukkit.util.RayTraceResult hit = player.rayTraceBlocks(
                BLOCK_INTERACTION_RANGE, org.bukkit.FluidCollisionMode.SOURCE_ONLY);
        return hit == null ? null : hit.getHitBlock();
    }

    private void handleStore(PlayerInteractEvent event, Player player, ItemStack heldStack, int tier) {
        int available = XpBottlePolicy.totalExperience(player.getLevel(), player.getExp());
        int toStore = XpBottlePolicy.clampStoreAmount(available, gimmickConfig.xpBottleStoreAmount(tier));
        if (toStore <= 0) {
            // No-op gate: nothing to store; leave the vanilla throw behaviour alone.
            return;
        }
        event.setCancelled(true);

        int remaining = available - toStore;
        resetExperience(player);
        if (remaining > 0) {
            player.giveExp(remaining);
        }

        // ガラス瓶1本を「充填済み経験値瓶」(EXPERIENCE_BOTTLE + PDC)に変える。素の瓶のmetaは
        // 引き継がない(バニラのガラス瓶に付いた表示名などを経験値瓶へ持ち込まないため)。
        ItemStack filledBottle = new ItemStack(Material.EXPERIENCE_BOTTLE);
        stampFilledBottle(filledBottle, toStore, tier);
        consumeOneAndGive(player, heldStack, filledBottle);

        player.sendMessage(MINI_MESSAGE.deserialize(
                "<green>経験値 <white><amount></white> を経験値瓶に格納しました。</green>",
                Placeholder.unparsed("amount", Integer.toString(toStore))));
    }

    private void handleWithdraw(Player player, ItemStack heldStack, int storedAmount, int tier) {
        // 返すのは EXPERIENCE_BOTTLE ではなく GLASS_BOTTLE。経験値瓶を返すと
        // 「ガラス瓶を入れて経験値瓶が出てくる」= バニラでは交易/戦利品しか入手経路の無い
        // アイテムの無償生成路になる(クラス javadoc 参照)。
        ItemStack emptyBottle = new ItemStack(Material.GLASS_BOTTLE);
        consumeOneAndGive(player, heldStack, emptyBottle);
        int returned = (int) Math.floor(storedAmount * gimmickConfig.xpBottleReturnRate(tier));
        player.giveExp(returned);

        player.sendMessage(MINI_MESSAGE.deserialize(
                "<green>経験値瓶から経験値 <white><returned></white> を取り出しました"
                        + "(格納量: <white><stored></white>)。</green>",
                Placeholder.unparsed("returned", Integer.toString(returned)),
                Placeholder.unparsed("stored", Integer.toString(storedAmount))));
    }

    /**
     * Resets the player's XP bar to level/progress 0 (and the internal total-experience counter)
     * before {@link Player#giveExp(int)} re-lands them on the correct level+progress for the
     * post-withdrawal total — Bukkit has no direct "subtract XP" API.
     */
    private static void resetExperience(Player player) {
        player.setLevel(0);
        player.setExp(0.0f);
        player.setTotalExperience(0);
    }

    /**
     * 取り出しの還元率を引く tier(2026-08-19 / W-134)。
     *
     * <p>優先順位は (1) 瓶に焼き付けられた<b>充填時の tier</b>、(2) 持ち主の解放 tier、(3) tier1。
     * 瓶側を先に見るのは、取り出しに解放が要らなくなった以上
     * 「そのとき持っている人の tier」で引くと<b>受け渡すだけで還元率が変わってしまう</b>ため。
     * (2) は W-134 以前に詰められた瓶（tier の刻印が無い）への救済で、
     * そこも取れなければ tier1 ＝ 従来のグローバル既定へ落ちる。
     */
    private int withdrawTier(ItemStack bottle, Player player) {
        Integer stamped = readStoredTier(bottle);
        if (stamped != null && stamped > 0) {
            return stamped;
        }
        OptionalDouble holderTier = dedicatedEffects.valueMax(player, EFFECT_XP_BOTTLE_STORE);
        return holderTier.isPresent() ? Math.max(1, (int) holderTier.getAsDouble()) : 1;
    }

    private static void stampFilledBottle(ItemStack bottle, int amount, int tier) {
        ItemMeta meta = bottle.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(PdcKeys.ITEM_XP_BOTTLE_AMOUNT, PersistentDataType.INTEGER, amount);
        if (tier > 0) {
            meta.getPersistentDataContainer().set(PdcKeys.ITEM_XP_BOTTLE_TIER, PersistentDataType.INTEGER, tier);
        }
        meta.displayName(MINI_MESSAGE.deserialize("<light_purple>充填済み経験値瓶</light_purple>"));
        meta.lore(List.of(MINI_MESSAGE.deserialize(
                "<gray>格納された経験値: <white><amount></white></gray>",
                Placeholder.unparsed("amount", Integer.toString(amount)))));
        bottle.setItemMeta(meta);
    }

    private static Integer readStoredAmount(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null
                || !meta.getPersistentDataContainer().has(PdcKeys.ITEM_XP_BOTTLE_AMOUNT, PersistentDataType.INTEGER)) {
            return null;
        }
        return meta.getPersistentDataContainer().get(PdcKeys.ITEM_XP_BOTTLE_AMOUNT, PersistentDataType.INTEGER);
    }

    /** 充填時に焼き付けた tier(無ければ {@code null} = W-134 以前に詰められた瓶)。 */
    private static Integer readStoredTier(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null
                ? null
                : meta.getPersistentDataContainer().get(PdcKeys.ITEM_XP_BOTTLE_TIER, PersistentDataType.INTEGER);
    }

    /** Removes one {@code original} from the player's hand and gives {@code replacement} (falls back
     * to a natural drop when the inventory is full, matching the ticket-consume pattern used
     * elsewhere in the codebase). */
    private static void consumeOneAndGive(Player player, ItemStack original, ItemStack replacement) {
        if (original.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(replacement);
            return;
        }
        original.setAmount(original.getAmount() - 1);
        player.getInventory().setItemInMainHand(original);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(replacement);
        for (ItemStack item : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
    }
}
