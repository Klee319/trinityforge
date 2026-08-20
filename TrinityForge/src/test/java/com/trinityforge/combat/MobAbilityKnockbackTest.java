package com.trinityforge.combat;

import com.trinityforge.config.domains.MobAbilitiesConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-16 に追加した3つの型（{@code REPULSE} / {@code VORTEX_PULL} / {@code DELAYED_ZONE}）の
 * 純ロジックを固定する。
 *
 * <p>ここで守りたいのは3つ:
 * <ul>
 *   <li><b>NaN 速度を作らない</b> — 正規化できないベクトル（同一座標）を {@code normalize()} に
 *       渡すと速度が NaN になり、プレイヤーが操作不能になる。例外は出ないので気づけない。</li>
 *   <li><b>yml の 0〜5 をそのまま速度にしない</b> — Bukkit の速度はブロック/tick なので、
 *       5 をそのまま入れると毎秒 100 ブロックで場外へ飛ぶ（バニラのノックバックは約 0.4）。</li>
 *   <li><b>予告 0 秒を「即着弾」にしない</b> — 避けられる技という型そのものが、
 *       設定の書き忘れひとつで回避不能な高倍率AoEへ静かに化けるため。</li>
 * </ul>
 */
class MobAbilityKnockbackTest {

    private static final Logger LOG = Logger.getLogger("MobAbilityKnockbackTest");

    private static final Vector ORIGIN = new Vector(0.0, 64.0, 0.0);

    // ------------------------------------------------------------------
    // REPULSE
    // ------------------------------------------------------------------

    @Test
    @DisplayName("吹き飛ばしは中心から遠ざかる向き＋上向き成分を持つ")
    void repulsePushesAwayAndUp() {
        Vector victim = new Vector(3.0, 64.0, 0.0);

        Vector velocity = MobAbilityExecutor.repulseVelocity(ORIGIN, victim, 2.0);

        assertTrue(velocity.getX() > 0.0, "+X 側に居る相手は +X へ飛ぶべき: " + velocity);
        assertEquals(0.0, velocity.getZ(), 1.0e-9);
        assertTrue(velocity.getY() > 0.0, "上向き成分が無いと崖・落下が脅威にならない: " + velocity);
    }

    @Test
    @DisplayName("水平速度は knockback に比例するが、yml の値をそのまま速度にはしない")
    void repulseScalesButDoesNotUseRawKnockback() {
        Vector victim = new Vector(0.0, 64.0, 5.0);

        Vector weak = MobAbilityExecutor.repulseVelocity(ORIGIN, victim, 1.0);
        Vector strong = MobAbilityExecutor.repulseVelocity(ORIGIN, victim, 4.0);

        assertEquals(MobAbilityExecutor.REPULSE_HORIZONTAL_SCALE, weak.getZ(), 1.0e-9);
        assertEquals(4.0 * MobAbilityExecutor.REPULSE_HORIZONTAL_SCALE, strong.getZ(), 1.0e-9);
        // clamp 上限(5.0)でも「毎tick 5ブロック」にはならない = 場外へ吹き飛ばさない。
        Vector max = MobAbilityExecutor.repulseVelocity(ORIGIN, victim, 5.0);
        assertTrue(max.getZ() < 5.0, "yml の knockback をそのまま速度にしてはいけない: " + max);
    }

    @Test
    @DisplayName("上向き速度には天井がある（落下ダメージだけで殺せる高さまで打ち上げない）")
    void repulseVerticalIsCapped() {
        Vector victim = new Vector(1.0, 64.0, 0.0);

        Vector max = MobAbilityExecutor.repulseVelocity(ORIGIN, victim, 5.0);

        assertEquals(MobAbilityExecutor.REPULSE_MAX_VERTICAL, max.getY(), 1.0e-9);
    }

    @Test
    @DisplayName("同じ座標に重なっていても NaN にならない（真上へ飛ばす）")
    void repulseAtSamePositionIsPureLift() {
        Vector velocity = MobAbilityExecutor.repulseVelocity(ORIGIN, ORIGIN.clone(), 2.0);

        assertFinite(velocity);
        assertEquals(0.0, velocity.getX(), 1.0e-9);
        assertEquals(0.0, velocity.getZ(), 1.0e-9);
        assertTrue(velocity.getY() > 0.0);
    }

