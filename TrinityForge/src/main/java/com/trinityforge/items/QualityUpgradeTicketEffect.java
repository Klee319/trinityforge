package com.trinityforge.items;

import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.config.domains.QualityTiersConfig;
import com.trinityforge.listeners.PickupQualityListener;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.QualityTier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 品質レベルアップ券({@code quality_upgrade_ticket})の効果本体。装備の品質だけを+1し、
 * rollSeed(ランダムロール)は完全に維持する。
 *
 * <p>通常の装備は {@link ItemFactory#stampPreservingQualityScore(ItemStack, long, int)} で品質だけを
 * 更新し、同じ rollSeed のランダムロール(pt)を保持する。ArsPaper の効果付きスレッドだけは専用
 * lore/セット効果を壊さないよう {@code ThreadItem#refreshLoreKeepingIdentity} へ委譲する。
 * いずれも実効上限({@link QualityConfig#maxQuality()}、{@code stats/quality-tiers.yml} のティア数
 * 由来)を含めて既存の品質経路と整合する。
 *
 * <p>最上位ティアに到達済みの装備は {@link #eligible(ItemStack)} が {@code false} を返し、
 * {@link EquipmentTicketGui} は候補にすら出さない(= 券を消費せず拒否する)。
 */
public final class QualityUpgradeTicketEffect implements EquipmentTicketEffect {

    public static final String CATALOG_ID = "quality_upgrade_ticket";

    private final ItemFactory itemFactory;
    private final QualityConfig qualityConfig;
    private final QualityTiersConfig qualityTiers;

    public QualityUpgradeTicketEffect(ItemFactory itemFactory, QualityConfig qualityConfig) {
        this(itemFactory, qualityConfig, null);
    }

    /** {@code qualityTiers} はティア名表示専用で任意({@code null} なら数値のみ表示)。 */
    public QualityUpgradeTicketEffect(ItemFactory itemFactory, QualityConfig qualityConfig,
                                      QualityTiersConfig qualityTiers) {
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
        this.qualityConfig = Objects.requireNonNull(qualityConfig, "qualityConfig");
        this.qualityTiers = qualityTiers;
    }

    @Override
    public String catalogId() {
        return CATALOG_ID;
    }

    @Override
    public String title() {
        return "品質レベルアップ";
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
        return data.hasRollSeed()
                && data.quality() < qualityConfig.maxQuality()
                && itemFactory.qualityVaries(stack);
    }

    @Override
    public List<Component> previewLore(ItemStack stack) {
        ItemData data = ItemData.of(stack.getItemMeta());
        int quality = data.quality();
        int next = Math.min(qualityConfig.maxQuality(), quality + 1);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("現在の品質: " + quality + tierSuffix(quality), NamedTextColor.GRAY));
        lines.add(Component.text("変化後: " + next + tierSuffix(next), NamedTextColor.GOLD));
        lines.add(Component.text("ランダムロール(厳選幅)は変わりません", NamedTextColor.AQUA));
        return lines;
    }

    private String tierSuffix(int quality) {
        if (qualityTiers == null) {
            return "";
        }
        return qualityTiers.tierFor(quality).map(QualityTier::name)
                .map(name -> " (" + name + ")").orElse("");
    }

    @Override
    public Optional<ItemStack> apply(ItemStack stack) {
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
        int max = qualityConfig.maxQuality();
        if (quality >= max) {
            return Optional.empty();
        }
        long seed = data.rollSeed().orElse(0L);
        // ArsPaper のスレッドは専用 lore にセット効果・スロット案内を持つ。
        // 汎用 stamp はそれらを消してしまうため、既存 identity を保つ専用更新へ委譲する。
        if (PickupQualityListener.hasArsThreadMarker(meta)) {
            return PickupQualityListener.promoteArsThreadKeepingIdentity(stack, quality + 1, itemFactory)
                    ? Optional.of(stack) : Optional.empty();
        }
        itemFactory.stampPreservingQualityScore(stack, seed, quality + 1);
        return Optional.of(stack);
    }

    @Override
    public String appliedMessage() {
        return "品質を1段階引き上げました(ランダムロールは維持されます)。";
    }
}
