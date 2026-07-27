package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;
import com.trinityforge.stats.StatKeys;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 課題1(魔法出血)の回帰テスト: {@link BleedService#maybeApplyFromAggregate} が近接
 * ({@code CombatListener#maybeApplyBleed}) と同じ判定規則を、魔法経路向けの独立エントリポイントとして
 * 正しく満たすことを検証する。ArsPaperフォーク({@code TrinityForgeBridge})はこのメソッドへ
 * 委譲するだけで確率ロジックを持たない設計のため、判定の正しさはここTF側のテストが唯一の保証。
 */
class BleedServiceMagicAggregateTest {

    private static final UUID ATTACKER = UUID.randomUUID();

    private ServerMock server;
    private WorldMock world;
    private BleedService bleedService;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * CombatListenerNegativeDamageHealTest と同じ実configワイヤリング(CombatWiringSupport)で
     * SymmetricCombatService/BleedServiceを構築する。maybeApplyFromAggregateの判定経路
     * (chance/damageの読み取りと乱数判定・apply呼出し)自体はSymmetricCombatServiceを一切呼ばないが、
     * BleedServiceのコンストラクタが要求するため実体を渡す。
     */
    private BleedService bleedService(File dir) throws IOException {
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                """);
        Plugin plugin = MockBukkit.createMockPlugin();
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        SymmetricCombatService svc = new SymmetricCombatService(
                damage, cm.combatLevel(), cm.mobTypes(), SkillLevelSource.EMPTY, defense);
        return new BleedService(plugin, svc, damage);
    }

    private Map<String, Double> aggregateWith(double chance, double damage) {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put(StatKeys.canonical("bleed-chance"), chance);
        stats.put(StatKeys.canonical("bleed-damage"), damage);
        return stats;
    }

    @Test
    void magicHitWithBleedChanceStartsBleedViaAggregate(@TempDir File dir) throws IOException {
        bleedService = bleedService(dir);
        LivingEntity victim = world.spawn(world.getSpawnLocation(), Zombie.class);

        bleedService.maybeApplyFromAggregate(aggregateWith(1.0, 3.0), victim, ATTACKER, 5.0);

        assertEquals(1, bleedService.activeCount(),
                "bleed-chance=1.0 の魔法集約を渡したら BleedService.apply が呼ばれ、出血が開始するはず");
    }

    @Test
    void nonPositiveFinalDamageNeverRolls(@TempDir File dir) throws IOException {
        bleedService = bleedService(dir);
        LivingEntity victim = world.spawn(world.getSpawnLocation(), Zombie.class);

        bleedService.maybeApplyFromAggregate(aggregateWith(1.0, 3.0), victim, ATTACKER, 0.0);
        assertEquals(0, bleedService.activeCount(), "finalDamage=0(回復含む)では絶対にロールしない");

        bleedService.maybeApplyFromAggregate(aggregateWith(1.0, 3.0), victim, ATTACKER, -2.0);
        assertEquals(0, bleedService.activeCount(), "finalDamageが負(回復)でも絶対にロールしない");
    }

    @Test
    void missingBleedChanceOrDamageNeverRolls(@TempDir File dir) throws IOException {
        bleedService = bleedService(dir);
        LivingEntity victim = world.spawn(world.getSpawnLocation(), Zombie.class);

        bleedService.maybeApplyFromAggregate(aggregateWith(0.0, 3.0), victim, ATTACKER, 5.0);
        assertEquals(0, bleedService.activeCount(), "bleed-chance=0では絶対にロールしない");

        bleedService.maybeApplyFromAggregate(aggregateWith(1.0, 0.0), victim, ATTACKER, 5.0);
        assertEquals(0, bleedService.activeCount(), "bleed-damage=0では絶対にロールしない");
    }

    @Test
    void emptyAggregateNeverRolls(@TempDir File dir) throws IOException {
        // #3全ステ合算のCombatListener#maybeApplyBleedと違い、このAPIは呼び出し側(フォーク)が渡した
        // マップだけを読む — メインハンド専用ステを自分で取得しに行く経路が存在しないことをAPI形状
        // (Map引数を額面通り読むだけ)で保証する。攻撃集約に bleed-chance が無ければ(=メインハンド由来
        // ステが混入していなければ)、他に何があってもロールされない。
        bleedService = bleedService(dir);
        LivingEntity victim = world.spawn(world.getSpawnLocation(), Zombie.class);

        bleedService.maybeApplyFromAggregate(Map.of(), victim, ATTACKER, 5.0);

        assertEquals(0, bleedService.activeCount(), "空集約(=メインハンド由来ステ無し)ではロールしない");
    }
}
