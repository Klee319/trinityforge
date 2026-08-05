package com.trinityforge.progression;

import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.config.domains.RoleBuffsConfig.CombatRoleSpec;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.listeners.RoleBuffListener;
import org.bukkit.Location;
import org.bukkit.entity.Cow;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 交戦中ガード({@code role-change.nearby-enemy-radius})の契約。
 *
 * <p>2026-07-31 まではこのガードが {@code MONSTER_SCAN_RADIUS = 16.0} のハードコードで、しかも
 * 敵対判定に Bukkit の {@code Monster} を使っていた。そのため
 * <b>ネザーではゾンビピグリンが常時居るのでロール変更が事実上永久に不可</b>、逆に
 * <b>エンドラ/ガストとの戦闘中は素通り</b>という、意図と両方向に外れた挙動になっていた。
 *
 * <p>ここで固定したいのは 3 点:
 * <ul>
 *   <li><b>既定(0)ではガードが無効</b> — 敵が隣に居てもロールを変更できる(ユーザー報告の回帰テスト)。</li>
 *   <li>有効にした場合は<b>「自分を狙っている敵」だけ</b>が拒否理由になる(アンビエントで誤爆しない)。</li>
 *   <li>ガードは<b>クールダウンとは別機構</b> — 無効にしても {@code clear} → 即再選択の迂回路は開かない
 *       (そちらは {@link RoleChangeCooldownTest} が固定している)。</li>
 * </ul>
 */
class RoleChangeCombatGateTest {

    private static final double RADIUS = 8.0;

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private RoleChangeService service(double nearbyEnemyRadius) {
        CombatRoleSpec tank = new CombatRoleSpec("tank", "守衛", Map.of(), Map.of(), 1.5, "SHIELD", List.of());
        SupportRoleSpec miner = new SupportRoleSpec("miner", "鉱夫", "MINING", 1.15, null, "IRON_PICKAXE", List.of());

        RoleBuffsConfig config = mock(RoleBuffsConfig.class);
        when(config.allowRoleChange()).thenReturn(true);
        when(config.combatRoles()).thenReturn(Map.of("tank", tank));
        when(config.supportRoles()).thenReturn(Map.of("miner", miner));
        when(config.roleChangeCooldownMillis()).thenReturn(0L);
        when(config.firstChoiceFree()).thenReturn(true);
        when(config.nearbyEnemyRadius()).thenReturn(nearbyEnemyRadius);

        return new RoleChangeService(config, mock(RoleBuffListener.class), System::currentTimeMillis);
    }

    private Player playerIn() {
        Player player = server.addPlayer();
        player.teleport(world.getSpawnLocation());
        return player;
    }

    /** プレイヤーから離れた場所(半径外)。 */
    private Location farAway() {
        return world.getSpawnLocation().clone().add(50.0, 0.0, 0.0);
    }

    @Test
    @DisplayName("既定(0): 敵が隣に居てもロールを変更できる")
    void defaultRadiusDisablesTheGuardEntirely() {
        RoleChangeService service = service(0.0);
        Player player = playerIn();
        Zombie zombie = world.spawn(player.getLocation(), Zombie.class);
        zombie.setTarget(player);

        assertTrue(service.denyReason(player).isEmpty(),
                "既定ではガードそのものが無効(近くに敵が居ても使えるようにする、が要望)");
        assertTrue(service.denyReasonForCombat(player).isEmpty());
        assertTrue(service.setCombat(player, "tank"));
    }

    @Test
    @DisplayName("有効時: 自分を狙っている敵が半径内に居ると拒否される")
    void enabledRadiusDeniesWhileAnEnemyTargetsThePlayer() {
        RoleChangeService service = service(RADIUS);
        Player player = playerIn();
        Zombie zombie = world.spawn(player.getLocation(), Zombie.class);
        zombie.setTarget(player);

        assertTrue(service.denyReason(player).isPresent());
        assertTrue(service.denyReason(player).orElseThrow().contains("戦闘中"));
    }

    @Test
    @DisplayName("有効時: 狙っていない敵は拒否理由にならない(壁越し・アンビエントの誤爆防止)")
    void enabledRadiusIgnoresEnemiesThatAreNotTargetingThePlayer() {
        RoleChangeService service = service(RADIUS);
        Player player = playerIn();
        Zombie zombie = world.spawn(player.getLocation(), Zombie.class);
        zombie.setTarget(null);

        assertTrue(service.denyReason(player).isEmpty(),
                "Monster 判定時代は「居るだけ」で拒否していた。洞窟や拠点で常時ブロックされる形の誤爆");
    }

