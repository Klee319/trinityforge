package com.trinityforge.combat;

import com.trinityforge.TrinityForge;
import com.trinityforge.TrinityForgeSingletonTestSupport;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.dungeon.DungeonWorldRegistry;
import com.trinityforge.listeners.CombatListener;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import io.papermc.paper.event.entity.EntityLoadCrossbowEvent;
import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
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
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug fix regression: an under-leveled {@code use-level-requirement} shot must not consume the arrow
 * (bow), the loaded ammo (crossbow), or the thrown item (trident) even though the shot itself is
 * correctly blocked. All three shooters use {@link SkillLevelSource#EMPTY} (level 0 in every skill), so
 * any positive {@code use-level-requirement} configured below always blocks them; a second, unrestricted
 * material proves the un-blocked path is untouched.
 *
 * <p>Root causes fixed in {@link CombatListener}:
 * <ul>
 *   <li>bow — {@code EntityShootBowEvent#setCancelled(true)} alone does not stop vanilla from consuming
 *       the arrow; {@code setConsumeArrow(false)}/{@code setConsumeItem(false)} are required too.</li>
 *   <li>crossbow — ammo is consumed at LOAD time ({@link EntityLoadCrossbowEvent}), before {@code
 *       EntityShootBowEvent} would even fire, so the shoot-time gate alone is too late; the load event
 *       itself must be gated.</li>
 *   <li>trident — {@link ProjectileLaunchEvent} has no consume-control flag at all; the thrown item must
 *       be given back explicitly, guarded so it is a no-op (not a duplicate) when the item was not
 *       actually removed from the hand.</li>
 * </ul>
 */
class RangedUseRequirementConsumptionTest {

    private static final String SKILL = "ARCHERY";
    private static final int REQUIRED_LEVEL = 10;

    private ServerMock server;
    private WorldMock world;
    private CombatListener listener;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        TrinityForge tf = org.mockito.Mockito.mock(TrinityForge.class);
        org.mockito.Mockito.when(tf.dungeonWorldRegistry()).thenReturn(new DungeonWorldRegistry());
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
        // BOW/CROSSBOW/TRIDENT gated at REQUIRED_LEVEL; DIAMOND_SWORD left unrestricted as a control.
        Files.writeString(itemStats.toPath(), """
                items:
                  BOW:
                    fixed: { attack-power: 10 }
                    use-level-requirement: %d
                    use-skill: %s
                  CROSSBOW:
                    fixed: { attack-power: 10 }
                    use-level-requirement: %d
                    use-skill: %s
                  TRIDENT:
                    fixed: { attack-power: 10 }
                    use-level-requirement: %d
                    use-skill: %s
                """.formatted(REQUIRED_LEVEL, SKILL, REQUIRED_LEVEL, SKILL, REQUIRED_LEVEL, SKILL));

        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        Plugin plugin = MockBukkit.createMockPlugin();
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), cm.combatDamage(), perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(
                cm.combatDamage().defenseStatKeys(), aggregator);
        SymmetricCombatService svc = new SymmetricCombatService(
                cm.combatDamage(), cm.combatLevel(), cm.mobTypes(), SkillLevelSource.EMPTY, defense);
        BleedService bleed = new BleedService(plugin, svc, cm.combatDamage());
        return new CombatListener(plugin, svc, cm.itemStats(),
                cm.combatDamage(), SkillLevelSource.EMPTY, bleed, perks, aggregator,
                cm.useRequirements(), cm.skillExp(), cm.craftingFeatures(),
                new RoleBuffResolver(cm.roleBuffs()));
    }

    // ---- bow ----------------------------------------------------------------------------------

    @Test
    void blockedBowShotDoesNotConsumeArrow(@TempDir File dir) throws IOException {
        listener = listener(dir);
        Player shooter = server.addPlayer();
        ItemStack bow = new ItemStack(Material.BOW);
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);

        EntityShootBowEvent event = new EntityShootBowEvent(shooter, bow, arrow, 1.0f);
        listener.onEntityShootBow(event);

        assertTrue(event.isCancelled(), "under-leveled bow shot must be cancelled");
        assertFalse(event.getConsumeArrow(), "cancelled shot must not consume the arrow");
        assertFalse(event.shouldConsumeItem(), "cancelled shot must not consume the item either");
    }

    @Test
    void allowedBowShotStillConsumesArrowAsUsual(@TempDir File dir) throws IOException {
        listener = listener(dir);
        Player shooter = server.addPlayer();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD); // unrestricted control material
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);

        EntityShootBowEvent event = new EntityShootBowEvent(shooter, sword, arrow, 1.0f);
        boolean consumeArrowBefore = event.getConsumeArrow();
        listener.onEntityShootBow(event);

        assertFalse(event.isCancelled(), "an unrestricted weapon's shot must not be blocked");
        assertEquals(consumeArrowBefore, event.getConsumeArrow(),
                "an allowed shot's consume flag must be left exactly as vanilla set it");
    }

    // ---- crossbow -------------------------------------------------------------------------------

    @Test
    void blockedCrossbowLoadDoesNotConsumeAmmo(@TempDir File dir) throws IOException {
        listener = listener(dir);
        Player shooter = server.addPlayer();
        ItemStack crossbow = new ItemStack(Material.CROSSBOW);

        EntityLoadCrossbowEvent event = new EntityLoadCrossbowEvent(shooter, crossbow, EquipmentSlot.HAND);
        listener.onEntityLoadCrossbow(event);

        assertTrue(event.isCancelled(), "under-leveled crossbow load must be cancelled");
        assertFalse(event.shouldConsumeItem(), "cancelled load must not consume the ammo");
    }

    @Test
    void allowedCrossbowLoadStillConsumesAmmoAsUsual(@TempDir File dir) throws IOException {
        listener = listener(dir);
        Player shooter = server.addPlayer();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD); // unrestricted control material

        EntityLoadCrossbowEvent event = new EntityLoadCrossbowEvent(shooter, sword, EquipmentSlot.HAND);
        boolean consumeBefore = event.shouldConsumeItem();
        listener.onEntityLoadCrossbow(event);

        assertFalse(event.isCancelled(), "an unrestricted crossbow load must not be blocked");
        assertEquals(consumeBefore, event.shouldConsumeItem(),
                "an allowed load's consume flag must be left exactly as vanilla set it");
    }

    @Test
    void blockedCrossbowShootIsAlsoGatedAsDefenseInDepth(@TempDir File dir) throws IOException {
        // A crossbow that was already loaded before the requirement applied (e.g. dispenser-loaded)
        // must still be blocked at fire time, with the same consume-flag hygiene as the bow path.
        listener = listener(dir);
        Player shooter = server.addPlayer();
        ItemStack crossbow = new ItemStack(Material.CROSSBOW);
        Arrow arrow = world.spawn(world.getSpawnLocation(), Arrow.class);

        EntityShootBowEvent event = new EntityShootBowEvent(shooter, crossbow, arrow, 1.0f);
        listener.onEntityShootBow(event);

        assertTrue(event.isCancelled(), "under-leveled crossbow fire must be blocked too");
        assertFalse(event.getConsumeArrow());
        assertFalse(event.shouldConsumeItem());
    }

    // ---- trident --------------------------------------------------------------------------------

    /**
     * MockBukkit's {@code TridentMock} does not implement {@code setShooter}/{@code setItem} ({@code
     * UnimplementedOperationException}, reported by JUnit as a silently SKIPPED test rather than a
     * failure — easy to miss). A real spawned trident is wrapped in a {@link org.mockito.Mockito#spy} so
     * {@code getShooter()}/{@code getItem()} return the values the listener needs, without ever calling
     * the unimplemented setters.
     */
    private static Trident tridentFrom(Player shooter, ItemStack item, WorldMock world) {
        Trident real = world.spawn(world.getSpawnLocation(), Trident.class);
        Trident trident = org.mockito.Mockito.spy(real);
        org.mockito.Mockito.doReturn(shooter).when(trident).getShooter();
        org.mockito.Mockito.doReturn(item).when(trident).getItem();
        return trident;
    }

    @Test
    void blockedTridentThrowRestoresItemWhenHandWasEmptied(@TempDir File dir) throws IOException {
        listener = listener(dir);
        Player shooter = server.addPlayer();
        ItemStack tridentItem = new ItemStack(Material.TRIDENT);
        // Simulate vanilla having already removed the trident from the hand before this event fires
        // (ProjectileLaunchEvent has no consume-control flag, unlike EntityShootBowEvent).
        shooter.getInventory().setItem(EquipmentSlot.HAND, null);

        Trident trident = tridentFrom(shooter, tridentItem, world);
        ProjectileLaunchEvent event = new ProjectileLaunchEvent(trident);
        listener.onProjectileLaunch(event);

        assertTrue(event.isCancelled(), "under-leveled trident throw must be blocked");
        ItemStack restored = shooter.getInventory().getItemInMainHand();
        assertEquals(Material.TRIDENT, restored.getType(), "the trident must be given back to the hand");
        assertEquals(1, restored.getAmount(), "exactly one trident must be restored, not duplicated");
    }

    @Test
    void blockedTridentThrowDoesNotDuplicateWhenHandStillHeldIt(@TempDir File dir) throws IOException {
        listener = listener(dir);
        Player shooter = server.addPlayer();
        ItemStack tridentItem = new ItemStack(Material.TRIDENT);
        // Defensive scenario: the item was NOT actually removed from the hand (e.g. a future Paper
        // version changes the consumption/event ordering). The fix must be a no-op here, not a duplicate.
        shooter.getInventory().setItem(EquipmentSlot.HAND, tridentItem.clone());

        Trident trident = tridentFrom(shooter, tridentItem, world);
        ProjectileLaunchEvent event = new ProjectileLaunchEvent(trident);
        listener.onProjectileLaunch(event);

        assertTrue(event.isCancelled());
        ItemStack afterMain = shooter.getInventory().getItemInMainHand();
        assertEquals(1, afterMain.getAmount(), "must still be exactly 1 trident (no duplication)");
        long totalTridents = java.util.Arrays.stream(shooter.getInventory().getContents())
                .filter(stack -> stack != null && stack.getType() == Material.TRIDENT)
                .mapToInt(ItemStack::getAmount)
                .sum();
        assertEquals(1, totalTridents, "the whole inventory must contain exactly one trident total");
    }

    @Test
    void allowedTridentThrowIsNotBlockedOrTouched(@TempDir File dir) throws IOException {
        listener = listener(dir);
        Player shooter = server.addPlayer();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD); // unrestricted control material

        Trident trident = tridentFrom(shooter, sword, world); // stand-in "thrown item" is unrestricted

        ProjectileLaunchEvent event = new ProjectileLaunchEvent(trident);
        listener.onProjectileLaunch(event);

        assertFalse(event.isCancelled(), "an unrestricted thrown weapon must not be blocked");
    }
}
