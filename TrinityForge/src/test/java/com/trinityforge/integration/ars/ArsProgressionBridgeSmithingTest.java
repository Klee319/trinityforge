package com.trinityforge.integration.ars;

import com.trinityforge.TrinityForge;
import com.trinityforge.TrinityForgeSingletonTestSupport;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.ItemStatProfile;
import com.trinityforge.stats.ItemUseRequirement;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArsProgressionBridgeSmithingTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        TrinityForgeSingletonTestSupport.clear();
        MockBukkit.unmock();
    }

    @Test
    void craftedArsGearUsesItsConfiguredUseLevelToScaleExperience() {
        PlayerMock player = server.addPlayer();
        ItemStack result = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = result.getItemMeta();
        meta.setCustomModelData(400006);
        result.setItemMeta(meta);

        SkillExpConfig skillExp = mock(SkillExpConfig.class);
        // 2026-08-17: 定額(ars-smithing.exp-per-craft)は廃止。基礎値は儀式専用の素材表から出す。
        when(skillExp.arsSmithingExpPerMaterial()).thenReturn(java.util.Map.of("IRON_INGOT", 100.0));
        when(skillExp.useLevelExpMultiplier(SkillId.ARS_SMITHING, 55)).thenReturn(1.55);

        ItemStatsConfig itemStats = mock(ItemStatsConfig.class);
        when(itemStats.profileFor(eq(Material.BLAZE_ROD), eq(400006)))
                .thenReturn(Optional.of(mock(ItemStatProfile.class)));
        when(itemStats.useRequirementFor(eq(Material.BLAZE_ROD), eq(400006)))
                .thenReturn(Optional.of(new ItemUseRequirement(55, SkillId.ARS_MAGIC)));

        ConfigManager config = mock(ConfigManager.class);
        when(config.skillExp()).thenReturn(skillExp);
        when(config.itemStats()).thenReturn(itemStats);
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.config()).thenReturn(config);
        when(tf.experienceDispatcher()).thenReturn(dispatcher);
        TrinityForgeSingletonTestSupport.set(tf);

        ArsProgressionBridge.grantSmithingCraftExp(
                MockBukkit.createMockPlugin(), player, result, java.util.List.of("IRON_INGOT"));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 155.0);
        verify(skillExp).useLevelExpMultiplier(SkillId.ARS_SMITHING, 55);
    }

    @Test
    void craftedArsGearWithoutAUseRequirementKeepsBaseExperience() {
        PlayerMock player = server.addPlayer();
        ItemStack result = new ItemStack(Material.BOOK);

        SkillExpConfig skillExp = mock(SkillExpConfig.class);
        when(skillExp.arsSmithingExpPerMaterial()).thenReturn(java.util.Map.of("IRON_INGOT", 100.0));
        when(skillExp.useLevelExpMultiplier(SkillId.ARS_SMITHING, 0)).thenReturn(1.0);
        ItemStatsConfig itemStats = mock(ItemStatsConfig.class);

        ConfigManager config = mock(ConfigManager.class);
        when(config.skillExp()).thenReturn(skillExp);
        when(config.itemStats()).thenReturn(itemStats);
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.config()).thenReturn(config);
        when(tf.experienceDispatcher()).thenReturn(dispatcher);
        TrinityForgeSingletonTestSupport.set(tf);

        ArsProgressionBridge.grantSmithingCraftExp(
                MockBukkit.createMockPlugin(), player, result, java.util.List.of("IRON_INGOT"));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 100.0);
    }
}
