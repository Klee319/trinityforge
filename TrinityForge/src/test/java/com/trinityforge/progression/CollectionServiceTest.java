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

    /**
     * 遡り登録 (2026-07-31, K-11): {@code announce=false} では 1件ごとの「図鑑に登録」チャットも、
     * {@code broadcast: true} のサーバー全体告知も出さない。報酬(claimed への記録)は通常どおり行う。
     *
     * <p>K-11(素のバニラ品が1件も記録されていなかった)の修正で、既存プレイヤーの参加時に
     * 最大16件が一括登録される。通知したままだと t3(60)/t4(120)/t5(200) を跨いだ人数分の
     * 全体告知が連続発火して事故に見えるため、遡り分だけ黙らせる口を入れた。
     *
     * <p><b>全体告知に落ちていないことをどう確かめているか</b>: MockBukkit の
     * {@code Bukkit.getServer().sendMessage(Component)} は<b>未実装</b>で
     * {@code UnimplementedOperationException} を投げる(このテストを書く過程で実測)。つまり
     * 抑止が壊れて broadcast 経路へ入った瞬間にテストは例外で中断し、
     * {@code UnintendedSkipGuardListener} がそれをビルド失敗に変える。加えて本人通知
     * ({@code player.sendMessage}) は else 側にしか無いので、「本人が受け取っている」こと自体が
     * 非broadcast経路を通った証拠になる。逆向き(broadcast: true が実際に全体告知になること)は
     * MockBukkit では実行できないため、ここでは固定できない。
     */
    @Test
    void retroactiveRecordSuppressesEntryChatAndTierBroadcast(@TempDir File dir) throws IOException {
        CollectionConfig config = loadedConfig(dir, """
                enabled: true
                reward-tiers:
                  bronze:
                    threshold: 1
                    title: "駆け出し収集家"
                    broadcast: true
                """);
        CollectionService service = new CollectionService(config, LOG);
        Player player = server.addPlayer();
        drainMessages(player);

        assertEquals(1, service.record(player,
                java.util.Map.of(CollectionService.itemEntryId("core_ember"), 0), false));

        assertEquals(List.of("bronze"), PlayerData.of(player).claimedCollectionTiers(),
                "通知を抑止しても報酬ティアの解放そのものは通常どおり行う");
        List<String> messages = allMessages(player);
        assertTrue(messages.stream().noneMatch(m -> m.contains("図鑑に登録")),
                "遡り登録では1件ごとのチャットを出さない(最大16行流れる)");
        assertTrue(messages.stream().anyMatch(m -> m.contains("コレクション報酬解放")),
                "報酬が付与された事実は本人にだけ伝える(黙って称号が増えると理由が分からない)");
        assertTrue(messages.stream().noneMatch(m -> m.contains(player.getName())),
                "全体告知フォーマット(\"<name> が...\")が本人の受信箱にも来ていないこと");
    }

    @Test
    void normalRecordStillAnnouncesEachNewEntry(@TempDir File dir) throws IOException {
        // 遡りでない通常経路(拾得・インベントリ操作)は従来どおり通知する。
        // broadcast は書かない(既定 false): MockBukkit は Server#sendMessage(Component) が
        // 未実装なので、全体告知そのものはテストから実行できない。
        CollectionConfig config = loadedConfig(dir, """
                enabled: true
                reward-tiers:
                  bronze:
                    threshold: 1
                    title: "駆け出し収集家"
                """);
        CollectionService service = new CollectionService(config, LOG);
        Player player = server.addPlayer();
        drainMessages(player);

        assertEquals(1, service.record(player, Set.of(CollectionService.itemEntryId("core_ember"))));

        List<String> messages = allMessages(player);
        assertTrue(messages.stream().anyMatch(m -> m.contains("図鑑に登録")),
                "通常の新規登録は1件ごとに通知する");
        assertTrue(messages.stream().anyMatch(m -> m.contains("コレクション報酬解放")),
                "ティア解放も通常どおり通知する");
    }

    private static void drainMessages(Player player) {
        allMessages(player);
    }

    /** PlayerMock の受信箱を空になるまで読み切る。 */
    private static List<String> allMessages(Player player) {
        List<String> out = new java.util.ArrayList<>();
        String message;
        while ((message = ((org.mockbukkit.mockbukkit.entity.PlayerMock) player).nextMessage()) != null) {
            out.add(message);
        }
        return out;
    }
}
