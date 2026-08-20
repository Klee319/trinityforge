package com.trinityforge.stats;

import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 儀式クラフトの成果物を<b>儀式を実行したプレイヤーのステータスで</b>その場で確定させる (W-85、2026-08-18)。
 *
 * <p><b>なぜ「その場で」なのか</b>: 2026-08-04 版は成果物にマーカー
 * ({@link com.trinityforge.pdc.PdcKeys#ITEM_PENDING_CRAFT_QUALITY}) だけを刻んでコアの上へドロップし、
 * 品質と SOULBOUND の所有者を「最初にインベントリへ入れたプレイヤー」
 * ({@code PickupQualityListener#stampIfEligible}) で決めていた。実サーバでは<b>儀式を行った本人より先に
 * 別のプレイヤーが拾える</b>ため、他人の魔法鍛冶レベルで品質が決まり、SOULBOUND の所有権まで
 * その拾い主のものになっていた(実サーバ報告 W-85)。
 *
 * <p>ここで確定させれば、成果物が誰の手に渡っても品質・所有者は実行者基準で固定される
 * (ユーザ決定 2026-08-18「誰でも回収可・品質は実行者基準」)。実行者は儀式を発動した直後なので
 * <b>必ずオンライン</b>であり、マーカー方式のような「決定を後回しにする」必要がそもそも無い。
 *
 * <p>確定後の成果物は {@code rollSeed} を持ち、マーカーも消えているので、
 * {@code PickupQualityListener} は誰が拾っても素通りする(=二重刻印にならない)。
 */
public final class RitualCraftFinalizer {

    private final CraftQualityService craftQuality;
    private final ItemFactory itemFactory;

    public RitualCraftFinalizer(CraftQualityService craftQuality, ItemFactory itemFactory) {
        this.craftQuality = Objects.requireNonNull(craftQuality, "craftQuality");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
    }

    /**
     * {@code performer} の魔法鍛冶ステータスで品質をロールし、lore/属性まで再組み立てして刻む。
     * SOULBOUND の成果物なら所有者も {@code performer} で確定させる。
     *
     * @return 刻印したら true(空スタック / meta を組めないスタックは false)
     */
    public boolean finalizeForPerformer(ItemStack stack, Player performer) {
        return finalizeForPerformer(stack, performer, ThreadLocalRandom.current().nextLong());
    }

    /** {@link #finalizeForPerformer(ItemStack, Player)} の rollSeed 固定版(テスト用)。 */
    boolean finalizeForPerformer(ItemStack stack, Player performer, long rollSeed) {
        if (stack == null || performer == null || stack.getType().isAir()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        int quality = craftQuality.rollArsSmithingQuality(performer, stack);
        CraftRollMods mods = craftQuality.craftRollMods(performer);
        // 旧経路(あるいは古い ArsPaper jar)が先に刻んだマーカーはここで剥がす。残したままだと
        // 拾ったプレイヤーのステータスで上書きロールされ、この確定が無意味になる。
        // 旧経路(あるいは古い ArsPaper jar)が先に刻んだマーカーはここで剥がす。残したままだと
        // 拾ったプレイヤーのステータスで上書きロールされ、この確定が無意味になる。
        ItemData.of(meta).clearPendingCraftQuality();
        stack.setItemMeta(meta);
        itemFactory.stamp(stack, rollSeed, quality, mods);
        stampOwner(stack, performer.getUniqueId(), rollSeed);
        return true;
    }

    /**
     * SOULBOUND の成果物へ実行者を所有者として刻む。
     *
     * <p><b>刻印(stamp)の後でなければならない</b>: bindType はカタログテンプレートから
     * {@link ItemAssembler} が組み立てるので、stamp 前の meta にはまだ入っていないことがある。
     * 先に見ると「SOULBOUND なのに所有者が空のまま」になり、結局
     * {@code PickupQualityListener#stampOwnerIfEligible} が拾い主を所有者にしてしまう。
     */
    private void stampOwner(ItemStack stack, UUID performerId, long rollSeed) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        ItemData data = ItemData.of(meta);
        if (data.owner().isPresent()) {
            return;
        }
        Optional<BindType> bindType = data.bindType();
        // OWNER_BOUND はコマンド専用なので自動刻印しない(autoStampsOwner は SOULBOUND のみ true)。
        if (bindType.isEmpty() || !bindType.get().autoStampsOwner()) {
            return;
        }
        int quality = data.quality();
        data.setOwner(performerId);
        stack.setItemMeta(meta);
        // 同じ rollSeed / quality で組み直す(所有者行を lore へ出すための再組み立てで、ロールは変えない)。
        itemFactory.stamp(stack, rollSeed, quality);
    }
}
