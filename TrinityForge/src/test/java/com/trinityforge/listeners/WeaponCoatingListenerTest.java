package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.CoatingMaterial;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.WeaponBaseFormula;
import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        itemStats = mock(ItemStatsConfig.class);
        listener = new WeaponCoatingListener(dedicatedEffects, features, itemStats, WeaponBaseFormula.disabled());

        player = server.addPlayer();
        when(dedicatedEffects.isActive(any(), eq("weapon-coating-unlock"))).thenReturn(true);
        when(dedicatedEffects.valueSum(any(), eq("coating-stack-increase"))).thenReturn(3.0);
        when(features.coatingMaterial(GEM_CATALOG_ID)).thenReturn(new CoatingMaterial(1.0, 0));
        when(features.coatingBaseMaxStacks()).thenReturn(0);

    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerInteractEvent interactEvent() {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
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
        // The weapon's own coating-charges item stat (2) adds on top of the dedicated
        // coating-stack-increase perk bonus (3, stubbed in setUp) -> 5 total accepted coats.
        when(itemStats.fallbackFixedFor(eq(Material.IRON_SWORD), any()))
                .thenReturn(Map.of("coating_charges", 2.0));
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        int accepted = coatUpToAndCountAccepted(sword, 10);
        assertEquals(5, accepted,
                "weapon's own coating-charges stat is added on top of the perk-wide bonus");
    }

    @Test
    void weaponsOwnCoatingChargesStatIsFlooredAndNeverNegative() {
        when(itemStats.fallbackFixedFor(eq(Material.IRON_SWORD), any()))
                .thenReturn(Map.of("coating_charges", -4.0));
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        int accepted = coatUpToAndCountAccepted(sword, 10);
        assertEquals(3, accepted,
                "a negative item coating-charges stat clamps to 0 rather than reducing capacity");
    }
}
