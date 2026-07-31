package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.stats.BrewPotionMixRegistrar.MixPlan;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BrewingStartEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BrewUnlockListener} の<b>投入ゲートと燃料ゲート</b> (2026-07-31 D10 = K-13、
 * オーケストレータ決定「未解放プレイヤーが素材を置いたときは投入自体を弾く」＋
 * レビュー指摘#1「順序と離席で抜ける／燃料無限消費ループが残る」の修正)。
 *
 * <p>{@code PotionMix} を登録すると素材が誰でも上段に置けて醸造が始まるため、完成時に
 * {@code BrewEvent} をキャンセルするだけだと <b>20秒ごとに燃料を1つ燃やし続ける</b>
 * (バニラは {@code brewable && fuel>0} で無条件に再開し、{@code BrewingStartEvent} は
 * {@code Cancellable} ではない)。対策は3段:
 * (1) 組み合わせが成立する投入を素材側・ビン側の両方で弾く、
 * (2) 未解放の組み合わせが載っているスタンドは燃料を受け付けない、
 * (3) 走り出してしまったら燃料を 0 にして周回を止める。
 */
class BrewUnlockIngredientGateTest {

    private static final NamespacedKey ARS_ID = new NamespacedKey("arspaper", "custom_item_id");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    // ---- 優先度の不変条件 ----

    @Test
    void brewHandlerOrderKeepsQualityOnCustomPotionsAndCannotDuplicateIngredients() throws Exception {
        EventPriority unlock = priorityOf(BrewUnlockListener.class);
        EventPriority quality = priorityOf(PotionQualityListener.class);
        EventPriority guard = priorityOf(CatalogVanillaOperationGuardListener.class);
        EventPriority save = priorityOf(BrewIngredientSaveListener.class);

        assertEquals(EventPriority.NORMAL, unlock,
                "結果の差し替えは品質付与(PotionQualityListener=HIGH)より前でなければ、"
                        + "results.set で丸ごと差し替えたときに品質が消える");
        assertEquals(EventPriority.HIGH, quality);
        assertEquals(EventPriority.HIGH, guard,
                "キャンセラは +1 ミラー(BrewIngredientSaveListener=HIGHEST)より前でなければ"
                        + "「+1 されたのに shrink されない」= 素材の純増になる");
        assertEquals(EventPriority.HIGHEST, save);
        assertTrue(unlock.ordinal() < quality.ordinal() && quality.ordinal() < save.ordinal(),
                "NORMAL < HIGH < HIGHEST の順序自体が不変条件 (過去にこの順序で複製バグが出ている)");
    }

    private static EventPriority priorityOf(Class<?> listener) throws Exception {
        EventHandler annotation = listener.getMethod("onBrew", BrewEvent.class)
                .getAnnotation(EventHandler.class);
        return annotation.priority();
    }

    // ---- クリックによる投入 ----

    @Test
    void lockedPlayerCannotPlaceACustomIngredientIntoTheStand() {
        Player player = mock(Player.class);
        BrewerInventory inv = standWith(PotionType.THICK);
        ItemStack tusk = arsItem(Material.BONE, "hoglin_tusk");
        InventoryClickEvent event = placeEvent(player, inv, tusk);

        listener(false).onBrewerClick(event);

        verify(event).setCancelled(true);
    }

    @Test
    void unlockedPlayerCanStillPlaceTheSameCustomIngredient() {
        Player player = mock(Player.class);
        BrewerInventory inv = standWith(PotionType.THICK);
        ItemStack tusk = arsItem(Material.BONE, "hoglin_tusk");
        InventoryClickEvent event = placeEvent(player, inv, tusk);

        listener(true).onBrewerClick(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void aPlainVanillaBrewIngredientIsNotBlockedWhenNoMatchingBottleIsLoaded() {
        // THICK + SUGAR は brew-unlocks のゲート対象だが、AWKWARD ビンしか入っていない醸造台では
        // バニラの俊敏のポーションを作る正当な操作。未解放でも弾いてはいけない。
        Player player = mock(Player.class);
        BrewerInventory inv = standWith(PotionType.AWKWARD);
        InventoryClickEvent event = placeEvent(player, inv, plain(Material.SUGAR));

        listener(false, new BrewPotionSpec("THICK", "SUGAR", mock(PotionEffectType.class), 3600, 1))
                .onBrewerClick(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void aPlainVanillaBrewIngredientIsBlockedOnceTheGatedBaseIsLoaded() {
        Player player = mock(Player.class);
        BrewerInventory inv = standWith(PotionType.THICK);
        InventoryClickEvent event = placeEvent(player, inv, plain(Material.SUGAR));

        listener(false, new BrewPotionSpec("THICK", "SUGAR", mock(PotionEffectType.class), 3600, 1))
                .onBrewerClick(event);

        verify(event).setCancelled(true);
    }

    @Test
    void anUnrelatedItemIsNeverBlocked() {
        Player player = mock(Player.class);
        BrewerInventory inv = standWith(PotionType.THICK);
        InventoryClickEvent event = placeEvent(player, inv, plain(Material.DIAMOND));

        listener(false).onBrewerClick(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void shiftClickingFromThePlayerInventoryIsAlsoBlocked() {
        Player player = mock(Player.class);
        BrewerInventory inv = standWith(PotionType.THICK);
        ItemStack tusk = arsItem(Material.BONE, "hoglin_tusk");
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(inv);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getAction()).thenReturn(InventoryAction.MOVE_TO_OTHER_INVENTORY);
        when(event.getClickedInventory()).thenReturn(mock(org.bukkit.inventory.PlayerInventory.class));
        when(event.getCurrentItem()).thenReturn(tusk);

        listener(false).onBrewerClick(event);

        verify(event).setCancelled(true);
    }

    // ---- レビュー指摘#1(a): 「素材 → ビン」の順序で抜けていた穴 ----

    @Test
    void insertingTheGatedBottleAfterTheIngredientIsAlsoBlocked() {
        // 旧実装は投入されたスタックが素材かどうかだけを見ていたため、
        // 砂糖を先に入れて(ビンが無いので通る) → THICK ビンを入れる(ビンは素材照合に一致しない)
        // という順序でゲートを完全に迂回でき、燃料無限消費ループへ入れた。
        Player player = mock(Player.class);
        BrewerInventory inv = emptyStandWithIngredient(plain(Material.SUGAR));
        InventoryClickEvent event = placeEvent(player, inv, potionBottle(PotionType.THICK));

        listener(false, new BrewPotionSpec("THICK", "SUGAR", mock(PotionEffectType.class), 3600, 1))
                .onBrewerClick(event);

        verify(event).setCancelled(true);
    }

    @Test
    void insertingANonGatedBottleAfterTheIngredientIsStillAllowed() {
        // AWKWARD + SUGAR はバニラの俊敏のポーション。ゲートは THICK なので弾いてはいけない。
        Player player = mock(Player.class);
        BrewerInventory inv = emptyStandWithIngredient(plain(Material.SUGAR));
        InventoryClickEvent event = placeEvent(player, inv, potionBottle(PotionType.AWKWARD));

        listener(false, new BrewPotionSpec("THICK", "SUGAR", mock(PotionEffectType.class), 3600, 1))
                .onBrewerClick(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void aBottleIsAlsoCheckedAsAnIngredientWhenTheConfigNamesAPotionAsTheIngredient() {
        // 素材にポーションを書いた config でも取りこぼさない(ビン解釈と素材解釈の両方を見る)。
        Player player = mock(Player.class);
        BrewerInventory inv = standWith(PotionType.THICK);
        InventoryClickEvent event = placeEvent(player, inv, potionBottle(PotionType.AWKWARD));

        listener(false, new BrewPotionSpec("THICK", "POTION", mock(PotionEffectType.class), 3600, 1))
                .onBrewerClick(event);

        verify(event).setCancelled(true);
    }

    @Test
    void unlockedPlayerBecomesTheRecordedOwnerOfTheStand() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(OWNER);
        BrewerInventory inv = standWith(PotionType.THICK);
        RecordingOwners owners = new RecordingOwners(null, player);
        InventoryClickEvent event = placeEvent(player, inv, arsItem(Material.BONE, "hoglin_tusk"));

        listener(true, owners).onBrewerClick(event);

        verify(event, never()).setCancelled(true);
        assertSame(player, owners.remembered, "解放者が組み立てたスタンドは所有者として記録される");
    }

    @Test
    void lockedPlayerNeverBecomesTheOwner() {
        Player player = mock(Player.class);
        BrewerInventory inv = standWith(PotionType.THICK);
        RecordingOwners owners = new RecordingOwners(null, null);
        InventoryClickEvent event = placeEvent(player, inv, arsItem(Material.BONE, "hoglin_tusk"));

        listener(false, owners).onBrewerClick(event);

        verify(event).setCancelled(true);
        assertNull(owners.remembered, "弾いた投入で所有権を奪えてはいけない");
    }

    // ---- ホッパー経由: 所有者で判定する ----

    @Test
    void hopperInsertionIsBlockedWhenTheStandHasNoRecordedOwner() {
        BrewerInventory inv = standWith(PotionType.THICK);
        InventoryMoveItemEvent event = hopperEvent(inv, arsItem(Material.BONE, "hoglin_tusk"));

        listener(false, new RecordingOwners(null, null)).onHopperMove(event);

        verify(event).setCancelled(true);
    }

    @Test
    void hopperInsertionIsAllowedWhenTheRecordedOwnerIsOnlineAndUnlocked() {
        BrewerInventory inv = standWith(PotionType.THICK);
        InventoryMoveItemEvent event = hopperEvent(inv, arsItem(Material.BONE, "hoglin_tusk"));
        Player owner = mock(Player.class);

        listener(true, new RecordingOwners(OWNER, owner)).onHopperMove(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void hopperInsertionIsBlockedWhenTheRecordedOwnerIsOffline() {
        // 所有者が解決できなければ弾く(燃料が無言で溶けるより事故が少ない = D10 と同じ判断)。
        BrewerInventory inv = standWith(PotionType.THICK);
        InventoryMoveItemEvent event = hopperEvent(inv, arsItem(Material.BONE, "hoglin_tusk"));

        listener(true, new RecordingOwners(OWNER, null)).onHopperMove(event);

        verify(event).setCancelled(true);
    }

    // ---- レビュー指摘#1(b): 解放者の離席で完成時キャンセルへ落ちない ----

    @Test
    void theRecordedOwnerKeepsTheBrewEvenAfterWalkingAwayFromTheStand() {
        // 旧実装は「閲覧者 or 半径8ブロック」で判定していたため、20秒の醸造中に8ブロック歩くだけで
        // 完成時キャンセル → 素材が減らないまま次tickで再開 → 燃料を延々と燃やすループに入った。
        BrewEvent event = gatedBrewEvent(false); // 閲覧者ゼロ = GUI を閉じて離れている
        Player owner = mock(Player.class);

        listener(true, new RecordingOwners(OWNER, owner)).onBrew(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void aViewingUnlockedPlayerNoLongerGrantsTheBrewForAnUnownedStand() {
        // 「閲覧者/近くのプレイヤーで解放判定する」を残すと所有者モデルが意味を失う
        // (誰かに開けてもらえば通る)。解放済みの閲覧者がいても所有者が未記録なら通さない。
        BrewEvent event = gatedBrewEvent(true);

        listener(true, new RecordingOwners(null, null)).onBrew(event);

        verify(event).setCancelled(true);
    }

    @Test
    void aStandWhoseOwnerIsOfflineCancelsTheGatedBrew() {
        BrewEvent event = gatedBrewEvent(true);

        listener(true, new RecordingOwners(OWNER, null)).onBrew(event);

        verify(event).setCancelled(true);
    }

    // ---- レビュー指摘#1(a): 燃料の取得段で止める ----

    @Test
    void aStandHoldingALockedGatedBrewCannotBeFueled() {
        org.bukkit.block.BrewingStand stand = standLoaded(PotionType.THICK, tusk());
        BrewingStandFuelEvent event = mock(BrewingStandFuelEvent.class);
        Block block = blockOf(stand);
        when(event.getBlock()).thenReturn(block);

        listener(false, new RecordingOwners(null, null)).onBrewingStandFuel(event);

        verify(event).setCancelled(true);
    }

    @Test
    void aStandHoldingAnUnlockedGatedBrewIsFueledNormally() {
        org.bukkit.block.BrewingStand stand = standLoaded(PotionType.THICK, tusk());
        BrewingStandFuelEvent event = mock(BrewingStandFuelEvent.class);
        Block block = blockOf(stand);
        when(event.getBlock()).thenReturn(block);
        Player owner = mock(Player.class);

        listener(true, new RecordingOwners(OWNER, owner)).onBrewingStandFuel(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void aVanillaBrewIsFueledEvenWhileTheGatedIngredientIsUnrelated() {
        org.bukkit.block.BrewingStand stand = standLoaded(PotionType.AWKWARD, plain(Material.SUGAR));
        BrewingStandFuelEvent event = mock(BrewingStandFuelEvent.class);
        Block block = blockOf(stand);
        when(event.getBlock()).thenReturn(block);

        listener(false, new BrewPotionSpec("THICK", "SUGAR", mock(PotionEffectType.class), 3600, 1))
                .onBrewingStandFuel(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void aLockedBrewThatManagedToStartHasItsFuelDrainedSoItCannotLoop() {
        // BrewingStartEvent は Cancellable ではないので、周回を止める手段は「燃料を 0 にする」だけ。
        org.bukkit.block.BrewingStand stand = standLoaded(PotionType.THICK, tusk());
        BrewingStartEvent event = mock(BrewingStartEvent.class);
        Block block = blockOf(stand);
        when(event.getBlock()).thenReturn(block);

        listener(false, new RecordingOwners(null, null)).onBrewingStart(event);

        verify(stand).setFuelLevel(0);
        verify(stand).update();
    }

    @Test
    void anUnlockedBrewThatStartsIsLeftAlone() {
        org.bukkit.block.BrewingStand stand = standLoaded(PotionType.THICK, tusk());
        BrewingStartEvent event = mock(BrewingStartEvent.class);
        Block block = blockOf(stand);
        when(event.getBlock()).thenReturn(block);
        Player owner = mock(Player.class);

        listener(true, new RecordingOwners(OWNER, owner)).onBrewingStart(event);

        verify(stand, never()).setFuelLevel(0);
    }

    // ---- ヘルパー ----

    private static BrewUnlockListener listener(boolean unlocked, BrewPotionSpec... extraSpecs) {
        return listener(unlocked, new RecordingOwners(OWNER, unlockedOwnerStub(unlocked)), extraSpecs);
    }

    /** 所有者を「常にオンライン」にしておくと、投入ゲートの既存テストが所有者記録に依存しない。 */
    private static Player unlockedOwnerStub(boolean unlocked) {
        return unlocked ? mock(Player.class) : null;
    }

    private static BrewUnlockListener listener(boolean unlocked, BrewStandOwners owners,
                                              BrewPotionSpec... extraSpecs) {
        List<MixPlan> plans = new ArrayList<>();
        plans.add(new MixPlan(new NamespacedKey("trinityforge", "brew_apex_brew_1"), "apex-brew",
                new BrewPotionSpec("THICK", "custom:hoglin_tusk", mock(PotionEffectType.class), 3600, 2), 90));
        int index = 1;
        for (BrewPotionSpec spec : extraSpecs) {
            plans.add(new MixPlan(new NamespacedKey("trinityforge", "brew_apex_brew_extra_" + index++),
                    "apex-brew", spec, 90));
        }

        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.isActive(org.mockito.ArgumentMatchers.any(Player.class),
                eq("brew:apex-brew"))).thenReturn(unlocked);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("BrewUnlockIngredientGateTest"));
        return new BrewUnlockListener(dedicatedEffects, () -> List.copyOf(plans), owners, plugin);
    }

    private static InventoryClickEvent placeEvent(Player player, BrewerInventory inv, ItemStack moving) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(inv);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getAction()).thenReturn(InventoryAction.PLACE_ALL);
        when(event.getClickedInventory()).thenReturn(inv);
        when(event.getCursor()).thenReturn(moving);
        return event;
    }

    private static InventoryMoveItemEvent hopperEvent(BrewerInventory inv, ItemStack moving) {
        InventoryMoveItemEvent event = mock(InventoryMoveItemEvent.class);
        when(event.getDestination()).thenReturn(inv);
        when(event.getItem()).thenReturn(moving);
        return event;
    }

    /**
     * ゲート対象(THICK + custom:hoglin_tusk)が成立している完成イベント。
     *
     * <p>ビンは<b>結果枠側</b>の {@code PotionMeta} で表現している({@code potionMetaForSlot} の
     * フォールバック経路)。こうすると「キャンセルするか」の判定だけを検証でき、結果の差し替え
     * ({@code BrewRecipeSupport#customPotion})に必要な {@code Bukkit.getItemFactory()} を呼ばずに済む。
     * 差し替え内容(重複時に上位段を選ぶこと)は {@code BrewPotionMixRegistrarTest} の dedup 側で固定している。
     */
    private static BrewEvent gatedBrewEvent(boolean unlockedViewerPresent) {
        ItemStack ingredient = tusk();
        List<org.bukkit.entity.HumanEntity> viewers = unlockedViewerPresent
                ? List.of(mock(Player.class))
                : List.of();
        ItemStack resultBottle = potionBottle(PotionType.THICK);
        BrewerInventory inv = mock(BrewerInventory.class);
        when(inv.getIngredient()).thenReturn(ingredient);
        when(inv.getViewers()).thenReturn(viewers);
        BrewEvent event = mock(BrewEvent.class);
        when(event.getContents()).thenReturn(inv);
        when(event.getResults()).thenReturn(new ArrayList<>(List.of(resultBottle)));
        return event;
    }

    /** 指定ベースのビンと素材が載っている醸造台の {@code BlockState}。 */
    private static org.bukkit.block.BrewingStand standLoaded(PotionType base, ItemStack ingredient) {
        BrewerInventory inv = standWith(base);
        when(inv.getIngredient()).thenReturn(ingredient);
        org.bukkit.block.BrewingStand stand = mock(org.bukkit.block.BrewingStand.class);
        when(stand.getInventory()).thenReturn(inv);
        return stand;
    }

    private static Block blockOf(org.bukkit.block.BrewingStand stand) {
        Block block = mock(Block.class);
        when(block.getState()).thenReturn(stand);
        return block;
    }

    /** 対象の醸造台: 指定ベースのビンが slot0 に入っている。 */
    private static BrewerInventory standWith(PotionType base) {
        ItemStack bottle = potionBottle(base); // 先に組む(when の引数の中で stub すると Mockito が壊れる)
        BrewerInventory inv = mock(BrewerInventory.class);
        when(inv.getItem(0)).thenReturn(bottle);
        return inv;
    }

    private static ItemStack tusk() {
        return arsItem(Material.BONE, "hoglin_tusk");
    }

    /** ビンが1本も入っておらず、上段に素材だけが載っている醸造台。 */
    private static BrewerInventory emptyStandWithIngredient(ItemStack ingredient) {
        BrewerInventory inv = mock(BrewerInventory.class);
        when(inv.getIngredient()).thenReturn(ingredient);
        when(inv.getViewers()).thenReturn(List.of(mock(Player.class)));
        return inv;
    }

    private static ItemStack potionBottle(PotionType base) {
        ItemStack bottle = mock(ItemStack.class);
        PotionMeta meta = mock(PotionMeta.class);
        when(bottle.getType()).thenReturn(Material.POTION);
        when(bottle.getItemMeta()).thenReturn(meta);
        when(meta.getBasePotionType()).thenReturn(base);
        return bottle;
    }

    private static ItemStack plain(Material type) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(type);
        when(stack.hasItemMeta()).thenReturn(false);
        return stack;
    }

    private static ItemStack arsItem(Material type, String id) {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(stack.getType()).thenReturn(type);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(ARS_ID, PersistentDataType.STRING)).thenReturn(id);
        return stack;
    }

    /** Bukkit を触らない {@link BrewStandOwners}: 記録済み所有者と「オンラインか」を固定で返す。 */
    private static final class RecordingOwners implements BrewStandOwners {
        private final UUID recorded;
        private final Player online;
        private Player remembered;

        RecordingOwners(UUID recorded, Player online) {
            this.recorded = recorded;
            this.online = online;
        }

        @Override
        public Optional<UUID> ownerOf(BrewerInventory brew) {
            return Optional.ofNullable(recorded);
        }

        @Override
        public void remember(BrewerInventory brew, Player player) {
            this.remembered = player;
        }

        @Override
        public Player online(UUID uuid) {
            return online;
        }
    }
}
