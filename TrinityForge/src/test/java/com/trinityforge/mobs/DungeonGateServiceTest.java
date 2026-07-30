package com.trinityforge.mobs;

import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.DungeonGateConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DungeonGateService} (2026-07-27 カスタムアイテム鍵対応): 二相評価(全ゲート通過確認後に消費)、
 * カタログ/ArsPaper鍵の所持・消費、解決不能な鍵IDの通過扱いを検証する。
 */
class DungeonGateServiceTest {

    private static final Logger LOG = Logger.getLogger("DungeonGateServiceTest");

    private ServerMock server;
    private ItemCatalogConfig catalog;
    private ItemFactory itemFactory;
    private CrossPluginItemResolver itemResolver;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        catalog = mock(ItemCatalogConfig.class);
        itemFactory = mock(ItemFactory.class);
        when(catalog.template(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        itemResolver = new CrossPluginItemResolver(catalog, itemFactory);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Loads a real {@link DungeonGateConfig} from inline YAML via {@code load(Plugin)} (fake plugin,
     *  same pattern as {@code FoodGimmickConfigTest}) so gate resolution goes through the real parser. */
    private static DungeonGateConfig loadViaFakePlugin(String yaml) throws Exception {
        java.io.File tempDir = java.nio.file.Files.createTempDirectory("dgs-test").toFile();
        java.io.File file = new java.io.File(tempDir, DungeonGateConfig.PATH);
        file.getParentFile().mkdirs();
        java.nio.file.Files.writeString(file.toPath(), yaml);
        org.bukkit.plugin.Plugin plugin = fakePlugin(tempDir);
        DungeonGateConfig config = new DungeonGateConfig();
        config.load(plugin);
        return config;
    }

    private static org.bukkit.plugin.Plugin fakePlugin(java.io.File dataFolder) {
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> LOG;
            case "saveResource" -> null;
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(),
                new Class<?>[] {org.bukkit.plugin.Plugin.class}, handler);
    }

    private static ItemStack stampedCatalogItem(Material material, String catalogId) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId(catalogId);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Registers {@code catalogId} in the mocked {@link ItemCatalogConfig} so that
     * {@link CrossPluginItemResolver#exists(String)} resolves it (needed for
     * {@link DungeonGateService}'s "does this key-item resolve at all" check — without this, every
     * custom catalog id in these tests would look unresolvable, since the mocked
     * {@code ItemCatalogConfig}/{@code ItemFactory} know nothing about any id unless told to).
     */
    private void resolvableAsCatalogItem(String catalogId) {
        when(catalog.template(catalogId)).thenReturn(Optional.of(
                new ItemTemplate(catalogId, Material.LEATHER, null, null, BindType.TRADEABLE, 0, null)));
    }

    @Test
    void allowsEntryAndConsumesCustomCatalogKey() throws Exception {
        DungeonGateConfig gateConfig = loadViaFakePlugin("""
                gates:
                  dungeon_sanctum:
                    required-combat-level: 0
                    key-item: tf_crypt_sigil
                    key-amount: 1
                """);
        resolvableAsCatalogItem("tf_crypt_sigil");
        SymmetricCombatService combat = mock(SymmetricCombatService.class);
        when(combat.combatLevelOf(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(50);
        DungeonGateService service = new DungeonGateService(gateConfig, combat, itemResolver);

        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, stampedCatalogItem(Material.LEATHER, "tf_crypt_sigil"));

        assertTrue(service.checkEntry(player, "dungeon_sanctum"));
        // Consumed on success.
        assertFalse(hasAnyCatalogKey(player, "tf_crypt_sigil"));
    }

    @Test
    void deniesEntryWhenCatalogKeyMissing() throws Exception {
        DungeonGateConfig gateConfig = loadViaFakePlugin("""
                gates:
                  dungeon_sanctum:
                    required-combat-level: 0
                    key-item: tf_crypt_sigil
                    key-amount: 1
                """);
        resolvableAsCatalogItem("tf_crypt_sigil");
        SymmetricCombatService combat = mock(SymmetricCombatService.class);
        when(combat.combatLevelOf(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(50);
        DungeonGateService service = new DungeonGateService(gateConfig, combat, itemResolver);

        PlayerMock player = server.addPlayer(); // empty inventory

        assertFalse(service.checkEntry(player, "dungeon_sanctum"));
    }

    @Test
    void requiredEliteMobsEntryFailsClosedWhenNoGateIsConfigured() throws Exception {
        DungeonGateConfig gateConfig = loadViaFakePlugin("gates: {}\n");
        SymmetricCombatService combat = mock(SymmetricCombatService.class);
        when(combat.combatLevelOf(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(50);
        DungeonGateService service = new DungeonGateService(gateConfig, combat, itemResolver);

        PlayerMock player = server.addPlayer();

        assertFalse(service.checkRequiredEntry(player, "unconfigured_content_package"));
    }

    @Test
    void requiredEliteMobsEntryAllowsAdminWithoutConfiguredGate() throws Exception {
        DungeonGateConfig gateConfig = loadViaFakePlugin("gates: {}\n");
        SymmetricCombatService combat = mock(SymmetricCombatService.class);
        when(combat.combatLevelOf(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(50);
        DungeonGateService service = new DungeonGateService(gateConfig, combat, itemResolver);

        PlayerMock admin = server.addPlayer();
        admin.setOp(true);

        assertTrue(service.checkRequiredEntry(admin, "unconfigured_content_package"));
    }

    @Test
    void gatePresenceCheckResolvesWorldAndContentPackageWithoutConsuming() throws Exception {
        DungeonGateConfig gateConfig = loadViaFakePlugin("""
                gates:
                  dungeon_sanctum:
                    content-package: sanctum_package.yml
                    required-combat-level: 0
                    key-item: tf_crypt_sigil
                    key-amount: 1
                """);
        resolvableAsCatalogItem("tf_crypt_sigil");
        SymmetricCombatService combat = mock(SymmetricCombatService.class);
        DungeonGateService service = new DungeonGateService(gateConfig, combat, itemResolver);

        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, stampedCatalogItem(Material.LEATHER, "tf_crypt_sigil"));

        assertTrue(service.hasEntryGate("dungeon_sanctum"));
        assertTrue(service.hasEntryGate("SANCTUM_PACKAGE.YML"));
        assertFalse(service.hasEntryGate("unconfigured_package.yml"));
        assertTrue(hasAnyCatalogKey(player, "tf_crypt_sigil"));
    }

    @Test
    void requiredEntryPreviewDoesNotConsumeUntilCommittedEntryCheck() throws Exception {
        DungeonGateConfig gateConfig = loadViaFakePlugin("""
                gates:
                  dungeon_sanctum:
                    content-package: sanctum_package.yml
                    required-combat-level: 0
                    key-item: tf_crypt_sigil
                    key-amount: 1
                """);
        resolvableAsCatalogItem("tf_crypt_sigil");
        SymmetricCombatService combat = mock(SymmetricCombatService.class);
        when(combat.combatLevelOf(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(50);
        DungeonGateService service = new DungeonGateService(gateConfig, combat, itemResolver);

        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, stampedCatalogItem(Material.LEATHER, "tf_crypt_sigil"));

        assertTrue(service.previewRequiredEntry(player, "sanctum_package.yml"));
        assertTrue(hasAnyCatalogKey(player, "tf_crypt_sigil"), "事前確認で鍵を消費している");
        assertTrue(service.checkRequiredEntry(player, "sanctum_package.yml"));
        assertFalse(hasAnyCatalogKey(player, "tf_crypt_sigil"), "確定入場で鍵を消費していない");
    }

    @Test
    void vanillaMaterialKeyDoesNotConsumeCustomItemOfSameMaterial() throws Exception {
        // 鍵がバニラMaterial(AMETHYST_SHARD)のとき、同じMaterialのカスタム品(PDCあり)を巻き込んで
        // 消費してはいけない。カスタム品しか持っていない場合は「所持なし」で拒否される。
        DungeonGateConfig gateConfig = loadViaFakePlugin("""
                gates:
                  dungeon_sanctum:
                    required-combat-level: 0
                    key-item: AMETHYST_SHARD
                    key-amount: 1
                """);
        SymmetricCombatService combat = mock(SymmetricCombatService.class);
        when(combat.combatLevelOf(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(50);
        DungeonGateService service = new DungeonGateService(gateConfig, combat, itemResolver);

        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, stampedCatalogItem(Material.AMETHYST_SHARD, "tf_special_shard"));

        assertFalse(service.checkEntry(player, "dungeon_sanctum"));
    }

    @Test
    void twoPhaseEvaluationDoesNotConsumeAnyKeyWhenOneOfMultipleGatesFails() throws Exception {
        PlayerMock player = server.addPlayer();
        String worldName = player.getWorld().getName();
        DungeonGateConfig gateConfig = loadViaFakePlugin("""
                gates:
                  dungeon_a:
                    required-combat-level: 0
                    key-item: tf_key_a
                    key-amount: 1
                    region:
                      world: %1$s
                      min: [0, 0, 0]
                      max: [10, 10, 10]
                  dungeon_b:
                    required-combat-level: 999
                    key-item: tf_key_b
                    key-amount: 1
                    region:
                      world: %1$s
                      min: [0, 0, 0]
                      max: [10, 10, 10]
                """.formatted(worldName));
        resolvableAsCatalogItem("tf_key_a");
        SymmetricCombatService combat = mock(SymmetricCombatService.class);
        when(combat.combatLevelOf(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(1);
        DungeonGateService service = new DungeonGateService(gateConfig, combat, itemResolver);

        player.getInventory().setItem(0, stampedCatalogItem(Material.LEATHER, "tf_key_a"));
        org.bukkit.World world = player.getWorld();
        org.bukkit.Location from = new org.bukkit.Location(world, -5, 5, 5);
        org.bukkit.Location to = new org.bukkit.Location(world, 5, 5, 5);

        assertFalse(service.checkRegionEntry(player, from, to, false));
        // dungeon_b denies on combat level -> neither gate's key (incl. tf_key_a, which the player
        // does hold) should be consumed, proving the two-phase evaluate-then-consume contract holds.
        assertTrue(hasAnyCatalogKey(player, "tf_key_a"));
    }

    @Test
    void unresolvableKeyIdActsAsPassThroughNotAsMissingKey() throws Exception {
        DungeonGateConfig gateConfig = loadViaFakePlugin("""
                gates:
                  dungeon_typo:
                    required-combat-level: 0
                    key-item: totally_bogus_id_no_such_thing
                    key-amount: 1
                """);
        SymmetricCombatService combat = mock(SymmetricCombatService.class);
        when(combat.combatLevelOf(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(1);
        DungeonGateService service = new DungeonGateService(gateConfig, combat, itemResolver);

        PlayerMock player = server.addPlayer(); // no items at all

        assertTrue(service.checkEntry(player, "dungeon_typo"));
    }

    @Test
    void isPassValidBoundary() {
        // 2026-07-27 一回限りの通行許可の純粋な期限判定(実時間で10秒待たずに境界値を検証する)。
        assertTrue(DungeonGateService.isPassValid(100L, 50L));
        assertFalse(DungeonGateService.isPassValid(100L, 100L)); // ちょうど期限=無効
        assertFalse(DungeonGateService.isPassValid(100L, 150L));
        assertFalse(DungeonGateService.isPassValid(null, 50L));
    }

    @Test
    void oneTimePassAllowsExactlyOneEntryThenFallsBackToNormalCheck() throws Exception {
        // 発行→1回だけ通る→2回目は通常判定に戻る。
        DungeonGateConfig gateConfig = loadViaFakePlugin("""
                gates:
                  dungeon_sanctum:
                    required-combat-level: 0
                    key-item: tf_crypt_sigil
                    key-amount: 1
                """);
        resolvableAsCatalogItem("tf_crypt_sigil");
        SymmetricCombatService combat = mock(SymmetricCombatService.class);
        when(combat.combatLevelOf(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(50);
        DungeonGateService service = new DungeonGateService(gateConfig, combat, itemResolver);

        PlayerMock player = server.addPlayer(); // 鍵を持っていない

        service.grantOneTimePass(player.getUniqueId(), "dungeon_sanctum");
        // パスがあるので鍵なしでも1回目は通る。
        assertTrue(service.checkEntry(player, "dungeon_sanctum"));
        // パスは使い切りなので2回目は通常判定に戻り、鍵が無いので拒否される。
        assertFalse(service.checkEntry(player, "dungeon_sanctum"));
    }

    @Test
    void oneTimePassNotConsumedWhenBatchDeniedByAnotherGate() throws Exception {
        // パスの消費は「入場が実際に許可された時」に限る: 同時に評価された別ゲートがレベル不足で
        // 拒否された場合、パス自体は消費されず温存されること。
        PlayerMock player = server.addPlayer();
        String worldName = player.getWorld().getName();
        DungeonGateConfig gateConfig = loadViaFakePlugin("""
                gates:
                  dungeon_a:
                    required-combat-level: 0
                    region:
                      world: %1$s
                      min: [0, 0, 0]
                      max: [10, 10, 10]
                  dungeon_b:
                    required-combat-level: 999
                    region:
                      world: %1$s
                      min: [0, 0, 0]
                      max: [10, 10, 10]
                """.formatted(worldName));
        SymmetricCombatService combat = mock(SymmetricCombatService.class);
        when(combat.combatLevelOf(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(1);
        DungeonGateService service = new DungeonGateService(gateConfig, combat, itemResolver);

        service.grantOneTimePass(player.getUniqueId(), "dungeon_a");
        org.bukkit.World world = player.getWorld();
        org.bukkit.Location from = new org.bukkit.Location(world, -5, 5, 5);
        org.bukkit.Location to = new org.bukkit.Location(world, 5, 5, 5);

        // dungeon_bのレベル不足でバッチ全体が拒否される。
        assertFalse(service.checkRegionEntry(player, from, to, false));
        // dungeon_a用のパスは(バッチが許可されなかったので)消費されず残っている -> 単独評価では通る。
        assertTrue(service.checkEntry(player, "dungeon_a"));
    }

    @Test
    void clearOneTimePassesRemovesGrantedPass() throws Exception {
        DungeonGateConfig gateConfig = loadViaFakePlugin("""
                gates:
                  dungeon_sanctum:
                    required-combat-level: 999
                """);
        SymmetricCombatService combat = mock(SymmetricCombatService.class);
        when(combat.combatLevelOf(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(1);
        DungeonGateService service = new DungeonGateService(gateConfig, combat, itemResolver);

        PlayerMock player = server.addPlayer();
        service.grantOneTimePass(player.getUniqueId(), "dungeon_sanctum");
        service.clearOneTimePasses(player.getUniqueId());

        // ログアウト等でパスが掃除された後は、通常判定に戻りレベル不足で拒否される。
        assertFalse(service.checkEntry(player, "dungeon_sanctum"));
    }

    private static boolean hasAnyCatalogKey(PlayerMock player, String catalogId) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && CrossPluginItemResolver.idOf(stack).map(catalogId::equals).orElse(false)) {
                return true;
            }
        }
        return false;
    }
}
