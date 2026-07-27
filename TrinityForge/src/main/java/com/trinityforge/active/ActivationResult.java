package com.trinityforge.active;

import java.util.Objects;

/**
 * The outcome of {@link ActiveSkill#activate}: whether the effect actually applied, plus a short
 * player-facing message the {@link ActivationDispatcher} forwards to {@link FeedbackLayer}
 * (2026-07-25 gather-rework-active-framework §3 B-1/§3 component 5 — "発動/作動フィードバック層").
 *
 * <p>{@code success=false} is for a skill-internal reason to refuse activation even though the CT/gate
 * checks already passed (e.g. a future cost layer rejecting insufficient resources, §3 component 6); it is
 * distinct from — and checked strictly after — the dispatcher's own CT/gate/item-match refusals, which
 * never call {@link ActiveSkill#activate} at all.
 */
public record ActivationResult(boolean success, String feedbackMessage) {

    public ActivationResult {
        Objects.requireNonNull(feedbackMessage, "feedbackMessage");
    }

    public static ActivationResult success(String feedbackMessage) {
        return new ActivationResult(true, feedbackMessage);
    }

    public static ActivationResult failure(String feedbackMessage) {
        return new ActivationResult(false, feedbackMessage);
    }
}
