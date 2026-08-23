package com.trinityforge.skilltree.runtime;

import org.bukkit.Material;

import java.util.Map;
import java.util.Objects;

/** Selects dependency-free item models for the native skill-tree GUI. */
public final class SkillTreeGuiVisuals {

    private static final Map<String, Visual> CONTROLS = Map.of(
            "move-nw", new Visual(Material.ARROW, "gui/skilltree_nw"),
            "move-n", new Visual(Material.ARROW, "gui/skilltree_n"),
            "move-ne", new Visual(Material.ARROW, "gui/skilltree_ne"),
            "move-e", new Visual(Material.ARROW, "gui/skilltree_e"),
            "move-se", new Visual(Material.ARROW, "gui/skilltree_se"),
            "move-s", new Visual(Material.ARROW, "gui/skilltree_s"),
            "move-sw", new Visual(Material.ARROW, "gui/skilltree_sw"),
            "move-w", new Visual(Material.ARROW, "gui/skilltree_w"),
            // 2026-08-04新設: 通常モード⇔一覧モード(スキルアイコンだけの格子表示)の切替ボタン。
            // 新しいリソースパックCMDは要求しない(itemModel未指定=素のバニラ材質を表示する)。
            "toggle-view", new Visual(Material.COMPASS, null),
            // 2026-08-05新設(W-29): 通常モード⇔パーク一覧モード(現ツリーの全パークの格子表示)の
            // 切替ボタン。ユーザー指定の「時計アイコン」なので材質は CLOCK 固定。
            // toggle-view と同じくリソースパックCMDは要求しない。
            "perk-list", new Visual(Material.CLOCK, null));

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
            // 2026-07-28: DIGGING は WOODCUTTING と同じ landscaping モデルに固定されていたため、
            // スキル選択GUIで伐採と切削が同じアイコンになり、digging.yml の icon: IRON_SHOVEL が
            // 無視されていた。専用モデルが無いスキルはここに載せず、config のアイコンを使わせる。
            Map.entry("WOODCUTTING", "landscaping"));

    private SkillTreeGuiVisuals() {
    }

    public static Visual node(boolean unlocked, boolean unlockable, boolean pending, Material configuredIcon) {
        Objects.requireNonNull(configuredIcon, "configuredIcon");
        if (unlocked) {
            // 解放済みノードは共通モデル(gui/node_unlocked)ではなく、editorで指定されたperkアイコンを
            // そのまま表示する(2026-07-22 ユーザー方針)。解放済みの区別は名前色(緑)とlore「解放済み」が担う。
            return new Visual(configuredIcon, null);
        }
        if (!unlockable) {
            // 未解放パークは南京錠アイコン(リソパの gui/node_locked)。
            // 2026-08-22: 基底材質を ROTTEN_FLESH から TRIAL_KEY へ変更した。リソースパックを
            // 当てていないクライアント(統合版・パック拒否)にはこの材質がそのまま出るため、
            // 「腐肉」ではなく「鍵」が見える方が意味が通る。パックを当てていれば見た目は不変。
            //
            // ここを「1種類の絵に潰す」意味は Ars のグリフ未解放(石炭)と同じだが、
            // **絵は別物にしておくこと**(ユーザー指示 2026-08-22)。スキルパークとグリフは
            // 別系統の解放なので、同じ絵にすると画面をまたいだときに混ざる。
            return new Visual(Material.TRIAL_KEY, "gui/node_locked");
        }
        if (pending) {
            return new Visual(Material.STRUCTURE_VOID, "gui/node_confirm");
        }
        return new Visual(configuredIcon, null);
    }

    public static Visual control(String action) {
        Visual visual = CONTROLS.get(action);
        if (visual == null) {
            throw new IllegalArgumentException("unknown skill-tree control: " + action);
        }
        return visual;
    }

    public static Visual connector(ConnectorState state, String suffix) {
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

    public static Visual skill(String skillId, Material fallback) {
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(fallback, "fallback");
        String model = SKILL_MODELS.get(skillId);
        return new Visual(fallback, model == null ? null : "gui/skill/" + model);
    }

    public enum ConnectorState {
        LOCKED("locked"),
        UNLOCKABLE("unlockable"),
        UNLOCKED("unlocked");

        private final String path;

        ConnectorState(String path) {
            this.path = path;
        }
    }

    public record Visual(Material material, String itemModel) {
        public Visual {
            Objects.requireNonNull(material, "material");
        }
    }
}
