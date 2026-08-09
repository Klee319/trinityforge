package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemStatsConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>スレッド(厳選アイテム)のロール幅が「引き直す価値がある」大きさであることを固定する</b>
 * 回帰テスト(2026-08-10、ユーザー要望「厳選の上振れ下振れをがっつり大きく」)。
 *
 * <h2>何が起きていたか</h2>
 * スレッド 45 種の {@code random:} は min/max の比が <b>2.75〜5 倍</b>しかなく、さらに
 * {@link QualityRollModel#reach} の σ({@code stats/quality.yml} の {@code roll-spread-*})が
 * <b>0.1</b> だったため、同じ品質なら reach がほぼ mode に張り付いていた。
 * 具体的には品質 4〜5 で max 側へ届くには z=5 相当が要り、<b>事実上「品質が全て、ロールは無意味」</b>で、
 * 厳選という遊びが成立していなかった。
 *
 * <h2>ここで何を固定するか</h2>
 * <ol>
 *   <li>各 {@code random:} の <b>max/min 比</b>が {@link #MIN_SPREAD_RATIO} 倍以上あること</li>
 *   <li>{@code stats/quality.yml} の {@code roll-spread-up} / {@code roll-spread-down} が
 *       {@link #MIN_ROLL_SIGMA} 以上であること</li>
 * </ol>
 * <b>片方だけでは効かない</b>のが要点。帯を広げても σ が小さければ端に届かず、
 * σ を上げても帯が狭ければ振れ幅そのものが小さいままになる。
 *
 * <p>値そのもの(ステごとの絶対値)は設計判断なので固定しない。見るのは「幅」だけ。
 */
class ShippedThreadRollSpreadTest {

    private static final String QUALITY_PATH = "stats/quality.yml";

    /** スレッドの CMD 帯。 */
    private static final Pattern THREAD_KEY = Pattern.compile("^[A-Z_]+#3000(0[1-9]|[1-4][0-9])$");

    /**
     * max/min の下限。2026-08-10 の拡大前は最小 2.75 倍(damage-reduction の 0.011/0.004)で、
     * 拡大後は 10 倍以上ある。8 は「拡大前のデータなら必ず落ち、拡大後は余裕を持って通る」位置。
     */
    private static final double MIN_SPREAD_RATIO = 8.0;

    /** 品質内ロールの σ の下限。拡大前は 0.1、拡大後は 0.22。 */
    private static final double MIN_ROLL_SIGMA = 0.2;

    /** 節ごと消えたことに気づくための下限(スレッド 45 種のうち thread_empty を除く 44 件 × 5 ステ)。 */
    private static final int MIN_EXPECTED_RANGES = 200;

    private static YamlConfiguration shipped(String path) throws IOException {
        try (InputStream in = ShippedThreadRollSpreadTest.class.getClassLoader()
                .getResourceAsStream(path.replace('\\', '/'))) {
            assertNotNull(in, "出荷リソースが見つからない: " + path);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("スレッドの random: は max/min が 8 倍以上ある(狭いと品質で決まりきって厳選にならない)")
    void threadRandomRangesAreWideEnoughToBeWorthRerolling() throws IOException {
        ConfigurationSection items = shipped(ItemStatsConfig.PATH).getConfigurationSection("items");
        assertNotNull(items, "出荷 item-stats.yml に items セクションが無い");

        TreeMap<String, Double> tooNarrow = new TreeMap<>();
        List<String> invalid = new ArrayList<>();
        int ranges = 0;

        for (String id : items.getKeys(false)) {
            Matcher matcher = THREAD_KEY.matcher(id);
            if (!matcher.matches()) {
                continue;
            }
            ConfigurationSection random = items.getConfigurationSection(id + ".random");
            if (random == null) {
                continue; // thread_empty(300001) は random を持たない
            }
            for (String stat : random.getKeys(false)) {
                ConfigurationSection range = random.getConfigurationSection(stat);
                if (range == null) {
                    continue;
                }
                double min = range.getDouble("min");
                double max = range.getDouble("max");
                ranges++;
                if (!(min > 0.0) || !(max > 0.0) || max < min) {
                    invalid.add(id + "|" + stat + " min=" + min + " max=" + max);
                    continue;
                }
                double ratio = max / min;
                if (ratio < MIN_SPREAD_RATIO - 1.0e-9) {
                    tooNarrow.put(id + "|" + stat, Math.round(ratio * 100) / 100.0);
                }
            }
        }

        assertTrue(ranges >= MIN_EXPECTED_RANGES,
                "スレッドの random: が " + ranges + " 件しか読めていない。"
                        + "CMD 帯(300001-300045)か節の構造が変わっていないか確認すること"
                        + "(期待: " + MIN_EXPECTED_RANGES + " 件以上)");
        assertTrue(invalid.isEmpty(), "min/max が不正なロール範囲がある: " + invalid);
        assertTrue(tooNarrow.isEmpty(),
                "ロール幅(max/min)が " + MIN_SPREAD_RATIO + " 倍未満のスレッドステがある: " + tooNarrow
                        + " — 幅が狭いと同じ品質でロールがほぼ一意に決まり、振り直す意味が無くなる。");
    }

    @Test
    @DisplayName("quality.yml の roll-spread は 0.2 以上(小さいと reach が mode に張り付いて端に届かない)")
    void qualityRollSigmaIsLargeEnoughToReachBothEnds() throws IOException {
        YamlConfiguration quality = shipped(QUALITY_PATH);
        double up = quality.getDouble("roll-spread-up", -1);
        double down = quality.getDouble("roll-spread-down", -1);

        assertTrue(up >= MIN_ROLL_SIGMA - 1.0e-9,
                "roll-spread-up が " + up + "。" + MIN_ROLL_SIGMA + " 未満だと"
                        + " QualityRollModel.reach が mode にほぼ張り付き、item-stats.yml の"
                        + " random: をいくら広げても実際には端まで引けない。");
        assertTrue(down >= MIN_ROLL_SIGMA - 1.0e-9,
                "roll-spread-down が " + down + "。上振れだけ広げて下振れを狭めると"
                        + "「引けば必ず得をする」ので、これも厳選にならない。");
    }
}
