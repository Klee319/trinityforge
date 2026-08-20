package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.PreviewRollSeeds;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock;
import org.mockbukkit.mockbukkit.inventory.SmithingInventoryMock;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /**
     * {@code assemble(...)} を素通し(0を返すだけ)で止めた mock。W-51 の再スタンプ系テストは
     * この mock への呼び出し引数(material/quality/rollSeed)を検証することで、実際の数値導出
     * ({@link ItemAssembler} 自体の責務、他所のテストで担保済み)に踏み込まずに
     * 「新Materialで正しく再組み立てが呼ばれたか」だけを確かめる。
     */
    private static ItemAssembler mockAssembler() {
        ItemAssembler assembler = Mockito.mock(ItemAssembler.class);
        Mockito.when(assembler.assemble(
                        ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.anyLong(), ArgumentMatchers.anyInt()))
                .thenReturn(0);
        return assembler;
    }

    private static ItemFactory factory() {
        return new ItemFactory(mockAssembler());
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
     * バニラのダイヤ装備→ネザライト装備は TF のカタログIDを持たないので、結果を「消す」ことは無い
     * (base 側の PDC が結果に引き継がれるケースも「素材側のID」なので消去対象外)。
     * ただし TF 品質 PDC(rollSeed) が無い、一度も TF に触られていない完全な素の装備は
     * 再スタンプの対象にもならず素通しのまま(仕様変更の対象外 — 救済は「TF 品質を持つ既存装備」だけ)。
     */
    @Test
    void bareVanillaEquipmentWithoutQualityIsLeftAlone(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        ItemAssembler assembler = mockAssembler();
        CatalogSmithingListener listener = new CatalogSmithingListener(catalog, new ItemFactory(assembler));

        PrepareSmithingEvent event = event(netheriteTemplate(),
                new ItemStack(Material.DIAMOND_SWORD), netheriteIngot(),
                new ItemStack(Material.NETHERITE_SWORD));
        listener.onPrepare(event);

        assertNotNull(event.getResult());
        assertEquals(Material.NETHERITE_SWORD, event.getResult().getType());
        Mockito.verify(assembler, Mockito.never()).assemble(
                ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyInt());
    }

    /**
     * W-51(2026-08-18): 素のバニラ装備でも TF 品質 PDC(rollSeed) を持っていれば、ネザライト化で
     * 「素通し」ではなく「品質を引き継ぎつつ再スタンプ」される(仕様変更 — 旧仕様は
     * {@code unrelatedVanillaUpgradeResultIsLeftAlone} という名前でこの素通しを固定していたが、
     * それこそが「lore・耐久上限・use-level-requirement がダイヤ時代の値のまま凍結される」バグの
     * 本体だった)。
     *
     * <p>{@link ItemAssembler} は mock なので数値導出そのもの({@code useLevelRequirement} の実値等)
     * はここでは検証しない(それは {@code ItemAssemblerTest} 等の責務)。ここで固定するのは
     * 「{@link ItemFactory#stamp} が新Material(NETHERITE_SWORD)・引き継いだ品質(5)・base とは
     * 異なる rollSeed(プレビューは {@link PreviewRollSeeds#SMITHING} 固定)で呼ばれること」という
     * このリスナーの配線責務。
     */
    @Test
    void plainQualityVanillaUpgradeIsRestampedWithInheritedQuality(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        ItemAssembler assembler = mockAssembler();
        CatalogSmithingListener listener = new CatalogSmithingListener(catalog, new ItemFactory(assembler));

        ItemStack base = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta baseMeta = base.getItemMeta();
        ItemData.of(baseMeta).setRollSeed(999L);
        ItemData.of(baseMeta).setQuality(5);
        base.setItemMeta(baseMeta);

        PrepareSmithingEvent event = event(netheriteTemplate(), base, netheriteIngot(),
                new ItemStack(Material.NETHERITE_SWORD));
        listener.onPrepare(event);

        ItemStack result = event.getResult();
        assertNotNull(result, "品質付きバニラ装備は再スタンプ対象になるはず");
        assertEquals(Material.NETHERITE_SWORD, result.getType());

        ArgumentCaptor<Material> materialCaptor = ArgumentCaptor.forClass(Material.class);
        ArgumentCaptor<Long> seedCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> qualityCaptor = ArgumentCaptor.forClass(Integer.class);
        Mockito.verify(assembler).assemble(ArgumentMatchers.any(),
                materialCaptor.capture(), seedCaptor.capture(), qualityCaptor.capture());
        assertEquals(Material.NETHERITE_SWORD, materialCaptor.getValue(),
                "新Material(ネザライト)基準で再組み立てされていない");
        assertEquals(5, qualityCaptor.getValue(), "品質がbaseから引き継がれていない");
        assertEquals(PreviewRollSeeds.SMITHING, (long) seedCaptor.getValue(),
                "プレビューはカタログ品と同じ固定プレビューseedで見せるべき");
        assertNotEquals(999L, (long) seedCaptor.getValue(),
                "rollSeedがbaseのまま(=再抽選されていない)");
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

    /**
     * 実サーバ報告「ネザライト化したときにエンチャントがはがれる」(2026-08-05)。
     *
     * <p>このリスナーは成果物を {@code itemFactory.create(...)} で<b>作り直す</b>ので、
     * 何もしなければ素材側のエンチャントは丸ごと消える。バニラのネザライト強化は保持するため、
     * プレイヤーから見ると取り返しのつかない損失になる。
     */
    @Test
    void netheriteUpgradeKeepsTheEnchantmentsOnTheSourceItem(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        ItemFactory itemFactory = factory();
        CatalogSmithingListener listener = new CatalogSmithingListener(catalog, itemFactory);
        ItemTemplate source = catalog.template("diamond_bow").orElseThrow();

        ItemStack base = itemFactory.createIdentityOnly(source);
        base.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.POWER, 4);
        base.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 3);

        PrepareSmithingEvent event = event(netheriteTemplate(), base, netheriteIngot(),
                new ItemStack(Material.BOW));
        listener.onPrepare(event);

        ItemStack result = event.getResult();
        assertNotNull(result);
        assertEquals(4, result.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.POWER),
                "射撃ダメージ増加IVが引き継がれていない");
        assertEquals(3, result.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING),
                "耐久力IIIが引き継がれていない");
    }

    /**
     * 上限突破パークで素材が上限超えのレベルを持っていても、引き継ぎで削られないこと。
     * ({@code addUnsafeEnchantment} を使っている理由の固定)
     */
    @Test
    void netheriteUpgradeKeepsOverCapEnchantLevels(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir);
        ItemFactory itemFactory = factory();
        CatalogSmithingListener listener = new CatalogSmithingListener(catalog, itemFactory);
        ItemTemplate source = catalog.template("diamond_bow").orElseThrow();

        int overCap = org.bukkit.enchantments.Enchantment.POWER.getMaxLevel() + 2;
        ItemStack base = itemFactory.createIdentityOnly(source);
        base.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.POWER, overCap);

        PrepareSmithingEvent event = event(netheriteTemplate(), base, netheriteIngot(),
                new ItemStack(Material.BOW));
        listener.onPrepare(event);

        assertEquals(overCap,
                event.getResult().getEnchantmentLevel(org.bukkit.enchantments.Enchantment.POWER),
                "上限突破分がネザライト強化で削られている");
    }
}
