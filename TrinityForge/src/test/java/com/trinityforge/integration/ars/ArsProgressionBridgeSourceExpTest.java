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

    /**
     * 2026-08-17: 定額(ars-smithing.exp-per-craft)は機能ごと廃止したので、
     * 基礎値は儀式専用の素材表({@code ars-smithing.exp-per-material})から出す。
     * {@code IRON_INGOT} 1個 = {@code ironIngotExp} として組む。
     */
    private NativeExperienceDispatcher wireConfig(double ironIngotExp, double expPerSource) {
        SkillExpConfig skillExp = mock(SkillExpConfig.class);
        when(skillExp.arsSmithingExpPerMaterial())
                .thenReturn(java.util.Map.of("IRON_INGOT", ironIngotExp));
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
    @DisplayName("消費ソース量 × exp-per-source が素材ぶんに加算される")
    void consumedSourceAddsExperienceOnTopOfTheMaterials() {
        PlayerMock player = server.addPlayer();
        NativeExperienceDispatcher dispatcher = wireConfig(100.0, 0.01);

        ArsProgressionBridge.grantSmithingCraftExp(
                MockBukkit.createMockPlugin(), player, new ItemStack(Material.BOOK),
                java.util.List.of("IRON_INGOT"), 5000);

        // 100(素材) + 5000 × 0.01 = 150
        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 150.0);
    }

    @Test
    @DisplayName("exp-per-source=0 なら消費ソースは一切効かない(素材ぶんだけ)")
    void zeroExpPerSourceKeepsOnlyTheMaterialAmount() {
        PlayerMock player = server.addPlayer();
        NativeExperienceDispatcher dispatcher = wireConfig(100.0, 0.0);

        ArsProgressionBridge.grantSmithingCraftExp(
                MockBukkit.createMockPlugin(), player, new ItemStack(Material.BOOK),
                java.util.List.of("IRON_INGOT"), 45_000_000);

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 100.0);
    }

    @Test
    @DisplayName("ソース量を渡さない4引数版は素材ぶんだけ(ソース項は付かない)")
    void fourArgOverloadGrantsTheMaterialAmountOnly() {
        PlayerMock player = server.addPlayer();
        NativeExperienceDispatcher dispatcher = wireConfig(100.0, 1.0);

        ArsProgressionBridge.grantSmithingCraftExp(
                MockBukkit.createMockPlugin(), player, new ItemStack(Material.BOOK),
                java.util.List.of("IRON_INGOT"));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 100.0);
    }

    /**
     * 2026-08-17 回帰。定額があった頃は「1つでも表に無い素材が混ざると合計を捨てて定額へ戻す」
     * 全か無かの分岐だったため、<b>素材を1つ足すとEXPが落ちる</b>向きの不整合が出ていた
     * (実際に binder_spear が 100 → 1 になっていた)。定額を消したので、
     * 表に無い素材は「その素材ぶんが乗らないだけ」で単調になる。
     */
    @Test
    @DisplayName("表に無い素材が混ざっても、載っている素材ぶんは失われない")
    void unlistedMaterialsDoNotWipeOutTheListedOnes() {
        PlayerMock player = server.addPlayer();
        NativeExperienceDispatcher dispatcher = wireConfig(100.0, 0.0);

        ArsProgressionBridge.grantSmithingCraftExp(
                MockBukkit.createMockPlugin(), player, new ItemStack(Material.BOOK),
                java.util.List.of("IRON_INGOT", "custom:not_in_the_table"), 0);

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
