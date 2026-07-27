package com.trinityforge.progression.catalog;

/**
 * Tiny single-use recursive-descent evaluator for ValhallaMMO-style EXP curve formulas.
 *
 * <p>Supported grammar — exactly the set needed for all formulas found in
 * {@code skills/base/*_progression.yml}:
 * <pre>
 *   expr           := additive
 *   additive       := multiplicative (('+' | '-') multiplicative)*
 *   multiplicative := exponent       (('*' | '/') exponent)*
 *   exponent       := primary        ('^' exponent)?    // right-associative
 *   primary        := '(' expr ')' | '%level%' | number
 *   number         := digit+ ('.' digit+)?
 * </pre>
 *
 * <p>Whitespace is ignored between any two tokens. The parser is strictly single-use:
 * construct a new instance per formula string (or use {@link #evaluate(String, double)}).
 *
 * <p>2026-07-26 (EXP調整タスク3): {@code evaluate} と例外型を {@code public} に開放した。
 * {@code com.trinityforge.progression.SkillExpDiminishingCurve} がレベル逓減カーブの式評価に
 * このパーサーをそのまま再利用するため(専用の式パーサーを新設しない)。文法・挙動は変更していない。
 */
public final class FormulaParser {

    private final String formula;
    private int pos;

    private FormulaParser(String formula) {
        this.formula = formula;
        this.pos = 0;
    }

    /**
     * Evaluates a ValhallaMMO EXP curve formula with {@code %level%} replaced by {@code level}.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "(%level% + 75 * 2^(%level%/7.6)) + 300"} at level 0 → 375.0</li>
     *   <li>{@code "(%level%/100) * 1800 + 800"}              at level 0 → 800.0</li>
     * </ul>
     *
     * @param formula formula string from {@code experience.exp_level_curve}
     * @param level   value substituted for every {@code %level%} token
     * @return evaluated result
     * @throws FormulaParseException if the formula is syntactically invalid or contains
     *                               unconsumed trailing content
     */
    public static double evaluate(String formula, double level) {
        FormulaParser p = new FormulaParser(formula.trim());
        double result = p.parseAdditive(level);
        p.skipWs();
        if (p.pos < p.formula.length()) {
            throw new FormulaParseException(
                    "Unexpected content at pos " + p.pos + " in: " + formula);
        }
        return result;
    }

    private double parseAdditive(double level) {
        double result = parseMultiplicative(level);
        for (;;) {
            skipWs();
            if (pos < formula.length() && formula.charAt(pos) == '+') {
                pos++;
                result += parseMultiplicative(level);
            } else if (pos < formula.length() && formula.charAt(pos) == '-') {
                pos++;
                result -= parseMultiplicative(level);
            } else {
                break;
            }
        }
        return result;
    }

    private double parseMultiplicative(double level) {
        double result = parseExponent(level);
        for (;;) {
            skipWs();
            if (pos < formula.length() && formula.charAt(pos) == '*') {
                pos++;
                result *= parseExponent(level);
            } else if (pos < formula.length() && formula.charAt(pos) == '/') {
                pos++;
                result /= parseExponent(level);
            } else {
                break;
            }
        }
        return result;
    }

    /** Right-associative: {@code a^b^c} = {@code a^(b^c)}. */
    private double parseExponent(double level) {
        double base = parsePrimary(level);
        skipWs();
        if (pos < formula.length() && formula.charAt(pos) == '^') {
            pos++;
            double exp = parseExponent(level);
            return Math.pow(base, exp);
        }
        return base;
    }

    private double parsePrimary(double level) {
        skipWs();
        if (pos >= formula.length()) {
            throw new FormulaParseException("Unexpected end of formula: " + formula);
        }
        char c = formula.charAt(pos);

        if (c == '(') {
            pos++;
            double val = parseAdditive(level);
            skipWs();
            if (pos >= formula.length() || formula.charAt(pos) != ')') {
                throw new FormulaParseException(
                        "Expected ')' at pos " + pos + " in: " + formula);
            }
            pos++;
            return val;
        }

        if (formula.startsWith("%level%", pos)) {
            pos += 7; // "%level%".length()
            return level;
        }

        if (Character.isDigit(c) || c == '.') {
            return parseNumber();
        }

        throw new FormulaParseException(
                "Unexpected char '" + c + "' at pos " + pos + " in: " + formula);
    }

    private double parseNumber() {
        int start = pos;
        while (pos < formula.length()
                && (Character.isDigit(formula.charAt(pos)) || formula.charAt(pos) == '.')) {
            pos++;
        }
        if (pos == start) {
            throw new FormulaParseException("Expected number at pos " + pos + " in: " + formula);
        }
        return Double.parseDouble(formula.substring(start, pos));
    }

    private void skipWs() {
        while (pos < formula.length() && Character.isWhitespace(formula.charAt(pos))) {
            pos++;
        }
    }

    /** Thrown when the formula string is syntactically invalid. */
    public static final class FormulaParseException extends RuntimeException {
        FormulaParseException(String msg) {
            super(msg);
        }
    }
}
