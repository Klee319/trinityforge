package com.trinityforge.progression.catalog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the internal {@link FormulaParser}. No MockBukkit required. */
class FormulaParserTest {

    private static final double EPS = 1e-6;

    // ---- basic atoms ----

    @Test
    void literalInteger() {
        assertEquals(42.0, FormulaParser.evaluate("42", 0), EPS);
    }

    @Test
    void literalDecimal() {
        assertEquals(7.6, FormulaParser.evaluate("7.6", 0), EPS);
    }

    @Test
    void levelSubstitution() {
        assertEquals(15.0, FormulaParser.evaluate("%level%", 15), EPS);
    }

    // ---- binary operators ----

    @Test
    void addition() {
        assertEquals(7.0, FormulaParser.evaluate("3 + 4", 0), EPS);
    }

    @Test
    void subtraction() {
        assertEquals(1.0, FormulaParser.evaluate("5 - 4", 0), EPS);
    }

    @Test
    void multiplication() {
        assertEquals(12.0, FormulaParser.evaluate("3 * 4", 0), EPS);
    }

    @Test
    void division() {
        assertEquals(2.5, FormulaParser.evaluate("5 / 2", 0), EPS);
    }

    @Test
    void exponentiation() {
        assertEquals(8.0, FormulaParser.evaluate("2^3", 0), EPS);
    }

    // ---- precedence and associativity ----

    @Test
    void multiplicationBeforeAddition() {
        // 2 + 3 * 4 = 14 (not 20)
        assertEquals(14.0, FormulaParser.evaluate("2 + 3 * 4", 0), EPS);
    }

    @Test
    void exponentBeforeMultiplication() {
        // 2 * 2^3 = 2 * 8 = 16 (not 64)
        assertEquals(16.0, FormulaParser.evaluate("2 * 2^3", 0), EPS);
    }

    @Test
    void rightAssociativeExponent() {
        // 2^3^2 = 2^(3^2) = 2^9 = 512
        assertEquals(512.0, FormulaParser.evaluate("2^3^2", 0), EPS);
    }

    @Test
    void parenthesesOverridePrecedence() {
        assertEquals(20.0, FormulaParser.evaluate("(3 + 2) * 4", 0), EPS);
    }

    @Test
    void nestedParentheses() {
        assertEquals(7.0, FormulaParser.evaluate("((1 + 2) * 3) - 2", 0), EPS);
    }

    // ---- the two actual YAML formulas ----

    @Test
    void valhallaStandardFormula_level0() {
        // (%level% + 75 * 2^(%level%/7.6)) + 300 at level=0:
        // (0 + 75 * 2^0) + 300 = 75 + 300 = 375
        double result = FormulaParser.evaluate("(%level% + 75 * 2^(%level%/7.6)) + 300", 0);
        assertEquals(375.0, result, EPS);
    }

    @Test
    void valhallaStandardFormula_level10() {
        double expected = (10 + 75.0 * Math.pow(2.0, 10.0 / 7.6)) + 300;
        double result   = FormulaParser.evaluate("(%level% + 75 * 2^(%level%/7.6)) + 300", 10);
        assertEquals(expected, result, 0.01);
    }

    @Test
    void valhallaStandardFormula_level50() {
        double expected = (50 + 75.0 * Math.pow(2.0, 50.0 / 7.6)) + 300;
        double result   = FormulaParser.evaluate("(%level% + 75 * 2^(%level%/7.6)) + 300", 50);
        assertEquals(expected, result, 0.01);
    }

    @Test
    void valhallaStandardFormula_level99() {
        double expected = (99 + 75.0 * Math.pow(2.0, 99.0 / 7.6)) + 300;
        double result   = FormulaParser.evaluate("(%level% + 75 * 2^(%level%/7.6)) + 300", 99);
        assertEquals(expected, result, 1.0); // large values, allow 1.0 absolute tolerance
    }

    @Test
    void powerSkillFormula_level0() {
        // (%level%/100) * 1800 + 800 at level=0 = 800
        assertEquals(800.0, FormulaParser.evaluate("(%level%/100) * 1800 + 800", 0), EPS);
    }

    @Test
    void powerSkillFormula_level100() {
        // (100/100) * 1800 + 800 = 2600
        assertEquals(2600.0, FormulaParser.evaluate("(%level%/100) * 1800 + 800", 100), EPS);
    }

    @Test
    void powerSkillFormula_level200() {
        // (200/100) * 1800 + 800 = 4400
        assertEquals(4400.0, FormulaParser.evaluate("(%level%/100) * 1800 + 800", 200), EPS);
    }

    // ---- whitespace tolerance ----

    @Test
    void noWhitespace() {
        assertEquals(375.0, FormulaParser.evaluate("(%level%+75*2^(%level%/7.6))+300", 0), EPS);
    }

    @Test
    void extraWhitespace() {
        assertEquals(375.0,
                FormulaParser.evaluate("( %level% + 75 * 2^( %level% / 7.6 ) ) + 300", 0), EPS);
    }

    // ---- error cases ----

    @Test
    void invalidChar_throwsParseException() {
        assertThrows(FormulaParser.FormulaParseException.class,
                () -> FormulaParser.evaluate("@invalid", 0));
    }

    @Test
    void unclosedParen_throwsParseException() {
        assertThrows(FormulaParser.FormulaParseException.class,
                () -> FormulaParser.evaluate("(3 + 4", 0));
    }

    @Test
    void trailingGarbage_throwsParseException() {
        assertThrows(FormulaParser.FormulaParseException.class,
                () -> FormulaParser.evaluate("42 garbage", 0));
    }
}