    // ------------------------------------------------------------------
    // VORTEX_PULL
    // ------------------------------------------------------------------

    @Test
    @DisplayName("引き寄せは中心へ向かう（吹き飛ばしと逆向き）")
    void pullMovesTowardCentre() {
        Vector victim = new Vector(10.0, 64.0, 0.0);

        Vector velocity = MobAbilityExecutor.pullVelocity(ORIGIN, victim, 2.0);

        assertTrue(velocity.getX() < 0.0, "+X 側に居る相手は -X（中心方向）へ引かれるべき: " + velocity);
        assertTrue(velocity.getY() > 0.0, "段差に引っ掛からないよう少し浮かせる");
    }

    @Test
    @DisplayName("遠い相手ほど強く引く（密着した相手を押し込むだけの速度にしない）")
    void pullIsStrongerFurtherAway() {
        double near = MobAbilityExecutor
                .pullVelocity(ORIGIN, new Vector(2.0, 64.0, 0.0), 2.0).clone().setY(0).length();
        double far = MobAbilityExecutor
                .pullVelocity(ORIGIN, new Vector(11.0, 64.0, 0.0), 2.0).clone().setY(0).length();

        assertTrue(far > near, "遠い方が強く引かれるべき: near=" + near + " far=" + far);
    }

    @Test
    @DisplayName("中心に重なっていても NaN にならない")
    void pullAtCentreIsFinite() {
        Vector velocity = MobAbilityExecutor.pullVelocity(ORIGIN, ORIGIN.clone(), 3.0);

        assertFinite(velocity);
        assertEquals(0.0, velocity.getX(), 1.0e-9);
        assertEquals(0.0, velocity.getZ(), 1.0e-9);
    }

    // ------------------------------------------------------------------
    // DELAYED_ZONE
    // ------------------------------------------------------------------

    @Test
    @DisplayName("予告時間 未設定(0) は即着弾ではなく既定値へ落ちる")
    void delayFallsBackInsteadOfInstantHit() {
        assertEquals(MobAbility.DEFAULT_DELAY_TICKS, ability("delayed_zone", 0.0).delayTicks());
    }

    @Test
    @DisplayName("予告時間は上下限へ丸める（短すぎ＝回避不能／長すぎ＝当たらない）")
    void delayIsClamped() {
        assertEquals(MobAbility.MIN_DELAY_TICKS, ability("delayed_zone", 0.05).delayTicks());
        assertEquals(MobAbility.MAX_DELAY_TICKS, ability("delayed_zone", 60.0).delayTicks());
        assertEquals(30, ability("delayed_zone", 1.5).delayTicks());
    }

    // ------------------------------------------------------------------
    // パース
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新しい3つの型が yml から読める（未知typeとして捨てられない）")
    void newTypesParse() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                abilities:
                  blast:
                    type: repulse
                    knockback: 4.5
                  grip:
                    type: vortex_pull
                    knockback: 1.6
                  mark:
                    type: delayed_zone
                    duration-seconds: 1.5
                """);
        MobAbilitiesConfig.ParseResult result =
                MobAbilitiesConfig.parse(cfg.getConfigurationSection("abilities"), LOG);

        assertEquals(0, result.skipped());
        assertEquals(MobAbility.Type.REPULSE, result.abilities().get("blast").type());
        assertEquals(MobAbility.Type.VORTEX_PULL, result.abilities().get("grip").type());
        assertEquals(MobAbility.Type.DELAYED_ZONE, result.abilities().get("mark").type());
        assertEquals(4.5, result.abilities().get("blast").knockback());
    }

    // ------------------------------------------------------------------

    private static MobAbility ability(String type, double durationSeconds) throws AssertionError {
        MobAbility.Type parsed = MobAbility.Type.valueOf(type.toUpperCase(java.util.Locale.ROOT));
        MobAbility ability = new MobAbility("t", "", parsed, DamageType.PHYSICAL,
                1.0, 10.0, 0.3, 16.0, 4.0, 1, 45.0, "", "", durationSeconds,
                0.0, java.util.List.of(), "", 0, "");
        assertNotNull(ability);
        return ability;
    }

    private static void assertFinite(Vector vector) {
        assertTrue(Double.isFinite(vector.getX()) && Double.isFinite(vector.getY())
                        && Double.isFinite(vector.getZ()),
                "NaN/Inf 速度はプレイヤーを操作不能にする: " + vector);
    }
}
