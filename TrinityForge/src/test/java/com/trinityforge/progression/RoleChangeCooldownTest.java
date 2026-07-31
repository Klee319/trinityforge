package com.trinityforge.progression;

import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.config.domains.RoleBuffsConfig.CombatRoleSpec;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.listeners.RoleBuffListener;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ロール変更クールダウン({@link RoleChangeService}, 2026-07-31)の契約。
 *
 * <p>クールダウンが無いと、採掘するときだけ鉱夫・釣るときだけ漁師へ切り替えれば全系統に
 * 最大倍率が乗り、補助職の選択そのものが意味を失う。ここで固定したいのは
 * <b>迂回路が無いこと</b>（clear→即再選択でリセットできない）と、
 * <b>戦闘職と補助職が互いを塞がないこと</b>（GUI で片方を選んだ瞬間に他方が押せなくなる、
 * という形の手戻りを作らない）。
 */
class RoleChangeCooldownTest {

    private static final long COOLDOWN_MILLIS = 120L * 60_000L;

    private ServerMock server;
    private AtomicLong now;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        now = new AtomicLong(1_000_000L);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private RoleChangeService service(boolean firstChoiceFree) {
        return service(COOLDOWN_MILLIS, firstChoiceFree);
    }

    private RoleChangeService service(long cooldownMillis, boolean firstChoiceFree) {
        CombatRoleSpec tank = new CombatRoleSpec("tank", "守衛", Map.of(), Map.of(), 1.5, "SHIELD", List.of());
        CombatRoleSpec mage = new CombatRoleSpec("mage", "魔術師", Map.of(), Map.of(), 1.0, "BLAZE_ROD", List.of());
        SupportRoleSpec miner = new SupportRoleSpec("miner", "鉱夫", "MINING", 1.15, null, "IRON_PICKAXE", List.of());
        SupportRoleSpec fisher = new SupportRoleSpec("fisher", "漁師", "FISHING", 1.35, null, "FISHING_ROD", List.of());

        RoleBuffsConfig config = mock(RoleBuffsConfig.class);
        when(config.allowRoleCommand()).thenReturn(true);
        when(config.combatRoles()).thenReturn(Map.of("tank", tank, "mage", mage));
        when(config.supportRoles()).thenReturn(Map.of("miner", miner, "fisher", fisher));
        when(config.roleChangeCooldownMillis()).thenReturn(cooldownMillis);
        when(config.firstChoiceFree()).thenReturn(firstChoiceFree);

        RoleBuffListener listener = mock(RoleBuffListener.class);
        return new RoleChangeService(config, listener, now::get);
    }

    @Test
    @DisplayName("初回無料: 空の枠を埋めた直後は待ち時間ゼロで選び直せる")
    void firstChoiceDoesNotStartTheCooldown() {
        RoleChangeService service = service(true);
        Player player = server.addPlayer();

        assertTrue(service.setSupport(player, "miner"));
        assertEquals(0L, service.supportCooldownRemainingMillis(player));
        assertTrue(service.denyReasonForSupport(player).isEmpty());

        // 2回目(＝空でない枠の変更)からは刻む。
        assertTrue(service.setSupport(player, "fisher"));
        assertEquals(COOLDOWN_MILLIS, service.supportCooldownRemainingMillis(player));
        assertTrue(service.denyReasonForSupport(player).isPresent());
    }

    @Test
    @DisplayName("初回無料を切ると1回目から刻む")
    void firstChoiceStampsWhenTheOptionIsOff() {
        RoleChangeService service = service(false);
        Player player = server.addPlayer();

        assertTrue(service.setSupport(player, "miner"));
        assertEquals(COOLDOWN_MILLIS, service.supportCooldownRemainingMillis(player));
    }

    @Test
    @DisplayName("待ち時間が過ぎれば変更できる")
    void cooldownExpires() {
        RoleChangeService service = service(false);
        Player player = server.addPlayer();
        service.setSupport(player, "miner");

        now.addAndGet(COOLDOWN_MILLIS - 1L);
        assertTrue(service.denyReasonForSupport(player).isPresent());

        now.addAndGet(1L);
        assertEquals(0L, service.supportCooldownRemainingMillis(player));
        assertTrue(service.denyReasonForSupport(player).isEmpty());
    }

