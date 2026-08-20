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

import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
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
 * 2026-08-01 の回帰テスト: <b>物理 PvP 抑制が BASE だけを縮めて、盾({@code BLOCKING})と
 * 吸収ハート({@code ABSORPTION})を抑制前のバニラ絶対値のまま置き去りにしていた</b>件。
 *
 * <p>魔法経路では同型の欠陥が 487e84a(F5 指摘1)で修正済みだったが、物理経路
 * ({@code CombatListener#onEntityDamageByEntity})は {@code setDamage(BASE, total)} しか書いておらず
 * 残っていた。{@code getFinalDamage()} は<b>全 modifier の単純和で 0 クランプが無い</b>ため、
 * 抑制で BASE が数点まで縮む帯では 吸収ハート2個({@code -4.0})だけで最終ダメージが負値へ潰れ、
 * <b>盾や金リンゴを持った相手には物理攻撃が一切通らない</b>状態になっていた。
 *
 * <p><b>5引数の非推奨コンストラクタでは再現できない</b>(modifiers に BASE しか入らない)ので、
 * {@link CombatListenerMagicPvpSuppressionTest} と同じく modifier マップを渡す版でイベントを組む。
 */
@SuppressWarnings({"removal", "deprecation"}) // deprecated-for-removal event ctor / DamageModifier reads.
class CombatListenerPhysicalPvpSuppressionTest {

    /** バニラ側の素の武器ダメージ(BASE の初期値)。TF は attack-power 置換でこれを丸ごと捨てる。 */
    private static final double VANILLA_BASE = 6.0;
    private static final double PVP_MULTIPLIER = 0.5;
    private static final double PVP_MAX_PERCENT = 0.15;
    /** MockBukkit のプレイヤー最大体力 20 × 0.15。テスト内で前提として検証する。 */
    private static final double PVP_CAP = 20.0 * PVP_MAX_PERCENT;
    /** 金リンゴの吸収ハート2個 = {@code -min(absorption, damage)}。 */
    private static final double ABSORPTION = -4.0;
    /** 盾で受け止めた分。バニラは「その時点のダメージ」をそのまま打ち消す値を入れてくる。 */
    private static final double BLOCKING = -VANILLA_BASE;

    private ServerMock server;
    private WorldMock world;
    /** 1テストで2本のリスナー(抑制あり/なし)を組むので、モックプラグインは使い回す。 */
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.createMockPlugin();
        // total > 0 で expAllowedInWorld() に入り TrinityForge.getInstance() を読むのでスタブする
        // (CombatListenerProtectionMitigationTest と同じ理由)。
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.dungeonWorldRegistry()).thenReturn(new DungeonWorldRegistry());
        TrinityForgeSingletonTestSupport.set(tf);
    }

    @AfterEach
    void tearDown() {
        TrinityForgeSingletonTestSupport.clear();
        MockBukkit.unmock();
    }

    /**
     * PvP 抑制の有無だけが違う 2 本のリスナー。抑制前の TF ダメージ(=抑制係数の分母)を
     * 実測するために「抑制なし」側が要る — 期待値を定数で決め打ちすると、攻撃カーブの
     * 調整でテストが壊れて本質(0以下へ潰れないこと)を見失うため。
     */
    private CombatListener listener(File dir, boolean pvpEnabled) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        // 攻撃力を大きな固定値に置換(tfBaseReplaces=true)。crit-chance 未設定=0 なので会心の乱数は無い。
        Files.writeString(itemStats.toPath(), """
                items:
                  GOLDEN_SWORD:
                    fixed: { attack-power: 1000.0, damage-modifier: 1.0 }
                """);

        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        // pvp: を自分で書くので CombatWiringSupport の「既定OFF」は付かない。
        // vanilla-armor と melee-charge を殺して、観測値を抑制係数だけの関数にする。
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                vanilla-armor:
                  defense-rate-per-point: 0.0
                  defense-rate-max: 0.0
                  armor-strength-per-point: 0.0
                melee-charge:
                  enabled: false
                pvp:
                  enabled: %s
                  damage-multiplier: %s
                  max-damage-percent-of-max-health: %s
                """.formatted(pvpEnabled, PVP_MULTIPLIER, PVP_MAX_PERCENT));
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        SymmetricCombatService svc = new SymmetricCombatService(
                damage, cm.combatLevel(), cm.mobTypes(), SkillLevelSource.EMPTY, defense);
        BleedService bleed = new BleedService(plugin, svc, damage);
        return new CombatListener(plugin, svc, cm.itemStats(), damage, SkillLevelSource.EMPTY, bleed,
                perks, aggregator, cm.useRequirements(), cm.skillExp(), cm.craftingFeatures(),
                new RoleBuffResolver(cm.roleBuffs()));
    }

    /** 実サーバと同じ「BASE 以外の modifier も入っている」近接ヒットを組む。 */
    private EntityDamageByEntityEvent meleeHit(Player attacker, LivingEntity victim,
                                               Map<EntityDamageEvent.DamageModifier, Double> extras) {
        Map<EntityDamageEvent.DamageModifier, Double> modifiers =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        modifiers.put(EntityDamageEvent.DamageModifier.BASE, VANILLA_BASE);
        modifiers.putAll(extras);

        Map<EntityDamageEvent.DamageModifier, Function<? super Double, Double>> functions =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        for (EntityDamageEvent.DamageModifier modifier : modifiers.keySet()) {
            functions.put(modifier, Functions.constant(modifiers.get(modifier)));
        }

        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        return new EntityDamageByEntityEvent(attacker, victim,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, modifiers, functions);
    }

    private static double damageOf(EntityDamageEvent event, EntityDamageEvent.DamageModifier modifier) {
        return event.getDamage(modifier);
    }

    private static double base(EntityDamageEvent event) {
        return damageOf(event, EntityDamageEvent.DamageModifier.BASE);
    }

    private Player swordsman() {
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD));
        return attacker;
    }

    /** 抑制が掛かる前の TF 物理ダメージ(= BASE へ書かれる素の値)を、同一構成の pvp:false 側で実測する。 */
    private double unsuppressedTotal(File dir, Player attacker, Player victim) throws IOException {
        EntityDamageByEntityEvent baseline = meleeHit(attacker, victim, Map.of());
        listener(new File(dir, "pvp-off"), false).onEntityDamageByEntity(baseline);
        double total = base(baseline);
        assertTrue(total * PVP_MULTIPLIER > PVP_CAP,
                "前提: 抑制前ダメージは倍率を掛けても上限(" + PVP_CAP + ")を超える大きさが要る。実測 " + total);
        return total;
    }

    @Test
    @DisplayName("PvP抑制下で吸収ハート持ちを殴っても最終ダメージが0以下へ潰れない(BASEだけ縮めると-1.0になる)")
    void suppressedPhysicalHitDoesNotCollapseWithAbsorptionHearts(@TempDir File dir) throws IOException {
        Player attacker = swordsman();
        Player victim = server.addPlayer();
        assertEquals(20.0, PvpDamagePolicy.maxHealthOf(victim), 1e-9,
                "前提: MockBukkit のプレイヤー最大体力は20(PVP_CAP の計算根拠)");
        double unsuppressed = unsuppressedTotal(dir, attacker, victim);

        EntityDamageByEntityEvent event = meleeHit(attacker, victim,
                Map.of(EntityDamageEvent.DamageModifier.ABSORPTION, ABSORPTION));
        listener(new File(dir, "pvp-on"), true).onEntityDamageByEntity(event);

        assertEquals(PVP_CAP, base(event), 1e-9,
                "BASE は上限ちょうど。1.5 なら抑制が二重に掛かっている(BASE まで係数を掛けた)");
        assertTrue(event.getFinalDamage() > 0.0,
                "吸収ハート持ちへの物理が0ダメージへ潰れてはならない(修正前は 3.0 + (-4.0) = -1.0)。実測 "
                        + event.getFinalDamage());
        assertEquals(ABSORPTION * (PVP_CAP / unsuppressed),
                damageOf(event, EntityDamageEvent.DamageModifier.ABSORPTION), 1e-9,
                "吸収は BASE と同じ抑制係数で縮む(割合としての軽減が抑制の前後で変わらない)");
    }

    @Test
    @DisplayName("PvP抑制下で盾を構えた相手を殴っても最終ダメージが0以下へ潰れない")
    void suppressedPhysicalHitDoesNotCollapseAgainstAShield(@TempDir File dir) throws IOException {
        Player attacker = swordsman();
        Player victim = server.addPlayer();
        double unsuppressed = unsuppressedTotal(dir, attacker, victim);

        EntityDamageByEntityEvent event = meleeHit(attacker, victim,
                Map.of(EntityDamageEvent.DamageModifier.BLOCKING, BLOCKING));
        listener(new File(dir, "pvp-on"), true).onEntityDamageByEntity(event);

        assertEquals(PVP_CAP, base(event), 1e-9, "BASE は上限ちょうど");
        assertTrue(event.getFinalDamage() > 0.0,
                "盾持ちへの物理が0ダメージへ潰れてはならない(修正前は 3.0 + (-6.0) = -3.0)。実測 "
                        + event.getFinalDamage());
        assertEquals(BLOCKING * (PVP_CAP / unsuppressed),
                damageOf(event, EntityDamageEvent.DamageModifier.BLOCKING), 1e-9,
                "盾も BASE と同じ抑制係数で縮む");
    }

    @Test
    @DisplayName("盾＋吸収の同時持ちでも最終ダメージが0以下へ潰れない")
    void suppressedPhysicalHitSurvivesShieldAndAbsorptionTogether(@TempDir File dir) throws IOException {
        Player attacker = swordsman();
        Player victim = server.addPlayer();
        unsuppressedTotal(dir, attacker, victim);

        Map<EntityDamageEvent.DamageModifier, Double> extras =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        extras.put(EntityDamageEvent.DamageModifier.BLOCKING, BLOCKING);
        extras.put(EntityDamageEvent.DamageModifier.ABSORPTION, ABSORPTION);
        EntityDamageByEntityEvent event = meleeHit(attacker, victim, extras);
        listener(new File(dir, "pvp-on"), true).onEntityDamageByEntity(event);

        assertEquals(PVP_CAP, base(event), 1e-9, "BASE は上限ちょうど");
        assertTrue(event.getFinalDamage() > 0.0,
                "修正前は 3.0 + (-6.0) + (-4.0) = -7.0 で完全無効だった。実測 " + event.getFinalDamage());
    }

    @Test
    @DisplayName("対モブ(PvEの大多数)では生き残りmodifierを一切触らない")
    void mobVictimKeepsItsVanillaModifiersUntouched(@TempDir File dir) throws IOException {
        Player attacker = swordsman();
        LivingEntity mob = world.spawn(world.getSpawnLocation(), Zombie.class);

        EntityDamageByEntityEvent event = meleeHit(attacker, mob,
                Map.of(EntityDamageEvent.DamageModifier.ABSORPTION, ABSORPTION));
        listener(new File(dir, "pvp-on"), true).onEntityDamageByEntity(event);

        assertEquals(ABSORPTION, damageOf(event, EntityDamageEvent.DamageModifier.ABSORPTION), 1e-9,
                "PvP抑制はplayer→playerだけ。モブ相手の吸収/盾のスケールを変えてはならない");
    }

    @Test
    @DisplayName("pvp.enabled=false なら生き残りmodifierも従来どおり素通し")
    void disabledPvpLeavesSurvivingModifiersUntouched(@TempDir File dir) throws IOException {
        Player attacker = swordsman();
        Player victim = server.addPlayer();

        EntityDamageByEntityEvent event = meleeHit(attacker, victim,
                Map.of(EntityDamageEvent.DamageModifier.ABSORPTION, ABSORPTION));
        listener(new File(dir, "pvp-off"), false).onEntityDamageByEntity(event);

        assertEquals(ABSORPTION, damageOf(event, EntityDamageEvent.DamageModifier.ABSORPTION), 1e-9,
                "抑制が掛からない構成では event を一切触らない(係数がちょうど1.0)");
        assertTrue(base(event) > PVP_CAP, "前提: 抑制が無いので BASE は上限を大きく超える");
    }

    @Test
    @DisplayName("折り込み済み ARMOR/MAGIC は抑制後も0のまま(再導出済みの軽減を二重に復活させない)")
    void foldedModifiersStayZeroAfterSuppression(@TempDir File dir) throws IOException {
        Player attacker = swordsman();
        Player victim = server.addPlayer();

        Map<EntityDamageEvent.DamageModifier, Double> extras =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        extras.put(EntityDamageEvent.DamageModifier.ARMOR, -2.0);
        extras.put(EntityDamageEvent.DamageModifier.MAGIC, -1.0);
        extras.put(EntityDamageEvent.DamageModifier.ABSORPTION, ABSORPTION);
        EntityDamageByEntityEvent event = meleeHit(attacker, victim, extras);
        listener(new File(dir, "pvp-on"), true).onEntityDamageByEntity(event);

        assertEquals(0.0, damageOf(event, EntityDamageEvent.DamageModifier.ARMOR), 1e-9,
                "ARMOR はパイプラインが再導出するので0のまま");
        assertEquals(0.0, damageOf(event, EntityDamageEvent.DamageModifier.MAGIC), 1e-9,
                "MAGIC(防護)はパイプラインが再導出するので0のまま");
        assertTrue(event.getFinalDamage() > 0.0, "実測 " + event.getFinalDamage());
    }
}
