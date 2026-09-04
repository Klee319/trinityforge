package com.trinityforge.combat;

import com.trinityforge.config.domains.MobAbilitiesConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TelegraphFeasibility}（Codex UXレビュー #6「机上検査」）の純関数テスト。
 */
class TelegraphFeasibilityTest {

    private static MobAbility ability(String id, String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("abilities:\n  " + id + ":\n" + yaml.stripIndent().indent(4));
        MobAbilitiesConfig.ParseResult result = MobAbilitiesConfig.parse(
                cfg.getConfigurationSection("abilities"), Logger.getLogger("TelegraphFeasibilityTest"));
        MobAbility parsed = result.abilities().get(id);
        assertNotNull(parsed, "test ability '" + id + "' failed to parse (skipped=" + result.skipped() + ")");
        return parsed;
    }

    @Test
    @DisplayName("allowedRadius: 詠唱秒がD95以下なら移動可能距離0(安全余白ぶんマイナス)")
    void allowedRadiusBelowReactionTimeIsNegative() {
        double radius = TelegraphFeasibility.allowedRadius(TelegraphFeasibility.WALK_SPEED, 0.2);
        assertEquals(-TelegraphFeasibility.SAFETY_MARGIN, radius, 1e-9);
    }

    @Test
    @DisplayName("allowedRadius: 速度*(詠唱-D95)-安全余白の式どおり")
    void allowedRadiusMatchesFormula() {
        double expected = TelegraphFeasibility.WALK_SPEED * (2.0 - TelegraphFeasibility.D95_SECONDS)
                - TelegraphFeasibility.SAFETY_MARGIN;
        assertEquals(expected, TelegraphFeasibility.allowedRadius(TelegraphFeasibility.WALK_SPEED, 2.0), 1e-9);
    }

    @Test
    @DisplayName("meteor_mark(既存出荷技、delayed_zone・半径3.5・実質2秒予告)は合格する")
    void meteorMarkIsWalkable() throws Exception {
        MobAbility meteorMark = ability("meteor_mark", """
                type: delayed_zone
                damage-percent: 2.1
                radius: 3.5
                duration-seconds: 2
                range: 24
                cooldown-seconds: 18
                """);
        assertEquals(40, meteorMark.telegraphTicks(), "duration-seconds 2 -> 40tick(2.0秒)のはず");
        assertTrue(TelegraphFeasibility.walkable(meteorMark), "出荷中のmeteor_markが机上検査に落ちている");
    }

    @Test
    @DisplayName("gale_smash(予告なしのrepulse)は検査対象外として常にtrue")
    void galeSmashWithoutTelegraphIsAlwaysWalkable() throws Exception {
        MobAbility galeSmash = ability("gale_smash", """
                type: repulse
                damage-percent: 1.25
                radius: 6
                range: 6
                cooldown-seconds: 11
                """);
        assertEquals(0, galeSmash.telegraphTicks());
        assertTrue(TelegraphFeasibility.walkable(galeSmash));
    }

    @Test
    @DisplayName("同じ半径6のrepulseにcast-seconds 1.5を入れると机上検査に落ちる(回避不能技)")
    void sameRepulseWithShortCastFailsFeasibility() throws Exception {
        MobAbility staged = ability("staged_gale", """
                type: repulse
                damage-percent: 1.25
                radius: 6
                range: 6
                cooldown-seconds: 11
                cast-seconds: 1.5
                """);
        assertFalse(TelegraphFeasibility.walkable(staged),
                "半径6は1.5秒詠唱で歩いて避けられないはずなのにtrueになった");
    }

    @Test
    @DisplayName("BEAMは太さの直径(2*radius)を実効半径として使う")
    void beamUsesDiameterAsEffectiveRadius() throws Exception {
        MobAbility beam = ability("test_beam", """
                type: beam
                damage-percent: 1.0
                radius: 1.0
                count: 10
                range: 20
                cooldown-seconds: 5
                cast-seconds: 1.0
                """);
        // allowedRadius(walk, 1.0) = 4.317*(1.0-0.35)-0.5 = 2.306 ; 実効半径 = 2*radius = 2.0 -> true
        assertTrue(TelegraphFeasibility.walkable(beam));
    }

    @Test
    @DisplayName("DELAYED_ZONE/FIXED_ZONEは単一解型として0.8倍だけ緩める")
    void singleMarkTypesAreLenient() throws Exception {
        MobAbility zone = ability("lenient_zone", """
                type: fixed_zone
                damage-percent: 1.0
                radius: 5.0
                range: 20
                cooldown-seconds: 10
                cast-seconds: 1.0
                """);
        double allowed = TelegraphFeasibility.allowedRadius(TelegraphFeasibility.WALK_SPEED, 1.0);
        boolean expectWalkable = allowed >= zone.radius() * 0.8;
        assertEquals(expectWalkable, TelegraphFeasibility.walkable(zone));
    }
}