    @Test
    @DisplayName("戦闘職と補助職は互いの待ち時間を塞がない")
    void slotsHaveIndependentCooldowns() {
        RoleChangeService service = service(false);
        Player player = server.addPlayer();

        service.setCombat(player, "tank");
        assertTrue(service.denyReasonForCombat(player).isPresent());
        // 戦闘職を選んだ直後でも補助職は選べる(GUI で両方選ぶ動線を塞がない)。
        assertTrue(service.denyReasonForSupport(player).isEmpty());
        assertTrue(service.setSupport(player, "miner"));
        assertEquals("miner", PlayerData.of(player).roleSupport().orElseThrow());
    }

    @Test
    @DisplayName("同じロールを選び直しても待ち時間は始まらない")
    void reselectingTheSameRoleIsANoOp() {
        RoleChangeService service = service(false);
        Player player = server.addPlayer();
        service.setSupport(player, "miner");
        now.addAndGet(COOLDOWN_MILLIS);

        assertTrue(service.setSupport(player, "miner"));
        assertEquals(0L, service.supportCooldownRemainingMillis(player),
                "押し間違いで待ち時間が始まると取り返しがつかない");
    }

    @Test
    @DisplayName("clear は迂回路にならない(解除しても待ち時間が残る)")
    void clearDoesNotResetTheCooldown() {
        RoleChangeService service = service(true);
        Player player = server.addPlayer();
        service.setSupport(player, "miner");
        service.setSupport(player, "fisher");
        assertTrue(service.denyReasonForSupport(player).isPresent());

        service.clear(player);

        // 枠は空になるが、初回無料の判定は「刻印時刻」なので待ち時間はリセットされない。
        assertTrue(PlayerData.of(player).roleSupport().isEmpty());
        assertEquals(COOLDOWN_MILLIS, service.supportCooldownRemainingMillis(player));
        assertTrue(service.denyReasonForSupport(player).isPresent());
        assertTrue(service.denyReasonForCombat(player).isPresent());
    }

    @Test
    @DisplayName("cooldown-minutes: 0 なら従来どおり無制限に変更できる")
    void zeroCooldownKeepsTheOldBehaviour() {
        RoleChangeService service = service(0L, false);
        Player player = server.addPlayer();

        assertTrue(service.setSupport(player, "miner"));
        assertTrue(service.setSupport(player, "fisher"));
        assertTrue(service.setSupport(player, "miner"));
        assertEquals(0L, service.supportCooldownRemainingMillis(player));
        assertTrue(service.denyReasonForSupport(player).isEmpty());
    }

    @Test
    @DisplayName("時計が巻き戻っても締め出されない")
    void clockGoingBackwardsDoesNotLockThePlayerOut() {
        RoleChangeService service = service(false);
        Player player = server.addPlayer();
        service.setSupport(player, "miner");

        // サーバの時刻修正などで now が過去へ動いたケース。待たせると復旧手段が無い。
        now.addAndGet(-COOLDOWN_MILLIS * 10L);
        assertEquals(0L, service.supportCooldownRemainingMillis(player));
        assertTrue(service.denyReasonForSupport(player).isEmpty());
    }

    @Test
    @DisplayName("未知のIDは false を返し、待ち時間も刻まない")
    void unknownRoleIdIsRejectedWithoutStamping() {
        RoleChangeService service = service(false);
        Player player = server.addPlayer();

        assertFalse(service.setSupport(player, "astronaut"));
        assertFalse(service.setCombat(player, "astronaut"));
        assertEquals(0L, service.supportCooldownRemainingMillis(player));
        assertEquals(0L, service.combatCooldownRemainingMillis(player));
    }

    @Test
    @DisplayName("残り時間の表記は 時間/分/秒 で切り替わる")
    void remainingTimeIsFormattedForChat() {
        assertEquals("2時間", RoleChangeService.formatRemaining(120L * 60_000L));
        assertEquals("1時間23分", RoleChangeService.formatRemaining((83L * 60L) * 1000L));
        assertEquals("45分", RoleChangeService.formatRemaining(45L * 60_000L));
        assertEquals("30秒", RoleChangeService.formatRemaining(30_000L));
        // 1秒未満でも「0秒」とは言わない(残っているのに0と出ると壊れて見える)。
        assertEquals("1秒", RoleChangeService.formatRemaining(1L));
    }
}
