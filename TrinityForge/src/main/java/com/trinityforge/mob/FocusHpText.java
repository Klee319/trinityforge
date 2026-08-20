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

    /**
     * K/M/B/T 略記に入る閾値。EliteMobs の {@code BossHealthDisplay} が持つ同名定数
     * ({@code fork-handoff/elitemobs/elitemobs-fork/src/main/java/com/magmaguy/elitemobs/
     * combatsystem/displays/BossHealthDisplay.java:77-80}) と同じ値。
     */
    private static final long THOUSAND = 1_000L;
    private static final long MILLION = 1_000_000L;
    private static final long BILLION = 1_000_000_000L;
    private static final long TRILLION = 1_000_000_000_000L;

    private FocusHpText() {
    }

    /**
     * Colored two-line label: gold level + white name, then HP with ratio-based color.
     * High visibility against world backgrounds (shadow is applied by the TextDisplay).
     */
    public static Component format(int level, String name, long curHp, long maxHp) {
        String safeName = name == null || name.isBlank() ? "?" : name;
        return format(level, Component.text(safeName), curHp, maxHp);
    }

    /**
     * Same layout as {@link #format(int, String, int, int)}, but takes the name as a
     * {@link Component} directly so callers can pass a {@code Component.translatable(...)} (client-side
     * localized species name) or a custom-named entity's own {@code customName()} component without
     * losing its original styling by round-tripping through plain text first.
     */
    public static Component format(int level, Component nameComponent, long curHp, long maxHp) {
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
    public static Component format(int level, Component nameComponent, long curHp, long maxHp,
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
    public static Component format(int level, Component nameComponent, long curHp, long maxHp,
                                    ResistanceLean lean, AttackLean attackLean) {
        Component safeName = nameComponent == null ? Component.text("?") : nameComponent;
        long safeMax = Math.max(1L, maxHp);
        long safeCur = Math.max(0L, curHp);
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
                .append(Component.text(formatHp(safeCur), hpColor(safeCur, safeMax), TextDecoration.BOLD))
                .append(Component.text(" / ", NamedTextColor.DARK_GRAY))
                // 分母は満タン時の分子と同じ色・太さ(緑+BOLD)に統一。
                .append(Component.text(formatHp(safeMax), NamedTextColor.GREEN, TextDecoration.BOLD));
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
    public static String formatPlain(int level, String name, long curHp, long maxHp) {
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
    public static String formatPlain(int level, String name, long curHp, long maxHp,
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
        return "Lv." + level + " " + nameLine + "\n"
                + formatHp(Math.max(0L, curHp)) + " / " + formatHp(Math.max(1L, maxHp));
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

    /**
     * HP を K/M/B/T 略記へ落とす(2026-08-14 修正)。修正前は {@code String.valueOf(int)} で生の整数を
     * 出していたため、闇の大聖堂のボス(7,683,000)が {@code 7683000 / 7683000} と7桁で並び、難易度調整後の
     * 最大値(69,371,755)では8桁になって完全に読めなかった。
     *
     * <p><b>表記規則は EliteMobs の {@code BossHealthDisplay#formatNumber}
     * ({@code fork-handoff/elitemobs/elitemobs-fork/src/main/java/com/magmaguy/elitemobs/combatsystem/
     * displays/BossHealthDisplay.java:193-210}) に揃えてある。</b>TF はこの表示を
     * {@code suppress-native-combat-display} で EM 側の表示を抑止した上で自前に描き直しているので、
     * ここで独自形式を作るとプレイヤーが同じサーバで2種類の表記(EM のボスバー/XPポップアップと TF の
     * フォーカス表示)を見ることになる。そのため閾値・小数桁・丸め方向まで一致させている:
     * <ul>
     *   <li>閾値は 1,000 / 1e6 / 1e9 / 1e12 で K/M/B/T（同ファイル 197-208 行の分岐順と同じ）</li>
     *   <li>小数は2桁。丸めは MagmaCore の {@code Round#decimalPlaces}
     *       ({@code Math.round(v * 10^p) / 10^p}) と同じ<b>四捨五入</b>で、切り捨てではない</li>
     *   <li>末尾の {@code .0} も EM と同じく残す(1,000 は "1K" ではなく "1.0K")</li>
     *   <li>負値は EM 同様「符号を前置して絶対値を略記」する</li>
     * </ul>
     *
     * <p><b>EM と意図的に変えてある唯一の点は 1,000 未満。</b>EM は
     * {@code String.valueOf(Round.twoDecimalPlaces(999))} なので "999.0" と小数が付くが、TF の HP は整数な
     * ので、それに合わせると全通常モブの表示が "20.0 / 20.0" に化けて元のバグより広範囲に読みにくくなる。
     * よって 1,000 未満は略記も小数付けもせずそのまま整数で出す。
     *
     * <p>なお 999,999 が "1.0M" ではなく "1000.0K" になるのは EM 側の丸めの癖
     * (999.999 を2桁丸めすると 1000.0 になるが分岐は K のまま)をそのまま再現したもの。ここを「直す」と
     * EM の表示と食い違うため、あえて揃えてある。
     */
    static String formatHp(long value) {
        if (value < 0) {
            // Long.MIN_VALUE は符号反転しても負のままで無限再帰になるため潰しておく。
            long positive = value == Long.MIN_VALUE ? Long.MAX_VALUE : -value;
            return "-" + formatHp(positive);
        }
        if (value >= TRILLION) {
            return twoDecimalPlaces((double) value / TRILLION) + "T";
        }
        if (value >= BILLION) {
            return twoDecimalPlaces((double) value / BILLION) + "B";
        }
        if (value >= MILLION) {
            return twoDecimalPlaces((double) value / MILLION) + "M";
        }
        if (value >= THOUSAND) {
            return twoDecimalPlaces((double) value / THOUSAND) + "K";
        }
        return Long.toString(value);
    }

    /**
     * MagmaCore {@code com.magmaguy.magmacore.util.Round#twoDecimalPlaces} と同一の丸め
     * ({@code Math.round(value * 100) / 100.0} = 四捨五入)。EM は MagmaCore に依存してこれを呼ぶが、
     * TF 本体は MagmaCore に依存していないので同じ式をここに持つ。
     */
    private static double twoDecimalPlaces(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    static TextColor hpColor(long curHp, long maxHp) {
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
