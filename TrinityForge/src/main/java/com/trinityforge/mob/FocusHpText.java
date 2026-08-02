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
     * {@link #leanFrom}.
     *
     * <p>※2026-08-02までは「モブの攻撃タイプは判定できない」としてここに固定表示しない設計だったが、
     * {@code combat/mob-types.yml} / {@code mob-profiles.yml} / {@code mob-overrides.yml} の
     * {@code attack.magic-ratio}(実装1)でモブの通常攻撃自体を魔法として解決できるようになったため、
     * その情報は下記 {@link AttackLean} として別タグで表示する。こちらの {@link ResistanceLean} は
     * 引き続き「防御(耐性)がどちらの型に寄っているか」だけを表し、両者は混ざらない別タグとして
     * 名前行に並べる。
     */
    public enum ResistanceLean {
        /** Both components are within {@link #LEAN_THRESHOLD} of each other (including 0/0 = unconfigured). */
        NONE,
        PHYSICAL,
        MAGICAL
    }

    /**
     * モブの通常攻撃(attack.magic-ratio)がどちらの型で解決されるかを示すタグ(2026-08-02 新設、実装2)。
     * {@link ResistanceLean}(防御の偏り)とは別の情報 — 「魔法耐性が高いモブ」と「魔法で殴ってくる
     * モブ」は無関係なので、同じタグに混ぜない。{@code magic-ratio <= 0}(既定・従来どおり完全物理)の
     * ときは {@link #NONE} でタグを一切出さない(ほとんどのモブはこちら)。
     */
    public enum AttackLean {
        /** magic-ratio <= 0(完全物理、従来どおり)。タグなし。 */
        NONE,
        /** 0 &lt; magic-ratio &lt; 1: 通常攻撃が物理/魔法の両方で解決される。 */
        HYBRID,
        /** magic-ratio &gt;= 1: 通常攻撃が完全に魔法として解決される。 */
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
        return format(level, nameComponent, curHp, maxHp, lean, AttackLean.NONE);
    }

    /**
     * Same as {@link #format(int, Component, int, int, ResistanceLean)} with an additional optional
     * attack-type tag (実装2, 2026-08-02): shown only when the mob's {@code attack.magic-ratio} is
     * greater than 0 (most mobs stay {@link AttackLean#NONE}, no tag). Appended on the SAME name line,
     * after the resistance tag if both are present — the display stays at 2 lines total (see class-level
     * rationale on {@link #format(int, Component, int, int, ResistanceLean)}'s sibling javadoc), and the
     * two tags are visually distinct bracket groups so they never read as one merged piece of information.
     */
    public static Component format(int level, Component nameComponent, int curHp, int maxHp,
                                    ResistanceLean lean, AttackLean attackLean) {
        Component safeName = nameComponent == null ? Component.text("?") : nameComponent;
        int safeMax = Math.max(1, maxHp);
        int safeCur = Math.max(0, curHp);
        Component nameLine = Component.text().color(NamedTextColor.WHITE).append(safeName).build();
        Component resistTag = resistanceTag(lean);
        if (resistTag != null) {
            nameLine = nameLine.append(Component.text(" ")).append(resistTag);
        }
        Component attackTag = attackTag(attackLean);
        if (attackTag != null) {
            nameLine = nameLine.append(Component.text(" ")).append(attackTag);
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

    /**
     * Resolves {@link AttackLean} from a mob's stamped {@code attack.magic-ratio} [0,1](実装2）。
     * {@code ratio <= 0} is {@link AttackLean#NONE}(タグなし、既定・大多数のモブ)。
     */
    public static AttackLean attackLeanFrom(double magicRatio) {
        if (magicRatio >= 1.0) {
            return AttackLean.MAGICAL;
        }
        if (magicRatio > 0.0) {
            return AttackLean.HYBRID;
        }
        return AttackLean.NONE;
    }

    /**
     * 攻撃タイプタグ({@link ResistanceLean} の耐性タグとは別のブラケット、色も別系統(紫)にして
     * 見分けがつくようにしてある)。カスタムフォント/グリフは使わずバニラの色付きテキストのみ
     * (統合版でも化けない)。
     */
    private static Component attackTag(AttackLean lean) {
        return switch (lean) {
            case MAGICAL -> Component.text("[攻:魔]", NamedTextColor.LIGHT_PURPLE);
            case HYBRID -> Component.text("[攻:混]", NamedTextColor.LIGHT_PURPLE, TextDecoration.ITALIC);
            case NONE -> null;
        };
    }

    /**
     * Plain-text form for tests / logs (no color codes). Backward-compatible 4-arg overload: no
     * lean tags (equivalent to {@link ResistanceLean#NONE}/{@link AttackLean#NONE}).
     */
    public static String formatPlain(int level, String name, int curHp, int maxHp) {
        return formatPlain(level, name, curHp, maxHp, ResistanceLean.NONE, AttackLean.NONE);
    }

    /**
     * Same as {@link #formatPlain(int, String, int, int)}, but a faithful plain-text rendering of
     * the actual colored display — including the {@link ResistanceLean}/{@link AttackLean} tags
     * appended to the name line by {@link #format(int, Component, int, int, ResistanceLean,
     * AttackLean)} (2026-08-02 指摘10修正: this 4-arg-only overload previously had no way to express
     * the tags added alongside it, so a plain-text log of a tagged mob silently dropped them —
     * a real display/log mismatch, not just an unused overload).
     */
    public static String formatPlain(int level, String name, int curHp, int maxHp,
                                     ResistanceLean lean, AttackLean attackLean) {
        String safeName = name == null || name.isBlank() ? "?" : name;
        StringBuilder nameLine = new StringBuilder(safeName);
        String resistTag = plainResistanceTag(lean);
        if (resistTag != null) {
            nameLine.append(' ').append(resistTag);
        }
        String attackTagText = plainAttackTag(attackLean);
        if (attackTagText != null) {
            nameLine.append(' ').append(attackTagText);
        }
        return "Lv." + level + " " + nameLine + "\n" + Math.max(0, curHp) + " / " + Math.max(1, maxHp);
    }

    private static String plainResistanceTag(ResistanceLean lean) {
        return switch (lean) {
            case PHYSICAL -> "[耐:物]";
            case MAGICAL -> "[耐:魔]";
            case NONE -> null;
        };
    }

    private static String plainAttackTag(AttackLean lean) {
        return switch (lean) {
            case MAGICAL -> "[攻:魔]";
            case HYBRID -> "[攻:混]";
            case NONE -> null;
        };
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
