package com.trinityforge.progression;

import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.skilltree.runtime.PerkAttributeApplier;
import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * コレクション図鑑 (M7) の記録/段階解放: 新規エントリのみPDCへ追記され、登録数がしきい値へ
 * 到達した未解放ティアが一度だけ解放されること(重複記録・再付与なし)を検証する。
 */
class CollectionServiceTest {

    private static final Logger LOG = Logger.getLogger("CollectionServiceTest");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private CollectionConfig loadedConfig(File dir, String yaml) throws IOException {
        File file = new File(dir, CollectionConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        CollectionConfig config = new CollectionConfig();
        config.load(fakePlugin(dir));
        return config;
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> LOG;
            case "saveResource" -> null; // テストはファイルを事前に書くので配布不要
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    @Test
    void recordsOnlyNewEntriesAndGrantsTierOnce(@TempDir File dir) throws IOException {
        CollectionConfig config = loadedConfig(dir, """
                enabled: true
                reward-tiers:
                  bronze:
                    threshold: 2
                    title: "駆け出し収集家"
                """);
        CollectionService service = new CollectionService(config, LOG);
        Player player = server.addPlayer();

        assertEquals(1, service.record(player, Set.of(CollectionService.itemEntryId("core_ember"))));
        // 既知エントリは記録されない(PDCは1件のまま)。
        assertEquals(0, service.record(player, Set.of(CollectionService.itemEntryId("core_ember"))));
        PlayerData data = PlayerData.of(player);
        // 新形式 id|epochMillis|maxQualityPt で永続化される(品質pt指定無し=0のオーバーロード)。
        List<CollectionRecord> records = service.records(player);
        assertEquals(1, records.size());
        assertEquals("item:core_ember", records.get(0).id());
        assertEquals(0, records.get(0).maxQualityPt());
        assertTrue(data.claimedCollectionTiers().isEmpty(), "しきい値未達でティアは解放されない");

        // 2種目でしきい値2到達 → bronze解放。
        assertEquals(1, service.record(player, Set.of(CollectionService.mobEntryId("ZOMBIE"))));
        assertEquals(List.of("bronze"), data.claimedCollectionTiers());

        // 追い付き評価を再実行しても再付与されない(冪等)。
        service.grantPendingTiers(player);
        assertEquals(List.of("bronze"), data.claimedCollectionTiers());
    }

    @Test
    void disabledConfigRecordsNothing(@TempDir File dir) throws IOException {
        CollectionConfig config = loadedConfig(dir, "enabled: false");
        CollectionService service = new CollectionService(config, LOG);
        Player player = server.addPlayer();

        assertEquals(0, service.record(player, Set.of(CollectionService.itemEntryId("x"))));
        assertTrue(PlayerData.of(player).collectionEntries().isEmpty());
    }

    @Test
    void qualityUpdatesOnlyWhenHigherAndDoesNotCountAsNewDiscovery(@TempDir File dir) throws IOException {
        CollectionConfig config = loadedConfig(dir, "enabled: true");
        CollectionService service = new CollectionService(config, LOG);
        Player player = server.addPlayer();
        String entryId = CollectionService.itemEntryId("core_ember");

        assertEquals(1, service.record(player, java.util.Map.of(entryId, 40)));
        assertEquals(40, service.records(player).get(0).maxQualityPt());

        // 既知だが品質ptが低い→新規扱いにならず(戻り値0)、品質ptも据え置き。
        assertEquals(0, service.record(player, java.util.Map.of(entryId, 10)));
        assertEquals(40, service.records(player).get(0).maxQualityPt());

        // 既知で品質ptがより高い→戻り値0(新規登録数としては数えない)だが品質ptは更新される。
        assertEquals(0, service.record(player, java.util.Map.of(entryId, 90)));
        assertEquals(90, service.records(player).get(0).maxQualityPt());
    }

    @Test
    void legacyBareIdEntryIsReadBackWithZeroEpochAndQuality(@TempDir File dir) throws IOException {
        CollectionConfig config = loadedConfig(dir, "enabled: true");
        CollectionService service = new CollectionService(config, LOG);
        Player player = server.addPlayer();
        // 旧形式(idのみ)を直接PDCへ書き込み、後方互換読み替えを検証する。
        PlayerData.of(player).setCollectionEntries(List.of("item:legacy_only"));

        List<CollectionRecord> records = service.records(player);
        assertEquals(1, records.size());
        assertEquals("item:legacy_only", records.get(0).id());
        assertEquals(0L, records.get(0).epochMillis());
        assertEquals(0, records.get(0).maxQualityPt());
    }

    @Test
    void grantsItemsVanillaExpAndJobExpOnTierUnlockWhenDependenciesInjected(@TempDir File dir) throws IOException {
        CollectionConfig config = loadedConfig(dir, """
                enabled: true
                reward-tiers:
                  bronze:
                    threshold: 1
                    vanilla-exp: 50
                    job-exp:
                      - skill: FARMING
                        amount: 20.0
                    items:
                      - id: diamond
                        amount: 2
                """);
        CrossPluginItemResolver itemResolver = mock(CrossPluginItemResolver.class);
        when(itemResolver.create("diamond")).thenReturn(Optional.of(new ItemStack(Material.DIAMOND)));
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        CollectionService service = new CollectionService(config, LOG, itemResolver, dispatcher);
        Player player = server.addPlayer();
        int expBefore = player.getTotalExperience();

        assertEquals(1, service.record(player, Set.of(CollectionService.itemEntryId("core_ember"))));

        assertTrue(player.getTotalExperience() > expBefore, "vanilla-expがgiveExpされること");
        verify(dispatcher).grant(player.getUniqueId(), "FARMING", 20.0);
        ItemStack found = null;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == Material.DIAMOND) {
                found = stack;
                break;
            }
        }
        assertTrue(found != null && found.getAmount() == 2, "amount指定通りに付与されること");
    }

