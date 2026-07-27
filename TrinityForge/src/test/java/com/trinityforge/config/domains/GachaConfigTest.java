package com.trinityforge.config.domains;

import com.trinityforge.config.domains.GachaConfig.ParseResult;
import com.trinityforge.gacha.GachaEntry;
import com.trinityforge.gacha.GachaPool;
import com.trinityforge.gacha.GachaTicket;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GachaConfigTest {

    private static final Logger LOG = Logger.getLogger("GachaConfigTest");

    private static ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return GachaConfig.parse(cfg, LOG);
    }

    @Test
    void parsesValidTicketsAndPools() throws Exception {
        ParseResult r = parse("""
                tickets:
                  tf_gacha_ticket:
                    pool: standard
                pools:
                  standard:
                    entries:
                      - item: "tf_sword_rare"
                        weight: 10
                        amount: 1
                        quality-random: true
                      - item: "DIAMOND"
                        weight: 5
                        amount: 3
                """);
        assertEquals(0, r.skipped());

        GachaTicket ticket = r.tickets().get("tf_gacha_ticket");
        assertNotNull(ticket);
        assertEquals("standard", ticket.poolId());

        GachaPool pool = r.pools().get("standard");
        assertNotNull(pool);
        assertEquals(2, pool.entries().size());

        GachaEntry first = pool.entries().get(0);
        assertEquals("tf_sword_rare", first.itemId());
        assertEquals(10, first.weight());
        assertEquals(1, first.amount());
        assertTrue(first.qualityRandom());

        GachaEntry second = pool.entries().get(1);
        assertEquals("DIAMOND", second.itemId());
        assertEquals(5, second.weight());
        assertEquals(3, second.amount());
        assertFalse(second.qualityRandom());
    }

    @Test
    void qualityRandomDefaultsToFalseWhenAbsent() throws Exception {
        ParseResult r = parse("""
                pools:
                  standard:
                    entries:
                      - item: "DIAMOND"
                        weight: 1
                """);
        assertEquals(0, r.skipped());
        GachaEntry entry = r.pools().get("standard").entries().get(0);
        assertFalse(entry.qualityRandom());
        assertEquals(1, entry.amount(), "amount defaults to 1 when absent");
    }

    @Test
    void skipsEntryWithNonPositiveWeightButKeepsPool() throws Exception {
        ParseResult r = parse("""
                pools:
                  standard:
                    entries:
                      - item: "DIAMOND"
                        weight: 0
                      - item: "GOLD_INGOT"
                        weight: 5
                """);
        assertEquals(1, r.skipped());
        GachaPool pool = r.pools().get("standard");
        assertNotNull(pool);
        assertEquals(1, pool.entries().size());
        assertEquals("GOLD_INGOT", pool.entries().get(0).itemId());
    }

    @Test
    void skipsEntryWithNonPositiveAmount() throws Exception {
        ParseResult r = parse("""
                pools:
                  standard:
                    entries:
                      - item: "DIAMOND"
                        weight: 1
                        amount: 0
                      - item: "GOLD_INGOT"
                        weight: 5
                """);
        assertEquals(1, r.skipped());
        assertEquals(1, r.pools().get("standard").entries().size());
    }

    @Test
    void skipsEntryMissingItemId() throws Exception {
        ParseResult r = parse("""
                pools:
                  standard:
                    entries:
                      - weight: 5
                      - item: "GOLD_INGOT"
                        weight: 5
                """);
        assertEquals(1, r.skipped());
        assertEquals(1, r.pools().get("standard").entries().size());
    }

    @Test
    void poolWithNoValidEntriesIsSkippedEntirely() throws Exception {
        ParseResult r = parse("""
                pools:
                  empty:
                    entries:
                      - item: "DIAMOND"
                        weight: 0
                """);
        assertTrue(r.skipped() > 0);
        assertNull(r.pools().get("empty"));
    }

    @Test
    void poolWithMissingEntriesSectionIsSkipped() throws Exception {
        ParseResult r = parse("""
                pools:
                  broken: {}
                """);
        assertEquals(1, r.skipped());
        assertNull(r.pools().get("broken"));
    }

    @Test
    void ticketReferencingUnknownPoolIsSkipped() throws Exception {
        ParseResult r = parse("""
                tickets:
                  tf_gacha_ticket:
                    pool: missing_pool
                pools:
                  standard:
                    entries:
                      - item: "DIAMOND"
                        weight: 1
                """);
        assertEquals(1, r.skipped());
        assertNull(r.tickets().get("tf_gacha_ticket"));
        assertNotNull(r.pools().get("standard"), "the unrelated valid pool must still load");
    }

    @Test
    void ticketMissingPoolFieldIsSkipped() throws Exception {
        ParseResult r = parse("""
                tickets:
                  tf_gacha_ticket: {}
                """);
        assertEquals(1, r.skipped());
        assertNull(r.tickets().get("tf_gacha_ticket"));
    }

    @Test
    void emptyFileYieldsNoTicketsOrPoolsAndNoIssues() throws Exception {
        ParseResult r = parse("other: 1\n");
        assertEquals(0, r.skipped());
        assertTrue(r.tickets().isEmpty());
        assertTrue(r.pools().isEmpty());
    }

    @Test
    void poolWithoutPitySectionDefaultsThresholdToZero() throws Exception {
        ParseResult r = parse("""
                pools:
                  standard:
                    entries:
                      - item: "DIAMOND"
                        weight: 1
                """);
        assertEquals(0, r.pools().get("standard").pityThreshold());
    }

    @Test
    void poolParsesPityThresholdFromConfig() throws Exception {
        ParseResult r = parse("""
                pools:
                  standard:
                    pity:
                      threshold: 30
                    entries:
                      - item: "DIAMOND"
                        weight: 1
                """);
        assertEquals(30, r.pools().get("standard").pityThreshold());
    }

    @Test
    void poolClampsNegativePityThresholdToZero() throws Exception {
        ParseResult r = parse("""
                pools:
                  standard:
                    pity:
                      threshold: -10
                    entries:
                      - item: "DIAMOND"
                        weight: 1
                """);
        assertEquals(0, r.pools().get("standard").pityThreshold());
    }

    @Test
    void validTicketAndPoolTogetherLoadCleanly() throws Exception {
        ParseResult r = parse("""
                tickets:
                  tf_gacha_ticket:
                    pool: standard
                pools:
                  standard:
                    entries:
                      - item: "DIAMOND"
                        weight: 1
                """);
        assertEquals(0, r.skipped());
        assertEquals(1, r.tickets().size());
        assertEquals(1, r.pools().size());
    }
}
