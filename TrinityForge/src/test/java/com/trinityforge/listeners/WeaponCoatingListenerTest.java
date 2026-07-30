package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.CoatingMaterial;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.config.domains.WeaponBaseFormula;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WeaponCoatingListener}: coating stack capacity is defined by the one dedicated effect, with no
 * weapon-class-specific native reward path.
 *
 * <p>Isolation trick: {@code coating-base-max-stacks} and the material's own {@code max-stacks} are both
 * stubbed to 0, so the listener's {@code maxStacks = min(base, material) + perkBonus} collapses to exactly
 * {@code max(1, perkBonus)} — the number of successful coats a fresh weapon accepts before refusing IS the
 * computed perk bonus, made directly observable without reading any private field.
 */
class WeaponCoatingListenerTest {

    private static final String GEM_CATALOG_ID = "tf_coating_gem";

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private CraftingFeaturesConfig features;
    private ItemStatsConfig itemStats;
    private WeaponCoatingListener listener;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        features = mock(CraftingFeaturesConfig.class);
        // 未スタブのfallbackFixedFor(Material,Integer)はMockitoのデフォルト空Mapを返すので、
        // このitemStatsモックはitem由来coating-chargesボーナス0(= 既存挙動と同じ)として振る舞う。
        // 個別テストでのみ特定Materialを上書きスタブしてitem由来ボーナスを検証する。
        // 2026-07-28(数値のギミックyml集約): perk側の coating-stack-increase は通常stat
        // coating_charges_bonus へ移設された。PlayerStatAggregator は final なのでMockitoで直接
        // モックできない(FishingQualityListenerTestと同じ流儀で実物を組み立てる) — 対象武器の
        // fallbackFixedFor に coating_charges_bonus を乗せることで aggregator.totalOf 経由で
        // perk相当のボーナスとして観測できるようにする(既存の item由来 coating_charges とは別キー)。
        itemStats = mock(ItemStatsConfig.class);
        CombatDamageConfig combatDamage = mock(CombatDamageConfig.class);
        when(combatDamage.weaponBaseFormula()).thenReturn(WeaponBaseFormula.disabled());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                itemStats, combatDamage,
                new PerkBuffResolver(SkillPerkStatSource.EMPTY, java.util.List::of),
                new RoleBuffResolver(new RoleBuffsConfig()));
        listener = new WeaponCoatingListener(dedicatedEffects, features, itemStats, WeaponBaseFormula.disabled(), aggregator);

        player = server.addPlayer();
        when(dedicatedEffects.isActive(any(), eq("weapon-coating-unlock"))).thenReturn(true);
        when(itemStats.fallbackFixedFor(any(), any())).thenReturn(Map.of("coating_charges_bonus", 3.0));
        when(features.coatingMaterial(GEM_CATALOG_ID)).thenReturn(new CoatingMaterial(1.0, 0));
        when(features.coatingBaseMaxStacks()).thenReturn(0);

    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerInteractEvent interactEvent() {
        return interactEvent(Action.RIGHT_CLICK_AIR);
    }

    private PlayerInteractEvent interactEvent(Action action) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(action);
        return event;
    }

    private ItemStack freshGem() {
        ItemStack gem = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta gemMeta = gem.getItemMeta();
        ItemData.of(gemMeta).setCatalogId(GEM_CATALOG_ID);
        gem.setItemMeta(gemMeta);
        return gem;
    }

    /**
     * Coats {@code weapon} up to {@code attempts} times (fresh gem each try) and returns stacks accepted.
     * Re-reads the mainhand from the player's inventory after every attempt (rather than trusting the
     * original {@code weapon} reference) so the assertion does not depend on whether the inventory mock
     * clones ItemStacks on set/get.
     */
    private int coatUpToAndCountAccepted(ItemStack weapon, int attempts) {
        player.getInventory().setItemInMainHand(weapon);
        int accepted = 0;
        for (int i = 0; i < attempts; i++) {
            player.getInventory().setItemInOffHand(freshGem());
            listener.onInteract(interactEvent());
            ItemStack current = player.getInventory().getItemInMainHand();
            int stacksNow = ItemData.of(current.getItemMeta()).coatingStacks();
            if (stacksNow <= accepted) {
                break; // maxStacks reached: listener refused, stacks did not advance
            }
            accepted = stacksNow;
            player.getInventory().setItemInMainHand(current);
        }
        return accepted;
    }

    @Test
    void swordUsesDedicatedStackBonus() {
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        int accepted = coatUpToAndCountAccepted(sword, 10);
        assertEquals(3, accepted,
                "sword receives the configured dedicated coating stack bonus");
    }

    @Test
    void axeUsesDedicatedStackBonus() {
        ItemStack axe = new ItemStack(Material.IRON_AXE);
        int accepted = coatUpToAndCountAccepted(axe, 10);
        assertEquals(3, accepted, "axe receives the configured dedicated coating stack bonus");
    }

    @Test
    void bowUsesTheSameDedicatedStackBonus() {
        ItemStack bow = new ItemStack(Material.BOW);
        int accepted = coatUpToAndCountAccepted(bow, 10);
        assertEquals(3, accepted, "bow receives the same configured stack bonus as melee weapons");
    }

    @Test
    void weaponsOwnCoatingChargesStatAddsOnTopOfThePerkBonus() {
        // The weapon's own coating-charges item stat (2) adds on top of the coating_charges_bonus
        // perk-wide stat (3, stubbed in setUp's fallbackFixedFor default) -> 5 total accepted coats.
        // Both keys are read from the same mainhand material via DerivedItemStats.resolve, so the
        // override map must keep coating_charges_bonus alongside the item-specific coating_charges.
        when(itemStats.fallbackFixedFor(eq(Material.IRON_SWORD), any()))
                .thenReturn(Map.of("coating_charges", 2.0, "coating_charges_bonus", 3.0));
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        int accepted = coatUpToAndCountAccepted(sword, 10);
        assertEquals(5, accepted,
                "weapon's own coating-charges stat is added on top of the perk-wide bonus");
    }

    @Test
    void weaponsOwnCoatingChargesStatIsFlooredAndNeverNegative() {
        when(itemStats.fallbackFixedFor(eq(Material.IRON_SWORD), any()))
                .thenReturn(Map.of("coating_charges", -4.0, "coating_charges_bonus", 3.0));
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        int accepted = coatUpToAndCountAccepted(sword, 10);
        assertEquals(3, accepted,
                "a negative item coating-charges stat clamps to 0 rather than reducing capacity");
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"LEFT_CLICK_AIR", "LEFT_CLICK_BLOCK", "PHYSICAL"})
    void nonRightClickNeverConsumesGemOrCoatsWeapon(Action action) {
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        ItemStack gem = freshGem();
        gem.setAmount(2);
        player.getInventory().setItemInMainHand(sword);
        player.getInventory().setItemInOffHand(gem);
        PlayerInteractEvent event = interactEvent(action);

        listener.onInteract(event);

        ItemStack currentWeapon = player.getInventory().getItemInMainHand();
        assertEquals(0, ItemData.of(currentWeapon.getItemMeta()).coatingStacks(),
                "non-right-click interaction must not add a coating stack");
        assertEquals(2, player.getInventory().getItemInOffHand().getAmount(),
                "non-right-click interaction must not consume the offhand coating material");
        verify(event, never()).setCancelled(true);
    }

    @Test
    void rightClickBlockCoatsWeapon() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
        player.getInventory().setItemInOffHand(freshGem());

        listener.onInteract(interactEvent(Action.RIGHT_CLICK_BLOCK));

        ItemStack currentWeapon = player.getInventory().getItemInMainHand();
        assertEquals(1, ItemData.of(currentWeapon.getItemMeta()).coatingStacks());
        assertEquals(0, player.getInventory().getItemInOffHand().getAmount());
    }
}
