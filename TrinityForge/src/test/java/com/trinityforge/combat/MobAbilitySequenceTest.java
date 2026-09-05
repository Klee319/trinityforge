package com.trinityforge.combat;

import com.trinityforge.config.domains.MobAbilitiesConfig;
import com.trinityforge.config.domains.MobOverridesConfig;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.ZombieMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 敵の技の「行動順の指定」(2026-09-04、設計正本の境界表8 + UXレビュー #12「技選択のリズム」)の回帰。
 *
 * <p><b>ability-sequence が非空のモブは chance を無視して撃つ</b>(振り付けが chance で崩れないように)、
 * <b>撃てない周は次の技へ飛ばさず空振りにする</b>(振り付けの周期を守るため)、
 * <b>ability-interval-seconds はモブ単位の GLOBAL_GAP を上書きする</b>ことをそれぞれ固定する。
 * ランダム抽選側の選択リズム(直近の発動語が偏ると重みが下がる)は {@link #pickWeighted} /
 * {@link MobAbilityTask#weightsFor} の純関数テストで別途固定する。
 */
class MobAbilitySequenceTest {

    private ServerMock server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    private static Map<String, MobAbility> parseAbilities(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return MobAbilitiesConfig
                .parse(cfg.getConfigurationSection("abilities"), Logger.getLogger("MobAbilitySequenceTest"))
                .abilities();
    }

    private MobAbilitiesConfig abilitiesConfigOf(Map<String, MobAbility> abilities, long globalCooldownMillis) {
        MobAbilitiesConfig config = mock(MobAbilitiesConfig.class);
        when(config.requireTarget()).thenReturn(false);
        when(config.requireLineOfSight()).thenReturn(false);
        when(config.globalCooldownMillis()).thenReturn(globalCooldownMillis);
        for (Map.Entry<String, MobAbility> entry : abilities.entrySet()) {
            when(config.ability(entry.getKey())).thenReturn(entry.getValue());
        }
        return config;
    }

    private MobOverridesConfig overridesConfigOf(List<String> sequence, OptionalDouble intervalSeconds) {
        MobOverridesConfig overrides = mock(MobOverridesConfig.class);
        when(overrides.abilitiesFor(anyString(), anyString())).thenReturn(List.of());
        when(overrides.abilitySequenceFor(anyString(), anyString())).thenReturn(sequence);
        when(overrides.abilityIntervalSecondsFor(anyString(), anyString())).thenReturn(intervalSeconds);
        return overrides;
    }

    private ZombieMock spawnZombie(WorldMock world) {
        ZombieMock zombie = new ZombieMock(server, UUID.randomUUID());
        zombie.setLocation(new Location(world, 3, 64, 0));
        return zombie;
    }

    // ------------------------------------------------------------------
    // ①②③: 順番指定の発動経路
    // ------------------------------------------------------------------

    @Test
    @DisplayName("① ability-sequence [a,b] は 1回目a・2回目b・3回目a の順に撃つ(カーソルが1周する)")
    void sequenceFiresInOrderAndWrapsAround() throws Exception {
        server = MockBukkit.mock();
        WorldMock world = server.addSimpleWorld("sequence_world");
        PlayerMock player = server.addPlayer("Victim");
        player.setLocation(new Location(world, 0, 64, 0));
        ZombieMock zombie = spawnZombie(world);

        Map<String, MobAbility> abilities = parseAbilities("""
                abilities:
                  a:
                    type: ground_slam
                    chance: 1.0
                    range: 24
                    cooldown-seconds: 0
                  b:
                    type: beam
                    chance: 1.0
                    range: 24
                    cooldown-seconds: 0
                """);
        MobAbilitiesConfig abilitiesConfig = abilitiesConfigOf(abilities, 0L);
        MobOverridesConfig overrides = overridesConfigOf(List.of("a", "b"), OptionalDouble.empty());

        MobAbilityExecutor executor = mock(MobAbilityExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(true);

        // 時計を固定(0)のままにすると、technique-level cooldown-seconds が MobAbility 側で
        // 最低 0.5秒(500ms)へ丸められる(record の compact constructor)ため、a を一度armすると
        // 二度と ready() に戻らず false になり続ける。周回を検証するには時計を進める必要がある。
        long[] now = {0L};
        MobAbilityTask task = new MobAbilityTask(MockBukkit.createMockPlugin("TrinityForge"), abilitiesConfig,
                overrides, executor, new MobAbilityCooldowns(() -> now[0]), new Random(1));

        assertTrue(task.tryFire(zombie, player));
        verify(executor).execute(zombie, player, abilities.get("a"));

        now[0] += 1_000L;
        assertTrue(task.tryFire(zombie, player));
        verify(executor).execute(zombie, player, abilities.get("b"));

        now[0] += 1_000L;
        assertTrue(task.tryFire(zombie, player));
        verify(executor, org.mockito.Mockito.times(2)).execute(zombie, player, abilities.get("a"));
    }

    @Test
    @DisplayName("② 今の番の技がクールダウン中なら空振りになり、別の技へは飛ばさない")
    void sequenceMisfiresWithoutSkippingAheadWhenCurrentAbilityIsOnCooldown() throws Exception {
        server = MockBukkit.mock();
        WorldMock world = server.addSimpleWorld("sequence_gate_world");
        PlayerMock player = server.addPlayer("Victim2");
        player.setLocation(new Location(world, 0, 64, 0));
        ZombieMock zombie = spawnZombie(world);

        Map<String, MobAbility> abilities = parseAbilities("""
                abilities:
                  a:
                    type: ground_slam
                    chance: 1.0
                    range: 24
                    cooldown-seconds: 0
                  b:
                    type: beam
                    chance: 1.0
                    range: 24
                    cooldown-seconds: 0
                """);
        MobAbilitiesConfig abilitiesConfig = abilitiesConfigOf(abilities, 0L);
        MobOverridesConfig overrides = overridesConfigOf(List.of("a", "b"), OptionalDouble.empty());

        MobAbilityExecutor executor = mock(MobAbilityExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(true);

        MobAbilityCooldowns cooldowns = new MobAbilityCooldowns(() -> 0L);
        MobAbilityTask task = new MobAbilityTask(MockBukkit.createMockPlugin("TrinityForge"), abilitiesConfig,
                overrides, executor, cooldowns, new Random(1));

        // 1回目: a が撃たれてカーソルが b へ進む。
        assertTrue(task.tryFire(zombie, player));
        verify(executor).execute(zombie, player, abilities.get("a"));

        // b を手動でクールダウン中にする(振り付けの「今の番の技が撃てない」状況を再現)。
        cooldowns.arm(zombie.getUniqueId(), "b", 1_000_000L);

        assertFalse(task.tryFire(zombie, player),
                "今の番(b)がクールダウン中なのに発動している");
        verify(executor, never()).execute(zombie, player, abilities.get("b"));
        // a へ飛び直していないこと(2回目のaが呼ばれていない = 呼び出し回数は1のまま)。
        verify(executor, org.mockito.Mockito.times(1)).execute(zombie, player, abilities.get("a"));

        // もう一度呼んでも同じ(b が明けるまでカーソルは動かない)。
        assertFalse(task.tryFire(zombie, player));
        verify(executor, never()).execute(zombie, player, abilities.get("b"));
    }

    @Test
    @DisplayName("③ chance: 0.0 の技でも ability-sequence に載っていれば確定で撃つ")
    void sequenceIgnoresChance() throws Exception {
        server = MockBukkit.mock();
        WorldMock world = server.addSimpleWorld("sequence_chance_world");
        PlayerMock player = server.addPlayer("Victim3");
        player.setLocation(new Location(world, 0, 64, 0));
        ZombieMock zombie = spawnZombie(world);

        Map<String, MobAbility> abilities = parseAbilities("""
                abilities:
                  c:
                    type: ground_slam
                    chance: 0.0
                    range: 24
                    cooldown-seconds: 0
                """);
        MobAbilitiesConfig abilitiesConfig = abilitiesConfigOf(abilities, 0L);
        MobOverridesConfig overrides = overridesConfigOf(List.of("c"), OptionalDouble.empty());

        MobAbilityExecutor executor = mock(MobAbilityExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(true);

        // chance=0.0 なら通常の抽選経路は絶対に撃たない random を使っても sequence 経路なら関係ない、
        // ことを固定するため random.nextDouble() が常に 0.999... を返すシード(発火しない側)を使う。
        MobAbilityTask task = new MobAbilityTask(MockBukkit.createMockPlugin("TrinityForge"), abilitiesConfig,
                overrides, executor, new MobAbilityCooldowns(() -> 0L), new Random(1));

        assertTrue(task.tryFire(zombie, player), "chance 0.0 でも sequence 経由なら撃つはず");
        verify(executor).execute(zombie, player, abilities.get("c"));
    }

    // ------------------------------------------------------------------
    // ④: モブ単位の間合い上書き
    // ------------------------------------------------------------------

    @Test
    @DisplayName("④ ability-interval-seconds: 3 のモブは GLOBAL_GAP が3秒で明ける")
    void abilityIntervalSecondsOverridesTheGlobalGap() throws Exception {
        server = MockBukkit.mock();
        WorldMock world = server.addSimpleWorld("interval_world");
        PlayerMock player = server.addPlayer("Victim4");
        player.setLocation(new Location(world, 0, 64, 0));
        ZombieMock zombie = spawnZombie(world);

        Map<String, MobAbility> abilities = parseAbilities("""
                abilities:
                  a:
                    type: ground_slam
                    chance: 1.0
                    range: 24
                    cooldown-seconds: 0
                """);
        // config 側の既定は 0(＝上書きが無ければ即座に明ける)にしておき、
        // 3秒縛りが overrides 側の値からしか来ていないことを区別できるようにする。
        MobAbilitiesConfig abilitiesConfig = abilitiesConfigOf(abilities, 0L);
        MobOverridesConfig overrides = overridesConfigOf(List.of("a"), OptionalDouble.of(3.0));

        MobAbilityExecutor executor = mock(MobAbilityExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(true);

        long[] now = {0L};
        MobAbilityCooldowns cooldowns = new MobAbilityCooldowns(() -> now[0]);
        MobAbilityTask task = new MobAbilityTask(MockBukkit.createMockPlugin("TrinityForge"), abilitiesConfig,
                overrides, executor, cooldowns, new Random(1));

        assertTrue(task.tryFire(zombie, player), "1回目は無条件で撃てるはず");

        now[0] += 2_999L;
        assertFalse(task.tryFire(zombie, player), "3秒未満なのに GLOBAL_GAP が明けている");

        now[0] += 1L; // ちょうど3000msに到達
        assertTrue(task.tryFire(zombie, player), "3秒経過しても GLOBAL_GAP が明いていない");
    }

    // ------------------------------------------------------------------
    // ⑤: pickWeighted の純関数テスト
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑤ pickWeighted: 均等な重みなら合計に対する比率で境界が決まる")
    void pickWeightedSplitsProportionallyToWeight() {
        // weights=[1,1] (合計2): target=roll*2。roll<0.5 → target<1 → index0。roll>=0.5 → index1。
        assertEquals(0, MobAbilityTask.pickWeighted(List.of(1.0, 1.0), 0.4));
        assertEquals(1, MobAbilityTask.pickWeighted(List.of(1.0, 1.0), 0.6));
    }

    @Test
    @DisplayName("⑤ pickWeighted: 重い候補ほど選ばれやすい区間が広い")
    void pickWeightedFavorsHeavierWeight() {
        // weights=[3,1] (合計4): 境界は 3/4=0.75。
        assertEquals(0, MobAbilityTask.pickWeighted(List.of(3.0, 1.0), 0.5));
        assertEquals(1, MobAbilityTask.pickWeighted(List.of(3.0, 1.0), 0.9));
    }

    @Test
    @DisplayName("⑤ pickWeighted: 候補が1つなら roll に関係なく index0")
    void pickWeightedWithSingleCandidateAlwaysReturnsZero() {
        assertEquals(0, MobAbilityTask.pickWeighted(List.of(5.0), 0.0));
        assertEquals(0, MobAbilityTask.pickWeighted(List.of(5.0), 0.999));
    }

    @Test
    @DisplayName("⑤ pickWeighted: 重みの合計が0以下ならindex0にフォールバックする(例外を投げない)")
    void pickWeightedFallsBackToZeroWhenTotalIsNotPositive() {
        assertEquals(0, MobAbilityTask.pickWeighted(List.of(0.0, 0.0), 0.5));
    }

    // ------------------------------------------------------------------
    // ⑥: weightsFor の純関数テスト(選択リズム)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑥ 直近に同じ行動語が続いた候補ほど重みが下がる")
    void weightsForLowersWeightForRecentlyRepeatedActionWords() throws Exception {
        Map<String, MobAbility> abilities = parseAbilities("""
                abilities:
                  slam:
                    type: ground_slam
                    range: 24
                  laser:
                    type: beam
                    range: 24
                """);
        MobAbility slam = abilities.get("slam"); // responseWordOf(GROUND_SLAM) == "離れろ"
        MobAbility laser = abilities.get("laser"); // responseWordOf(BEAM) == "横へ"

        // 直近3回のうち「離れろ」が2回、「横へ」が1回出たと仮定する。
        Deque<String> recent = new ArrayDeque<>(List.of(
                MobAbilityExecutor.responseWordOf(MobAbility.Type.GROUND_SLAM),
                MobAbilityExecutor.responseWordOf(MobAbility.Type.GROUND_SLAM),
                MobAbilityExecutor.responseWordOf(MobAbility.Type.BEAM)));

        List<Double> weights = MobAbilityTask.weightsFor(List.of(slam, laser), recent);

        assertEquals(1.0 / 3.0, weights.get(0), 1.0e-9, "「離れろ」が2回続いたのに重みが 1/(1+2) になっていない");
        assertEquals(1.0 / 2.0, weights.get(1), 1.0e-9, "「横へ」が1回出たのに重みが 1/(1+1) になっていない");
    }

    @Test
    @DisplayName("⑥ 履歴が無い(null)候補は全て重み1になる")
    void weightsForWithNoHistoryGivesEqualWeight() throws Exception {
        Map<String, MobAbility> abilities = parseAbilities("""
                abilities:
                  slam:
                    type: ground_slam
                    range: 24
                  laser:
                    type: beam
                    range: 24
                """);
        List<Double> weights = MobAbilityTask.weightsFor(
                List.of(abilities.get("slam"), abilities.get("laser")), null);
        assertEquals(1.0, weights.get(0), 1.0e-9);
        assertEquals(1.0, weights.get(1), 1.0e-9);
    }
}
