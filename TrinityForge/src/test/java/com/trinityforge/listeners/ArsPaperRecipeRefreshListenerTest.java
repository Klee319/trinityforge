package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import com.trinityforge.stats.ExternalItemRegistry;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.MaterialLists;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.Recipe;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W-44 の回帰テスト: 「素材が ArsPaper 側 {@code materials.yml} にしか無いレシピ」が、TF の初回登録で
 * 解決できずスキップされたまま<b>永久にクラフト不可</b>になっていた事故。
 *
 * <p>再現している構図は出荷 yml の {@code key_binder} と同じ:
 * {@code items/material-lists.yml} の {@code dungeon_seals} は 28 件すべてが ArsPaper 定義の
 * {@code custom:} id で、TF は ArsPaper より先に enable するため
 * {@code CatalogRecipeRegistrar} の初回 {@code registerAll()} では 1 件も解決できない。
 *
 * <p>ここで検証する契約は 2 つ:
 * <ol>
 *   <li>ArsPaper 導入済み・未 enable の間に未解決だった {@code list:} メンバーは「保留」であって
 *       エラーではない (WARNING を出さず {@code deferredArsCatalogIds} に載る)。</li>
 *   <li>ArsPaper の {@link PluginEnableEvent} を TF 自身が拾って再登録し、レシピが実際に
 *       {@code Bukkit} へ登録される (フォーク側の enable フック頼みにしない)。</li>
 * </ol>
 */
class ArsPaperRecipeRefreshListenerTest {

    private static final String LIST_ID = "test_seals";
    private static final String EXTERNAL_ID = "dungeon_seal_test";

