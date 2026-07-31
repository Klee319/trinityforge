package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewUnlockGroup;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.stats.BrewRecipeSupport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Gated custom brew results ({@code brew-unlocks} in crafting-features.yml), gated by
 * {@code brew:<groupId>} (2026-07-23 動的ID方式改修 §3): unlike a recipe gate, an unreferenced group stays
 * <b>locked</b> — no node placing {@code brew:<groupId>} means {@code isActive} naturally returns
 * {@code false} for every player, so no explicit "unreferenced -&gt; open" branch is needed here.
 *
 * <h2>役割分担 (2026-07-31 D10 = K-13 の修正後)</h2>
 * <ul>
 *   <li><b>「素材を上段に置ける / 醸造が始まる」を成立させるのは
 *       {@link com.trinityforge.stats.BrewPotionMixRegistrar}</b>(Paper の customMixes へ登録)。
 *       それまでこのリスナーが {@code stand.setBrewingTime(400)} で押し込んでいたが、
 *       (a) 素材がスロットに入らないので到達しない、(b) 到達しても {@code isBrewable()} が false なので
 *       {@code serverTick} が次tickで 0 に戻す、(c) NMS の {@code ingredient} フィールドを更新しない、
 *       の三重で<b>原理的に効いていなかった</b>(しかも燃料を減らさない)。強制開始の実装は削除済み。</li>
 *   <li><b>このリスナーは「解放判定」と「結果の差し替え」だけを担う。</b></li>
 * </ul>
 *
 * <h2>未解放プレイヤーの扱い: 投入自体を弾く</h2>
 * mix を登録すると素材は<b>誰でも</b>上段に置けて醸造も始まる。完成時に {@code BrewEvent} を
 * キャンセルするだけだと、{@code doBrew} は素材を {@code shrink} せず即 return し、
 * {@code brewTime} は既に 0 なので次tickで {@code brewable && fuel>0} から再開する
 * → <b>20秒ごとにブレイズパウダーを1個燃やし続ける</b>({@code BrewingStartEvent} は
 * {@code Cancellable} ではないのでイベントで止められない)。そこで
 * {@link #onBrewerClick} / {@link #onBrewerDrag} / {@link #onHopperMove} で<b>投入自体をキャンセル</b>する。
 *
 * <p><b>⚠️ ホッパー自動化への影響</b>: 未解放(または近くに解放者が居ない)状態でホッパーが
 * ゲート付き素材を送り込もうとすると<b>投入が拒否され、素材はスタンド手前で詰まる</b>
 * (ホッパーに残り続ける)。燃料が無言で溶けるより事故が少ないという判断
 * (オーケストレータ決定 2026-07-31)。
 *
 * <p><b>弾く条件を絞っている理由</b>: 素材だけを見て一律に弾くと、たとえば
 * {@code THICK + SUGAR} をゲートしているために<b>バニラの俊敏のポーション(AWKWARD + SUGAR)まで
 * 作れなくなる</b>。そのため「{@code custom:} 素材(バニラの醸造素材ではないので弾いて損が無い)」か
 * 「ゲート対象 spec の base に一致するビンが既にスタンドへ入っている」場合だけ弾く。
 *
 * <p>残る副作用として、<b>ゲート対象 spec が base をバニラと共有している場合</b>
 * (出荷 config では {@code AWKWARD + GLISTERING_MELON_SLICE})は、未解放プレイヤーがそのビンを
 * 入れた状態では素材を投入できない。これは修正前から {@code onBrew} 側のキャンセルで
 * 「そのバニラポーションも作れない」状態だった<b>config 側の設計問題</b>で、直すなら
 * yml で base を {@code THICK} 側へ寄せる(Java 側の変更は不要)。
 */
public final class BrewUnlockListener implements Listener {

    private static final String GATE_PREFIX = "brew:";
    private static final Component LOCKED_MESSAGE = Component.text(
            "この醸造素材を使うにはスキルツリーで解放する必要があります", NamedTextColor.RED);

    private final DedicatedEffectsConfig dedicatedEffects;
    private final CraftingFeaturesConfig features;
    private final Plugin plugin;

    public BrewUnlockListener(DedicatedEffectsConfig dedicatedEffects,
                              CraftingFeaturesConfig features,
                              Plugin plugin) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.features = Objects.requireNonNull(features, "features");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * 優先度は {@code NORMAL} 固定。
     * <ul>
     *   <li><b>上限側</b>: {@code PotionQualityListener}(HIGH)が結果の {@code ItemMeta} を書き換えた
     *       <b>後</b>に {@code results.set} で丸ごと差し替えると、{@code potion_quality_bonus}
     *       (Lv70/Lv90 ノード)がゲート付き醸造に一切効かなくなる(2026-07-31 に発覚したバグ)。
     *       品質より<b>前</b>に差し替える必要がある。</li>
     *   <li><b>下限側</b>: {@code BrewIngredientSaveListener}(HIGHEST)は
     *       「全キャンセラより後」であることが複製防止の前提なので、キャンセルするこちらは
     *       それより前でなければならない。</li>
     * </ul>
     * この順序は {@code BrewUnlockIngredientGateTest} が注釈値で固定している。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        BrewerInventory inv = event.getContents();
        ItemStack ingredient = inv.getIngredient();
        if (ingredient == null || ingredient.getType().isAir()) {
            return;
        }

        List<ItemStack> results = event.getResults();
        List<MatchedSpec> matched = matchSpecs(ingredient).stream()
                .filter(match -> hasMatchingBottleBase(inv, results, match.spec().base()))
                .toList();
        if (matched.isEmpty()) {
            return; // not a gated TF brew recipe
        }

        // スタンド全体で「どれか1つの解放」を見ると、同じ材料を使う別baseの未解放瓶を
        // 混ぜるだけでゲートを迂回できる。各瓶について、そのbaseに一致するspecの少なくとも
        // 1つを解放していることを要求する。BrewEventは瓶単位でcancelできないため、1本でも
        // 未解放ならスタンド全体を止める。
        for (int slot = 0; slot < 3; slot++) {
            PotionMeta probe = potionMetaForSlot(inv, results, slot);
            if (probe == null) {
                continue;
            }
            boolean hasGatedMatch = false;
            boolean hasUnlockedMatch = false;
            for (MatchedSpec m : matched) {
                if (!BrewRecipeSupport.matchesBase(probe, m.spec().base())) {
                    continue;
                }
                hasGatedMatch = true;
                if (playerHasEffectNear(inv, m.effectId())) {
                    hasUnlockedMatch = true;
                    break;
                }
            }
            if (hasGatedMatch && !hasUnlockedMatch) {
                event.setCancelled(true);
                return;
            }
        }

        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = inv.getItem(slot);
            if (bottle == null || bottle.getType().isAir()) {
                continue;
            }
            PotionMeta probe = potionMetaForSlot(inv, results, slot);
            if (probe == null) {
                continue;
            }

            for (MatchedSpec m : matched) {
                if (!playerHasEffectNear(inv, m.effectId())) {
                    continue;
                }
                if (!BrewRecipeSupport.matchesBase(probe, m.spec().base())) {
                    continue;
                }
                ItemStack custom = BrewRecipeSupport.customPotion(bottle.getType(), m.spec());
                while (results.size() <= slot) {
                    results.add(null);
                }
                results.set(slot, custom);
                break;
            }
        }
    }

    /** 未解放プレイヤーによる素材投入をクリック経路で弾く(クラスjavadoc「投入自体を弾く」参照)。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrewerClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof BrewerInventory brew)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack moving = insertedStack(event, brew);
        if (moving == null || !isLockedInsertion(brew, player, moving)) {
            return;
        }
        event.setCancelled(true);
        player.sendActionBar(LOCKED_MESSAGE);
    }

    /** ドラッグ配布経路。醸造台側のスロットに1つでも配られるなら、カーソルの中身を判定する。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrewerDrag(InventoryDragEvent event) {
        if (!(event.getInventory() instanceof BrewerInventory brew)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean touchesStand = event.getRawSlots().stream().anyMatch(raw -> raw < brew.getSize());
        if (!touchesStand) {
            return;
        }
        ItemStack moving = event.getOldCursor();
        if (moving == null || !isLockedInsertion(brew, player, moving)) {
            return;
        }
        event.setCancelled(true);
        player.sendActionBar(LOCKED_MESSAGE);
    }

    /**
     * ホッパー経由の投入。プレイヤーがいないので「スタンドを見ている / 8ブロック以内の解放者」で
     * 判定する({@link #playerHasEffectNear} と同じ規約)。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        Inventory dest = event.getDestination();
        if (!(dest instanceof BrewerInventory brew)) {
            return;
        }
        ItemStack moving = event.getItem();
        if (moving == null || !isLockedInsertion(brew, null, moving)) {
            return;
        }
        event.setCancelled(true);
        // 「ホッパーが動かない」の原因を運営が追えるようにする(クラスjavadoc の詰まり挙動)。
        plugin.getLogger().log(java.util.logging.Level.FINE,
                () -> "[brew-unlocks] refused hopper insertion of " + moving.getType()
                        + " into a brewing stand: no nearby player holds the unlock");
    }

    /**
     * クリック操作が「醸造台へアイテムを入れる」ものなら、その入るスタックを返す(それ以外は null)。
     *
     * <p><b>注意</b>: {@code getAction()} は Mockito の既定値が {@code null} になる。
     * ここを通るテストは必ず action を明示的にスタブすること(このリポジトリの既知の罠)。
     */
    private static ItemStack insertedStack(InventoryClickEvent event, BrewerInventory brew) {
        InventoryAction action = event.getAction();
        if (action == null) {
            return null;
        }
        boolean clickedStand = event.getClickedInventory() == brew;
        return switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR ->
                    clickedStand ? event.getCursor() : null;
            // 醸造台以外(=プレイヤーインベントリ)からのシフトクリックが「入れる」側。
            case MOVE_TO_OTHER_INVENTORY -> clickedStand ? null : event.getCurrentItem();
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> clickedStand
                    ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton())
                    : null;
            default -> null;
        };
    }

    /**
     * この投入を弾くべきか。「ゲート対象 spec に一致する素材」かつ「一致する spec を1つも解放して
     * いない」ことに加え、<b>誤爆を避けるため</b>次のどちらかを要求する(クラスjavadoc参照)。
     * <ul>
     *   <li>{@code custom:} 素材である(バニラの醸造素材ではないので弾いて失うものが無い)</li>
     *   <li>ゲート対象 spec の base に一致するビンが既にスタンドへ入っている</li>
     * </ul>
     */
    private boolean isLockedInsertion(BrewerInventory brew, Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        List<MatchedSpec> matched = matchSpecs(stack);
        if (matched.isEmpty()) {
            return false;
        }
        boolean anyCustom = false;
        boolean anyBottleBaseMatch = false;
        for (MatchedSpec m : matched) {
            if (hasEffect(player, brew, m.effectId())) {
                return false; // 解放済み: 通常どおり投入できる
            }
            if (BrewRecipeSupport.isCustomKey(m.spec().ingredient())) {
                anyCustom = true;
            }
            if (bottleBaseLoaded(brew, m.spec().base())) {
                anyBottleBaseMatch = true;
            }
        }
        return anyCustom || anyBottleBaseMatch;
    }

    /** 指定 base のビンがスタンドの下段(0..2)に入っているか。 */
    private static boolean bottleBaseLoaded(BrewerInventory brew, String baseName) {
        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = brew.getItem(slot);
            if (bottle != null && bottle.getItemMeta() instanceof PotionMeta meta
                    && BrewRecipeSupport.matchesBase(meta, baseName)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEffect(Player hint, BrewerInventory brew, String effectId) {
        if (hint != null && dedicatedEffects.isActive(hint, effectId)) {
            return true;
        }
        return playerHasEffectNear(brew, effectId);
    }

    private List<MatchedSpec> matchSpecs(ItemStack ingredient) {
        List<MatchedSpec> out = new ArrayList<>();
        for (Map.Entry<String, BrewUnlockGroup> entry : features.brewUnlocks().entrySet()) {
            String effectId = GATE_PREFIX + entry.getKey();
            for (BrewPotionSpec spec : entry.getValue().potions()) {
                if (BrewRecipeSupport.matchesIngredient(ingredient, spec.ingredient())) {
                    out.add(new MatchedSpec(effectId, spec));
                }
            }
        }
        return out;
    }

    private static boolean hasMatchingBottleBase(BrewerInventory inv, List<ItemStack> results,
                                                 String baseName) {
        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = inv.getItem(slot);
            PotionMeta probe = bottle != null && bottle.getItemMeta() instanceof PotionMeta pm ? pm : null;
            if (probe == null && slot < results.size() && results.get(slot) != null
                    && results.get(slot).getItemMeta() instanceof PotionMeta rm) {
                probe = rm;
            }
            if (probe != null && BrewRecipeSupport.matchesBase(probe, baseName)) {
                return true;
            }
        }
        return false;
    }

    private static PotionMeta potionMetaForSlot(BrewerInventory inv, List<ItemStack> results, int slot) {
        ItemStack bottle = inv.getItem(slot);
        if (bottle != null && bottle.getItemMeta() instanceof PotionMeta meta) {
            return meta;
        }
        if (slot < results.size()) {
            ItemStack result = results.get(slot);
            if (result != null && result.getItemMeta() instanceof PotionMeta meta) {
                return meta;
            }
        }
        return null;
    }

    private boolean playerHasEffectNear(BrewerInventory inv, String effectId) {
        for (HumanEntity viewer : inv.getViewers()) {
            if (viewer instanceof Player player && dedicatedEffects.isActive(player, effectId)) {
                return true;
            }
        }
        if (!(inv.getHolder() instanceof BrewingStand stand)) {
            return false;
        }
        Location loc = stand.getLocation();
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        for (Player player : loc.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(loc) <= 64.0 // 8 blocks
                    && dedicatedEffects.isActive(player, effectId)) {
                return true;
            }
        }
        return false;
    }

    private record MatchedSpec(String effectId, BrewPotionSpec spec) {}
}