    @Test
    @DisplayName("有効時: 中立のゾンビピグリンは狙っていなければ拒否理由にならない")
    void neutralPigZombieDoesNotBlockUnlessItTargetsThePlayer() {
        RoleChangeService service = service(RADIUS);
        Player player = playerIn();
        PigZombie pigZombie = world.spawn(player.getLocation(), PigZombie.class);
        pigZombie.setTarget(null);

        assertTrue(service.denyReason(player).isEmpty(),
                "ネザーはゾンビピグリンが常時居るので、ここで拒否すると事実上永久に変更できない");

        pigZombie.setTarget(player);
        assertTrue(service.denyReason(player).isPresent(), "殴り返されている間は拒否する");
    }

    @Test
    @DisplayName("有効時: 半径外の敵は自分を狙っていても拒否理由にならない")
    void enemiesOutsideTheRadiusAreIgnored() {
        RoleChangeService service = service(RADIUS);
        Player player = playerIn();
        Zombie zombie = world.spawn(farAway(), Zombie.class);
        zombie.setTarget(player);

        assertTrue(service.denyReason(player).isEmpty());
    }

    @Test
    @DisplayName("敵でないモブ(牛)は半径内に居ても無関係")
    void nonEnemyMobsNeverBlock() {
        RoleChangeService service = service(RADIUS);
        Player player = playerIn();
        world.spawn(player.getLocation(), Cow.class);

        assertTrue(service.denyReason(player).isEmpty());
    }

    @Test
    @DisplayName("GUIを開く/解除の動線はガードを通さない(説明を読むだけの操作を戦闘で塞がない)")
    void readOnlyAndClearPathsBypassTheGuard() {
        RoleChangeService service = service(RADIUS);
        Player player = playerIn();
        Zombie zombie = world.spawn(player.getLocation(), Zombie.class);
        zombie.setTarget(player);

        assertTrue(service.denyReason(player).isPresent(), "付け替えは塞がれる");
        assertTrue(service.changeDisabledReason(player).isEmpty(),
                "解除の動線は allow-change だけを見る");
    }

    @Test
    @DisplayName("allow-change: false でも交戦中ガードは別機構(共通ゲートは通る)")
    void allowChangeFalseIsIndependentOfTheCombatGuard() {
        RoleBuffsConfig config = mock(RoleBuffsConfig.class);
        when(config.allowRoleChange()).thenReturn(false);
        when(config.nearbyEnemyRadius()).thenReturn(0.0);
        RoleChangeService service = new RoleChangeService(config, mock(RoleBuffListener.class));
        Player player = playerIn();

        assertTrue(service.changeDisabledReason(player).isPresent(), "解除は塞ぐ(枠を空にできると初回無料が再利用できる)");
        assertTrue(service.denyReason(player).isEmpty(),
                "共通ゲートは交戦中ガードだけを見る。allow-change は枠ごと(初回無料の例外がある)に見る");
    }

    @Test
    @DisplayName("null プレイヤーは両方の入口でプレイヤー専用エラーになる")
    void nullPlayerIsRejectedByBothEntryPoints() {
        RoleChangeService service = service(0.0);

        assertTrue(service.changeDisabledReason(null).isPresent());
        assertTrue(service.denyReason(null).isPresent());
    }

    /**
     * 敵対判定の基準そのものの固定。Bukkit の {@code Monster} は中立を拾い本物の敵対を落とすので
     * 分類に使えない、という既に踏んだ落とし穴の再発防止
     * ({@code ResourceServerMobSimulationTest#isHostile} と同じ基準を role 側にも置く)。
     */
    @Test
    @DisplayName("敵対判定は Enemy が正: Monster は中立を拾い Hoglin/EnderDragon を落とす")
    void monsterIsNotAUsableHostilityBasis() {
        // Monster=true だが中立 → Monster 基準だと「近くに居るだけ」で永久に拒否していた組。
        assertTrue(Monster.class.isAssignableFrom(PigZombie.class));
        // Monster=false だが Enemy=true → Monster 基準ではガードが効かなかった組。
        assertFalse(Monster.class.isAssignableFrom(Hoglin.class));
        assertTrue(Enemy.class.isAssignableFrom(Hoglin.class));
        assertFalse(Monster.class.isAssignableFrom(EnderDragon.class));
        assertTrue(Enemy.class.isAssignableFrom(EnderDragon.class));
        // Enemy は Mob 経由でターゲットを問い合わせられる(＝「自分を狙っているか」で絞れる)。
        assertTrue(Mob.class.isAssignableFrom(Hoglin.class));
        assertTrue(Mob.class.isAssignableFrom(EnderDragon.class));
    }
}
