package com.trinityforge.skilltree.runtime;

import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.generator.PerkNaming;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Resolves numeric values from legacy {@code native:} maps using TF-owned unlock state. */
public final class NativePerkRewardResolver {

    private final SkillPerkStatSource source;
    private final Supplier<Collection<SkillTree>> trees;

    public NativePerkRewardResolver(SkillPerkStatSource source,
                                    Supplier<Collection<SkillTree>> trees) {
        this.source = Objects.requireNonNull(source, "source");
        this.trees = Objects.requireNonNull(trees, "trees");
    }

    public double value(UUID playerId, String rewardKey) {
        if (playerId == null || rewardKey == null) return 0.0;
        Set<String> owned = source.unlockedPerkIds(playerId);
        double total = 0.0;
        for (SkillTree tree : trees.get()) {
            for (var node : tree.nodes().values()) {
                if (!owned.contains(PerkNaming.perkId(tree.skill(), node.id()))) continue;
                Object raw = node.native_().get(rewardKey);
                if (raw instanceof Number number) total += number.doubleValue();
                else if (raw instanceof Boolean bool && bool) total += 1.0;
            }
            if (tree.prestige() != null) {
                Object raw = tree.prestige().native_().get(rewardKey);
                if (raw instanceof Number number) {
                    for (int tier = 1; tier <= tree.prestige().maxTimes(); tier++) {
                        if (owned.contains(PerkNaming.prestigePerkId(tree.skill(), tier))) {
                            total += number.doubleValue();
                        }
                    }
                }
            }
        }
        return total;
    }
}
