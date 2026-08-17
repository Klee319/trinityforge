package com.trinityforge.combat;

import com.trinityforge.config.domains.MobAbilitiesConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W-62（2026-08-18 ユーザー報告「矢の雨などのスキルが、死角＝壁の裏や非追跡状態でも発動する」）の回帰。
 *
 * <p><b>真因</b>: {@link MobAbilityTask} の走査は<b>プレイヤー起点で半径32m以内の全 LivingEntity を
 * 舐めるだけ</b>で、射程とクールダウンしか見ていなかった。索敵していないモブも、壁を挟んだモブも
 * 等しく抽選対象になっていた。
 *
 * <p>判定本体を {@link MobAbilityTask#engagementAllows(boolean, boolean, boolean, boolean, boolean)}
 * という純関数に切り出してあるのは、<b>MockBukkit が {@code LivingEntity#hasLineOfSight} も
 * {@code Mob#getTarget} も実装していない</b>ため。実体経由でテストすると
 * {@code UnimplementedOperationException} が SKIPPED に化けて、判定が一度も検証されないまま
 * 「緑」になる（このリポジトリの既知の罠）。
 */
class MobAbilityEngagementGateTest {

    private static final String ABILITIES = "src/main/resources/combat/mob-abilities.yml";

    private static YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new File(ABILITIES));
    }

    // ------------------------------------------------------------------
    // 非追跡状態（こちらに気づいていないモブ）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("狙っていない相手には撃たない（AIを持つモブ・視線は通っている）")
    void anAiMobThatIsNotTargetingThePlayerDoesNotFire() {
        assertFalse(MobAbilityTask.engagementAllows(true, true, false, true, true),
                "getTarget() が別人/未設定でも撃てるなら、気づかれていないのに技が飛んでくる");
    }

    @Test
    @DisplayName("狙っている相手には撃つ")
    void anAiMobTargetingThePlayerFires() {
        assertTrue(MobAbilityTask.engagementAllows(true, true, true, true, true));
    }

    @Test
    @DisplayName("AI を持たない LivingEntity には追跡条件を課さない（撃てなくなるのを防ぐ）")
    void entitiesWithoutAiAreExemptFromTheTargetCondition() {
        // AI を切られたボスや、実体だけ置いてあるギミックモブに getTarget() は無い。
        // ここで false を返すと、そういう個体が技を一生撃たなくなる。
        assertTrue(MobAbilityTask.engagementAllows(true, false, false, true, true));
    }

    // ------------------------------------------------------------------
    // 死角（壁の裏）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("視線が通らない相手には撃たない（＝壁に隠れて凌げる）")
    void aMobWithoutLineOfSightDoesNotFire() {
        assertFalse(MobAbilityTask.engagementAllows(true, true, true, true, false),
                "遮蔽越しに撃てるなら「隠れて凌ぐ」という回避手段が成立しない");
    }

    // ------------------------------------------------------------------
    // 逃がし口（config で切ったときは 2026-08-18 以前の挙動へ戻る）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("両方 false なら、非追跡・遮蔽越しでも撃つ（修正前の挙動へ戻せる）")
    void bothConditionsDisabledRestoresThePreviousBehaviour() {
        assertTrue(MobAbilityTask.engagementAllows(false, true, false, false, false));
    }

    @Test
    @DisplayName("片方だけ切れる（視線だけ要求／追跡だけ要求）")
    void eachConditionIsIndependent() {
        assertTrue(MobAbilityTask.engagementAllows(false, true, false, true, true),
                "追跡条件だけ切ったのに視線条件まで消えている");
        assertFalse(MobAbilityTask.engagementAllows(false, true, false, true, false),
                "追跡条件を切ると視線条件まで無効になっている");
        assertTrue(MobAbilityTask.engagementAllows(true, true, true, false, false),
                "視線条件だけ切ったのに追跡条件まで消えている");
    }

    // ------------------------------------------------------------------
    // 配線（判定が「正しいが呼ばれていない」no-op 修正を弾く）
    // ------------------------------------------------------------------

    /**
     * {@link MobAbilityTask#tryFire} まで実体を通して、交戦条件が<b>実際に効いている</b>ことを見る。
     *
     * <p>視線条件は切ってある（{@code requireLineOfSight=false}）。MockBukkit が
     * {@code hasLineOfSight} を実装しておらず、有効にすると例外が SKIPPED に化けて
     * このテスト自体が「緑」のまま何も検査しなくなるため。追跡条件だけで配線は証明できる。
     */
    @Test
    @DisplayName("配線: 狙われていないモブは tryFire が false を返し、狙った途端に撃つ")
    void theGateIsActuallyWiredIntoTheFiringPath() throws Exception {
        org.mockbukkit.mockbukkit.ServerMock server = org.mockbukkit.mockbukkit.MockBukkit.mock();
        try {
            org.mockbukkit.mockbukkit.world.WorldMock world = server.addSimpleWorld("engagement_world");
            org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer("Victim");
            player.setLocation(new org.bukkit.Location(world, 0, 64, 0));
            org.mockbukkit.mockbukkit.entity.ZombieMock zombie =
                    new org.mockbukkit.mockbukkit.entity.ZombieMock(server, java.util.UUID.randomUUID());
            zombie.setLocation(new org.bukkit.Location(world, 3, 64, 0));

            YamlConfiguration cfg = new YamlConfiguration();
            cfg.loadFromString("""
                    abilities:
                      slam:
                        type: ground_slam
                        chance: 1.0
                        range: 24
                        cooldown-seconds: 0
                    """);
            MobAbility slam = MobAbilitiesConfig
                    .parse(cfg.getConfigurationSection("abilities"), java.util.logging.Logger
                            .getLogger("MobAbilityEngagementGateTest"))
                    .abilities().get("slam");

            MobAbilitiesConfig abilities = org.mockito.Mockito.mock(MobAbilitiesConfig.class);
            org.mockito.Mockito.when(abilities.requireTarget()).thenReturn(true);
            org.mockito.Mockito.when(abilities.requireLineOfSight()).thenReturn(false);
            org.mockito.Mockito.when(abilities.ability("slam")).thenReturn(slam);
            org.mockito.Mockito.when(abilities.globalCooldownMillis()).thenReturn(0L);

            com.trinityforge.config.domains.MobOverridesConfig overrides =
                    org.mockito.Mockito.mock(com.trinityforge.config.domains.MobOverridesConfig.class);
            org.mockito.Mockito.when(overrides.abilitiesFor(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString())).thenReturn(java.util.List.of("slam"));

            MobAbilityExecutor executor = org.mockito.Mockito.mock(MobAbilityExecutor.class);
            org.mockito.Mockito.when(executor.execute(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(true);

            MobAbilityTask task = new MobAbilityTask(
                    org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin("TrinityForge"),
                    abilities, overrides, executor,
                    new MobAbilityCooldowns(() -> 0L), new java.util.Random(1));

            assertFalse(task.tryFire(zombie, player),
                    "狙っていない相手へ撃てている = 交戦条件が発動経路へ配線されていない");

            zombie.setTarget(player);
            assertTrue(task.tryFire(zombie, player),
                    "狙っている相手にも撃てないなら、条件が厳しすぎて技が死んでいる");
        } finally {
            org.mockbukkit.mockbukkit.MockBukkit.unmock();
        }
    }

    // ------------------------------------------------------------------
    // config
    // ------------------------------------------------------------------

    @Test
    @DisplayName("既定は両方 true（未設定の config でも死角・非追跡では撃たない）")
    void defaultsAreOnSoAnUnconfiguredServerIsAlreadyFixed() {
        assertTrue(MobAbilitiesConfig.DEFAULT_REQUIRE_TARGET);
        assertTrue(MobAbilitiesConfig.DEFAULT_REQUIRE_LINE_OF_SIGHT);
    }

    @Test
    @DisplayName("出荷 mob-abilities.yml が両方 true で出荷されている")
    void shippedConfigEnablesBothConditions() {
        YamlConfiguration yaml = shipped();
        assertTrue(yaml.getBoolean("require-target", false),
                "require-target を false で出荷すると、気づいていないモブが技を撃つ報告に戻る");
        assertTrue(yaml.getBoolean("require-line-of-sight", false),
                "require-line-of-sight を false で出荷すると、壁の裏から技が飛んでくる報告に戻る");
    }
}
