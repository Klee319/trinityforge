package com.trinityforge.listeners;

import com.trinityforge.TrinityForge;
import com.trinityforge.TrinityForgeSingletonTestSupport;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.MobLevelTableConfig;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NativeSkillExperienceListener#onArmorDamage}: 防具EXPパッシブfarm fix — a below-threshold final
 * hit grants no piece-flat EXP, and a repeat hit from the SAME attacker within the cooldown grants no
 * piece-flat EXP either (a fresh attacker, or a hit after the cooldown elapses, still grants normally).
 * {@link NativeExperienceDispatcher}/{@link NativeSkillCatalog}/{@link PlacedBlockTracker} are Mockito
 * mocks (no Bukkit server needed for pure grant-amount assertions); {@link EntityDamageByEntityEvent}/
 * {@link Player}/{@link LivingEntity} are mocked concrete/interface types, matching this package's existing
 * event-mocking convention.
 */
class NativeSkillExperienceListenerArmorExpTest {

    private static final double MIN_DAMAGE = 1.0;
    private static final double COOLDOWN_SECONDS = 10.0;
    private static final double PIECE_FLAT = 0.25;
    private static final double DAMAGE_RATE = 0.0; // isolate the piece-flat component from the damage-rate one

    /** A {@link NativeSkillExperienceListener} wired to a fresh, capturable {@link NativeExperienceDispatcher} mock. */
    private record Wired(NativeSkillExperienceListener listener, NativeExperienceDispatcher dispatcher) {
    }

