package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.MobAbilitiesConfig;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 予告機構(2026-09-02仕様 + 2026-09-04 UXクロスレビュー追加指示)の {@link MobAbilityExecutor} 統合テスト。
 *
 * <p>{@code hasLineOfSight} が MockBukkit 未実装で {@code UnimplementedOperationException}
 * (JUnit の {@code TestAbortedException} 継承)を投げる既知の罠があるため、
 * 詠唱ループ本体がそれを try/catch で握っていることを前提に、
 * {@link MobAbilityExecutor#setLineOfSightCheck} で挙動を直接固定して検証する。
 */
class MobAbilityExecutorTelegraphTest {

    private static final SkillLevelSource NO_SKILLS = id -> Map.of();

    private ServerMock server;
    private WorldMock world;
    private PluginMock plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.createMockPlugin("TrinityForge");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private SymmetricCombatService combatService(File dir, double maxDodgeChance) throws IOException {
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                  min-component-damage: 1.0
                magical:
                  base-coefficient: 1.0
                  min-component-damage: 1.0
                  scale-with-combat-level: false
                level-scaling:
                  per-level: 0.0
                defense:
                  max-dodge-chance: %s
                """.formatted(maxDodgeChance));
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        return new SymmetricCombatService(damage, cm.combatLevel(), cm.mobTypes(), NO_SKILLS, defense);
    }

    private static MobAbility ability(String id, String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("abilities:\n  " + id + ":\n" + yaml.stripIndent().indent(4));
        MobAbilitiesConfig.ParseResult result = MobAbilitiesConfig.parse(
                cfg.getConfigurationSection("abilities"), Logger.getLogger("MobAbilityExecutorTelegraphTest"));
        MobAbility parsed = result.abilities().get(id);
        assertNotNull(parsed, "test ability '" + id + "' failed to parse (skipped=" + result.skipped() + ")");
        return parsed;
    }

    // ------------------------------------------------------------------
    // 機構2: 詠唱と telegraphed の伝搬
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cast-seconds>0のGROUND_SLAMは詠唱完了までダメージが出ず、完了後にtelegraphed=trueで届く")
    void castedAbilityDelaysDamageAndReportsTelegraphedTrue(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir, 0.0);
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), new TelegraphBudget());
        List<Boolean> hits = new ArrayList<>();
        executor.setHitObserver((player, telegraphed) -> hits.add(telegraphed));

        Location loc = new Location(world, 100, 64, 100);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc);

        MobAbility slam = ability("cast_slam", """
                type: ground_slam
                cast-seconds: 1.0
                damage-percent: 50.0
                radius: 4.0
                range: 24
                cooldown-seconds: 0
                """);
        assertEquals(20, slam.castTicks());

        boolean started = executor.execute(mob, target, slam);
        assertTrue(started, "予約が取れるはずの詠唱がfalseを返した");
        assertTrue(hits.isEmpty(), "詠唱開始直後にダメージが出てはいけない");

        server.getScheduler().performTicks(19);
        assertTrue(hits.isEmpty(), "詠唱完了前(19/20tick)にダメージが出た");

        server.getScheduler().performTicks(2);
        assertEquals(List.of(true), hits, "詠唱完了後の一撃はtelegraphed=trueで届くべき");
    }

    @Test
    @DisplayName("cast-secondsが0の技は即座にtelegraphed=falseで届く")
    void immediateAbilityReportsTelegraphedFalseRightAway(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir, 0.0);
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), new TelegraphBudget());
        List<Boolean> hits = new ArrayList<>();
        executor.setHitObserver((player, telegraphed) -> hits.add(telegraphed));

        Location loc = new Location(world, 200, 64, 200);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc);

        MobAbility slam = ability("instant_slam", """
                type: ground_slam
                damage-percent: 50.0
                radius: 4.0
                range: 24
                cooldown-seconds: 0
                """);
        assertEquals(0, slam.castTicks());

        assertTrue(executor.execute(mob, target, slam));
        assertEquals(List.of(false), hits, "予告なしの技はtelegraphed=falseで即着弾するべき");
    }

    @Test
    @DisplayName("詠唱中に対象がログアウトすると不発になり、予告予算が解放される")
    void targetLeavingDuringCastMisfires(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir, 0.0);
        TelegraphBudget budget = new TelegraphBudget();
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), budget);
        List<Boolean> hits = new ArrayList<>();
        executor.setHitObserver((player, telegraphed) -> hits.add(telegraphed));

        Location loc = new Location(world, 300, 64, 300);
        Zombie mob = world.spawn(loc, Zombie.class);
        PlayerMock target = server.addPlayer();
        target.teleport(loc);

        MobAbility slam = ability("leave_slam", """
                type: ground_slam
                cast-seconds: 1.0
                damage-percent: 50.0
                radius: 4.0
                range: 24
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, target, slam));
        server.getScheduler().performTicks(5);
        target.disconnect();
        server.getScheduler().performTicks(20);

        assertTrue(hits.isEmpty(), "対象が退出したのに着弾している");
        assertTrue(budget.active(target.getUniqueId()).isEmpty(), "退出後も予告予算が握られたまま");
    }

    @Test
    @DisplayName("詠唱中に術者が死ぬと不発になる")
    void casterDyingDuringCastMisfires(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir, 0.0);
        TelegraphBudget budget = new TelegraphBudget();
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), budget);
        List<Boolean> hits = new ArrayList<>();
        executor.setHitObserver((player, telegraphed) -> hits.add(telegraphed));

        Location loc = new Location(world, 400, 64, 400);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc);

        MobAbility slam = ability("dying_caster_slam", """
                type: ground_slam
                cast-seconds: 1.0
                damage-percent: 50.0
                radius: 4.0
                range: 24
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, target, slam));
        server.getScheduler().performTicks(5);
        mob.setHealth(0.0);
        server.getScheduler().performTicks(20);

        assertTrue(hits.isEmpty(), "術者が死んだのに着弾している");
        assertTrue(budget.active(target.getUniqueId()).isEmpty(), "術者死亡後も予告予算が握られたまま");
    }

    @Test
    @DisplayName("予告予算が埋まっていると詠唱を開始できない(false・クールダウン消費なし)")
    void budgetExhaustionRefusesTheCast(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir, 0.0);
        TelegraphBudget budget = new TelegraphBudget();
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), budget);

        Location loc = new Location(world, 500, 64, 500);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc);

        // TOTAL_LIMIT=2 を先に埋める。
        budget.tryReserve(target.getUniqueId(), java.util.UUID.randomUUID(), "other-1", false, System.currentTimeMillis() + 10_000_000L);
        budget.tryReserve(target.getUniqueId(), java.util.UUID.randomUUID(), "other-2", false, System.currentTimeMillis() + 10_000_000L);

        MobAbility slam = ability("blocked_slam", """
                type: ground_slam
                cast-seconds: 1.0
                damage-percent: 50.0
                radius: 4.0
                range: 24
                cooldown-seconds: 0
                """);

        assertFalse(executor.execute(mob, target, slam), "予算が埋まっているのに詠唱が開始できた");
    }

    // ------------------------------------------------------------------
    // 機構1: 円判定(箱ではなく円)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("半径4の即時GROUND_SLAM: 軸方向3.9は当たり、対角3.5,3.5(距離約4.95)は外れる")
    void circularHitDetectionExcludesTheOldBoxDiagonal(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir, 0.0);
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), new TelegraphBudget());
        List<Player> hitPlayers = new ArrayList<>();
        executor.setHitObserver((player, telegraphed) -> hitPlayers.add(player));

        Location center = new Location(world, 600, 64, 600);
        Zombie mob = world.spawn(center, Zombie.class);
        PlayerMock axisVictim = server.addPlayer();
        axisVictim.teleport(center.clone().add(3.9, 0, 0));
        PlayerMock diagonalVictim = server.addPlayer();
        diagonalVictim.teleport(center.clone().add(3.5, 0, 3.5));

        MobAbility slam = ability("circle_slam", """
                type: ground_slam
                damage-percent: 50.0
                radius: 4.0
                range: 24
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, axisVictim, slam));
        assertTrue(hitPlayers.contains(axisVictim), "軸方向3.9(半径4以内)が外れている");
        assertFalse(hitPlayers.contains(diagonalVictim), "対角3.5,3.5(距離約4.95、半径4超)が箱判定のまま当たっている");
    }

    // ------------------------------------------------------------------
    // 追加指示2: 型別のLOS再確認
    // ------------------------------------------------------------------

    @Test
    @DisplayName("BEAMは詠唱終了時に視線が通っていないと不発になる")
    void beamMisfiresWhenLineOfSightIsLostAtCastEnd(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir, 0.0);
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), new TelegraphBudget());
        executor.setLineOfSightCheck((m, t) -> false);
        List<Boolean> hits = new ArrayList<>();
        executor.setHitObserver((player, telegraphed) -> hits.add(telegraphed));

        Location loc = new Location(world, 700, 64, 700);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc.clone().add(3, 0, 0));

        MobAbility beam = ability("los_beam", """
                type: beam
                cast-seconds: 0.5
                damage-percent: 50.0
                count: 5
                radius: 1.0
                range: 24
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, target, beam));
        server.getScheduler().performTicks(15);

        assertTrue(hits.isEmpty(), "視線が通っていないBEAMが着弾している");
    }

    @Test
    @DisplayName("DELAYED_ZONEは対象の視線が通っていなくても着弾する(印は固定済み)")
    void delayedZoneResolvesEvenWithoutLineOfSight(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir, 0.0);
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), new TelegraphBudget());
        executor.setLineOfSightCheck((m, t) -> false);
        List<Boolean> hits = new ArrayList<>();
        executor.setHitObserver((player, telegraphed) -> hits.add(telegraphed));

        Location loc = new Location(world, 800, 64, 800);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc);

        MobAbility zone = ability("los_zone", """
                type: delayed_zone
                duration-seconds: 0.5
                damage-percent: 50.0
                radius: 4.0
                range: 24
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, target, zone));
        server.getScheduler().performTicks(zone.delayTicks() + 2);

        assertEquals(List.of(true), hits, "DELAYED_ZONEはLOSを見ずに着弾するはず");
    }

    // ------------------------------------------------------------------
    // 追加指示5: 行動語の網羅
    // ------------------------------------------------------------------

    @Test
    @DisplayName("responseWordOfはType.values()全部に語を持つ")
    void responseWordCoversEveryAbilityType() {
        for (MobAbility.Type type : MobAbility.Type.values()) {
            String word = MobAbilityExecutor.responseWordOf(type);
            assertNotNull(word, "type=" + type);
            assertFalse(word.isBlank(), "type=" + type);
        }
    }

    // ------------------------------------------------------------------
    // 機構4: アクションバー調停との配線
    // ------------------------------------------------------------------

    @Test
    @DisplayName("詠唱中は予告が出て、完了後はSKILL_EXP表示が復帰する")
    void telegraphUpdatesDuringCastAndSkillExpResumesAfter(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir, 0.0);
        List<Component> sent = new ArrayList<>();
        ActionBarRouter router = new ActionBarRouter(System::currentTimeMillis, (p, c) -> sent.add(c));
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                router, new TelegraphBudget());

        Location loc = new Location(world, 900, 64, 900);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc);

        MobAbility slam = ability("actionbar_slam", """
                type: ground_slam
                cast-seconds: 1.0
                damage-percent: 50.0
                radius: 4.0
                range: 24
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, target, slam));
        server.getScheduler().performTicks(5); // 最初の予告更新(5tickごと)
        assertFalse(sent.isEmpty(), "詠唱中に予告のアクションバーが1度も送られていない");

        server.getScheduler().performTicks(20); // 詠唱完了まで進める
        sent.clear();

        boolean resumed = router.send(target, ActionBarRouter.Priority.SKILL_EXP, Component.text("+1 EXP"));
        assertTrue(resumed, "詠唱終了後もSKILL_EXP表示が予告に阻まれている");
    }

    // ------------------------------------------------------------------
    // 複数人技の被害者ごとの視線（設計正本「複数人技の視線」）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("VORTEX_PULLは視線が通らない被害者を巻き込まない(壁の向こうの味方を引かない)")
    void vortexPullSkipsVictimsWithoutLineOfSight(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir, 0.0);
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), new TelegraphBudget());

        Location center = new Location(world, 1500, 64, 1500);
        Zombie mob = world.spawn(center, Zombie.class);
        PlayerMock visible = server.addPlayer();
        visible.teleport(center.clone().add(3, 0, 0));
        PlayerMock hidden = server.addPlayer();
        hidden.teleport(center.clone().add(-3, 0, 0));
        executor.setLineOfSightCheck((m, p) -> !p.equals(hidden));
        List<Player> hitPlayers = new ArrayList<>();
        executor.setHitObserver((player, telegraphed) -> hitPlayers.add(player));

        MobAbility pull = ability("vortex_test", """
                type: vortex_pull
                damage-percent: 50.0
                radius: 16
                range: 20
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, visible, pull));
        assertTrue(hitPlayers.contains(visible), "視線が通る被害者が外れている");
        assertFalse(hitPlayers.contains(hidden), "視線が通らない被害者(壁の向こう)まで引き寄せてしまった");
    }

    @Test
    @DisplayName("REPULSEも被害者ごとの視線を見る")
    void repulseSkipsVictimsWithoutLineOfSight(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir, 0.0);
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), new TelegraphBudget());

        Location center = new Location(world, 1600, 64, 1600);
        Zombie mob = world.spawn(center, Zombie.class);
        PlayerMock visible = server.addPlayer();
        visible.teleport(center.clone().add(3, 0, 0));
        PlayerMock hidden = server.addPlayer();
        hidden.teleport(center.clone().add(-3, 0, 0));
        executor.setLineOfSightCheck((m, p) -> !p.equals(hidden));
        List<Player> hitPlayers = new ArrayList<>();
        executor.setHitObserver((player, telegraphed) -> hitPlayers.add(player));

        MobAbility repulse = ability("repulse_test", """
                type: repulse
                damage-percent: 50.0
                radius: 6
                range: 20
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, visible, repulse));
        assertTrue(hitPlayers.contains(visible));
        assertFalse(hitPlayers.contains(hidden), "視線が通らない被害者まで吹き飛ばしてしまった");
    }

    // ------------------------------------------------------------------
    // 機構10: 致命予約の原子化
    // ------------------------------------------------------------------

    @Test
    @DisplayName("致命かつ原子化ONで脅威圏内2人のうち1人の枠が埋まっていればexecuteはfalseになり、取った予約は残らない")
    void atomicLethalReservationRollsBackWhenAnyoneCannotReserve(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir, 0.0);
        TelegraphBudget budget = new TelegraphBudget();
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), budget, () -> true, () -> ActionBarRouter.BarStyle.BLOCK);

        Location center = new Location(world, 1700, 64, 1700);
        Zombie mob = world.spawn(center, Zombie.class);
        PlayerMock target = server.addPlayer();
        target.teleport(center);
        PlayerMock bystander = server.addPlayer();
        bystander.teleport(center.clone().add(3, 0, 0)); // 脅威圏内(GROUND_SLAM radius 6)

        // bystander の予告予算を先に埋めておく(TOTAL_LIMIT=2)。
        budget.tryReserve(bystander.getUniqueId(), java.util.UUID.randomUUID(), "other-1", false,
                System.currentTimeMillis() + 10_000_000L);
        budget.tryReserve(bystander.getUniqueId(), java.util.UUID.randomUUID(), "other-2", false,
                System.currentTimeMillis() + 10_000_000L);

        MobAbility lethalSlam = ability("atomic_lethal_slam", """
                type: ground_slam
                cast-seconds: 1.0
                lethal: true
                damage-percent: 50.0
                radius: 6.0
                range: 24
                cooldown-seconds: 0
                """);

        boolean started = executor.execute(mob, target, lethalSlam);

        assertFalse(started, "bystanderの枠が埋まっているのに致命原子化がexecuteをtrueにした");
        assertTrue(budget.active(target.getUniqueId()).isEmpty(),
                "原子化で失敗したのに主対象の予約が残っている(releaseされていない)");
    }

    @Test
    @DisplayName("致命でも telegraph-lethal-atomic=false ならベスト・エフォートで開始できる")
    void nonAtomicLethalReservationIsBestEffort(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir, 0.0);
        TelegraphBudget budget = new TelegraphBudget();
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), budget, () -> false, () -> ActionBarRouter.BarStyle.BLOCK);

        Location center = new Location(world, 1800, 64, 1800);
        Zombie mob = world.spawn(center, Zombie.class);
        PlayerMock target = server.addPlayer();
        target.teleport(center);
        PlayerMock bystander = server.addPlayer();
        bystander.teleport(center.clone().add(3, 0, 0));

        budget.tryReserve(bystander.getUniqueId(), java.util.UUID.randomUUID(), "other-1", false,
                System.currentTimeMillis() + 10_000_000L);
        budget.tryReserve(bystander.getUniqueId(), java.util.UUID.randomUUID(), "other-2", false,
                System.currentTimeMillis() + 10_000_000L);

        MobAbility lethalSlam = ability("nonatomic_lethal_slam", """
                type: ground_slam
                cast-seconds: 1.0
                lethal: true
                damage-percent: 50.0
                radius: 6.0
                range: 24
                cooldown-seconds: 0
                """);

        boolean started = executor.execute(mob, target, lethalSlam);

        assertTrue(started, "atomic=falseならbystanderの枠が埋まっていても開始できるはず");
    }
}
