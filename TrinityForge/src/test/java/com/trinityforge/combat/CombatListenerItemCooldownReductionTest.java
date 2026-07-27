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
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-07-25 CT短縮ステータス分離 §1 / CT設計一本化 §2: {@code cooldown-reduction}(武器CT短縮)が
 * {@code CombatListener.startItemCooldown} で武器CT(item-cooldown秒数)を実際に短縮すること、および
 * ActiveSkill単位のCT短縮キー({@code haste-active-mining-cooldown-reduction} 等)が武器CTに一切影響しない
 * ことの回帰テスト(テスト必須1/2/4/5/6)。アクティブスキルCT側の対称テストは
 * {@code active.ActivationDispatcherTest} にある。
 */
class CombatListenerItemCooldownReductionTest {

    private ServerMock server;
    private CombatListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.dungeonWorldRegistry()).thenReturn(new DungeonWorldRegistry());
        TrinityForgeSingletonTestSupport.set(tf);
    }

    @AfterEach
    void tearDown() {
        TrinityForgeSingletonTestSupport.clear();
        MockBukkit.unmock();
    }

    private CombatListener listener(File dir, String itemStatsYaml) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), itemStatsYaml);

        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                  min-component-damage: 0.0
                """);
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

    private EntityDamageByEntityEvent meleeHit(Player attacker, Player victim) {
        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        return new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 1.0);
    }

    /** ActivationDispatcherTest と同じ流儀(Mockitoモック)で右クリックのPlayerInteractEventを組み立てる。 */
    private PlayerInteractEvent rightClickEvent(Player player, EquipmentSlot hand, Action action) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(hand);
        when(event.getAction()).thenReturn(action);
        return event;
    }

    @Test
    void cooldownReductionShortensTheWeaponPhysicalCooldown(@TempDir File dir) throws IOException {
        // item-cooldown=10s, cooldown-reduction=0.5 -> multiplier max(0.05, 1-min(0.9,0.5))=0.5 -> 5s -> 100 ticks.
        listener = listener(dir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { item-cooldown: 10.0, cooldown-reduction: 0.5 }
                """);
        PlayerMock attacker = server.addPlayer();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        attacker.getInventory().setItemInMainHand(sword);
        Player victim = server.addPlayer();

        listener.onEntityDamageByEntity(meleeHit(attacker, victim));

        int ticks = attacker.getCooldown(attacker.getInventory().getItemInMainHand());
        assertEquals(100, ticks, "10s item-cooldown * 0.5 multiplier = 5s = 100 ticks");
    }

    @Test
    void skillCooldownReductionKeyHasNoEffectOnTheWeaponPhysicalCooldown(@TempDir File dir) throws IOException {
        // item-cooldown=10s (200 ticks), haste-active-mining-cooldown-reduction=0.9 (would shrink to 20
        // ticks if it leaked into the weapon-CT path) — CombatListener must never read this key.
        listener = listener(dir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { item-cooldown: 10.0, haste-active-mining-cooldown-reduction: 0.9 }
                """);
        PlayerMock attacker = server.addPlayer();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        attacker.getInventory().setItemInMainHand(sword);
        Player victim = server.addPlayer();

        listener.onEntityDamageByEntity(meleeHit(attacker, victim));

        int ticks = attacker.getCooldown(attacker.getInventory().getItemInMainHand());
        assertEquals(200, ticks, "haste-active-mining-cooldown-reduction must not shorten the weapon's physical CT");
    }

    @Test
    void negativeCooldownReductionIsIgnoredByTheExistingGuard(@TempDir File dir) throws IOException {
        // Documents the PRE-EXISTING CombatListener.startItemCooldown behavior (untouched by this change,
        // 1-A is display/comment-only): the `reduction > 0.0` guard means a negative cooldown-reduction does
        // NOT increase the weapon CT (unlike the ActiveSkill per-skill CT-reduction path, which has no such
        // guard — see CooldownManagerTest#applyReductionIncreasesCooldownForNegativeFraction).
        listener = listener(dir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { item-cooldown: 10.0, cooldown-reduction: -0.5 }
                """);
        PlayerMock attacker = server.addPlayer();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        attacker.getInventory().setItemInMainHand(sword);
        Player victim = server.addPlayer();

        listener.onEntityDamageByEntity(meleeHit(attacker, victim));

        int ticks = attacker.getCooldown(attacker.getInventory().getItemInMainHand());
        assertEquals(200, ticks, "negative cooldown-reduction is ignored by the existing >0.0 guard");
    }

    @Test
    void cooldownReductionCapsAt90PercentReduction(@TempDir File dir) throws IOException {
        // 3.5 (non-integer) deliberately escapes PercentStatNormalize's "2..100 whole number -> /100"
        // percent-point coercion (unlike a plain 5.0, which the loader would treat as "5%" == 0.05).
        listener = listener(dir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { item-cooldown: 10.0, cooldown-reduction: 3.5 }
                """);
        PlayerMock attacker = server.addPlayer();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        attacker.getInventory().setItemInMainHand(sword);
        Player victim = server.addPlayer();

        listener.onEntityDamageByEntity(meleeHit(attacker, victim));

        int ticks = attacker.getCooldown(attacker.getInventory().getItemInMainHand());
        assertEquals(20, ticks, "reduction clamps at 0.9 -> multiplier floor 0.1 -> 1s = 20 ticks");
        assertTrue(ticks > 0);
    }

    /**
     * 2026-07-26 右クリック使用アイテムのアイテムCT回帰テスト(課題2、CombatListener.onRightClickItem)。
     * 杖・触媒等、右クリックで使うメインハンドのアイテムに item-cooldown があればCTが始まることを検証する。
     */
    @Test
    void rightClickAirStartsCooldownForMainhandItemWithItemCooldown(@TempDir File dir) throws IOException {
        listener = listener(dir, """
                items:
                  BLAZE_ROD:
                    fixed: { item-cooldown: 10.0 }
                """);
        PlayerMock attacker = server.addPlayer();
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        attacker.getInventory().setItemInMainHand(wand);

        listener.onRightClickItem(rightClickEvent(attacker, EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR));

        int ticks = attacker.getCooldown(attacker.getInventory().getItemInMainHand());
        assertEquals(200, ticks, "10s item-cooldown on a right-click-only item = 200 ticks");
    }

    @Test
    void rightClickBlockAlsoStartsCooldown(@TempDir File dir) throws IOException {
        listener = listener(dir, """
                items:
                  BLAZE_ROD:
                    fixed: { item-cooldown: 10.0 }
                """);
        PlayerMock attacker = server.addPlayer();
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        attacker.getInventory().setItemInMainHand(wand);

        listener.onRightClickItem(rightClickEvent(attacker, EquipmentSlot.HAND, Action.RIGHT_CLICK_BLOCK));

        int ticks = attacker.getCooldown(attacker.getInventory().getItemInMainHand());
        assertEquals(200, ticks, "RIGHT_CLICK_BLOCK must start the CT the same as RIGHT_CLICK_AIR");
    }

    @Test
    void rightClickDoesNotStartCooldownWithoutItemCooldownStat(@TempDir File dir) throws IOException {
        // BLAZE_ROD is not present in item-stats at all: derived item-cooldown resolves to 0.
        listener = listener(dir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { item-cooldown: 10.0 }
                """);
        PlayerMock attacker = server.addPlayer();
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        attacker.getInventory().setItemInMainHand(wand);

        listener.onRightClickItem(rightClickEvent(attacker, EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR));

        int ticks = attacker.getCooldown(attacker.getInventory().getItemInMainHand());
        assertEquals(0, ticks, "an item without item-cooldown must never start a CT");
    }

    @Test
    void offhandRightClickNeverStartsCooldown(@TempDir File dir) throws IOException {
        listener = listener(dir, """
                items:
                  BLAZE_ROD:
                    fixed: { item-cooldown: 10.0 }
                """);
        PlayerMock attacker = server.addPlayer();
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        // Offhand の武器CTは対象外: メインハンドは空手のまま、オフハンドにだけ杖を持たせる。
        attacker.getInventory().setItemInOffHand(wand);

        PlayerInteractEvent event = rightClickEvent(attacker, EquipmentSlot.OFF_HAND, Action.RIGHT_CLICK_AIR);
        listener.onRightClickItem(event);

        // event.getPlayer()以外は一切参照されない(オフハンドゲートで即return)ことも確認する。
        verify(event).getHand();
        int ticks = attacker.getCooldown(attacker.getInventory().getItemInOffHand());
        assertEquals(0, ticks, "an offhand right-click must never start a weapon CT");
    }

    @Test
    void doubleActivationDoesNotResetAnAlreadyRunningCooldown(@TempDir File dir) throws IOException {
        // 二重発動ガード: 右クリックでCTが始まった直後、同じアイテムでもう一度右クリックしても
        // (あるいは剣なら左クリックで殴っても)既にCT中のアイテムのCTはリセットされない。
        listener = listener(dir, """
                items:
                  BLAZE_ROD:
                    fixed: { item-cooldown: 10.0 }
                """);
        PlayerMock attacker = server.addPlayer();
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        attacker.getInventory().setItemInMainHand(wand);

        listener.onRightClickItem(rightClickEvent(attacker, EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR));
        int firstTicks = attacker.getCooldown(attacker.getInventory().getItemInMainHand());
        assertEquals(200, firstTicks, "first right-click starts the full 10s CT");

        // Simulate time passing partway through the cooldown, then right-click again.
        attacker.setCooldown(attacker.getInventory().getItemInMainHand(), 50);
        listener.onRightClickItem(rightClickEvent(attacker, EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR));

        int secondTicks = attacker.getCooldown(attacker.getInventory().getItemInMainHand());
        assertEquals(50, secondTicks, "a still-running CT must not be reset/restarted by a second right-click");
    }
}