    private ServerMock server;
    private final List<LogRecord> logRecords = new ArrayList<>();
    private Logger captureLogger;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        logRecords.clear();
        captureLogger = Logger.getLogger("ArsPaperRecipeRefreshListenerTest-" + System.nanoTime());
        captureLogger.setUseParentHandlers(false);
        captureLogger.setLevel(Level.ALL);
        captureLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logRecords.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        // 静的スナップショットはテスト間で漏れるので毎回初期化する。
        ExternalItemRegistry.update(Map.of());
        ExternalItemRegistry.updateExternalPlugin("arspaper", Map.of());
        MaterialLists.update(Map.of(), Map.of(), Map.of());
    }

    @AfterEach
    void tearDown() {
        ExternalItemRegistry.update(Map.of());
        ExternalItemRegistry.updateExternalPlugin("arspaper", Map.of());
        MaterialLists.update(Map.of(), Map.of(), Map.of());
        MockBukkit.unmock();
    }

    /** ArsPaper 定義の custom: id だけをメンバーに持つ互換リスト (= 出荷の dungeon_seals と同じ形)。 */
    private static void defineArsOnlyList() {
        MaterialLists.update(Map.of(LIST_ID, Set.<Material>of()),
                Map.of(LIST_ID, Set.of(EXTERNAL_ID)),
                Map.of(LIST_ID, "テスト用の印"));
    }

    /** ArsPaper が enable 時に push する ExternalItemRegistry の層を再現する。 */
    private static void pushArsExternalItems() {
        ExternalItemRegistry.updateExternalPlugin("arspaper", Map.of(EXTERNAL_ID,
                new ExternalItemRegistry.Definition(EXTERNAL_ID, Material.PAPER, 5480, "テストの印")));
    }

    private Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> captureLogger;
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private ItemCatalogConfig loadCatalog(File tempDir) throws IOException {
        File file = new File(tempDir, ItemCatalogConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), """
                items:
                  binder:
                    material: TRIAL_KEY
                    recipe:
                      method: workbench
                      type: shapeless
                      ingredients:
                        - list:%s
                        - list:%s
                      amount: 1
                """.formatted(LIST_ID, LIST_ID));
        ItemCatalogConfig config = new ItemCatalogConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    private static ItemFactory factoryWithMockAssembler() {
        ItemAssembler assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenReturn(0);
        return new ItemFactory(assembler);
    }

    private static boolean recipeRegistered(String keyName) {
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe = it.next();
            if (recipe instanceof Keyed keyed
                    && "trinityforge".equals(keyed.getKey().getNamespace())
                    && keyName.equals(keyed.getKey().getKey())) {
                return true;
            }
        }
        return false;
    }

    private List<String> warnings() {
        return logRecords.stream()
                .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
                .map(LogRecord::getMessage)
                .toList();
    }

    /** ArsPaper を「導入済みだがまだ未 enable」の状態にする (TF が先に enable する実機と同じ)。 */
    private Plugin arsPaperNotYetEnabled() {
        Plugin ars = MockBukkit.createMockPlugin("ArsPaper");
        server.getPluginManager().disablePlugin(ars);
        assertFalse(ars.isEnabled(), "前提: ArsPaper はまだ enable していない");
        return ars;
    }

    @Test
    @DisplayName("ArsPaper未enable中のlist:メンバー未解決は警告ではなく保留として扱う")
    void unresolvedListMemberIsDeferredWhileArsPaperIsStillLoading(@TempDir File tempDir) throws IOException {
        defineArsOnlyList();
        arsPaperNotYetEnabled();
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), loadCatalog(tempDir), factoryWithMockAssembler());

        registrar.registerAll();

        assertFalse(recipeRegistered("catalog_binder"),
                "ArsPaper 未 enable の間はまだ登録できない (素材が解決できないため)");
        assertEquals(Set.of("binder"), registrar.deferredArsCatalogIds(),
                "ArsPaper 未 enable による未解決は『保留』として記録され、後で再登録される対象になる");
        assertEquals(List.of(), warnings(),
                "一過性の未解決で WARNING を出すと、毎起動の誤アラームになる: " + warnings());
    }

    @Test
    @DisplayName("ArsPaperのenableをTF自身が拾って、保留していたレシピを登録し直す")
    void arsPaperEnableReRegistersDeferredRecipe(@TempDir File tempDir) throws IOException {
        defineArsOnlyList();
        Plugin ars = arsPaperNotYetEnabled();
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), loadCatalog(tempDir), factoryWithMockAssembler());
        registrar.registerAll();
        assertFalse(recipeRegistered("catalog_binder"), "前提: 初回登録では登録できていない");

        Plugin tf = MockBukkit.createMockPlugin("TrinityForge");
        ArsPaperRecipeRefreshListener listener = new ArsPaperRecipeRefreshListener(
                registrar::registerAll, registrar::deferredArsCatalogIds, captureLogger::warning);
        server.getPluginManager().registerEvents(listener, tf);

        // ArsPaper の onEnable 相当: materials.yml を ExternalItemRegistry へ push してから enable。
        pushArsExternalItems();
        server.getPluginManager().callEvent(new PluginEnableEvent(ars));

        assertTrue(listener.hasRefreshed(), "ArsPaper の PluginEnableEvent で再登録が走らねばならない");
        assertTrue(recipeRegistered("catalog_binder"),
                "ArsPaper enable 後の再登録でレシピが登録されること (これが無いと永久にクラフト不可)");
        assertEquals(Set.of(), registrar.deferredArsCatalogIds(), "保留は解消されている");
        assertEquals(List.of(), warnings(), "解決できたのだから警告は出ない: " + warnings());
    }

    @Test
    @DisplayName("他プラグインのenableでは再登録しない")
    void otherPluginEnableDoesNotRefresh(@TempDir File tempDir) throws IOException {
        defineArsOnlyList();
        arsPaperNotYetEnabled();
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), loadCatalog(tempDir), factoryWithMockAssembler());
        registrar.registerAll();

        Plugin tf = MockBukkit.createMockPlugin("TrinityForge");
        ArsPaperRecipeRefreshListener listener = new ArsPaperRecipeRefreshListener(
                registrar::registerAll, registrar::deferredArsCatalogIds, captureLogger::warning);
        server.getPluginManager().registerEvents(listener, tf);

        Plugin other = MockBukkit.createMockPlugin("SomeOtherPlugin");
        pushArsExternalItems();
        server.getPluginManager().callEvent(new PluginEnableEvent(other));

        assertFalse(listener.hasRefreshed(), "ArsPaper 以外の enable では走らない");
        assertFalse(recipeRegistered("catalog_binder"), "再登録していないのだから状態は変わらない");
    }

    @Test
    @DisplayName("再登録しても解決できないIDが残ったら警告する")
    void stillUnresolvedAfterRefreshWarns(@TempDir File tempDir) throws IOException {
        defineArsOnlyList();
        Plugin ars = arsPaperNotYetEnabled();
        CatalogRecipeRegistrar registrar = new CatalogRecipeRegistrar(
                fakePlugin(tempDir), loadCatalog(tempDir), factoryWithMockAssembler());
        registrar.registerAll();

        Plugin tf = MockBukkit.createMockPlugin("TrinityForge");
        ArsPaperRecipeRefreshListener listener = new ArsPaperRecipeRefreshListener(
                registrar::registerAll, registrar::deferredArsCatalogIds, captureLogger::warning);
        server.getPluginManager().registerEvents(listener, tf);

        // ArsPaper は enable したが、この id は push してこなかった (綴り違い等の本当の設定ミス)。
        server.getPluginManager().callEvent(new PluginEnableEvent(ars));

        assertTrue(listener.hasRefreshed());
        assertFalse(warnings().isEmpty(), "本当に解決できない id は黙って落とさず警告する");
        assertTrue(warnings().stream().anyMatch(message -> message.contains("binder")),
                "警告には該当カタログ id が含まれる: " + warnings());
    }
}
