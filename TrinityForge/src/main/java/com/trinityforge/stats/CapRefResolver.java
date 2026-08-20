package com.trinityforge.stats;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * Resolves a {@code stats/lore.yml limits.cap-ref} / {@code limits.floor-ref} pointer to its real
 * numeric value, so the declared {@code cap}/{@code floor} can be asserted equal to the implementation
 * by an automated test instead of trusting a hand-written number (段階2 拘束テスト).
 *
 * <p>Exactly two pointer formats are accepted (closed format, matching the closed vocabulary policy for
 * the rest of the declaration language):
 * <ul>
 *   <li>{@code "<relative-yml-path>#<dotted.key.path>"} — reads a numeric value out of a real shipped
 *       config file, relative to a caller-supplied config root (e.g. the plugin data folder in
 *       production, or the test classpath's copied resources in tests).</li>
 *   <li>{@code "java:<fully.qualified.ClassName>#<CONSTANT_NAME>"} — reads a {@code public static
 *       final} numeric field via reflection. The field must be {@code public} (not just accessible via
 *       {@code setAccessible}) precisely so this doubles as encouragement to promote the magic number
 *       out of private/package-private scope where a config-editor or future tooling could not see it.</li>
 * </ul>
 *
 * <p>Every failure mode (missing file, missing key, non-numeric value, missing class, missing field,
 * non-public/non-static/non-final field, non-numeric field) throws {@link IllegalArgumentException} with
 * a message identifying exactly what was wrong — a typo in a {@code cap-ref} must fail loud, not
 * silently resolve to nothing (this is the whole reason cap-ref exists).
 */
public final class CapRefResolver {

    private static final String JAVA_PREFIX = "java:";

    private CapRefResolver() {
    }

    /**
     * @param ref        the {@code cap-ref}/{@code floor-ref} string (either format above)
     * @param configRoot the directory {@code <relative-yml-path>} is resolved against; ignored for the
     *                   {@code java:} format
     */
    public static double resolve(String ref, File configRoot) {
        Objects.requireNonNull(ref, "ref");
        if (ref.isBlank()) {
            throw new IllegalArgumentException("cap-ref/floor-ref must not be blank");
        }
        if (ref.startsWith(JAVA_PREFIX)) {
            return resolveJava(ref.substring(JAVA_PREFIX.length()), ref);
        }
        return resolveYaml(ref, configRoot);
    }

    private static double resolveYaml(String ref, File configRoot) {
        Objects.requireNonNull(configRoot, "configRoot (required for non-java: cap-ref '" + ref + "')");
        int hash = ref.indexOf('#');
        if (hash < 0) {
            throw new IllegalArgumentException(
                    "cap-ref '" + ref + "': expected '<relative-yml-path>#<key.path>' (missing '#')");
        }
        String relPath = ref.substring(0, hash);
        String keyPath = ref.substring(hash + 1);
        if (relPath.isBlank() || keyPath.isBlank()) {
            throw new IllegalArgumentException("cap-ref '" + ref + "': path and key path must both be non-empty");
        }
        File file = new File(configRoot, relPath);
        if (!file.isFile()) {
            throw new IllegalArgumentException("cap-ref '" + ref + "': file not found at " + file.getPath());
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception ex) {
            throw new IllegalArgumentException("cap-ref '" + ref + "': failed to parse " + file.getPath(), ex);
        }
        if (!yaml.isSet(keyPath)) {
            throw new IllegalArgumentException(
                    "cap-ref '" + ref + "': key path '" + keyPath + "' not present in " + relPath);
        }
        Object value = yaml.get(keyPath);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    "cap-ref '" + ref + "': value at '" + keyPath + "' is not numeric (was " + value + ")");
        }
        return number.doubleValue();
    }

    private static double resolveJava(String spec, String fullRef) {
        int hash = spec.indexOf('#');
        if (hash < 0) {
            throw new IllegalArgumentException(
                    "cap-ref '" + fullRef + "': expected 'java:<FQCN>#<CONST>' (missing '#')");
        }
        String className = spec.substring(0, hash);
        String fieldName = spec.substring(hash + 1);
        if (className.isBlank() || fieldName.isBlank()) {
            throw new IllegalArgumentException("cap-ref '" + fullRef + "': class name and constant name must both be non-empty");
        }

        Class<?> clazz;
        try {
            clazz = Class.forName(className);
        } catch (ClassNotFoundException ex) {
            throw new IllegalArgumentException("cap-ref '" + fullRef + "': class not found: " + className, ex);
        }

        Field field;
        try {
            field = clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException ex) {
            throw new IllegalArgumentException(
                    "cap-ref '" + fullRef + "': field not found: " + className + "#" + fieldName, ex);
        }

        int mods = field.getModifiers();
        if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods) || !Modifier.isFinal(mods)) {
            throw new IllegalArgumentException(
                    "cap-ref '" + fullRef + "': " + className + "#" + fieldName
                            + " must be 'public static final' (promote the magic number)");
        }

        Object value;
        try {
            value = field.get(null);
        } catch (IllegalAccessException ex) {
            throw new IllegalArgumentException(
                    "cap-ref '" + fullRef + "': cannot read " + className + "#" + fieldName, ex);
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    "cap-ref '" + fullRef + "': " + className + "#" + fieldName + " is not numeric (was " + value + ")");
        }
        return number.doubleValue();
    }
}
