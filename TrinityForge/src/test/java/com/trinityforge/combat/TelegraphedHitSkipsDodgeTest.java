package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.pdc.MobData;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 機構6 (2026-09-02 予告機構仕様「機構 6 — 予告技へ確率回避を通さない」): 予告付きの技(castSeconds > 0)は
 * 確率回避(dodge)を通さず、通常攻撃・予告なしの技は従来どおり回避が効くことを固定する。
 *
 * <p>victimの回避率は {@link MobData}(#hasProfile 経由。{@link PdcKeys#MOB_LEVEL} を刻んだ
 * {@link org.bukkit.persistence.PersistentDataHolder} は誰でも「プロファイル持ち」として扱われる、
 * COMBAT_SYSTEM_SPEC 6)を Player 自身のPDCへ直接刻むことで作る — {@link PlayerDefenseResolver} 経由の
 * アイテム/パーク集計を経由せず、回避率だけを確定値として与えられるため。
 */
class TelegraphedHitSkipsDodgeTest {

    private static final double BASE = 100.0;
    private static final SkillLevelSource NO_SKILLS = id -> Map.of();

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

    private SymmetricCombatService service(File dir, double maxDodgeChance) throws IOException {
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                  min-component-damage: 1.0
                magical:
                  base-coefficient: 1.0
                  min-component-damage: 1.0
                  scale-with-combat-level: false
                level-scaling:
                  per-level: 0.0
                defense:
                  max-dodge-chance: %s
                """.formatted(maxDodgeChance));
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        return new SymmetricCombatService(damage, cm.combatLevel(), cm.mobTypes(), NO_SKILLS, defense);
    }

    /** Stamps the player's own PDC with a MobData profile whose dodge chance is always-proc. */
    private static void stampAlwaysDodges(Player victim) {
        var pdc = victim.getPersistentDataContainer();
        pdc.set(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER, 0);
        pdc.set(PdcKeys.MOB_DODGE_CHANCE, PersistentDataType.DOUBLE, 1.0);
    }

    @Test
    void telegraphedHitAlwaysLandsDespiteFullDodgeChance(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir, 1.0);
        Zombie mobAttacker = world.spawn(world.getSpawnLocation(), Zombie.class);
        Player victim = server.addPlayer();
        stampAlwaysDodges(victim);

        for (int i = 0; i < 20; i++) {
            double damage = svc.physicalFinalDamageFromMob(mobAttacker, victim, BASE, AttackStats.plain(0), true);
            assertTrue(damage > 0.0,
                    "telegraphed hit must never be dodged even at max-dodge-chance=1.0 (got " + damage + ")");
        }
    }

    @Test
    void nonTelegraphedHitIsDodgedAtFullDodgeChance(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir, 1.0);
        Zombie mobAttacker = world.spawn(world.getSpawnLocation(), Zombie.class);
        Player victim = server.addPlayer();
        stampAlwaysDodges(victim);

        double damage = svc.physicalFinalDamageFromMob(mobAttacker, victim, BASE, AttackStats.plain(0), false);
        assertEquals(0.0, damage, "a non-telegraphed hit must still be fully dodgeable (回帰)");
    }

    @Test
    void existingFourArgOverloadBehavesLikeTelegraphedFalse_regression(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir, 1.0);
        Zombie mobAttacker = world.spawn(world.getSpawnLocation(), Zombie.class);
        Player victim = server.addPlayer();
        stampAlwaysDodges(victim);

        double legacyResult = svc.physicalFinalDamageFromMob(mobAttacker, victim, BASE, AttackStats.plain(0));
        double explicitFalseResult =
                svc.physicalFinalDamageFromMob(mobAttacker, victim, BASE, AttackStats.plain(0), false);

        assertEquals(explicitFalseResult, legacyResult,
                "the pre-existing 4-arg entry point must behave exactly like telegraphed=false");
        assertEquals(0.0, legacyResult, "and therefore must still be dodged at max-dodge-chance=1.0");
    }

    @Test
    void hybridTelegraphedHitAlwaysLandsDespiteFullDodgeChance(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir, 1.0);
        Zombie mobAttacker = world.spawn(world.getSpawnLocation(), Zombie.class);
        Player victim = server.addPlayer();
        stampAlwaysDodges(victim);

        AttackStats hybridAttack = AttackStats.plain(0).withMagicRatio(0.5);
        for (int i = 0; i < 20; i++) {
            var result = svc.physicalFinalDamageFromMobResult(
                    mobAttacker, victim, BASE, hybridAttack, false, true);
            assertTrue(result.damage() > 0.0,
                    "a telegraphed hybrid (magicRatio=0.5) hit must never be dodged (got " + result.damage() + ")");
        }
    }

    @Test
    void hybridNonTelegraphedHitIsDodgedAtFullDodgeChance(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir, 1.0);
        Zombie mobAttacker = world.spawn(world.getSpawnLocation(), Zombie.class);
        Player victim = server.addPlayer();
        stampAlwaysDodges(victim);

        AttackStats hybridAttack = AttackStats.plain(0).withMagicRatio(0.5);
        var result = svc.physicalFinalDamageFromMobResult(mobAttacker, victim, BASE, hybridAttack, false, false);
        assertEquals(0.0, result.damage(), "non-telegraphed hybrid hit must still be fully dodgeable (回帰)");
    }
}
