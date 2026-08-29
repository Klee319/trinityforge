package com.trinityforge.config.domains;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code particles:} / {@code particle-seeds:} の発生パラメータの読み込み
 * (2026-08-25 / W-242・W-243・W-244)。
 *
 * <p><b>着手前</b>: {@code particles:} は {@code count}/{@code radius}/{@code interval-ticks}/
 * {@code shape} の4キー、{@code particle-seeds:} は<b>{@code count} だけ</b>で、
 * 速さ・高さ・巻き数・発生位置は config から触れなかった。
 */
class SpecialRewardsEmissionParseTest {

    private static final Logger LOG = Logger.getLogger("test");

    private static SpecialRewardsConfig.ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return SpecialRewardsConfig.parse(cfg, LOG);
    }

    @Test
    @DisplayName("particles: 新しい発生パラメータが全部読める")
    void particlesReadEveryEmissionKey() throws Exception {
        SpecialRewardsConfig.ParseResult result = parse("""
                particles:
                  spiral:
                    particle: FLAME
                    interval-ticks: 12
                    shape: helix
                    count: 30
                    radius: 0.9
                    speed: 0.2
                    height: 3.5
                    turns: 4
                    y-offset: -0.5
                """);
        assertEquals(0, result.skipped());
        SpecialRewardsConfig.Emission emission = result.particles().get("spiral").emission();
        assertEquals(SpecialRewardsConfig.Shape.HELIX, emission.shape());
        assertEquals(30, emission.count());
        assertEquals(0.9, emission.radius(), 1e-9);
        assertEquals(0.2, emission.speed(), 1e-9);
        assertEquals(3.5, emission.height(), 1e-9);
        assertEquals(4, emission.turns());
        assertEquals(-0.5, emission.yOffset(), 1e-9);
    }

    @Test
    @DisplayName("particle-seeds: 形状と発生パラメータが particles と同じ語彙で読める")
    void seedsShareTheSameVocabulary() throws Exception {
        SpecialRewardsConfig.ParseResult result = parse("""
                particle-seeds:
                  spark:
                    seed-item: COPPER_INGOT
                    particle: ELECTRIC_SPARK
                    shape: burst
                    count: 16
                    speed: 0.3
                """);
        assertEquals(0, result.skipped());
        SpecialRewardsConfig.ParticleSeed seed = result.particleSeeds().get("spark");
        assertNotNull(seed);
        assertEquals(SpecialRewardsConfig.Shape.BURST, seed.emission().shape());
        assertEquals(16, seed.count());
        assertEquals(0.3, seed.emission().speed(), 1e-9);
    }

    /**
     * ★ 発生位置の既定。W-242 の要望そのもの ── 何も書かなければ
     * 「道具が実際に当たった場所」に出る。ここが {@code PLAYER} に戻ると、
     * どれだけ遠くのブロックを掘っても粒子はプレイヤーの足元から出る旧挙動に戻る。
     */
    @Test
    @DisplayName("シードの発生位置は既定で『当たった場所』、origin: player で明示的にプレイヤーへ戻せる")
    void seedAnchorDefaultsToImpact() throws Exception {
        SpecialRewardsConfig.ParseResult result = parse("""
                particle-seeds:
                  implicit:
                    seed-item: BLAZE_POWDER
                    particle: FLAME
                  pinned:
                    seed-item: BLUE_ICE
                    particle: SNOWFLAKE
                    origin: player
                """);
        assertEquals(0, result.skipped());
        assertEquals(SpecialRewardsConfig.Anchor.IMPACT,
                result.particleSeeds().get("implicit").anchor());
        assertEquals(SpecialRewardsConfig.Anchor.PLAYER,
                result.particleSeeds().get("pinned").anchor());
    }

    @Test
    @DisplayName("書いていないキーはその形状の既定値で埋まる（形状を変えただけで破綻しない）")
    void omittedKeysFallBackToShapeDefaults() throws Exception {
        SpecialRewardsConfig.ParseResult result = parse("""
                particles:
                  bare_helix:
                    particle: FLAME
                    shape: helix
                """);
        SpecialRewardsConfig.Emission emission = result.particles().get("bare_helix").emission();
        assertTrue(emission.height() > 0.0,
                "height を書かなかった螺旋が平らな輪になっている(既定値が入っていない)");
        assertTrue(emission.turns() >= 1);
        assertTrue(emission.count() > 0);
    }

    @Test
    @DisplayName("y-offset の既定は particles=1.0(腰) / particle-seeds=0.0(当たった場所そのまま)")
    void yOffsetDefaultDiffersByCategory() throws Exception {
        SpecialRewardsConfig.ParseResult result = parse("""
                particles:
                  p:
                    particle: FLAME
                    shape: aura
                particle-seeds:
                  s:
                    seed-item: BLAZE_POWDER
                    particle: FLAME
                    shape: aura
                """);
        assertEquals(SpecialRewardsConfig.Emission.DEFAULT_PLAYER_Y_OFFSET,
                result.particles().get("p").emission().yOffset(), 1e-9);
        assertEquals(SpecialRewardsConfig.Emission.DEFAULT_IMPACT_Y_OFFSET,
                result.particleSeeds().get("s").emission().yOffset(), 1e-9);
    }

    @Test
    @DisplayName("知らない形状・知らない origin はそのエントリを落とす（黙って別物にしない）")
    void unknownVocabularyIsSkipped() throws Exception {
        SpecialRewardsConfig.ParseResult particles = parse("""
                particles:
                  bad:
                    particle: FLAME
                    shape: kaleidoscope
                """);
        assertEquals(1, particles.skipped());
        assertTrue(particles.particles().isEmpty());

        SpecialRewardsConfig.ParseResult seeds = parse("""
                particle-seeds:
                  bad:
                    seed-item: BLAZE_POWDER
                    particle: FLAME
                    origin: cursor
                """);
        assertEquals(1, seeds.skipped());
        assertTrue(seeds.particleSeeds().isEmpty());
    }

    @Test
    @DisplayName("範囲外の値は矯正される（読み手ごとに clamp を書かない）")
    void outOfRangeValuesAreClampedAtParse() throws Exception {
        SpecialRewardsConfig.ParseResult result = parse("""
                particles:
                  huge:
                    particle: FLAME
                    shape: aura
                    count: 99999
                    radius: 999
                    speed: -3
                """);
        SpecialRewardsConfig.Emission emission = result.particles().get("huge").emission();
        assertEquals(SpecialRewardsConfig.Emission.MAX_COUNT, emission.count());
        assertEquals(16.0, emission.radius(), 1e-9);
        assertEquals(0.0, emission.speed(), 1e-9);
    }

    /**
     * 旧 yml(shape も speed も書いていない)がそのまま同じ見た目で動くこと。
     * シードの既定半径 0.3 は旧実装の {@code burstAt(..., 0.3)} そのままの値。
     */
    @Test
    @DisplayName("旧形式のシード定義は既定の見た目のまま読める")
    void legacySeedDefinitionKeepsItsLook() throws Exception {
        SpecialRewardsConfig.ParseResult result = parse("""
                particle-seeds:
                  flame:
                    seed-item: BLAZE_POWDER
                    display: 焔
                    particle: FLAME
                    count: 6
                """);
        SpecialRewardsConfig.ParticleSeed seed = result.particleSeeds().get("flame");
        assertEquals(SpecialRewardsConfig.Shape.AURA, seed.emission().shape());
        assertEquals(6, seed.count());
        assertEquals(0.3, seed.emission().radius(), 1e-9);
        assertEquals("焔", seed.displayName());
    }
}
