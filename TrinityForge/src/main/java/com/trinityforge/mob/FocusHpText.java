package com.trinityforge.mob;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Pure text formatter for the focus-target HP display (Bukkit-free, unit-testable). The Bukkit-
 * facing driver is {@link FocusHpDisplay}.
 */
public final class FocusHpText {

    private FocusHpText() {
    }

    /**
     * Colored two-line label: gold level + white name, then HP with ratio-based color.
     * High visibility against world backgrounds (shadow is applied by the TextDisplay).
     */
    public static Component format(int level, String name, int curHp, int maxHp) {
        String safeName = name == null || name.isBlank() ? "?" : name;
        return format(level, Component.text(safeName), curHp, maxHp);
    }

    /**
     * Same layout as {@link #format(int, String, int, int)}, but takes the name as a
     * {@link Component} directly so callers can pass a {@code Component.translatable(...)} (client-side
     * localized species name) or a custom-named entity's own {@code customName()} component without
     * losing its original styling by round-tripping through plain text first.
     */
    public static Component format(int level, Component nameComponent, int curHp, int maxHp) {
        Component safeName = nameComponent == null ? Component.text("?") : nameComponent;
        int safeMax = Math.max(1, maxHp);
        int safeCur = Math.max(0, curHp);
        return Component.empty()
                .append(Component.text("Lv." + level + " ", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text().color(NamedTextColor.WHITE).append(safeName))
                .append(Component.newline())
                .append(Component.text(String.valueOf(safeCur), hpColor(safeCur, safeMax), TextDecoration.BOLD))
                .append(Component.text(" / ", NamedTextColor.DARK_GRAY))
                // 分母は満タン時の分子と同じ色・太さ(緑+BOLD)に統一。
                .append(Component.text(String.valueOf(safeMax), NamedTextColor.GREEN, TextDecoration.BOLD));
    }

    /** Plain-text form for tests / logs (no color codes). */
    public static String formatPlain(int level, String name, int curHp, int maxHp) {
        String safeName = name == null || name.isBlank() ? "?" : name;
        return "Lv." + level + " " + safeName + "\n" + Math.max(0, curHp) + " / " + Math.max(1, maxHp);
    }

    static TextColor hpColor(int curHp, int maxHp) {
        double ratio = maxHp <= 0 ? 0.0 : (double) curHp / (double) maxHp;
        if (ratio > 0.66) {
            return NamedTextColor.GREEN;
        }
        if (ratio > 0.33) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.RED;
    }
}
