package com.trinityforge.skilltree.runtime;

import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.generator.PerkNaming;
import com.trinityforge.stats.LoreLayout;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatVocabulary;
import com.trinityforge.pdc.ItemData;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Aggregates a player's unlocked-perk id set plus the canonical {@link SkillTree} config into the
 * attacker/defender TF-stat addends the combat pipeline applies (SKILL_TREE design section 3.2). This is
 * the single applier of the TF {@code buffs} (LD-9): Valhalla only owns the unlock state (read by
 * {@link SkillPerkStatSource}), and the {@code buffs} are excluded from the generated {@code perk_rewards}
 * (P2), so nothing is applied twice.
 *
 * <p>The core {@link #compute} step is pure and Bukkit-independent (fully unit-testable): for every node
 * in every tree it derives the ValhallaMMO perk id with {@link PerkNaming#perkId} and, when the unlocked
 * set contains it, folds the node's {@code buffs} into the attacker or defender map by canonical key
 * (design section B allow-list). A tree's prestige block now fabricates {@code prestige.maxTimes()} tiers
 * ({@code ng1..ngN}); every tier whose perk ({@link PerkNaming#prestigePerkId}) is unlocked folds in the
 * (shared, per-tier identical) prestige {@code buffs} again, so K unlocked tiers stack the prestige buffs
 * K times — cumulative by design. Keys are re-normalized through {@link StatKeys#canonical}; any key
 * outside the allow-list is dropped (defence in depth — the loader already allow-lists, but a stray key
 * must never leak into the pipeline).
 */
public final class PerkBuffResolver {

    private final SkillPerkStatSource source;
    private final Supplier<Collection<SkillTree>> trees;
    private final Supplier<java.util.List<LoreLayout.MultiplierLayer>> multiplierLayers;
    private final boolean validateMultiplierLayers;

    /**
     * @param source live unlocked-perk-id source (ValhallaMMO bridge or {@link SkillPerkStatSource#EMPTY})
     * @param trees  live view of every loaded canonical tree, e.g.
     *               {@code () -> configManager.skillTrees().all().values()}, so a
     *               {@code /trinityforge reload} that changes buffs is picked up with no re-construction
     */
    public PerkBuffResolver(SkillPerkStatSource source, Supplier<Collection<SkillTree>> trees) {
        this(source, trees, java.util.List::of, false);
    }

    /**
     * Runtime constructor with live per-stat multiplier-layer definitions from {@code stats/lore.yml}.
     * Undefined layers and stat/layer mismatches are discarded defensively even if config-editor
     * validation was bypassed by a manual YAML edit.
     */
    public PerkBuffResolver(SkillPerkStatSource source, Supplier<Collection<SkillTree>> trees,
                            Supplier<java.util.List<LoreLayout.MultiplierLayer>> multiplierLayers) {
        this(source, trees, multiplierLayers, true);
    }

    private PerkBuffResolver(SkillPerkStatSource source, Supplier<Collection<SkillTree>> trees,
                             Supplier<java.util.List<LoreLayout.MultiplierLayer>> multiplierLayers,
                             boolean validateMultiplierLayers) {
        this.source = Objects.requireNonNull(source, "source");
        this.trees = Objects.requireNonNull(trees, "trees");
        this.multiplierLayers = Objects.requireNonNull(multiplierLayers, "multiplierLayers");
        this.validateMultiplierLayers = validateMultiplierLayers;
    }

    /** The player's unlocked skill-tree buffs, split attacker/defender. Empty when nothing is unlocked. */
    public PerkBuffs buffsFor(UUID playerId) {
        if (playerId == null) {
            return PerkBuffs.EMPTY;
        }
        Set<String> unlocked = source.unlockedPerkIds(playerId);
        if (unlocked.isEmpty()) {
            return PerkBuffs.EMPTY;
        }
        PerkBuffs computed = compute(unlocked, trees.get());
        if (!validateMultiplierLayers || computed.multipliers().isEmpty()) {
            return computed;
        }
        return withValidMultiplierLayers(computed, multiplierLayers.get());
    }

    /** Includes buffs that are active only while the supplied item is held in the main hand. */
    public PerkBuffs buffsFor(UUID playerId, ItemStack mainHand) {
        PerkBuffs always = buffsFor(playerId);
        if (playerId == null || mainHand == null || mainHand.getType().isAir()) return always;
        Set<String> unlocked = source.unlockedPerkIds(playerId);
        if (unlocked.isEmpty()) return always;
        Map<String, Double> attack = new LinkedHashMap<>();
        Map<String, Double> defense = new LinkedHashMap<>();
        Map<String, Double> attributes = new LinkedHashMap<>();
        Map<String, Double> general = new LinkedHashMap<>();
        Map<String, Map<String, Double>> multipliers = new LinkedHashMap<>();
        for (SkillTree tree : trees.get()) {
            if (tree == null || !matchesMainHandSkill(tree.skill(), mainHand)) continue;
            for (SkillNode node : tree.nodes().values()) {
                if (unlocked.contains(PerkNaming.perkId(tree.skill(), node.id()))) {
                    accumulate(node.mainhandBuffs(), attack, defense, attributes, general);
                    accumulateMultipliers(node.mainhandMultipliers(), multipliers);
                }
            }
            Prestige prestige = tree.prestige();
            if (prestige != null && prestige.enabled()) for (int tier = 1; tier <= prestige.maxTimes(); tier++) {
                if (unlocked.contains(PerkNaming.prestigePerkId(tree.skill(), tier))) {
                    accumulate(prestige.mainhandBuffs(), attack, defense, attributes, general);
                    accumulateMultipliers(prestige.mainhandMultipliers(), multipliers);
                }
            }
        }
        PerkBuffs combined = new PerkBuffs(merge(always.attack(), attack), merge(always.defense(), defense),
                merge(always.attributes(), attributes), merge(always.general(), general),
                mergeMultipliers(always.multipliers(), multipliers));
        return !validateMultiplierLayers || combined.multipliers().isEmpty()
                ? combined
                : withValidMultiplierLayers(combined, multiplierLayers.get());
    }

    private static Map<String, Double> merge(Map<String, Double> base, Map<String, Double> addition) {
        if (addition.isEmpty()) return base;
        Map<String, Double> result = new LinkedHashMap<>(base);
        addition.forEach((key, value) -> result.merge(key, value, Double::sum));
        return result;
    }

    private static Map<String, Map<String, Double>> mergeMultipliers(
            Map<String, Map<String, Double>> base, Map<String, Map<String, Double>> addition) {
        if (addition.isEmpty()) return base;
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        base.forEach((layer, stats) -> result.put(layer, new LinkedHashMap<>(stats)));
        addition.forEach((layer, stats) -> {
            Map<String, Double> target = result.computeIfAbsent(layer, ignored -> new LinkedHashMap<>());
            stats.forEach((stat, value) -> target.merge(stat, value, Double::sum));
        });
        return result;
    }

    /**
     * mainhand-buffs 発動条件: 手に持ったアイテムの item-stats「使用スキル」(use-skill) が、この
     * スキルツリーのスキルと<em>厳密に一致</em>した時のみ true。使用スキル未設定(空欄)のアイテムは
     * 発動させない(素材種別からの推測フォールバックは廃止)。
     */
    private static boolean matchesMainHandSkill(String skill, ItemStack stack) {
        String expected = skill == null ? "" : skill.trim().toUpperCase(java.util.Locale.ROOT);
        if (expected.isBlank() || !stack.hasItemMeta()) {
            return false;
        }
        String tagged = ItemData.of(stack.getItemMeta()).useSkill().orElse("")
                .trim().toUpperCase(java.util.Locale.ROOT);
        return !tagged.isBlank() && expected.equals(tagged);
    }

    /** The player's attacker-side canonical buff map (empty when nothing is unlocked). */
    public Map<String, Double> attackerBuffs(UUID playerId) {
        return buffsFor(playerId).attack();
    }

    /** The player's defender-side canonical buff map (empty when nothing is unlocked). */
    public Map<String, Double> defenderBuffs(UUID playerId) {
        return buffsFor(playerId).defense();
    }

    /**
     * Pure aggregation of unlocked perk ids + trees into attacker/defender buff maps. Deterministic and
     * side-effect free, so it is the unit-test seam. A {@code null}/empty unlocked set yields
     * {@link PerkBuffs#EMPTY}.
     */
    public static PerkBuffs compute(Set<String> unlockedPerkIds, Collection<SkillTree> trees) {
        if (unlockedPerkIds == null || unlockedPerkIds.isEmpty() || trees == null || trees.isEmpty()) {
            return PerkBuffs.EMPTY;
        }
        Map<String, Double> attack = new LinkedHashMap<>();
        Map<String, Double> defense = new LinkedHashMap<>();
        Map<String, Double> attributes = new LinkedHashMap<>();
        Map<String, Double> general = new LinkedHashMap<>();
        Map<String, Map<String, Double>> multipliers = new LinkedHashMap<>();
        for (SkillTree tree : trees) {
            if (tree == null) {
                continue;
            }
            String skill = tree.skill();
            for (SkillNode node : tree.nodes().values()) {
                if (unlockedPerkIds.contains(PerkNaming.perkId(skill, node.id()))) {
                    accumulate(node.buffs(), attack, defense, attributes, general);
                    accumulateMultipliers(node.multipliers(), multipliers);
                }
            }
            Prestige prestige = tree.prestige();
            if (prestige != null && prestige.enabled()) {
                for (int tier = 1; tier <= prestige.maxTimes(); tier++) {
                    if (unlockedPerkIds.contains(PerkNaming.prestigePerkId(skill, tier))) {
                        accumulate(prestige.buffs(), attack, defense, attributes, general);
                        accumulateMultipliers(prestige.multipliers(), multipliers);
                    }
                }
            }
        }
        return new PerkBuffs(attack, defense, attributes, general, multipliers);
    }

    private static void accumulate(Map<String, Double> buffs, Map<String, Double> attack,
                                   Map<String, Double> defense, Map<String, Double> attributes,
                                   Map<String, Double> general) {
        for (Map.Entry<String, Double> entry : buffs.entrySet()) {
            Double value = entry.getValue();
            if (value == null || !Double.isFinite(value)) {
                continue;
            }
            String key = StatKeys.canonical(entry.getKey());
            switch (StatVocabulary.channelOf(key)) {
                case ATTACK -> attack.merge(key, value, Double::sum);
                case DEFENSE -> defense.merge(key, value, Double::sum);
                case ATTRIBUTE -> attributes.merge(key, value, Double::sum);
                case GENERAL -> general.merge(key, value, Double::sum);
                case NONE -> { /* disallowed key: dropped (defence in depth) */ }
            }
        }
    }

    /**
     * Merges skill-tree multipliers into the same representation used by item multipliers:
     * layer → stat → Σ(value - 1). Same-layer contributions add; different layers multiply later.
     */
    private static void accumulateMultipliers(Map<String, Map<String, Double>> authored,
                                              Map<String, Map<String, Double>> target) {
        authored.forEach((layerId, stats) -> {
            if (layerId == null || layerId.isBlank() || stats == null) {
                return;
            }
            Map<String, Double> layer = target.computeIfAbsent(layerId, ignored -> new LinkedHashMap<>());
            stats.forEach((rawKey, value) -> {
                if (value == null || !Double.isFinite(value)) {
                    return;
                }
                String key = StatKeys.canonical(rawKey);
                StatVocabulary.Channel channel = StatVocabulary.channelOf(key);
                if (channel == StatVocabulary.Channel.ATTACK || channel == StatVocabulary.Channel.DEFENSE
                        || channel == StatVocabulary.Channel.GENERAL) {
                    layer.merge(key, value - 1.0, Double::sum);
                }
            });
        });
        target.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private static PerkBuffs withValidMultiplierLayers(
            PerkBuffs buffs, java.util.List<LoreLayout.MultiplierLayer> definitions) {
        Map<String, String> expectedStatByLayer = new LinkedHashMap<>();
        if (definitions != null) {
            for (LoreLayout.MultiplierLayer definition : definitions) {
                if (definition != null && !definition.id().isBlank() && !definition.statKey().isBlank()) {
                    expectedStatByLayer.put(definition.id(), definition.statKey());
                }
            }
        }
        Map<String, Map<String, Double>> valid = new LinkedHashMap<>();
        buffs.multipliers().forEach((layerId, stats) -> {
            String expected = expectedStatByLayer.get(layerId);
            if (expected == null) {
                return;
            }
            Map<String, Double> matching = new LinkedHashMap<>();
            stats.forEach((stat, value) -> {
                if (expected.equals(StatKeys.canonical(stat))) {
                    matching.put(stat, value);
                }
            });
            if (!matching.isEmpty()) {
                valid.put(layerId, matching);
            }
        });
        return new PerkBuffs(buffs.attack(), buffs.defense(), buffs.attributes(), buffs.general(), valid);
    }
}