    @Test
    void skipsItemAndJobExpRewardsWhenDependenciesNotInjected(@TempDir File dir) throws IOException {
        // 2引数コンストラクタ(既存テスト互換): itemResolver/experienceDispatcherがnullでも
        // 例外にならず、単に該当報酬をスキップすること。
        CollectionConfig config = loadedConfig(dir, """
                enabled: true
                reward-tiers:
                  bronze:
                    threshold: 1
                    items:
                      - id: diamond
                    job-exp:
                      - skill: FARMING
                        amount: 20.0
                """);
        CollectionService service = new CollectionService(config, LOG);
        Player player = server.addPlayer();

        assertEquals(1, service.record(player, Set.of(CollectionService.itemEntryId("core_ember"))));

        assertTrue(player.getInventory().isEmpty(), "itemResolver未注入ならアイテムは付与されない");
    }

    @Test
    void tierUnlockCallsPerkAttributeApplierApplyWhenInjected(@TempDir File dir) throws IOException {
        // 修正A: ATTRIBUTE系永続バフ(move_speed等)は次回join/防具変更まで反映されないため、
        // ティア解放成功後に perkAttributeApplier.apply(player) が即座に呼ばれること。
        CollectionConfig config = loadedConfig(dir, """
                enabled: true
                reward-tiers:
                  bronze:
                    threshold: 1
                    permanent-buffs:
                      move-speed: 0.02
                """);
        PerkAttributeApplier applier = mock(PerkAttributeApplier.class);
        CollectionService service = new CollectionService(config, LOG, null, null, applier);
        Player player = server.addPlayer();

        assertEquals(1, service.record(player, Set.of(CollectionService.itemEntryId("core_ember"))));

        assertEquals(List.of("bronze"), PlayerData.of(player).claimedCollectionTiers());
        verify(applier).apply(player);
    }

    @Test
    void noTierUnlockDoesNotCallPerkAttributeApplier(@TempDir File dir) throws IOException {
        // ティアが解放されない(しきい値未達)ならapplyは呼ばれない。
        CollectionConfig config = loadedConfig(dir, """
                enabled: true
                reward-tiers:
                  bronze:
                    threshold: 5
                """);
        PerkAttributeApplier applier = mock(PerkAttributeApplier.class);
        CollectionService service = new CollectionService(config, LOG, null, null, applier);
        Player player = server.addPlayer();

        assertEquals(1, service.record(player, Set.of(CollectionService.itemEntryId("core_ember"))));

        org.mockito.Mockito.verifyNoInteractions(applier);
    }
}
