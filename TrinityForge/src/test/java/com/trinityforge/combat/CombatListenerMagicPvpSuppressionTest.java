package com.trinityforge.combat;

import com.trinityforge.TrinityForge;
import com.trinityforge.TrinityForgeSingletonTestSupport;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.dungeon.DungeonWorldRegistry;
import com.trinityforge.listeners.CombatListener;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import com.google.common.base.Function;
import com.google.common.base.Functions;

import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-07-31 (F4 指摘2) の回帰テスト: <b>魔法ダメージが TF の PvP 抑制を通る</b>こと、かつ
 * <b>ステが二重計上されない</b>ことを固定する。
 *
 * <p>元の欠落: {@code CombatListener#onEntityDamageByEntity} の {@code resolveAttacker} は近接と
 * 飛び道具しか受け付けないため、ArsPaper フォークが {@code DamageType.MAGIC} で撃つ魔法ヒットは
 * 必ず早期returnしていた。結果 {@code PvpDamagePolicy}(倍率＋最大体力割合上限)が魔法だけに届かず、
 * Lv100帯の魔法基礎 約21222 が無抑制で対人に通っていた。
 *
 * <p>フォーク自体はTFのテスト対象外なので、{@code applyMagicDamage} が発火させるイベント
 * (cause=MAGIC / damager=詠唱者 / {@link MagicPipelineDamage#mark()} 中)を直接組み立てて再現する。
 */
@SuppressWarnings({"removal", "deprecation"}) // deprecated-for-removal event ctor / DamageModifier reads.
class CombatListenerMagicPvpSuppressionTest {

    /** フォークが撃つ Lv100 帯の最終魔法ダメージ(レビュー指摘の実測値)。 */
    private static final double MAGIC_DAMAGE = 21222.0;
    private static final double PVP_MULTIPLIER = 0.5;
    private static final double PVP_MAX_PERCENT = 0.15;

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.dungeonWorldRegistry()).thenReturn(new DungeonWorldRegistry());
        TrinityForgeSingletonTestSupport.set(tf);
        resetMarker();
    }

    @AfterEach
    void tearDown() {
        resetMarker();
        TrinityForgeSingletonTestSupport.clear();
        MockBukkit.unmock();
    }

    /** static な深度カウンタなのでテスト間の汚染を防ぐ。 */
    private void resetMarker() {
        while (MagicPipelineDamage.isActive()) {
            MagicPipelineDamage.clear();
        }
    }

    private CombatListener listener(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), "items: {}\n");

        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        // pvp: を自分で書くので CombatWiringSupport の「既定OFF」は付かない(=抑制が生きる)。
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                  min-component-damage: 0.0
                pvp:
                  enabled: true
                  damage-multiplier: %s
                  max-damage-percent-of-max-health: %s
                """.formatted(PVP_MULTIPLIER, PVP_MAX_PERCENT));
        Plugin plugin = MockBukkit.createMockPlugin();
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(cm.itemStats(), damage, perks,
                new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        SymmetricCombatService svc = new SymmetricCombatService(
                damage, cm.combatLevel(), cm.mobTypes(), SkillLevelSource.EMPTY, defense);
        BleedService bleed = new BleedService(plugin, svc, damage);
        return new CombatListener(plugin, svc, cm.itemStats(), damage, SkillLevelSource.EMPTY, bleed,
                perks, aggregator, cm.useRequirements(), cm.skillExp(), cm.craftingFeatures(),
                new RoleBuffResolver(cm.roleBuffs()));
    }

    private EntityDamageByEntityEvent magicHit(Player caster, LivingEntity victim) {
        DamageSource source = DamageSource.builder(DamageType.MAGIC)
                .withCausingEntity(caster).withDirectEntity(caster).build();
        return new EntityDamageByEntityEvent(caster, victim,
                EntityDamageEvent.DamageCause.MAGIC, source, MAGIC_DAMAGE);
    }

    /**
     * 実サーバと同じ「BASE 以外の modifier も入っている」イベントを組む(F5 指摘1 の回帰用)。
     * 5引数の非推奨コンストラクタは modifiers に BASE しか入れないため、防護エンチャントや
     * 吸収ハートによる崩壊を<b>構造的に再現できない</b>(まさに「モックの既定値のおかげで
     * 通っているだけのテスト」だった)。modifier マップを渡す版だけが再現できる。
     */
    private EntityDamageByEntityEvent magicHitWithModifiers(
            Player caster, LivingEntity victim,
            Map<EntityDamageEvent.DamageModifier, Double> extraModifiers) {
        Map<EntityDamageEvent.DamageModifier, Double> modifiers =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        modifiers.put(EntityDamageEvent.DamageModifier.BASE, MAGIC_DAMAGE);
        modifiers.putAll(extraModifiers);

        Map<EntityDamageEvent.DamageModifier, Function<? super Double, Double>> functions =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        for (EntityDamageEvent.DamageModifier modifier : modifiers.keySet()) {
            functions.put(modifier, Functions.constant(modifiers.get(modifier)));
        }

        DamageSource source = DamageSource.builder(DamageType.MAGIC)
                .withCausingEntity(caster).withDirectEntity(caster).build();
        return new EntityDamageByEntityEvent(caster, victim,
                EntityDamageEvent.DamageCause.MAGIC, source, modifiers, functions);
    }

    private static double base(EntityDamageEvent event) {
        return event.getDamage(EntityDamageEvent.DamageModifier.BASE);
    }

    @Test
    @DisplayName("player→player の魔法は倍率と最大体力割合の上限を通る")
    void magicPvpIsSuppressed(@TempDir File dir) throws IOException {
        CombatListener listener = listener(dir);
        Player caster = server.addPlayer();
        Player victim = server.addPlayer();
        assertEquals(20.0, PvpDamagePolicy.maxHealthOf(victim), 1e-9,
                "前提: MockBukkit のプレイヤー最大体力は20(期待値の計算根拠)");

        EntityDamageByEntityEvent event = magicHit(caster, victim);
        MagicPipelineDamage.mark();
        try {
            listener.onMagicPipelineDamageByEntity(event);
        } finally {
            MagicPipelineDamage.clear();
        }

        // min(21222 * 0.5, 20 * 0.15) = 3.0。倍率だけだと10611で即死するので割合上限が効くことが本質。
        assertEquals(3.0, base(event), 1e-9,
                "魔法もPvP抑制(倍率→最大体力割合上限)を通る必要がある");
    }

    @Test
    @DisplayName("防護エンチャント付きの相手でも最終ダメージが0以下に潰れない(F5 指摘1)")
    void magicPvpSuppressionDoesNotCollapseToZeroWithProtectionEnchantment(@TempDir File dir)
            throws IOException {
        CombatListener listener = listener(dir);
        Player caster = server.addPlayer();
        Player victim = server.addPlayer();

        // 防護IVフルセット(EPF16 ≒ -64%)を抑制前 BASE から算出した絶対値。実サーバはこの形で届く。
        double protectionModifier = -13582.0;
        EntityDamageByEntityEvent event = magicHitWithModifiers(caster, victim,
                Map.of(EntityDamageEvent.DamageModifier.MAGIC, protectionModifier));
        double finalBefore = event.getFinalDamage();
        assertEquals(MAGIC_DAMAGE + protectionModifier, finalBefore, 1e-9,
                "前提: 抑制前の最終ダメージは BASE + 防護modifier");

        MagicPipelineDamage.mark();
        try {
            listener.onMagicPipelineDamageByEntity(event);
        } finally {
            MagicPipelineDamage.clear();
        }

        // 初版実装(BASEだけを 3.0 へ書き換える)だと 3.0 - 13582 < 0 = 完全無効になっていた。
        assertTrue(event.getFinalDamage() > 0.0,
                "防護エンチャント持ちへの魔法が0ダメージへ潰れてはならない(実測 "
                        + event.getFinalDamage() + ")");
        assertEquals(3.0, event.getFinalDamage(), 1e-9,
                "抑制は最終ダメージへ掛かる: min(7640*0.5, 20*0.15) = 3.0");
        assertTrue(event.getDamage(EntityDamageEvent.DamageModifier.MAGIC) < 0.0,
                "防護は0化せず軽減として残す(魔法経路はTF側で防護を再導出していないため)");
        assertEquals(protectionModifier / MAGIC_DAMAGE,
                event.getDamage(EntityDamageEvent.DamageModifier.MAGIC) / base(event), 1e-9,
                "防護の『割合としての軽減』が抑制の前後で変わらない");
    }

    @Test
    @DisplayName("吸収ハート付きの相手でも最終ダメージが0以下に潰れない(F5 指摘1)")
    void magicPvpSuppressionDoesNotCollapseToZeroWithAbsorptionHearts(@TempDir File dir)
            throws IOException {
        CombatListener listener = listener(dir);
        Player caster = server.addPlayer();
        Player victim = server.addPlayer();

        // 金リンゴの吸収ハート2個 = -min(absorption, damage) = -4.0。防護が無くてもこれだけで
        // 初版実装は 3.0 - 4 = -1 → 0ダメージへ振り切れていた。
        double absorption = -4.0;
        EntityDamageByEntityEvent event = magicHitWithModifiers(caster, victim,
                Map.of(EntityDamageEvent.DamageModifier.ABSORPTION, absorption));

        MagicPipelineDamage.mark();
        try {
            listener.onMagicPipelineDamageByEntity(event);
        } finally {
            MagicPipelineDamage.clear();
        }

        assertTrue(event.getFinalDamage() > 0.0,
                "吸収ハート持ちへの魔法が0ダメージへ潰れてはならない(実測 "
                        + event.getFinalDamage() + ")");
        assertEquals(3.0, event.getFinalDamage(), 1e-9, "抑制後の最終ダメージは上限値ちょうど");
        double expectedScale = 3.0 / (MAGIC_DAMAGE + absorption);
        assertEquals(absorption * expectedScale,
                event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION), 1e-9,
                "吸収の消費量も同じ比率で縮む(上限が効いている間は吸収プールが比例して長持ちする)");
    }

    @Test
    @DisplayName("防護＋吸収の同時持ちでも0以下に潰れない(F5 指摘1)")
    void magicPvpSuppressionSurvivesProtectionAndAbsorptionTogether(@TempDir File dir)
            throws IOException {
        CombatListener listener = listener(dir);
        Player caster = server.addPlayer();
        Player victim = server.addPlayer();

        Map<EntityDamageEvent.DamageModifier, Double> extras = new EnumMap<>(
                EntityDamageEvent.DamageModifier.class);
        extras.put(EntityDamageEvent.DamageModifier.MAGIC, -13582.0);
        extras.put(EntityDamageEvent.DamageModifier.ABSORPTION, -4.0);
        EntityDamageByEntityEvent event = magicHitWithModifiers(caster, victim, extras);

        MagicPipelineDamage.mark();
        try {
            listener.onMagicPipelineDamageByEntity(event);
        } finally {
            MagicPipelineDamage.clear();
        }

        assertTrue(event.getFinalDamage() > 0.0, "実測 " + event.getFinalDamage());
        assertEquals(3.0, event.getFinalDamage(), 1e-9, "抑制後の最終ダメージは上限値ちょうど");
    }

    @Test
    @DisplayName("盾で完全ブロック済み(最終0)の魔法には触らない")
    void fullyBlockedMagicIsLeftAlone(@TempDir File dir) throws IOException {
        CombatListener listener = listener(dir);
        Player caster = server.addPlayer();
        Player victim = server.addPlayer();

        EntityDamageByEntityEvent event = magicHitWithModifiers(caster, victim,
                Map.of(EntityDamageEvent.DamageModifier.BLOCKING, -MAGIC_DAMAGE));
        assertEquals(0.0, event.getFinalDamage(), 1e-9, "前提: 完全ブロックで最終0");

        MagicPipelineDamage.mark();
        try {
            listener.onMagicPipelineDamageByEntity(event);
        } finally {
            MagicPipelineDamage.clear();
        }

        assertEquals(MAGIC_DAMAGE, base(event), 1e-9,
                "最終が既に0以下なら縮める余地がないので何も書き換えない");
        assertEquals(0.0, event.getFinalDamage(), 1e-9, "最終ダメージも0のまま");
    }

    @Test
    @DisplayName("PvP抑制を無効化すれば魔法は素通し(従来挙動)")
    void disabledPvpLeavesMagicUntouched(@TempDir File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), "items: {}\n");
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                pvp:
                  enabled: false
                """);
        Plugin plugin = MockBukkit.createMockPlugin();
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(cm.itemStats(), damage, perks,
                new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        SymmetricCombatService svc = new SymmetricCombatService(
                damage, cm.combatLevel(), cm.mobTypes(), SkillLevelSource.EMPTY, defense);
        CombatListener listener = new CombatListener(plugin, svc, cm.itemStats(), damage,
                SkillLevelSource.EMPTY, new BleedService(plugin, svc, damage), perks, aggregator,
                cm.useRequirements(), cm.skillExp(), cm.craftingFeatures(),
                new RoleBuffResolver(cm.roleBuffs()));

        Player caster = server.addPlayer();
        Player victim = server.addPlayer();
        EntityDamageByEntityEvent event = magicHit(caster, victim);
        MagicPipelineDamage.mark();
        try {
            listener.onMagicPipelineDamageByEntity(event);
        } finally {
            MagicPipelineDamage.clear();
        }

        assertEquals(MAGIC_DAMAGE, base(event), 1e-9,
                "pvp.enabled=false なら魔法にも一切触れない");
    }

    @Test
    @DisplayName("モブへの魔法(PvEの大多数)は抑制されない")
    void magicToMobIsNotSuppressed(@TempDir File dir) throws IOException {
        CombatListener listener = listener(dir);
        Player caster = server.addPlayer();
        LivingEntity mob = world.spawn(world.getSpawnLocation(), Zombie.class);

        EntityDamageByEntityEvent event = magicHit(caster, mob);
        MagicPipelineDamage.mark();
        try {
            listener.onMagicPipelineDamageByEntity(event);
        } finally {
            MagicPipelineDamage.clear();
        }

        assertEquals(MAGIC_DAMAGE, base(event), 1e-9,
                "PvP抑制はplayer→playerだけ。モブ相手のダメージを削ってはならない");
    }

    @Test
    @DisplayName("TFパイプライン外の MAGIC(バニラ負傷ポーション等)には触らない")
    void magicWithoutPipelineMarkerIsUntouched(@TempDir File dir) throws IOException {
        CombatListener listener = listener(dir);
        Player caster = server.addPlayer();
        Player victim = server.addPlayer();

        EntityDamageByEntityEvent event = magicHit(caster, victim);
        // マーカー無し = TFの対称パイプラインを通っていないダメージ。バニラの負傷ポーションを
        // 半減させるとPvPバランスを別方向に壊すので、cause だけで判定してはいけない
        // (MagicResistanceFoldListener が同じ理由で踏んだ実害リグレッション)。
        listener.onMagicPipelineDamageByEntity(event);

        assertEquals(MAGIC_DAMAGE, base(event), 1e-9,
                "マーカー無しの cause=MAGIC は書き換えない");
    }

    @Test
    @DisplayName("魔法ヒットは物理ハンドラを一切通らない(ステの二重計上なし)")
    void meleeHandlerIgnoresMagicSoStatsAreNotCountedTwice(@TempDir File dir) throws IOException {
        CombatListener listener = listener(dir);
        Player caster = server.addPlayer();
        Player victim = server.addPlayer();

        EntityDamageByEntityEvent event = magicHit(caster, victim);
        MagicPipelineDamage.mark();
        try {
            // Bukkit は同一リスナーの両ハンドラを同じイベントへ配送する。物理側は cause=MAGIC を
            // 受け付けない(resolveAttacker=null)ので何もせず、抑制は1回だけ掛かるのが正しい。
            listener.onEntityDamageByEntity(event);
            assertEquals(MAGIC_DAMAGE, base(event), 1e-9,
                    "物理ハンドラは魔法ヒットに触ってはならない(会心/貫通/守備力はフォーク側で計算済み)");
            listener.onMagicPipelineDamageByEntity(event);
        } finally {
            MagicPipelineDamage.clear();
        }

        assertEquals(3.0, base(event), 1e-9,
                "PvP抑制は1回だけ。二重に掛かると 1.5 になる");
    }
}
