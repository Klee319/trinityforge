package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FishingGimmickConfig;
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
 * {@code xp-bottle-store-unlock}(flag, enchanting.yml B-3「EXPフリーザー」): 経験値瓶への経験値の
 * 格納/取出。効果を保有するプレイヤーが手にバニラの {@link Material#EXPERIENCE_BOTTLE} を持ち、
 *
 * <ul>
 *   <li>sneak+右クリック(未充填の瓶): 自身の経験値のうち
 *       {@link FishingGimmickConfig#xpBottleStoreAmount()}(または保有量が少なければ保有量全て)を
 *       瓶1本のPDCに格納する。</li>
 *   <li>通常右クリック(充填済みの瓶、PDCの有無で判定): PDCに格納された経験値量を取り出し自身に返す。</li>
 * </ul>
 *
 * <p>「充填済み」かどうかはMaterialではなく{@link PdcKeys#ITEM_XP_BOTTLE_AMOUNT}の有無で判定する
 * (未充填・充填済みとも同じ{@code EXPERIENCE_BOTTLE}のため)。<strong>充填済みの瓶は sneak 状態に
 * 関わらず常にイベントをキャンセルする</strong>: バニラの「投げる」動作に流れると、瓶に格納した経験値量
 * とは無関係な少量のバニラXPオーブ(投擲時の固定量)に化けて格納値が消滅してしまうため、誤投げによる
 * 経験値消失を防ぐガード。未充填の瓶をsneakなしで右クリックした場合(取り出す対象が無い)は、
 * 通常のバニラ投擲挙動に据え置く(no-op gate)。
 *
 * <p><strong>実装メモ(要調整)</strong>: Bukkitに「経験値を直接減算するAPI」が無いため、格納時は
 * {@link Player#setLevel(int)}/{@link Player#setExp(float)}/{@link Player#setTotalExperience(int)}で
 * 一旦0にリセットしてから{@link Player#giveExp(int)}で残り分を再付与する、広く使われる回避策を採用。
 * 格納量・PDCキー名はconfig/定数(要調整)。
 */
public final class XpBottleListener implements Listener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String EFFECT_XP_BOTTLE_STORE = "xp-bottle-store-unlock";

    private final DedicatedEffectsConfig dedicatedEffects;
    private final FishingGimmickConfig gimmickConfig;

    public XpBottleListener(DedicatedEffectsConfig dedicatedEffects, FishingGimmickConfig gimmickConfig) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
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
        if (heldStack.getType() != Material.EXPERIENCE_BOTTLE) {
            return;
        }
        // 2026-07-26 tier-expand: xp-bottle-store-unlock は feature:<id> SCALE化(旧NONE)。valueMax の
        // present/emptyそのものが従来の isActive() 相当のゲートを兼ねる(SCALEはvalue省略時にtier1が
        // 自動補完されるため、既存の単一解放ノードは無改変のまま従来どおり動作する — VeinMiningListener
        // と同じidiom)。
        OptionalDouble tierValue = dedicatedEffects.valueMax(player, EFFECT_XP_BOTTLE_STORE);
        if (tierValue.isEmpty()) {
            return;
        }
        int tier = (int) tierValue.getAsDouble();

        Integer storedAmount = readStoredAmount(heldStack);
        if (storedAmount != null) {
            // Always cancel: see class javadoc (protects the stored amount from vanilla's throw).
            event.setCancelled(true);
            if (!player.isSneaking()) {
                handleWithdraw(player, heldStack, storedAmount, tier);
            }
            return;
        }

        if (player.isSneaking()) {
            handleStore(event, player, heldStack, tier);
        }
        // Unfilled bottle, not sneaking: no recognized action -> vanilla throw proceeds untouched.
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

        ItemStack filledBottle = heldStack.clone();
        filledBottle.setAmount(1);
        stampFilledBottle(filledBottle, toStore);
        consumeOneAndGive(player, heldStack, filledBottle);

        player.sendMessage(MINI_MESSAGE.deserialize(
                "<green>経験値 <white><amount></white> を経験値瓶に格納しました。</green>",
                Placeholder.unparsed("amount", Integer.toString(toStore))));
    }

    private void handleWithdraw(Player player, ItemStack heldStack, int storedAmount, int tier) {
        ItemStack emptyBottle = heldStack.clone();
        emptyBottle.setAmount(1);
        ItemMeta meta = emptyBottle.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(PdcKeys.ITEM_XP_BOTTLE_AMOUNT);
            meta.displayName(null);
            meta.lore(null);
            emptyBottle.setItemMeta(meta);
        }
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

    private static void stampFilledBottle(ItemStack bottle, int amount) {
        ItemMeta meta = bottle.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(PdcKeys.ITEM_XP_BOTTLE_AMOUNT, PersistentDataType.INTEGER, amount);
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
