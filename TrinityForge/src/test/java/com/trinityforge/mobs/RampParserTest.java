package com.trinityforge.mobs;

import com.trinityforge.mobs.ConversionPolicy.DefenseRamp;
import com.trinityforge.mobs.ConversionPolicy.Ramp;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RampParser}: ramp/defenseRamp parsing and the fail-soft zero defaults
 * for absent sections.
 */
class RampParserTest {

    private static final double DELTA = 1.0e-9;

    private static YamlConfiguration yaml(String body) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(body);
        return cfg;
    }

    /** A fresh, uniquely named logger with an in-memory handler so warnings can be asserted on. */
    private static Logger captureLogger(List<LogRecord> sink) {
        Logger log = Logger.getLogger("RampParserTest-" + System.nanoTime());
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                sink.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return log;
    }

    @Test
    @DisplayName("ramp(null) yields a zero base/per-level ramp")
    void rampNullIsZero() {
        Ramp r = RampParser.ramp(null);
        assertEquals(0.0, r.base(), DELTA);
        assertEquals(0.0, r.perLevel(), DELTA);
    }

    @Test
    @DisplayName("ramp(section) reads base and per-level")
    void rampReadsBaseAndPerLevel() throws Exception {
        YamlConfiguration cfg = yaml("ar:\n  base: 0.5\n  per-level: 0.1\n");
        Ramp r = RampParser.ramp(cfg.getConfigurationSection("ar"));
        assertEquals(0.5, r.base(), DELTA);
        assertEquals(0.1, r.perLevel(), DELTA);
    }

    @Test
    @DisplayName("ramp defaults missing per-level to 0")
    void rampMissingPerLevelDefaultsZero() throws Exception {
        YamlConfiguration cfg = yaml("ar:\n  base: 0.3\n");
        Ramp r = RampParser.ramp(cfg.getConfigurationSection("ar"));
        assertEquals(0.3, r.base(), DELTA);
        assertEquals(0.0, r.perLevel(), DELTA);
    }

    @Test
    @DisplayName("ramp reads growth and growth-interval")
    void rampReadsGrowthAndGrowthInterval() throws Exception {
        YamlConfiguration cfg = yaml("ar:\n  base: 1.0\n  per-level: 0.5\n  growth: 1.1\n  growth-interval: 5\n");
        Ramp r = RampParser.ramp(cfg.getConfigurationSection("ar"));
        assertEquals(1.0, r.base(), DELTA);
        assertEquals(0.5, r.perLevel(), DELTA);
        assertEquals(1.1, r.growth(), DELTA);
        assertEquals(5.0, r.growthInterval(), DELTA);
    }

    @Test
    @DisplayName("ramp defaults growth/growth-interval to 1.0 (linear, full back-compat) when absent")
    void rampMissingGrowthDefaultsToLinear() throws Exception {
        YamlConfiguration cfg = yaml("ar:\n  base: 1.0\n  per-level: 0.5\n");
        Ramp r = RampParser.ramp(cfg.getConfigurationSection("ar"));
        assertEquals(1.0, r.growth(), DELTA);
        assertEquals(1.0, r.growthInterval(), DELTA);
        assertEquals(r.base() + r.perLevel() * 10, r.at(10), DELTA);
    }

    @Test
    @DisplayName("ramp reads high-level-from and high-level-per-level")
    void rampReadsHighLevelBreakpoint() throws Exception {
        YamlConfiguration cfg = yaml("ar:\n  base: 100.0\n  per-level: 0.0\n"
                + "  high-level-from: 45\n  high-level-per-level: 50.0\n");
        Ramp r = RampParser.ramp(cfg.getConfigurationSection("ar"));
        assertEquals(45.0, r.highLevelFrom(), DELTA);
        assertEquals(50.0, r.highLevelPerLevel(), DELTA);
        assertEquals(100.0, r.at(45), DELTA);
        assertEquals(100.0 + 50.0 * 15, r.at(60), DELTA);
    }

    @Test
    @DisplayName("ramp defaults high-level-from/high-level-per-level to a no-op when absent")
    void rampMissingHighLevelBreakpointDefaultsToNoOp() throws Exception {
        YamlConfiguration cfg = yaml("ar:\n  base: 1.0\n  per-level: 0.5\n");
        Ramp r = RampParser.ramp(cfg.getConfigurationSection("ar"));
        assertEquals(Double.POSITIVE_INFINITY, r.highLevelFrom(), DELTA);
        assertEquals(0.0, r.highLevelPerLevel(), DELTA);
        // 途方もなく高いレベルでも従来どおり線形のまま(後方互換)。
        assertEquals(r.base() + r.perLevel() * 500, r.at(500), DELTA);
    }

    @Test
    @DisplayName("defenseRamp(null) yields four all-zero ramps")
    void defenseRampNullAllZero() {
        DefenseRamp d = RampParser.defenseRamp(null);
        assertEquals(0.0, d.defenseRate().base(), DELTA);
        assertEquals(0.0, d.resistance().base(), DELTA);
        assertEquals(0.0, d.damageReduction().base(), DELTA);
        assertEquals(0.0, d.flatDefense().base(), DELTA);
        assertEquals(0.0, d.defenseRate().perLevel(), DELTA);
    }

    @Test
    @DisplayName("defenseRamp with one child section absent leaves that child zero")
    void defenseRampMissingChildIsZero() throws Exception {
        YamlConfiguration cfg = yaml("""
                physical:
                  defense-rate: { base: 0.2, per-level: 0.01 }
                """);
        DefenseRamp d = RampParser.defenseRamp(cfg.getConfigurationSection("physical"));
        assertEquals(0.2, d.defenseRate().base(), DELTA);
        assertEquals(0.01, d.defenseRate().perLevel(), DELTA);
        // resistance section is absent -> zero ramp.
        assertEquals(0.0, d.resistance().base(), DELTA);
        assertEquals(0.0, d.resistance().perLevel(), DELTA);
        assertEquals(0.0, d.flatDefense().base(), DELTA);
    }

    @Test
    @DisplayName("ramp(parent,key,...) is silent and zero when the key is simply absent")
    void rampWithParentKeySilentWhenAbsent() throws Exception {
        YamlConfiguration cfg = yaml("physical: {}\n");
        List<LogRecord> records = new ArrayList<>();
        Logger log = captureLogger(records);

        Ramp r = RampParser.ramp(cfg.getConfigurationSection("physical"), "resistance", log, "test.path");

        assertEquals(0.0, r.base(), DELTA);
        assertEquals(0.0, r.perLevel(), DELTA);
        assertTrue(records.isEmpty(), "no warning expected for a simply-absent key");
    }

    @Test
    @DisplayName("ramp(parent,key,...) warns and zeroes when the key is a scalar, e.g. resistance: 0.5")
    void rampWithParentKeyWarnsOnScalar() throws Exception {
        YamlConfiguration cfg = yaml("physical:\n  resistance: 0.5\n");
        List<LogRecord> records = new ArrayList<>();
        Logger log = captureLogger(records);

        Ramp r = RampParser.ramp(cfg.getConfigurationSection("physical"), "resistance", log, "test.path");

        assertEquals(0.0, r.base(), DELTA);
        assertEquals(0.0, r.perLevel(), DELTA);
        assertEquals(1, records.size());
        assertTrue(records.get(0).getMessage().contains("resistance"));
        assertTrue(records.get(0).getMessage().contains("test.path"));
    }

    @Test
    @DisplayName("ramp(parent,key,...) warns when the section has neither base nor per-level")
    void rampWithParentKeyWarnsOnEmptySection() throws Exception {
        YamlConfiguration cfg = yaml("physical:\n  resistance: { unrelated-key: 1 }\n");
        List<LogRecord> records = new ArrayList<>();
        Logger log = captureLogger(records);

        Ramp r = RampParser.ramp(cfg.getConfigurationSection("physical"), "resistance", log, "test.path");

        assertEquals(0.0, r.base(), DELTA);
        assertEquals(0.0, r.perLevel(), DELTA);
        assertEquals(1, records.size());
        assertTrue(records.get(0).getMessage().contains("resistance"));
    }

    @Test
    @DisplayName("defenseRamp(parent,key,...) warns once and zeroes all four fields when the key is a scalar")
    void defenseRampWithParentKeyWarnsOnScalar() throws Exception {
        YamlConfiguration cfg = yaml("physical: 0.5\n");
        List<LogRecord> records = new ArrayList<>();
        Logger log = captureLogger(records);

        DefenseRamp d = RampParser.defenseRamp(cfg, "physical", log, "test.path");

        assertEquals(0.0, d.defenseRate().base(), DELTA);
        assertEquals(0.0, d.resistance().base(), DELTA);
        assertEquals(0.0, d.damageReduction().base(), DELTA);
        assertEquals(0.0, d.flatDefense().base(), DELTA);
        assertEquals(1, records.size());
        assertTrue(records.get(0).getMessage().contains("physical"));
    }
}
