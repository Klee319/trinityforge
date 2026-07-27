package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-07-28 実サーバの {@link java.util.ConcurrentModificationException} の回帰ガード。
 *
 * <p>{@code PlayerStatAggregator#tickCache} は同期化されていない {@link java.util.HashMap} なのに、
 * {@code NativeExperienceDispatcher#drain}(<b>非同期</b>スケジューラタスク)が
 * {@code NativeProgressionService} → {@code TrinityForge} の {@code skill_exp_bonus} サプライヤ
 * → {@code aggregate(player)} と降りてきて同じマップを触っていた。メインスレッド側が
 * {@code PerkAttributeApplier#reconcileAllOnline} でこのマップを回している最中に非同期側が put すると
 * CME になり、全員ぶんの属性再計算ループがその場で中断していた(＝以降のプレイヤーに属性が当たらない)。
 *
 * <p>現在は非同期呼び出しにキャッシュを触らせない。ここではその2点を固定する:
 * ①非同期呼び出しがキャッシュを一切汚さない(＝メインスレッドのキャッシュ済み結果を返さない)、
 * ②メインと非同期を並行に叩いても例外が飛ばない。
 */
class PlayerStatAggregatorAsyncSafetyTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerStatAggregator aggregator(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-power: 10.0 }
                """);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, java.util.List::of);
        return new PlayerStatAggregator(cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
    }

    @Test
    @DisplayName("非同期スレッドからの集計は tick キャッシュを使わない(共有マップを触らない)")
    void asyncCallBypassesTheTickCache(@TempDir File dir) throws Exception {
        PlayerStatAggregator aggregator = aggregator(dir);
        Player player = server.addPlayer();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        player.getInventory().setItemInMainHand(sword);

        // 同一tickでメインスレッドがキャッシュを温めておく。
        PlayerCombatAggregate onMain = aggregator.aggregate(player, sword);

        AtomicReference<PlayerCombatAggregate> offThread = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                offThread.set(aggregator.aggregate(player, sword));
            } catch (Throwable ex) {
                failure.set(ex);
            }
        }, "async-exp-drain-lookalike");
        worker.start();
        worker.join(10_000);

        assertNull(failure.get(), "非同期呼び出しで例外が飛んではいけない: " + failure.get());
        assertNotSame(onMain, offThread.get(),
                "非同期呼び出しはキャッシュ済みインスタンスを返してはいけない(共有マップを触っていない証拠)");
        assertEquals(onMain.mainhand(), offThread.get().mainhand(),
                "キャッシュを使わないだけで、解決される値そのものは同じでなければならない");
    }

    @Test
    @DisplayName("メインと非同期から並行に叩いても ConcurrentModificationException が飛ばない")
    void concurrentMainAndAsyncCallsDoNotThrow(@TempDir File dir) throws Exception {
        PlayerStatAggregator aggregator = aggregator(dir);
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        // キーを散らして HashMap が実際にリハッシュ(構造変更)するようにする — 修正前はこれで落ちた。
        Player[] players = new Player[16];
        for (int i = 0; i < players.length; i++) {
            players[i] = server.addPlayer();
            players[i].getInventory().setItemInMainHand(sword);
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    aggregator.aggregate(players[i % players.length], sword);
                }
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            } finally {
                done.countDown();
            }
        }, "async-exp-drain-lookalike");
        worker.start();

        start.countDown();
        try {
            for (int i = 0; i < 2_000; i++) {
                aggregator.aggregate(players[i % players.length], sword);
            }
        } catch (Throwable ex) {
            failure.compareAndSet(null, ex);
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "非同期側が終わらない");

        assertNull(failure.get(), "並行呼び出しで例外が飛んではいけない: " + failure.get());
    }
}
