package com.trinityforge.active;

import org.bukkit.entity.Player;

/**
 * A cooldown-backed skill triggered by normal gameplay rather than
 * {@link ActivationDispatcher}. Implementations own the activation eligibility and
 * cooldown calculation so the display cannot drift from the trigger logic.
 */
public interface SemiActiveCooldown {

    String id();

    String displayName();

    boolean isEligible(Player player);

    long cooldownMillis(Player player);
}
