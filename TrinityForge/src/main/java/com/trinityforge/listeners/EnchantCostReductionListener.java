package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.EnchantBookshelfConfig;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * エンチャント費用軽減(stat: {@code enchant_cost_reduction}) + 本棚パワーconfig化(2026-07-26相乗り追加)。
 *
 * <p>2箇所に適用する(タスク仕様):
 * <ol>
 *   <li>エンチャントテーブル: {@link PrepareItemEnchantEvent}(GUIに表示される提示コスト、3ボタン分)と
 *       {@link EnchantItemEvent}(実際にプレイヤーへ課金される経験値レベルコスト)の両方。表示と実消費が
 *       食い違わないよう同じ軽減率を両イベントへ適用する。</li>
 *   <li>金床: {@link PrepareAnvilEvent} の修理コスト({@link org.bukkit.inventory.AnvilInventory#getRepairCost()})。
 *       「高すぎる！」で弾かれるケースの緩和にもなる。{@link EventPriority#HIGHEST} で動作させ、
 *       {@code CatalogAnvilListener}/{@code WoodRepairListener}/{@code OverEnchantListener} 等の
 *       HIGH以下の優先度で確定した最終コストへ、後から割合を掛ける(先に読むと他リスナーの上書きを
 *       取りこぼす)。</li>
 * </ol>
 *
 * <p>0.0〜{@link #MAX_REDUCTION} にクランプし、軽減後も {@link #MIN_LEVEL_COST} レベルを下回らないよう
 * ガードする(0コストはエンチャント/修理の無限循環という経済破壊を招くため)。
 *
 * <p><b>本棚パワーconfig化(2026-07-26)</b>: {@link EnchantBookshelfConfig}(crafting-features.yml
 * {@code enchant-bookshelf-power} サブツリー)で、バニラが本棚パワーとして考慮する上限
 * ({@link EnchantBookshelfConfig#VANILLA_MAX_BOOKSHELVES} = 15、Bukkitの
 * {@link PrepareItemEnchantEvent#getEnchantmentBonus()} が返す実際の本棚数に対して、バニラの内部式は
 * これを超える分を切り捨てる)と、本棚1個あたりのパワー係数(バニラは暗黙に1.0固定)を調整可能にする。
 * 既定値(15, 1.0)では実効パワーが常にバニラの実効パワーと一致するため、
 * {@link #bookshelfRatio} は常に1.0を返し、このリスナーは一切書き換えを行わない(挙動不変)。
 *
 * <p>この既存の {@code enchant_cost_reduction} 適用へ相乗りした理由: 同じ2イベント
 * ({@link PrepareItemEnchantEvent}/{@link EnchantItemEvent})に対する「オファーの数値を書き換える」処理を
 * 専用リスナーとして別に登録すると、実行順序次第で二重適用(本棚パワー由来の倍率とコスト軽減率の
 * 適用順序次第で結果が変わる)や食い違いのリスクが増える。ここで一箇所にまとめ、
 * 「本棚パワー補正 → コスト軽減」の順に確定的に適用する。
 *
 * <p><b>本棚数と実際の付与エンチャントの整合について</b>: {@link PrepareItemEnchantEvent} は
 * {@link EnchantItemEvent#getEnchantsToAdd()}(実際に付与される内容)とは独立にバニラが再計算するため、
 * 本棚パワー由来の倍率は {@link PrepareItemEnchantEvent#getEnchantmentBonus()} からその場でしか取得できない
 * ({@link EnchantItemEvent} 側には本棚数の取得手段がない)。そのためプレイヤー単位で直前の
 * {@code onPrepare} 呼び出し時点の倍率を {@link #lastBookshelfRatioByPlayer} に一時保持し、
 * 直後の {@code onEnchant} で消費(取り出し後に削除)する。エンチャントテーブルGUIでは
 * アイテム挿入/変更のたびに必ず {@link PrepareItemEnchantEvent} が先に発火するため、
 * 通常操作では常にキャッシュがフレッシュな状態で消費される。
 */
public final class EnchantCostReductionListener implements Listener {

    /** 軽減率の上限。1.0(全額無料)は経済破壊のため意図的に頭打ちする。 */
    static final double MAX_REDUCTION = 0.9;
    /** 軽減後も残す最低コスト(レベル)。0まで軽減すると無限エンチャント/修理になる。 */
    static final int MIN_LEVEL_COST = 1;
    /**
     * バニラ金床の「コストが高すぎます」(既定40)を外す上限。
     * {@link Integer#MAX_VALUE} は一部クライアントで表示が壊れるので、実運用で届かない大きさにする。
     */
    static final int UNCAPPED_ANVIL_REPAIR_COST = 10_000;

    private static final String ENCHANT_COST_REDUCTION = StatKeys.canonical("enchant_cost_reduction");

    private final PlayerStatAggregator aggregator;
    /** null許容: 本棚パワーconfig未注入(テスト等)の場合は常にバニラ同一(比率1.0)として扱う。 */
    private final EnchantBookshelfConfig bookshelfConfig;
    /** プレイヤーUUID → 直前の {@code onPrepare} で算出した本棚パワー比率。onEnchant消費後に削除する。 */
    private final Map<UUID, Double> lastBookshelfRatioByPlayer = new ConcurrentHashMap<>();
    /** 直前のprepareで提示costまたはhint levelを変更したプレイヤー。クリック時のhint整合判定に使う。 */
    private final Set<UUID> adjustedOfferPlayers = ConcurrentHashMap.newKeySet();
    /**
     * クライアントへ 39 を出しているあいだの、取り出しで実際に課金するレベル。
     * {@code AnvilScreen} は 40 以上を「コストが高すぎます」に決め打ちするので、表示だけ落とす。
     */
    private final Map<UUID, Integer> pendingAnvilCharge = new ConcurrentHashMap<>();

    public EnchantCostReductionListener(PlayerStatAggregator aggregator) {
        this(aggregator, null);
    }

    public EnchantCostReductionListener(PlayerStatAggregator aggregator, EnchantBookshelfConfig bookshelfConfig) {
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.bookshelfConfig = bookshelfConfig;
    }

    /** エンチャントテーブルの提示コスト(GUIの3ボタン)を、本棚パワー補正→費用軽減の順で書き換える。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPrepare(PrepareItemEnchantEvent event) {
        Player enchanter = event.getEnchanter();
        double ratio = bookshelfRatio(event.getEnchantmentBonus());
        if (enchanter != null) {
            // 比率1.0(既定config、または本棚0個)のときは意図的に何も記録しない —
            // onEnchant側は「キャッシュ無し = 1.0」を既定とするため、記録を省いても安全であり、
            // 前回訪問(別アイテムで比率≠1.0だった場合)の値が誤って残り続けることを防げる。
            if (ratio == 1.0) {
                lastBookshelfRatioByPlayer.remove(enchanter.getUniqueId());
            } else {
                lastBookshelfRatioByPlayer.put(enchanter.getUniqueId(), ratio);
            }
        }
        double reduction = reductionOf(enchanter);
        if (ratio == 1.0 && reduction <= 0.0) {
            if (enchanter != null) {
                adjustedOfferPlayers.remove(enchanter.getUniqueId());
            }
            return;
        }
        boolean adjusted = false;
        for (EnchantmentOffer offer : event.getOffers()) {
            if (offer == null) continue;
            int cost = offer.getCost();
            int level = offer.getEnchantmentLevel();
            int originalCost = cost;
            int originalLevel = level;
            Enchantment ench = offer.getEnchantment();
            if (ratio != 1.0) {
                cost = rescaledCost(cost, ratio);
                if (ench != null) {
                    level = rescaledLevel(level, ratio, ench.getMaxLevel());
                }
            }
            if (reduction > 0.0) {
                cost = reducedCost(cost, reduction);
            }
            offer.setCost(cost);
            if (ench != null && level != offer.getEnchantmentLevel()) {
                offer.setEnchantmentLevel(level);
            }
            adjusted |= cost != originalCost || level != originalLevel;
        }
        if (enchanter != null) {
            if (adjusted) {
                adjustedOfferPlayers.add(enchanter.getUniqueId());
            } else {
                adjustedOfferPlayers.remove(enchanter.getUniqueId());
            }
        }
    }

    /**
     * {@link PrepareItemEnchantEvent} で offer cost を変更すると、Paper はクリック時に変更後costで
     * 付与一覧を再抽選する一方、GUIに送った hint は prepare 時のまま保持する。その再抽選結果から
     * hint が外れた場合、表示と実付与が食い違うため、他の付与補正より先にhintを実付与集合へ戻す。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEnchantHint(EnchantItemEvent event) {
        Player enchanter = event.getEnchanter();
        if (enchanter == null || !adjustedOfferPlayers.contains(enchanter.getUniqueId())) {
            return;
        }
        alignDisplayedHint(event.getEnchantsToAdd(), event.getEnchantmentHint(), event.getLevelHint());
    }

    /** エンチャントテーブルで実際に消費される経験値レベルコストと付与レベルを、同じ比率で追随させる。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Player enchanter = event.getEnchanter();
        double ratio = enchanter == null ? 1.0
                : lastBookshelfRatioByPlayer.getOrDefault(enchanter.getUniqueId(), 1.0);
        if (enchanter != null) {
            lastBookshelfRatioByPlayer.remove(enchanter.getUniqueId());
            adjustedOfferPlayers.remove(enchanter.getUniqueId());
        }
        double reduction = reductionOf(enchanter);
        if (ratio == 1.0 && reduction <= 0.0) return;

        int cost = event.getExpLevelCost();
        if (ratio != 1.0) {
            cost = rescaledCost(cost, ratio);
        }
        if (reduction > 0.0) {
            cost = reducedCost(cost, reduction);
        }
        event.setExpLevelCost(cost);

        if (ratio != 1.0) {
            Map<Enchantment, Integer> toAdd = event.getEnchantsToAdd();
            toAdd.replaceAll((ench, level) -> rescaledLevel(level, ratio, ench.getMaxLevel()));
            // onPrepareで既に表示hint levelもratio補正済み。再抽選Mapの全要素へratioを掛けた後、
            // 表示済みhintまで二重補正で下がらないよう、最終集合でも表示levelを最低保証する。
            alignDisplayedHint(toAdd, event.getEnchantmentHint(), event.getLevelHint());
        }
    }

    /**
     * Paperが変更後offer costから再抽選した付与集合を、プレイヤーへ表示済みのhintと整合させる。
     * hintと競合する再抽選エンチャントを残すと、通常は成立しない競合組合せを生成するため除去する。
     */
    static void alignDisplayedHint(Map<Enchantment, Integer> enchants,
                                   Enchantment displayedHint, int displayedLevel) {
        if (enchants == null || displayedHint == null || displayedLevel <= 0) {
            return;
        }
        enchants.keySet().removeIf(existing -> existing != null
                && !existing.equals(displayedHint)
                && (existing.conflictsWith(displayedHint) || displayedHint.conflictsWith(existing)));
        enchants.merge(displayedHint, displayedLevel, Math::max);
    }

    /** 金床の修理/合成コストを軽減する。他リスナーが確定させた最終コストへ後から適用する。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvil(PrepareAnvilEvent event) {
        uncapAnvil(event);
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        if (event.getResult() == null) return; // 有効な修理/リネーム/合成が確定していない
        double reduction = reductionOf(player);
        if (reduction <= 0.0) return;
        int cost = event.getInventory().getRepairCost();
        if (cost <= 0) return;
        event.getInventory().setRepairCost(reducedCost(cost, reduction));
        if (event.getView() instanceof AnvilView anvilView) {
            anvilView.setRepairCost(reducedCost(cost, reduction));
        }
    }

    /**
     * 金床を開いた瞬間にバニラ上限40を外す。Prepare より前にセットしないと、
     * バニラが既に結果を null にしたあとに上限だけ上げても「コストが高すぎます」が残る。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnvilOpen(InventoryOpenEvent event) {
        if (event.getView() instanceof AnvilView anvilView) {
            anvilView.setMaximumRepairCost(UNCAPPED_ANVIL_REPAIR_COST);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAnvilUncap(PrepareAnvilEvent event) {
        uncapAnvil(event);
    }

    private static void uncapAnvil(PrepareAnvilEvent event) {
        uncapAnvilView(event.getView() instanceof AnvilView anvilView ? anvilView : null,
                event.getInventory());
    }

    private static void uncapAnvilView(AnvilView anvilView, Inventory inventory) {
        if (anvilView != null) {
            anvilView.setMaximumRepairCost(UNCAPPED_ANVIL_REPAIR_COST);
        }
        if (inventory instanceof AnvilInventory anvilInventory) {
            anvilInventory.setMaximumRepairCost(UNCAPPED_ANVIL_REPAIR_COST);
        }
    }

    /**
     * 実コストはイベント中に触らない。Paper はハンドラのあと {@code cost >= maximumRepairCost}
     * （既定40）なら結果を空にするので、ここで 39 に落とすと再計算後の実コストと比較されて
     * リザルトが消える。次tickで上限を掛け直し、空なら結果を戻してから表示だけ 39 にする。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvilClientDisplay(PrepareAnvilEvent event) {
        if (!(event.getView() instanceof AnvilView anvilView)) {
            return;
        }
        if (!(anvilView.getPlayer() instanceof Player player)) {
            return;
        }
        UUID id = player.getUniqueId();
        ItemStack preview = event.getResult();
        if (player.getGameMode() == GameMode.CREATIVE || preview == null || preview.getType().isAir()) {
            pendingAnvilCharge.remove(id);
            return;
        }
        int realCost = anvilView.getRepairCost();
        if (!AnvilClientCost.needsDisplayCap(realCost)) {
            pendingAnvilCharge.remove(id);
            return;
        }
        pendingAnvilCharge.put(id, realCost);
        ItemStack snapshot = preview.clone();
        Inventory inventory = event.getInventory();
        Runnable apply = () -> applyAnvilClientDisplay(player, anvilView, inventory, realCost, snapshot);
        Plugin plugin = hostingPlugin();
        if (plugin == null) {
            apply.run();
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, apply);
    }

    /**
     * テストはプラグインクラスローダ外なので即時適用。本番は {@code TrinityForge} の次tick。
     */
    static Plugin hostingPlugin() {
        try {
            return JavaPlugin.getProvidingPlugin(EnchantCostReductionListener.class);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            if (Bukkit.getPluginManager() == null) {
                return null;
            }
            return Bukkit.getPluginManager().getPlugin("TrinityForge");
        }
    }

    private void applyAnvilClientDisplay(Player player, AnvilView anvilView, Inventory inventory,
                                         int realCost, ItemStack resultSnapshot) {
        if (player == null || anvilView == null || inventory == null) {
            return;
        }
        Plugin plugin = hostingPlugin();
        if (plugin != null && (!(player.getOpenInventory() instanceof AnvilView open) || open != anvilView)) {
            return;
        }
        uncapAnvilView(anvilView, inventory);
        if (isEmpty(inventory.getItem(2)) && resultSnapshot != null && !resultSnapshot.getType().isAir()) {
            inventory.setItem(2, resultSnapshot.clone());
        }
        int displayed = AnvilClientCost.displayed(realCost);
        if (inventory instanceof AnvilInventory anvilInventory) {
            anvilInventory.setRepairCost(displayed);
        }
        anvilView.setRepairCost(displayed);
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }

    /**
     * 取り出し直前に実コストを戻す。表示を 39 のままにするとバニラが 39 しか引かない。
     * レベルが実コストに足りないときは、クライアントが 39 で通してしまうのでここで止める。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvilResultTake(InventoryClickEvent event) {
        if (event.getRawSlot() != 2) {
            return;
        }
        if (!(event.getView() instanceof AnvilView anvilView)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Integer realCost = pendingAnvilCharge.get(player.getUniqueId());
        if (realCost == null) {
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE && player.getLevel() < realCost) {
            event.setCancelled(true);
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                    "レベルが足りません(必要 " + realCost + ")",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        anvilView.setRepairCost(realCost);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnvilClose(InventoryCloseEvent event) {
        if (event.getView() instanceof AnvilView) {
            pendingAnvilCharge.remove(event.getPlayer().getUniqueId());
        }
    }

    /**
     * プレイヤー退出時に {@link #lastBookshelfRatioByPlayer} の残留エントリを掃除する。
     * エンチャント台を開いて {@code onPrepare} が走った(≠1.0のときのみ記録される)あと、
     * 実際にエンチャントせず({@code onEnchant} 未発火のまま)ログアウトした場合、
     * このMapにそのプレイヤーのエントリが残り続けてしまう(1人あたりDouble1個で実害は小さいが、
     * 長期稼働で単調増加する)ためのクリーンアップ。既定config(比率が常に1.0)では
     * {@link #onPrepare} が何も記録しないため、この修正は既定挙動を変えない。
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastBookshelfRatioByPlayer.remove(event.getPlayer().getUniqueId());
        adjustedOfferPlayers.remove(event.getPlayer().getUniqueId());
        pendingAnvilCharge.remove(event.getPlayer().getUniqueId());
    }

    private double reductionOf(Player player) {
        if (player == null) return 0.0;
        double raw = aggregator.aggregate(player).totalOf(ENCHANT_COST_REDUCTION);
        return Math.max(0.0, Math.min(MAX_REDUCTION, raw));
    }

    /** {@code cost} を {@code reduction}(0..{@link #MAX_REDUCTION})だけ軽減し、最低{@link #MIN_LEVEL_COST}を保証する。 */
    static int reducedCost(int cost, double reduction) {
        if (cost <= 0) return cost;
        int reduced = (int) Math.round(cost * (1.0 - reduction));
        return Math.max(MIN_LEVEL_COST, reduced);
    }

    /**
     * {@link EnchantBookshelfConfig} の設定値から、バニラ実効パワーに対する補正比率を算出する。
     * {@code bookshelfConfig} が {@code null}(未注入)、または {@code rawBonus <= 0}(本棚0個)のときは
     * 常に1.0(補正なし)を返す。
     *
     * <p>既定config(max=15, coefficient=1.0)では、{@code effective = min(rawBonus,15) * 1.0} が常に
     * {@code vanilla = min(rawBonus,15)} と一致するため、この関数は常に1.0を返す(挙動不変の根拠)。
     */
    double bookshelfRatio(int rawBonus) {
        if (bookshelfConfig == null || rawBonus <= 0) return 1.0;
        return computeBookshelfRatio(rawBonus, bookshelfConfig.maxBookshelves(), bookshelfConfig.powerPerBookshelf());
    }

    /**
     * 純粋関数版(config層に依存しないテスト用)。
     * {@code vanillaPower = min(rawBonus, 15)}、{@code effectivePower = min(rawBonus, maxBookshelves) * powerPerBookshelf}
     * とし、{@code effectivePower / vanillaPower} を返す。{@code vanillaPower <= 0} のときは1.0(補正なし)。
     */
    static double computeBookshelfRatio(int rawBonus, int maxBookshelves, double powerPerBookshelf) {
        int clampedRaw = Math.max(0, rawBonus);
        int vanillaPower = Math.min(clampedRaw, EnchantBookshelfConfig.VANILLA_MAX_BOOKSHELVES);
        if (vanillaPower <= 0) return 1.0;
        double effectivePower = Math.min(clampedRaw, Math.max(0, maxBookshelves)) * Math.max(0.0, powerPerBookshelf);
        return effectivePower / vanillaPower;
    }

    /** {@code cost} を本棚パワー比率 {@code ratio} で再スケールし、最低{@link #MIN_LEVEL_COST}を保証する。 */
    static int rescaledCost(int cost, double ratio) {
        if (cost <= 0 || ratio == 1.0) return cost;
        int scaled = (int) Math.round(cost * ratio);
        return Math.max(MIN_LEVEL_COST, scaled);
    }

    /**
     * エンチャントレベルを本棚パワー比率 {@code ratio} で再スケールする。{@code vanillaMaxLevel} で
     * 上限をクランプし(バニラの通常上限を超えない — 上限突破は {@code OverEnchantListener} の
     * 専管範囲であり、ここでは踏み込まない)、下限は1にクランプする。
     */
    static int rescaledLevel(int level, double ratio, int vanillaMaxLevel) {
        if (level <= 0 || ratio == 1.0) return level;
        int scaled = (int) Math.round(level * ratio);
        scaled = Math.max(1, scaled);
        if (vanillaMaxLevel > 0) {
            scaled = Math.min(scaled, vanillaMaxLevel);
        }
        return scaled;
    }
}
