package com.trinityforge.gathering;

import com.trinityforge.active.ActivationDispatcher;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.GatheringEfficiencyConfig;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.stats.StatKeys;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime mirror of the {@code gathering-efficiency} stat onto a real vanilla Efficiency enchant level
 * on the player's mainhand tool (2026-07-25 採集効率エンチャント連動方式). Replaces a withdrawn
 * vanilla-Attribute implementation ({@code mining-efficiency}/{@code mining-speed-bonus} ->
 * {@code Attribute.MINING_EFFICIENCY}/{@code Attribute.BLOCK_BREAK_SPEED}) that does not work on
 * Bedrock/Geyser (GeyserMC/Geyser#6266): those attributes do not exist client-side and are not part of
 * Geyser's break-time calculation, so Bedrock players saw the tool flicker between vanilla and boosted
 * speed. A real Efficiency enchant level IS honoured by Geyser.
 *
 * <p><b>合算元 vs 適用先</b>: {@code gathering-efficiency} は総合ステータスとして装備/防具/装飾品/パーク/
 * base-stats等から通常通り合算される({@link PlayerStatAggregator#aggregate}経由)。適用先だけがメインハンド
 * 1個に限定される: そのアイテムの {@code use-skill}(既存の {@link ActivationDispatcher#mainHandUseSkill}
 * を再利用して解決)が FARMING/MINING/WOODCUTTING/DIGGING のいずれかのときだけ、合算値を
 * {@link GatheringEfficiencyMath#resolveLevel} で floor+クランプしたレベル分だけ Efficiency を底上げする。
 * バニラの未刻印ツール(TFのPDCスタンプが無い素のダイヤのツルハシ等)は {@code use-skill} を解決できないため
 * 対象外になる — Materialベースの独自フォールバックは意図的に実装していない。
 *
 * <p><b>exploit防止(付与量のPDC記録による厳密な差し引き)</b>: TFが足したレベル数を
 * {@link PdcKeys#ITEM_GATHERING_EFFICIENCY_APPLIED} に記録し、除去は必ずこの記録値ぶんだけ差し引く
 * (現在のエンチャントレベルから機械的に引かない)。これにより金床由来の正規のレベルを一切巻き込まない
 * (2026-07-26 ユーザー決定の効率ステータス統合により、旧 {@code tool-enchant-efficiency}
 * (クラフト時刻印)は {@link com.trinityforge.stats.StatKeys} のエイリアスで {@code gathering-efficiency}
 * へ読み替えられ、この総合ステータス経路に一本化された — {@link com.trinityforge.stats.ItemAssembler} は
 * もうそのキーを {@code tool-enchant-*} として焼き込まない。既存アイテムに残っていた旧クラフト刻印値は
 * 次回の {@code ItemAssembler.assemble} 実行(reload/refresh)時に {@code ItemAssembler} 自身の
 * 置換ベースの掃除ロジックで自動的に剥がれ、この経路の動的な差分適用に完全移行する)。あらゆる離脱経路(持ち替え/装備変更/インベントリを
 * 開く/ログアウト/死亡/ドロップ/プラグイン無効化)で確実に除去し、恒久的なエンチャント焼き付き
 * (=実質的なアイテム増殖/強化exploit)を防ぐ。特にインベントリ(金床/砥石/チェスト/作業台等)を開いた瞬間は
 * プレイヤーの全スロットを素の状態に戻し、閉じたら再適用する — 装備中のブースト済みアイテムがそのまま
 * 金床の素材になって恒久的に焼き付くことを防ぐ({@link #onInventoryOpen}が{@link #stripAllSlots}を使う
 * 唯一の理由。バックパック内の非メインハンドスロットに残ったマーカーも同時に掃除する)。
 */
public final class GatheringEfficiencyEnchantApplier implements Listener {

    private static final Set<String> TARGET_SKILLS = Set.of("FARMING", "MINING", "WOODCUTTING", "DIGGING");
    private static final String STAT_KEY = StatKeys.canonical("gathering-efficiency");
    private static final Enchantment EFFICIENCY = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("efficiency"));

    private final Plugin plugin;
    private final PlayerStatAggregator aggregator;
    private final GatheringEfficiencyConfig config;

    /** tick単位デバウンス(PerkAttributeApplierと同じ流儀): 同一tick内の複数装備変化を1回にまとめる。 */
    private final Map<UUID, Integer> lastAppliedTick = new ConcurrentHashMap<>();

    /**
     * エンチャントレベルの上限は {@code config}({@code stats/gathering-efficiency.yml} の
     * {@code max-enchant-level})が唯一の設定箇所。2026-08-05 に {@code combat/stat-caps.yml} の
     * {@code gathering-efficiency-max-enchant-level} 上書き経路(と、それを渡していた4引数版
     * コンストラクタ)をユーザー決定で削除した。
     */
    public GatheringEfficiencyEnchantApplier(Plugin plugin, PlayerStatAggregator aggregator,
                                             GatheringEfficiencyConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.config = Objects.requireNonNull(config, "config");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // クラッシュ耐性(危険な点4): PDC記録の残ったアイテムから、現在のメインハンド判定を通して
        // 付与分を再計算・剥がす復旧処理を兼ねる(reconcileFull自体が全スロット走査のため)。
        reconcileFull(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        lastAppliedTick.remove(player.getUniqueId());
        // 危険な点3(離脱経路): ログアウトでメインハンドの付与分を剥がす。
        stripSlotIfMarked(player.getInventory(), player.getInventory().getHeldItemSlot());
    }

    @EventHandler
    public void onEquipmentChanged(EntityEquipmentChangedEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        debounceReconcile(player);
    }

    /** 危険な点3: インベントリ(金床/砥石/チェスト/作業台等)を開いた瞬間は必ず素の状態に戻す。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        stripAllSlots(player);
    }

    /** 閉じたら再適用で構わない(要件どおり)。 */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> reconcileFull(player));
    }

    /** 危険な点3: ドロップしたアイテムのスタックにも付与分が残らないようにする。 */
    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        ItemStack stripped = withDelta(dropped, 0);
        if (stripped != null) {
            event.getItemDrop().setItemStack(stripped);
        }
    }

    /** 危険な点3: 死亡ドロップにも付与分が残らないようにする。 */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        List<ItemStack> drops = event.getDrops();
        for (int i = 0; i < drops.size(); i++) {
            ItemStack stripped = withDelta(drops.get(i), 0);
            if (stripped != null) {
                drops.set(i, stripped);
            }
        }
    }

    private void debounceReconcile(Player player) {
        int currentTick = plugin.getServer().getCurrentTick();
        UUID id = player.getUniqueId();
        Integer last = lastAppliedTick.get(id);
        if (last != null && last == currentTick) {
            return;
        }
        lastAppliedTick.put(id, currentTick);
        reconcileFull(player);
    }

    /**
     * メインハンドの付与を再計算・適用し、それ以外の全スロット(防具/収納/オフハンド)に残った
     * マーカーは剥がす(危険な点3: 持ち替え/装備変更で外れたアイテムに付与分が残らないようにする安全網。
     * 危険な点4: 復旧処理としても兼用)。
     */
    public void reconcileFull(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        int mainhandSlot = inv.getHeldItemSlot();
        applyToMainhand(player);
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (slot == mainhandSlot) {
                continue;
            }
            stripSlotIfMarked(inv, slot);
        }
    }

    public void applyAllOnline() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            reconcileFull(player);
        }
    }

    /** プラグイン無効化時の安全網(危険な点3): 全オンラインプレイヤーの付与分を剥がす。 */
    public void stripAllOnline() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            stripAllSlots(player);
        }
    }

    private void stripAllSlots(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            stripSlotIfMarked(inv, slot);
        }
    }

    private void applyToMainhand(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack mainhand = inv.getItemInMainHand();
        int desired = desiredLevelFor(player, mainhand);
        ItemStack updated = withDelta(mainhand, desired);
        if (updated != null) {
            inv.setItemInMainHand(updated);
        }
    }

    private int desiredLevelFor(Player player, ItemStack mainhand) {
        if (mainhand == null || mainhand.getType().isAir()) {
            return 0;
        }
        String useSkill = ActivationDispatcher.mainHandUseSkill(mainhand);
        if (useSkill == null || !TARGET_SKILLS.contains(useSkill)) {
            return 0;
        }
        double total = aggregator.aggregate(player, mainhand).totalOf(STAT_KEY);
        // 上限は stats/gathering-efficiency.yml の max-enchant-level 一本(0以下=無制限)。
        return GatheringEfficiencyMath.resolveLevel(total, config.maxEnchantLevel());
    }

    private void stripSlotIfMarked(PlayerInventory inv, int slot) {
        ItemStack stack = inv.getItem(slot);
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return;
        }
        int recorded = recordedDelta(stack.getItemMeta());
        if (recorded <= 0) {
            return;
        }
        ItemStack updated = withDelta(stack, 0);
        if (updated != null) {
            inv.setItem(slot, updated);
        }
    }

    /**
     * 冪等な差分適用(危険な点1・2): TFの記録値(PDC)ぶんだけを baseLevel(=現在レベル-記録値、下限0)へ
     * 差し引き、そのうえで新しい {@code desiredDelta} を足し直す。記録値と目標値が既に一致していれば
     * 何もしない({@code null} を返す)。金床由来の正規のレベル(または {@code ItemAssembler} が未reload
     * のアイテムに残す旧 {@code tool-enchant-efficiency} 刻印。統合後は {@code ItemAssembler} の次回
     * assemble 時に自動で剥がれる、上のクラスjavadoc参照)は baseLevel として温存されるので巻き込まない。
     *
     * @return 変更後のスタック(呼び出し側が書き戻す必要がある)、または変更不要なら {@code null}
     */
    private ItemStack withDelta(ItemStack stack, int desiredDelta) {
        if (stack == null || stack.getType().isAir() || EFFICIENCY == null) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int recorded = recordedDelta(meta);
        int currentLevel = meta.hasEnchant(EFFICIENCY) ? meta.getEnchantLevel(EFFICIENCY) : 0;
        int baseLevel = Math.max(0, currentLevel - recorded);
        int clampedDesired = Math.max(0, desiredDelta);

        if (recorded == clampedDesired && currentLevel == baseLevel + recorded) {
            return null; // 冪等: 既に正しい状態なので何もしない。
        }

        int newTotal = baseLevel + clampedDesired;
        if (newTotal >= 1) {
            meta.addEnchant(EFFICIENCY, newTotal, true);
        } else {
            meta.removeEnchant(EFFICIENCY);
        }
        if (clampedDesired > 0) {
            pdc.set(PdcKeys.ITEM_GATHERING_EFFICIENCY_APPLIED, PersistentDataType.INTEGER, clampedDesired);
        } else {
            pdc.remove(PdcKeys.ITEM_GATHERING_EFFICIENCY_APPLIED);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static int recordedDelta(ItemMeta meta) {
        return meta.getPersistentDataContainer()
                .getOrDefault(PdcKeys.ITEM_GATHERING_EFFICIENCY_APPLIED, PersistentDataType.INTEGER, 0);
    }
}
