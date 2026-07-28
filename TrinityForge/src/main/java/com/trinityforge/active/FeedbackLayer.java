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

    /**
     * CT進行中の常時表示(2026-07-28 ユーザー要望)。発動を試みた瞬間だけ出る {@link #onCooldown}
     * と違い、{@link ActiveCooldownDisplay} が0.5秒ごとに更新する。0.1秒刻みにしているのは、
     * 秒単位の切り上げだと「1s」のまま止まって見える時間が長く、解禁が近いのかどうか分からないため。
     */
    public void cooldownTicking(Player player, String skillName, long remainingMillis) {
        String seconds = String.format(java.util.Locale.ROOT, "%.1f", Math.max(0L, remainingMillis) / 1000.0);
        player.sendActionBar(Component.text(
                skillName + " クールダウン 残り " + seconds + "s", NamedTextColor.GOLD));
    }

    /** Any other message the dispatcher/gathering listeners want in the same subtle actionbar style. */
    public void subtle(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.GRAY));
    }
}
