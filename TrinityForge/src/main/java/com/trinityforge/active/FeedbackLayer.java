package com.trinityforge.active;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * Actionbar feedback for activation attempts (2026-07-25 gather-rework-active-framework §3 component 5,
 * shared with the gathering B-1 lever per the design doc's "B-1 と共通化" note). Same
 * {@code player.sendActionBar(Component)} convention every other TF listener already uses (e.g.
 * {@code WoodRepairListener}, {@code PotionMergeListener}) — no new dependency, just centralizes the three
 * message shapes an activation attempt can produce so {@link ActivationDispatcher} and the W3 gathering
 * listeners share one look.
 */
public final class FeedbackLayer {

    /** Successful activation: {@code message} is the skill's own {@link ActivationResult#feedbackMessage()}. */
    public void success(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.GREEN));
    }

    /** A skill-internal refusal after CT/gate already passed (see {@link ActivationResult#failure}). */
    public void failure(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.RED));
    }

    /** Attempted while on cooldown: {@code remainingSeconds} is ceil'd from {@link CooldownManager}. */
    public void onCooldown(Player player, long remainingSeconds) {
        player.sendActionBar(Component.text(
                "クールダウン中 (残り " + remainingSeconds + "s)", NamedTextColor.YELLOW));
    }

    /** Any other message the dispatcher/gathering listeners want in the same subtle actionbar style. */
    public void subtle(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.GRAY));
    }
}
