package com.trinityforge.skilltree.generator;

import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, offline generator turning one TF canonical {@link SkillTree} (SKILL_TREE design sections A–E)
 * into a ValhallaMMO {@code <skill>_progression.yml} document plus the {@code lang} map it references
 * (design section 2). No Bukkit, no I/O, no reflection — the same input always yields byte-identical
 * output, so structure, coordinates, exclusive wiring and lang integrity are all verifiable offline.
 *
 * <h2>Mapping (per the real ValhallaMMO progression format, which wins over the design doc on conflicts)</h2>
 * <ul>
 *   <li>node id → perk id {@code <compact>_perk_<normalizedId>}; {@code name}/{@code effect-text} →
 *       {@code <lang.PERK_name>} / {@code <lang.PERK_description>} with matching lang entries.</li>
 *   <li>{@code level} → {@code required_lv}; {@code parent} → {@code requireperk_all: [<parentPerk>]};
 *       {@code icon}/{@code cost} pass through.</li>
 *   <li>{@code native} → {@code perk_rewards} (verbatim); TF {@code buffs} are <b>never</b> emitted to
 *       {@code perk_rewards} (TF applies them — double-application guard).</li>
 *   <li>exclusive {@code group} → reciprocal {@code perk_rewards.perks_locked_add} across members.</li>
 *   <li>{@code prestige} → a chain of visible {@code <compact>_perk_ng1..ng<maxTimes>} perks (TOTEM at the
 *       top of the trunk), each carrying {@code reset_skill_<snake>} and {@code p:}-prefixed permanent
 *       rewards keyed to its own tier id; TF buffs are likewise excluded from every tier.</li>
 *   <li>A synthetic lv0 {@code <compact>_perk_root} sits at {@code starting_coordinates} (tree icon, cost 0);
 *       the lowest MAIN requires it and connects from it.</li>
 * </ul>
 *
 * <h2>Deviations from ValhallaMMO's shipped files (deliberate)</h2>
 * <ul>
 *   <li>Perk ids follow the real {@code <compact>_perk_<id>} shape, not the design doc's
 *       {@code <skill>_<id>}.</li>
 *   <li>Layout is TF's vertical trunk layout (design section 1), not ValhallaMMO's default stacks.</li>
 *   <li>Only structural blocks TF owns are emitted ({@code starting_coordinates} + {@code perks}); the
 *       {@code experience}/{@code leveling_perks}/{@code messages} blocks are out of scope for TF config
 *       and are supplied by a separate template merge.</li>
 *   <li>{@code prestige.max-times} (プレステージ上限回数, default 1) fabricates {@code ng1..ngN}: tier
 *       {@code K} requires tier {@code K-1}'s perk via {@code requireperk_all}, so every tier is a strictly
 *       sequential, cumulative unlock.</li>
 * </ul>
 */
public final class SkillTreeProgressionGenerator {

    /** Fallback perk icon when neither the node nor the tree supplies one. */
    private static final String DEFAULT_ICON = "STONE";
    /** Icon used for the generated prestige (New-Game+) perk, matching ValhallaMMO convention. */
    private static final String PRESTIGE_ICON = "TOTEM_OF_UNDYING";
    /** Rows above the topmost MAIN at which each prestige tier is parked. */
    private static final int PRESTIGE_ROW_OFFSET = SkillTreeLayout.TRUNK_STEP;

    // コネクタのdyeトークンは "<COLOR>_DYE:1172[7/8/9]<2桁向きコード>"。色系統=ロック状態(GRAY=locked/
    // ORANGE=unlockable/LIME=unlocked)、末尾2桁=向き(縦/横/角)。ValhallaMMOの同梱progression ymlの実使用から
    // 復号した対応表(00縦/06縦内部/10-11縦端/07横内部/08-09横端/12-23各種角)に従い、セルごとに出し分ける。
    // 旧実装は全セル "00"(縦・両端ノード)固定だったため縦横の描き分けができていなかった。
    private static final String LOCKED_BASE = "GRAY_DYE:11727";
    private static final String UNLOCKABLE_BASE = "ORANGE_DYE:11728";
    private static final String UNLOCKED_BASE = "LIME_DYE:11729";

    /** Emission order: mains, then intermediates, branches, greeks; each by (level, id). */
    private static final Comparator<SkillNode> NODE_ORDER = Comparator
            .comparingInt((SkillNode n) -> roleRank(n.role()))
            .thenComparingInt(SkillNode::level)
            .thenComparing(SkillNode::id);

    private SkillTreeProgressionGenerator() {
    }

