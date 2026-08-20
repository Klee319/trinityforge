package com.trinityforge.config.domains;

import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatVocabulary;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>出荷 {@code combat/stat-caps.yml} と出荷 {@code stats/item-stats.yml} を実際に突き合わせる</b>
 * drift 検出テスト(2026-08-01 K-19)。
 *
 * <h2>なぜ要るか — このファイルの上限は「厳選の寄与分」ではない</h2>
 * 追加コンテンツ詳細プラン §3 A-4 は「19枠フル厳選の理論最大」に対して cap を提案していたが、
 * {@link StatCapsConfig} の適用点は <b>装備 + パーク + 永続バフ + base-stats を全部足した後の総量</b>
 * なので、<b>武器そのもののステも同じ上限に服する</b>。プランの提案値
 * ({@code attack-power: 16000} / {@code bleed-damage: 1500})をそのまま書くと、
 * 出荷 item-stats の最上位剣(96,279.8)とトライデント(3,240)が上限で潰れ、
 * <b>装備の階段が丸ごと消える</b>。しかもクランプは無言なので、実機では
 * 「なぜか強い武器に持ち替えてもダメージが伸びない」としか見えない。
 *
 * <p>そこでここでは <b>「cap は、そのキーを持つ単品装備の最大値を下回ってはならない」</b> を固定する。
 * バランス調整で cap を下げること自体は自由だが、装備を潰す高さまで下げた瞬間にここが落ちる。
 *
 * <h2>2026-08-16 ユーザー決定: 上限なしが正。バランスは thread-rolls の抽選幅で取る</h2>
 * K-19 で入れた「攻撃側8キーの初期上限」は方針転換により<b>撤回された</b>。
 * 出荷 {@code combat/stat-caps.yml} は {@code stat-caps: {}}(＝上限なし)が<b>意図された状態</b>である。
 * よって以前ここにあった
 * <ul>
 *   <li>「出荷 cap が空なら落とす」(shippedCapsNeverClampShippedGear の冒頭)</li>
 *   <li>「攻撃側8キーが揃っていること」(everyAttackSideKeyIsCapped)</li>
 * </ul>
 * は<b>廃止した</b>。厳選の伸びを縛りたい場合は stat-caps ではなく
 * {@code thread-rolls.yml} の min/max(抽選幅)で取る、というのが現行の方針。
 *
 * <p>ただし<b>「上限を書いた場合に装備を潰していないか」の検出は価値が残る</b>ので保持する。
 * caps が空なら比較対象が無いので何もせず緑、将来 caps が書かれたら即座に上の突き合わせが復活する。
 *
 * <h2>単品最大の定義</h2>
 * {@code item-stats.yml} の意味論そのまま: {@code fixed + per-quality × max-quality + random.max}。
 * {@code max-quality} は出荷 {@code stats/quality.yml} から読む(ハードコードしない)。
 * 攻撃側キーは防具4部位が1つも持たないので単品=手に持つ1本で足りるが、
 * ここは<b>キーの種類を限定せず</b>「出荷 stat-caps に書かれた全キー」を走査するので、
 * 将来 防御側キーへ cap を足しても同じ守りが自動で効く。
 */
class ShippedStatCapsDriftTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ShippedStatCapsDriftTest");
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
    private static StatCapsConfig loadShippedCaps(File tempDir) throws IOException {
        File file = new File(tempDir, StatCapsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        try (InputStream in = resource(StatCapsConfig.PATH)) {
            assertNotNull(in, "出荷リソースが見つからない: " + StatCapsConfig.PATH);
            Files.write(file.toPath(), in.readAllBytes());
        }
        StatCapsConfig config = new StatCapsConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "出荷 stat-caps.yml がパースできない");
        return config;
    }

    private static InputStream resource(String path) {
        return ShippedStatCapsDriftTest.class.getClassLoader().getResourceAsStream(path.replace('\\', '/'));
    }

    private static YamlConfiguration loadShippedYaml(String path) throws IOException {
        try (InputStream in = resource(path)) {
            assertNotNull(in, "出荷リソースが見つからない: " + path);
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** 出荷 quality.yml の {@code max-quality}(per-quality を何段まで積めるか)。 */
    private static int shippedMaxQuality() throws IOException {
        return loadShippedYaml(QualityConfig.PATH).getInt("max-quality", 9);
    }

    /**
     * canonical キー -&gt; 「出荷 item-stats.yml でその値を取りうる単品装備の最大値」。
     * 値ゼロのキーは載せない(cap を比較する意味が無いため)。
     */
    private static Map<String, ItemPeak> shippedItemPeaks(int maxQuality) throws IOException {
        YamlConfiguration yaml = loadShippedYaml(ItemStatsConfig.PATH);
        ConfigurationSection items = yaml.getConfigurationSection("items");
        assertNotNull(items, "出荷 item-stats.yml に items セクションが無い");

        Map<String, ItemPeak> peaks = new LinkedHashMap<>();
        for (String itemId : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(itemId);
            if (item == null) {
                continue;
            }
            Map<String, Double> perItem = new LinkedHashMap<>();
            accumulate(perItem, item.getConfigurationSection("fixed"), 1.0);
            accumulate(perItem, item.getConfigurationSection("per-quality"), maxQuality);
            ConfigurationSection random = item.getConfigurationSection("random");
            if (random != null) {
                for (String rawKey : random.getKeys(false)) {
                    ConfigurationSection range = random.getConfigurationSection(rawKey);
                    if (range == null) {
                        continue;
                    }
                    perItem.merge(StatKeys.canonical(rawKey), range.getDouble("max", 0.0), Double::sum);
                }
            }
            perItem.forEach((key, value) -> {
                if (value <= 0.0) {
                    return;
                }
                ItemPeak current = peaks.get(key);
                if (current == null || value > current.value()) {
                    peaks.put(key, new ItemPeak(value, itemId));
                }
            });
        }
        return peaks;
    }

    private static void accumulate(Map<String, Double> into, ConfigurationSection section, double factor) {
        if (section == null) {
            return;
        }
        for (String rawKey : section.getKeys(false)) {
            if (!section.isDouble(rawKey) && !section.isInt(rawKey) && !section.isLong(rawKey)) {
                continue;
            }
            into.merge(StatKeys.canonical(rawKey), section.getDouble(rawKey) * factor, Double::sum);
        }
    }

    private record ItemPeak(double value, String itemId) {
    }

    // === drift 検出(1) 上限が装備を潰していないこと ===

    /**
     * <b>2026-08-16 ユーザー決定: 上限なしが正。バランスは thread-rolls の抽選幅で取る。</b>
     * したがって caps が空(出荷の既定)なら比較対象が無いので何も検査せず緑。
     * 将来ここに上限を書いたときだけ「装備を潰す高さになっていないか」が復活する。
     */
    @Test
    @DisplayName("出荷 cap を書いた場合は、出荷 item-stats の単品最大を下回らない(下回ると最上位装備が無言で潰れる / 空＝上限なしは正常)")
    void shippedCapsNeverClampShippedGear(@TempDir File tempDir) throws IOException {
        Map<String, Double> caps = loadShippedCaps(tempDir).caps();
        if (caps.isEmpty()) {
            return; // 上限なし(出荷の意図された状態)。潰される装備も存在しない。
        }
        int maxQuality = shippedMaxQuality();
        Map<String, ItemPeak> peaks = shippedItemPeaks(maxQuality);

        caps.forEach((key, cap) -> {
            ItemPeak peak = peaks.get(key);
            if (peak == null) {
                return; // 装備が持たないキー(percent-bonus-damage 等)は比較対象にならない。
            }
            assertTrue(cap >= peak.value(),
                    "stat-caps の '" + key + "' が " + cap + " で、出荷 item-stats の単品最大 "
                            + peak.value() + "(" + peak.itemId() + "、fixed + per-quality×" + maxQuality
                            + " + random.max)を下回っている。stat-caps は【最終合算値】の上限なので"
                            + "武器そのもののステもここで潰れる ── 実機では『強い武器に持ち替えても"
                            + "ダメージが伸びない』という無言の症状になる。厳選(スレッド)の寄与分だけを"
                            + "縛りたいなら stat-caps ではなく thread-rolls.yml 側の min/max を下げること。");
        });
    }

    // === drift 検出(2) 書いたキーが実際に効くキーであること ===

    @Test
    @DisplayName("出荷 cap のキーは全て StatVocabulary 既知(未知キーは警告だけ出て無言で効かない)")
    void shippedCapKeysAreKnownToTheStatVocabulary(@TempDir File tempDir) throws IOException {
        Map<String, Double> caps = loadShippedCaps(tempDir).caps();
        caps.keySet().forEach(key -> assertTrue(StatVocabulary.isKnown(key),
                "stat-caps に StatVocabulary 未登録のキー '" + key + "' がある。"
                        + "StatCapsConfig#load は警告ログを出すだけで読み込みは続けるので、"
                        + "綴り間違いは【書いたのに何も起きない】という形で無言化する。"));
    }

    // === drift 検出(3) 出荷 yml が読める形であること(上限の有無そのものは問わない) ===

    /**
     * <b>2026-08-16 ユーザー決定: 上限なしが正。バランスは thread-rolls の抽選幅で取る。</b>
     *
     * <p>ここは以前「攻撃側8キー(K-19)が揃っていること」を要求していたが、方針転換で
     * {@code stat-caps: {}}(上限なし)が出荷の正しい状態になったため、その要求は<b>廃止した</b>。
     * 代わりに残すのは「出荷 yml が壊れていないこと」だけ —— パースに失敗すると
     * {@link StatCapsConfig#load} は false を返し、上限機構そのものが無言で死ぬ。
     * (パース失敗は {@code loadShippedCaps} 内で assert している)
     */
    @Test
    @DisplayName("出荷 stat-caps.yml はパースできる(上限が空＝上限なしは 2026-08-16 の決定どおりの正常状態)")
    void shippedStatCapsFileParses(@TempDir File tempDir) throws IOException {
        Map<String, Double> caps = loadShippedCaps(tempDir).caps();
        assertNotNull(caps, "StatCapsConfig#caps() が null を返した(load は成功しているのに読めていない)");
    }

    // === drift 検出(4) 設定リファレンスの記載値が出荷 cap から離れていないこと ===

    /**
     * {@code docs/config-reference/combat/stat-caps.md} が {@code attack-power} の上限を
     * <b>実際の出荷値で</b>書いていることを固定する（2026-08-02 追加）。
     *
     * <p><b>なぜ要るか</b>: この md は cap を上げ下げした理由の唯一の記録で、
     * 「単品最大がいくつだから cap をいくつにした」という導出まで書いてある。
     * ところが値をアサートするものが何も無く、cap を動かしても md は無言で古くなる。
     * 実際 2026-08-01→08-02 の2回の変更で {@code 137500}(実際は {@code 127500})、
     * 単品最大 {@code 120,349.8}(実際は {@code 111,395.9}) と<b>2箇所とも stale になった</b>。
     * 古い導出を読んだ人は「まだ余裕がある」と誤解して cap を据え置く。
     *
     * <p><b>2026-08-16 ユーザー決定: 上限なしが正。バランスは thread-rolls の抽選幅で取る。</b>
     * 出荷 yml に {@code attack-power} の上限が無い状態が正常になったので、
     * 「上限が書かれているときだけ md と突き合わせる」に変更した(以前は上限が消えた瞬間に
     * {@code Map#get} が null を返して NPE で落ちていた)。
     */
    @Test
    @DisplayName("設定リファレンスの attack-power 上限が出荷値と一致している(md が無言で腐らない / 上限なしのときは対象外)")
    void theConfigReferenceQuotesTheShippedAttackPowerCap(@TempDir File tempDir) throws IOException {
        Double shipped = loadShippedCaps(tempDir).caps().get(StatKeys.canonical("attack-power"));
        if (shipped == null) {
            return; // 上限なし(2026-08-16 の決定)。md と突き合わせる値そのものが存在しない。
        }
        double cap = shipped;
        File doc = new File("../docs/config-reference/combat/stat-caps.md");
        assertTrue(doc.isFile(), "設定リファレンスが見つからない: " + doc.getAbsolutePath());

        String text = Files.readString(doc.toPath(), StandardCharsets.UTF_8);
        String expected = "`attack-power: " + (long) cap + "`";
        assertTrue(text.contains(expected),
                "stat-caps.md が出荷値 " + expected + " を書いていない。"
                        + "cap を動かしたら md の見出し値と『単品最大がいくつだから』の導出も直すこと"
                        + "(古い導出だけが残ると、次に読む人が余裕を誤って見積もる)");
    }
}
