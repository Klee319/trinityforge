package com.trinityforge.skilltree.runtime;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SkillTreeGuiVisualsTest {

    @Test
    void lockedNodeUsesValhallaLockTexture() {
        var visual = SkillTreeGuiVisuals.node(false, false, false, Material.BOW);

        assertEquals(Material.ROTTEN_FLESH, visual.material());
        assertEquals("gui/node_locked", visual.itemModel());
    }

    @Test
    void availableNodeKeepsConfiguredIcon() {
        var visual = SkillTreeGuiVisuals.node(false, true, false, Material.BOW);

        assertEquals(Material.BOW, visual.material());
        assertNull(visual.itemModel());
    }

    @Test
    void pendingNodeUsesConfirmTextureButUnlockedKeepsConfiguredIcon() {
        var pending = SkillTreeGuiVisuals.node(false, true, true, Material.BOW);
        var unlocked = SkillTreeGuiVisuals.node(true, false, false, Material.BOW);

        assertEquals(Material.STRUCTURE_VOID, pending.material());
        assertEquals("gui/node_confirm", pending.itemModel());
        // 解放済みノードは共通「解放済み」モデルではなくeditor指定のperkアイコンを維持する(2026-07-22方針)。
        assertEquals(Material.BOW, unlocked.material());
        assertNull(unlocked.itemModel());
    }

    @Test
    void allEightNavigationControlsUseDirectionalValhallaTextures() {
        assertEquals("gui/skilltree_nw", SkillTreeGuiVisuals.control("move-nw").itemModel());
        assertEquals("gui/skilltree_n", SkillTreeGuiVisuals.control("move-n").itemModel());
        assertEquals("gui/skilltree_ne", SkillTreeGuiVisuals.control("move-ne").itemModel());
        assertEquals("gui/skilltree_e", SkillTreeGuiVisuals.control("move-e").itemModel());
        assertEquals("gui/skilltree_se", SkillTreeGuiVisuals.control("move-se").itemModel());
        assertEquals("gui/skilltree_s", SkillTreeGuiVisuals.control("move-s").itemModel());
        assertEquals("gui/skilltree_sw", SkillTreeGuiVisuals.control("move-sw").itemModel());
        assertEquals("gui/skilltree_w", SkillTreeGuiVisuals.control("move-w").itemModel());
    }

    @Test
    void toggleViewControlUsesAPlainVanillaMaterialWithNoCustomItemModel() {
        // 2026-08-04新設: 一覧モード⇔通常モードの切替ボタン。新しいリソースパックCMDを要求しない
        // (itemModel=nullは統合版でも素のバニラ材質のまま描画できる)。
        var visual = SkillTreeGuiVisuals.control("toggle-view");

        assertEquals(Material.COMPASS, visual.material());
        assertNull(visual.itemModel());
    }

    @Test
    void connectorsUseStateAndShapeSpecificValhallaTextures() {
        assertEquals(Material.GRAY_DYE,
                SkillTreeGuiVisuals.connector(SkillTreeGuiVisuals.ConnectorState.LOCKED, "06").material());
        assertEquals("gui/connection/locked_continuous_vertical",
                SkillTreeGuiVisuals.connector(SkillTreeGuiVisuals.ConnectorState.LOCKED, "06").itemModel());
        assertEquals("gui/connection/unlockable_continuous_horizontal",
                SkillTreeGuiVisuals.connector(SkillTreeGuiVisuals.ConnectorState.UNLOCKABLE, "07").itemModel());
        assertEquals("gui/connection/unlocked_endpoint_verti_top",
                SkillTreeGuiVisuals.connector(SkillTreeGuiVisuals.ConnectorState.UNLOCKED, "10").itemModel());
        assertEquals("gui/connection/locked_corner_ne",
                SkillTreeGuiVisuals.connector(SkillTreeGuiVisuals.ConnectorState.LOCKED, "12").itemModel());
        assertEquals("gui/connection/unlockable_junction_new",
                SkillTreeGuiVisuals.connector(SkillTreeGuiVisuals.ConnectorState.UNLOCKABLE, "16").itemModel());
        assertEquals("gui/connection/unlocked_cross_nesw",
                SkillTreeGuiVisuals.connector(SkillTreeGuiVisuals.ConnectorState.UNLOCKED, "20").itemModel());
    }

    @Test
    void skillSelectorsUseOfficialModelsWhereValhallaDefinesThem() {
        assertEquals("gui/skill/power",
                SkillTreeGuiVisuals.skill("POWER", Material.ARMOR_STAND).itemModel());
        assertEquals("gui/skill/landscaping",
                SkillTreeGuiVisuals.skill("WOODCUTTING", Material.IRON_AXE).itemModel());
        // 2026-07-28: DIGGING は WOODCUTTING と同じ landscaping モデルを共有していたため、スキル選択
        // GUIで伐採と切削が同じアイコンになっていた。専用モデルが無いスキルはモデル未指定にして、
        // digging.yml の icon(鉄のシャベル)をそのまま出す。
        assertNull(SkillTreeGuiVisuals.skill("DIGGING", Material.IRON_SHOVEL).itemModel());
        assertEquals(Material.IRON_SHOVEL,
                SkillTreeGuiVisuals.skill("DIGGING", Material.IRON_SHOVEL).material());
        assertEquals(Material.BREWING_STAND,
                SkillTreeGuiVisuals.skill("ALCHEMY", Material.BREWING_STAND).material());
        assertNull(SkillTreeGuiVisuals.skill("ALCHEMY", Material.BREWING_STAND).itemModel());
    }
}
