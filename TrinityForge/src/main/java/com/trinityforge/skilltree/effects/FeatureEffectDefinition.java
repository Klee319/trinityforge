package com.trinityforge.skilltree.effects;

/**
 * One entry of the {@link FeatureEffectRegistry} fixed vocabulary: a {@code feature:<id>} placement's
 * metadata (2026-07-23 動的ID方式改修 §3.2).
 *
 * @param id    the bare feature id (without the {@code feature:} prefix), e.g. {@code vein-mining}.
 * @param label Japanese display label (editor/log use).
 * @param param whether a node placing this feature must supply a numeric {@code value}.
 */
public record FeatureEffectDefinition(String id, String label, FeatureEffectParam param) {
}
