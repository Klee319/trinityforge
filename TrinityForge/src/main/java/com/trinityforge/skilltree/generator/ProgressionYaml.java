package com.trinityforge.skilltree.generator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A tiny, deterministic block-style YAML emitter for the fixed shape the progression generator produces
 * (nested {@link Map}s, scalar-only {@link List}s, and string/number/boolean scalars). It is intentionally
 * self-contained (no SnakeYAML, no Bukkit) so the generator stays a pure function whose textual output is
 * byte-stable for a given input — insertion order of every {@link java.util.LinkedHashMap} is preserved.
 *
 * <p>Scope note: this is not a general YAML library. It only handles the value types this generator emits.
 */
final class ProgressionYaml {

    private static final String INDENT_UNIT = "  ";

    private ProgressionYaml() {
    }

    /** Renders a top-level mapping document to YAML text (trailing newline included). */
    static String emit(Map<String, Object> document) {
        StringBuilder out = new StringBuilder();
        writeMapping(out, document, 0);
        return out.toString();
    }

    private static void writeMapping(StringBuilder out, Map<String, Object> mapping, int indent) {
        for (Map.Entry<String, Object> entry : mapping.entrySet()) {
            writeEntry(out, entry.getKey(), entry.getValue(), indent);
        }
    }

    private static void writeEntry(StringBuilder out, String key, Object value, int indent) {
        indent(out, indent);
        out.append(formatKey(key));
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append(": {}\n");
            } else {
                out.append(":\n");
                writeMapping(out, castMapping(map), indent + 1);
            }
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append(": []\n");
            } else {
                out.append(":\n");
                for (Object item : list) {
                    indent(out, indent + 1);
                    out.append("- ").append(scalar(item)).append('\n');
                }
            }
        } else {
            out.append(": ").append(scalar(value)).append('\n');
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMapping(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static void indent(StringBuilder out, int indent) {
        out.append(INDENT_UNIT.repeat(indent));
    }

    /** Numeric keys are single-quoted (matching ValhallaMMO's {@code connection_line} indices); rest bare. */
    private static String formatKey(String key) {
        return key.matches("[0-9]+") ? "'" + key + "'" : key;
    }

    private static String scalar(Object value) {
        if (value == null) {
            return "\"\"";
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        if (value instanceof Double || value instanceof Float) {
            return formatDecimal(((Number) value).doubleValue());
        }
        if (value instanceof Number n) {
            return n.toString();
        }
        return quote(value.toString());
    }

    /** Formats a floating value without exponent or spurious trailing zeros ({@code 2.0} → {@code 2}). */
    private static String formatDecimal(double d) {
        if (!Double.isFinite(d)) {
            return "0";
        }
        if (d == Math.floor(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        // Double.toString first so we round-trip the intended decimal, not the binary noise.
        return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }

    private static String quote(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