    /**
     * Generates the progression YAML + lang map + structured perks for one tree (SKILL_TREE design
     * sections A–E). Dedicated-effects placements ({@code dedicated-effects[]}) are schema/gate-index data
     * only (2026-07-23 動的ID方式改修 §3) — none of them are compiled into {@code perk_rewards} here; a
     * node's perk id merely needing to appear in the dynamic gate index is handled entirely by
     * {@code DedicatedEffectsConfig}/{@code DedicatedEffectGateIndex} off of the same {@code SkillTree}
     * model, not by this generator. Never mutates input.
     */
    public static GeneratedProgression generate(SkillTree tree) {
        Objects.requireNonNull(tree, "tree");
        String skill = Objects.requireNonNull(tree.skill(), "tree.skill");
        SkillTreeLayout layout = new SkillTreeLayout(tree);

        List<SkillNode> ordered = tree.nodes().values().stream().sorted(NODE_ORDER).toList();

        LinkedHashMap<String, GeneratedPerk> perks = new LinkedHashMap<>();
        LinkedHashMap<String, String> lang = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();

        GeneratedPerk root = buildRootPerk(tree, layout);
        seen.add(root.id());
        perks.put(root.id(), root);
        lang.put(root.nameKey(), root.name());
        lang.put(root.descriptionKey(), root.description());

        for (SkillNode node : ordered) {
            GeneratedPerk perk = buildNodePerk(tree, node, layout, root.id());
            if (!seen.add(perk.id())) {
                throw new IllegalStateException(
                        "perk id collision '" + perk.id() + "' from node '" + node.id() + "'");
            }
            perks.put(perk.id(), perk);
            lang.put(perk.nameKey(), perk.name());
            lang.put(perk.descriptionKey(), perk.description());
        }

        Prestige prestige = tree.prestige();
        if (prestige != null && prestige.enabled()) {
            // maxTimes(プレステージ上限回数)分のtierを1から順に生成: 各tierはひとつ前のtier完了を
            // requireperk_allで要求する鎖状構造(runtimeでは累積的に全tier分のbuffが加算される)。
            for (int tier = 1; tier <= prestige.maxTimes(); tier++) {
                GeneratedPerk ng = buildPrestigePerk(skill, tree.displayName(), prestige, layout, tier);
                if (!seen.add(ng.id())) {
                    throw new IllegalStateException("prestige perk id collision '" + ng.id() + "'");
                }
                perks.put(ng.id(), ng);
                lang.put(ng.nameKey(), ng.name());
                lang.put(ng.descriptionKey(), ng.description());
            }
        }

        String yaml = ProgressionYaml.emit(buildDocument(layout, perks));
        return new GeneratedProgression(skill, layout.startingCoordinates(), perks, lang, yaml);
    }

    /**
     * Synthetic lv0 root at {@link SkillTreeLayout#rootCoord()}: tree icon, free unlock, no rewards.
     * Lowest MAIN nodes (no parent / parent absent) require this perk and connect from it.
     */
    private static GeneratedPerk buildRootPerk(SkillTree tree, SkillTreeLayout layout) {
        String perkId = PerkNaming.rootPerkId(tree.skill());
        String name = safe(tree.displayName(), tree.skill()) + "の基礎";
        return new GeneratedPerk(
                perkId,
                icon(null, tree.icon()),
                layout.rootCoord(),
                0,
                0,
                false,
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                name,
                "スキルツリーの起点。コスト0で解放できる。",
                PerkNaming.nameLangKey(perkId),
                PerkNaming.descriptionLangKey(perkId));
    }

    private static GeneratedPerk buildNodePerk(SkillTree tree, SkillNode node, SkillTreeLayout layout,
                                               String rootPerkId) {
        String skill = tree.skill();
        String perkId = PerkNaming.perkId(skill, node.id());
        Coord coord = layout.coordOf(node.id());

        List<String> requirePerkAll = List.of();
        List<String> requirePerkOne = List.of();
        Map<String, Map<String, Object>> connectionLine = Map.of();
        List<String> prerequisites = node.prerequisiteParents().stream()
                .filter(tree.nodes()::containsKey)
                .map(parent -> PerkNaming.perkId(skill, parent))
                .toList();
        if (!prerequisites.isEmpty()) {
            if (prerequisites.size() == 1) {
                requirePerkAll = prerequisites;
            } else {
                requirePerkOne = prerequisites;
            }
            connectionLine = buildPrerequisiteConnections(tree, node, layout, coord);
        } else if (node.role() == SkillRole.MAIN && isLowestMain(tree, node)) {
            // Trunk root: wire the first MAIN to the synthetic lv0 perk.
            requirePerkAll = List.of(rootPerkId);
            connectionLine = buildConnectionLine(layout, layout.rootCoord(), coord);
        }

        return new GeneratedPerk(
                perkId,
                icon(node.icon(), tree.icon()),
                coord,
                node.level(),
                node.cost(),
                false,
                requirePerkAll,
                requirePerkOne,
                buildNodeRewards(tree, node),
                connectionLine,
                safe(node.name()),
                safe(node.effectText()),
                PerkNaming.nameLangKey(perkId),
                PerkNaming.descriptionLangKey(perkId));
    }

