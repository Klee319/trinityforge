package com.trinityforge.gathering;

import com.trinityforge.mining.VeinMiningAlgorithm.BlockPos;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 一括伐採({@code tree-fell}) / 一括破壊({@code vein-mining}) の連鎖破壊分の共通処理。
 *
 * <p>2026-07-28 実サーバ報告「一括伐採や一括採掘で経験値が正しくはいらない。もしかしたらその分
 * 耐久値も減っていないかも」への対応。連鎖分は {@link Block#breakNaturally(ItemStack)} で壊しており、
 * これは {@code BlockBreakEvent} を発火しない。その結果、
 * <ul>
 *   <li>採取スキルEXP({@code NativeSkillExperienceListener#onBlockBreak})が動かない
 *       — 起点1ブロック分しか入らない</li>
 *   <li>道具の耐久も減らない — バニラの耐久消費は破壊イベント経路にあるため</li>
 * </ul>
 * が両方とも起きていた(ユーザーの推測どおり)。ここで明示的に補う。
 *
 * <p><strong>あえて {@code BlockBreakEvent} を合成しない。</strong> 発火させると採掘運/各ギミック/
 * ドロップテーブル/設置ブロック追跡など 10 以上のリスナーが連鎖分にも走り、一括破壊の収穫量が
 * 跳ね上がる(＝要望の範囲を超えた大幅なバランス変更になる)。ここで補うのは <em>EXP と耐久だけ</em>。
 */
public final class ChainBreakSupport {

    private ChainBreakSupport() {
    }

    /**
     * 連鎖破壊対象を順に壊し、1ブロックごとに採取EXPを付与して道具の耐久を1消費する。
     *
     * <p>道具が壊れた(あるいは持ち替わった)時点で連鎖を打ち切る — バニラなら道具が壊れれば
     * そこで採掘は止まるので、耐久を無視して掘り進めるのは EXP 側だけ直して耐久側を残す
     * のと同じ抜け道になる。
     *
     * @param type     連鎖対象のマテリアル。同tickの連鎖反応でスナップショットから変わった位置は飛ばす。
     * @param expGrant null 可(未配線時は EXP 付与だけ行わない)。
     * @return 実際に壊した数
     */
    public static int breakChain(Player player, World world, List<BlockPos> positions, Material type,
                                 ItemStack tool, ChainBreakExpGrant expGrant) {
        int broken = 0;
        boolean damageTool = consumesDurability(player, tool);
        Material toolType = tool == null ? null : tool.getType();
        for (BlockPos pos : positions) {
            Block target = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (target.getType() != type) {
                continue;
            }
            if (damageTool && player.getInventory().getItemInMainHand().getType() != toolType) {
                // 道具が壊れた/持ち替わった。
                break;
            }
            if (expGrant != null) {
                // 破壊前に呼ぶこと(壊した後は AIR になりドロップも取れない)。
                expGrant.grant(player, target, target.getDrops(tool, player), tool);
            }
            target.breakNaturally(tool);
            broken++;
            if (damageTool && !damageHeldTool(player)) {
                // 道具が壊れた。
                break;
            }
        }
        return broken;
    }

    /**
     * 硬度0のブロック(作物・草・花など)はバニラでも道具の耐久を減らさないため、範囲収穫は
     * EXP だけを補う。破壊は呼び出し側が行う(自動再植の有無で経路が分かれるため)。
     */
    public static void grantExpFor(ChainBreakExpGrant expGrant, Player player, Block block, ItemStack tool) {
        if (expGrant == null) {
            return;
        }
        expGrant.grant(player, block, block.getDrops(tool, player), tool);
    }

    private static boolean consumesDurability(Player player, ItemStack tool) {
        return player.getGameMode() != GameMode.CREATIVE
                && tool != null
                && tool.getType().getMaxDurability() > 0;
    }

    /**
     * メインハンドの道具を1減らす。バニラの道具と同じく耐久力(UNBREAKING)エンチャントの
     * {@code 1/(L+1)} 判定を通し、上限に達したら破壊する。
     *
     * <p>{@code HumanEntity#damageItemStack} を使わないのは意図的 — MockBukkit が未実装で、
     * 呼ぶとテストが <strong>失敗ではなく SKIPPED</strong> になり(既知の罠)、耐久が減ることを
     * 誰も検証できなくなるため。挙動はここで明示する。
     *
     * @return 破壊を続けてよければ true(道具が壊れたら false)
     */
    private static boolean damageHeldTool(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack held = inventory.getItemInMainHand();
        if (held == null) {
            return false;
        }
        int unbreaking = held.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) {
            return true; // 耐久力エンチャントで今回は減らなかった
        }
        ItemMeta meta = held.getItemMeta();
        if (!(meta instanceof Damageable damageable)) {
            return true;
        }
        int maxDurability = damageable.hasMaxDamage()
                ? damageable.getMaxDamage()
                : held.getType().getMaxDurability();
        if (maxDurability <= 0) {
            return false;
        }
        int next = damageable.getDamage() + 1;
        if (next >= maxDurability) {
            inventory.setItemInMainHand(null);
            return false;
        }
        damageable.setDamage(next);
        held.setItemMeta(meta);
        return true;
    }
}
