package com.trinityforge.stats;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-07-25 attack-speed {@code -1} sentinel タスクの T1 調査結果を固定するテスト:
 * {@code base-stats.yml} の {@code attack-speed} が絶対値方式で「0=バニラのまま」になった今、
 * 素手(TFのいかなる modifier も付かない状態)の {@code Attribute.ATTACK_SPEED} 実効値は
 * バニラ既定の4.0のままである。TF側は素手に対して一切のハードコードを追加していない
 * (追加する必要が無かった、というT1調査の結論を裏付ける)。
 */
class BareHandAttackSpeedProbeTest {
    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void freshPlayerWithNoItemAndNoModifiersHasVanillaFourAttackSpeed() {
        PlayerMock p = server.addPlayer();
        p.registerAttribute(Attribute.ATTACK_SPEED);
        AttributeInstance instance = p.getAttribute(Attribute.ATTACK_SPEED);

        assertEquals(4.0, instance.getValue(), 1e-9,
                "bare hand (no TF modifiers applied) must already read vanilla 4.0 attack speed");
    }
}
