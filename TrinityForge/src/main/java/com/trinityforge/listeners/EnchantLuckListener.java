package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.EnchantLuckConfig;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * エンチャント運(stat: {@code enchant_luck})による {@link EnchantItemEvent} 結果の補正。
 *
 * <p>横断制約(かまど/エンチャント/ポーションは実行者限定): {@link EnchantItemEvent#getEnchanter()}
 * が確実にエンチャントテーブルを操作した本人であり、近くにいるだけの他プレイヤーには一切影響しない。
 *
 * <p>バニラのエンチャント抽選自体にプレイヤー側の運パラメータは存在しない(本棚数=提示レベルと
 * アイテム固有enchantability、重み付き抽選のみ)ため、これはTrinityForge独自の追加re-rollレイヤー:
 * {@link EnchantItemEvent} で既に確定した {@code enchantsToAdd} に対し、{@code enchant_luck} stat
 * (enchanting.yml A/C/A-alpha-1/A-alpha-2/A-beta-1/A-beta-2 の buffs で蓄積)に応じて
 * <ol>
 *   <li>各エンチャントのレベルを追加で+1格上げ(config-driven確率、最大{@link EnchantLuckConfig#levelBoostMaxSteps()}回)</li>
 *   <li>バニラ上限に達している場合、{@code overenchant:<id>} を解放しているプレイヤーだけ、
 *       そのプロファイルの絶対上限までさらに格上げを試行(解放者ほど出現率が上がる)</li>
 *   <li>確率で、まだ付与されていない互換エンチャントを1つ追加</li>
 * </ol>
 * を行う。重み付けの係数は {@code stats/enchant-luck.yml}(config駆動、ハードコードしない)。
 *
 * <p>{@link EventPriority#LOW} で動く: {@link OverEnchantListener}(HIGH)より確実に先に走るため、
 * このリスナーがバニラ上限を超えて格上げした値も OverEnchantListener の最終クランプで安全な
 * 範囲(プロファイルの絶対上限以下)に収まる — 二重に上限判定を実装する必要はない。
 */
public final class EnchantLuckListener implements Listener {

    private static final String GATE_PREFIX = "overenchant:";
    private static final String ENCHANT_LUCK = StatKeys.canonical("enchant_luck");

    private final PlayerStatAggregator aggregator;
    private final EnchantLuckConfig config;
    private final DedicatedEffectsConfig dedicatedEffects;
    private final CraftingFeaturesConfig craftingFeatures;
    private final Random random;

    public EnchantLuckListener(PlayerStatAggregator aggregator, EnchantLuckConfig config,
                               DedicatedEffectsConfig dedicatedEffects,
                               CraftingFeaturesConfig craftingFeatures) {
        this(aggregator, config, dedicatedEffects, craftingFeatures, new Random());
    }

    /** Test-only constructor with an injectable {@link Random} for deterministic assertions. */
    EnchantLuckListener(PlayerStatAggregator aggregator, EnchantLuckConfig config,
                        DedicatedEffectsConfig dedicatedEffects, CraftingFeaturesConfig craftingFeatures,
                        Random random) {
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.config = Objects.requireNonNull(config, "config");
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.craftingFeatures = Objects.requireNonNull(craftingFeatures, "craftingFeatures");
        this.random = Objects.requireNonNull(random, "random");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Player enchanter = event.getEnchanter();
        if (enchanter == null) return;
        double luck = Math.max(0.0, aggregator.aggregate(enchanter).totalOf(ENCHANT_LUCK));
        if (luck <= 0.0) return;

        Map<Enchantment, Integer> enchants = event.getEnchantsToAdd();
        if (enchants.isEmpty()) return;

        double baseChance = Math.min(1.0, config.levelBoostChancePerLuck() * luck);
        double overChance = Math.min(1.0, config.overenchantBonusChancePerLuck() * luck);
        int maxSteps = config.levelBoostMaxSteps();

        Map<Enchantment, Integer> boosted = new HashMap<>();
        for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
            Enchantment ench = e.getKey();
            int level = e.getValue();
            int vanillaMax = ench.getMaxLevel();
            boolean overUnlocked = isOverenchantUnlocked(enchanter, ench);
            for (int step = 0; step < maxSteps; step++) {
                boolean atVanillaCap = level >= vanillaMax;
                double chance = atVanillaCap ? (overUnlocked ? overChance : 0.0) : baseChance;
                if (chance <= 0.0 || random.nextDouble() >= chance) {
                    break;
                }
                level++;
            }
            if (level != e.getValue()) {
                boosted.put(ench, level);
            }
        }
        boosted.forEach(enchants::put);

        double extraChance = Math.min(1.0, config.extraEnchantChancePerLuck() * luck);
        if (extraChance > 0.0 && random.nextDouble() < extraChance) {
            Enchantment extra = pickCompatibleEnchant(event.getItem(), enchants);
            if (extra != null) {
                enchants.put(extra, 1);
            }
        }
    }

    private boolean isOverenchantUnlocked(Player player, Enchantment ench) {
        return craftingFeatures.overEnchantMaxLevel(
                id -> dedicatedEffects.isActive(player, GATE_PREFIX + id), ench) > ench.getMaxLevel();
    }

    private Enchantment pickCompatibleEnchant(ItemStack item, Map<Enchantment, Integer> already) {
        if (item == null) {
            return null;
        }
        List<Enchantment> candidates = new ArrayList<>();
        for (Enchantment candidate : Registry.ENCHANTMENT) {
            if (already.containsKey(candidate) || !candidate.canEnchantItem(item)) {
                continue;
            }
            boolean conflicts = already.keySet().stream()
                    .anyMatch(existing -> existing.conflictsWith(candidate) || candidate.conflictsWith(existing));
            if (conflicts) {
                continue;
            }
            candidates.add(candidate);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }
}
