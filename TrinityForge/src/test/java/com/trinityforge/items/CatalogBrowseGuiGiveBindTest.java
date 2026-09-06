package com.trinityforge.items;

import com.trinityforge.config.domains.AttributeMappingConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.LoreConfig;
import com.trinityforge.config.domains.QualityTiersConfig;
import com.trinityforge.config.domains.SkillTreeConfig;
import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.AttributeApplier;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.LoreComposer;
import com.trinityforge.stats.TableGeneration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /tf catalog} の配布は Ars 実体を先に取るため {@link ItemFactory#create} を通らない。
 * catalog の bind-type と SOULBOUND の所有者は give 側で焼く。プレビューには付けない。
 */
class CatalogBrowseGuiGiveBindTest {

    private static final NamespacedKey THREAD_ITEM_TYPE_KEY =
            new NamespacedKey("arspaper", "thread_item_type");
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void extraCreateSoulboundThreadGetsCatalogBindAndOwnerLore(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  thread_backpack:
                    external-source: arspaper
                    material: STRING
                    display-name: "<green>pack</green>"
                    custom-model-data: 300016
                    bind-type: SOULBOUND
                """);
        ItemFactory factory = new ItemFactory(assembler(catalog));
        CatalogBrowseGui gui = new CatalogBrowseGui(
                MockBukkit.createMockPlugin(), catalog, factory, List::of,
                id -> Optional.of(arsThread(BindType.TRADEABLE)));
        PlayerMock player = MockBukkit.getMock().addPlayer("GiveOwner");
        ItemTemplate template = catalog.template("thread_backpack").orElseThrow();

        ItemStack given = arsThread(BindType.TRADEABLE);
        gui.stampGiveIdentity(given, template, player);

        ItemData data = ItemData.of(given.getItemMeta());
        assertEquals(BindType.SOULBOUND, data.bindType().orElseThrow(),
                "Ars 側の TRADEABLE を catalog の SOULBOUND で上書きする");
        assertEquals(player.getUniqueId(), data.owner().orElseThrow());
        assertTrue(lorePlain(given).contains("所有者"),
                "所有者 PDC だけ付けて lore が空だと魂縛解きの対象にだけ見える");

        ItemStack preview = gui.previewOf("thread_backpack");
        assertTrue(ItemData.of(preview.getItemMeta()).owner().isEmpty(),
                "プレビューに所有者を焼くと画面の見本が個人所有になる");
    }

    @Test
    void tradeableCatalogGiveDoesNotStampOwner(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  tradeable_token:
                    material: PAPER
                    display-name: token
                    custom-model-data: 1
                    bind-type: TRADEABLE
                """);
        ItemFactory factory = new ItemFactory(assembler(catalog));
        CatalogBrowseGui gui = new CatalogBrowseGui(MockBukkit.createMockPlugin(), catalog, factory);
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ItemStack stack = new ItemStack(Material.PAPER);
        gui.stampGiveIdentity(stack, catalog.template("tradeable_token").orElseThrow(), player);

        ItemData data = ItemData.of(stack.getItemMeta());
        assertEquals(BindType.TRADEABLE, data.bindType().orElseThrow());
        assertTrue(data.owner().isEmpty());
    }

    private static ItemStack arsThread(BindType bindType) {
        ItemStack stack = new ItemStack(Material.STRING);
        stack.editMeta(meta -> {
            meta.getPersistentDataContainer().set(THREAD_ITEM_TYPE_KEY, PersistentDataType.STRING, "backpack");
            ItemData data = ItemData.of(meta);
            data.setBindType(bindType);
            data.setRollSeed(1L);
        });
        return stack;
    }

    private static String lorePlain(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || meta.lore() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Component line : meta.lore()) {
            text.append(PLAIN.serialize(line)).append('\n');
        }
        return text.toString();
    }

    private static ItemAssembler assembler(ItemCatalogConfig catalog) {
        return new ItemAssembler(
                new ItemStatsConfig(),
                new AttributeMappingConfig(),
                new AttributeApplier(MockBukkit.createMockPlugin()),
                new LoreConfig(),
                new LoreComposer(),
                new QualityTiersConfig(),
                new TableGeneration(),
                catalog,
                new SkillTreeConfig(),
                new CraftingFeaturesConfig(),
                new SpecialRewardsConfig());
    }

    private static ItemCatalogConfig loadCatalog(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, ItemCatalogConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        ItemCatalogConfig config = new ItemCatalogConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CatalogBrowseGuiGiveBindTest");
            case "saveResource" -> throw new AssertionError("file exists; saveResource must not be called");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }
}
