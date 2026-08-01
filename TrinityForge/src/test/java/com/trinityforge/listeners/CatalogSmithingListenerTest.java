package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock;
import org.mockbukkit.mockbukkit.inventory.SmithingInventoryMock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link CatalogSmithingListener} の {@code PrepareSmithingEvent} 挙動。
 *
 * <p>U7 で {@code CatalogRecipeRegistrar} が {@code method: netherite} を
 * {@code SmithingTransformRecipe} として登録するようになった結果、Bukkit 側の base 判定は
 * 「材質だけ」の緩い一致になった。素のバニラ素材でも一致してしまうので、精密照合が外れたときに
 * リスナーが結果を消すことが「素の弓＋インゴット→ネザライトの弓」の抜け道を塞ぐ唯一の防波堤になる。
 */
class CatalogSmithingListenerTest {

    private static final String CATALOG_YAML = """
            items:
              diamond_bow:
                material: BOW
                display-name: ダイヤモンドの弓
                custom-model-data: 1096
              netherite_bow:
                material: BOW
                display-name: ネザライトの弓
                custom-model-data: 1097
                recipe:
                  method: netherite
                  source-item: custom:diamond_bow
            """;

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CatalogSmithingListenerTest");
            case "saveResource" -> throw new AssertionError("file exists; saveResource must not be called");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static ItemCatalogConfig loadCatalog(File tempDir) throws IOException {
        File file = new File(tempDir, ItemCatalogConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), CATALOG_YAML);
        ItemCatalogConfig config = new ItemCatalogConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    private static ItemFactory factory() {
        ItemAssembler assembler = org.mockito.Mockito.mock(ItemAssembler.class);
        org.mockito.Mockito.when(assembler.assemble(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(0);
        return new ItemFactory(assembler);
    }

    /** 鍛冶台の 3 スロット + 結果を持つイベントを組み立てる。 */
    private PrepareSmithingEvent event(ItemStack template, ItemStack base, ItemStack addition,
                                       ItemStack currentResult) {
        SmithingInventoryMock inventory = new SmithingInventoryMock(null);
        inventory.setItem(0, template);
        inventory.setItem(1, base);
        inventory.setItem(2, addition);
        Player player = server.addPlayer();
        SimpleInventoryViewMock view = new SimpleInventoryViewMock(
                player, inventory, player.getInventory(), InventoryType.SMITHING);
        return new PrepareSmithingEvent(view, currentResult);
    }

    private static ItemStack netheriteTemplate() {
        return new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
    }

    private static ItemStack netheriteIngot() {
        return new ItemStack(Material.NETHERITE_INGOT);
    }

    @Test
    void matchingSourceItemProducesTheCatalogResult(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        ItemFactory itemFactory = factory();
        CatalogSmithingListener listener = new CatalogSmithingListener(catalog, itemFactory);
        ItemTemplate source = catalog.template("diamond_bow").orElseThrow();

        PrepareSmithingEvent event = event(netheriteTemplate(),
                itemFactory.createIdentityOnly(source), netheriteIngot(),
                new ItemStack(Material.BOW));
        listener.onPrepare(event);

        ItemStack result = event.getResult();
        assertNotNull(result);
        assertEquals("netherite_bow",
                ItemData.of(result.getItemMeta()).catalogId().orElseThrow());
    }

    /**
     * 登録レシピの base は BOW という材質だけなので、素のバニラ弓でも Bukkit 側は一致して
     * ネザライトの弓を組み上げてしまう。精密照合が外れた以上、結果は消さなければならない。
     */
    @Test
    void plainVanillaBaseDoesNotYieldTheNetheriteCatalogItem(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        ItemFactory itemFactory = factory();
        CatalogSmithingListener listener = new CatalogSmithingListener(catalog, itemFactory);
        ItemStack registrarResult =
                itemFactory.createIdentityOnly(catalog.template("netherite_bow").orElseThrow());

        PrepareSmithingEvent event = event(netheriteTemplate(),
                new ItemStack(Material.BOW), netheriteIngot(), registrarResult);
        listener.onPrepare(event);

        assertNull(event.getResult(), "素の弓からネザライトの弓が作れてはいけない");
    }

    /** 完成済みを再投入しても無限アップグレードにならないこと。 */
    @Test
    void alreadyUpgradedBaseDoesNotYieldAnotherNetheriteCatalogItem(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        ItemFactory itemFactory = factory();
        CatalogSmithingListener listener = new CatalogSmithingListener(catalog, itemFactory);
        ItemTemplate netheriteBow = catalog.template("netherite_bow").orElseThrow();

        PrepareSmithingEvent event = event(netheriteTemplate(),
                itemFactory.createIdentityOnly(netheriteBow), netheriteIngot(),
                itemFactory.createIdentityOnly(netheriteBow));
        listener.onPrepare(event);

        assertNull(event.getResult());
    }

    /**
     * バニラのダイヤ装備→ネザライト装備は TF のカタログIDを持たないので、消してはいけない
     * (base 側の PDC が結果に引き継がれるケースも「素材側のID」なので対象外)。
     */
    @Test
    void unrelatedVanillaUpgradeResultIsLeftAlone(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        CatalogSmithingListener listener = new CatalogSmithingListener(catalog, factory());

        PrepareSmithingEvent event = event(netheriteTemplate(),
                new ItemStack(Material.DIAMOND_SWORD), netheriteIngot(),
                new ItemStack(Material.NETHERITE_SWORD));
        listener.onPrepare(event);

        assertNotNull(event.getResult());
        assertEquals(Material.NETHERITE_SWORD, event.getResult().getType());
    }

    /** 素材側のカタログIDが結果に引き継がれても、そのIDが netherite レシピを持たないなら消さない。 */
    @Test
    void vanillaResultCarryingTheSourceCatalogIdIsLeftAlone(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        ItemFactory itemFactory = factory();
        CatalogSmithingListener listener = new CatalogSmithingListener(catalog, itemFactory);
        // 「バニラのレシピが base の component を引き継いだ」状況を再現: 結果に素材側のIDが載る。
        ItemStack carried = new ItemStack(Material.NETHERITE_SWORD);
        ItemStack sourceLike = itemFactory.createIdentityOnly(catalog.template("diamond_bow").orElseThrow());
        var meta = carried.getItemMeta();
        ItemData.of(sourceLike.getItemMeta()).catalogId()
                .ifPresent(id -> ItemData.of(meta).setCatalogId(id));
        carried.setItemMeta(meta);

        PrepareSmithingEvent event = event(netheriteTemplate(),
                new ItemStack(Material.DIAMOND_SWORD), netheriteIngot(), carried);
        listener.onPrepare(event);

        assertNotNull(event.getResult(), "diamond_bow は netherite レシピを持たないので消してはいけない");
    }

    /** ネザライト強化以外(トリム等)には一切干渉しない。 */
    @Test
    void nonNetheriteSmithingIsUntouched(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        ItemFactory itemFactory = factory();
        CatalogSmithingListener listener = new CatalogSmithingListener(catalog, itemFactory);
        ItemStack registrarResult =
                itemFactory.createIdentityOnly(catalog.template("netherite_bow").orElseThrow());

        PrepareSmithingEvent event = event(
                new ItemStack(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE),
                new ItemStack(Material.DIAMOND_HELMET),
                new ItemStack(Material.EMERALD),
                registrarResult);
        listener.onPrepare(event);

        assertNotNull(event.getResult(), "トリムの結果に手を出してはいけない");
    }
}
