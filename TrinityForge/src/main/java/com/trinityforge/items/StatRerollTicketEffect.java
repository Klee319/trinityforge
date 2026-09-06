package com.trinityforge.items;

import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CraftQualityService;
import com.trinityforge.stats.CraftRollMods;
import com.trinityforge.stats.ItemFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * ランダムステータス再抽選券({@code stat_reroll_ticket})の効果本体。装備の rollSeed だけを
 * 引き直し、品質は完全に維持する。実行者のロール運・ロール効率
 * ({@link CraftQualityService#craftRollMods}) は作業台/儀式と同じく再抽選時に焼き直す。
 *
 * <p><b>{@link com.trinityforge.listeners.GrindstonePreserveListener} との整合について。</b>
 * 同クラスの javadoc は「rollSeed は絶対に引き直さない(砥石でロールをガチャする exploit になる)」と
 * 明記しているが、これと本クラスは矛盾しない。砥石はエンチャント除去という<b>別目的の副作用として</b>
 * 無償で何度でも通せる経路であり、rollSeed の再抽選を無償の副作用に混ぜると際限なく引き直せてしまう。
 * 一方この券は「rollSeed を引き直すこと自体」を<b>唯一の主機能として対価(消費アイテム1個)と交換する</b>
 * 設計になっている — 禁止されているのは「無償でついでに引き直せる経路」であって、「専用の消費アイテムを
 * 通貨として払って引き直す」ことまでは禁止されていない。rollSeed を引き直す新しい経路を追加するときは、
 * 必ずこの2クラスを相互参照して「無償/副作用」側に紛れ込ませていないかを確認すること。
 */
public final class StatRerollTicketEffect implements EquipmentTicketEffect {

    public static final String CATALOG_ID = "stat_reroll_ticket";

    private final ItemFactory itemFactory;
    private final Supplier<CraftQualityService> craftQuality;

    public StatRerollTicketEffect(ItemFactory itemFactory) {
        this(itemFactory, () -> null);
    }

    /**
     * {@code craftQuality} は {@link com.trinityforge.TrinityForge} の初期化順が券の配線より後なので
     * Supplier で遅延参照する。適用時点では必ずセット済み。テストは {@code () -> null} で
     * {@link CraftRollMods#NONE} に落とす。
     */
    public StatRerollTicketEffect(ItemFactory itemFactory, Supplier<CraftQualityService> craftQuality) {
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
        this.craftQuality = craftQuality == null ? () -> null : craftQuality;
    }

    @Override
    public String catalogId() {
        return CATALOG_ID;
    }

    @Override
    public String title() {
        return "ランダムステータス再抽選";
    }

    @Override
    public boolean eligible(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        if (EquipmentTicketEffect.isConsumableTicketItem(stack)) {
            return false;
        }
        ItemData data = ItemData.of(stack.getItemMeta());
        return data.hasRollSeed() && itemFactory.hasRerollableRandom(stack);
    }

    @Override
    public List<Component> previewLore(ItemStack stack) {
        int quality = ItemData.of(stack.getItemMeta()).quality();
        return List.of(
                Component.text("品質: " + quality + " (維持されます)", NamedTextColor.GRAY),
                Component.text("厳選ロール(ランダム幅)だけを引き直します", NamedTextColor.AQUA),
                Component.text("ロール運・ロール効率は実行者の今の値（作業台と同じ）", NamedTextColor.DARK_AQUA));
    }

    @Override
    public Optional<ItemStack> apply(ItemStack stack) {
        return apply(stack, null);
    }

    @Override
    public Optional<ItemStack> apply(ItemStack stack, Player player) {
        if (!eligible(stack)) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        ItemData data = ItemData.of(meta);
        if (!data.hasRollSeed()) {
            return Optional.empty();
        }
        int quality = data.quality();
        long newSeed = ThreadLocalRandom.current().nextLong();
        itemFactory.stamp(stack, newSeed, quality, rollModsOf(player));
        return Optional.of(stack);
    }

    private CraftRollMods rollModsOf(Player player) {
        if (player == null) {
            return CraftRollMods.NONE;
        }
        CraftQualityService service = craftQuality.get();
        return service == null ? CraftRollMods.NONE : service.craftRollMods(player);
    }

    @Override
    public String appliedMessage() {
        return "ランダムステータスを再抽選しました(品質は維持されます)。";
    }
}
