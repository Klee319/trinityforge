package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BrewIngredientSaveListener}: {@code ingredient_save_chance}をバニラ醸造台へ配線したことの検証
 * (2026-07-25 監査 reports/20260725_SkilltreeNodeTriage.md B-alpha-1 の修正)。
 *
 * <p>実際にバニラの{@code BrewingStandBlockEntity#doBrew}が行う{@code itemstack.shrink(1)}との
 * 相殺(=消費キャンセルそのもの)は、CraftBukkitの内部実装(単体テストの射程外)に依存する。この
 * テストが検証するのはリスナー自体の観測可能な振る舞い: 勝った回だけ材料スロットの個数を+1する、
 * という契約(このハンドラのjavadocに書いた通り、直後のバニラshrink(1)と相殺されて「消費されない」
 * 結果になる)。
 */
class BrewIngredientSaveListenerTest {

    private static final String KEY = "ingredient_save_chance";

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private BrewingStand stand;
    private BrewOwnership ownership;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.BREWING_STAND);
        stand = (BrewingStand) block.getState();
        ownership = new BrewOwnership(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void writeManualOwner(Player owner) {
        stand.getPersistentDataContainer().set(
                ownership.lastBrewerKey(), PersistentDataType.STRING, owner.getUniqueId().toString());
        stand.getPersistentDataContainer().set(
                ownership.brewModeKey(), PersistentDataType.STRING, BrewOwnership.MODE_MANUAL);
        stand.update();
    }

    private void writeAutomatedOwner(Player owner) {
        stand.getPersistentDataContainer().set(
                ownership.lastBrewerKey(), PersistentDataType.STRING, owner.getUniqueId().toString());
        stand.getPersistentDataContainer().set(
                ownership.brewModeKey(), PersistentDataType.STRING, BrewOwnership.MODE_AUTO);
        stand.update();
    }

    private PlayerStatAggregator aggregatorReturning(Player p, double chance) {
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(p)).thenReturn(new PlayerCombatAggregate(
                Map.of(KEY, chance), Map.of(), Map.of(), Map.of(), Map.of()));
        return aggregator;
    }

    private BrewEvent brewEvent() {
        BrewerInventory inv = stand.getInventory();
        List<ItemStack> results = new ArrayList<>();
        results.add(new ItemStack(Material.POTION));
        return new BrewEvent(stand.getBlock(), inv, results, 20);
    }

    @Test
    void manualProcSavesTheIngredientByCreditingOneBack() {
        writeManualOwner(player);
        stand.getInventory().setIngredient(new ItemStack(Material.NETHER_WART, 3));
        // 1.0 == "certain proc" (ThreadLocalRandom#nextDouble() is always in [0,1), so roll < 1.0 always
        // holds — deterministic without needing to inject the RNG, matching this codebase's existing
        // "extreme value" determinism trick used by FoodGimmickListenerTest et al.).
        BrewIngredientSaveListener listener =
                new BrewIngredientSaveListener(plugin, aggregatorReturning(player, 1.0));

        listener.onBrew(brewEvent());

        assertEquals(4, stand.getInventory().getIngredient().getAmount(),
                "a winning roll credits back the 1 unit vanilla is about to shrink() away");
    }

    @Test
    void manualNonProcLeavesTheIngredientUntouched() {
        writeManualOwner(player);
        stand.getInventory().setIngredient(new ItemStack(Material.NETHER_WART, 3));
        BrewIngredientSaveListener listener =
                new BrewIngredientSaveListener(plugin, aggregatorReturning(player, 0.0));

        listener.onBrew(brewEvent());

        assertEquals(3, stand.getInventory().getIngredient().getAmount(),
                "chance <= 0 must never touch the ingredient slot");
    }

    @Test
    void automatedHopperFedBrewNeverProcsEvenAtCertainChance() {
        writeAutomatedOwner(player);
        stand.getInventory().setIngredient(new ItemStack(Material.NETHER_WART, 3));
        // Even a "certain" 100% chance must be fully skipped for automated brews — this stat gets no
        // auto_mult damping the way quality/speed do; it is a hard off-switch (duplication-engine risk
        // on unattended hopper farms).
        BrewIngredientSaveListener listener =
                new BrewIngredientSaveListener(plugin, aggregatorReturning(player, 1.0));

        listener.onBrew(brewEvent());

        assertEquals(3, stand.getInventory().getIngredient().getAmount(),
                "automated (hopper-fed) brewing must never roll the save chance, regardless of stat value");
    }

    @Test
    void unownedStandNeverProcs() {
        // Never written -> no owner recorded.
        stand.getInventory().setIngredient(new ItemStack(Material.NETHER_WART, 3));
        BrewIngredientSaveListener listener =
                new BrewIngredientSaveListener(plugin, aggregatorReturning(player, 1.0));

        listener.onBrew(brewEvent());

        assertEquals(3, stand.getInventory().getIngredient().getAmount(),
                "an unattributed stand (no manual-click owner recorded) must never save ingredients");
    }

    @Test
    void chanceAboveOneHundredPercentIsClampedButStillProcsReliably() {
        writeManualOwner(player);
        stand.getInventory().setIngredient(new ItemStack(Material.NETHER_WART, 3));
        // A misconfigured/unnormalized value far above 1.0 must be clamped to 1.0, not treated as some
        // runaway probability that breaks the roll comparison.
        BrewIngredientSaveListener listener =
                new BrewIngredientSaveListener(plugin, aggregatorReturning(player, 250.0));

        listener.onBrew(brewEvent());

        assertEquals(4, stand.getInventory().getIngredient().getAmount(),
                "a value far above 1.0 clamps to certain-proc, not an error or a no-op");
    }

    @Test
    void negativeChanceIsClampedToNeverProc() {
        writeManualOwner(player);
        stand.getInventory().setIngredient(new ItemStack(Material.NETHER_WART, 3));
        BrewIngredientSaveListener listener =
                new BrewIngredientSaveListener(plugin, aggregatorReturning(player, -5.0));

        listener.onBrew(brewEvent());

        assertEquals(3, stand.getInventory().getIngredient().getAmount(),
                "a negative stat value must clamp to 0 chance, never touching the ingredient slot");
    }

    /**
     * 複製バグの回帰テスト(2026-07-26)。このハンドラは「+1しておけば直後のバニラshrink(1)と相殺される」
     * ことに依存しているため、<b>誰かがこの後にBrewEventをキャンセルすると+1だけが残って純増する</b>。
     * TF内には実際にキャンセルする{@link BrewUnlockListener#onBrew}(HIGH)があり、登録順もそちらが後
     * なので、同じHIGHに置くと毎醸造周回で素材が1個ずつ増える無限増殖装置になっていた。
     *
     * <p>したがって優先度は「全キャンセル判定の後」かつ「{@link NativeSkillExperienceListener#onBrew}が
     * MONITORで所有者PDCを消す前」= HIGHEST でなければならない。ここを緩めた変更は必ず落とす。
     */
    @Test
    void handlerRunsAtHighestSoNoLaterCancelCanTurnTheCreditIntoDuplication() throws Exception {
        EventHandler annotation = BrewIngredientSaveListener.class
                .getMethod("onBrew", BrewEvent.class)
                .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.HIGHEST, annotation.priority(),
                "must run after every canceller (BrewUnlockListener cancels at NORMAL, "
                        + "CatalogVanillaOperationGuardListener at HIGH), but before "
                        + "NativeSkillExperienceListener clears the owner PDC at MONITOR");
        assertEquals(true, annotation.ignoreCancelled(),
                "an already-cancelled brew never reaches vanilla's shrink(1), so crediting +1 there "
                        + "would be pure item duplication");
    }

    @Test
    void emptyIngredientSlotIsSafelyIgnoredOnAProc() {
        writeManualOwner(player);
        // Defensive edge case: an AIR ingredient slot (real vanilla never actually fires BrewEvent this
        // way — BrewingStandBlockEntity#isBrewable requires a non-empty, valid ingredient — but the
        // listener guards against it anyway). Explicitly setIngredient(AIR) rather than leaving the slot
        // untouched: MockBukkit's BrewerInventoryMock#getIngredient throws IllegalStateException("No
        // ingredient has been set") if setIngredient was never called at all, which is a MockBukkit-only
        // quirk unrelated to what this test is verifying.
        stand.getInventory().setIngredient(new ItemStack(Material.AIR));
        BrewIngredientSaveListener listener =
                new BrewIngredientSaveListener(plugin, aggregatorReturning(player, 1.0));

        listener.onBrew(brewEvent());

        ItemStack ingredient = stand.getInventory().getIngredient();
        boolean isAirOrNull = ingredient == null || ingredient.getType().isAir();
        assertEquals(true, isAirOrNull, "no ingredient present -> nothing to credit back, no crash");
    }
}
