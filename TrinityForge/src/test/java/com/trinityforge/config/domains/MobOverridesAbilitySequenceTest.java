package com.trinityforge.config.domains;

import com.trinityforge.config.domains.MobOverridesConfig.ParseResult;
import com.trinityforge.mobs.MobOverrideEntry;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MobOverridesConfig} の {@code ability-sequence} / {@code ability-interval-seconds}
 * (2026-09-04、UXレビュー #12「技選択のリズム」)の読み取りを検証する。
 *
 * <p>解決規則は {@link MobOverridesConfig#abilitiesFor} と同じ REPLACE(world scope が設定していれば
 * それ、無ければ default)であることも {@link #worldScopeOverridesDefaultForSequence()} で見る。
 */
class MobOverridesAbilitySequenceTest {

    private static final Logger LOG = Logger.getLogger("MobOverridesAbilitySequenceTest");

    private static ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.options().pathSeparator(MobOverridesConfig.MOB_ID_SAFE_PATH_SEPARATOR);
        cfg.loadFromString(yaml);
        return MobOverridesConfig.parse(cfg, LOG);
    }

    private static MobOverridesConfig configOf(Map<String, Map<String, MobOverrideEntry>> scopes) throws Exception {
        MobOverridesConfig config = new MobOverridesConfig();
        java.lang.reflect.Field field = MobOverridesConfig.class.getDeclaredField("scopes");
        field.setAccessible(true);
        field.set(config, scopes);
        return config;
    }

    @Test
    void abilitySequenceIsReadAndDuplicatesAreAllowed() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      boss_one:
                        abilities: [shockwave]
                        ability-sequence: [shockwave, bull_rush, shockwave]
                """);
        assertEquals(0, r.skipped());
        MobOverrideEntry entry = r.scopes().get(MobOverridesConfig.DEFAULT_SCOPE).get("boss_one");
        // abilities: と違い重複を許す(振り付けとして同じ技を連続させられる)。
        assertEquals(List.of("shockwave", "bull_rush", "shockwave"), entry.abilitySequence());
    }

    @Test
    void abilitySequenceIdsAreAutoAddedToAbilities() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      boss_two:
                        abilities: [shockwave]
                        ability-sequence: [shockwave, bull_rush, meteor_mark]
                """);
        assertEquals(0, r.skipped());
        MobOverrideEntry entry = r.scopes().get(MobOverridesConfig.DEFAULT_SCOPE).get("boss_two");
        // sequence に書いた技は「撃てるのが直感」なので abilities へ自動追加される(警告なし)。
        assertTrue(entry.abilities().containsAll(List.of("shockwave", "bull_rush", "meteor_mark")),
                "ability-sequence の技が abilities へ自動追加されていない: " + entry.abilities());
    }

    @Test
    void abilityIntervalSecondsIsReadAndRoundedIntoRange() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      boss_three:
                        ability-interval-seconds: 200
                      boss_four:
                        ability-interval-seconds: 0.1
                      boss_five:
                        ability-interval-seconds: 3
                """);
        assertEquals(0, r.skipped());
        Map<String, MobOverrideEntry> mobs = r.scopes().get(MobOverridesConfig.DEFAULT_SCOPE);
        assertEquals(120.0, mobs.get("boss_three").abilityIntervalSeconds(), 1.0e-9,
                "上限120秒へ丸められていない");
        assertEquals(0.5, mobs.get("boss_four").abilityIntervalSeconds(), 1.0e-9,
                "下限0.5秒へ丸められていない");
        assertEquals(3.0, mobs.get("boss_five").abilityIntervalSeconds(), 1.0e-9);
    }

    @Test
    void invalidAbilityIntervalSecondsIsSkippedWithWarning() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      boss_six:
                        ability-interval-seconds: "not-a-number"
                """);
        assertEquals(1, r.skipped());
        MobOverrideEntry entry = r.scopes().get(MobOverridesConfig.DEFAULT_SCOPE).get("boss_six");
        assertEquals(null, entry.abilityIntervalSeconds());
    }

    @Test
    void unspecifiedAbilitySequenceAndIntervalAreEmpty() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      plain_mob:
                        abilities: [shockwave]
                """);
        MobOverrideEntry entry = r.scopes().get(MobOverridesConfig.DEFAULT_SCOPE).get("plain_mob");
        assertTrue(entry.abilitySequence().isEmpty());
        assertEquals(null, entry.abilityIntervalSeconds());

        MobOverridesConfig config = configOf(r.scopes());
        assertTrue(config.abilitySequenceFor("any_world", "plain_mob").isEmpty());
        assertFalse(config.abilityIntervalSecondsFor("any_world", "plain_mob").isPresent());
    }

    @Test
    void worldScopeOverridesDefaultForSequence() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      shared_boss:
                        abilities: [shockwave]
                        ability-sequence: [shockwave]
                        ability-interval-seconds: 5
                  boss_world:
                    mobs:
                      shared_boss:
                        abilities: [bull_rush]
                        ability-sequence: [bull_rush, bull_rush]
                        ability-interval-seconds: 2
                """);
        assertEquals(0, r.skipped());
        MobOverridesConfig config = configOf(r.scopes());

        // ワールドスコープが1件でも書いていればそれが全部で、default とは合成しない(REPLACE)。
        assertEquals(List.of("bull_rush", "bull_rush"),
                config.abilitySequenceFor("boss_world", "shared_boss"));
        OptionalDouble worldInterval = config.abilityIntervalSecondsFor("boss_world", "shared_boss");
        assertTrue(worldInterval.isPresent());
        assertEquals(2.0, worldInterval.getAsDouble(), 1.0e-9);

        // world scope が存在しないワールドは default へフォールスルーする。
        assertEquals(List.of("shockwave"), config.abilitySequenceFor("other_world", "shared_boss"));
        OptionalDouble defaultInterval = config.abilityIntervalSecondsFor("other_world", "shared_boss");
        assertTrue(defaultInterval.isPresent());
        assertEquals(5.0, defaultInterval.getAsDouble(), 1.0e-9);
    }
}
