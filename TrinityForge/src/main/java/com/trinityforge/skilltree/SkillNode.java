package com.trinityforge.skilltree;

import java.util.List;
import java.util.Map;

/**
 * One immutable skill-tree node (SKILL_TREE design section A). A node carries both the TF-owned numeric
 * {@code buffs} (applied by the future PerkBuffResolver, canonical stat keys) and the ValhallaMMO
 * {@code native} perk-reward machinery (applied by Valhalla), plus {@code effectText} — the node's unified
 * free-text description (要件⑤: loaded from {@code description}, falling back to the legacy
 * {@code effect-text} key) — and {@code dedicatedEffects} (要件⑥: catalog-id placements against
 * the {@code dedicated-effects:} field on each node in {@code skilltree/*.yml}, schema/data only — no runtime dispatch yet).
 *
 * <p>{@code commands}/{@code effects} are the legacy, now-unread display-tag lists (要件⑤: superseded by
 * {@code effectText}); TF's loader no longer inspects their contents, but the fields are kept so any
 * lingering YAML data round-trips losslessly through the config editor.
 *
 * <p>All collections are defensively copied and unmodifiable; {@code parent}, {@code group}, {@code icon}
 * and {@code effectText} may be {@code null}. The Valhalla-native map keeps the field name {@code native_}
 * because {@code native} is a Java keyword.
 */
public record SkillNode(
        String id,
        String name,
        int level,
        SkillRole role,
        String parent,
        List<String> parentsAny,
        String group,
        String icon,
        int cost,
        String effectText,
        Map<String, Double> buffs,
        Map<String, Double> mainhandBuffs,
        Map<String, Map<String, Double>> multipliers,
        Map<String, Map<String, Double>> mainhandMultipliers,
        Map<Integer, Map<String, Double>> setBuffs,
        Map<String, Object> native_,
        List<String> commands,
        List<String> effects,
        List<DedicatedEffectEntry> dedicatedEffects) {

    public SkillNode {
        parentsAny = parentsAny == null ? List.of() : parentsAny.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        buffs = buffs == null ? Map.of() : Map.copyOf(buffs);
        mainhandBuffs = mainhandBuffs == null ? Map.of() : Map.copyOf(mainhandBuffs);
        multipliers = deepCopy(multipliers);
        mainhandMultipliers = deepCopy(mainhandMultipliers);
        setBuffs = deepCopyInt(setBuffs);
        native_ = native_ == null ? Map.of() : Map.copyOf(native_);
        commands = commands == null ? List.of() : List.copyOf(commands);
        effects = effects == null ? List.of() : List.copyOf(effects);
        dedicatedEffects = dedicatedEffects == null ? List.of() : List.copyOf(dedicatedEffects);
    }

    /** Source-compatible constructor for the pre-set-buffs full node shape. */
    public SkillNode(String id, String name, int level, SkillRole role, String parent, List<String> parentsAny,
                     String group, String icon, int cost, String effectText, Map<String, Double> buffs,
                     Map<String, Double> mainhandBuffs, Map<String, Map<String, Double>> multipliers,
                     Map<String, Map<String, Double>> mainhandMultipliers, Map<String, Object> native_,
                     List<String> commands, List<String> effects, List<DedicatedEffectEntry> dedicatedEffects) {
        this(id, name, level, role, parent, parentsAny, group, icon, cost, effectText, buffs, mainhandBuffs,
                multipliers, mainhandMultipliers, Map.of(), native_, commands, effects, dedicatedEffects);
    }

    /** Source-compatible constructor for the pre-mainhand-buffs full node shape. */
    public SkillNode(String id, String name, int level, SkillRole role, String parent, List<String> parentsAny,
                     String group, String icon, int cost, String effectText, Map<String, Double> buffs,
                     Map<String, Map<String, Double>> multipliers, Map<String, Object> native_,
                     List<String> commands, List<String> effects, List<DedicatedEffectEntry> dedicatedEffects) {
        this(id, name, level, role, parent, parentsAny, group, icon, cost, effectText, buffs, Map.of(),
                multipliers, Map.of(), Map.of(), native_, commands, effects, dedicatedEffects);
    }

    /** Backward-compatible constructor for nodes with one mandatory parent. */
    public SkillNode(String id, String name, int level, SkillRole role, String parent, String group,
                     String icon, int cost, String effectText, Map<String, Double> buffs,
                     Map<String, Map<String, Double>> multipliers, Map<String, Object> native_,
                     List<String> commands, List<String> effects,
                     List<DedicatedEffectEntry> dedicatedEffects) {
        this(id, name, level, role, parent, List.of(), group, icon, cost, effectText, buffs,
                Map.of(), multipliers, Map.of(), Map.of(), native_, commands, effects, dedicatedEffects);
    }

    /** Backward-compatible constructor for additive-only nodes. */
    public SkillNode(String id, String name, int level, SkillRole role, String parent, String group,
                     String icon, int cost, String effectText, Map<String, Double> buffs,
                     Map<String, Object> native_, List<String> commands, List<String> effects,
                     List<DedicatedEffectEntry> dedicatedEffects) {
        this(id, name, level, role, parent, List.of(), group, icon, cost, effectText, buffs, Map.of(),
                Map.of(), Map.of(), Map.of(), native_, commands, effects, dedicatedEffects);
    }

    private static Map<String, Map<String, Double>> deepCopy(Map<String, Map<String, Double>> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Map<String, Double>> copy = new java.util.LinkedHashMap<>();
        raw.forEach((layer, stats) -> copy.put(layer, stats == null ? Map.of() : Map.copyOf(stats)));
        return Map.copyOf(copy);
    }

    private static Map<Integer, Map<String, Double>> deepCopyInt(Map<Integer, Map<String, Double>> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<Integer, Map<String, Double>> copy = new java.util.LinkedHashMap<>();
        raw.forEach((tier, stats) -> copy.put(tier, stats == null ? Map.of() : Map.copyOf(stats)));
        return Map.copyOf(copy);
    }

    /** Whether this node is a root (no prerequisite node). */
    public boolean isRoot() {
        return parent == null && parentsAny.isEmpty();
    }

    /** Ordered any-of prerequisite candidates; the primary {@code parent} is first when present. */
    public List<String> prerequisiteParents() {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        if (parent != null && !parent.isBlank()) result.add(parent);
        result.addAll(parentsAny);
        return List.copyOf(result);
    }
}
