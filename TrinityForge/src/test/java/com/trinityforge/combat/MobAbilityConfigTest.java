package com.trinityforge.combat;

import com.trinityforge.config.domains.MobAbilitiesConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code combat/mob-abilities.yml} のパースと安全弁を固定する。
 *
 * <p>ここで守りたいのは「設定ミスがサーバを壊さない」こと。特殊攻撃は周期タスクから
 * 全プレイヤー分回るので、1件の書き間違いが毎 tick 例外を吐くと戦闘処理まで道連れになる。
 */
class MobAbilityConfigTest {

    private static final Logger LOG = Logger.getLogger("MobAbilityConfigTest");

    private static MobAbilitiesConfig.ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return MobAbilitiesConfig.parse(cfg.getConfigurationSection("abilities"), LOG);
    }

    @Test
    @DisplayName("最小構成（type だけ）で読める — 残りは既定値")
    void minimalEntryParses() throws Exception {
        MobAbilitiesConfig.ParseResult result = parse("""
                abilities:
                  slam:
                    type: ground_slam
                """);
        assertEquals(0, result.skipped());
        MobAbility ability = result.abilities().get("slam");
        assertNotNull(ability);
        assertEquals(MobAbility.Type.GROUND_SLAM, ability.type());
        // damage-type 未指定は物理。TYPELESS を許すと防御を一切通さない即死経路になる。
        assertEquals(DamageType.PHYSICAL, ability.damageType());
    }

    @Test
    @DisplayName("未知の type はそのエントリだけ捨てる（他の技は生き残る）")
    void unknownTypeSkipsOnlyThatEntry() throws Exception {
        MobAbilitiesConfig.ParseResult result = parse("""
                abilities:
                  broken:
                    type: laser_of_doom
                  good:
                    type: beam
                """);
        assertEquals(1, result.skipped());
        assertNull(result.abilities().get("broken"));
        assertNotNull(result.abilities().get("good"));
    }

    @Test
    @DisplayName("damage-type: magical は魔法として読む（物理/魔法の作り分けが効くこと）")
    void magicalDamageTypeIsHonoured() throws Exception {
        MobAbility ability = parse("""
                abilities:
                  ray:
                    type: beam
                    damage-type: MAGICAL
                """).abilities().get("ray");
        assertEquals(DamageType.MAGICAL, ability.damageType());
    }

    @Test
    @DisplayName("未知の damage-type は物理へ寄せる（防御を通さない抜け道を作らない）")
    void unknownDamageTypeFallsBackToPhysical() throws Exception {
        MobAbility ability = parse("""
                abilities:
                  ray:
                    type: beam
                    damage-type: typeless
                """).abilities().get("ray");
        assertEquals(DamageType.PHYSICAL, ability.damageType());
    }

    @Test
    @DisplayName("桁を間違えた数値は範囲へ丸める（サーバを止めない）")
    void insaneNumbersAreClamped() throws Exception {
        MobAbility ability = parse("""
                abilities:
                  oops:
                    type: ground_slam
                    damage-percent: 999999
                    cooldown-seconds: 0.001
                    chance: 5.0
                    range: 100000
                    radius: 100000
                    count: 100000
                    knockback: 100
                    particle-count: 100000
                """).abilities().get("oops");
        assertEquals(100.0, ability.damagePercent());
        assertEquals(0.5, ability.cooldownSeconds());
        assertEquals(1.0, ability.chance());
        assertEquals(64.0, ability.range());
        assertEquals(32.0, ability.radius());
        assertEquals(64, ability.count());
        assertEquals(5.0, ability.knockback());
        assertEquals(500, ability.particleCount());
    }

    @Test
    @DisplayName("effects は type 必須。type 無しの要素は落とす")
    void effectsWithoutTypeAreDropped() throws Exception {
        MobAbility ability = parse("""
                abilities:
                  chill:
                    type: aura
                    effects:
                      - type: SLOWNESS
                        duration-seconds: 4
                        amplifier: 2
                      - duration-seconds: 4
                """).abilities().get("chill");
        assertEquals(1, ability.effects().size());
        assertEquals("SLOWNESS", ability.effects().get(0).type());
        assertEquals(80, ability.effects().get(0).durationTicks());
        assertEquals(2, ability.effects().get(0).amplifier());
    }

    @Test
    @DisplayName("テンプレートIDは小文字へ正規化される（yml の大小揺れで参照が外れない）")
    void idsAreLowerCased() throws Exception {
        MobAbilitiesConfig.ParseResult result = parse("""
                abilities:
                  ShockWave:
                    type: ground_slam
                """);
        assertNotNull(result.abilities().get("shockwave"));
    }

    @Test
    @DisplayName("扇状の回転は基準ベクトルの長さを変えない（投射速度が方向で変わらない）")
    void rotationKeepsUnitLength() {
        org.bukkit.util.Vector base = new org.bukkit.util.Vector(1.0, 0.2, 0.0).normalize();
        for (int deg = -180; deg <= 180; deg += 15) {
            org.bukkit.util.Vector rotated =
                    MobAbilityExecutor.rotateAroundY(base, Math.toRadians(deg));
            assertTrue(Math.abs(rotated.length() - 1.0) < 1.0e-9,
                    deg + "度の回転で長さが変わっている: " + rotated.length());
        }
    }
}
