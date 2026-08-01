package com.trinityforge.integration.ars;

import com.trinityforge.TrinityForge;
import com.trinityforge.TrinityForgeSingletonTestSupport;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * U1/N6: Ars鍛冶(儀式)EXPを素材ごとの表({@code smithing.exp-per-material})から引く配線と、
 * 素材トークンが ArsPaper の刻印({@code arspaper:custom_item_id})も読むことの回帰テスト。
 *
 * <p>後者は<b>出荷ymlの {@code custom:} 行がほぼ全滅していた</b>実バグ: 表の {@code custom:} キーは
 * source_gem / magebloom_fiber / hard_metal … と ArsPaper の materials.yml 由来のIDが大半なのに、
 * 旧実装は TF カタログの PDC しか読んでいなかったため 1 行も引けず常に 0 だった。
 */
class ArsProgressionBridgeMaterialExpTest {

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

    // ------------------------------------------------------------------
    // 素材表の合計(純関数)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("素材トークンごとに表を引いて合計する(同じ素材2個なら2回積む)")
    void sumsEveryTokenIndependently() {
        Map<String, Double> table = Map.of("IRON_INGOT", 8.0, "custom:source_gem", 20.0);
        assertEquals(36.0, ArsProgressionBridge.sumMaterialExp(
                List.of("IRON_INGOT", "IRON_INGOT", "custom:source_gem"), table));
    }

    @Test
    @DisplayName("表に無い素材は0で積む(暗黙の既定値を出さない)")
    void unlistedMaterialsContributeNothing() {
        Map<String, Double> table = Map.of("IRON_INGOT", 8.0);
        assertEquals(8.0, ArsProgressionBridge.sumMaterialExp(
                List.of("IRON_INGOT", "DIRT", "custom:未登録"), table));
        assertEquals(0.0, ArsProgressionBridge.sumMaterialExp(List.of("DIRT"), table));
    }

    @Test
    @DisplayName("トークンはconfigと同じ正規化を通す(バニラは大文字化・customは原文維持)")
    void tokensAreNormalizedLikeTheConfig() {
        Map<String, Double> table = Map.of("IRON_INGOT", 8.0, "custom:source_gem", 20.0);
        assertEquals(8.0, ArsProgressionBridge.sumMaterialExp(List.of("iron_ingot"), table),
                "バニラ Material 名は大小を無視して引けること");
        assertEquals(0.0, ArsProgressionBridge.sumMaterialExp(List.of("custom:SOURCE_GEM"), table),
                "カタログIDは大小を潰してはいけない(ymlのキーとPDCの値は完全一致が前提)");
    }

    @Test
    @DisplayName("表が空 / トークンが空なら0(呼び出し側が定額へ倒せるように)")
    void emptyInputsYieldZero() {
        assertEquals(0.0, ArsProgressionBridge.sumMaterialExp(List.of("IRON_INGOT"), Map.of()));
        assertEquals(0.0, ArsProgressionBridge.sumMaterialExp(List.of(), Map.of("IRON_INGOT", 8.0)));
        assertEquals(0.0, ArsProgressionBridge.sumMaterialExp(null, Map.of("IRON_INGOT", 8.0)));
    }

    // ------------------------------------------------------------------
    // 素材トークンの解決
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ArsPaper の刻印(arspaper:custom_item_id)から custom: トークンを起こす")
    void arsStampedItemsResolveToCustomTokens() {
        ItemStack stack = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(
                new NamespacedKey("arspaper", "custom_item_id"),
                PersistentDataType.STRING, "source_gem");
        stack.setItemMeta(meta);

        assertEquals("custom:source_gem", ArsProgressionBridge.materialToken(stack),
                "ここが Material 名に落ちると出荷表の custom: 行が全部死ぬ");
    }

    @Test
    @DisplayName("TFカタログの刻印は従来どおり custom:<catalogId>")
    void catalogStampedItemsKeepTheirToken() {
        ItemStack stack = new ItemStack(Material.STRING);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId("thread_empty");
        stack.setItemMeta(meta);

        assertEquals("custom:thread_empty", ArsProgressionBridge.materialToken(stack));
    }

