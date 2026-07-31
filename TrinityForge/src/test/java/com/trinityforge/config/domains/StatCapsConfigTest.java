package com.trinityforge.config.domains;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StatCapsConfig} load coverage: "no cap" (key absent) vs "cap 0" (key explicitly present with
 * 0) are distinguished (opposite convention from {@link BaseStatsConfig}), CT短縮系キーは登録されず、
 * {@code gathering-efficiency-max-enchant-level} は独立した上書き値として読める。
 */
class StatCapsConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("StatCapsConfigTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not be called when the file already exists on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static StatCapsConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, StatCapsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        StatCapsConfig config = new StatCapsConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void missingKeyMeansNoCap(@TempDir File tempDir) throws IOException {
        StatCapsConfig config = loaded(tempDir, "stat-caps: {}\n");
        assertTrue(config.caps().isEmpty());
        assertEquals(100.0, config.clamp("mining_fortune", 100.0), 1e-9);
        assertEquals(Double.POSITIVE_INFINITY, config.clamp("mining_fortune", Double.POSITIVE_INFINITY));
    }

    @Test
    void explicitZeroIsAValidCapUnlikeBaseStatsConfig(@TempDir File tempDir) throws IOException {
        StatCapsConfig config = loaded(tempDir, """
                stat-caps:
                  mining-fortune: 0
                """);
        assertTrue(config.caps().containsKey("mining_fortune"), "0 must be kept as an explicit cap, not dropped");
        assertEquals(0.0, config.caps().get("mining_fortune"), 1e-9);
        assertEquals(0.0, config.clamp("mining_fortune", 50.0), 1e-9);
    }

    @Test
    void nonNumericEntryIsIgnoredAsNoCap(@TempDir File tempDir) throws IOException {
        StatCapsConfig config = loaded(tempDir, """
                stat-caps:
                  mining-fortune: not-a-number
                """);
        assertFalse(config.caps().containsKey("mining_fortune"));
        assertEquals(999.0, config.clamp("mining_fortune", 999.0), 1e-9);
    }

    @Test
    void clampOnlyBoundsFromAboveNegativeRawPassesThrough(@TempDir File tempDir) throws IOException {
        StatCapsConfig config = loaded(tempDir, """
                stat-caps:
                  mining-fortune: 10
                """);
        assertEquals(-5.0, config.clamp("mining_fortune", -5.0), 1e-9, "raw below cap must pass through unchanged");
        assertEquals(10.0, config.clamp("mining_fortune", 25.0), 1e-9, "raw above cap must be clamped to cap");
        assertEquals(10.0, config.clamp("mining_fortune", 10.0), 1e-9, "raw exactly at cap stays as-is");
    }

    @Test
    void nonFiniteRawPassesThroughUnclamped(@TempDir File tempDir) throws IOException {
        StatCapsConfig config = loaded(tempDir, """
                stat-caps:
                  mining-fortune: 10
                """);
        assertEquals(Double.NaN, config.clamp("mining_fortune", Double.NaN));
        assertEquals(Double.POSITIVE_INFINITY, config.clamp("mining_fortune", Double.POSITIVE_INFINITY));
    }

    @Test
    void cooldownReductionFamilyKeysAreExcludedEvenWhenWritten(@TempDir File tempDir) throws IOException {
        StatCapsConfig config = loaded(tempDir, """
                stat-caps:
                  cooldown-reduction: 5
                  haste-active-mining-cooldown-reduction: 5
                  tree-fell-cooldown-reduction: 5
                """);
        assertTrue(config.caps().isEmpty(), "CT短縮系キーはCooldownManagerが既にクランプ済みのため登録されない");
        assertEquals(999.0, config.clamp("cooldown_reduction", 999.0), 1e-9);
        assertEquals(999.0, config.clamp("haste_active_mining_cooldown_reduction", 999.0), 1e-9);
    }

    @Test
    void negativeCapIsAllowedAndAppliedAsUpperBound(@TempDir File tempDir) throws IOException {
        StatCapsConfig config = loaded(tempDir, """
                stat-caps:
                  crit-chance: -1
                """);
        assertEquals(-1.0, config.clamp("crit_chance", 0.5), 1e-9);
        assertEquals(-2.0, config.clamp("crit_chance", -2.0), 1e-9, "raw already below negative cap stays as-is");
    }

    @Test
    void gatheringEfficiencyOverrideAbsentByDefault(@TempDir File tempDir) throws IOException {
        StatCapsConfig config = loaded(tempDir, "stat-caps: {}\n");
        assertNull(config.gatheringEfficiencyMaxEnchantLevel());
    }

    @Test
    void gatheringEfficiencyOverrideIsReadableWhenSet(@TempDir File tempDir) throws IOException {
        StatCapsConfig config = loaded(tempDir, """
                stat-caps: {}
                gathering-efficiency-max-enchant-level: 8
                """);
        assertEquals(8, config.gatheringEfficiencyMaxEnchantLevel());
    }

    @Test
    void withCapsFactoryFiltersCooldownReductionFamilyLikeLoad() {
        StatCapsConfig config = StatCapsConfig.withCaps(Map.of(
                "mining-fortune", 10.0,
                "cooldown-reduction", 5.0));
        assertEquals(Map.of("mining_fortune", 10.0), config.caps());
    }

    @Test
    void bundledYamlResourceHasNoCapsByDefault(@TempDir File tempDir) throws IOException {
        // 出荷ymlの実バイトをコピーして読み込み、既定で上限が1件も無い(=現在の挙動と完全に同一)ことを
        // StatCapsConfig の実ロード経路(sec==nullの扱いも含む)で確認する。すべてコメントアウトされた
        // stat-caps: セクションはYAML上「値なし(null)」に畳まれる場合があり、これは load() が
        // sec==null として正しく「空マップ」に扱う想定どおりの挙動 — マニュアルなYAML内省ではなく
        // 実際のロード結果で検証する。
        File source = new File("src/main/resources/" + StatCapsConfig.PATH);
        assertTrue(source.exists(), "bundled " + StatCapsConfig.PATH + " must exist under src/main/resources");
        File dest = new File(tempDir, StatCapsConfig.PATH);
        Files.createDirectories(dest.getParentFile().toPath());
        Files.copy(source.toPath(), dest.toPath());

        StatCapsConfig config = new StatCapsConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "bundled stat-caps.yml must parse without issues");
        assertTrue(config.caps().isEmpty(),
                "shipped stat-caps.yml must ship with every cap commented out (default = no cap)");
        assertNull(config.gatheringEfficiencyMaxEnchantLevel());
    }
}
