package com.trinityforge.skilltree.runtime;

import org.bukkit.Material;

import java.util.Map;
import java.util.Objects;

/** Selects dependency-free item models for the native skill-tree GUI. */
final class SkillTreeGuiVisuals {

    private static final Map<String, Visual> CONTROLS = Map.of(
            "move-nw", new Visual(Material.ARROW, "gui/skilltree_nw"),
            "move-n", new Visual(Material.ARROW, "gui/skilltree_n"),
            "move-ne", new Visual(Material.ARROW, "gui/skilltree_ne"),
            "move-e", new Visual(Material.ARROW, "gui/skilltree_e"),
            "move-se", new Visual(Material.ARROW, "gui/skilltree_se"),
            "move-s", new Visual(Material.ARROW, "gui/skilltree_s"),
            "move-sw", new Visual(Material.ARROW, "gui/skilltree_sw"),
            "move-w", new Visual(Material.ARROW, "gui/skilltree_w"));

    private static final Map<String, String> CONNECTOR_SHAPES = Map.ofEntries(
            Map.entry("00", "direct_vertical"),
            Map.entry("06", "continuous_vertical"),
            Map.entry("07", "continuous_horizontal"),
            Map.entry("08", "endpoint_horiz_right"),
            Map.entry("09", "endpoint_horiz_left"),
            Map.entry("10", "endpoint_verti_top"),
            Map.entry("11", "endpoint_verti_bottom"),
            Map.entry("12", "corner_ne"),
            Map.entry("13", "corner_se"),
            Map.entry("14", "corner_sw"),
            Map.entry("15", "corner_nw"),
            Map.entry("16", "junction_new"),
            Map.entry("17", "junction_nes"),
            Map.entry("18", "junction_esw"),
            Map.entry("19", "junction_nsw"),
            Map.entry("20", "cross_nesw"));
    private static final Map<String, String> SKILL_MODELS = Map.ofEntries(
            Map.entry("POWER", "power"),
            Map.entry("MINING", "mining"),
            Map.entry("FARMING", "farming"),
            Map.entry("ARCHERY", "archery"),
            Map.entry("LIGHT_WEAPONS", "lightweapons"),
            Map.entry("HEAVY_WEAPONS", "heavyweapons"),
            Map.entry("LIGHT_ARMOR", "lightarmor"),
            Map.entry("HEAVY_ARMOR", "heavyarmor"),
            Map.entry("WOODCUTTING", "landscaping"),
            Map.entry("DIGGING", "landscaping"));

    private SkillTreeGuiVisuals() {
    }

    static Visual node(boolean unlocked, boolean unlockable, boolean pending, Material configuredIcon) {
        Objects.requireNonNull(configuredIcon, "configuredIcon");
        if (unlocked) {
            // 解放済みノードは共通モデル(gui/node_unlocked)ではなく、editorで指定されたperkアイコンを
            // そのまま表示する(2026-07-22 ユーザー方針)。解放済みの区別は名前色(緑)とlore「解放済み」が担う。
            return new Visual(configuredIcon, null);
        }
        if (!unlockable) {
            return new Visual(Material.ROTTEN_FLESH, "gui/node_locked");
        }
        if (pending) {
            return new Visual(Material.STRUCTURE_VOID, "gui/node_confirm");
        }
        return new Visual(configuredIcon, null);
    }

    static Visual control(String action) {
        Visual visual = CONTROLS.get(action);
        if (visual == null) {
            throw new IllegalArgumentException("unknown skill-tree control: " + action);
        }
        return visual;
    }

    static Visual connector(ConnectorState state, String suffix) {
        Objects.requireNonNull(state, "state");
        String shape = CONNECTOR_SHAPES.get(suffix);
        if (shape == null) {
            throw new IllegalArgumentException("unknown skill-tree connector suffix: " + suffix);
        }
        Material material = switch (state) {
            case LOCKED -> Material.GRAY_DYE;
            case UNLOCKABLE -> Material.ORANGE_DYE;
            case UNLOCKED -> Material.LIME_DYE;
        };
        return new Visual(material, "gui/connection/" + state.path + "_" + shape);
    }

    static Visual skill(String skillId, Material fallback) {
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(fallback, "fallback");
        String model = SKILL_MODELS.get(skillId);
        return new Visual(fallback, model == null ? null : "gui/skill/" + model);
    }

    enum ConnectorState {
        LOCKED("locked"),
        UNLOCKABLE("unlockable"),
        UNLOCKED("unlocked");

        private final String path;

        ConnectorState(String path) {
            this.path = path;
        }
    }

    record Visual(Material material, String itemModel) {
        Visual {
            Objects.requireNonNull(material, "material");
        }
    }
}
