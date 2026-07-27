package com.trinityforge.skilltree.effects;

/**
 * The numeric-parameter shape a {@code feature:<id>} placement takes (2026-07-23 動的ID方式改修 §3.2;
 * {@code SCALE} added 2026-07-25 gather-rework-active-framework §1). {@code NONE} is a pure on/off unlock;
 * {@code LEVEL} always requires a {@code value} (currently only {@code dismantle-unlock}, whose value is the
 * unlocked dismantle level) — a placement missing it is dropped at parse time.
 *
 * <p>{@code SCALE} is the new tiered-gathering-feature shape (vein-mining / tree-fell / area-harvest /
 * haste-active-mining): a node <em>may</em> supply a numeric {@code value} (the tier the node grants), and
 * {@link com.trinityforge.config.domains.DedicatedEffectsConfig#valueMax} resolves the highest tier a
 * player holds across every placement of that feature. Unlike {@code LEVEL}, a missing {@code value} is
 * <b>not</b> dropped — it defaults to tier {@code 1} ({@link com.trinityforge.config.domains.SkillTreeConfig}),
 * so every pre-existing boolean {@code feature:<id>} placement authored before this param changed from
 * {@code NONE} to {@code SCALE} keeps granting the feature exactly as before (full backward compatibility;
 * 2026-07-25 design doc §1 item 2 / §5 risk 2).
 */
public enum FeatureEffectParam {
    NONE,
    LEVEL,
    SCALE;

    /** {@code LEVEL} only: {@code SCALE} defaults a missing value instead of requiring one (see class doc). */
    public boolean requiresValue() {
        return this == LEVEL;
    }

    /** {@code SCALE} only: a missing {@code value} defaults to tier 1 rather than being dropped. */
    public boolean defaultsMissingValue() {
        return this == SCALE;
    }
}