    /** True when {@code node} is a MAIN with the lowest level (ties broken by id) among MAIN nodes. */
    private static boolean isLowestMain(SkillTree tree, SkillNode node) {
        if (node.role() != SkillRole.MAIN) {
            return false;
        }
        return tree.nodes().values().stream()
                .filter(n -> n.role() == SkillRole.MAIN)
                .min(Comparator.comparingInt(SkillNode::level).thenComparing(SkillNode::id))
                .map(n -> n.id().equals(node.id()))
                .orElse(false);
    }

    private static Map<String, Map<String, Object>> buildPrerequisiteConnections(
            SkillTree tree, SkillNode node, SkillTreeLayout layout, Coord child) {
        LinkedHashMap<String, Map<String, Object>> result = new LinkedHashMap<>();
        int index = 1;
        for (String parentId : node.prerequisiteParents()) {
            if (!tree.nodes().containsKey(parentId)) continue;
            for (Map<String, Object> segment
                    : buildConnectionLine(layout, layout.coordOf(parentId), child).values()) {
                result.put(Integer.toString(index++), segment);
            }
        }
        return result;
    }

    /**
     * Builds {@code perk_rewards}: the node's ValhallaMMO-native entries (keys sorted for determinism),
     * followed by, for an exclusive greek node, the reciprocal {@code perks_locked_add} list. TF buffs and
     * every {@code dedicated-effects[]} placement are never read here (2026-07-23 動的ID方式改修: dedicated
     * effects are gate-index-only data now, never compiled into {@code perk_rewards}), so neither can ever
     * leak into {@code perk_rewards}.
     */
    private static Map<String, Object> buildNodeRewards(SkillTree tree, SkillNode node) {
        LinkedHashMap<String, Object> rewards = new LinkedHashMap<>();
        node.native_().keySet().stream().sorted()
                .forEach(key -> rewards.put(key, node.native_().get(key)));

        String group = node.group();
        if (group != null) {
            // 排他は「同じ親から出る同groupの兄弟」だけ。連鎖greek(親違い・同group)を丸ごとロックしない。
            List<String> others = tree.nodes().values().stream()
                    .filter(other -> group.equals(other.group())
                            && Objects.equals(node.parent(), other.parent())
                            && !other.id().equals(node.id()))
                    .sorted(Comparator.comparing(SkillNode::id))
                    .map(other -> PerkNaming.perkId(tree.skill(), other.id()))
                    .toList();
            if (!others.isEmpty()) {
                rewards.put("perks_locked_add", others);
            }
        }
        return rewards;
    }

    /**
     * Builds one prestige tier's perk. Parked above the topmost MAIN ({@code top.y - tier * OFFSET}).
     * Tier 1 connects visually from the top MAIN (gate remains {@code required_lv} alone); tier
     * {@code K > 1} requires tier {@code K-1}. Visible ({@code hidden=false}) so the TOTEM anchors the
     * trunk apex.
     */
    private static GeneratedPerk buildPrestigePerk(String skill, String displayName, Prestige prestige,
                                                   SkillTreeLayout layout, int tier) {
        String perkId = PerkNaming.prestigePerkId(skill, tier);
        SkillNode topMain = layout.topMain();
        Coord coord = layout.prestigeCoord(tier);

        List<String> requirePerkAll = tier == 1
                ? List.of()
                : List.of(PerkNaming.prestigePerkId(skill, tier - 1));

        Map<String, Map<String, Object>> connectionLine = Map.of();
        if (tier == 1 && topMain != null) {
            connectionLine = buildConnectionLine(layout, layout.coordOf(topMain.id()), coord);
        } else if (tier > 1) {
            Coord prev = layout.prestigeCoord(tier - 1);
            connectionLine = buildConnectionLine(layout, prev, coord);
        }

        LinkedHashMap<String, Object> rewards = new LinkedHashMap<>();
        rewards.put(PerkNaming.resetSkillKey(skill), 0);
        prestige.native_().keySet().stream().sorted()
                .forEach(key -> rewards.put("p:" + key, prestige.native_().get(key)));
        rewards.put("p:perks_permanently_unlocked_add", List.of(perkId));
        rewards.put("p:perks_unlocked_remove", List.of(perkId));

        return new GeneratedPerk(
                perkId,
                PRESTIGE_ICON,
                coord,
                prestige.atLevel(),
                0,
                false,
                requirePerkAll,
                List.of(),
                rewards,
                connectionLine,
                safe(prestige.name(), displayName + " プレステージ"),
                safe(prestige.effectText()),
                PerkNaming.nameLangKey(perkId),
                PerkNaming.descriptionLangKey(perkId));
    }

