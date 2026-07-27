package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.effects.DedicatedEffectGateIndex;
import com.trinityforge.skilltree.effects.GateEffectId;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Runtime home of the dynamic gate index (2026-07-23 動的ID方式改修 §3): the derived
 * {@code channel -> target -> perkId(s)} maps built from every loaded skill tree's
 * {@code dedicated-effects[]} placements. There is no catalog file to load anymore — a placement's own
 * prefix ({@code glyph:}/{@code recipe:}/{@code ritual:}/{@code drop:}/{@code brew:}/{@code trade:}/
 * {@code feature:}/{@code overenchant:}/{@code reward:}/{@code ars-tier}) is the sole source of truth for
 * which channel/target it compiles onto (see {@link GateEffectId}); {@code SkillTreeConfig} validates the
 * shape (unknown prefix, {@code feature:} vocab, required {@code value}) at tree-parse time.
 *
 * <p>{@code ConfigManager} still registers this domain (via {@link #load}, now a no-op) before
 * {@code SkillTreeConfig} purely to preserve the existing load-order documentation; the actual index is
 * (re)built by {@link #reindex} once every tree has loaded.
 */
public final class DedicatedEffectsConfig implements LoadableConfig {

    /** Derived channel -&gt; perkId(s) index, recomputed by {@link #reindex} from every loaded skill tree. */
    private volatile DedicatedEffectGateIndex gateIndex = DedicatedEffectGateIndex.EMPTY;

    /**
     * Recomputes the derived gate/flag index from {@code trees}. {@code ConfigManager} calls this once per
     * {@code loadAll} pass, after {@code SkillTreeConfig} has (re)loaded, so every {@code /trinityforge
     * reload} republishes an up-to-date, immutable snapshot. Fail-safe: a {@code null}/empty input yields
     * {@link DedicatedEffectGateIndex#EMPTY}, never throws.
     */
    public void reindex(Collection<SkillTree> trees) {
        this.gateIndex = DedicatedEffectGateIndex.build(trees);
    }

    /** glyph bare key -&gt; perk id(s) granting it (channel {@code glyph:}). Never {@code null}. */
    public Map<String, Set<String>> glyphGatePerks() {
        return gateIndex.glyphGatePerks();
    }

    /** recipe id -&gt; perk id(s) granting it (channel {@code recipe:}). Never {@code null}. */
    public Map<String, Set<String>> recipeGatePerks() {
        return gateIndex.recipeGatePerks();
    }

    /** ritual id -&gt; perk id(s) granting it (channel {@code ritual:}). Never {@code null}. */
    public Map<String, Set<String>> ritualGatePerks() {
        return gateIndex.ritualGatePerks();
    }

    /**
     * {@code "<prof>:<categoryId>"} / {@code "<prof>:item:<itemId>"} -&gt; perk id(s) granting it
     * (channel {@code drop:}). Never {@code null}.
     */
    public Map<String, Set<String>> dropGatePerks() {
        return gateIndex.dropGatePerks();
    }

    /** capability id (full, e.g. {@code "feature:vein-mining"}) -&gt; perk id(s) granting it. Never {@code null}. */
    public Map<String, Set<String>> flagPerks() {
        return gateIndex.flagPerks();
    }

    /**
     * True when {@code player} holds the perk of at least one node placing dedicated-effect
     * {@code effectId} (ランタイム値クエリ層). A bare id with no {@code prefix:} (e.g. {@code "vein-mining"})
     * is normalized to {@code feature:vein-mining} first (後方互換: pre-existing gimmick listeners query by
     * bare feature id — see {@link #normalize}). Fail-safe: a {@code null} player, an unreadable PDC, or
     * any internal exception yields {@code false} rather than propagating into the fork.
     */
    public boolean isActive(Player player, String effectId) {
        return isActiveByPerks(heldPerksOf(player), effectId);
    }

    /**
     * Sums the {@code value} of every {@code effectId} placement whose perk id {@code player} holds
     * (unique/single-node effects resolve to that one value; stackable effects sum). Fail-safe: same as
     * {@link #isActive}.
     */
    public double valueSum(Player player, String effectId) {
        return valueSumByPerks(heldPerksOf(player), effectId);
    }

    /** The highest {@code value} among {@code player}'s matching placements. Fail-safe: same as {@link #isActive}. */
    public OptionalDouble valueMax(Player player, String effectId) {
        try {
            return gateIndex.valueMaxByPerks(heldPerksOf(player), normalize(effectId));
        } catch (RuntimeException ex) {
            return OptionalDouble.empty();
        }
    }

    /** {@link #isActive} without a live {@link Player} (tests, or a caller that already has the perk set). */
    public boolean isActiveByPerks(Set<String> heldPerks, String effectId) {
        try {
            return gateIndex.isActiveByPerks(heldPerks, normalize(effectId));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** {@link #valueSum} without a live {@link Player} (tests, or a caller that already has the perk set). */
    public double valueSumByPerks(Set<String> heldPerks, String effectId) {
        try {
            return gateIndex.valueSumByPerks(heldPerks, normalize(effectId));
        } catch (RuntimeException ex) {
            return 0.0;
        }
    }

    /**
     * Back-compat normalization (2026-07-23 動的ID方式改修): a caller querying by a bare id with no
     * {@code prefix:} — the convention every pre-existing gimmick listener uses (e.g.
     * {@code isActive(player, "vein-mining")}) — is rewritten to {@code feature:<id>} before hitting the
     * index, so those ~15 call sites keep working unchanged once skill-tree ymls are converted to the new
     * {@code feature:vein-mining} placement id (W2c). The bare literal {@code ars-tier} is passed through
     * untouched (it is its own full id, not a feature).
     */
    private static String normalize(String effectId) {
        if (effectId == null) {
            return null;
        }
        String trimmed = effectId.trim();
        if (trimmed.isEmpty() || trimmed.contains(":") || trimmed.equals(GateEffectId.ARS_TIER)) {
            return trimmed;
        }
        return "feature:" + trimmed;
    }

    /** Reads {@code player}'s held perks via {@link PlayerData}; {@code null}/any failure yields empty. */
    private static Set<String> heldPerksOf(Player player) {
        if (player == null) {
            return Set.of();
        }
        try {
            return Set.copyOf(PlayerData.of(player).heldPerks());
        } catch (RuntimeException ex) {
            return Set.of();
        }
    }

    @Override
    public boolean load(Plugin plugin) {
        // No catalog file anymore (2026-07-23 動的ID方式改修): the gate index is built purely from loaded
        // skill trees by reindex(), called by ConfigManager after SkillTreeConfig loads. Nothing to do here;
        // this stays a LoadableConfig only to preserve the existing register() ordering/documentation.
        return true;
    }
}
