package com.trinityforge.mob;

import com.trinityforge.config.domains.MobOverridesConfig;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * 「そのモブをそもそも対象にしてよいか」の除外規則の回帰テスト。
 *
 * <p>視線と位置の幾何は {@link FocusHitboxTest} が持つ(そちらは MockBukkit を使わない)。
 * ここに残すのは<b>エンティティの素性でしか判定できないもの</b>だけ:
 * <ul>
 *   <li>{@link ArmorStand} は Bukkit の API 上 {@link LivingEntity} なので、素朴な
 *       {@code instanceof LivingEntity} だと装飾のアーマースタンドにまでラベルが浮く(既存の回帰)</li>
 *   <li><b>自分が騎乗しているモブは対象外</b>(2026-08-18 ユーザー報告: ハッピーガスト/馬/
 *       ストライダーに乗るとラベルが視界の真ん中に居座って邪魔)。多段騎乗でも全段を除外する</li>
 * </ul>
 *
 * <p>判定は意図的に private static(唯一の呼び出し元は tick ループ)なのでリフレクションで叩く。
 * {@code World#rayTrace} は MockBukkit 未実装で、呼ぶと<b>失敗ではなく中断(SKIPPED)</b>に化けるため、
 * レイキャストを通る経路はここでは踏まない。
 */
class FocusHpDisplayTest {

    private ServerMock server;
    private WorldMock world;
    private Method isFocusable;
    private Method riddenEntities;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        Plugin plugin = MockBukkit.createMockPlugin();
        // 2026-07-26: 表示名は MobDisplayNames が解決する。ここでは overrides を持たない空の
        // MobOverridesConfig を渡す = 従来どおり customName → EntityType の順にフォールバックする。
        new FocusHpDisplay(plugin, new MobDisplayNames(new MobOverridesConfig()));
        isFocusable = FocusHpDisplay.class.getDeclaredMethod("isFocusable", LivingEntity.class, Set.class);
        isFocusable.setAccessible(true);
        riddenEntities = FocusHpDisplay.class.getDeclaredMethod("riddenEntities", Player.class);
        riddenEntities.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private boolean focusable(LivingEntity entity, Set<UUID> ridden) throws Exception {
        return (boolean) isFocusable.invoke(null, entity, ridden);
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> ridden(Player player) throws Exception {
        return (Set<UUID>) riddenEntities.invoke(null, player);
    }

    @Test
    @DisplayName("アーマースタンドは対象にしない")
    void armorStandIsNotFocusable() throws Exception {
        ArmorStand stand = world.spawn(world.getSpawnLocation(), ArmorStand.class);

        assertFalse(focusable(stand, Set.of()),
                "ArmorStand は LivingEntity だが装飾。レベル/HP を出す意味が無い");
    }

    @Test
    @DisplayName("通常のモブは対象にする(除外が本物の対象まで飲み込んでいないこと)")
    void normalMobIsFocusable() throws Exception {
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);

        assertTrue(focusable(zombie, Set.of()));
    }

    @Test
    @DisplayName("プレイヤーは対象にしない")
    void playerIsNotFocusable() throws Exception {
        Player other = server.addPlayer();

        assertFalse(focusable(other, Set.of()));
    }

    @Test
    @DisplayName("騎乗中のモブは対象にしない(視界の真ん中にラベルが居座る不具合)")
    void riddenMobIsNotFocusable() throws Exception {
        Horse horse = world.spawn(world.getSpawnLocation(), Horse.class);

        assertTrue(focusable(horse, Set.of()),
                "乗っていない馬は通常どおり対象");
        assertFalse(focusable(horse, Set.of(horse.getUniqueId())),
                "自分が乗っている馬のラベルは出してはいけない");
    }

    @Test
    @DisplayName("乗り物を辿って多段騎乗の全段を除外する")
    void vehicleChainIsCollected() throws Exception {
        Horse horse = world.spawn(world.getSpawnLocation(), Horse.class);
        Zombie carrier = world.spawn(world.getSpawnLocation(), Zombie.class);
        Player realPlayer = server.addPlayer();

        Player player = spy(realPlayer);
        Horse riddenHorse = spy(horse);
        doReturn(riddenHorse).when(player).getVehicle();
        doReturn(carrier).when(riddenHorse).getVehicle();

        Set<UUID> ids = ridden(player);

        assertTrue(ids.contains(horse.getUniqueId()), "直接乗っている馬");
        assertTrue(ids.contains(carrier.getUniqueId()), "その馬が乗っている相手も除外対象");
    }

    @Test
    @DisplayName("何にも乗っていなければ除外集合は空")
    void notRidingAnythingYieldsEmptySet() throws Exception {
        Player player = server.addPlayer();

        assertTrue(ridden(player).isEmpty());
    }
}