    // onArmorDamage() gates on expAllowedInWorld(), which reads TrinityForge.getInstance().config() (this
    // listener has no injected SkillExpConfig — see the class-level onArmorDamage() javadoc). This class
    // never boots a real TrinityForge plugin (pure Mockito, no MockBukkit server), so the singleton is never
    // published; stub it with dungeon-only-exp=false so every test's damage event is exp-allowed regardless
    // of world (these tests assert piece-flat EXP threshold/cooldown behaviour, not the world gate itself).
    @BeforeEach
    void stubTrinityForgeSingleton() {
        SkillExpConfig skillExp = mock(SkillExpConfig.class);
        when(skillExp.dungeonOnlyExp()).thenReturn(false);
        ConfigManager config = mock(ConfigManager.class);
        when(config.skillExp()).thenReturn(skillExp);
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.config()).thenReturn(config);
        TrinityForgeSingletonTestSupport.set(tf);
    }

    @AfterEach
    void clearTrinityForgeSingleton() {
        TrinityForgeSingletonTestSupport.clear();
    }

    private Wired newListener() {
        return newListener(null);
    }

    /**
     * @param mobLevelTable {@code combat/mob-level-table.yml} の {@code no-skill-exp-mobs}(2026-07-27
     *                      牧場対策)。null なら旧8引数未満コンストラクタ相当(抑止なし)。
     */
    private Wired newListener(MobLevelTableConfig mobLevelTable) {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        PlacedBlockTracker tracker = mock(PlacedBlockTracker.class);

        Map<String, Double> rates = Map.of(
                "armor.damage_exp_rate", DAMAGE_RATE,
                "armor.exp_damage_piece", PIECE_FLAT,
                "armor.exp_damage_piece_min_damage", MIN_DAMAGE,
                "armor.exp_damage_piece_cooldown_seconds", COOLDOWN_SECONDS);
        SkillCatalogEntry heavyArmorEntry = new SkillCatalogEntry(
                SkillId.HEAVY_ARMOR, 100, "1", level -> 1L, Map.of(), rates);
        when(catalog.get(SkillId.HEAVY_ARMOR)).thenReturn(heavyArmorEntry);

        Object plugin = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {org.bukkit.plugin.Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "TrinityForge";
                    // Paper 1.21.11's NamespacedKey(Plugin, String) reads Plugin.namespace(), not getName().
                    case "namespace" -> "trinityforge";
                    case "toString" -> "FakePlugin";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        NativeSkillExperienceListener listener = new NativeSkillExperienceListener(
                (org.bukkit.plugin.Plugin) plugin, dispatcher, catalog, tracker, null, null, null, mobLevelTable);
        return new Wired(listener, dispatcher);
    }

    /** Builds a real, loaded {@link MobLevelTableConfig} from inline YAML via a fake resource plugin. */
    private static MobLevelTableConfig loadedMobLevelTable(File dataFolder, String yaml) throws Exception {
        File file = new File(dataFolder, MobLevelTableConfig.PATH);
        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
        java.nio.file.Files.writeString(file.toPath(), yaml);
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("NativeSkillExperienceListenerArmorExpTest");
            case "saveResource" -> null;
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(),
                new Class<?>[]{org.bukkit.plugin.Plugin.class}, handler);
        MobLevelTableConfig config = new MobLevelTableConfig();
        config.load(plugin);
        return config;
    }

    /** A player wearing a single heavy-armor (diamond) chestplate, non-creative. */
    private Player heavyArmorPlayer() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        PlayerInventory inv = mock(PlayerInventory.class);
        when(inv.getArmorContents()).thenReturn(new org.bukkit.inventory.ItemStack[] {
                null, null, null, new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_CHESTPLATE)
        });
        when(player.getInventory()).thenReturn(inv);
        return player;
    }

    private EntityDamageByEntityEvent damageEvent(Player victim, LivingEntity damager, double finalDamage) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamager()).thenReturn(damager);
        when(event.getFinalDamage()).thenReturn(finalDamage);
        return event;
    }

    @Test
    void belowThresholdDamageGrantsNoPieceFlatExp() {
        Wired wired = newListener();
        Player victim = heavyArmorPlayer();
        LivingEntity attacker = mock(LivingEntity.class);
        when(attacker.getUniqueId()).thenReturn(UUID.randomUUID());

        wired.listener().onArmorDamage(damageEvent(victim, attacker, 0.1)); // below MIN_DAMAGE(1.0)

        // damage-rate is 0 in this test, so ANY grant call would mean the piece-flat leaked through.
        verify(wired.dispatcher(), never()).grant(any(), eq(SkillId.HEAVY_ARMOR), anyDouble());
    }

    @Test
    void aboveThresholdFirstHitGrantsPieceFlatExp() {
        Wired wired = newListener();
        Player victim = heavyArmorPlayer();
        LivingEntity attacker = mock(LivingEntity.class);
        when(attacker.getUniqueId()).thenReturn(UUID.randomUUID());

        wired.listener().onArmorDamage(damageEvent(victim, attacker, 5.0));

        verify(wired.dispatcher()).grant(victim.getUniqueId(), SkillId.HEAVY_ARMOR, PIECE_FLAT);
    }

    @Test
    void repeatHitFromSameAttackerWithinCooldownGrantsNoPieceFlatExp() {
        Wired wired = newListener();
        Player victim = heavyArmorPlayer();
        LivingEntity attacker = mock(LivingEntity.class);
        when(attacker.getUniqueId()).thenReturn(UUID.randomUUID());

        wired.listener().onArmorDamage(damageEvent(victim, attacker, 5.0)); // grants
        wired.listener().onArmorDamage(damageEvent(victim, attacker, 5.0)); // same pair, immediately after: blocked

        verify(wired.dispatcher(), org.mockito.Mockito.times(1))
                .grant(victim.getUniqueId(), SkillId.HEAVY_ARMOR, PIECE_FLAT);
    }

    @Test
    void repeatHitFromADifferentAttackerIsNotBlockedByAnotherAttackersCooldown() {
        Wired wired = newListener();
        Player victim = heavyArmorPlayer();
        LivingEntity attackerOne = mock(LivingEntity.class);
        when(attackerOne.getUniqueId()).thenReturn(UUID.randomUUID());
        LivingEntity attackerTwo = mock(LivingEntity.class);
        when(attackerTwo.getUniqueId()).thenReturn(UUID.randomUUID());

        wired.listener().onArmorDamage(damageEvent(victim, attackerOne, 5.0));
        wired.listener().onArmorDamage(damageEvent(victim, attackerTwo, 5.0));

        verify(wired.dispatcher(), org.mockito.Mockito.times(2))
                .grant(victim.getUniqueId(), SkillId.HEAVY_ARMOR, PIECE_FLAT);
    }

    // --- 2026-07-27 牧場対策: no-skill-exp-mobs (攻撃してきた側のEntityTypeで防具スキルEXPを抑止) ---
    // CombatListenerNoSkillExpMobsTest(武器側)と対になるテスト。バニラEXPオーブは対象外 —
    // ここで検証するのは NativeExperienceDispatcher#grant(HEAVY_ARMOR) = TrinityForgeの戦闘スキルEXPだけ。

    @Test
    void noSkillExpMobsBlocksArmorSkillExpForAttackerType(@TempDir File dir) throws Exception {
        MobLevelTableConfig mobLevelTable = loadedMobLevelTable(dir, "no-skill-exp-mobs: [ZOMBIE]\n");
        Wired wired = newListener(mobLevelTable);
        Player victim = heavyArmorPlayer();
        LivingEntity attacker = mock(LivingEntity.class);
        when(attacker.getUniqueId()).thenReturn(UUID.randomUUID());
        when(attacker.getType()).thenReturn(EntityType.ZOMBIE);

        wired.listener().onArmorDamage(damageEvent(victim, attacker, 5.0));

        verify(wired.dispatcher(), never()).grant(any(), eq(SkillId.HEAVY_ARMOR), anyDouble());
    }

    @Test
    void attackerTypeNotInNoSkillExpMobsStillGrantsArmorSkillExp(@TempDir File dir) throws Exception {
        MobLevelTableConfig mobLevelTable = loadedMobLevelTable(dir, "no-skill-exp-mobs: [ZOMBIE]\n");
        Wired wired = newListener(mobLevelTable);
        Player victim = heavyArmorPlayer();
        LivingEntity attacker = mock(LivingEntity.class);
        when(attacker.getUniqueId()).thenReturn(UUID.randomUUID());
        when(attacker.getType()).thenReturn(EntityType.SKELETON);

        wired.listener().onArmorDamage(damageEvent(victim, attacker, 5.0));

        verify(wired.dispatcher()).grant(victim.getUniqueId(), SkillId.HEAVY_ARMOR, PIECE_FLAT);
    }

    @Test
    void backCompatConstructorsWithNullMobLevelTableNeverSuppress() {
        // 旧4引数コンストラクタ(newListener()、mobLevelTable=null)は EntityType が何であっても
        // 抑止しない — このクラスの他の全既存テストが getType() を一切スタブしていないこと自体が
        // 「抑止ロジックが getType() を呼ばない(=無効)」ことの間接証拠だが、ここでは明示的に確認する。
        Wired wired = newListener(); // mobLevelTable = null
        Player victim = heavyArmorPlayer();
        LivingEntity attacker = mock(LivingEntity.class);
        when(attacker.getUniqueId()).thenReturn(UUID.randomUUID());
        when(attacker.getType()).thenReturn(EntityType.ZOMBIE);

        wired.listener().onArmorDamage(damageEvent(victim, attacker, 5.0));

        verify(wired.dispatcher()).grant(victim.getUniqueId(), SkillId.HEAVY_ARMOR, PIECE_FLAT);
    }
}
