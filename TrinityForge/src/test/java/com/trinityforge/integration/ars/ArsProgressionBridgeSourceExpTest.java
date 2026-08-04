package com.trinityforge.integration.ars;

import com.trinityforge.TrinityForge;
import com.trinityforge.TrinityForgeSingletonTestSupport;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消費ソース量に応じた追加EXP (2026-08-04 ユーザー要望) の回帰テスト。
 *
 * <p>ソース項を消すと {@code expectedTotal} が定額のままになるので、
 * {@code grantSmithingCraftExp} からソース加算を外すと必ず落ちる。
 */
class ArsProgressionBridgeSourceExpTest {

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

    private NativeExperienceDispatcher wireConfig(double expPerCraft, double expPerSource) {
        SkillExpConfig skillExp = mock(SkillExpConfig.class);
        when(skillExp.arsSmithingExpPerCraft()).thenReturn(expPerCraft);
        when(skillExp.arsSmithingExpPerSource()).thenReturn(expPerSource);
        when(skillExp.useLevelExpMultiplier(SkillId.ARS_SMITHING, 0)).thenReturn(1.0);

        ConfigManager config = mock(ConfigManager.class);
        when(config.skillExp()).thenReturn(skillExp);
        when(config.itemStats()).thenReturn(mock(ItemStatsConfig.class));
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.config()).thenReturn(config);
        when(tf.experienceDispatcher()).thenReturn(dispatcher);
        TrinityForgeSingletonTestSupport.set(tf);
        return dispatcher;
    }

    @Test
    @DisplayName("消費ソース量 × exp-per-source が定額に加算される")
    void consumedSourceAddsExperienceOnTopOfTheFlatAmount() {
        PlayerMock player = server.addPlayer();
        NativeExperienceDispatcher dispatcher = wireConfig(100.0, 0.01);

        ArsProgressionBridge.grantSmithingCraftExp(
                MockBukkit.createMockPlugin(), player, new ItemStack(Material.BOOK),
                java.util.List.of(), 5000);

        // 100(定額) + 5000 × 0.01 = 150
        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 150.0);
    }

    @Test
    @DisplayName("exp-per-source=0 なら消費ソースは一切効かない(既定の後方互換)")
    void zeroExpPerSourceKeepsTheLegacyFlatAmount() {
        PlayerMock player = server.addPlayer();
        NativeExperienceDispatcher dispatcher = wireConfig(100.0, 0.0);

        ArsProgressionBridge.grantSmithingCraftExp(
                MockBukkit.createMockPlugin(), player, new ItemStack(Material.BOOK),
                java.util.List.of(), 45_000_000);

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 100.0);
    }

    @Test
    @DisplayName("ソース量を渡さない4引数版は従来どおり定額のまま(既存呼び出し側の挙動不変)")
    void fourArgOverloadStillGrantsTheFlatAmount() {
        PlayerMock player = server.addPlayer();
        NativeExperienceDispatcher dispatcher = wireConfig(100.0, 1.0);

        ArsProgressionBridge.grantSmithingCraftExp(
                MockBukkit.createMockPlugin(), player, new ItemStack(Material.BOOK),
                java.util.List.of());

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 100.0);
    }

    @Test
    @DisplayName("負のソース量・非有限な係数はEXPを減らさない")
    void sourceExpNeverContributesNegatively() {
        assertEquals(0.0, ArsProgressionBridge.sourceExp(-100, 1.0));
        assertEquals(0.0, ArsProgressionBridge.sourceExp(0, 1.0));
        assertEquals(0.0, ArsProgressionBridge.sourceExp(100, -1.0));
        assertEquals(0.0, ArsProgressionBridge.sourceExp(100, Double.NaN));
        assertEquals(0.0, ArsProgressionBridge.sourceExp(100, Double.POSITIVE_INFINITY));
        assertEquals(2.5, ArsProgressionBridge.sourceExp(500, 0.005));
    }
}
