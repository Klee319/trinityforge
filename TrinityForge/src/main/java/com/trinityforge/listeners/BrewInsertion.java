package com.trinityforge.listeners;

import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 「このGUI操作は醸造台へアイテムを<b>入れた</b>のか」の唯一の判定。
 *
 * <p><b>なぜ共有クラスにしたか (2026-08-19 / W-147)</b>: 同じ判定が
 * {@link BrewUnlockListener}(解放ゲート)と {@link NativeSkillExperienceListener}(所有者の記録)に
 * 独立して2本書かれており、<b>後者が {@code MOVE_TO_OTHER_INVENTORY}(シフトクリック)と
 * {@code HOTBAR_MOVE_AND_READD} を取りこぼしていた</b>。
 * 所有者が記録されないと {@code onBrew} は {@code ownerId.isEmpty()} で即 return するので、
 * <b>シフトクリックで素材を入れて醸造した人には錬金EXPが1点も入らない</b>
 * (同じPDCを読む品質補正・手動/自動倍率も同時に死ぬ)。
 * W-124 で醸造そのものが完成するようになるまでは、この穴は症状として表に出なかった。
 *
 * <p>2本に分かれている限り片方だけ直しても再発するので、判定はここ1箇所に集約する。
 */
final class BrewInsertion {

    private BrewInsertion() {
    }

    /**
     * クリック操作が「醸造台へアイテムを入れる」ものなら、その入るスタックを返す(それ以外は null)。
     *
     * <p><b>注意</b>: {@code getAction()} は Mockito の既定値が {@code null} になる。
     * ここを通るテストは必ず action を明示的にスタブすること(このリポジトリの既知の罠)。
     *
     * @param standInventory 醸造台側のインベントリ(= ビューの上段)
     */
    static ItemStack insertedStack(InventoryClickEvent event, Inventory standInventory) {
        InventoryAction action = event.getAction();
        if (action == null) {
            return null;
        }
        boolean clickedStand = clickedStand(event, standInventory);
        return switch (action) {
            // InventoryClickEvent はクリック前の状態を見せる。空スロットへの配置では
            // currentItem が null で、入れようとしているものはまだカーソル上にある。
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR ->
                    clickedStand ? event.getCursor() : null;
            // 醸造台以外(=プレイヤーインベントリ)からのシフトクリックが「入れる」側。
            case MOVE_TO_OTHER_INVENTORY -> clickedStand ? null : event.getCurrentItem();
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> clickedStand ? hotbarStack(event) : null;
            default -> null;
        };
    }

    /**
     * ホットバー入れ替えで入ってくるスタック。{@code getHotbarButton()} が負のときは
     * <b>オフハンドとの入れ替え</b>で、そのまま {@code getItem(-1)} を呼ぶと添字外で投げる。
     */
    private static ItemStack hotbarStack(InventoryClickEvent event) {
        PlayerInventory inventory = event.getWhoClicked().getInventory();
        int button = event.getHotbarButton();
        return button >= 0 ? inventory.getItem(button) : inventory.getItemInOffHand();
    }

    /**
     * ドラッグ操作が醸造台側のスロットへ1つでも配るなら、カーソルの中身を返す(それ以外は null)。
     */
    static ItemStack draggedStack(InventoryDragEvent event, Inventory standInventory) {
        if (standInventory == null) {
            return null;
        }
        int size = standInventory.getSize();
        boolean touchesStand = event.getRawSlots().stream().anyMatch(raw -> raw != null && raw < size);
        return touchesStand ? event.getOldCursor() : null;
    }

    /**
     * クリックされたスロットが醸造台側かどうか。
     *
     * <p>{@code getClickedInventory()} が取れるならそれで比べる。取れない({@code null})ときだけ
     * 生スロット番号で判定する — 実運用で {@code null} になるのは「ウィンドウ外クリック」
     * (rawSlot が -999)だけなので、この段でも判定は false に落ちて食い違わない。
     * Mockito で組んだ既存テストは {@code getClickedInventory()} を張らないものと
     * {@code getRawSlot()} を張らないものが混在しているため、どちらの経路も残す。
     */
    private static boolean clickedStand(InventoryClickEvent event, Inventory standInventory) {
        if (standInventory == null) {
            return false;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked != null) {
            return clicked == standInventory;
        }
        int rawSlot = event.getRawSlot();
        return rawSlot >= 0 && rawSlot < standInventory.getSize();
    }
}
