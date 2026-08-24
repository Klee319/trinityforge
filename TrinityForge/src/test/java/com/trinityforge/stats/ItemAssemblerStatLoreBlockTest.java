package com.trinityforge.stats;

import com.trinityforge.config.domains.AttributeMappingConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.LoreConfig;
import com.trinityforge.config.domains.QualityTiersConfig;
import com.trinityforge.config.domains.SkillTreeConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.opentest4j.TestAbortedException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ItemAssembler#statLoreBlock} が<b>装備と同一の体裁</b>を返すことを固定する（2026-08-05）。
 *
 * <p>要望は「スレッドに表記するステータスの lore の体裁とフォントを通常の装備と同じにしてほしい」。
 * スレッドの lore はフォーク(ArsPaper)が種類別の効果説明とスロット案内を足すために組み直しており、
 * そのステ部分だけをこの経路へ委ねる設計になっている。ここが装備の経路
 * ({@link ItemAssembler#assemble} の lore 部)からズレると<b>実機で初めて気づく</b>ので、
 * 「同じ入力なら装備の lore と1行ずつ一致する」ことを直接突き合わせて固定する。
 */
class ItemAssemblerStatLoreBlockTest {

    /** Damageable でない素材を使う: 装備側に耐久行が注入されると突き合わせが崩れるため。 */
    private static final Material THREAD_LIKE = Material.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("品質行とカテゴリ区切り線が付く(statLines へ戻すと落ちる)")
    void statLoreBlockCarriesTheQualityLineAndCategorySeparators(@TempDir File dir) throws IOException {
        ItemAssembler assembler = assembler(dir);

        List<Component> lines = assembler.statLoreBlock(THREAD_LIKE, null, 3, 12345L);

        assertFalse(lines.isEmpty(), "ステ行が1行も返っていない");
        String all = lines.stream().map(ItemAssemblerStatLoreBlockTest::plain).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("【Master】"),
                "品質行(ティア名)が無い。LoreComposer#statLines は品質行を落とすので、"
                        + "そちらへ戻すとスレッドだけ装備と体裁が食い違う: " + all);
        assertTrue(lines.stream().anyMatch(line -> plain(line).startsWith("==")),
                "カテゴリ区切り線(====)が無い。装備の lore には入るので体裁が揃わない: " + all);
    }

    @Test
    @DisplayName("同じ入力なら装備の lore と1行ずつ一致する")
    void statLoreBlockMatchesTheEquipmentLoreLineByLine(@TempDir File dir) throws IOException {
        ItemAssembler assembler = assembler(dir);
        ItemMeta meta = new ItemStack(THREAD_LIKE).getItemMeta();

        // 装備の経路(assemble)。MockBukkit の既知の穴(getDefaultAttributeModifiers 未実装)で
        // 末尾の属性適用が中断するが、lore はその手前で確定している。
        try {
            assembler.assemble(meta, THREAD_LIKE, 12345L, 3);
        } catch (TestAbortedException mockBukkitAttributeGap) {
            // 期待どおり。lore は既に書かれている。
        }
        List<Component> equipmentLore = meta.lore();
        assertTrue(equipmentLore != null && !equipmentLore.isEmpty(), "装備側の lore が空");

        List<Component> threadLore = assembler.statLoreBlock(THREAD_LIKE, null, 3, 12345L);

        assertEquals(equipmentLore.stream().map(ItemAssemblerStatLoreBlockTest::plain).toList(),
                threadLore.stream().map(ItemAssemblerStatLoreBlockTest::plain).toList(),
                "スレッド用のステ lore が装備の lore と一致していない(体裁のズレ)");
    }

    @Test
    @DisplayName("ステ定義が無い素材では空リスト(空の区切り線だけを吐かない)")
    void statLoreBlockIsEmptyWithoutAProfile(@TempDir File dir) throws IOException {
        ItemAssembler assembler = assembler(dir);

        assertTrue(assembler.statLoreBlock(Material.STONE, null, 3, 1L).isEmpty(),
                "ステを1つも持たない素材で行を返すと、フォーク側の lore に空の飾りだけが混ざる");
    }

    // ---- wiring -----------------------------------------------------------------------------

    private static ItemAssembler assembler(File dir) throws IOException {
        ItemStatsConfig itemStats = loadItemStats(dir, """
                items:
                  BOLT_ARMOR_TRIM_SMITHING_TEMPLATE:
                    fixed:
                      attack-power: 4.0
                    per-quality:
                      reflect-percent: 0.006
                    random:
                      bleed-damage:
                        min: 20
                        max: 80
                """);
        QualityTiersConfig tiers = loadQualityTiers(dir, """
                tiers:
                  - { name: "Rough", color: "gray" }
                  - { name: "Plain", color: "white" }
                  - { name: "Fine", color: "green" }
                  - { name: "Master", color: "gold" }
                """);
        return new ItemAssembler(
                itemStats,
                new AttributeMappingConfig(),
                new AttributeApplier(MockBukkit.createMockPlugin()),
                loadShippedLore(dir),
                new LoreComposer(),
                tiers,
                new TableGeneration(),
                new ItemCatalogConfig(),
                new SkillTreeConfig(),
                new CraftingFeaturesConfig(),
                new com.trinityforge.config.domains.SpecialRewardsConfig());
    }

    /**
     * 出荷 {@code stats/lore.yml} をそのまま読ませる。表示テーブル(表示名/アイコン/桁数/カテゴリ)は
     * この表が唯一の供給元なので、空の {@code new LoreConfig()} ではステ行が1行も出ず
     * 「体裁が一致しているか」を検証できない(実際に空表で通ってしまった)。
     */
    private static LoreConfig loadShippedLore(File dir) throws IOException {
        java.nio.file.Path source = java.nio.file.Path.of("src/main/resources", LoreConfig.PATH);
        assertTrue(Files.isRegularFile(source), "出荷 lore.yml が見つからない: " + source.toAbsolutePath());
        File target = new File(dir, LoreConfig.PATH);
        Files.createDirectories(target.getParentFile().toPath());
        Files.copy(source, target.toPath());
        LoreConfig config = new LoreConfig();
        config.load(fakePlugin(dir));
        return config;
    }

    private static ItemStatsConfig loadItemStats(File dir, String yaml) throws IOException {
        File file = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        ItemStatsConfig config = new ItemStatsConfig();
        config.load(fakePlugin(dir));
        return config;
    }

    private static QualityTiersConfig loadQualityTiers(File dir, String yaml) throws IOException {
        File file = new File(dir, QualityTiersConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        QualityTiersConfig config = new QualityTiersConfig();
        config.load(fakePlugin(dir));
        return config;
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ItemAssemblerStatLoreBlockTest");
            case "saveResource" -> throw new AssertionError("既にファイルがあるのに saveResource が呼ばれた");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
