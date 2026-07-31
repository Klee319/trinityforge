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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>出荷 yml のスレッド枠上限と機構を実際に突き合わせる</b> drift 検出テスト(2026-07-31 F2)。
 *
 * <p>なぜ新設したか: {@code ThreadSlotPolicyTest} / {@code DerivedItemStatsThreadSlotTest} は
 * 上限マップを<b>テスト内定数でハードコード</b>しており、出荷 yml を一切読んでいなかった。
 * そのため以下の drift が構造的に検出不能だった —
 * Java 既定は {@code weapon/tool/other = 0} のまま、出荷 yml だけが commit {@code 7dca432} で
 * これらを 5 に変えていた。{@link ThreadSlotPolicy#applyCategoryCap} は cap&le;0 のとき
 * {@code thread-slots} をマップから削除する設計なので、0 の間は矛盾が表に出ず、
 * 5 になった瞬間に {@code ItemAssembler} が lore を焼いて
 * <b>「スレッド枠 N枠」と表示されるだけで装着も効果も無い装備が 78 件</b>生まれた。
 *
 * <p>ここでは「yml をパースした実値」と「その値で機構が実際にどう振る舞うか」の両方を固定する。
 * 上限を config で変えたら、このテストが機構側の帰結ごと言い直させる。
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

    @Test
    @DisplayName("出荷ymlの max-by-category は Java 既定値と一致している(drift が起きたらここで落ちる)")
    void shippedCapsMatchJavaDefaults(@TempDir File tempDir) throws IOException {
        Map<String, Integer> shipped = loadShipped(tempDir).threadSlotMaxByCategory();

        for (String category : REPRESENTATIVE.keySet()) {
            assertEquals(CraftingFeaturesConfig.DEFAULT_THREAD_SLOT_CAP, shipped.get(category),
                    "出荷ymlの thread-slots.max-by-category." + category + " が Java 既定値と違う。"
                            + "既定値と出荷値がずれると、cap<=0 の側では thread-slots がキーごと"
                            + "削除されるため矛盾が表に出ず、片方だけ正の値になった瞬間に"
                            + "『lore に枠が出るのに効かない装備』が生まれる(2026-07-31 F2)。"
                            + "片方を変えたら必ず両方を変えること。");
        }
    }

    @Test
    @DisplayName("出荷値では防具・武器・ツール・触媒(other)すべてが thread-slots を保持する")
    void everyCategoryKeepsThreadSlotsUnderShippedCaps(@TempDir File tempDir) throws IOException {
        Map<String, Integer> shipped = loadShipped(tempDir).threadSlotMaxByCategory();

        REPRESENTATIVE.forEach((category, material) -> {
            int cap = shipped.getOrDefault(category, 0);
            assertTrue(cap > 0, category + " の上限が 0 以下。ThreadSlotPolicy がキーを削除するので"
                    + "この材質のスレッド枠は存在しないことになる: " + material);

            Map<String, Double> stats = new LinkedHashMap<>();
            stats.put(THREAD_SLOTS_KEY, 3.0);
            ThreadSlotPolicy.applyCategoryCap(stats, material, shipped);
            assertEquals(3.0, stats.get(THREAD_SLOTS_KEY),
                    material + " の thread-slots が出荷上限で落ちた/削除された(category=" + category + ")");

            // 上限超過はクランプ、削除ではない(枠を持つ側の期待値)。
            Map<String, Double> over = new LinkedHashMap<>();
            over.put(THREAD_SLOTS_KEY, cap + 4.0);
            ThreadSlotPolicy.applyCategoryCap(over, material, shipped);
            assertEquals((double) cap, over.get(THREAD_SLOTS_KEY),
                    material + " の上限超過はクランプされるべき(削除ではない)");
        });
    }

    @Test
    @DisplayName("cap を 0 にすると当該材質の thread-slots はキーごと消える(削除セマンティクスの固定)")
    void zeroCapStillRemovesTheKey() {
        // 「0 は非表示ではなく削除」という設計はこの drift の温床そのものなので、
        // 期待値として明示的に固定しておく(将来 0 を『そのまま 0 を残す』に変えるなら
        // lore 側の hide-when-zero と併せて設計判断が必要になる)。
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
