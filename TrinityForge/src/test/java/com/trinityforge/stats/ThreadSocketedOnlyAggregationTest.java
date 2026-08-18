package com.trinityforge.stats;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>「スレッドを装備に挿さずに持っているだけでステが乗る」穴を塞いだことを固定する</b>
 * 回帰テスト(2026-08-14)。
 *
 * <h2>何が壊れていたか</h2>
 * {@code PlayerStatAggregator} は寄与アイテムを {@code isWornOnlyArmor}(HEAD/CHEST/LEGS/FEET のみ true)
 * 以外なら無条件に item マップへ合流させる。スレッドの材質(鍛冶型テンプレート / 陶器の欠片 / 旗の模様)は
 * どの装備カテゴリにも当たらないので、<b>防具のスレッド枠へ挿さず手に持つだけで全ステが乗っていた</b>。
 * 2026-08-14 の実測では攻撃8キー / 守備9キー / 汎用26キーが手持ちで乗っており、
 * {@code phys-flat-defense}(最大 12.4) / {@code damage-reduction} / {@code mining-fortune} /
 * {@code loot-luck} などは「持ち替えるだけでタダで付く」状態だった。
 *
 * <p>直前の波は「割合化したのでダメージの実害は消えた」と判断したが、それはダメージ系だけの話で、
 * 守備系・採取系の穴はそのまま残っていた。ここではダメージに限らず<b>ステが1つも乗らないこと</b>を見る。
 *
 * <h2>ここで固定すること</h2>
 * <ol>
 *   <li>出荷スレッド 45 種を<b>メインハンド / オフハンド / 防具スロット</b>のどこに置いても、
 *       {@link PlayerCombatAggregate#item()} が素手のときと 1 キーも変わらないこと。</li>
 *   <li>その原因が {@code socketed-only-stats} フラグであること
 *       (同じアイテム定義からフラグだけ外すと寄与が復活する = 検査が空回りしていない証明)。</li>
 *   <li>ArsPaper の<b>装着済みスレッド経路</b>({@link DerivedItemStats#profileStats}、
 *       {@code WeaponAttackStatResolver#resolveItemStats} が呼ぶ関数)は<b>塞がっていない</b>こと。</li>
 * </ol>
 *
 * <p><b>許可リストを持たない</b>のが要点: スレッドの一覧は出荷 {@code item-stats.yml} の CMD 帯から
 * 引くので、スレッドが増減しても検査対象が自動で追随する。また「乗らないこと」を
 * 期待キーの一覧ではなく<b>マップ全体の一致</b>で見るので、新種のステが増えても取りこぼさない。
 */
class ThreadSocketedOnlyAggregationTest {

    /** スレッドの CMD 帯。 */
    private static final int THREAD_CMD_MIN = 300001;
    private static final int THREAD_CMD_MAX = 300045;

    /** 節ごと消えたことに気づくための下限(45種 - 空のスレッド1種)。 */
    private static final int MIN_EXPECTED_THREADS = 44;

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------
    // 配線(出荷 yml を temp データフォルダへ展開して読む。ConfigManager 全体は要らない)
    // ------------------------------------------------------------------

    /** {@code getDataFolder}/{@code saveResource}/{@code getResource} だけを持つ反射プラグイン。 */
    private static Plugin resourcePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ThreadSocketedOnlyAggregationTest");
            case "saveResource" -> {
                copyResource((String) args[0], dataFolder);
                yield null;
            }
            case "getResource" -> ThreadSocketedOnlyAggregationTest.class
                    .getResourceAsStream("/" + args[0]);
            case "toString" -> "ResourcePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static void copyResource(String resourcePath, File dataFolder) {
        try (InputStream in = ThreadSocketedOnlyAggregationTest.class
                .getResourceAsStream("/" + resourcePath)) {
            if (in == null) {
                return;
            }
            Path target = new File(dataFolder, resourcePath).toPath();
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
        } catch (IOException ex) {
            throw new IllegalStateException("bundled resource copy failed: " + resourcePath, ex);
        }
    }

    /** {@code item-stats.yml} を読み込んだ設定。{@code yaml} が null なら出荷版をそのまま使う。 */
    private static ItemStatsConfig itemStats(File dir, String yaml) throws IOException {
        File file = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        if (yaml != null) {
            Files.writeString(file.toPath(), yaml);
        }
        ItemStatsConfig config = new ItemStatsConfig();
        config.load(resourcePlugin(dir));
        // random レイヤを実際に動かす(ロールモデル未配線だと random: が丸ごと無視され、
        // 「ステが乗らない」検査が空回りする)。値は quality.yml の出荷値と同程度でよい。
        config.useRollModel(() -> new QualityRollModel(15, 0.22, 0.22, 0.1));
        return config;
    }

    private static PlayerStatAggregator aggregator(ItemStatsConfig itemStats) {
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, List::of);
        return new PlayerStatAggregator(itemStats, new CombatDamageConfig(), perks,
                new RoleBuffResolver(new RoleBuffsConfig()));
    }

    private static ItemStack itemOf(Material material, Integer cmd) {
        ItemStack stack = new ItemStack(material);
        if (cmd != null) {
            ItemMeta meta = stack.getItemMeta();
            meta.setCustomModelData(cmd);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 出荷 {@code item-stats.yml} のスレッド枠キー(MATERIAL#CMD)。 */
    private static List<String> shippedThreadKeys() {
        try (InputStream in = ThreadSocketedOnlyAggregationTest.class.getClassLoader()
                .getResourceAsStream(ItemStatsConfig.PATH)) {
            assertNotNull(in, "出荷 item-stats.yml が classpath に無い");
            ConfigurationSection items = YamlConfiguration
                    .loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getConfigurationSection("items");
            assertNotNull(items, "item-stats.yml に items: が無い");
            List<String> keys = new ArrayList<>();
            for (String key : items.getKeys(false)) {
                int hash = key.indexOf('#');
                if (hash < 0) {
                    continue;
                }
                int cmd;
                try {
                    cmd = Integer.parseInt(key.substring(hash + 1));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (cmd >= THREAD_CMD_MIN && cmd <= THREAD_CMD_MAX) {
                    keys.add(key);
                }
            }
            return keys;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------------
    // 本体
    // ------------------------------------------------------------------

    @Test
    @DisplayName("出荷スレッド45種: メインハンド/オフハンド/防具スロットのどこに置いてもステが1つも乗らない")
    void shippedThreadsContributeNothingFromAnySlot(@TempDir File dir) throws IOException {
        ItemStatsConfig config = itemStats(dir, null);
        PlayerStatAggregator aggregator = aggregator(config);
        List<String> threadKeys = shippedThreadKeys();
        assertTrue(threadKeys.size() >= MIN_EXPECTED_THREADS,
                "スレッドが " + threadKeys.size() + " 件しか読めていない。CMD 帯("
                        + THREAD_CMD_MIN + "-" + THREAD_CMD_MAX + ")か節の構造が変わっていないか"
                        + "確認すること(期待: " + MIN_EXPECTED_THREADS + " 件以上)");

        Player bare = server.addPlayer();
        Map<String, Double> baseline = new LinkedHashMap<>(aggregator.aggregate(bare).item());

        TreeMap<String, String> offenders = new TreeMap<>();
        int resolvable = 0;
        for (String key : threadKeys) {
            int hash = key.indexOf('#');
            Material material = Material.matchMaterial(key.substring(0, hash));
            if (material == null) {
                continue; // 材質名が現行 Bukkit に無い(=このテストの対象外)
            }
            int cmd = Integer.parseInt(key.substring(hash + 1));
            ItemStack thread = itemOf(material, cmd);

            // 空回り防止: そもそも解決できるステを持っているか(持っていなければ何を置いても0で通る)。
            if (!DerivedItemStats.resolve(thread, config, new CombatDamageConfig().weaponBaseFormula())
                    .isEmpty()) {
                resolvable++;
            }

            for (String slot : List.of("mainhand", "offhand", "helmet")) {
                Player player = server.addPlayer();
                switch (slot) {
                    case "mainhand" -> player.getInventory().setItemInMainHand(thread);
                    case "offhand" -> player.getInventory().setItemInOffHand(thread);
                    default -> player.getInventory().setHelmet(thread);
                }
                Map<String, Double> withThread = aggregator.aggregate(player).item();
                if (!withThread.equals(baseline)) {
                    offenders.put(key + " @" + slot, diffOf(baseline, withThread));
                }
            }
        }

        assertTrue(resolvable >= MIN_EXPECTED_THREADS,
                "ステを解決できるスレッドが " + resolvable + " 件しかない。"
                        + "この検査は『置いても乗らない』を見るので、そもそも解決結果が空だと"
                        + "何も検査していないのと同じ(空回り検出)");
        assertEquals(Map.of(), offenders,
                "スレッドを装備に挿さずスロットへ置いただけでステが乗っている。"
                        + "スレッドは『防具のスレッド枠へ挿して初めて効く』素材なので、"
                        + "item-stats.yml の該当エントリに socketed-only-stats: true が要る: " + offenders);
    }

    @Test
    @DisplayName("socketed-only-stats を外すと寄与が復活する(遮断しているのはこのフラグだと示す)")
    void withoutTheFlagTheSameItemDoesContribute(@TempDir File dir) throws IOException {
        String withFlag = """
                items:
                  BOLT_ARMOR_TRIM_SMITHING_TEMPLATE#300035:
                    fixed:
                      phys-flat-defense: 12.4
                      percent-bonus-damage: 0.5
                      mining-fortune: 0.2
                    offhand-stats-apply: true
                    socketed-only-stats: true
                """;
        String withoutFlag = withFlag.replace("    socketed-only-stats: true\n", "");

        ItemStack thread = itemOf(Material.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE, 300035);

        PlayerStatAggregator blocked = aggregator(itemStats(dir, withFlag));
        Player p1 = server.addPlayer();
        p1.getInventory().setItemInMainHand(thread);
        assertEquals(Map.of(), blocked.aggregate(p1).item(),
                "socketed-only-stats: true のアイテムはメインハンドから寄与してはいけない");
        Player p2 = server.addPlayer();
        p2.getInventory().setItemInOffHand(thread);
        assertEquals(Map.of(), blocked.aggregate(p2).item(),
                "socketed-only-stats: true は offhand-stats-apply: true より強い");

        File other = new File(dir, "no-flag");
        PlayerStatAggregator open = aggregator(itemStats(other, withoutFlag));
        Player p3 = server.addPlayer();
        p3.getInventory().setItemInMainHand(thread);
        Map<String, Double> contributed = open.aggregate(p3).item();
        assertEquals(12.4, contributed.getOrDefault(StatKeys.canonical("phys-flat-defense"), 0.0), 1e-9,
                "フラグを外したときに寄与しないなら、上の検査は空回りしている");
        assertEquals(0.2, contributed.getOrDefault(StatKeys.canonical("mining-fortune"), 0.0), 1e-9);
    }

    @Test
    @DisplayName("ArsPaper の装着済みスレッド経路(profileStats)は塞がっていない")
    void socketedThreadResolutionStillWorks(@TempDir File dir) throws IOException {
        ItemStatsConfig config = itemStats(dir, null);
        List<String> threadKeys = shippedThreadKeys();
        List<String> empty = new ArrayList<>();
        int resolved = 0;
        for (String key : threadKeys) {
            int hash = key.indexOf('#');
            Material material = Material.matchMaterial(key.substring(0, hash));
            if (material == null) {
                continue;
            }
            int cmd = Integer.parseInt(key.substring(hash + 1));
            // ArmorManaListener → TrinityForgeBridge#resolveThreadStats →
            // WeaponAttackStatResolver#resolveItemStats が最終的に呼ぶ関数そのもの。
            Map<String, Double> socketed =
                    DerivedItemStats.profileStats(material, cmd, 5, 12345L, config);
            if (socketed.isEmpty()) {
                empty.add(key);
            } else {
                resolved++;
            }
        }
        assertTrue(resolved >= MIN_EXPECTED_THREADS,
                "装着済みスレッドのステ解決が " + resolved + " 件しか返らない。"
                        + "socketed-only-stats の遮断が ArsPaper 側の経路(DerivedItemStats#profileStats)"
                        + "まで波及していないか確認すること。空だったキー: " + empty);
    }

    private static String diffOf(Map<String, Double> baseline, Map<String, Double> actual) {
        Map<String, Double> delta = new TreeMap<>();
        actual.forEach((key, value) -> {
            double before = baseline.getOrDefault(key, 0.0);
            if (Math.abs(value - before) > 1.0e-12) {
                delta.put(key, value - before);
            }
        });
        baseline.forEach((key, value) -> {
            if (!actual.containsKey(key)) {
                delta.put(key, -value);
            }
        });
        return delta.toString();
    }
}
