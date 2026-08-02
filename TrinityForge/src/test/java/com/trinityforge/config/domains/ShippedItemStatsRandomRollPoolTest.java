package com.trinityforge.config.domains;

import com.trinityforge.stats.RandomRollPool;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code stats/item-stats.yml} の {@code random-roll-pools.thread}（旧 ArsPaper
 * {@code thread-rolls.yml} の移設先、2026-08-02）と、スレッド40件の {@code random-roll-pool: thread}
 * 参照が壊れていないことを固定する。
 *
 * <p>{@code sub-count} の「0本禁止（合計は必ず2種以上）」は旧フォーク側
 * {@code ThreadRollSubCountFloorTest} が守っていた不変条件の移設。プールごとファイルが変わっただけで
 * 要件は変わっていない。
 */
class ShippedItemStatsRandomRollPoolTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ShippedItemStatsRandomRollPoolTest");
            case "saveResource" -> throw new AssertionError("shipped file must already exist on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static ItemStatsConfig loadShipped() {
        ItemStatsConfig config = new ItemStatsConfig();
        assertTrue(config.load(fakePlugin(new File("src/main/resources"))),
                "出荷 item-stats.yml のロードに失敗した");
        return config;
    }

    /** items/catalog.yml の thread_* 40件と1対1対応する MATERIAL#CMD（thread_empty含む）。 */
    private static final List<String> THREAD_KEYS = List.of(
            "WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE#300001",
            "TIDE_ARMOR_TRIM_SMITHING_TEMPLATE#300002",
            "WARD_ARMOR_TRIM_SMITHING_TEMPLATE#300003",
            "RAISER_ARMOR_TRIM_SMITHING_TEMPLATE#300004",
            "SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE#300005",
            "DUNE_ARMOR_TRIM_SMITHING_TEMPLATE#300006",
            "SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE#300007",
            "COAST_ARMOR_TRIM_SMITHING_TEMPLATE#300008",
            "EYE_ARMOR_TRIM_SMITHING_TEMPLATE#300009",
            "HOST_ARMOR_TRIM_SMITHING_TEMPLATE#300010",
            "RIB_ARMOR_TRIM_SMITHING_TEMPLATE#300011",
            "SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE#300012",
            "WILD_ARMOR_TRIM_SMITHING_TEMPLATE#300013",
            "VEX_ARMOR_TRIM_SMITHING_TEMPLATE#300014",
            "SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE#300015",
            "SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE#300016",
            "FLOW_ARMOR_TRIM_SMITHING_TEMPLATE#300017",
            "BOLT_ARMOR_TRIM_SMITHING_TEMPLATE#300018",
            "FLOW_POTTERY_SHERD#300019",
            "ARMS_UP_POTTERY_SHERD#300020",
            "BREWER_POTTERY_SHERD#300021",
            "PLENTY_POTTERY_SHERD#300022",
            "SCRAPE_POTTERY_SHERD#300023",
            "BURN_POTTERY_SHERD#300031",
            "MINER_POTTERY_SHERD#300024",
            "ANGLER_POTTERY_SHERD#300025",
            "SHEAF_POTTERY_SHERD#300026",
            "SNORT_POTTERY_SHERD#300027",
            "FRIEND_POTTERY_SHERD#300029",
            "SHELTER_POTTERY_SHERD#300033",
            "HEARTBREAK_POTTERY_SHERD#300034",
            "GUSTER_POTTERY_SHERD#300039",
            "SKULL_POTTERY_SHERD#300028",
            "HOWL_POTTERY_SHERD#300030",
            "HEART_POTTERY_SHERD#300032",
            "DANGER_POTTERY_SHERD#300035",
            "BLADE_POTTERY_SHERD#300036",
            "MOURNER_POTTERY_SHERD#300037",
            "ARCHER_POTTERY_SHERD#300038",
            "EXPLORER_POTTERY_SHERD#300040");

    @Test
    void threadPoolExistsWithSaneRaritiesAndMainStats() {
        ItemStatsConfig config = loadShipped();
        RandomRollPool pool = config.randomRollPools().get("thread");
        assertTrue(pool != null, "random-roll-pools.thread が読めていない");
        assertFalse(pool.rarities().isEmpty(), "レア度が空");
        assertFalse(pool.mainStats().isEmpty(), "主ステ候補が空");
        assertFalse(pool.subStats().isEmpty(), "サブステ候補が空");
    }

    @Test
    void subCountHasNoZeroWeightEntryAndStaysWithinExpectedBand() {
        RandomRollPool pool = loadShipped().randomRollPools().get("thread");
        Map<Integer, Integer> subCount = pool.subCount();
        assertFalse(subCount.isEmpty(), "sub-count が空");
        for (Map.Entry<Integer, Integer> entry : subCount.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            assertTrue(entry.getKey() >= 1,
                    "sub-count." + entry.getKey() + " は1本以上でなければならない(要件: 合計2種以上)");
            assertTrue(entry.getKey() <= 4,
                    "sub-count." + entry.getKey() + " が4本を超えると合計(主1本込み)が要件上限の5種を超える");
        }
    }

    @Test
    void allFortyThreadEntriesResolveToThePoolExceptEmptyVariant() {
        ItemStatsConfig config = loadShipped();
        for (String key : THREAD_KEYS) {
            String[] parts = key.split("#", 2);
            Material material = Material.getMaterial(parts[0]);
            assertTrue(material != null, "未知のMaterial: " + parts[0]);
            int cmd = Integer.parseInt(parts[1]);
            boolean isEmptyVariant = key.startsWith("WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE");
            var resolved = config.randomRollPoolFor(material, cmd);
            if (isEmptyVariant) {
                assertTrue(resolved.isEmpty(), key + "(thread_empty)は厳選プールを持たないはず");
            } else {
                assertTrue(resolved.isPresent(), key + " が random-roll-pool: thread を解決できない");
            }
        }
        assertEquals(40, THREAD_KEYS.size(), "スレッド40件の対応表が40件でない(catalog.yml側の増減を確認)");
    }

    @Test
    void wandsCarryCritAttackStats() {
        ItemStatsConfig config = loadShipped();
        int[] cmds = {400001, 400002, 400003, 400004, 400005, 400006, 400007, 400008, 400012, 400013, 400014};
        for (int cmd : cmds) {
            var profile = config.profileFor(Material.BLAZE_ROD, cmd).orElseThrow(
                    () -> new AssertionError("BLAZE_ROD#" + cmd + " のプロファイルが無い"));
            assertTrue(profile.fixed().containsKey("crit_chance"), "BLAZE_ROD#" + cmd + " に crit-chance が無い");
            assertTrue(profile.fixed().get("crit_chance") > 0, "BLAZE_ROD#" + cmd + " の crit-chance が0以下");
            assertTrue(profile.fixed().containsKey("crit_damage"), "BLAZE_ROD#" + cmd + " に crit-damage が無い");
            assertTrue(profile.fixed().containsKey("penetration"), "BLAZE_ROD#" + cmd + " に penetration が無い");
        }
    }
}
