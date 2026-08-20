package com.trinityforge.config.domains;

import com.trinityforge.stats.EquipmentSlotResolver;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.ThreadSlotPolicy;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>出荷 yml のスレッド枠上限と機構を実際に突き合わせる</b> drift 検出テスト(2026-07-31 F2)。
 *
 * <p>なぜ新設したか: {@code ThreadSlotPolicyTest} / {@code DerivedItemStatsThreadSlotTest} は
 * 上限マップを<b>テスト内定数でハードコード</b>しており、出荷 yml を一切読んでいなかった。
 * そのため「出荷 yml をいくら変えてもテストは緑」という盲点があった。
 *
 * <h2>何を drift と呼ぶか(2026-07-31 F3 指摘4 で定義し直した)</h2>
 * 当初ここは「出荷 yml の armor/weapon/tool/other の 4 値すべてが単一定数
 * {@code DEFAULT_THREAD_SLOT_CAP} と一致すること」を assert していたが、これは<b>誤り</b>だった。
 * {@code max-by-category} は<b>カテゴリごとに違う値を置くために存在する設定項目</b>なので、
 * バランス調整で {@code weapon: 3 / armor: 5} にした瞬間にビルドが落ちる
 * (しかも定数が1本なので指示に従っても直せない)。実際に検出したい drift は次の2つ:
 * <ol>
 *   <li><b>どのカテゴリも cap が 0 以下になっていない</b> —
 *       {@link ThreadSlotPolicy#applyCategoryCap} は cap&le;0 のとき {@code thread-slots} を
 *       <b>キーごと削除する</b>ので、0 にすると lore にも枠が出ず、そのカテゴリでは
 *       スレッド機構が丸ごと無効になる(意図してそうするなら、それは仕様変更としてここを直す)。</li>
 *   <li><b>出荷 yml に現れるカテゴリキーが Java の既定マップに存在する</b> —
 *       綴り違い({@code weapons} など)は {@code loadThreadSlots} が素通しでマップへ入れるだけで
 *       どの材質にも解決されないため、<b>無言で何も起きない</b>(直したかった側は既定値のまま)。</li>
 * </ol>
 *
 * <h2>F2 の因果についての訂正(F3 指摘3)</h2>
 * 以前ここには「Java 既定は {@code weapon/tool/other = 0} のまま出荷 yml だけが 5 になり、
 * それが 78 件の飾りを生んだ」と書いてあったが誤り。出荷 yml は {@code thread-slots} セクションを
 * 持ち 4 キーすべてを明示しているので {@code loadThreadSlots} の seed は必ず上書きされ、
 * <b>Java のフィールド既定値は稼働サーバで一度も効いていない</b>。
 * lore に枠が出るようになったのは出荷 yml を 0→5 にした前段の変更({@code 7dca432})の帰結で、
 * 「出るのに効かない」の真因は<b>ArsPaper フォークの装着 GUI が防具限定・ステ収集が
 * {@code getArmorContents()} 限定だったこと</b>である
 * (commit {@code 4c60833} の message には誤った因果が残っているが、正は
 * {@code CraftingFeaturesConfig#DEFAULT_THREAD_SLOT_CAP} の javadoc)。
 *
 * <p>ここでは「yml をパースした実値」と「その値で機構が実際にどう振る舞うか」の両方を固定する。
 */
class ShippedThreadSlotCapDriftTest {

    private static final String THREAD_SLOTS_KEY = StatKeys.canonical("thread-slots");

    /** 各カテゴリの代表材質。yml のキーだけでなく「実際にその材質が通るか」まで見るために要る。 */
    private static final Map<String, Material> REPRESENTATIVE = Map.of(
            EquipmentSlotResolver.CATEGORY_ARMOR, Material.DIAMOND_CHESTPLATE,
            EquipmentSlotResolver.CATEGORY_WEAPON, Material.NETHERITE_SWORD,
            EquipmentSlotResolver.CATEGORY_TOOL, Material.NETHERITE_PICKAXE,
            // 触媒(杖)はここ。EquipmentSlotResolver#statCategories が BLAZE_ROD を other に落とす。
            EquipmentSlotResolver.CATEGORY_OTHER, Material.BLAZE_ROD);

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ShippedThreadSlotCapDriftTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not be called when the file already exists on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    /** 出荷リソースの bytes をそのままデータフォルダへ置いて読み込む(手書きの写しを作らない)。 */
    private static CraftingFeaturesConfig loadShipped(File tempDir) throws IOException {
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        try (var in = ShippedThreadSlotCapDriftTest.class.getClassLoader()
                .getResourceAsStream(CraftingFeaturesConfig.PATH.replace('\\', '/'))) {
            assertNotNull(in, "出荷リソースが見つからない: " + CraftingFeaturesConfig.PATH);
            Files.write(file.toPath(), in.readAllBytes());
        }
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "出荷ymlがパースできない");
        return config;
    }

    // === drift 検出(1) 無効化されているカテゴリが無いこと ===

    @Test
    @DisplayName("出荷ymlのどのカテゴリも cap が 0 以下になっていない(0 はスレッド機構の無効化)")
    void noShippedCategoryDisablesThreadSlots(@TempDir File tempDir) throws IOException {
        Map<String, Integer> shipped = loadShipped(tempDir).threadSlotMaxByCategory();

        assertTrue(shipped.containsKey(EquipmentSlotResolver.CATEGORY_ARMOR)
                        && shipped.containsKey(EquipmentSlotResolver.CATEGORY_WEAPON)
                        && shipped.containsKey(EquipmentSlotResolver.CATEGORY_TOOL)
                        && shipped.containsKey(EquipmentSlotResolver.CATEGORY_OTHER),
                "既知カテゴリのどれかが出荷値のマップから欠けている: " + shipped.keySet());

        shipped.forEach((category, cap) -> assertTrue(cap != null && cap > 0,
                "出荷ymlの thread-slots.max-by-category." + category + " が " + cap
                        + "(0 以下)。ThreadSlotPolicy#applyCategoryCap は cap<=0 のとき"
                        + " thread-slots をキーごと削除するので、このカテゴリでは lore にも枠が出ず"
                        + "スレッド機構が丸ごと無効になる。カテゴリ別に【違う正の値】を置くのは"
                        + "正当な調整なので許容する ── 禁じるのは 0 以下だけ。"
                        + "意図して無効化するならこの assert ごと仕様として書き換えること。"));
    }

    // === drift 検出(2) Java が知らないカテゴリキーが無いこと ===

    @Test
    @DisplayName("出荷ymlのカテゴリキーは Java の既定マップに存在する(綴り違いは無言で無効になる)")
    void shippedCategoryKeysAreKnownToJava(@TempDir File tempDir) throws IOException {
        Set<String> known = CraftingFeaturesConfig.knownThreadSlotCategories();
        Map<String, Integer> shipped = loadShipped(tempDir).threadSlotMaxByCategory();

        for (String category : shipped.keySet()) {
            assertTrue(known.contains(category),
                    "出荷ymlの thread-slots.max-by-category に Java が知らないキー '" + category
                            + "' がある。loadThreadSlots は素通しでマップへ入れるだけで"
                            + "どの材質にも解決されないため、書いても【無言で何も起きない】"
                            + "(直したかったカテゴリは既定値のまま残る)。既知キー: " + known);
        }
    }

    // === 機構の帰結 ===

    @Test
    @DisplayName("出荷値では防具・武器・ツール・触媒(other)すべてが thread-slots を保持する")
    void everyCategoryKeepsThreadSlotsUnderShippedCaps(@TempDir File tempDir) throws IOException {
        // 「cap>0 か」は noShippedCategoryDisablesThreadSlots の担当。ここは
        // 【その cap で機構が実際にどう振る舞うか】(キー保持とクランプ)だけを見る。
        Map<String, Integer> shipped = loadShipped(tempDir).threadSlotMaxByCategory();

        REPRESENTATIVE.forEach((category, material) -> {
            int cap = shipped.getOrDefault(category, 0);

            Map<String, Double> stats = new LinkedHashMap<>();
            stats.put(THREAD_SLOTS_KEY, 1.0);
            ThreadSlotPolicy.applyCategoryCap(stats, material, shipped);
            assertEquals(1.0, stats.get(THREAD_SLOTS_KEY),
                    material + " の thread-slots が出荷上限で落ちた/削除された(category=" + category
                            + ", cap=" + cap + ")");

            // 上限超過はクランプ、削除ではない(枠を持つ側の期待値)。
            Map<String, Double> over = new LinkedHashMap<>();
            over.put(THREAD_SLOTS_KEY, cap + 4.0);
            ThreadSlotPolicy.applyCategoryCap(over, material, shipped);
            assertEquals((double) cap, over.get(THREAD_SLOTS_KEY),
                    material + " の上限超過はクランプされるべき(削除ではない)");
        });
    }

    @Test
    @DisplayName("カテゴリ別に違う上限を置いても機構はそのとおり働く(差別化はテストで禁じない)")
    void differentiatedCapsPerCategoryAreSupported() {
        // F3 指摘4 の回帰ガード: 「全カテゴリ同値」を要求すると weapon:3 / armor:5 のような
        // 正当なバランス調整でビルドが落ちる。機構側は差別化を素通しできることを固定する。
        Map<String, Integer> differentiated = new LinkedHashMap<>();
        differentiated.put(EquipmentSlotResolver.CATEGORY_ARMOR, 5);
        differentiated.put(EquipmentSlotResolver.CATEGORY_WEAPON, 3);
        differentiated.put(EquipmentSlotResolver.CATEGORY_TOOL, 2);
        differentiated.put(EquipmentSlotResolver.CATEGORY_OTHER, 4);

        Map<String, Double> armor = new LinkedHashMap<>();
        armor.put(THREAD_SLOTS_KEY, 5.0);
        ThreadSlotPolicy.applyCategoryCap(armor, Material.DIAMOND_CHESTPLATE, differentiated);
        assertEquals(5.0, armor.get(THREAD_SLOTS_KEY), "防具の 5 枠が別カテゴリの上限に引きずられた");

        Map<String, Double> weapon = new LinkedHashMap<>();
        weapon.put(THREAD_SLOTS_KEY, 5.0);
        ThreadSlotPolicy.applyCategoryCap(weapon, Material.NETHERITE_SWORD, differentiated);
        assertEquals(3.0, weapon.get(THREAD_SLOTS_KEY), "武器が自分のカテゴリ上限でクランプされていない");
    }

    @Test
    @DisplayName("cap を 0 にすると当該材質の thread-slots はキーごと消える(削除セマンティクスの固定)")
    void zeroCapStillRemovesTheKey() {
        // 「0 は非表示ではなく削除」という設計は noShippedCategoryDisablesThreadSlots が
        // 0 を禁じる根拠そのものなので、期待値として明示的に固定しておく(将来 0 を
        // 『そのまま 0 を残す』に変えるなら lore 側の hide-when-zero と併せて設計判断が必要になる)。
        Map<String, Integer> zeroWeapon = new LinkedHashMap<>();
        zeroWeapon.put(EquipmentSlotResolver.CATEGORY_ARMOR, 5);
        zeroWeapon.put(EquipmentSlotResolver.CATEGORY_WEAPON, 0);
        zeroWeapon.put(EquipmentSlotResolver.CATEGORY_TOOL, 0);
        zeroWeapon.put(EquipmentSlotResolver.CATEGORY_OTHER, 0);

        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put(THREAD_SLOTS_KEY, 3.0);
        ThreadSlotPolicy.applyCategoryCap(stats, Material.NETHERITE_SWORD, zeroWeapon);
        assertTrue(stats.isEmpty(), "cap 0 のカテゴリでは thread-slots はキーごと削除される");
    }
}