    /**
     * An orthogonal path of grid cells strictly between {@code parent} and {@code child}: up/down the
     * parent's column, then across the child's row when needed. Endpoints (the two perk icons) are
     * excluded. Intermediate cells use vertical or horizontal connector dyes only (no L-corner parts).
     */
    private static Map<String, Map<String, Object>> buildConnectionLine(
            SkillTreeLayout layout, Coord parent, Coord child) {
        List<Coord> path = layout.route(parent, child);
        LinkedHashMap<String, Map<String, Object>> line = new LinkedHashMap<>();
        int index = 1;
        for (int i = 1; i < path.size() - 1; i++) { // 両端(node)を除く中間セルだけ
            Coord cell = path.get(i);
            Coord before = path.get(i - 1);
            Coord after = path.get(i + 1);
            boolean beforeNode = (i - 1) == 0;               // 直前が親node
            boolean afterNode = (i + 1) == path.size() - 1;  // 直後が子node
            String suffix = connectorSuffix(cell, before, after, beforeNode, afterNode);
            LinkedHashMap<String, Object> segment = new LinkedHashMap<>();
            segment.put("position", cell.format());
            segment.put("locked", LOCKED_BASE + suffix);
            segment.put("unlockable", UNLOCKABLE_BASE + suffix);
            segment.put("unlocked", UNLOCKED_BASE + suffix);
            line.put(Integer.toString(index++), segment);
        }
        return line;
    }

    /**
     * 1つのコネクタセルの2桁向きコードを、前後の経路要素との位置関係から決める。grid の y は下方向に増える
     * (N=上=y小, S=下=y大, E=右=x大, W=左=x小)。ValhallaMMO同梱ymlから復号した対応:
     * 縦=00(両端node)/10(上node)/11(下node)/06(両端stick)、横=07(両端stick)/08(東node)/09(西node)。
     * 角セルは L字(12–23)を使わず、縦/横の直線パーツにフォールバックする。
     */
    private static String connectorSuffix(Coord cell, Coord before, Coord after,
                                          boolean beforeNode, boolean afterNode) {
        // 2026-07-29: 実体は GridConnectorRouting へ集約(アチーブメントGUIと共有)。
        return GridConnectorRouting.suffix(cell, before, after, beforeNode, afterNode);
    }

    private static Map<String, Object> buildDocument(SkillTreeLayout layout,
                                                     Map<String, GeneratedPerk> perks) {
        LinkedHashMap<String, Object> document = new LinkedHashMap<>();
        document.put("starting_coordinates", layout.startingCoordinates());

        LinkedHashMap<String, Object> perksBlock = new LinkedHashMap<>();
        for (GeneratedPerk perk : perks.values()) {
            perksBlock.put(perk.id(), perkToMapping(perk));
        }
        document.put("perks", perksBlock);
        return document;
    }

    private static Map<String, Object> perkToMapping(GeneratedPerk perk) {
        LinkedHashMap<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("icon", perk.icon());
        mapping.put("coords", perk.coords().format());
        // Literal display text (not a <lang.*> ref): TF deploys progression ymls but never merges
        // ValhallaMMO's shared language file, so inlining keeps names/descriptions readable in-game.
        mapping.put("name", perk.name());
        mapping.put("description", perk.description());
        mapping.put("cost", perk.cost());
        mapping.put("hidden", perk.hidden());
        mapping.put("required_lv", perk.requiredLv());
        mapping.put("perk_rewards", perk.perkRewards());
        if (!perk.requirePerkAll().isEmpty()) {
            mapping.put("requireperk_all", perk.requirePerkAll());
        }
        if (!perk.requirePerkOne().isEmpty()) {
            mapping.put("requireperk_one", perk.requirePerkOne());
        }
        if (!perk.connectionLine().isEmpty()) {
            mapping.put("connection_line", perk.connectionLine());
        }
        return mapping;
    }

    private static int roleRank(SkillRole role) {
        return switch (role) {
            case MAIN -> 0;
            case INTERMEDIATE -> 1;
            case BRANCH -> 2;
            case GREEK -> 3;
        };
    }

    private static String icon(String nodeIcon, String treeIcon) {
        if (nodeIcon != null && !nodeIcon.isBlank()) {
            return nodeIcon;
        }
        if (treeIcon != null && !treeIcon.isBlank()) {
            return treeIcon;
        }
        return DEFAULT_ICON;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
