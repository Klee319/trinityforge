package com.trinityforge.skilltree.effects;

import com.trinityforge.skilltree.DedicatedEffectEntry;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.generator.PerkNaming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;

/**
 * Derived {@code channel -> target -> perkId(s)} index (2026-07-23 動的ID方式改修, follow-up to the original
 * ランタイム配線層 要件④): for every node across every loaded skill tree that places a
 * {@code glyph:}/{@code recipe:}/{@code ritual:}/{@code drop:}/flag-family dedicated-effect id, the node's
 * own Valhalla perk id ({@link PerkNaming#perkId}) is added to that channel's {@code target} bucket, per
 * {@link GateEffectId#parse}. No catalog file is consulted — the id's own prefix is the sole source of
 * truth. This is the one thing TF publishes; the fork unioning these onto its own hand-authored
 * {@code usage-gate.yml}/{@code unlock-gate.yml} is a follow-up task, not done here.
 *
 * <p>Immutable snapshot: {@link #build} is a pure function of its input, called by
 * {@code DedicatedEffectsConfig#reindex} on every load/reload so the maps always reflect the then-current
 * trees. A placement whose id fails {@link GateEffectId#parse} (unrecognized prefix, or a legacy
 * pre-conversion id — see the design doc §5 W2c note) contributes nothing: {@code SkillTreeConfig} already
 * warns and drops it at parse time, so in practice every surviving placement here parses cleanly; this
 * layer stays fail-safe regardless.
 */
public final class DedicatedEffectGateIndex {

