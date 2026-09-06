package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.EnchantLuckConfig;
import com.trinityforge.stats.ItemIdentityCopy;
import com.trinityforge.stats.StatKeys;
import io.papermc.paper.registry.keys.tags.EnchantmentTagKeys;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 *   <li>確率で、まだ付与されていない互換エンチャントを1つ追加。
 *       候補は**バニラのエンチャントテーブルで出得るものだけ**({@code #minecraft:in_enchanting_table})に
 *       限る — 詳細と経緯は {@link #enchantingTablePool()}</li>
 * </ol>
 * を行う。重み付けの係数は {@code stats/enchant-luck.yml}(config駆動、ハードコードしない)。
 *
 * <p>運が {@link EnchantLuckConfig#vanillaParityLuck()} 未満のときは格上げせず、
 * 確定レベルを下げる(下限1。エンチャントは消さない)。パリティ以上は従来どおり格上げする。
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
    /** エンチャント台のプレビュー時点の個体。確定時にバニラが PDC を落とす経路への保険。 */
    private final Map<UUID, ItemStack> identitySnapshots = new ConcurrentHashMap<>();

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        if (event.getEnchanter() == null) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) {
            return;
        }
        identitySnapshots.put(event.getEnchanter().getUniqueId(), item.clone());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void restoreIdentityAfterEnchant(EnchantItemEvent event) {
        if (event.getEnchanter() == null) {
            return;
        }
        ItemStack snap = identitySnapshots.remove(event.getEnchanter().getUniqueId());
        ItemIdentityCopy.copyRollQualityCatalog(snap, event.getItem());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Player enchanter = event.getEnchanter();
        if (enchanter == null) return;
        double luck = Math.max(0.0, aggregator.aggregate(enchanter).totalOf(ENCHANT_LUCK));

        Map<Enchantment, Integer> enchants = event.getEnchantsToAdd();
        if (enchants.isEmpty()) return;

        double parity = config.vanillaParityLuck();
        if (parity > 0.0 && luck < parity) {
            applyNerfs(enchants, luck, parity);
            return;
        }
        if (luck <= 0.0) return;

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
            Enchantment extra = pickCompatibleEnchant(
                    enchantingTablePool(), event.getItem(), enchants, random);
            if (extra != null) {
                enchants.put(extra, 1);
            }
        }
    }

    /**
     * 運がパリティ未満のときの弱体化。確率 = {@code (1 - luck/parity) * chanceAtZero}。
     * レベル1未満には下げない(付与そのものは消さない)。
     */
    private void applyNerfs(Map<Enchantment, Integer> enchants, double luck, double parity) {
        double atZero = config.levelNerfChanceAtZero();
        int maxSteps = config.levelNerfMaxSteps();
        if (atZero <= 0.0 || maxSteps <= 0) {
            return;
        }
        double chance = Math.min(1.0, atZero * (1.0 - luck / parity));
        if (chance <= 0.0) {
            return;
        }
        Map<Enchantment, Integer> nerfed = new HashMap<>();
        for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
            int level = e.getValue();
            for (int step = 0; step < maxSteps && level > 1; step++) {
                if (random.nextDouble() >= chance) {
                    break;
                }
                level--;
            }
            if (level != e.getValue()) {
                nerfed.put(e.getKey(), level);
            }
        }
        nerfed.forEach(enchants::put);
    }

    private boolean isOverenchantUnlocked(Player player, Enchantment ench) {
        return craftingFeatures.overEnchantMaxLevel(
                id -> dedicatedEffects.isActive(player, GATE_PREFIX + id), ench) > ench.getMaxLevel();
    }

    /**
     * 追加抽選の母集団(= 「バニラのエンチャントテーブルで出得るもの」)を解決する。
     *
     * <p>これは飾りではなくバグ修正の本体: 以前はここが {@link Registry#ENCHANTMENT} の全走査で、
     * フィルタが {@code canEnchantItem} と {@code conflictsWith} の2つしか無かったため、
     * テーブルの抽選対象外である treasure 系(修繕 / 氷結歩行 / 魂の速さ / 忍び歩き /
     * 束縛の呪い / 消滅の呪い / 風の衝撃)が追加抽選で出ていた。
     *
     * <p>バニラでは「テーブルに出得るか」は {@code #minecraft:in_enchanting_table} タグで定義されている
     * ので、Paper のタグ API ({@link Registry#getTagValues}) で**タグを引くのが第一経路**。
     * ハードコードした除外リストと違い、バニラ側でエンチャントが増減しても自動で追随する。
     *
     * <p>第二経路は {@link Enchantment#isTreasure()}。タグ API を実装していない環境
     * (MockBukkit 4.110.0 は {@code Registry#hasTag} / {@code getTagValues} がどちらも
     * {@code UnimplementedOperationException} を投げる)向けのフォールバック。
     * {@code isTreasure()} は 1.21 で「タグで管理するようになった」として非推奨になっているが、
     * 契約は「looting / 交易 / 釣りでしか手に入らない = テーブルでは出ない」なので、
     * テーブル抽選の母集団としては同じ意味で使える。
     * ※ {@code isDiscoverable()} は javadoc に「エンチャントテーブルで見つかるか」と書いてあるが
     * 実際には {@code #minecraft:on_random_loot} 相当で、修繕が {@code true} を返す。使ってはいけない。
     */
    @SuppressWarnings("deprecation") // isTreasure(): タグ API が無い環境向けのフォールバックとしてのみ使う
    static Iterable<Enchantment> enchantingTablePool() {
        try {
            if (Registry.ENCHANTMENT.hasTag(EnchantmentTagKeys.IN_ENCHANTING_TABLE)) {
                Collection<Enchantment> tagged =
                        Registry.ENCHANTMENT.getTagValues(EnchantmentTagKeys.IN_ENCHANTING_TABLE);
                if (!tagged.isEmpty()) {
                    return tagged;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // タグ API 未実装/未ロードの環境。下の isTreasure() 経路へ落ちる。
            // ここで全走査へ fail-open してはいけない(それが元のバグそのもの)。
        }
        List<Enchantment> pool = new ArrayList<>();
        for (Enchantment candidate : Registry.ENCHANTMENT) {
            if (!candidate.isTreasure()) {
                pool.add(candidate);
            }
        }
        return pool;
    }

    /**
     * {@code pool} から、{@code item} に付けられて既存エンチャントと衝突しないものを1つ等確率で選ぶ。
     *
     * <p>母集団を引数で受けるのはテスト用の seam: MockBukkit がタグ API を未実装なので、
     * タグ経路の母集団はテストから直接渡す(でないとタグ解決の時点で
     * {@code UnimplementedOperationException} → SKIPPED に化けてテストが素通りする)。
     */
    static Enchantment pickCompatibleEnchant(Iterable<Enchantment> pool, ItemStack item,
                                             Map<Enchantment, Integer> already, Random random) {
        if (item == null) {
            return null;
        }
        List<Enchantment> candidates = new ArrayList<>();
        for (Enchantment candidate : pool) {
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
