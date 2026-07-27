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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
 * 2026-07-25バグ修正(#1/#2): MACEはitem-stats.ymlに{@code attack-power}を持つため
 * {@link CombatListener}のtfBaseReplacesパスに入り、バニラのスマッシュ攻撃(落下攻撃)加算と
 * Density(重撃)エンチャントの加算が {@code vanillaBaseDamage} ごと丸ごと捨てられていた。
 * 固定 {@code attack-power}のみ(他stat無し, damage-modifier=1.0相当のneutral, min-component-damage=0)
 * のシンプルな構成にして、加算値がそのまま最終ダメージへ現れることを直接検証する。
 */
class CombatListenerMaceSmashIntegrationTest {

    private static final double MACE_ATTACK_POWER = 100.0;

    private ServerMock server;
    private WorldMock world;
    private CombatListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.dungeonWorldRegistry()).thenReturn(new DungeonWorldRegistry());
        TrinityForgeSingletonTestSupport.set(tf);
    }

    @AfterEach
    void tearDown() {
        TrinityForgeSingletonTestSupport.clear();
        MockBukkit.unmock();
    }

    private CombatListener listener(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  MACE:
                    fixed: { attack-power: %s }
                """.formatted(MACE_ATTACK_POWER));

        // base-coefficient 1.0, no floor, melee-charge disabled (isolate the smash-bonus math from
        // the unrelated combo-decay mechanic — both are keyed off the same tfBaseReplaces flag).
        String damageYaml = """
                physical:
                  base-coefficient: 1.0
                  min-component-damage: 0.0
                melee-charge:
                  enabled: false
                """;
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

    /**
     * #4 2026-07-25バグ修正: {@link #listener(File)} と異なり近接チャージ減衰(B2)を有効化した構成。
     * スマッシュ判定(maceSmashActive)がこの減衰を正しく無効化するかを、攻撃速度=1.0(フルチャージ=20tick)
     * にスタブして直接検証するために使う({@link CombatListenerMeleeChargeTest}と同じ流儀)。
     */
    private CombatListener listenerWithChargeEnabled(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  MACE:
                    fixed: { attack-power: %s }
                """.formatted(MACE_ATTACK_POWER));

        String damageYaml = """
                physical:
                  base-coefficient: 1.0
                  min-component-damage: 0.0
                melee-charge:
                  enabled: true
                  min-multiplier: 0.2
                  exponent: 2.0
                """;
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

    /** 攻撃速度1.0(フルチャージ=20tick)にスタブしたMACE装備プレイヤー。 */
    private static PlayerMock maceWielderWithUnitAttackSpeed(ServerMock server) {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.MACE));
        // MockBukkit does not auto-register every vanilla attribute on a fresh mock entity; ATTACK_SPEED
        // must be registered explicitly before getAttribute() returns a non-null instance.
        player.registerAttribute(Attribute.ATTACK_SPEED);
        AttributeInstance attackSpeed = player.getAttribute(Attribute.ATTACK_SPEED);
        attackSpeed.setBaseValue(1.0);
        return player;
    }

    private EntityDamageByEntityEvent maceHit(Player attacker, Player victim) {
        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        return new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 6.0);
    }

    private static PlayerMock maceWielder(ServerMock server, ItemStack mace) {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(mace);
        player.setOnGround(true);
        return player;
    }

    @Test
    void groundedMaceHitGetsNoSmashBonus(@TempDir File dir) throws IOException {
        listener = listener(dir);
        PlayerMock attacker = maceWielder(server, new ItemStack(Material.MACE));
        Player victim = server.addPlayer();

        EntityDamageByEntityEvent event = maceHit(attacker, victim);
        listener.onEntityDamageByEntity(event);

        assertEquals(MACE_ATTACK_POWER, event.getDamage(), 1e-6,
                "a grounded (non-smash) mace hit must equal the bare weapon attack-power, no bonus");
    }

    @Test
    void smashAttackAddsVanillaTierBonus(@TempDir File dir) throws IOException {
        listener = listener(dir);
        PlayerMock attacker = maceWielder(server, new ItemStack(Material.MACE));
        attacker.setOnGround(false);
        attacker.setFallDistance(3.0f);
        Player victim = server.addPlayer();

        EntityDamageByEntityEvent event = maceHit(attacker, victim);
        listener.onEntityDamageByEntity(event);

        // 3 blocks fallen, all within tier1 (+4/block): 100 + 3*4 = 112.
        assertEquals(112.0, event.getDamage(), 1e-6,
                "a smash attack (fallDistance=3, airborne) must add the vanilla tiered smash bonus");
    }

    @Test
    void smashAttackBelowThresholdAddsNoBonus(@TempDir File dir) throws IOException {
        listener = listener(dir);
        PlayerMock attacker = maceWielder(server, new ItemStack(Material.MACE));
        attacker.setOnGround(false);
        attacker.setFallDistance(1.0f); // below the 1.5-block smash threshold
        Player victim = server.addPlayer();

        EntityDamageByEntityEvent event = maceHit(attacker, victim);
        listener.onEntityDamageByEntity(event);

        assertEquals(MACE_ATTACK_POWER, event.getDamage(), 1e-6,
                "fallDistance below the 1.5-block smash threshold must add no bonus");
    }

    @Test
    void densityAddsOnTopOfTheVanillaTierBonusWithoutDoubleCounting(@TempDir File dir) throws IOException {
        listener = listener(dir);
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        meta.addEnchant(Enchantment.DENSITY, 2, true);
        mace.setItemMeta(meta);

        PlayerMock attacker = maceWielder(server, mace);
        attacker.setOnGround(false);
        attacker.setFallDistance(5.0f);
        Player victim = server.addPlayer();

        EntityDamageByEntityEvent event = maceHit(attacker, victim);
        listener.onEntityDamageByEntity(event);

        // tier bonus: 3*4 + 2*2 = 16. Density (lvl 2): 2 * 0.5 * 5 = 5. Total: 100 + 16 + 5 = 121.
        assertEquals(121.0, event.getDamage(), 1e-6,
                "Density must add on top of (not instead of / not multiplied into) the vanilla tier bonus");
    }

    @Test
    void glidingSuppressesTheSmashBonusEvenWhenAirborneAndFarEnough(@TempDir File dir) throws IOException {
        listener = listener(dir);
        PlayerMock attacker = maceWielder(server, new ItemStack(Material.MACE));
        attacker.setOnGround(false);
        attacker.setFallDistance(10.0f);
        attacker.setGliding(true);
        Player victim = server.addPlayer();

        EntityDamageByEntityEvent event = maceHit(attacker, victim);
        listener.onEntityDamageByEntity(event);

        assertEquals(MACE_ATTACK_POWER, event.getDamage(), 1e-6,
                "gliding (elytra) must suppress the smash bonus even with sufficient fall distance");
    }

    @Test
    void nonMaceWeaponNeverGetsTheSmashBonus(@TempDir File dir) throws IOException {
        // Reuses MACE's attack-power fixture value on a different material key to isolate "is this
        // material a MACE" from "does the item have attack-power" (both DIAMOND_SWORD needs its own
        // item-stats entry here since the fixture only defines MACE).
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  MACE:
                    fixed: { attack-power: %s }
                  DIAMOND_SWORD:
                    fixed: { attack-power: %s }
                """.formatted(MACE_ATTACK_POWER, MACE_ATTACK_POWER));
        String damageYaml = """
                physical:
                  base-coefficient: 1.0
                  min-component-damage: 0.0
                melee-charge:
                  enabled: false
                """;
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
        listener = new CombatListener(plugin, svc, cm.itemStats(),
                damage, SkillLevelSource.EMPTY, bleed, perks, aggregator,
                cm.useRequirements(), cm.skillExp(), cm.craftingFeatures(),
                new RoleBuffResolver(cm.roleBuffs()));

        PlayerMock attacker = maceWielder(server, new ItemStack(Material.DIAMOND_SWORD));
        attacker.setOnGround(false);
        attacker.setFallDistance(10.0f);
        Player victim = server.addPlayer();

        EntityDamageByEntityEvent event = maceHit(attacker, victim);
        listener.onEntityDamageByEntity(event);

        assertEquals(MACE_ATTACK_POWER, event.getDamage(), 1e-6,
                "a falling DIAMOND_SWORD hit must never get the MACE-only smash bonus");
    }

    /**
     * #4 2026-07-25バグ修正: バニラのメイススマッシュ攻撃は「攻撃クールダウンの進行状況にかかわらず」
     * 発動する(一次情報: minecraft.wiki "Mace")。直前に別の一撃(連打)を放ってTFの近接チャージ記録を
     * elapsed=0(=B2の減衰floor 0.2倍)に落としても、直後のスマッシュ攻撃は一切減衰しないことを検証する。
     * 減衰が(誤って)掛かっていれば (100+12)*0.2=22.4 になるはずだが、正しい実装は 112.0 のまま。
     */
    @Test
    void smashAttackIgnoresMeleeChargeDecayEvenImmediatelyAfterAPriorAttack(@TempDir File dir)
            throws IOException {
        listener = listenerWithChargeEnabled(dir);
        PlayerMock attacker = maceWielderWithUnitAttackSpeed(server);
        Player victim = server.addPlayer();

        // 1発目: 接地・非スマッシュ攻撃でチャージ記録を消費する(この結果自体は問わない)。
        attacker.setOnGround(true);
        listener.onEntityDamageByEntity(maceHit(attacker, victim));

        // 2発目: 同tick(elapsed=0)で即座にスマッシュ攻撃(落下距離3.0 = tier1のみ, +4*3=12)。
        attacker.setOnGround(false);
        attacker.setFallDistance(3.0f);
        EntityDamageByEntityEvent second = maceHit(attacker, victim);
        listener.onEntityDamageByEntity(second);

        assertEquals(MACE_ATTACK_POWER + 12.0, second.getDamage(), 1e-6,
                "a smash attack must ignore the melee-charge decay even immediately after a prior "
                        + "attack — vanilla smash damage is independent of attack-cooldown progress");
    }

    /**
     * #4 2026-07-25バグ修正の対称ケース: メイスの「通常攻撃」(スマッシュ不成立、例えば接地状態)は
     * 従来どおり近接チャージ減衰の対象のままであることを確認する(「メイスなら常に減衰なし」への
     * 過剰修正になっていないことの直接証拠)。
     */
    @Test
    void nonSmashMaceAttackStillGetsMeleeChargeDecay(@TempDir File dir) throws IOException {
        listener = listenerWithChargeEnabled(dir);
        PlayerMock attacker = maceWielderWithUnitAttackSpeed(server);
        attacker.setOnGround(true);
        Player victim = server.addPlayer();

        // 1発目(フルチャージ、スマッシュ不成立): 100。
        listener.onEntityDamageByEntity(maceHit(attacker, victim));
        // 2発目: 同tick(elapsed=0)、依然として接地 -> スマッシュ不成立 -> 0.2倍まで減衰。
        EntityDamageByEntityEvent second = maceHit(attacker, victim);
        listener.onEntityDamageByEntity(second);

        assertEquals(MACE_ATTACK_POWER * 0.2, second.getDamage(), 1e-6,
                "a grounded (non-smash) mace hit must still be subject to the melee-charge decay, "
                        + "same as before this fix — smash is the only exception");
    }
}
