package com.trinityforge.listeners;

import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.Material;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>出荷</b> {@code combat/mob-overrides.yml} をそのまま読み込み、儀式3本(暗殺者の弓 / 暗殺者の
 * クロスボウ / 黒淵の杖)の唯一の素材である {@code custom:warden_tendril} が
 * <b>実際に {@code EntityDeathEvent} のドロップへ積まれる</b>ことを、リスナー経由で確認する
 * (2026-08-03 追加)。
 *
 * <p>yml に行を足しただけでは足りないことがこのファイルの存在理由。{@link MobOverrideDropListener} は
 * {@code MOB_PROFILE_ID}(EliteMobs フォークだけが刻む PDC)が無い個体で即 return するため、
 * 「設定は書いたのに制御が到達していない」という形の no-op が起こりうる。ここでは
 * <b>刻印あり=落ちる / 刻印なし=1個も落ちない</b>の両方を固定し、その差が仕様であることを明示する。
 *
 * <p>抽選は確率0.5なので、固定シードで200回討伐して「1回以上出る」ことを見る(シードが固定なので
 * 実行ごとにブレない)。個数は 1〜2 の範囲であることも同時に固定する。
 */
class ShippedWardenTendrilDropTest {

    /** 出荷リソース。テストのカレントディレクトリは TrinityForge モジュール直下。 */
    private static final String SHIPPED_MOB_OVERRIDES = "src/main/resources/combat/mob-overrides.yml";

    /** 深層鉱山の踏破ボス(第3段階)。warden_tendril を載せた3種のうちの1つ。 */
    private static final String CLEAR_BOSS_ID = "em_id_the_deep_mines_boss_the_pursuer_p3";

    /** 上記ボスが属するスコープキー。実インスタンスは {@code <これ>_<連番>} だが完全一致でも解決する。 */
    private static final String DUNGEON_WORLD = "em_id_the_deep_mines";

    /** 解決結果の見分け用: warden_tendril だけこの Material を返させる(実素材の base_material と同じ)。 */
    private static final Material TENDRIL_MATERIAL = Material.ECHO_SHARD;

    private static final int SIMULATED_KILLS = 200;

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld(DUNGEON_WORLD);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("出荷 mob-overrides.yml: 深層鉱山の踏破ボスを倒すと warden_tendril が実際にドロップへ積まれる(1〜2個)")
    void shippedConfigActuallyDropsWardenTendril(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadShipped(dir);
        MobOverrideDropListener listener = listenerFor(config);

        Zombie boss = world.spawn(world.getSpawnLocation(), Zombie.class);
        boss.getPersistentDataContainer()
                .set(PdcKeys.MOB_PROFILE_ID, PersistentDataType.STRING, CLEAR_BOSS_ID);
        ((org.mockbukkit.mockbukkit.entity.LivingEntityMock) boss).setKiller(server.addPlayer());

        int hits = 0;
        for (int i = 0; i < SIMULATED_KILLS; i++) {
            EntityDeathEvent event = deathEvent(boss);
            listener.onDeath(event);
            for (ItemStack stack : event.getDrops()) {
                if (stack.getType() != TENDRIL_MATERIAL) {
                    continue;
                }
                hits++;
                assertTrue(stack.getAmount() >= 1 && stack.getAmount() <= 2,
                        "warden_tendril のドロップ個数が 1〜2 の範囲外: " + stack.getAmount());
            }
        }

        assertTrue(hits > 0,
                "出荷 mob-overrides.yml の " + CLEAR_BOSS_ID + " を " + SIMULATED_KILLS
                        + " 回討伐しても custom:warden_tendril が1個も出なかった。"
                        + "この素材は儀式3本(hero_bow / hero_crossbow / abyss_cane)の唯一の素材なので、"
                        + "落ちなければ3本とも【永久に作成不能】になる。"
                        + "drops: の行を消していないか、level-cutoff で止めていないか確認すること");
    }

    @Test
    @DisplayName("MOB_PROFILE_ID を持たないバニラ個体には mob-overrides.yml のドロップが1個も乗らない(EntityType名キーでは配れない)")
    void vanillaMobWithoutProfileIdGetsNothingFromShippedConfig(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadShipped(dir);
        MobOverrideDropListener listener = listenerFor(config);

        Zombie plain = world.spawn(world.getSpawnLocation(), Zombie.class);
        ((org.mockbukkit.mockbukkit.entity.LivingEntityMock) plain).setKiller(server.addPlayer());

        int drops = 0;
        for (int i = 0; i < SIMULATED_KILLS; i++) {
            EntityDeathEvent event = deathEvent(plain);
            listener.onDeath(event);
            drops += event.getDrops().size();
        }

        assertTrue(drops == 0,
                "MOB_PROFILE_ID の無い個体に mob-overrides.yml のドロップが乗った。"
                        + "このファイルは EliteMobs 個体専用という前提が崩れており、"
                        + "『EntityType 名キーに drops: を書いてもバニラモブには効かない』という"
                        + "ファイル冒頭の注意書きと実装が食い違っている。積まれた個数: " + drops);
    }

    // ------------------------------------------------------------------------------------------
    // 補助
    // ------------------------------------------------------------------------------------------

    private MobOverrideDropListener listenerFor(MobOverridesConfig config) {
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        when(resolver.create(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            // 毎回新しい ItemStack を返す(同一インスタンスを返すと setAmount が過去の戦利品まで書き換える)。
            return Optional.of(new ItemStack("warden_tendril".equals(id) ? TENDRIL_MATERIAL : Material.PAPER));
        });
        SymmetricCombatService combatService = mock(SymmetricCombatService.class);
        when(combatService.combatLevelOf(any())).thenReturn(0);
        return new MobOverrideDropListener(config, resolver, combatService,
                new SplittableRandom(20260803L));
    }

    /** 出荷リソースをそのまま一時 dataFolder へ写して読み込む(生成物ではなく出荷 yml を検証するため)。 */
    private MobOverridesConfig loadShipped(File dataFolder) throws Exception {
        Path shipped = Path.of(SHIPPED_MOB_OVERRIDES);
        assertTrue(Files.isRegularFile(shipped),
                "出荷 mob-overrides.yml が見つからない: " + shipped.toAbsolutePath());
        File target = new File(dataFolder, MobOverridesConfig.PATH);
        Files.createDirectories(target.getParentFile().toPath());
        Files.writeString(target.toPath(), Files.readString(shipped));

        MobOverridesConfig config = new MobOverridesConfig();
        config.load(fakePlugin(dataFolder));
        assertTrue(!config.dropsFor(DUNGEON_WORLD, CLEAR_BOSS_ID).isEmpty(),
                "出荷 mob-overrides.yml から " + CLEAR_BOSS_ID + " の drops: を解決できなかった。"
                        + "モブidかスコープキーが変わっていないか確認すること");
        return config;
    }

    private EntityDeathEvent deathEvent(org.bukkit.entity.LivingEntity entity) {
        org.bukkit.damage.DamageSource source = org.bukkit.damage.DamageSource
                .builder(org.bukkit.damage.DamageType.GENERIC_KILL).build();
        return new EntityDeathEvent(entity, source, new ArrayList<>());
    }

    private static org.bukkit.plugin.Plugin fakePlugin(File dataFolder) {
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ShippedWardenTendrilDropTest");
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
}
