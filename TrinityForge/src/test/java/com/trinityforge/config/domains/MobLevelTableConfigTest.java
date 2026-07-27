package com.trinityforge.config.domains;

import com.trinityforge.config.domains.MobLevelTableConfig.ParseResult;
import com.trinityforge.mobs.LevelTierDropEntry;
import com.trinityforge.mobs.LevelTierRule;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MobLevelTableConfig}: parse + band-boundary resolution + back-compat empty behavior. */
class MobLevelTableConfigTest {

    private static final Logger LOG = Logger.getLogger("MobLevelTableConfigTest");

    private static ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return MobLevelTableConfig.parse(cfg, LOG);
    }

    @Test
    void defaultFileHasNoTiersAndIsFullyBackwardCompatible() throws Exception {
        ParseResult r = parse("dungeon-only: false\ntiers: []\n");
        assertEquals(0, r.skipped());
        assertEquals(0, r.tierCount());
        assertFalse(r.dungeonOnly());
        assertEquals(Optional.empty(), r.tiers().resolve(0));
        assertEquals(Optional.empty(), r.tiers().resolve(999));
    }

    @Test
    void missingTopLevelKeysYieldEmptyTable() throws Exception {
        ParseResult r = parse("");
        assertEquals(0, r.skipped());
        assertTrue(r.tiers().isEmpty());
        assertFalse(r.dungeonOnly());
    }

    @Test
    void dungeonOnlyFlagParses() throws Exception {
        ParseResult r = parse("dungeon-only: true\ntiers: []\n");
        assertTrue(r.dungeonOnly());
    }

    @Test
    void parsesFullTierWithRemoveAddAndExp() throws Exception {
        ParseResult r = parse("""
                dungeon-only: false
                tiers:
                  - min-level: 0
                    remove-drops: [ROTTEN_FLESH]
                    add-drops:
                      - { material: BONE, chance: 0.3, min: 1, max: 2 }
                    vanilla-exp: 5
                  - min-level: 20
                    remove-drops: []
                    add-drops:
                      - { material: IRON_INGOT, chance: 0.05, min: 1, max: 1 }
                    vanilla-exp: 12
                """);
        assertEquals(0, r.skipped());
        assertEquals(2, r.tierCount());

        LevelTierRule band0 = r.tiers().resolve(0).orElseThrow();
        assertEquals(1, band0.removeDrops().size());
        assertEquals(Material.ROTTEN_FLESH, band0.removeDrops().get(0));
        assertEquals(1, band0.addDrops().size());
        assertEquals(Material.BONE, band0.addDrops().get(0).material());
        assertEquals(5, band0.vanillaExp());

        // Just below the next band's min-level: still band0 (upper-bound-exact test).
        LevelTierRule band0AtEdge = r.tiers().resolve(19).orElseThrow();
        assertEquals(5, band0AtEdge.vanillaExp());

        // Exactly at the next band's min-level: band20 (lower-bound-exact test).
        LevelTierRule band20 = r.tiers().resolve(20).orElseThrow();
        assertTrue(band20.removeDrops().isEmpty());
        assertEquals(12, band20.vanillaExp());

        // Far above every band: keeps resolving to the highest one.
        assertEquals(12, r.tiers().resolve(500).orElseThrow().vanillaExp());
    }

    @Test
    void levelBelowLowestBandResolvesToEmpty() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 10
                    vanilla-exp: 7
                """);
        assertEquals(Optional.empty(), r.tiers().resolve(0));
        assertEquals(Optional.empty(), r.tiers().resolve(9));
        assertEquals(7, r.tiers().resolve(10).orElseThrow().vanillaExp());
    }

    @Test
    void unconfiguredFieldsAreOptionalAndDefaultEmpty() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                """);
        LevelTierRule band0 = r.tiers().resolve(0).orElseThrow();
        assertTrue(band0.removeDrops().isEmpty());
        assertTrue(band0.addDrops().isEmpty());
        assertEquals(null, band0.vanillaExp());
    }

    @Test
    void negativeMinLevelIsSkipped() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: -5
                    vanilla-exp: 1
                """);
        assertEquals(1, r.skipped());
        assertTrue(r.tiers().isEmpty());
    }

    @Test
    void duplicateMinLevelKeepsFirstAndSkipsSecond() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    vanilla-exp: 1
                  - min-level: 0
                    vanilla-exp: 99
                """);
        assertEquals(1, r.skipped());
        assertEquals(1, r.tiers().resolve(0).orElseThrow().vanillaExp());
    }

    @Test
    void invalidRemoveDropsMaterialIsSkippedButBandSurvives() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    remove-drops: [NOT_A_REAL_MATERIAL, BONE]
                """);
        assertEquals(1, r.skipped());
        LevelTierRule band0 = r.tiers().resolve(0).orElseThrow();
        assertEquals(1, band0.removeDrops().size());
        assertEquals(Material.BONE, band0.removeDrops().get(0));
    }

    @Test
    void addDropsMissingRequiredFieldIsSkipped() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: BONE, chance: 0.3 }
                """);
        assertEquals(1, r.skipped());
        assertTrue(r.tiers().resolve(0).orElseThrow().addDrops().isEmpty());
    }

    // --- 2026-07-25 レベルテーブルのモブ別ドロップ指定拡張 (§2-A mobs / §2-B custom:) ---

    @Test
    void addDropsMobsUnspecifiedYieldsEmptySetMeaningEveryMob() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: BONE, chance: 0.3, min: 1, max: 1 }
                """);
        assertEquals(0, r.skipped());
        LevelTierDropEntry drop = r.tiers().resolve(0).orElseThrow().addDrops().get(0);
        assertTrue(drop.mobs().isEmpty());
        assertTrue(drop.appliesTo(EntityType.ZOMBIE));
        assertTrue(drop.appliesTo(EntityType.CHICKEN));
    }

    @Test
    void addDropsMobsListParsesToEntityTypeSet() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: BONE, chance: 0.3, min: 1, max: 1, mobs: [ZOMBIE, SKELETON] }
                """);
        assertEquals(0, r.skipped());
        LevelTierDropEntry drop = r.tiers().resolve(0).orElseThrow().addDrops().get(0);
        assertEquals(Set.of(EntityType.ZOMBIE, EntityType.SKELETON), drop.mobs());
        assertTrue(drop.appliesTo(EntityType.ZOMBIE));
        assertFalse(drop.appliesTo(EntityType.CHICKEN));
    }

    @Test
    void addDropsInvalidMobEntryIsSkippedButOthersSurvive() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: BONE, chance: 0.3, min: 1, max: 1, mobs: [NOT_A_REAL_ENTITY_TYPE, ZOMBIE] }
                """);
        assertEquals(1, r.skipped());
        LevelTierDropEntry drop = r.tiers().resolve(0).orElseThrow().addDrops().get(0);
        assertEquals(Set.of(EntityType.ZOMBIE), drop.mobs());
    }

    @Test
    void addDropsCustomPrefixParsesAsCatalogReference() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: "custom:tf_core_meat", chance: 0.1, min: 1, max: 2 }
                """);
        assertEquals(0, r.skipped());
        LevelTierDropEntry drop = r.tiers().resolve(0).orElseThrow().addDrops().get(0);
        assertTrue(drop.isCustom());
        assertEquals("tf_core_meat", drop.catalogId());
        assertEquals(null, drop.material());
        assertEquals(0.1, drop.chance());
        assertEquals(1, drop.min());
        assertEquals(2, drop.max());
    }

    @Test
    void addDropsCustomPrefixIsCaseInsensitiveAndTrimmed() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: "CUSTOM: tf_core_meat ", chance: 0.1, min: 1, max: 1 }
                """);
        assertEquals(0, r.skipped());
        LevelTierDropEntry drop = r.tiers().resolve(0).orElseThrow().addDrops().get(0);
        assertTrue(drop.isCustom());
        assertEquals("tf_core_meat", drop.catalogId());
    }

    @Test
    void addDropsBlankCustomIdIsSkipped() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: "custom:", chance: 0.1, min: 1, max: 1 }
                """);
        assertEquals(1, r.skipped());
        assertTrue(r.tiers().resolve(0).orElseThrow().addDrops().isEmpty());
    }

    @Test
    void negativeVanillaExpIsIgnoredNotFatal() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    vanilla-exp: -1
                """);
        assertEquals(0, r.skipped());
        assertEquals(null, r.tiers().resolve(0).orElseThrow().vanillaExp());
    }

    // --- 2026-07-26 対象モブ絞り込み: mob-ids(EliteMobsモブid)と帯そのものへのフィルタ ---
    // 動機: ダンジョンは1つ丸ごと同じ EntityType(見た目替えのZOMBIE等)であることが多く、
    // mobs:(EntityType)だけでは「このボスにだけ」という指定ができなかった。

    @Test
    void bandLevelTargetFilterUnspecifiedAppliesToEveryMob() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    vanilla-exp: 7
                """);
        assertEquals(0, r.skipped());
        LevelTierRule band = r.tiers().resolve(0).orElseThrow();
        assertTrue(band.targets().isEmpty());
        assertTrue(band.appliesTo(EntityType.ZOMBIE, null));
        assertTrue(band.appliesTo(EntityType.CHICKEN, "the_mines_boss"));
    }

    @Test
    void bandLevelMobIdsRestrictToNamedProfiles() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    mob-ids: [the_mines_boss, guild_boss]
                    vanilla-exp: 7
                """);
        assertEquals(0, r.skipped());
        LevelTierRule band = r.tiers().resolve(0).orElseThrow();
        assertEquals(Set.of("the_mines_boss", "guild_boss"), band.targets().mobIds());
        assertTrue(band.appliesTo(EntityType.ZOMBIE, "the_mines_boss"));
        assertFalse(band.appliesTo(EntityType.ZOMBIE, "some_other_mob"));
        assertFalse(band.appliesTo(EntityType.ZOMBIE, null));
    }

    @Test
    void bandLevelMobsAndMobIdsAreAndedTogether() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    mobs: [ZOMBIE]
                    mob-ids: [the_mines_boss]
                """);
        assertEquals(0, r.skipped());
        LevelTierRule band = r.tiers().resolve(0).orElseThrow();
        assertTrue(band.appliesTo(EntityType.ZOMBIE, "the_mines_boss"));
        assertFalse(band.appliesTo(EntityType.SKELETON, "the_mines_boss"));
        assertFalse(band.appliesTo(EntityType.ZOMBIE, "guild_boss"));
    }

    @Test
    void addDropsMobIdsRestrictToNamedProfiles() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: BONE, chance: 0.3, min: 1, max: 1, mob-ids: [the_mines_boss] }
                """);
        assertEquals(0, r.skipped());
        LevelTierDropEntry drop = r.tiers().resolve(0).orElseThrow().addDrops().get(0);
        assertEquals(Set.of("the_mines_boss"), drop.targets().mobIds());
        assertTrue(drop.appliesTo(EntityType.ZOMBIE, "the_mines_boss"));
        assertFalse(drop.appliesTo(EntityType.ZOMBIE, "guild_boss"));
        // EntityType軸は無指定なので、モブid一致なら種類は問わない。
        assertTrue(drop.appliesTo(EntityType.CHICKEN, "the_mines_boss"));
    }

    @Test
    void mobIdsAreNormalizedLikeEliteMobsFilenames() throws Exception {
        // EliteMobs の getFilename() は ".yml" 付きで返る。どちらの書き方でも同じidに正規化する。
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    mob-ids: ["the_mines_boss.yml"]
                """);
        assertEquals(0, r.skipped());
        LevelTierRule band = r.tiers().resolve(0).orElseThrow();
        assertEquals(Set.of("the_mines_boss"), band.targets().mobIds());
        assertTrue(band.appliesTo(EntityType.ZOMBIE, "the_mines_boss.yml"));
        assertTrue(band.appliesTo(EntityType.ZOMBIE, "the_mines_boss"));
    }

    @Test
    void blankMobIdEntriesAreSkippedButOthersSurvive() throws Exception {
        ParseResult r = parse("""
                tiers:
                  - min-level: 0
                    mob-ids: ["", the_mines_boss]
                """);
        assertEquals(1, r.skipped());
        assertEquals(Set.of("the_mines_boss"), r.tiers().resolve(0).orElseThrow().targets().mobIds());
    }

    // --- 2026-07-27 牧場対策: no-skill-exp-mobs (トップレベル、tiers とは独立) ---
    // バニラEXP(オーブ)は対象外。TrinityForgeの戦闘スキルEXP(武器命中/防具被弾/魔法詠唱)だけを
    // 止めるためのリスト — 実際の抑止判定は CombatListener/NativeSkillExperienceListener 側で行う
    // (MobLevelTableConfigTest はパース結果だけを検証する)。

    @Test
    void noSkillExpMobsOmittedYieldsEmptySet() throws Exception {
        ParseResult r = parse("tiers: []\n");
        assertEquals(0, r.skipped());
        assertTrue(r.noSkillExpMobs().isEmpty());
    }

    @Test
    void noSkillExpMobsEmptyListYieldsEmptySet() throws Exception {
        ParseResult r = parse("no-skill-exp-mobs: []\ntiers: []\n");
        assertEquals(0, r.skipped());
        assertTrue(r.noSkillExpMobs().isEmpty());
    }

    @Test
    void noSkillExpMobsParsesToEntityTypeSet() throws Exception {
        ParseResult r = parse("""
                no-skill-exp-mobs:
                  - BEE
                  - GOAT
                  - IRON_GOLEM
                """);
        assertEquals(0, r.skipped());
        assertEquals(Set.of(EntityType.BEE, EntityType.GOAT, EntityType.IRON_GOLEM), r.noSkillExpMobs());
    }

    @Test
    void noSkillExpMobsIsCaseInsensitive() throws Exception {
        ParseResult r = parse("no-skill-exp-mobs: [bee, Goat]\n");
        assertEquals(0, r.skipped());
        assertEquals(Set.of(EntityType.BEE, EntityType.GOAT), r.noSkillExpMobs());
    }

    @Test
    void noSkillExpMobsUnknownEntityTypeIsSkippedButOthersSurvive() throws Exception {
        ParseResult r = parse("no-skill-exp-mobs: [NOT_A_REAL_ENTITY_TYPE, BEE]\n");
        assertEquals(1, r.skipped());
        assertEquals(Set.of(EntityType.BEE), r.noSkillExpMobs());
    }

    @Test
    void noSkillExpMobsBlankEntryIsSkippedButOthersSurvive() throws Exception {
        ParseResult r = parse("no-skill-exp-mobs: [\"\", BEE]\n");
        assertEquals(1, r.skipped());
        assertEquals(Set.of(EntityType.BEE), r.noSkillExpMobs());
    }
}
