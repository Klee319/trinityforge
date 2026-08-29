package com.trinityforge.stats;

import com.trinityforge.config.domains.AttributeMappingConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.LoreConfig;
import com.trinityforge.config.domains.QualityTiersConfig;
import com.trinityforge.config.domains.SkillTreeConfig;
import com.trinityforge.pdc.ItemData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.opentest4j.TestAbortedException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalog flavor lore (items/catalog.yml {@code lore:}): {@link ItemAssembler#assemble} must insert it
 * ahead of the auto-generated quality-tier/stat lines when the item's PDC carries a catalog id that
 * resolves to a template with lore, and must leave the lore output byte-for-byte identical to the
 * pre-flavor-lore behaviour otherwise (no catalog id, or a template with none) — a hard regression
 * requirement per the flavor-lore spec.
 */
class ItemAssemblerTest {

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
            case "getLogger" -> Logger.getLogger("ItemAssemblerTest");
            case "saveResource" -> throw new AssertionError("file exists; saveResource must not be called");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static ItemCatalogConfig loadCatalog(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, ItemCatalogConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        ItemCatalogConfig config = new ItemCatalogConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    private static QualityTiersConfig loadQualityTiers(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, QualityTiersConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        QualityTiersConfig config = new QualityTiersConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    private ItemAssembler assembler(ItemCatalogConfig catalog, QualityTiersConfig qualityTiers) {
        return new ItemAssembler(
                new ItemStatsConfig(),
                new AttributeMappingConfig(),
                new AttributeApplier(MockBukkit.createMockPlugin()),
                new LoreConfig(),
                new LoreComposer(),
                qualityTiers,
                new TableGeneration(),
                catalog,
                new SkillTreeConfig(),
                new CraftingFeaturesConfig(),
                new com.trinityforge.config.domains.SpecialRewardsConfig());
    }

    /**
     * バグ修正 (2026-07-26) に伴う追加ヘルパー: catalog(フレーバーlore)とitem-stats(品質判定用の
     * プロファイル)の両方を差し込みたいテスト向け。profile が null では品質行が出なくなったため、
     * 「フレーバーloreと品質行の順序」を検証するテストは実プロファイルを持つ必要がある。
     */
    private ItemAssembler assembler(
            ItemCatalogConfig catalog, ItemStatsConfig itemStats, QualityTiersConfig qualityTiers) {
        return new ItemAssembler(
                itemStats,
                new AttributeMappingConfig(),
                new AttributeApplier(MockBukkit.createMockPlugin()),
                new LoreConfig(),
                new LoreComposer(),
                qualityTiers,
                new TableGeneration(),
                catalog,
                new SkillTreeConfig(),
                new CraftingFeaturesConfig(),
                new com.trinityforge.config.domains.SpecialRewardsConfig());
    }

    private ItemMeta freshMeta() {
        return new ItemStack(Material.DIAMOND_SWORD).getItemMeta();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /**
     * Calls {@link ItemAssembler#assemble}, tolerating MockBukkit 4.110.0's known gap: {@code
     * ItemTypeMock#getDefaultAttributeModifiers} is unconditionally unimplemented
     * ({@code UnimplementedOperationException}, itself a {@link TestAbortedException}), so the tail call
     * {@code AttributeApplier.apply -> restoreVanillaDefaults} always throws under MockBukkit regardless
     * of item/material. {@code assemble} writes {@code meta.lore(...)} (the thing under test here)
     * BEFORE that tail call, so {@code meta} is already fully mutated by the time it throws; swallowing
     * only this specific abort lets the flavor-lore assertions below exercise the real production method.
     */
    private static void assembleTolerantly(
            ItemAssembler assembler, ItemMeta meta, Material material, long rollSeed, int quality) {
        try {
            assembler.assemble(meta, material, rollSeed, quality);
        } catch (TestAbortedException mockBukkitAttributeGap) {
            // Expected: see javadoc above. Anything else propagates and fails the test normally.
        }
    }

    @Test
    void catalogIdWithLorePrependsFlavorLinesAheadOfQualityTier(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  flavor_sword:
                    material: DIAMOND_SWORD
                    lore:
                      - "<gray>A flame-forged blade.</gray>"
                      - "Legend line two"
                """);
        QualityTiersConfig tiers = loadQualityTiers(tempDir, """
                tiers:
                  - { name: "Master", color: "gold" }
                """);
        // バグ修正 (2026-07-26) 後は item-stats に per-quality を持つプロファイルが無いと
        // 品質行そのものが出ない。このテストの主目的(フレーバーloreが品質行より前に来ること)を
        // 検証するには品質行が実在する必要があるため、per-quality付きプロファイルを与える。
        ItemStatsConfig itemStats = loadItemStats(tempDir, """
                items:
                  DIAMOND_SWORD:
                    fixed:
                      attack-damage: 5.0
                    per-quality:
                      attack-damage: 0.5
                """);
        ItemAssembler assembler = assembler(catalog, itemStats, tiers);
        ItemMeta meta = freshMeta();
        ItemData.of(meta).setCatalogId("flavor_sword");

        assembleTolerantly(assembler, meta, Material.DIAMOND_SWORD, 1L, 0);

        List<Component> lore = meta.lore();
        assertTrue(lore != null && lore.size() >= 3,
                "expected 2 flavor lines + composed stat block, got: " + lore);
        assertEquals("A flame-forged blade.", plain(lore.get(0)));
        assertEquals("Legend line two", plain(lore.get(1)));
        assertTrue(plain(lore.get(2)).contains("【Master】") && plain(lore.get(2)).contains("Score→"),
                "flavor lore must sit ahead of the quality-tier/score header, got: " + plain(lore.get(2)));
    }

    @Test
    void flavorLoreLinesDefaultItalicOff(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  flavor_sword:
                    material: DIAMOND_SWORD
                    lore:
                      - "plain line, no italic tag"
                """);
        ItemAssembler assembler = assembler(catalog, new QualityTiersConfig());
        ItemMeta meta = freshMeta();
        ItemData.of(meta).setCatalogId("flavor_sword");

        assembleTolerantly(assembler, meta, Material.DIAMOND_SWORD, 1L, 0);

        Component line = meta.lore().get(0);
        assertEquals(TextDecoration.State.FALSE, line.decoration(TextDecoration.ITALIC),
                "flavor lore must force italic off, matching the rest of the item's lore styling");
    }

    @Test
    void noCatalogIdLeavesLoreExactlyAsBeforeFlavorLore(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  flavor_sword:
                    material: DIAMOND_SWORD
                    lore:
                      - "should never appear"
                """);
        QualityTiersConfig tiers = loadQualityTiers(tempDir, """
                tiers:
                  - { name: "Master", color: "gold" }
                """);
        // バグ修正 (2026-07-26) 前提: item-stats に一切プロファイルを持たないアイテムに
        // 品質行が出るのが「フレーバーlore機能が変えていない旧挙動」だったが、その旧挙動自体が
        // ユーザー報告バグだった。ここでは実プロファイル(per-quality)を与えて、この
        // テストの本来の主旨(catalog idの有無だけではlore出力が変わらないこと)を、
        // 新仕様(品質はプロファイルが無い限り出ない)の下でも検証できるようにする。
        ItemStatsConfig itemStats = loadItemStats(tempDir, """
                items:
                  DIAMOND_SWORD:
                    fixed:
                      attack-damage: 5.0
                    per-quality:
                      attack-damage: 0.5
                """);
        ItemAssembler assembler = assembler(catalog, itemStats, tiers);
        ItemMeta meta = freshMeta(); // no ItemData.setCatalogId(...) call at all

        assembleTolerantly(assembler, meta, Material.DIAMOND_SWORD, 1L, 0);

        List<Component> lore = meta.lore();
        assertTrue(lore != null && !lore.isEmpty(), "no catalog id -> composed lore with quality header");
        assertTrue(plain(lore.get(0)).contains("【Master】") && plain(lore.get(0)).contains("Score→"),
                "expected quality-tier score header, got: " + plain(lore.get(0)));
    }

    @Test
    void catalogIdWithNoConfiguredLoreMatchesLegacyBehavior(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  plain_sword:
                    material: DIAMOND_SWORD
                """);
        QualityTiersConfig tiers = loadQualityTiers(tempDir, """
                tiers:
                  - { name: "Master", color: "gold" }
                """);
        // バグ修正 (2026-07-26) 前提: 品質行はプロファイルが実在する場合のみ出るので、
        // 「lore:未設定でも通常のステ/品質loreは出ること」を検証するために実プロファイルを与える。
        ItemStatsConfig itemStats = loadItemStats(tempDir, """
                items:
                  DIAMOND_SWORD:
                    fixed:
                      attack-damage: 5.0
                    per-quality:
                      attack-damage: 0.5
                """);
        ItemAssembler assembler = assembler(catalog, itemStats, tiers);
        ItemMeta meta = freshMeta();
        ItemData.of(meta).setCatalogId("plain_sword");

        assembleTolerantly(assembler, meta, Material.DIAMOND_SWORD, 1L, 0);

        List<Component> lore = meta.lore();
        assertTrue(lore != null && !lore.isEmpty(),
                "a template with no lore: section must still produce quality/score header");
        assertTrue(plain(lore.get(0)).contains("【Master】") && plain(lore.get(0)).contains("Score→"),
                "expected quality-tier score header, got: " + plain(lore.get(0)));
    }

    @Test
    void reassemblyAppendsArsOwnedThreadLore(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  threaded_armor:
                    material: DIAMOND_CHESTPLATE
                    lore:
                      - "TF catalog lore"
                """);
        ItemAssembler assembler = assembler(catalog, new QualityTiersConfig());
        ItemMeta meta = new ItemStack(Material.DIAMOND_CHESTPLATE).getItemMeta();
        ItemData.of(meta).setCatalogId("threaded_armor");
        String componentJson = GsonComponentSerializer.gson().serialize(Component.text("スレッドスロット: 2"));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("arspaper", "thread_lore"),
                PersistentDataType.STRING,
                new com.google.gson.Gson().toJson(List.of(componentJson)));

        assembleTolerantly(assembler, meta, Material.DIAMOND_CHESTPLATE, 1L, 0);

        List<Component> lore = meta.lore();
        assertEquals("TF catalog lore", plain(lore.get(0)));
        assertEquals("スレッドスロット: 2", plain(lore.get(lore.size() - 1)));
    }

    @Test
    void unknownCatalogIdIsToleratedAndAddsNoFlavorLore(@TempDir File tempDir) throws IOException {
        ItemCatalogConfig catalog = loadCatalog(tempDir, "items: {}\n");
        ItemAssembler assembler = assembler(catalog, new QualityTiersConfig());
        ItemMeta meta = freshMeta();
        // Stamped id no longer resolves (e.g. the catalog entry was removed after this item was made).
        ItemData.of(meta).setCatalogId("removed_item");

        assembleTolerantly(assembler, meta, Material.DIAMOND_SWORD, 1L, 0);

        List<Component> lore = meta.lore();
        assertTrue(lore == null || lore.stream().noneMatch(c -> plain(c).contains("should never appear")),
                "an unresolvable catalog id must not throw and must add no flavor lore");
    }

    private static ItemStatsConfig loadItemStats(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, ItemStatsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        ItemStatsConfig config = new ItemStatsConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    private ItemAssembler assemblerWithStats(ItemStatsConfig itemStats) {
        return new ItemAssembler(
                itemStats,
                new AttributeMappingConfig(),
                new AttributeApplier(MockBukkit.createMockPlugin()),
                new LoreConfig(),
                new LoreComposer(),
                new QualityTiersConfig(),
                new TableGeneration(),
                new ItemCatalogConfig(),
                new SkillTreeConfig(),
                new CraftingFeaturesConfig(),
                new com.trinityforge.config.domains.SpecialRewardsConfig());
    }

    /**
     * アイテムCT (item-cooldown) を持つアイテムには material+CMD 単位の cooldown_group
     * ({@code trinityforge:ct/<material>/<cmd>}) が刻印されること — 同マテリアル別IDのアイテムと
     * CTゲージを共有しない(Player#setCooldown(ItemStack) がグループ単位でキーする)ための配線。
     */
    @Test
    void itemCooldownStatStampsPerCmdCooldownGroup(@TempDir File tempDir) throws IOException {
        ItemStatsConfig itemStats = loadItemStats(tempDir, """
                items:
                  DIAMOND_SWORD#7:
                    fixed:
                      item-cooldown: 2.5
                """);
        ItemAssembler assembler = assemblerWithStats(itemStats);
        ItemMeta meta = freshMeta();
        meta.setCustomModelData(7);

        assembleTolerantly(assembler, meta, Material.DIAMOND_SWORD, 1L, 0);

        assertTrue(meta.hasUseCooldown(), "CT武器には use_cooldown コンポーネントが刻印されること");
        org.bukkit.NamespacedKey group = meta.getUseCooldown().getCooldownGroup();
        assertTrue(group != null && "trinityforge".equals(group.getNamespace())
                        && "ct/diamond_sword/7".equals(group.getKey()),
                "cooldown_group は trinityforge:ct/diamond_sword/7 であること, got: " + group);
        assertEquals(2.5f, meta.getUseCooldown().getCooldownSeconds(), 1e-6f);
    }

    /** CTステが無いアイテムでは、TFが過去に刻印したグループだけをクリアすること(非TFグループは保持)。 */
    @Test
    void noItemCooldownStatClearsOnlyTfOwnedGroup(@TempDir File tempDir) throws IOException {
        ItemStatsConfig itemStats = loadItemStats(tempDir, "items: {}\n");
        ItemAssembler assembler = assemblerWithStats(itemStats);

        // TF刻印済みグループ → クリアされる
        ItemMeta stale = freshMeta();
        org.bukkit.inventory.meta.components.UseCooldownComponent cd = stale.getUseCooldown();
        cd.setCooldownSeconds(9.0f);
        cd.setCooldownGroup(new org.bukkit.NamespacedKey("trinityforge", "ct/diamond_sword"));
        stale.setUseCooldown(cd);
        assembleTolerantly(assembler, stale, Material.DIAMOND_SWORD, 1L, 0);
        assertTrue(!stale.hasUseCooldown()
                        || stale.getUseCooldown().getCooldownGroup() == null
                        || !"trinityforge".equals(stale.getUseCooldown().getCooldownGroup().getNamespace()),
                "TF刻印のcooldown_groupはCTステ喪失時にクリアされること");

        // 非TF(データパック等)のグループ → 触らない
        ItemMeta foreign = freshMeta();
        org.bukkit.inventory.meta.components.UseCooldownComponent fcd = foreign.getUseCooldown();
        fcd.setCooldownSeconds(3.0f);
        fcd.setCooldownGroup(new org.bukkit.NamespacedKey("somepack", "custom_group"));
        foreign.setUseCooldown(fcd);
        assembleTolerantly(assembler, foreign, Material.DIAMOND_SWORD, 1L, 0);
        assertTrue(foreign.hasUseCooldown()
                        && foreign.getUseCooldown().getCooldownGroup() != null
                        && "somepack".equals(foreign.getUseCooldown().getCooldownGroup().getNamespace()),
                "非TFのcooldown_groupは保持されること");
    }

    // ---- 2026-07-26 ユーザー決定: 「効率」ステータス統合(tool-enchant-efficiency → gathering-efficiency) ----

    /**
     * StatKeysのエイリアス統合後、item-stats.ymlに{@code tool-enchant-efficiency}と書いても、
     * ItemAssemblerはもうそれをTOOL_ENCHANT_PREFIX経由のバニラエンチャント焼き込み対象として
     * 認識しない(canonical化の時点で{@code gathering_efficiency}に化けるため、
     * TOOL_ENCHANT_PREFIX="tool_enchant_"に一致しなくなる)。GatheringEfficiencyEnchantApplierの
     * 実行時ミラー経路に一本化された結果、ItemAssembler.assemble自体は効率強化エンチャントを
     * 一切付与しないことを固定する。
     */
    @Test
    void toolEnchantEfficiencyIsNoLongerBakedAsAVanillaEnchantByItemAssembler(@TempDir File tempDir) throws IOException {
        ItemStatsConfig itemStats = loadItemStats(tempDir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed:
                      tool-enchant-efficiency: 3.0
                """);
        ItemAssembler assembler = assemblerWithStats(itemStats);
        ItemMeta meta = new ItemStack(Material.DIAMOND_PICKAXE).getItemMeta();

        assembleTolerantly(assembler, meta, Material.DIAMOND_PICKAXE, 1L, 0);

        org.bukkit.enchantments.Enchantment efficiency = org.bukkit.Registry.ENCHANTMENT.get(
                org.bukkit.NamespacedKey.minecraft("efficiency"));
        assertTrue(!meta.hasEnchant(efficiency),
                "tool-enchant-efficiency must no longer be baked as a vanilla enchant by ItemAssembler "
                        + "(it is now folded into gathering-efficiency and applied at runtime instead)");
    }

    /**
     * 対照実験: 効率(efficiency)以外のtool-enchant-*(例: fortune)は、統合の影響を受けず今までどおり
     * ItemAssemblerのTOOL_ENCHANT_PREFIX経路でバニラエンチャントとして焼き込まれ続けること。
     * これが壊れると全ツールのエンチャントが無言で消える最重要リグレッションガード。
     */
    @Test
    void toolEnchantFortuneStillBakesAsAVanillaEnchant(@TempDir File tempDir) throws IOException {
        ItemStatsConfig itemStats = loadItemStats(tempDir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed:
                      tool-enchant-fortune: 2.0
                """);
        ItemAssembler assembler = assemblerWithStats(itemStats);
        ItemMeta meta = new ItemStack(Material.DIAMOND_PICKAXE).getItemMeta();

        assembleTolerantly(assembler, meta, Material.DIAMOND_PICKAXE, 1L, 0);

        org.bukkit.enchantments.Enchantment fortune = org.bukkit.Registry.ENCHANTMENT.get(
                org.bukkit.NamespacedKey.minecraft("fortune"));
        assertTrue(meta.hasEnchant(fortune) && meta.getEnchantLevel(fortune) == 2,
                "tool-enchant-fortune must remain unaffected by the efficiency alias and keep baking normally");
    }

    @Test
    void fallbackUseRequirementFromCatalogWhenItemStatsMissingUseSkill(@TempDir File tempDir) throws IOException {
        // item-stats.yml が空の状態（= itemStats.useRequirementFor が空）でも、
        // catalog template 側が use-gate を持つなら PDC の use-skill / use-level が揃うこと。
        ItemCatalogConfig catalog = loadCatalog(tempDir, """
                items:
                  gated_sword:
                    material: DIAMOND_SWORD
                    use-level-requirement: 20
                    use-skill: LIGHT_WEAPONS
                """);

        ItemAssembler assembler = assembler(catalog, new QualityTiersConfig());
        ItemMeta meta = freshMeta();
        ItemData.of(meta).setCatalogId("gated_sword");

        assembleTolerantly(assembler, meta, Material.DIAMOND_SWORD, 1L, 0);

        ItemData data = ItemData.of(meta);
        assertEquals("LIGHT_WEAPONS", data.useSkill().orElse(null));
        assertEquals(20, data.useLevelRequirement().orElse(0));
    }

    // ---- タスクB (2026-07-26): fixedのみのプロファイルには品質lore行を出さない ----

    private ItemAssembler assemblerWithStatsAndTiers(ItemStatsConfig itemStats, QualityTiersConfig tiers) {
        return new ItemAssembler(
                itemStats,
                new AttributeMappingConfig(),
                new AttributeApplier(MockBukkit.createMockPlugin()),
                new LoreConfig(),
                new LoreComposer(),
                tiers,
                new TableGeneration(),
                new ItemCatalogConfig(),
                new SkillTreeConfig(),
                new CraftingFeaturesConfig(),
                new com.trinityforge.config.domains.SpecialRewardsConfig());
    }

    @Test
    void fixedOnlyProfileHasNoQualityHeaderLine(@TempDir File tempDir) throws IOException {
        ItemStatsConfig itemStats = loadItemStats(tempDir, """
                items:
                  DIAMOND_SWORD#9:
                    fixed:
                      attack-damage: 5.0
                """);
        QualityTiersConfig tiers = loadQualityTiers(tempDir, """
                tiers:
                  - { name: "Master", color: "gold" }
                """);
        ItemAssembler assembler = assemblerWithStatsAndTiers(itemStats, tiers);
        ItemMeta meta = freshMeta();
        meta.setCustomModelData(9);

        assembleTolerantly(assembler, meta, Material.DIAMOND_SWORD, 1L, 5);

        List<Component> lore = meta.lore();
        assertTrue(lore == null || lore.stream().noneMatch(c -> plain(c).contains("【")),
                "fixed-only item must not render a quality-tier header line, got: " + lore);
        assertEquals(0, ItemData.of(meta).quality(), "fixed-only item must have quality pinned to 0 in PDC");
    }

    @Test
    void randomProfileStillHasQualityHeaderLine(@TempDir File tempDir) throws IOException {
        ItemStatsConfig itemStats = loadItemStats(tempDir, """
                items:
                  DIAMOND_SWORD#9:
                    fixed:
                      attack-damage: 5.0
                    random:
                      attack-damage:
                        min: 1.0
                        max: 3.0
                """);
        QualityTiersConfig tiers = loadQualityTiers(tempDir, """
                tiers:
                  - { name: "Master", color: "gold" }
                """);
        ItemAssembler assembler = assemblerWithStatsAndTiers(itemStats, tiers);
        ItemMeta meta = freshMeta();
        meta.setCustomModelData(9);

        assembleTolerantly(assembler, meta, Material.DIAMOND_SWORD, 1L, 5);

        List<Component> lore = meta.lore();
        assertTrue(lore != null && lore.stream().anyMatch(c -> plain(c).contains("【Master】")),
                "random-bearing item must still render the quality-tier header line, got: " + lore);
        assertEquals(5, ItemData.of(meta).quality(), "random-bearing item keeps the requested quality");
    }

    @Test
    void perQualityOnlyProfileStillHasQualityHeaderLine(@TempDir File tempDir) throws IOException {
        ItemStatsConfig itemStats = loadItemStats(tempDir, """
                items:
                  DIAMOND_SWORD#9:
                    fixed:
                      attack-damage: 5.0
                    per-quality:
                      attack-damage: 0.5
                """);
        QualityTiersConfig tiers = loadQualityTiers(tempDir, """
                tiers:
                  - { name: "Master", color: "gold" }
                """);
        ItemAssembler assembler = assemblerWithStatsAndTiers(itemStats, tiers);
        ItemMeta meta = freshMeta();
        meta.setCustomModelData(9);

        assembleTolerantly(assembler, meta, Material.DIAMOND_SWORD, 1L, 3);

        List<Component> lore = meta.lore();
        assertTrue(lore != null && lore.stream().anyMatch(c -> plain(c).contains("【Master】")),
                "per-quality-bearing item must still render the quality-tier header line, got: " + lore);
    }

    @Test
    void undefinedProfileHasNoQualityHeaderLine(@TempDir File tempDir) throws IOException {
        // バグ修正 (2026-07-26, ユーザー報告): 以前は「未定義(そもそも item-stats.yml に
        // 載っていない)アイテムはタスクBの判定対象外」として品質行を出す既定に倒していたが、
        // これがバグそのものだった(ステータス定義の無いカタログアイテムに★品質ティア名+
        // スコア行が付いてしまう)。CraftQualityListener.hasStatsProfile と基準を揃え、
        // profile が存在しないアイテムには品質を適用しない/品質行も出さないのが新仕様。
        // (このテストは旧名 undefinedProfileKeepsLegacyQualityHeaderBehavior を反転させたもの)
        ItemStatsConfig itemStats = loadItemStats(tempDir, "items: {}\n");
        QualityTiersConfig tiers = loadQualityTiers(tempDir, """
                tiers:
                  - { name: "Master", color: "gold" }
                """);
        ItemAssembler assembler = assemblerWithStatsAndTiers(itemStats, tiers);
        ItemMeta meta = freshMeta();

        assembleTolerantly(assembler, meta, Material.DIAMOND_SWORD, 1L, 5);

        List<Component> lore = meta.lore();
        assertTrue(lore == null || lore.stream().noneMatch(c -> plain(c).contains("【")),
                "no item-stats profile at all must NOT render a quality-tier header line, got: " + lore);
        assertEquals(0, ItemData.of(meta).quality(),
                "no item-stats profile at all must have quality pinned to 0 in PDC");
    }
}