    public static final DedicatedEffectGateIndex EMPTY =
            new DedicatedEffectGateIndex(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    /** One node's placement of an effect: the node's own perk id, plus the placement's value (may be {@code null}). */
    public record PerkValue(String perkId, Double value) {
    }

    private final Map<String, Set<String>> glyphGatePerks;
    private final Map<String, Set<String>> recipeGatePerks;
    private final Map<String, Set<String>> ritualGatePerks;
    private final Map<String, Set<String>> dropGatePerks;
    private final Map<String, Set<String>> flagPerks;
    private final Map<String, List<PerkValue>> effectPerkValues;

    private DedicatedEffectGateIndex(Map<String, Set<String>> glyphGatePerks,
                                     Map<String, Set<String>> recipeGatePerks,
                                     Map<String, Set<String>> ritualGatePerks,
                                     Map<String, Set<String>> dropGatePerks,
                                     Map<String, Set<String>> flagPerks,
                                     Map<String, List<PerkValue>> effectPerkValues) {
        this.glyphGatePerks = copyOf(glyphGatePerks);
        this.recipeGatePerks = copyOf(recipeGatePerks);
        this.ritualGatePerks = copyOf(ritualGatePerks);
        this.dropGatePerks = copyOf(dropGatePerks);
        this.flagPerks = copyOf(flagPerks);
        this.effectPerkValues = copyOfValues(effectPerkValues);
    }

    /** glyph bare key -&gt; perk id(s) granting its use. */
    public Map<String, Set<String>> glyphGatePerks() {
        return glyphGatePerks;
    }

    /** recipe id -&gt; perk id(s) granting it. */
    public Map<String, Set<String>> recipeGatePerks() {
        return recipeGatePerks;
    }

    /** ritual id -&gt; perk id(s) granting it. */
    public Map<String, Set<String>> ritualGatePerks() {
        return ritualGatePerks;
    }

    /** {@code "<prof>:<categoryId>"} / {@code "<prof>:item:<itemId>"} -&gt; perk id(s) granting it. */
    public Map<String, Set<String>> dropGatePerks() {
        return dropGatePerks;
    }

    /** capability id (full, e.g. {@code "feature:vein-mining"}) -&gt; perk id(s) granting it. */
    public Map<String, Set<String>> flagPerks() {
        return flagPerks;
    }

    /**
     * True when {@code heldPerks} contains the perk id of at least one node placing {@code effectId},
     * across every channel (unlike {@link #glyphGatePerks()} etc. this is not limited to one channel).
     * Fail-safe: {@code null}/empty {@code heldPerks}, a {@code null} {@code effectId}, or an
     * {@code effectId} unknown to this index all yield {@code false}.
     */
    public boolean isActiveByPerks(Set<String> heldPerks, String effectId) {
        if (heldPerks == null || heldPerks.isEmpty() || effectId == null) {
            return false;
        }
        List<PerkValue> placements = effectPerkValues.get(effectId);
        if (placements == null) {
            return false;
        }
        for (PerkValue placement : placements) {
            if (heldPerks.contains(placement.perkId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sums the {@code value} of every {@code effectId} placement whose perk id is in {@code heldPerks}.
     * A placement with a {@code null} value contributes {@code 0} and is otherwise ignored. Fail-safe:
     * {@code null}/empty {@code heldPerks}, a {@code null} {@code effectId}, or an unknown {@code effectId}
     * all yield {@code 0}.
     */
    public double valueSumByPerks(Set<String> heldPerks, String effectId) {
        if (heldPerks == null || heldPerks.isEmpty() || effectId == null) {
            return 0.0;
        }
        List<PerkValue> placements = effectPerkValues.get(effectId);
        if (placements == null) {
            return 0.0;
        }
        double sum = 0.0;
        for (PerkValue placement : placements) {
            if (heldPerks.contains(placement.perkId()) && placement.value() != null) {
                sum += placement.value();
            }
        }
        return sum;
    }

    /**
     * The largest {@code value} among {@code effectId} placements whose perk id is in {@code heldPerks}
     * (useful for a staged/leveled effect where only the highest-tier node should count). Fail-safe:
     * {@code null}/empty {@code heldPerks}, a {@code null} {@code effectId}, an unknown {@code effectId},
     * or a match set with no numeric value all yield {@link OptionalDouble#empty()}.
     */
    public OptionalDouble valueMaxByPerks(Set<String> heldPerks, String effectId) {
        if (heldPerks == null || heldPerks.isEmpty() || effectId == null) {
            return OptionalDouble.empty();
        }
        List<PerkValue> placements = effectPerkValues.get(effectId);
        if (placements == null) {
            return OptionalDouble.empty();
        }
        return placements.stream()
                .filter(placement -> heldPerks.contains(placement.perkId()) && placement.value() != null)
                .mapToDouble(PerkValue::value)
                .max();
    }

    /**
     * Builds the index from every node's {@code dedicatedEffects} across {@code trees}. Fail-safe:
     * {@code null}/empty {@code trees} yields {@link #EMPTY}; a placement whose id does not
     * {@link GateEffectId#parse} is silently skipped ({@code SkillTreeConfig} already warns about that at
     * parse time — this layer never re-validates).
     */
    public static DedicatedEffectGateIndex build(Collection<SkillTree> trees) {
        if (trees == null || trees.isEmpty()) {
            return EMPTY;
        }
        Map<String, Set<String>> glyph = new LinkedHashMap<>();
        Map<String, Set<String>> recipe = new LinkedHashMap<>();
        Map<String, Set<String>> ritual = new LinkedHashMap<>();
        Map<String, Set<String>> drop = new LinkedHashMap<>();
        Map<String, Set<String>> flag = new LinkedHashMap<>();
        Map<String, List<PerkValue>> effectValues = new LinkedHashMap<>();

        for (SkillTree tree : trees) {
            if (tree == null) {
                continue;
            }
            for (SkillNode node : tree.nodes().values()) {
                String perkId = PerkNaming.perkId(tree.skill(), node.id());
                for (DedicatedEffectEntry placement : node.dedicatedEffects()) {
                    Optional<GateEffectId> parsed = GateEffectId.parse(placement.id());
                    if (parsed.isEmpty()) {
                        continue;
                    }
                    // effectId -> (perkId, value) index (ランタイム値クエリ層): built for every channel, so a
                    // consumer needing isActive/valueSum/valueMax for any effect id (flag or gate alike) has
                    // one uniform query surface.
                    effectValues.computeIfAbsent(placement.id(), k -> new ArrayList<>())
                            .add(new PerkValue(perkId, placement.value()));

                    GateEffectId gate = parsed.get();
                    Map<String, Set<String>> bucket = switch (gate.channel()) {
                        case GLYPH_GATE -> glyph;
                        case RECIPE_GATE -> recipe;
                        case RITUAL_GATE -> ritual;
                        case DROP_GATE -> drop;
                        case FLAG -> flag;
                    };
                    bucket.computeIfAbsent(gate.target(), k -> new TreeSet<>()).add(perkId);
                }
            }
        }
        return new DedicatedEffectGateIndex(glyph, recipe, ritual, drop, flag, effectValues);
    }

    private static Map<String, Set<String>> copyOf(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    private static Map<String, List<PerkValue>> copyOfValues(Map<String, List<PerkValue>> source) {
        Map<String, List<PerkValue>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<PerkValue>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }
}
