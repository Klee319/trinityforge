package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.pdc.MobData;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code attack.magic-ratio}(2026-08-02, 実装1): a mob's ordinary attack can be resolved as physical,
 * magical, or a physical/magical hybrid split. Locks in the three explicit completion-criteria
 * behaviours:
 * <ol>
 *   <li>未設定(magic-ratio=0, 既定) = 従来どおり完全物理・挙動不変({@link #unsetRatioMatchesPreExistingPhysicalOnlyBehaviour}).</li>
 *   <li>比率どおりに魔法防御が効く({@link #fullMagicRatioIgnoresPhysicalOnlyDefenseAndUsesMagicalDefense}).</li>
 *   <li>物理防御は(比率が0でない限り)フルには効かない、中間比率は両方が部分適用される
 *       ({@link #hybridRatioSplitsMitigationBetweenBothDefenseAxes}).</li>
 * </ol>
 *
 * <p>The victim wears an item that grants {@code phys-flat-defense} ONLY (no {@code magic-flat-defense}
 * — see {@link DefenseStatKeys}), so a magical component is guaranteed to receive zero mitigation from
 * it while a physical component is guaranteed to receive the full amount. This isolates the "victim's
 * physical armor must not silently bleed through onto a magic attacker" invariant called out in
 * {@code docs/agent-context/combat.md}.
 */
class SymmetricCombatServiceMagicRatioTest {

    private static final double VANILLA_BASE = 0.0; // unused: attack-power (200) replaces it outright.
    private static final double ATTACK_POWER = 200.0;
    private static final double PHYS_FLAT_DEFENSE = 150.0;
    private static final double EPS = 1e-9;

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

    private SymmetricCombatService service(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        // 胸当ては phys-flat-defense のみ(magic-flat-defense は無し) — 物理コンポーネントだけを
        // 確実に軽減し、魔法コンポーネントには一切効かないことを保証するための装備。
        Files.writeString(itemStats.toPath(), """
                items:
                  DIAMOND_CHESTPLATE:
                    fixed: { phys-flat-defense: %s }
                """.formatted(PHYS_FLAT_DEFENSE));
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
                  per-level: 0.05
                early-level-attack:
                  enabled: false
                """);
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        return new SymmetricCombatService(damage, cm.combatLevel(), cm.mobTypes(), NO_SKILLS, defense);
    }

    private Zombie plainMob() {
        // 素の(PDCスタンプ無し)モブ: MobData#hasProfile()==false のため defenseFor 経由には乗らず、
        // magic-ratio だけが AttackStats 経由でこのテストの対象になる。
        return world.spawn(world.getSpawnLocation(), Zombie.class);
    }

    private Player armoredVictim() {
        Player player = server.addPlayer();
        player.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        return player;
    }

    @Test
    void unsetRatioMatchesPreExistingPhysicalOnlyBehaviour(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir);
        Player victim = armoredVictim();
        Zombie mob = plainMob();

        // 旧8引数コンストラクタ(実装1着手前からの全既存呼び出し形)は magicRatio=0.0 に委譲される。
        AttackStats legacyShape = new AttackStats(ATTACK_POWER, 0, 0, 0, 0, 0, 1, 0);
        AttackStats explicitZeroRatio = AttackStats.plain(ATTACK_POWER); // 明示的に magicRatio=0.0 の9引数版

        double fromLegacyCtor = svc.physicalFinalDamageFromMob(mob, victim, VANILLA_BASE, legacyShape);
        double fromExplicitZero = svc.physicalFinalDamageFromMob(mob, victim, VANILLA_BASE, explicitZeroRatio);

        // 200 - 150(phys-flat-defense) = 50、他の防御軸(defenseRate/resistance/damageReduction)は0なので
        // そのまま50が最終値になる: これは「実装1以前と同じ完全物理の結果」がそのまま出ることの固定。
        assertEquals(ATTACK_POWER - PHYS_FLAT_DEFENSE, fromLegacyCtor, EPS,
                "magic-ratio 無指定(旧8引数コンストラクタ)は完全物理のまま: 200-150=50");
        assertEquals(fromLegacyCtor, fromExplicitZero, EPS,
                "旧8引数コンストラクタと明示的な magicRatio=0.0 は同一の結果になること(後方互換の固定)");
    }

    @Test
    void fullMagicRatioIgnoresPhysicalOnlyDefenseAndUsesMagicalDefense(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir);
        Player victim = armoredVictim();
        Zombie mob = plainMob();

        AttackStats fullPhysical = AttackStats.plain(ATTACK_POWER); // magicRatio=0.0
        AttackStats fullMagical = AttackStats.plain(ATTACK_POWER).withMagicRatio(1.0);

        double physicalResult = svc.physicalFinalDamageFromMob(mob, victim, VANILLA_BASE, fullPhysical);
        double magicalResult = svc.physicalFinalDamageFromMob(mob, victim, VANILLA_BASE, fullMagical);

        // 物理: 200-150=50。魔法: 胸当ての phys-flat-defense は魔法コンポーネントに一切乗らないため
        // 軽減ゼロのまま200がそのまま通る — 「魔法モブなのに物理防具で受けられる」の反対(正しい)挙動。
        assertEquals(ATTACK_POWER - PHYS_FLAT_DEFENSE, physicalResult, EPS);
        assertEquals(ATTACK_POWER, magicalResult, EPS,
                "magic-ratio=1.0 のモブ攻撃には物理専用装備(phys-flat-defense)の軽減が一切効かないこと");
        assertTrue(magicalResult > physicalResult,
                "同じ攻撃力でも、物理防具しか持たない防御側は魔法攻撃(" + magicalResult
                        + ")の方が物理攻撃(" + physicalResult + ")より多く食らうこと");
    }

    @Test
    void hybridRatioSplitsMitigationBetweenBothDefenseAxes(@TempDir File dir) throws IOException {
        SymmetricCombatService svc = service(dir);
        Player victim = armoredVictim();
        Zombie mob = plainMob();

        AttackStats hybrid = AttackStats.plain(ATTACK_POWER).withMagicRatio(0.5);
        double hybridResult = svc.physicalFinalDamageFromMob(mob, victim, VANILLA_BASE, hybrid);

        // 物理半分(100) - 150(phys-flat-defense) = -50 -> min-component-damage(1.0)の床に張り付く。
        // 魔法半分(100) - 0(magic-flat-defenseは未設定) = 100。合計 = 1.0 + 100.0 = 101.0。
        double expectedPhysicalHalf = 1.0; // floor
        double expectedMagicalHalf = 100.0;
        assertEquals(expectedPhysicalHalf + expectedMagicalHalf, hybridResult, EPS,
                "hybrid(0.5)は物理半分(床に張り付く)+魔法半分(無軽減)の合計になること");

        double physicalOnly = svc.physicalFinalDamageFromMob(mob, victim, VANILLA_BASE,
                AttackStats.plain(ATTACK_POWER));
        double magicalOnly = svc.physicalFinalDamageFromMob(mob, victim, VANILLA_BASE,
                AttackStats.plain(ATTACK_POWER).withMagicRatio(1.0));
        assertTrue(hybridResult > physicalOnly,
                "hybridは完全物理より重い(魔法半分が丸ごと通るため): " + hybridResult + " > " + physicalOnly);
        assertTrue(hybridResult < magicalOnly,
                "hybridは完全魔法より軽い(物理半分は防具で床まで削られるため): " + hybridResult + " < " + magicalOnly);
    }

    @Test
    void magicRatioIsUnscaledByMobLevel_typeClassifierNotMagnitude(@TempDir File dir) throws IOException {
        // MobStatScaling#scaleAttack は magicRatio をレベル係数の対象外にする(「型」であって「量」では
        // ない) — PDC 経由でスタンプされた magic-ratio がモブレベルで変質しないことをフルパイプラインで固定。
        SymmetricCombatService svc = service(dir);
        Player victim = armoredVictim();
        Zombie highLevelMob = plainMob();
        MobData.stamp(highLevelMob, 40, DefenseStats.NONE, DefenseStats.NONE);

        double magicalResult = svc.physicalFinalDamageFromMob(highLevelMob, victim, VANILLA_BASE,
                AttackStats.plain(ATTACK_POWER).withMagicRatio(1.0));

        // attack-power(200) がスタンプ済みなので、モブレベルに関わらずベースは置き換えられ、
        // 依然として phys-flat-defense の影響を受けない(=200のまま)。
        assertEquals(ATTACK_POWER, magicalResult, EPS);
    }
}
