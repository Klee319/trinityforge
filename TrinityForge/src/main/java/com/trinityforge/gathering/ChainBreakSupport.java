package com.trinityforge.gathering;

import com.trinityforge.mining.VeinMiningAlgorithm.BlockPos;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collection;
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
 *
 * <p>2026-07-31(G1 レビュー指摘5): 破壊そのものは {@code breakNaturally} をやめ
 * {@link #breakOnce} が「1回だけ引いた抽選結果」を自分で撒く形にした。{@code BlockBreakEvent} を
 * 発火しない点は変わらない(上の据え置き方針そのまま)。
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
        return breakChain(player, world, positions, material -> material == type, tool, expGrant, true);
    }

    /**
     * {@link #breakChain(Player, World, List, Material, ItemStack, ChainBreakExpGrant)} の一般形。
     *
     * @param accepts           破壊してよい材質の述語(一括伐採の葉のように「材質が一定でない連鎖」用)。
     * @param consumeDurability {@code false} なら道具の耐久を減らさず、道具の破損による打ち切りも
     *                          行わない。一括伐採の葉の巻き込みで使う — 葉は硬度0.2なのでバニラなら
     *                          耐久を消費するが、原木1本の伐採で数十〜数百枚が巻き込まれるため、
     *                          そのまま消費させると「一括伐採を解放した途端に斧が即壊れる」になる。
     *                          <b>葉は「原木伐採のおまけの片付け」であって収穫ではない</b>という
     *                          位置づけなので耐久は取らない。
     */
    public static int breakChain(Player player, World world, List<BlockPos> positions,
                                 java.util.function.Predicate<Material> accepts, ItemStack tool,
                                 ChainBreakExpGrant expGrant, boolean consumeDurability) {
        int broken = 0;
        boolean damageTool = consumeDurability && consumesDurability(player, tool);
        Material toolType = tool == null ? null : tool.getType();
        for (BlockPos pos : positions) {
            Block target = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (!accepts.test(target.getType())) {
                continue;
            }
            if (damageTool && player.getInventory().getItemInMainHand().getType() != toolType) {
                // 道具が壊れた/持ち替わった。
                break;
            }
            breakOnce(player, target, tool, expGrant);
            broken++;
            if (damageTool && !damageHeldTool(player)) {
                // 道具が壊れた。
                break;
            }
        }
        return broken;
    }

    /**
     * 1ブロックを壊し、<b>ルートテーブルの抽選を1回だけ</b>行う(2026-07-31 G1 レビュー指摘5)。
     *
     * <p><b>直した不具合</b>: 以前は {@code getDrops(tool, player)} で1回引いて EXP 側に渡し、その直後の
     * {@code breakNaturally(tool)} が<em>同じテーブルをもう1回</em>引いて実際のドロップを撒いていた。
     * つまり
     * <ul>
     *   <li>採取EXP({@code exp-mode: drop_sum})が<b>実際に落ちた物とは別の抽選</b>で計算されていた
     *       (葉のリンゴ/苗木のように確率ドロップだと EXP と手に入る物が食い違う)</li>
     *   <li>抽選コストが常に2倍。一括伐採の葉は1回で最大1024枚なので、そのままメインスレッドの
     *       tick に乗っていた(レビュー指摘3の主犯)</li>
     * </ul>
     * が起きていた。今は「1回引いた結果」を EXP にも渡し、そのまま自分で撒く。
     *
     * <p><b>{@code breakNaturally} を使わなくなった点の等価性</b>: {@code Block#breakNaturally(ItemStack)}
     * は Paper では {@code triggerEffect=false} / {@code dropExperience=false} の縮退呼び出しなので、
     * 破壊エフェクトもXPオーブも元から出ていない。したがって
     * 「{@code setType(AIR)} + 引いた結果を {@code dropItemNaturally}」で挙動は等価
     * (近傍更新も {@code setType} の既定で走る)。<b>{@code BlockBreakEvent} は依然として発火しない</b> —
     * 合成して代用すると採掘運/ドロップテーブル/設置追跡など10以上のリスナーが連鎖分にも反応して
     * 収穫量が跳ね上がるので、これは意図的に据え置き(クラス javadoc 参照)。
     *
     * <p><b>残余</b>: コンテナ(チェスト等)の中身は {@code getDrops} に含まれないので、連鎖対象に
     * コンテナを入れると中身が消える。現在の呼び出し元は原木/葉/鉱石/作物だけなので該当しないが、
     * 連鎖対象を広げるときはここを見ること。
     */
    private static void breakOnce(Player player, Block target, ItemStack tool, ChainBreakExpGrant expGrant) {
        // 抽選はこの1回だけ。破壊前に読むこと(壊した後は AIR になりドロップが取れない)。
        Collection<ItemStack> drops = target.getDrops(tool, player);
        if (expGrant != null) {
            expGrant.grant(player, target, drops, tool);
        }
        Location dropAt = target.getLocation();
        World world = target.getWorld();
        target.setType(Material.AIR);
        for (ItemStack drop : drops) {
            if (drop != null && drop.getType() != Material.AIR && drop.getAmount() > 0) {
                world.dropItemNaturally(dropAt, drop);
            }
        }
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
