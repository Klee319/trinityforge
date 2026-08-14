package com.trinityforge.listeners;

import com.trinityforge.combat.DefenseStats;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.MobLevelTableConfig;
import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.dungeon.DungeonWorldRegistry;
import com.trinityforge.pdc.MobData;
import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.Material;
import org.bukkit.entity.Warden;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
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
 * <b>出荷</b> {@code combat/mob-level-table.yml} をそのまま読み込み、儀式3本(暗殺者の弓 / 暗殺者の
 * クロスボウ / 黒淵の杖)の唯一の素材である {@code custom:warden_tendril} が
 * <b>実際に {@code EntityDeathEvent} のドロップへ積まれる</b>ことを、リスナー経由で確認する
 * (2026-08-03 追加 / 2026-08-14 検証対象を移設)。
 *
 * <h2>2026-08-14: 踏破ボス → フィールドの WARDEN へ移設</h2>
 * ユーザー指示「討伐素材17種はフィールドの該当モブの固有ドロップへ。ダンジョンモブには適用しない」に
 * より、{@code warden_tendril} は {@code combat/mob-overrides.yml} の踏破ボス3種から外れ、
 * {@code combat/mob-level-table.yml} の {@code add-drops}({@code mobs: [WARDEN]} /
 * {@code where: field})が唯一の TF 側ドロップ経路になった。したがってこのテストの検証対象も
 * {@link MobOverrideDropListener} から {@link MobLevelTableListener} へ移している。
 *
 * <p>yml に行を足しただけでは足りないことがこのファイルの存在理由。{@link MobLevelTableListener} は
 * <b>MOB_LEVEL が刻まれていない個体</b>・<b>プレイヤー以外のキル</b>・<b>帯の mobs: に載っていない
 * EntityType</b> のいずれでも即 return するため、「設定は書いたのに制御が到達していない」という形の
 * no-op が起こりうる。ここでは <b>フィールドで落ちる / ダンジョンでは落ちない</b> の両方を固定し、
 * その差({@code where: field})が仕様であることを明示する。
 *
 * <p>抽選は Lv100 で確率0.5(chance-by-level の上端)なので、固定シードで200回討伐して
 * 「1回以上出る」ことを見る(シードが固定なので実行ごとにブレない)。個数は 0〜2 なので、
 * 出たスタックは 1〜2 個であること(0個はリスナー側で捨てられる)も同時に固定する。
 */
class ShippedWardenTendrilDropTest {

    /** 出荷リソース。テストのカレントディレクトリは TrinityForge モジュール直下。 */
    private static final String SHIPPED_MOB_LEVEL_TABLE = "src/main/resources/combat/mob-level-table.yml";
    private static final String SHIPPED_MOB_OVERRIDES = "src/main/resources/combat/mob-overrides.yml";

    /** フィールド扱いのワールド(DungeonWorldRegistry に登録しない)。 */
    private static final String FIELD_WORLD = "world";

    /** ダンジョン扱いのワールド(DungeonWorldRegistry に登録する)。 */
    private static final String DUNGEON_WORLD = "em_id_the_deep_mines_1";

    /**
     * 深層鉱山の踏破ボス(第3段階)。{@code mob-overrides.yml} 側がまだ生きていること
     * (印 dungeon_seal_deep_mines は残す)の確認に使う。
     */
    private static final String CLEAR_BOSS_ID = "em_id_the_deep_mines_boss_the_pursuer_p3";

    /** 解決結果の見分け用: warden_tendril だけこの Material を返させる(実素材の base_material と同じ)。 */
    private static final Material TENDRIL_MATERIAL = Material.ECHO_SHARD;

    /** chance-by-level の上端(Lv100 で 0.5)に乗せるためのレベル。 */
    private static final int KILL_LEVEL = 100;

    private static final int SIMULATED_KILLS = 200;

