package com.trinityforge.ops;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/**
 * Shared helpers for the {@code ops} offline verification suite (資源サーバ分離の事前検証).
 *
 * <p>These tests exist to answer operational questions <b>without standing up a server</b>: they load
 * the <em>shipped</em> configs straight off the test classpath, drive the real parsers and scaling
 * maths, and emit a human-readable report under {@code ops/reports/} for the operator to eyeball.
 *
 * <p>The report files are deliverables, not test scratch — they are written into the repository so the
 * numbers can be reviewed and diffed alongside the config changes that produced them.
 */
final class OpsReport {

    /** Repo-root-relative directory the simulation reports are written to. */
    private static final String REPORTS_DIR = "ops/reports";

    /**
     * Marker paths that only exist at the repository root, used to locate it regardless of whether the
     * JVM's working directory is the Gradle subproject ({@code TrinityForge/}) or the repo root itself.
     */
    private static final String[] ROOT_MARKERS = {"TrinityForge/build.gradle.kts", "tools/config-editor"};

    private OpsReport() {
    }

    /**
     * Copies a shipped resource (e.g. {@code combat/mob-types.yml}) out of the test classpath into
     * {@code dataFolder}, so a {@code LoadableConfig} can load the real production file through its
     * ordinary on-disk path rather than a hand-written fixture.
     */
    static void copyShippedResource(Path dataFolder, String resourcePath) throws IOException {
        Path target = dataFolder.resolve(resourcePath);
        Files.createDirectories(target.getParent());
        try (InputStream in = OpsReport.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException(
                        "shipped resource missing from the test classpath: " + resourcePath);
            }
            Files.write(target, in.readAllBytes());
        }
    }

    /**
     * A {@link Plugin} stand-in exposing only {@code getDataFolder}/{@code getLogger}, which is all the
     * config loaders touch. Mirrors the proxy already used by {@code MobLevelTableListenerTest} and
     * {@code MobOverrideExpListenerTest} rather than introducing a second pattern.
     */
    static Plugin fakePlugin(Path dataFolder, String loggerName) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder.toFile();
            case "getLogger" -> Logger.getLogger(loggerName);
            case "saveResource" -> null;
            case "toString" -> "FakePlugin[" + loggerName + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, handler);
    }

    /** Writes {@code markdown} to {@code ops/reports/<fileName>} and returns the path actually written. */
    static Path write(String fileName, String markdown) {
        try {
            Path dir = repoRoot().resolve(REPORTS_DIR);
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.writeString(file, markdown, StandardCharsets.UTF_8);
            return file;
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to write ops report: " + fileName, ex);
        }
    }

    /** Walks up from the JVM working directory until every {@link #ROOT_MARKERS} entry resolves. */
    private static Path repoRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (isRepoRoot(candidate)) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "repository root not found above " + System.getProperty("user.dir"));
    }

    private static boolean isRepoRoot(Path candidate) {
        for (String marker : ROOT_MARKERS) {
            if (!Files.exists(candidate.resolve(marker))) {
                return false;
            }
        }
        return true;
    }
}