    @Test
    @DisplayName("どちらの刻印も無ければ Material 名")
    void plainItemsUseTheirMaterialName() {
        assertEquals("IRON_INGOT", ArsProgressionBridge.materialToken(new ItemStack(Material.IRON_INGOT)));
        assertEquals("", ArsProgressionBridge.materialToken(null));
    }

    // ------------------------------------------------------------------
    // 儀式EXPの配線
    // ------------------------------------------------------------------

    @Test
    @DisplayName("儀式EXPは消費素材の合計から決まる(定額ではない)")
    void ritualExpComesFromTheMaterialTable() {
        PlayerMock player = server.addPlayer();
        NativeExperienceDispatcher dispatcher = stubTrinityForge(
                Map.of("custom:source_gem", 20.0, "IRON_INGOT", 8.0));

        ArsProgressionBridge.grantSmithingCraftExp(MockBukkit.createMockPlugin(), player,
                new ItemStack(Material.BOOK), List.of("custom:source_gem", "IRON_INGOT"));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 28.0);
    }

    @Test
    @DisplayName("素材が1つも表に無ければ従来の定額へ戻す(儀式EXPが無言で消えない)")
    void unknownMaterialsFallBackToTheFlatAmount() {
        PlayerMock player = server.addPlayer();
        NativeExperienceDispatcher dispatcher = stubTrinityForge(Map.of("IRON_INGOT", 8.0));

        ArsProgressionBridge.grantSmithingCraftExp(MockBukkit.createMockPlugin(), player,
                new ItemStack(Material.BOOK), List.of("custom:表に無い素材"));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 100.0);
    }

    @Test
    @DisplayName("1つでも表に無い素材があれば合計を捨てて定額へ戻す(安い素材を足してEXPが激減しない)")
    void partialCoverageFallsBackToTheFlatAmountInsteadOfTheStubSum() {
        PlayerMock player = server.addPlayer();
        NativeExperienceDispatcher dispatcher = stubTrinityForge(Map.of("STICK", 0.5));

        // 実バグの再現形: 最上位素材4種は表に無く、STICK だけが引ける。
        // 「合計>0なら合計」という規則だと 1.0 EXP まで落ちていた(同格で STICK 抜きの品は 100)。
        ArsProgressionBridge.grantSmithingCraftExp(MockBukkit.createMockPlugin(), player,
                new ItemStack(Material.BOOK),
                List.of("custom:binder_fragment", "custom:abyssal_ingot",
                        "custom:dungeon_seal_binder", "custom:reality_thread_core",
                        "STICK", "STICK"));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 100.0);
    }

    @Test
    @DisplayName("allMaterialsListed は全カバーのときだけ true(空入力は false=定額へ倒す)")
    void allMaterialsListedOnlyAcceptsFullCoverage() {
        Map<String, Double> table = Map.of("IRON_INGOT", 8.0, "custom:source_gem", 20.0);
        assertTrue(ArsProgressionBridge.allMaterialsListed(
                List.of("IRON_INGOT", "custom:source_gem"), table));
        assertFalse(ArsProgressionBridge.allMaterialsListed(
                List.of("IRON_INGOT", "DIRT"), table));
        assertFalse(ArsProgressionBridge.allMaterialsListed(List.of(), table));
        assertFalse(ArsProgressionBridge.allMaterialsListed(List.of("IRON_INGOT"), Map.of()));
    }

    @Test
    @DisplayName("素材を渡さない旧シグネチャは定額のまま(既存の呼び出し元を壊さない)")
    void legacyCallSiteKeepsTheFlatAmount() {
        PlayerMock player = server.addPlayer();
        NativeExperienceDispatcher dispatcher = stubTrinityForge(Map.of("IRON_INGOT", 8.0));

        ArsProgressionBridge.grantSmithingCraftExp(MockBukkit.createMockPlugin(), player,
                new ItemStack(Material.BOOK));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 100.0);
    }

    /** 定額100・use-level倍率1.0 の TF シングルトンを立てて dispatcher を返す。 */
    private NativeExperienceDispatcher stubTrinityForge(Map<String, Double> perMaterial) {
        SkillExpConfig skillExp = mock(SkillExpConfig.class);
        when(skillExp.arsSmithingExpPerCraft()).thenReturn(100.0);
        when(skillExp.smithingExpPerMaterial()).thenReturn(perMaterial);
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
}