    private ServerMock server;
    private WorldMock field;
    private WorldMock dungeon;
    private DungeonWorldRegistry dungeonWorldRegistry;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        field = server.addSimpleWorld(FIELD_WORLD);
        dungeon = server.addSimpleWorld(DUNGEON_WORLD);
        dungeonWorldRegistry = new DungeonWorldRegistry();
        dungeonWorldRegistry.register(dungeon.getUID());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("出荷 mob-level-table.yml: フィールドのウォーデンを倒すと warden_tendril が実際にドロップへ積まれる(1〜2個)")
    void shippedConfigActuallyDropsWardenTendrilOnFieldWardens(@TempDir File dir) throws Exception {
        MobLevelTableListener listener = levelTableListenerFor(loadShippedLevelTable(dir));

        int hits = 0;
        for (int i = 0; i < SIMULATED_KILLS; i++) {
            Warden warden = field.spawn(field.getSpawnLocation(), Warden.class);
            EntityDeathEvent event = deathEvent(warden, KILL_LEVEL);
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
                "出荷 mob-level-table.yml でフィールドのウォーデンを " + SIMULATED_KILLS
                        + " 回討伐しても custom:warden_tendril が1個も出なかった。"
                        + "この素材は儀式3本(hero_bow / hero_crossbow / abyss_wand)の唯一の素材なので、"
                        + "落ちなければ3本とも【永久に作成不能】になる。"
                        + "add-drops の行を消していないか、mobs: から WARDEN を落としていないか、"
                        + "帯(tiers)の mobs: に WARDEN が残っているか、"
                        + "add-drops を一部の帯にしか書いていないか(帯は floor lookup で1つしか選ばれない)"
                        + "を確認すること");
    }

    @Test
    @DisplayName("where: field なので、ダンジョンインスタンス内のウォーデンからは warden_tendril が1個も出ない")
    void shippedConfigDropsNothingForWardensInsideDungeonInstances(@TempDir File dir) throws Exception {
        MobLevelTableListener listener = levelTableListenerFor(loadShippedLevelTable(dir));

        int hits = 0;
        for (int i = 0; i < SIMULATED_KILLS; i++) {
            Warden warden = dungeon.spawn(dungeon.getSpawnLocation(), Warden.class);
            EntityDeathEvent event = deathEvent(warden, KILL_LEVEL);
            listener.onDeath(event);
            for (ItemStack stack : event.getDrops()) {
                if (stack.getType() == TENDRIL_MATERIAL) {
                    hits++;
                }
            }
        }

        assertTrue(hits == 0,
                "ダンジョンインスタンスワールド内の討伐で warden_tendril が落ちた(" + hits + " 個)。"
                        + "ユーザー指示は「討伐素材はフィールドの該当モブから。ダンジョンモブには適用しない」なので、"
                        + "出荷 yml の where: field が外れていないか、判定が MOB_TYPE_STAMPED のような"
                        + "「ダンジョンモブにも付く印」に化けていないかを確認すること");
    }

    @Test
    @DisplayName("MOB_LEVEL を持たない個体には mob-level-table.yml のドロップが1個も乗らない(帯という概念が無い)")
    void mobWithoutStampedLevelGetsNothingFromShippedLevelTable(@TempDir File dir) throws Exception {
        MobLevelTableListener listener = levelTableListenerFor(loadShippedLevelTable(dir));

        int drops = 0;
        for (int i = 0; i < SIMULATED_KILLS; i++) {
            Warden warden = field.spawn(field.getSpawnLocation(), Warden.class);
            // MobData.stamp を呼ばない = MOB_LEVEL が無い(= レベル帯の対象外)。
            ((org.mockbukkit.mockbukkit.entity.LivingEntityMock) warden).setKiller(server.addPlayer());
            EntityDeathEvent event = rawDeathEvent(warden);
            listener.onDeath(event);
            drops += event.getDrops().size();
        }

        assertTrue(drops == 0,
                "MOB_LEVEL が刻まれていない個体に mob-level-table.yml のドロップが乗った。"
                        + "このファイルは『戦闘レベルが刻印されたモブ』専用という前提が崩れている。"
                        + "積まれた個数: " + drops);
    }

