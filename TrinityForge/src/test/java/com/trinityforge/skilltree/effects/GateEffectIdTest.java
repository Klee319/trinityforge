package com.trinityforge.skilltree.effects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GateEffectId} parses a node's {@code dedicated-effects[].id} placement string into the channel it
 * compiles onto plus the channel-specific target key, purely from the id's prefix (2026-07-23 動的ID方式
 * 改修 §3.1) — no catalog file involved anymore.
 */
class GateEffectIdTest {

    @Test
    void glyphPrefixMapsToGlyphGateWithBareTarget() {
        GateEffectId id = GateEffectId.parse("glyph:blink").orElseThrow();
        assertEquals(DedicatedEffectChannel.GLYPH_GATE, id.channel());
        assertEquals("blink", id.target());
    }

    @Test
    void recipePrefixMapsToRecipeGate() {
        GateEffectId id = GateEffectId.parse("recipe:waystone_craft").orElseThrow();
        assertEquals(DedicatedEffectChannel.RECIPE_GATE, id.channel());
        assertEquals("waystone_craft", id.target());
    }

    @Test
    void ritualPrefixMapsToRitualGate() {
        GateEffectId id = GateEffectId.parse("ritual:animal_summon").orElseThrow();
        assertEquals(DedicatedEffectChannel.RITUAL_GATE, id.channel());
        assertEquals("animal_summon", id.target());
    }

    @Test
    void dropPrefixKeepsRemainingColonsInTarget() {
        GateEffectId id = GateEffectId.parse("drop:mining:tier1").orElseThrow();
        assertEquals(DedicatedEffectChannel.DROP_GATE, id.channel());
        assertEquals("mining:tier1", id.target());

        GateEffectId itemId = GateEffectId.parse("drop:mining:item:tf_gacha_ticket_1").orElseThrow();
        assertEquals(DedicatedEffectChannel.DROP_GATE, itemId.channel());
        assertEquals("mining:item:tf_gacha_ticket_1", itemId.target());
    }

    @Test
    void flagFamilyPrefixesTargetTheFullId() {
        assertEquals("brew:swiftness-jump", GateEffectId.parse("brew:swiftness-jump").orElseThrow().target());
        assertEquals("trade:WEAPONSMITH", GateEffectId.parse("trade:WEAPONSMITH").orElseThrow().target());
        assertEquals("feature:vein-mining", GateEffectId.parse("feature:vein-mining").orElseThrow().target());
        assertEquals("overenchant:lv1", GateEffectId.parse("overenchant:lv1").orElseThrow().target());
        assertEquals("reward:dragon-title", GateEffectId.parse("reward:dragon-title").orElseThrow().target());

        for (String id : new String[] {"brew:swiftness-jump", "trade:WEAPONSMITH", "feature:vein-mining",
                "overenchant:lv1", "reward:dragon-title"}) {
            assertEquals(DedicatedEffectChannel.FLAG, GateEffectId.parse(id).orElseThrow().channel());
        }
    }

    @Test
    void arsTierBareLiteralIsFlagTargetingItself() {
        GateEffectId id = GateEffectId.parse("ars-tier").orElseThrow();
        assertEquals(DedicatedEffectChannel.FLAG, id.channel());
        assertEquals("ars-tier", id.target());
    }

    @Test
    void unrecognizedOrLegacyIdsFailToParse() {
        assertTrue(GateEffectId.parse(null).isEmpty());
        assertTrue(GateEffectId.parse("").isEmpty());
        assertTrue(GateEffectId.parse("   ").isEmpty());
        assertTrue(GateEffectId.parse("light-glyph-unlock").isEmpty(), "bare legacy id (no prefix) must not parse");
        assertTrue(GateEffectId.parse("enchant-cost-reduction").isEmpty());
        assertTrue(GateEffectId.parse("unknownprefix:foo").isEmpty());
        assertTrue(GateEffectId.parse("glyph:").isEmpty(), "prefix with empty remainder must not parse");
        assertTrue(GateEffectId.parse(":foo").isEmpty(), "empty prefix must not parse");
    }

    @Test
    void featureIdOfExtractsBareFeatureIdFromFeaturePrefixedId() {
        assertEquals("vein-mining", GateEffectId.featureIdOf("feature:vein-mining").orElseThrow());
        assertTrue(GateEffectId.featureIdOf("glyph:blink").isEmpty());
        assertTrue(GateEffectId.featureIdOf("feature:").isEmpty());
        assertTrue(GateEffectId.featureIdOf(null).isEmpty());
    }

    @Test
    void isGateReflectsOnlyTheFourGateChannels() {
        assertTrue(DedicatedEffectChannel.GLYPH_GATE.isGate());
        assertTrue(DedicatedEffectChannel.RECIPE_GATE.isGate());
        assertTrue(DedicatedEffectChannel.RITUAL_GATE.isGate());
        assertTrue(DedicatedEffectChannel.DROP_GATE.isGate());
        assertFalse(DedicatedEffectChannel.FLAG.isGate());
    }
}
