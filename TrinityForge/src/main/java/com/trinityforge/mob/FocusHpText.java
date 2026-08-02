package com.trinityforge.mob;

import com.trinityforge.combat.DefenseStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Pure text formatter for the focus-target HP display (Bukkit-free, unit-testable). The Bukkit-
 * facing driver is {@link FocusHpDisplay}.
 */
public final class FocusHpText {

    /**
     * Which damage type a mob's CONFIGURED defense profile leans toward resisting more. Derived purely
     * from the existing {@code combat/mob-defaults.yml} / {@code mob-import.yml} / {@code
     * mob-profiles.yml} defense keys already stamped to the mob's PDC (no new config surface) — see
     * {@link #leanFrom}. Deliberately does NOT attempt to classify which type of damage the mob's own
     * ATTACKS deal: EliteMobs has no per-mob static tag for that (TrinityForge's combat pipeline decides
     * it per-HIT at runtime — melee/projectile always resolve PHYSICAL, script/power "ability" damage
     * always resolves MAGICAL, see {@code TrinityForgeCombatListener#onPlayerDamagedByElite}), and most
     * elite mobs with any ElitePower use both, so a single per-mob "attack type" icon would misrepresent
     * more mobs than it clarifies.
     */
    public enum ResistanceLean {
        /** Both components are within {@link #LEAN_THRESHOLD} of each other (including 0/0 = unconfigured). */
        NONE,
        PHYSICAL,
        MAGICAL
    }

    /** Minimum score gap before a lean is shown; keeps the tag from firing on balanced/near-zero profiles. */
    private static final double LEAN_THRESHOLD = 0.05;

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
        return format(level, nameComponent, curHp, maxHp, ResistanceLean.NONE);
    }

    /**
     * Same as {@link #format(int, Component, int, int)} with an optional resistance-lean tag appended
     * after the name (same line — this display is deliberately kept at 2 lines total so it never grows
     * tall enough to collide with {@code DamagePopupDisplay}'s floating combat-text above it or with the
     * name label of an adjacent mob). Plain bracketed kanji text, no custom glyph/font: renders
     * identically on Java and on Bedrock via Geyser, unlike a private-use-area icon which would need a
     * resource-pack font entry on both platforms.
     */
    public static Component format(int level, Component nameComponent, int curHp, int maxHp,
                                    ResistanceLean lean) {
        Component safeName = nameComponent == null ? Component.text("?") : nameComponent;
        int safeMax = Math.max(1, maxHp);
        int safeCur = Math.max(0, curHp);
        Component nameLine = Component.text().color(NamedTextColor.WHITE).append(safeName).build();
        Component tag = resistanceTag(lean);
        if (tag != null) {
            nameLine = nameLine.append(Component.text(" ")).append(tag);
        }
        return Component.empty()
                .append(Component.text("Lv." + level + " ", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(nameLine)
                .append(Component.newline())
                .append(Component.text(String.valueOf(safeCur), hpColor(safeCur, safeMax), TextDecoration.BOLD))
                .append(Component.text(" / ", NamedTextColor.DARK_GRAY))
                // 分母は満タン時の分子と同じ色・太さ(緑+BOLD)に統一。
                .append(Component.text(String.valueOf(safeMax), NamedTextColor.GREEN, TextDecoration.BOLD));
    }

    /**
     * Resolves which of the mob's two (already-configured) defense components has the higher weighted
     * mitigation score: {@code defenseRate*0.4 + resistance*0.4 + damageReduction*0.2} (weights sum to 1;
     * {@code flatDefense} and {@code armorStrength} are excluded — they are not directly comparable
     * fractions with the three rate fields). Returns {@link ResistanceLean#NONE} when the mob has no
     * configured profile (both scores 0) or when the two scores are within {@link #LEAN_THRESHOLD} of
     * each other, so a plain/balanced mob never shows a misleading tag.
     */
    public static ResistanceLean leanFrom(DefenseStats physical, DefenseStats magical) {
        if (physical == null || magical == null) {
            return ResistanceLean.NONE;
        }
        double physicalScore = score(physical);
        double magicalScore = score(magical);
        double diff = physicalScore - magicalScore;
        if (Math.abs(diff) < LEAN_THRESHOLD) {
            return ResistanceLean.NONE;
        }
        return diff > 0 ? ResistanceLean.PHYSICAL : ResistanceLean.MAGICAL;
    }

    private static double score(DefenseStats stats) {
        return stats.defenseRate() * 0.4 + stats.resistance() * 0.4 + stats.damageReduction() * 0.2;
    }

    private static Component resistanceTag(ResistanceLean lean) {
        return switch (lean) {
            case PHYSICAL -> Component.text("[耐:物]", NamedTextColor.GRAY);
            case MAGICAL -> Component.text("[耐:魔]", NamedTextColor.AQUA);
            case NONE -> null;
        };
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
