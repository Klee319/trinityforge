package com.trinityforge.stats;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure derivation of an item's quality at creation time (ITEM_ECONOMY_SPEC 5.2d): a crafted item's
 * quality MODE rises with the crafter's relevant production-skill level, and a fished item's mode rises
 * with the rod's treasure luck; the final quality is a random draw around that mode (gacha-ish, CR-5).
 * Bukkit-free so the mode/clamp rules are unit-testable; the random offset is supplied by the caller.
 */
public final class CraftQualityPolicy {

    private CraftQualityPolicy() {
    }

    /**
     * The candidate production skills for an item with {@code itemCategories}, per the configured
     * category→skill map (e.g. {@code weapon→SMITHING}, {@code tool→MINING}). An item in several
     * categories (an axe = weapon+tool) yields several candidates; the caller picks the crafter's
     * highest among them. Order-preserving and de-duplicated.
     */
    public static Set<String> candidateSkills(Set<String> itemCategories, Map<String, String> categorySkill) {
        Set<String> skills = new LinkedHashSet<>();
        for (String category : itemCategories) {
            String skill = categorySkill.get(category);
            if (skill != null && !skill.isBlank()) {
                skills.add(skill);
            }
        }
        return skills;
    }

    /**
     * The quality mode from a skill level: {@code base + level / levelsPerQuality} (integer division).
     * Every {@code levelsPerQuality} skill levels raises the expected quality by one. A non-positive
     * {@code levelsPerQuality} pins the mode at {@code base}.
     */
    public static int modeFromLevel(int level, int levelsPerQuality, int base) {
        if (levelsPerQuality <= 0) {
            return base;
        }
        return base + Math.max(0, level) / levelsPerQuality;
    }

    /** Final quality: mode shifted by a (caller-supplied) random offset, clamped to [0, maxQuality]. */
    public static int resolveQuality(int mode, int offset, int maxQuality) {
        return Math.max(0, Math.min(maxQuality, mode + offset));
    }

    /**
     * A discrete normal (bell) quality draw centered on {@code mode} with standard deviation
     * {@code sigma}, clamped to {@code [0, maxQuality]} (ITEM_ECONOMY_SPEC 5.2d). Shared by BOTH crafted
     * items and mob drops: as the mode slides toward an end of the range the clamp truncates the bell
     * there, so the visible distribution deforms/skews — the intended "stronger crafter / stronger enemy
     * ⇒ the bell slides up and reshapes" model. A non-positive {@code sigma} pins the result at the
     * (clamped) mode.
     *
     * <p>{@code standardNormal} is a caller-supplied sample from N(0,1) so this stays deterministic and
     * Bukkit-free (unit-testable); the caller supplies it (a live Gaussian for crafts, a rollSeed-seeded
     * one for drops).
     */
    public static int resolveQualityNormal(int mode, double standardNormal, double sigma, int maxQuality) {
        return resolveQualityNormal(mode, standardNormal, sigma, sigma, maxQuality);
    }

    /**
     * A split-normal (two-piece Gaussian) quality draw: the upper half of the bell (standardNormal ≥ 0)
     * uses {@code sigmaUp}, the lower half uses {@code sigmaDown}, so the upward and downward spread of a
     * quality roll can be tuned independently (上振れ/下振れ を別々に設定; stats/quality.yml
     * {@code spread-up}/{@code spread-down}). Equal σ on both sides reproduces the plain symmetric normal.
     * Result clamped to {@code [0, maxQuality]}; a non-positive σ on the chosen side pins that side at the
     * (clamped) mode. As the mode nears an end of the range the clamp truncates that tail, so the visible
     * distribution deforms/skews — the intended "stronger crafter / stronger enemy ⇒ the bell slides up
     * and reshapes" model.
     *
     * <p>{@code standardNormal} is a caller-supplied sample from N(0,1) so this stays deterministic and
     * Bukkit-free (unit-testable); the caller supplies it (a live Gaussian for crafts, a rollSeed-seeded
     * one for drops).
     */
    public static int resolveQualityNormal(int mode, double standardNormal, double sigmaUp, double sigmaDown,
                                           int maxQuality) {
        double s = standardNormal >= 0.0 ? Math.max(0.0, sigmaUp) : Math.max(0.0, sigmaDown);
        long drawn = Math.round(mode + standardNormal * s);
        if (drawn < 0) {
            return 0;
        }
        if (drawn > maxQuality) {
            return maxQuality;
        }
        return (int) drawn;
    }

    /**
     * Mob-drop quality — the {@linkplain #resolveQualityNormal split-normal quality draw} in its drop
     * context. Retained as the EliteMobs fork's entry point: the fork's compiled call binds to this name.
     * The 4-arg form stays a symmetric alias; the 5-arg form applies the {@code spread-up}/{@code
     * spread-down} split (drops read the global up/down σ, with no per-player perk).
     */
    public static int resolveDropQuality(int mode, double standardNormal, double sigma, int maxQuality) {
        return resolveQualityNormal(mode, standardNormal, sigma, maxQuality);
    }

    public static int resolveDropQuality(int mode, double standardNormal, double sigmaUp, double sigmaDown,
                                         int maxQuality) {
        return resolveQualityNormal(mode, standardNormal, sigmaUp, sigmaDown, maxQuality);
    }

    /**
     * The TRUE guaranteed floor of {@link #resolveQualityNormal(int, double, double, double, int)} for
     * the DOWNWARD side — i.e. the smallest value that call can EVER return, for any {@code
     * standardNormal} the caller might supply (a live Gaussian is unbounded, so this must hold for
     * arbitrarily negative samples too, not just "typical" ones). Derived directly from that method's
     * body, not guessed:
     *
     * <pre>
     * s = standardNormal >= 0 ? sigmaUp : max(0, sigmaDown)   // only the sigmaDown branch matters here
     * drawn = round(mode + standardNormal * s)
     * return clamp(drawn, 0, maxQuality)
     * </pre>
     *
     * <p>Two cases, depending on whether the downward σ actually lets the sample move the result:
     * <ul>
     *   <li>{@code spreadDown <= 0} — {@code s} clamps to 0, so {@code drawn == round(mode)} NO MATTER
     *       HOW NEGATIVE {@code standardNormal} is. The downward spread is inert, so the true floor is
     *       {@code mode} itself.</li>
     *   <li>{@code spreadDown > 0} — {@code standardNormal} is unbounded below (a real Gaussian sample
     *       can be arbitrarily large in magnitude), so {@code drawn} can be driven arbitrarily far below
     *       0, and the method's own {@code [0, maxQuality]} clamp is what stops it. The true floor is
     *       therefore exactly 0.</li>
     * </ul>
     *
     * <p>Either way the result is finally clamped into {@code [0, maxQuality]} so a pathological
     * {@code mode}/{@code maxQuality} pairing (e.g. mode already above maxQuality) cannot escape range.
     */
    static int minimumQuality(int mode, double spreadDown, int maxQuality) {
        int floor = spreadDown > 0.0 ? 0 : mode;
        return Math.max(0, Math.min(maxQuality, floor));
    }
}
