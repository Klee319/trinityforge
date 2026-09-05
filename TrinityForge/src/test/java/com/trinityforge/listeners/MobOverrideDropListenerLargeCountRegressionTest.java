package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.mobs.MobLevelCutoff;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.StatKeys;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 台帳 W-312 の回帰テスト。{@code mob_drop_bonus} が大きいと
 * {@link com.trinityforge.mobs.MobDropRoller#cappedCount} の戻り値が
 * アイテムエンティティのコーデック上限(99)を軽く超える(実機ログでは 259 個の Rotten Flesh が
 * 1スタックのまま {@code dropItemNaturally} 相当の経路へ渡り、チャンク保存時に無言で消えていた)。
 *
 * <p>修正前は {@code EliteMobsSharedLootBridge.deliver(event, stack)} へ257個のスタックを
 * そのまま1本渡していた({@code event.getDrops()} に1件・amount=257)。
 * 修正後は {@link com.trinityforge.items.ItemStackDrops#split} を経由するため、
 * 同じ入力で複数件に分割され、どの1件も99個を超えない。
 */
class MobOverrideDropListenerLargeCountRegressionTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("w312_regression_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private MobOverridesConfig loadedConfig(File dataFolder, String yaml) throws Exception {
        File file = new File(dataFolder, MobOverridesConfig.PATH);
        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
        java.nio.file.Files.writeString(file.toPath(), yaml);
        MobOverridesConfig config = new MobOverridesConfig();
        config.load(fakePlugin(dataFolder));
        return config;
    }

    private static org.bukkit.plugin.Plugin fakePlugin(File dataFolder) {
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("MobOverrideDropListenerLargeCountRegressionTest");
            case "saveResource" -> null;
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(),
                new Class<?>[]{org.bukkit.plugin.Plugin.class}, handler);
    }

    /** dropBonus を極端に大きくして cappedCount を99超えの領域まで押し上げる調整器。 */
    private static KillRewardAdjuster hugeDropBonusAdjuster(double dropBonus) {
        SymmetricCombatService combatService = mock(SymmetricCombatService.class);
        when(combatService.combatLevelOf(any())).thenReturn(0);
        CombatDamageConfig damageConfig = mock(CombatDamageConfig.class);
        when(damageConfig.levelCutoff()).thenReturn(MobLevelCutoff.NONE);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(any(org.bukkit.entity.Player.class))).thenReturn(new PlayerCombatAggregate(
                Map.of(StatKeys.canonical("mob_drop_bonus"), dropBonus), Map.of(), Map.of(), Map.of(), Map.of()));
        return new KillRewardAdjuster(damageConfig, combatService, aggregator);
    }

    private EntityDeathEvent deathEventFor(org.bukkit.entity.LivingEntity entity, String profileId) {
        entity.getPersistentDataContainer().set(PdcKeys.MOB_PROFILE_ID, PersistentDataType.STRING, profileId);
        org.bukkit.entity.Player killer = server.addPlayer();
        ((org.mockbukkit.mockbukkit.entity.LivingEntityMock) entity).setKiller(killer);
        org.bukkit.damage.DamageSource source = org.bukkit.damage.DamageSource
                .builder(org.bukkit.damage.DamageType.GENERIC_KILL).build();
        List<ItemStack> drops = new ArrayList<>();
        return new EntityDeathEvent(entity, source, drops);
    }

    @Test
    void hugeDropBonusNoLongerProducesAnEntityDropAbove99(@TempDir File dir) throws Exception {
        // ROTTEN_FLESH 相当(64スタック)・min=1,max=2(non-singleFixed)・確率1.0。
        // dropBonus=300.0 -> extraCount は MAX_EXTRA_COUNT(256)で頭打ち。
        // count(1 or 2) + 256 を cappedCount(maxStackSize*8=512)へ通すと257か258になり、
        // 修正前ならそのまま1本のItemStackとして event.getDrops() に積まれていた(実機再現)。
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: ROTTEN_FLESH, chance: 1.0, min: 1, max: 2 }
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobOverrideDropListener listener = new MobOverrideDropListener(
                config, resolver, hugeDropBonusAdjuster(300.0), new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertTrue(!event.getDrops().isEmpty(), "前提: chance 1.0 なので必ず何か落ちる");

        int totalAmount = 0;
        for (ItemStack drop : event.getDrops()) {
            assertTrue(drop.getAmount() <= 99,
                    "修正後はどのドロップも99個を超えてはならない(超えるとチャンク保存時に消える)");
            totalAmount += drop.getAmount();
        }
        // 分割しても総量は変わらないこと(257〜258個のどちらかになるはず、乱数依存で1個だけ変わる)。
        assertTrue(totalAmount >= 257 && totalAmount <= 258,
                "分割前後で総個数が保存されていること: " + totalAmount);
        // 257個を99以下へ分割すると最低3本(99+99+59)は必要になる -> 1本のままでないことの確認。
        assertTrue(event.getDrops().size() >= 3,
                "257個超のドロップが分割されず1本のままなら回帰(修正前の実機不具合)");
    }
}
