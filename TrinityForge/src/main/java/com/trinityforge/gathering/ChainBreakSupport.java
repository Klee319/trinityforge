package com.trinityforge.gathering;

import com.trinityforge.mining.VeinMiningAlgorithm.BlockPos;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
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
        // 2026-07-31 G1 round2 指摘4: doTileDrops は1回だけ読む(連鎖は最大1024枚なので
        // ブロックごとに読むと NamespacedKey の文字列化がそのぶん走る)。
        boolean dropItems = tileDropsEnabled(world);
        for (BlockPos pos : positions) {
            Block target = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (!accepts.test(target.getType())) {
                continue;
            }
            if (damageTool && player.getInventory().getItemInMainHand().getType() != toolType) {
                // 道具が壊れた/持ち替わった。
                break;
            }
            breakOnce(player, target, tool, expGrant, dropItems);
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
     * <p><b>2026-07-31 G1 round2 指摘4: 等価性に開いていた唯一の穴 = {@code doTileDrops}。</b>
     * 旧 {@code breakNaturally} は NMS の {@code Block.dropResources} → {@code popResource} を通り、
     * {@code popResource} は {@code doTileDrops} ゲームルールを見て false ならアイテムを湧かせない。
     * 一方 {@link World#dropItemNaturally} はこのゲームルールを<b>一切見ない</b>ので、
     * {@code doTileDrops=false} のサーバでは「連鎖破壊分だけがドロップを出す」という非対称が
     * 生じていた。{@link #tileDropsEnabled} で明示的に見て、false ならアイテムを湧かせない。
     * <b>採取EXPは従来どおり付与する</b> — 旧実装も {@code getDrops} の結果で EXP を付与した後に
     * {@code breakNaturally} が何も落とさない挙動だったので、これが等価な側。
     *
     * <p><b>残余</b>: コンテナ(チェスト等)の中身は {@code getDrops} に含まれないので、連鎖対象に
     * コンテナを入れると中身が消える。現在の呼び出し元は原木/葉/鉱石/作物だけなので該当しないが、
     * 連鎖対象を広げるときはここを見ること。また {@code state.spawnAfterBreak(...)} も呼ばれなくなるが、
     * 現行の呼び出し元(原木/葉/鉱石/作物)では実質 no-op なので影響しない。
     *
     * @param dropItems {@code doTileDrops} 相当。false ならブロックは壊すがアイテムは湧かせない。
     */
    private static void breakOnce(Player player, Block target, ItemStack tool, ChainBreakExpGrant expGrant,
                                  boolean dropItems) {
        // 抽選はこの1回だけ。破壊前に読むこと(壊した後は AIR になりドロップが取れない)。
        Collection<ItemStack> drops = target.getDrops(tool, player);
        if (expGrant != null) {
            expGrant.grant(player, target, drops, tool);
        }
        Location dropAt = target.getLocation();
        World world = target.getWorld();
        target.setType(Material.AIR);
        if (!dropItems) {
            return;
        }
        for (ItemStack drop : drops) {
            if (drop != null && drop.getType() != Material.AIR && drop.getAmount() > 0) {
                world.dropItemNaturally(dropAt, drop);
            }
        }
    }

    /**
     * {@code doTileDrops} ゲームルールの現在値(2026-07-31 G1 round2 指摘4)。
     * <b>{@code setType} + {@code dropItemNaturally}</b> でブロックを壊す経路は、これを自分で見ないと
     * バニラ({@code breakNaturally} 経由)と挙動が食い違う。
     *
     * <p>値が取れない実装(テストダブル等で {@code null} が返る)ではバニラ既定の {@code true} として扱う
     * — 「ドロップが出ない」方向に倒すと採取が黙って死ぬので、安全側は true。
     *
     * <p>参照するのは {@link GameRules#BLOCK_DROPS}。{@code GameRule.DO_TILE_DROPS} は
     * paper-api 1.21.11 で deprecated-for-removal になった同じ定数の旧名なので使わない。
     */
    public static boolean tileDropsEnabled(World world) {
        if (world == null) {
            return true;
        }
        Boolean value = world.getGameRuleValue(GameRules.BLOCK_DROPS);
        return value == null || value;
    }

    /**
     * 硬度0のブロック(作物・草・花など)はバニラでも道具の耐久を減らさないため、範囲収穫は
     * EXP だけを補う。破壊は呼び出し側が行う(自動再植の有無で経路が分かれるため)。
     *
     * <p><b>ルートテーブルはここで1回だけ引き、その結果を返す</b>(2026-07-31 G1 round2 指摘7)。
     * 旧実装は EXP 用に1回引いた直後、呼び出し側が {@code breakNaturally}／{@code getDrops} で
     * <em>同じテーブルをもう1回</em>引いて実際のドロップを撒いていた。つまり範囲収穫だけが
     * 「採取EXPが実際に落ちた物とは別の抽選で計算される」状態で残っており、抽選コストも2倍だった
     * (一括伐採/一括採掘は {@link #breakOnce} で既に1回化してある)。
     * <b>呼び出し側は返り値をそのまま撒くこと。二度引かないこと。</b>
     *
     * @return 引いた抽選結果(そのまま撒く用)。{@code expGrant} が null でも抽選は行って返す。
     */
    public static Collection<ItemStack> grantExpFor(ChainBreakExpGrant expGrant, Player player, Block block,
                                                    ItemStack tool) {
        Collection<ItemStack> drops = block.getDrops(tool, player);
        if (expGrant != null) {
            expGrant.grant(player, block, drops, tool);
        }
        return drops;
    }

    private static boolean consumesDurability(Player player, ItemStack tool) {
        return player.getGameMode() != GameMode.CREATIVE
                && tool != null
                && tool.getType().getMaxDurability() > 0;
    }

    /**
     * この道具で「連鎖1回ぶんの耐久」を取ってよいか(クリエイティブ / 素手 / 耐久を持たない材質は false)。
     *
     * <p>{@link #breakChain} を使わずに自前でブロックを壊す経路
     * ({@code FarmingHarvestListener} の範囲収穫)へ<b>同じ耐久モデルを配るための入口</b>。
     * 判定と消費を別々に書き写すと、W-173 の {@code isUnbreakable()} 漏れのような穴が
     * 経路ごとに再発する。
     */
    public static boolean toolConsumesDurability(Player player, ItemStack tool) {
        return consumesDurability(player, tool);
    }

    /**
     * メインハンドの道具の耐久を1消費する。詳細と設計理由は {@link #damageHeldTool}。
     *
     * @return 破壊(収穫)を続けてよければ true(道具が壊れたら false)
     */
    public static boolean damageHeldToolOnce(Player player) {
        return damageHeldTool(player);
    }

    /**
     * メインハンドの道具を1減らす。バニラの道具と同じく耐久力(UNBREAKING)エンチャントの
     * {@code 1/(L+1)} 判定を通し、上限に達したら破壊する。
     *
     * <p>{@code HumanEntity#damageItemStack} を使わないのは意図的 — MockBukkit が未実装で、
     * 呼ぶとテストが <strong>失敗ではなく SKIPPED</strong> になり(既知の罠)、耐久が減ることを
     * 誰も検証できなくなるため。挙動はここで明示する。
     *
     * <p><b>2026-08-20 W-173: {@code isUnbreakable()} を見ていなかった。</b>
     * {@code ItemAssembler} は<b>耐久ステが設定されていないカタログ品を全部
     * {@code setUnbreakable(true)} にする</b>ので、TF の道具の多くが「壊れないはず」の品になる。
     * ところがここは自前で damage を加算していて、上限に達すると
     * {@code setItemInMainHand(null)} で<b>アイテムごと消して</b>いた。
     * しかも壊れない品は耐久バーが出ないので、<b>消えるまで誰も気づけない</b>。
     * 同じことを手でやっている {@code EquipmentDurabilityService#damageSlot} と
     * ArsPaper の {@code SpellCaster#consumeCastDurability} は両方 {@code isUnbreakable()} を見ている
     * ── ここだけが落ちていた。
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
        if (!(meta instanceof Damageable damageable) || meta.isUnbreakable()) {
            // 壊れない品は減らさず、連鎖もそのまま続ける(W-173)。
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
