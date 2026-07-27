package com.trinityforge.config.domains;

import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Numeric ceiling coverage for {@link RoleBuffsConfig} (OPEN_DECISIONS C1b): {@code attack-buffs} /
 * {@code defense-buffs} used to be an unbounded admin-editable map, and {@code hate-threat-multiplier}
 * had a floor but no ceiling. Uses the same reflective fake {@link Plugin} pattern as
 * {@code CombatDamageConfigTest} (a headless test has no real server), pre-creating the file so
 * {@code saveResource} is never called.
 */
class RoleBuffsConfigTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("RoleBuffsConfigTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not be called when the file already exists on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static RoleBuffsConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, RoleBuffsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        RoleBuffsConfig config = new RoleBuffsConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void withinBoundsValuesPassThroughUnchanged(@TempDir File tempDir) throws IOException {
        RoleBuffsConfig config = loaded(tempDir, """
                combat-roles:
                  tank:
                    label: Tank
                    defense-buffs:
                      flat-defense: 40.0
                    hate-threat-multiplier: 1.5
                """);

        var tank = config.combatRole("tank");
        assertEquals(40.0, tank.defenseBuffs().get("flat_defense"));
        assertEquals(1.5, tank.hateThreatMultiplier());
    }

    @Test
    void flatDefenseIsClampedToTenTimesTheShippedDefault(@TempDir File tempDir) throws IOException {
        RoleBuffsConfig config = loaded(tempDir, """
                combat-roles:
                  tank:
                    label: Tank
                    defense-buffs:
                      flat-defense: 999999.0
                """);

        assertEquals(400.0, config.combatRole("tank").defenseBuffs().get("flat_defense"));
    }

    @Test
    void armorStrengthIsClampedAsRateNotFlat(@TempDir File tempDir) throws IOException {
        // 防具強度は会心軽減率%[0,1]へ役割変更したので、flat系(±400)ではなく割合系(±1.0)の上限で縛る。
        RoleBuffsConfig config = loaded(tempDir, """
                combat-roles:
                  tank:
                    label: Tank
                    defense-buffs:
                      armor-strength: -999999.0
                """);

        assertEquals(-1.0, config.combatRole("tank").defenseBuffs().get("armor_strength"));
    }

    @Test
    void percentBonusDamageIsClampedToOneHundredPercent(@TempDir File tempDir) throws IOException {
        // 150.0 は PercentStatNormalize.coerce の対象範囲(|v|<=100)外なので正規化されず、
        // クランプだけが働く経路を検証する(50.0だとCMB-15正規化で0.5になり別の意味のテストになるため)。
        RoleBuffsConfig config = loaded(tempDir, """
                combat-roles:
                  swordfighter:
                    label: Swordfighter
                    attack-buffs:
                      percent-bonus-damage: 150.0
                """);

        assertEquals(1.0, config.combatRole("swordfighter").attackBuffs().get("percent_bonus_damage"));
    }

    @Test
    void cmb15PenetrationTwentyIsNormalizedToTwentyPercentNotFullyPierced(@TempDir File tempDir)
            throws IOException {
        // CMB-15: penetration: 20 (20%のつもり) が正規化されず生値20として渡ると貫通が飽和し
        // 防御ステが完全に無意味になる。PercentStatNormalizeで0.20へ矯正されることを検証する。
        RoleBuffsConfig config = loaded(tempDir, """
                combat-roles:
                  swordfighter:
                    label: Swordfighter
                    attack-buffs:
                      penetration: 20
                """);

        assertEquals(0.20, config.combatRole("swordfighter").attackBuffs().get("penetration"));
    }

    @Test
    void cmb15AlreadyFractionalRateKeyIsUnchanged(@TempDir File tempDir) throws IOException {
        RoleBuffsConfig config = loaded(tempDir, """
                combat-roles:
                  swordfighter:
                    label: Swordfighter
                    attack-buffs:
                      penetration: 0.15
                """);

        assertEquals(0.15, config.combatRole("swordfighter").attackBuffs().get("penetration"));
    }

    @Test
    void cmb15FlatKeyOutsideRateKeysIsUnaffectedByNormalization(@TempDir File tempDir) throws IOException {
        RoleBuffsConfig config = loaded(tempDir, """
                combat-roles:
                  tank:
                    label: Tank
                    defense-buffs:
                      flat-defense: 20.0
                """);

        assertEquals(20.0, config.combatRole("tank").defenseBuffs().get("flat_defense"));
    }

    @Test
    void damageReductionIsClampedToOneHundredPercentEitherSign(@TempDir File tempDir) throws IOException {
        RoleBuffsConfig config = loaded(tempDir, """
                combat-roles:
                  tank:
                    label: Tank
                    defense-buffs:
                      damage-reduction: 999.0
                """);

        assertEquals(1.0, config.combatRole("tank").defenseBuffs().get("damage_reduction"));
    }

    @Test
    void hateThreatMultiplierIsClampedToTenTimesTheShippedDefault(@TempDir File tempDir) throws IOException {
        RoleBuffsConfig config = loaded(tempDir, """
                combat-roles:
                  tank:
                    label: Tank
                    hate-threat-multiplier: 999.0
                """);

        assertEquals(15.0, config.combatRole("tank").hateThreatMultiplier());
    }

    @Test
    void unknownFutureKeyWithPercentNamingConventionIsClampedAsPercentScale(@TempDir File tempDir)
            throws IOException {
        RoleBuffsConfig config = loaded(tempDir, """
                combat-roles:
                  tank:
                    label: Tank
                    defense-buffs:
                      dodge-chance: 999.0
                """);

        assertEquals(1.0, config.combatRole("tank").defenseBuffs().get("dodge_chance"));
    }

    @Test
    void unknownFutureKeyWithoutPercentNamingConventionFallsBackToFlatScaleClamp(@TempDir File tempDir)
            throws IOException {
        RoleBuffsConfig config = loaded(tempDir, """
                combat-roles:
                  tank:
                    label: Tank
                    defense-buffs:
                      some-future-flat-stat: 999999.0
                """);

        assertEquals(400.0, config.combatRole("tank").defenseBuffs().get("some_future_flat_stat"));
    }

    @Test
    void critDamageIsClampedAsMultiplierNotFlat(@TempDir File tempDir) throws IOException {
        RoleBuffsConfig config = loaded(tempDir, """
                combat-roles:
                  swordfighter:
                    label: Swordfighter
                    attack-buffs:
                      crit-damage: 999.0
                """);

        assertEquals(2.0, config.combatRole("swordfighter").attackBuffs().get("crit_damage"));
    }

    @Test
    void damageModifierIsClampedAsMultiplierNotFlat(@TempDir File tempDir) throws IOException {
        RoleBuffsConfig config = loaded(tempDir, """
                combat-roles:
                  swordfighter:
                    label: Swordfighter
                    attack-buffs:
                      damage-modifier: 400.0
                """);

        assertEquals(2.0, config.combatRole("swordfighter").attackBuffs().get("damage_modifier"));
    }

    @Test
    void hateThreatMultiplierNegativeIsFlooredAtZero(@TempDir File tempDir) throws IOException {
        RoleBuffsConfig config = loaded(tempDir, """
                combat-roles:
                  tank:
                    label: Tank
                    hate-threat-multiplier: -999.0
                """);

        assertEquals(0.0, config.combatRole("tank").hateThreatMultiplier());
    }

    @Test
    void supportPotionBuffResolvesLegacyJumpAliasToJumpBoost(@TempDir File tempDir) throws IOException {
        RoleBuffsConfig config = loaded(tempDir, """
                support-roles:
                  farmer:
                    label: 農夫
                    exp-skill: FARMING
                    potion-buff: { type: JUMP, duration: 999999, amplifier: 0 }
                """);

        var potionBuff = config.supportRole("farmer").potionBuff();
        assertNotNull(potionBuff, "legacy JUMP alias must still resolve a potion buff");
        assertEquals(PotionEffectType.JUMP_BOOST, potionBuff.type());
    }

    @Test
    void supportExpMultiplierIsCappedAtTen(@TempDir File tempDir) throws IOException {
        RoleBuffsConfig config = loaded(tempDir, """
                support-roles:
                  miner:
                    label: Miner
                    exp-skill: MINING
                    exp-multiplier: 1000000.0
                """);

        assertEquals(10.0, config.supportRole("miner").expMultiplier());
    }
}
