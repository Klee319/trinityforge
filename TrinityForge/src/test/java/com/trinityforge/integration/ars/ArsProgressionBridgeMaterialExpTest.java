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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    @DisplayName("素材が1つも表に無ければEXPは付かない(定額へ戻さない ―― 定額は2026-08-17に廃止)")
    void unknownMaterialsYieldNothing() {
        PlayerMock player = server.addPlayer();
        NativeExperienceDispatcher dispatcher = stubTrinityForge(Map.of("IRON_INGOT", 8.0));

        ArsProgressionBridge.grantSmithingCraftExp(MockBukkit.createMockPlugin(), player,
                new ItemStack(Material.BOOK), List.of("custom:表に無い素材"));

        // 0 は grantSkillExp が早期returnするので dispatcher は呼ばれない(以前は定額100が入っていた)。
        verify(dispatcher, never()).grant(any(), eq(SkillId.ARS_SMITHING), anyDouble());
    }

    /**
     * 定額があった間は「1つでも表に無い素材があれば合計を捨てて定額へ戻す」という全か無かの分岐が
     * 必要で、<b>素材を1つ足すとEXPが100分の1に落ちる</b>向きの不整合が実際に出ていた
     * ({@code binder_spear} が 100 → 1。同格の {@code binder_sword} は全素材が表に無いおかげで
     * 100 のまま)。定額を消したので部分カバーは「その素材ぶんが乗らないだけ」で単調になる。
     */
    @Test
    @DisplayName("部分カバーでも引けた素材ぶんは素直に積む(足してEXPが落ちる向きの分岐が無い)")
    void partialCoverageIsMonotonic() {
        PlayerMock player = server.addPlayer();
        NativeExperienceDispatcher dispatcher = stubTrinityForge(Map.of("STICK", 0.5));

        ArsProgressionBridge.grantSmithingCraftExp(MockBukkit.createMockPlugin(), player,
                new ItemStack(Material.BOOK),
                List.of("custom:binder_fragment", "custom:abyssal_ingot",
                        "custom:dungeon_seal_binder", "custom:reality_thread_core",
                        "STICK", "STICK"));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 1.0);
    }

    /**
     * 2026-08-17: 儀式EXPの表は<b>儀式専用</b>({@code ars-smithing.exp-per-material})。
     * editor で通常鍛冶({@code smithing.exp-per-material})の素材リストを編集しても
     * Ars 側は動いてはいけない ―― ここが共用に戻ると必ず落ちる。
     */
    @Test
    @DisplayName("儀式EXPは作業台の素材表(smithing.exp-per-material)を読まない")
    void ritualExpIgnoresTheWorkbenchTable() {
        PlayerMock player = server.addPlayer();
        SkillExpConfig skillExp = mock(SkillExpConfig.class);
        when(skillExp.arsSmithingExpPerMaterial()).thenReturn(Map.of("IRON_INGOT", 8.0));
        when(skillExp.smithingExpPerMaterial()).thenReturn(Map.of("IRON_INGOT", 999.0));
        when(skillExp.useLevelExpMultiplier(SkillId.ARS_SMITHING, 0)).thenReturn(1.0);

        ConfigManager config = mock(ConfigManager.class);
        when(config.skillExp()).thenReturn(skillExp);
        when(config.itemStats()).thenReturn(mock(ItemStatsConfig.class));
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.config()).thenReturn(config);
        when(tf.experienceDispatcher()).thenReturn(dispatcher);
        TrinityForgeSingletonTestSupport.set(tf);

        ArsProgressionBridge.grantSmithingCraftExp(MockBukkit.createMockPlugin(), player,
                new ItemStack(Material.BOOK), List.of("IRON_INGOT"));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ARS_SMITHING, 8.0);
    }

    @Test
    @DisplayName("素材を渡さない旧シグネチャはEXPが付かない(定額が無いので素材ぶんも無い)")
    void legacyCallSiteGrantsNothing() {
        PlayerMock player = server.addPlayer();
        NativeExperienceDispatcher dispatcher = stubTrinityForge(Map.of("IRON_INGOT", 8.0));

        ArsProgressionBridge.grantSmithingCraftExp(MockBukkit.createMockPlugin(), player,
                new ItemStack(Material.BOOK));

        verify(dispatcher, never()).grant(any(), eq(SkillId.ARS_SMITHING), anyDouble());
    }

    /** 儀式専用の素材表 {@code perMaterial}・use-level倍率1.0 の TF シングルトンを立てる。 */
    private NativeExperienceDispatcher stubTrinityForge(Map<String, Double> perMaterial) {
        SkillExpConfig skillExp = mock(SkillExpConfig.class);
        when(skillExp.arsSmithingExpPerMaterial()).thenReturn(perMaterial);
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