    @Test
    @DisplayName("MOB_PROFILE_ID を持たないバニラ個体には mob-overrides.yml のドロップが1個も乗らない(EntityType名キーでは配れない)")
    void vanillaMobWithoutProfileIdGetsNothingFromShippedConfig(@TempDir File dir) throws Exception {
        MobOverrideDropListener listener = overrideListenerFor(loadShippedOverrides(dir));

        Zombie plain = field.spawn(field.getSpawnLocation(), Zombie.class);
        ((org.mockbukkit.mockbukkit.entity.LivingEntityMock) plain).setKiller(server.addPlayer());

        int drops = 0;
        for (int i = 0; i < SIMULATED_KILLS; i++) {
            EntityDeathEvent event = rawDeathEvent(plain);
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

    private MobLevelTableListener levelTableListenerFor(MobLevelTableConfig config) {
        return new MobLevelTableListener(config, dungeonWorldRegistry, tendrilResolver(),
                new SplittableRandom(20260814L));
    }

    private MobOverrideDropListener overrideListenerFor(MobOverridesConfig config) {
        SymmetricCombatService combatService = mock(SymmetricCombatService.class);
        when(combatService.combatLevelOf(any())).thenReturn(0);
        com.trinityforge.config.domains.CombatDamageConfig damageConfig =
                mock(com.trinityforge.config.domains.CombatDamageConfig.class);
        when(damageConfig.levelCutoff()).thenReturn(com.trinityforge.mobs.MobLevelCutoff.NONE);
        com.trinityforge.combat.PlayerStatAggregator aggregator =
                mock(com.trinityforge.combat.PlayerStatAggregator.class);
        when(aggregator.aggregate(any(org.bukkit.entity.Player.class)))
                .thenReturn(new com.trinityforge.combat.PlayerCombatAggregate(
                        java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                        java.util.Map.of(), java.util.Map.of()));
        return new MobOverrideDropListener(config, tendrilResolver(),
                new KillRewardAdjuster(damageConfig, combatService, aggregator),
                new SplittableRandom(20260803L));
    }

    private CrossPluginItemResolver tendrilResolver() {
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        when(resolver.create(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            // 毎回新しい ItemStack を返す(同一インスタンスを返すと setAmount が過去の戦利品まで書き換える)。
            return Optional.of(new ItemStack("warden_tendril".equals(id) ? TENDRIL_MATERIAL : Material.PAPER));
        });
        return resolver;
    }

    /** 出荷リソースをそのまま一時 dataFolder へ写して読み込む(生成物ではなく出荷 yml を検証するため)。 */
    private MobLevelTableConfig loadShippedLevelTable(File dataFolder) throws Exception {
        copyShipped(SHIPPED_MOB_LEVEL_TABLE, dataFolder, MobLevelTableConfig.PATH);
        MobLevelTableConfig config = new MobLevelTableConfig();
        config.load(fakePlugin(dataFolder));
        assertTrue(config.resolve(KILL_LEVEL).isPresent(),
                "出荷 mob-level-table.yml から Lv" + KILL_LEVEL + " の帯を解決できなかった");
        assertTrue(!config.resolve(KILL_LEVEL).orElseThrow().addDrops().isEmpty(),
                "出荷 mob-level-table.yml の Lv" + KILL_LEVEL + " の帯に add-drops が1件も無い。"
                        + "帯は floor lookup で1つしか選ばれないので、min-level: 0 の帯にだけ書くと"
                        + "高レベル帯では何も落ちなくなる");
        return config;
    }

    private MobOverridesConfig loadShippedOverrides(File dataFolder) throws Exception {
        copyShipped(SHIPPED_MOB_OVERRIDES, dataFolder, MobOverridesConfig.PATH);
        MobOverridesConfig config = new MobOverridesConfig();
        config.load(fakePlugin(dataFolder));
        assertTrue(!config.dropsFor("em_id_the_deep_mines", CLEAR_BOSS_ID).isEmpty(),
                "出荷 mob-overrides.yml から " + CLEAR_BOSS_ID + " の drops: を解決できなかった。"
                        + "モブidかスコープキーが変わっていないか確認すること(印 dungeon_seal_deep_mines は"
                        + "warden_tendril を外したあとも残っているはず)");
        return config;
    }

    private static void copyShipped(String shippedPath, File dataFolder, String targetRelative)
            throws Exception {
        Path shipped = Path.of(shippedPath);
        assertTrue(Files.isRegularFile(shipped), "出荷 yml が見つからない: " + shipped.toAbsolutePath());
        File target = new File(dataFolder, targetRelative);
        Files.createDirectories(target.getParentFile().toPath());
        Files.writeString(target.toPath(), Files.readString(shipped));
    }

    /** MOB_LEVEL を刻み、プレイヤーキルにしたうえで死亡イベントを作る({@code MOB_PROFILE_ID} は刻まない)。 */
    private EntityDeathEvent deathEvent(org.bukkit.entity.LivingEntity entity, int level) {
        MobData.stamp(entity, level, DefenseStats.NONE, DefenseStats.NONE);
        ((org.mockbukkit.mockbukkit.entity.LivingEntityMock) entity).setKiller(server.addPlayer());
        return rawDeathEvent(entity);
    }

    private EntityDeathEvent rawDeathEvent(org.bukkit.entity.LivingEntity entity) {
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
