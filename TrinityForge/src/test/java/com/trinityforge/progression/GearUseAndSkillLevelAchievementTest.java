package com.trinityforge.progression;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.listeners.GearUseListener;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-16 のアチーブメント再構築で足した 3 機構の固定テスト。
 *
 * <ul>
 *   <li>{@code type: gear-use} — 「その武器でダメージを与えた / その防具を着て被弾した」。
 *       <b>クラフト統計で書かない</b>のは、使用可能レベルに達していなくても作れば解除できるため。</li>
 *   <li>{@code type: skill-level} — 「列挙したスキルのうち N 種類が Lv◯以上」。</li>
 *   <li>{@code hidden: true} — 裏アチーブメント。達成するまで GUI に出さない。</li>
 * </ul>
 */
class GearUseAndSkillLevelAchievementTest {

    private static final Logger LOG = Logger.getLogger("GearUseAndSkillLevelAchievementTest");

    private ServerMock server;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("gear-use");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ---------------------------------------------------------------------------------- gear-use

    @Test
    @DisplayName("列挙した武器でダメージを与えた種類数がしきい値に届くと達成する")
    void weaponTierNeedsActualDamageNotCrafting(@TempDir File dir) throws IOException {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  tier-stone:
                    display-name: "石の武器"
                    trigger:
                      type: gear-use
                      gear-use:
                        slot: weapon
                        items: ["STONE_SWORD", "STONE_AXE"]
                        threshold: 1
                """);
        AchievementService service = new AchievementService(config, LOG);
        GearUseListener listener = new GearUseListener(config);
        Player player = server.addPlayer();
        player.setGameMode(GameMode.SURVIVAL);

        // 作っただけ(=インベントリに持っているだけ)では進まない。
        player.getInventory().addItem(new ItemStack(Material.STONE_SWORD));
        service.pollStatistics();
        assertFalse(PlayerData.of(player).achievedIds().contains("tier-stone"),
                "持っているだけで解除できてはならない(使用可能レベルの門を迂回できてしまう)");

        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE_SWORD));
        listener.onDamage(attack(player, spawnCow()));
        service.pollStatistics();
        assertTrue(PlayerData.of(player).achievedIds().contains("tier-stone"),
                "その武器で実際にダメージを与えたら達成する");
    }

    @Test
    @DisplayName("threshold 未満の種類数では達成しない")
    void multipleWeaponsAreCountedByDistinctKind(@TempDir File dir) throws IOException {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  two-kinds:
                    trigger:
                      type: gear-use
                      gear-use:
                        slot: weapon
                        items: ["IRON_SWORD", "IRON_AXE"]
                        threshold: 2
                """);
        AchievementService service = new AchievementService(config, LOG);
        GearUseListener listener = new GearUseListener(config);
        Player player = server.addPlayer();
        player.setGameMode(GameMode.SURVIVAL);

        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
        listener.onDamage(attack(player, spawnCow()));
        // 同じ武器を何度振っても種類は1のまま。
        listener.onDamage(attack(player, spawnCow()));
        service.pollStatistics();
        assertFalse(PlayerData.of(player).achievedIds().contains("two-kinds"), "1種類では届かない");

        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_AXE));
        listener.onDamage(attack(player, spawnCow()));
        service.pollStatistics();
        assertTrue(PlayerData.of(player).achievedIds().contains("two-kinds"), "2種類目で達成する");
    }

    @Test
    @DisplayName("設定に書かれていない装備はPDCへ記録しない(青天井を防ぐ)")
    void unwatchedGearIsNeverStored(@TempDir File dir) throws IOException {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  tier-wood:
                    trigger:
                      type: gear-use
                      gear-use:
                        slot: weapon
                        items: ["WOODEN_SWORD"]
                """);
        GearUseListener listener = new GearUseListener(config);
        Player player = server.addPlayer();
        player.setGameMode(GameMode.SURVIVAL);

        player.getInventory().setItemInMainHand(new ItemStack(Material.NETHERITE_HOE));
        listener.onDamage(attack(player, spawnCow()));

        assertTrue(PlayerData.of(player).gearUsed().isEmpty(),
                "achievements.yml から参照されていない装備を記録するとプレイヤーPDCが青天井になる");
    }

    @Test
    @DisplayName("防具は着て被弾したときに記録される")
    void armorIsRecordedWhenTheWearerTakesDamage(@TempDir File dir) throws IOException {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  armor-iron:
                    trigger:
                      type: gear-use
                      gear-use:
                        slot: armor
                        items: ["IRON_CHESTPLATE"]
                """);
        AchievementService service = new AchievementService(config, LOG);
        GearUseListener listener = new GearUseListener(config);
        Player player = server.addPlayer();
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));

        service.pollStatistics();
        assertFalse(PlayerData.of(player).achievedIds().contains("armor-iron"), "着ただけでは進まない");

        listener.onDamage(attack(spawnCow(), player));
        service.pollStatistics();
        assertTrue(PlayerData.of(player).achievedIds().contains("armor-iron"), "被弾で達成する");
    }

    @Test
    @DisplayName("クリエイティブ中の使用は記録しない")
    void creativeUseIsNotRecorded(@TempDir File dir) throws IOException {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  tier-diamond:
                    trigger:
                      type: gear-use
                      gear-use:
                        slot: weapon
                        items: ["DIAMOND_SWORD"]
                """);
        GearUseListener listener = new GearUseListener(config);
        Player player = server.addPlayer();
        player.setGameMode(GameMode.CREATIVE);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));

        listener.onDamage(attack(player, spawnCow()));

        assertTrue(PlayerData.of(player).gearUsed().isEmpty(),
                "アイテム欄から出した装備でティアを無料で埋められてはならない");
    }

    // ------------------------------------------------------------------------------- skill-level

    @Test
    @DisplayName("skill-level は列挙したスキルのうち count 種類が level 以上で達成する")
    void skillLevelCountsDistinctSkillsAtOrAboveTheLevel(@TempDir File dir) throws IOException {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  duo-master:
                    trigger:
                      type: skill-level
                      skill-level:
                        skills: ["ARS_MAGIC", "ARCHERY", "POWER"]
                        level: 20
                        count: 2
                """);
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();

        service.setLevelSources(
                levels(Map.of("ARS_MAGIC", 25, "ARCHERY", 19, "POWER", 3)),
                id -> 0);
        service.pollStatistics();
        assertFalse(PlayerData.of(player).achievedIds().contains("duo-master"), "1種類だけでは届かない");

        service.setLevelSources(
                levels(Map.of("ARS_MAGIC", 25, "ARCHERY", 20, "POWER", 3)),
                id -> 0);
        service.pollStatistics();
        assertTrue(PlayerData.of(player).achievedIds().contains("duo-master"), "2種類目が届いたら達成する");
    }

    @Test
    @DisplayName("skills に COMBAT を書くと総合戦闘レベルを見る")
    void combatPseudoSkillReadsTheCombatLevel(@TempDir File dir) throws IOException {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  combat-30:
                    trigger:
                      type: skill-level
                      skill-level:
                        skills: ["COMBAT"]
                        level: 30
                """);
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();

        service.setLevelSources(levels(Map.of()), id -> 29);
        service.pollStatistics();
        assertFalse(PlayerData.of(player).achievedIds().contains("combat-30"));

        service.setLevelSources(levels(Map.of()), id -> 30);
        service.pollStatistics();
        assertTrue(PlayerData.of(player).achievedIds().contains("combat-30"));
    }

    @Test
    @DisplayName("レベル源が未配線でも例外を投げず、達成もしない")
    void missingLevelSourcesFailSoft(@TempDir File dir) throws IOException {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  combat-1:
                    trigger:
                      type: skill-level
                      skill-level:
                        skills: ["COMBAT"]
                        level: 1
                """);
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();

        service.pollStatistics();

        assertFalse(PlayerData.of(player).achievedIds().contains("combat-1"),
                "未配線を「レベル0」ではなく「判定不能」として扱い、勝手に達成させない");
    }

    // ------------------------------------------------------------------------------------ hidden

    @Test
    @DisplayName("hidden: true を読み取る(裏アチーブメント)")
    void hiddenFlagIsParsed(@TempDir File dir) throws IOException {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  secret:
                    hidden: true
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 1
                  open:
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 1
                """);

        assertTrue(config.achievements().stream()
                .filter(a -> a.id().equals("secret")).findFirst().orElseThrow().hidden());
        assertFalse(config.achievements().stream()
                .filter(a -> a.id().equals("open")).findFirst().orElseThrow().hidden());
    }

    // ------------------------------------------------------------------------------------ 補助

    private Entity spawnCow() {
        return world.spawn(world.getSpawnLocation(), Cow.class);
    }

    private static EntityDamageByEntityEvent attack(Entity damager, Entity victim) {
        DamageSource source = DamageSource.builder(DamageType.MOB_ATTACK)
                .withCausingEntity(damager)
                .withDirectEntity(damager)
                .build();
        return new EntityDamageByEntityEvent(
                damager, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 1.0);
    }

    private static SkillLevelSource levels(Map<String, Integer> bySkill) {
        return new SkillLevelSource() {
            @Override
            public Map<String, Integer> levelsOf(UUID playerId) {
                return bySkill;
            }
        };
    }

    private static AchievementsConfig configOf(File dir, String yaml) throws IOException {
        File file = new File(dir, AchievementsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        AchievementsConfig config = new AchievementsConfig();
        config.load(fakePlugin(dir));
        return config;
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> LOG;
            case "saveResource" -> null;
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }
}
