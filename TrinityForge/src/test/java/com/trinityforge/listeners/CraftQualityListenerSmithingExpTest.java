package com.trinityforge.listeners;

import com.trinityforge.TrinityForge;
import com.trinityforge.TrinityForgeSingletonTestSupport;
import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.CraftQualityService;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemStatProfile;
import org.bukkit.Material;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PRG-13 (2026-07-25): SMITHING EXP moved from durability-consumption
 * ({@code NativeSkillExperienceListener#onItemDamage}, now removed) to crafting — mirrors the existing
 * ARS_SMITHING craft-EXP grant in {@link CraftQualityListener#onCraft}, gated by the same
 * {@code CraftQualityConfig#categorySkill()} fixed map ({@code weapon/armor/tool -> SMITHING}).
 *
 * <p>MockBukkit-backed (like {@code VeinMiningListenerTest}) so plain {@link ItemStack} meta reads
 * ({@code hasItemMeta}/{@code getItemMeta}) work without a full server boot. The
 * {@link TrinityForge#getInstance()} singleton is stubbed via {@link TrinityForgeSingletonTestSupport}
 * because {@link com.trinityforge.integration.ars.ArsProgressionBridge#grantSkillExp} reads it.
 */
class CraftQualityListenerSmithingExpTest {

    private static final double SMITHING_EXP_PER_CRAFT = 15.0;

    private ServerMock server;
    private NativeExperienceDispatcher dispatcher;
    private CraftQualityConfig craftQualityConfig;
    private ItemStatsConfig itemStats;
    private SkillExpConfig skillExp;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dispatcher = mock(NativeExperienceDispatcher.class);
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.experienceDispatcher()).thenReturn(dispatcher);
        TrinityForgeSingletonTestSupport.set(tf);

        craftQualityConfig = new CraftQualityConfig(); // real: fixed categorySkill map (weapon/armor/tool -> SMITHING)
        itemStats = mock(ItemStatsConfig.class);
        skillExp = mock(SkillExpConfig.class);
        when(skillExp.smithingExpPerCraft()).thenReturn(SMITHING_EXP_PER_CRAFT);
        when(skillExp.arsSmithingExpPerCraft()).thenReturn(100.0);
        // 2026-07-28 使用可能レベル連動EXP: このテストは倍率の挙動自体を検証しないので、
        // 常に1.0(影響なし)を返すようスタブする(スタブが無いと Mockito のdouble既定値0.0が
        // 返り、amount<=0.0 で grantSkillExp が早期returnして「呼ばれない」誤検知になる)。
        when(skillExp.useLevelExpMultiplier(org.mockito.ArgumentMatchers.anyString(), anyInt()))
                .thenReturn(1.0);

        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        TrinityForgeSingletonTestSupport.clear();
        MockBukkit.unmock();
    }

    private CraftQualityListener listener() {
        org.bukkit.plugin.Plugin plugin = MockBukkit.createMockPlugin();
        ItemFactory itemFactory = mock(ItemFactory.class);
        CraftQualityService craftQualityService = mock(CraftQualityService.class);
        when(craftQualityService.rollQuality(any(), any(), anyInt())).thenReturn(0);
        ItemCatalogConfig itemCatalog = mock(ItemCatalogConfig.class);
        return new CraftQualityListener(plugin, itemFactory, craftQualityService, craftQualityConfig,
                skillExp, itemCatalog, itemStats);
    }

    private CraftItemEvent craftEvent(Material resultMaterial, boolean shiftClick) {
        ItemStack result = new ItemStack(resultMaterial);
        when(itemStats.profileFor(eq(resultMaterial), any())).thenReturn(Optional.of(mock(ItemStatProfile.class)));
        when(itemStats.qualityModeOffsetFor(eq(resultMaterial), any())).thenReturn(0);

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(result);

        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getCurrentItem()).thenReturn(result);
        when(event.getRecipe()).thenReturn(mock(Recipe.class));
        when(event.isShiftClick()).thenReturn(shiftClick);
        return event;
    }

    @Test
    void craftingWeaponGrantsSmithingExpOnce() {
        listener().onCraft(craftEvent(Material.DIAMOND_SWORD, false));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.SMITHING, SMITHING_EXP_PER_CRAFT);
    }

    @Test
    void craftingArmorGrantsSmithingExpOnce() {
        listener().onCraft(craftEvent(Material.DIAMOND_CHESTPLATE, false));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.SMITHING, SMITHING_EXP_PER_CRAFT);
    }

    @Test
    void craftingToolGrantsSmithingExpOnce() {
        listener().onCraft(craftEvent(Material.DIAMOND_PICKAXE, false));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.SMITHING, SMITHING_EXP_PER_CRAFT);
    }

    @Test
    void shiftClickBulkCraftStillGrantsExactlyOneCraftsWorthOfExp() {
        // CraftItemEvent fires ONCE per shift-click action regardless of how many copies vanilla
        // fills the inventory with — this listener must not scale the grant by crafted amount.
        listener().onCraft(craftEvent(Material.DIAMOND_SWORD, true));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.SMITHING, SMITHING_EXP_PER_CRAFT);
    }

    @Test
    void craftingNonEquipmentGrantsNoSmithingExp() {
        // DIRT is not tiered equipment (MaterialTier.of(DIRT) == NONE), so isStampableEquipment()
        // rejects it before candidatesFor() is ever consulted.
        listener().onCraft(craftEvent(Material.DIRT, false));

        verify(dispatcher, never()).grant(any(), eq(SkillId.SMITHING), anyDouble());
    }

    @Test
    void durabilityLossNoLongerGrantsSmithingExp() {
        // PRG-13 regression guard: NativeSkillExperienceListener#onItemDamage was removed entirely, so
        // there is no code path left that grants SMITHING EXP from PlayerItemDamageEvent. This test
        // documents that guarantee at the type level: the method no longer exists.
        boolean methodStillExists = java.util.Arrays.stream(
                        com.trinityforge.listeners.NativeSkillExperienceListener.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("onItemDamage"));
        org.junit.jupiter.api.Assertions.assertFalse(methodStillExists,
                "onItemDamage must be fully removed, not merely disabled");
    }
}
