package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CatalogVanillaOperationPolicy;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * TrinityForge 装備の砥石利用(U4): エンチャント除去そのものは成立させたうえで、
 * 砥石が剥がしてしまう見た目とステータス(表示名・lore・attribute modifier・TF 付与エンチャント)を
 * <strong>元の rollSeed / quality のまま</strong>復元する。
 *
 * <p>復元は「元アイテムを clone し、バニラ結果が残したエンチャントと耐久だけを取り込み、
 * {@link ItemFactory#stamp} で再組み立てする」形で行う。カタログテンプレートから
 * {@link ItemFactory#create} で作り直す実装にはしていない: create は owner(SOULBOUND の所有者)/
 * 儀式のスレッド枠加算/コーティング/クラフト時ロール補正といった「支払い済みの PDC」を
 * 再現しないので、砥石を通すだけで魂縛が外れる・儀式の投資が消えるという別のバグになる。
 *
 * <p><strong>rollSeed は絶対に引き直さない。</strong>引き直すと「砥石に通して厳選ロールをガチャする」
 * exploit になる。品質・厳選ロール・耐久も維持する(砥石はエンチャント付け替え用の道具でよい)。
 *
 * <p>優先度が MONITOR なのは、拒否判定を持つ
 * {@link CatalogVanillaOperationGuardListener#onPrepareGrindstone}(HIGHEST) より必ず後に走る必要が
 * あるため(結果が null = ガードが拒否した組み合わせなので何もしない)。
 * materials.yml 素材のみの組み合わせは ArsPaper {@code CustomItemListener} が結果を空にする。
 */
public final class GrindstonePreserveListener implements Listener {

    private final ItemCatalogConfig catalog;
    private final ItemFactory itemFactory;

    public GrindstonePreserveListener(ItemCatalogConfig catalog, ItemFactory itemFactory) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        ItemStack result = event.getResult();
        if (isEmpty(result)) {
            return;
        }
        ItemStack upper = event.getInventory().getItem(0);
        ItemStack lower = event.getInventory().getItem(1);
        ItemStack primary = firstNonEmpty(upper, lower);
        if (primary == null || !hasTfItemIdentity(primary)) {
            return;
        }

        ItemStack restored = restore(primary, result);
        // 片側だけの投入(=純粋な除去)で、復元後のエンチャントが元と完全に同じなら、
        // プレイヤーが外せるエンチャントは1つも無い(= TF 自身が付与した刻印だけ)。
        // それでも結果枠を出すと「取り出す→TF が刻印を戻す→また取り出す」で砥石が
        // 無限EXP源になるので、この場合だけ結果を空にする。
        if ((isEmpty(upper) || isEmpty(lower))
                && enchantmentsOf(primary).equals(enchantmentsOf(restored))) {
            event.setResult(null);
            return;
        }
        event.setResult(restored);
    }

    /**
     * 元アイテム({@code primary})を土台に、バニラ結果({@code vanillaResult})が下した
     * 「どのエンチャントを残すか / 耐久をいくつにするか」だけを取り込んだ復元品を作る。
     */
    private ItemStack restore(ItemStack primary, ItemStack vanillaResult) {
        ItemStack out = primary.clone();
        out.setAmount(Math.max(1, vanillaResult.getAmount()));
        // バニラが残したエンチャント(呪い等)に揃える = 「エンチャントだけ落とす」
        applyEnchantments(out, enchantmentsOf(vanillaResult));
        // 耐久はバニラ結果を引き継ぐ(単体投入なら元と同値、同一 identity のマージなら修理後の値)。
        copyDamage(vanillaResult, out);

        ItemData source = ItemData.of(primary.getItemMeta());
        // rollSeed が無いアイテムは TF の派生ステを持たない(ItemRefreshPolicy と同じ基準)ので
        // 再組み立てしない。ここで seed を発行してはいけない(新規ロールの発行になる)。
        source.rollSeed().ifPresent(seed -> itemFactory.stamp(out, seed, source.quality()));
        restoreEnchantGlow(primary, out);
        return out;
    }

    /**
     * カタログの {@code enchant-glow} 由来の隠しエンチャントは砥石で剥がされ、
     * {@link ItemFactory#stamp}(= 再組み立て)では戻らないので個別に戻す。
     * 戻さないと「砥石に通すと光沢が永久に消える」という不可逆な見た目劣化になる。
     */
    private void restoreEnchantGlow(ItemStack primary, ItemStack out) {
        Optional<ItemTemplate> template = CatalogVanillaOperationPolicy.catalogIdOf(primary, catalog)
                .flatMap(catalog::template);
        if (template.isEmpty() || !template.get().enchantGlow()) {
            return;
        }
        ItemMeta meta = out.getItemMeta();
        if (meta == null) {
            return;
        }
        ItemFactory.applyEnchantGlow(meta);
        out.setItemMeta(meta);
    }

    /** {@code stack} のエンチャント構成を {@code target} と一致させる(余りを外し、足りない分を付ける)。 */
    private static void applyEnchantments(ItemStack stack, Map<Enchantment, Integer> target) {
        for (Enchantment existing : Set.copyOf(stack.getEnchantments().keySet())) {
            if (!target.containsKey(existing)) {
                stack.removeEnchantment(existing);
            }
        }
        target.forEach(stack::addUnsafeEnchantment);
    }

    private static void copyDamage(ItemStack from, ItemStack to) {
        ItemMeta fromMeta = from.getItemMeta();
        ItemMeta toMeta = to.getItemMeta();
        if (!(fromMeta instanceof Damageable fromDamage) || !(toMeta instanceof Damageable toDamage)) {
            return;
        }
        toDamage.setDamage(fromDamage.getDamage());
        to.setItemMeta(toMeta);
    }

    private static Map<Enchantment, Integer> enchantmentsOf(ItemStack stack) {
        return stack == null ? Map.of() : stack.getEnchantments();
    }

    private static ItemStack firstNonEmpty(ItemStack first, ItemStack second) {
        if (!isEmpty(first)) {
            return first;
        }
        if (!isEmpty(second)) {
            return second;
        }
        return null;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private static boolean hasTfItemIdentity(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemData data = ItemData.of(item.getItemMeta());
        return data.catalogId().isPresent()
            || data.hasRollSeed()
            || data.quality() > ItemData.MIN_QUALITY
            || data.bindType().isPresent()
            || data.owner().isPresent();
    }
}
