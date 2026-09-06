package com.trinityforge.items;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 個数の多い {@link ItemStack} を、地面へ落とす／インベントリへ渡す<b>直前</b>で安全な本数へ
 * 分割するための共有ユーティリティ(台帳 W-312)。
 *
 * <p><b>なぜ 99 なのか。</b> Paper 1.21 系のアイテムエンティティは個数を専用のコーデックで
 * シリアライズしており、その値域は <b>[1, 99]</b> しかない。これは素材の
 * {@link ItemStack#getMaxStackSize()}（丸太や鉄インゴットなら 64、盾やエンダーパールなら 1〜16）
 * とは<b>別の、より厳しい上限</b>で、64 スタックの素材であっても 100 個載せれば同じように壊れる。
 * 超過した個数の {@code ItemEntity} はチャンク保存時に
 * {@code Failed to encode value '... to field 'Item': Value must be within range [1;99]'}
 * という警告とともに<b>黙ってシリアライズを失敗し、次回読み込み時には消えている</b>
 * (＝プレイヤーが拾う前に落ちたアイテムが消滅する不具合の直接原因)。
 *
 * <p>したがって分割の単位は {@code min(99, stack.getMaxStackSize())}
 * であって、素材の最大スタック数だけを見てはいけない。
 *
 * <p>ドロップ増加ステ等で組み立てた個数の大きい {@link ItemStack} を扱う全ての呼び出し元は、
 * {@code world.dropItemNaturally(...)} や {@code player.getInventory().addItem(...)} へ
 * 直接渡す前に、必ずこのクラスの静的メソッドを経由すること。
 */
public final class ItemStackDrops {

    /** 1.21 系アイテムエンティティのコーデックが許す個数の上限。素材のスタック上限とは無関係。 */
    private static final int ITEM_ENTITY_CODEC_MAX = 99;

    private ItemStackDrops() {
    }

    /**
     * {@code stack} を {@code min(99, stack.getMaxStackSize())} 個ごとに分割した
     * {@link ItemStack} のリストを返す。
     *
     * <p>各要素は {@code stack.clone()} してから個数だけを変えたものなので、表示名・エンチャント・
     * PDC を含むメタは全ての分割片に残る。個数が上限以下なら、分割はせず<b>クローンを1つだけ</b>
     * 返す(呼び出し元が持つ元の {@code stack} を書き換えても分割結果に影響させないため)。
     *
     * @throws NullPointerException {@code stack} が {@code null} の場合
     */
    public static List<ItemStack> split(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        int unit = Math.max(1, Math.min(ITEM_ENTITY_CODEC_MAX, stack.getMaxStackSize()));
        int total = stack.getAmount();
        List<ItemStack> parts = new ArrayList<>();
        if (total <= unit) {
            ItemStack single = stack.clone();
            parts.add(single);
            return parts;
        }
        int remaining = total;
        while (remaining > 0) {
            int amount = Math.min(unit, remaining);
            ItemStack part = stack.clone();
            part.setAmount(amount);
            parts.add(part);
            remaining -= amount;
        }
        return parts;
    }

    /**
     * {@link #split(ItemStack)} した各片を {@code world.dropItemNaturally(loc, ...)} で落とす。
     */
    public static void dropSplit(World world, Location loc, ItemStack stack) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(loc, "loc");
        for (ItemStack part : split(stack)) {
            world.dropItemNaturally(loc, part);
        }
    }

    /**
     * {@link #split(ItemStack)} した各片を {@code player.getInventory().addItem(...)} へ渡し、
     * 入り切らなかった分(leftover)は本人の足元へ {@link #dropSplit} で落とす。
     *
     * <p><b>分割してから {@code addItem} に渡すのが要点。</b> Bukkit の
     * {@code Inventory#addItem} は、渡したスタックがインベントリに入り切らないとき
     * <b>元の巨大なスタックをそのまま</b> leftover として返す(内部で分割してはくれない)。
     * 先に分割せず 257 個のスタックを渡すと、満杯時の leftover も 257 個のまま地面へ落ち、
     * 何も解決しない。
     */
    public static void giveOrDropSplit(Player player, ItemStack stack) {
        Objects.requireNonNull(player, "player");
        for (ItemStack part : split(stack)) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(part);
            for (ItemStack remainder : leftover.values()) {
                dropSplit(player.getWorld(), player.getLocation(), remainder);
            }
        }
    }
}
