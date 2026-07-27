package com.trinityforge.active;

import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/**
 * The runtime facts an {@link ActiveSkill#activate} implementation needs, resolved once by
 * {@link ActivationDispatcher} before dispatch so the skill never has to re-query the gate/tier itself
 * (2026-07-25 gather-rework-active-framework §3 component 1).
 *
 * @param tier     the player's resolved tier for this skill's {@code gateEffectId()} (the highest
 *                 {@code feature:<id>} placement value they hold, via
 *                 {@code DedicatedEffectsConfig#valueMax}) — always {@code >= 1}, since the dispatcher
 *                 never calls {@code activate} for an unresolved (tier-0/unheld) player.
 * @param mainHand the item that triggered the activation attempt (never {@code null}; may be empty-typed
 *                 only in the (untriggered by this dispatcher) case a caller constructs one directly).
 */
public record ActiveContext(int tier, ItemStack mainHand) {

    public ActiveContext {
        Objects.requireNonNull(mainHand, "mainHand");
    }
}
