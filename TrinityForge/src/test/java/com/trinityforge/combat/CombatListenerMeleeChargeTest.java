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

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B2 (2026-07-25 バグ報告 / 2026-07-25 レビュー修正): 近接チャージ減衰の {@link CombatListener} 統合テスト。
 *
 * <p>アイテムは {@code attack-power: 1.0} (defaultDamage を非0にして {@code
 * ComponentInput#isActive()} を満たす最小値) + {@code fixed-damage: 9.0} + {@code damage-modifier: 1.0}
 * (ロール分散を排して決定的に), 被害者は無装備(防御0)、{@code physical.min-component-damage: 0}。
 * これで防御計算は素通り(0)になり、フルチャージ時の最終ダメージが常に {@code 1.0 + 9.0 = 10.0} という
 * 固定値になる。この10.0のうちfixed-damage由来の9.0が「防御を貫通するが、チャージが低いときは
 * defaultDamage側と同様に削られる」ことを、下記 t=0 のテストが直接証明する
 * (fixed-damageだけ乗算を免除する設計だったら 1.0*0.2+9.0=9.2 になるはずだが、実装は
 * (1.0+9.0)*0.2=2.0 になる — 全体乗算の証拠)。
 *
 * <p><b>レビュー修正(HIGH指摘1・3): {@code Player#getAttackCooldown()} のスタブには一切依存しない。</b>
 * チャージ状態は {@code server.getScheduler().performTicks(n)} で実際にサーバtickを進め、
 * {@link CombatListener} が内部で使う {@link MeleeChargeTracker} の「前回近接攻撃tick」記録を
 * 実イベント連打で駆動する——つまり「Bukkitが実際に何を返すか」ではなく「TFの実装が実イベントの
 * 連なりに対して正しく振る舞うか」を検証する(スタブした値がそのまま使われるだけのテストではない)。
 * 攻撃速度は {@code Attribute.ATTACK_SPEED} をスタブして、tick数から厳密な期待値を導出できるようにする
 * (attackSpeed=1.0 -> フルチャージ=20tick)。
 */
class CombatListenerMeleeChargeTest {

    private ServerMock server;
    private WorldMock world;
    private CombatListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        // total > 0 in every test here, so CombatListener.expAllowedInWorld() is reached, which reads
        // TrinityForge.getInstance().dungeonWorldRegistry() (dungeon-only-exp defaults true). Stub the
        // singleton like CombatListenerProjectileIntegrationTest / RangedUseRequirementConsumptionTest do.
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.dungeonWorldRegistry()).thenReturn(new DungeonWorldRegistry());
        TrinityForgeSingletonTestSupport.set(tf);
    }

    @AfterEach
    void tearDown() {
        TrinityForgeSingletonTestSupport.clear();
        MockBukkit.unmock();
    }

    private CombatListener listener(File dir, String damageYaml) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-power: 1.0, fixed-damage: 9.0, damage-modifier: 1.0 }
                  BOW:
                    fixed: { attack-power: 1.0, fixed-damage: 9.0, damage-modifier: 1.0 }
                  WOODEN_AXE:
                    fixed: { fixed-damage: 9.0, damage-modifier: 1.0 }
                """);

        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, damageYaml);
        Plugin plugin = MockBukkit.createMockPlugin();
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        SymmetricCombatService svc = new SymmetricCombatService(
                damage, cm.combatLevel(), cm.mobTypes(), SkillLevelSource.EMPTY, defense);
        BleedService bleed = new BleedService(plugin, svc, damage);
        return new CombatListener(plugin, svc, cm.itemStats(),
                damage, SkillLevelSource.EMPTY, bleed, perks, aggregator,
                cm.useRequirements(), cm.skillExp(), cm.craftingFeatures(),
                new RoleBuffResolver(cm.roleBuffs()));
    }

    private static final String DAMAGE_YAML_ENABLED = """
            physical:
              base-coefficient: 1.0
              min-component-damage: 0.0
            melee-charge:
              enabled: true
              min-multiplier: 0.2
              exponent: 2.0
            """;

    private static final String DAMAGE_YAML_DISABLED = """
            physical:
              base-coefficient: 1.0
              min-component-damage: 0.0
            melee-charge:
              enabled: false
              min-multiplier: 0.2
              exponent: 2.0
            """;

    private EntityDamageByEntityEvent meleeHit(Player attacker, Player victim) {
        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        return new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 6.0);
    }

    /**
     * 攻撃速度1.0(=フルチャージに20tick)にスタブしたプレイヤー。tick数からチャージ進捗を厳密に
     * 導出できるようにするため、バニラ既定の4.0ではなくキリの良い1.0を使う。
     */
    private static Player attackerWithUnitAttackSpeed(ServerMock server, Material weapon) {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(weapon));
        // MockBukkit does not auto-register every vanilla attribute on a fresh mock entity; ATTACK_SPEED
        // must be registered explicitly before getAttribute() returns a non-null instance.
        player.registerAttribute(Attribute.ATTACK_SPEED);
        AttributeInstance attackSpeed = player.getAttribute(Attribute.ATTACK_SPEED);
        attackSpeed.setBaseValue(1.0);
        return player;
    }

    @Test
    void firstAttackEverIsFullyChargedByDefault(@TempDir File dir) throws IOException {
        listener = listener(dir, DAMAGE_YAML_ENABLED);
        Player attacker = attackerWithUnitAttackSpeed(server, Material.DIAMOND_SWORD);
        Player victim = server.addPlayer();

        // このセッションで初回の近接攻撃 -> MeleeChargeTracker に記録が無い -> フルチャージ扱い。
        EntityDamageByEntityEvent event = meleeHit(attacker, victim);
        listener.onEntityDamageByEntity(event);

        assertEquals(10.0, event.getDamage(), 1e-6, "first-ever attack must deal full (unreduced) damage");
    }

    @Test
    void immediateFollowUpAttackDealsMinMultiplierFractionOfTotalIncludingFixedDamage(
            @TempDir File dir) throws IOException {
        listener = listener(dir, DAMAGE_YAML_ENABLED);
        Player attacker = attackerWithUnitAttackSpeed(server, Material.DIAMOND_SWORD);
        Player victim = server.addPlayer();

        // 1発目でチャージを消費(この結果自体は問わない)、直後(同tick, elapsed=0)に2発目 -> t=0 -> 0.2倍。
        listener.onEntityDamageByEntity(meleeHit(attacker, victim));
        EntityDamageByEntityEvent second = meleeHit(attacker, victim);
        listener.onEntityDamageByEntity(second);

        // (1.0 base + 9.0 fixed-damage) * 0.2 (min-multiplier at t=0) == 2.0.
        // If fixed-damage were exempt from the charge decay, this would instead be 1.0*0.2 + 9.0 = 9.2.
        // 2.0 is the direct proof fixed-damage is NOT exempt (B2 design decision).
        assertEquals(2.0, second.getDamage(), 1e-6,
                "an immediate follow-up (elapsed=0) must scale the WHOLE final total, "
                        + "including fixed-damage, down to the 0.2 floor");
    }

    @Test
    void midChargeAfterElapsedTicksMatchesVanillaFormula(@TempDir File dir) throws IOException {
        listener = listener(dir, DAMAGE_YAML_ENABLED);
        Player attacker = attackerWithUnitAttackSpeed(server, Material.DIAMOND_SWORD);
        Player victim = server.addPlayer();

        listener.onEntityDamageByEntity(meleeHit(attacker, victim));
        // attackSpeed=1.0 -> フルチャージ=20tick。10tick経過 -> t=0.5。
        server.getScheduler().performTicks(10);
        EntityDamageByEntityEvent second = meleeHit(attacker, victim);
        listener.onEntityDamageByEntity(second);

        // 10.0 * (0.2 + 0.5^2*0.8) == 10.0 * 0.4 == 4.0.
        assertEquals(4.0, second.getDamage(), 1e-6);
    }

    @Test
    void weaponSwapDoesNotResetTheChargeExploit(@TempDir File dir) throws IOException {
        listener = listener(dir, DAMAGE_YAML_ENABLED);
        Player attacker = attackerWithUnitAttackSpeed(server, Material.DIAMOND_SWORD);
        Player victim = server.addPlayer();

        listener.onEntityDamageByEntity(meleeHit(attacker, victim));
        // #11552 の持ち替えexploit回避確認: 同tickで武器を持ち替えても、記録は攻撃者UUID単位であって
        // 武器/マテリアルに依存しないため、経過tickは変わらずelapsed=0のまま(min-multiplier)。
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.WOODEN_AXE));
        EntityDamageByEntityEvent second = meleeHit(attacker, victim);
        listener.onEntityDamageByEntity(second);

        // WOODEN_AXE には attack-power が無い(tfBaseReplaces=false)ため、T3によりTFの減衰は掛からず
        // event.getDamage()の生値(vanillaBaseDamage=6.0)+fixed-damage(9.0)*damage-modifier(1.0)の
        // パイプライン結果がそのまま出る(このケースでは減衰なし=15.0)。持ち替え直後の記録継続性自体は
        // 次のテスト(itemWithoutAttackPowerSkipsTfDecay系)ではなく、ここでは「記録がリセットされない」
        // ことの確認が主目的。attack-power付き武器へ戻すと即座に減衰が反映されることを別途確認する。
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        EntityDamageByEntityEvent third = meleeHit(attacker, victim);
        listener.onEntityDamageByEntity(third);

        // 武器を戻しても経過tickは0のまま(持ち替えでチャージが回復していない) -> 0.2倍のまま。
        assertEquals(2.0, third.getDamage(), 1e-6,
                "swapping weapons mid-combo must NOT reset/refresh the melee-charge tracker (closes #11552-class exploit)");
    }

    @Test
    void itemWithoutAttackPowerNeverGetsTfDecayAppliedTwice(@TempDir File dir) throws IOException {
        listener = listener(dir, DAMAGE_YAML_ENABLED);
        Player attacker = attackerWithUnitAttackSpeed(server, Material.WOODEN_AXE);
        Player victim = server.addPlayer();

        // WOODEN_AXE has no attack-power in item-stats.yml above -> tfBaseReplaces=false ->
        // baseDamage=vanillaBaseDamage(6.0, already vanilla-decayed) -> TF must NOT apply its own
        // melee-charge multiplier again (T3, avoids double decay). Immediate follow-up (elapsed=0) would
        // otherwise floor it to 0.2x if the bug were present.
        listener.onEntityDamageByEntity(meleeHit(attacker, victim));
        EntityDamageByEntityEvent second = meleeHit(attacker, victim);
        listener.onEntityDamageByEntity(second);

        // pipeline: vanillaBaseDamage(6.0) + fixed-damage(9.0), damage-modifier=1.0 -> 15.0, undecayed.
        assertEquals(15.0, second.getDamage(), 1e-6,
                "attack-power-less items must never have TF's melee-charge decay applied "
                        + "(vanilla already decayed event.getDamage())");
    }

    @Test
    void toggleOffIgnoresChargeEvenImmediately(@TempDir File dir) throws IOException {
        listener = listener(dir, DAMAGE_YAML_DISABLED);
        Player attacker = attackerWithUnitAttackSpeed(server, Material.DIAMOND_SWORD);
        Player victim = server.addPlayer();

        listener.onEntityDamageByEntity(meleeHit(attacker, victim));
        EntityDamageByEntityEvent second = meleeHit(attacker, victim);
        listener.onEntityDamageByEntity(second);

        assertEquals(10.0, second.getDamage(), 1e-6, "melee-charge.enabled=false must never reduce damage");
    }

    @Test
    void playerQuitForgetsTrackedChargeState(@TempDir File dir) throws IOException {
        listener = listener(dir, DAMAGE_YAML_ENABLED);
        Player attacker = attackerWithUnitAttackSpeed(server, Material.DIAMOND_SWORD);
        Player victim = server.addPlayer();

        listener.onEntityDamageByEntity(meleeHit(attacker, victim));
        listener.onPlayerQuit(new PlayerQuitEvent(attacker, "quit"));

        // 記録が破棄されたので、同じUUIDでの次の攻撃は「初回」扱い(=フルチャージ)に戻る。
        EntityDamageByEntityEvent afterRejoin = meleeHit(attacker, victim);
        listener.onEntityDamageByEntity(afterRejoin);

        assertEquals(10.0, afterRejoin.getDamage(), 1e-6,
                "a quit must clear the tracked melee-charge state so a rejoin starts fully charged");
    }

    @Test
    void rangedShotIsNeverAffectedByChargeEvenImmediatelyAfterAMeleeHit(@TempDir File dir) throws IOException {
        listener = listener(dir, DAMAGE_YAML_ENABLED);
        Player shooter = attackerWithUnitAttackSpeed(server, Material.DIAMOND_SWORD);
        Player meleeVictim = server.addPlayer();
        // 直前に近接攻撃してチャージを最低に落としておく(このプレイヤーのトラッカー状態を「未チャージ」にする)。
        listener.onEntityDamageByEntity(meleeHit(shooter, meleeVictim));

        ItemStack bow = new ItemStack(Material.BOW);
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);
        listener.onEntityShootBow(new EntityShootBowEvent(shooter, bow, arrow, 1.0f));
        arrow.setShooter(shooter);

        Player victim = server.addPlayer();
        DamageSource source = DamageSource.builder(DamageType.ARROW)
                .withCausingEntity(shooter).withDirectEntity(arrow).build();
        EntityDamageByEntityEvent hit = new EntityDamageByEntityEvent(
                arrow, victim, EntityDamageEvent.DamageCause.PROJECTILE, source, 6.0);

        listener.onEntityDamageByEntity(hit);

        assertEquals(10.0, hit.getDamage(), 1e-6,
                "a projectile hit must never be reduced by the shooter's melee-charge state, "
                        + "even immediately after the same player's undercharged melee swing");
    }
}
