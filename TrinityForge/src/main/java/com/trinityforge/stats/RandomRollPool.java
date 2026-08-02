package com.trinityforge.stats;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * {@code stats/item-stats.yml} の {@code random-roll-pools.<poolId>} で定義される「アイテム1個ぶんの
 * 厳選（主ステ1つ + サブステ0〜N個 + レア度）」の抽選テーブル。もともとは ArsPaper フォークの
 * {@code thread-rolls.yml}/{@code ThreadRollConfig} にあった機構（スレッドの個体差＝原神の聖遺物と
 * 同じ形の沼厳選）を、エディタの「ステータス設定」タブから編集できるよう TF 側 item-stats.yml へ
 * 移設したもの（2026-08-02）。抽選の順序・quality-spread の意味は移設元と同一:
 *
 * <ol>
 *   <li>{@code rarities} から weight で1つ引く → 主ステの値へ {@code multiplier} が掛かる（サブには掛からない）</li>
 *   <li>{@code main-stats} から weight で1つ引く → min..max の一様乱数 × multiplier</li>
 *   <li>{@code sub-count} から weight で本数を引く</li>
 *   <li>{@code sub-stats} から重複なしで本数分引く（主ステと同じキーは引かない）</li>
 * </ol>
 *
 * <p><b>quality-spread</b>: quality（0〜100想定）が高いほど min/max の幅そのものを広げる
 * （下限はわずかに下がり、上限はより大きく上がる）。quality&lt;=0 は元の min/max のまま
 * （＝厳選テーブルを素朴に一様乱数で引くのと同じ結果）。
 *
 * <p><b>刻み幅（2026-08-02 タスク#43）</b>: ロールする値は、config に authored された
 * {@code min}/{@code max} の小数点以下の桁数から刻み幅を決める。両方とも整数（小数部なし）なら
 * 整数刻み、例えば {@code min: 0.5, max: 2.0} なら 0.1 刻みで抽選する（{@link #decimalPlaces}）。
 * quality-spread で拡縮した後の min/max ではなく、<b>config に書かれた元の min/max</b> から桁数を
 * 決める — 拡縮後の値は乗算で桁が増えることがあり、それを刻み幅の基準にすると運用者が
 * 意図しない半端な刻みになるため。
 *
 * <p>この型は Bukkit 非依存（サーバ無しで単体テストできる）。
 */
public record RandomRollPool(
        List<Rarity> rarities,
        List<StatDef> mainStats,
        List<StatDef> subStats,
        Map<Integer, Integer> subCount,
        boolean qualitySpreadEnabled,
        double qualityLowShrinkAtMaxQuality,
        double qualityHighExpandAtMaxQuality) {

    /** {@code quality-spread} セクションが無いときの既定値（旧 thread-rolls.yml の既定と同一）。 */
    public static final double DEFAULT_LOW_SHRINK_AT_MAX_QUALITY = 0.05;
    public static final double DEFAULT_HIGH_EXPAND_AT_MAX_QUALITY = 0.15;

    /** レア度1件。{@code multiplier} は主ステの値だけに掛かる。{@code colorName} は未設定/不明なら {@code null}。 */
    public record Rarity(String id, int weight, double multiplier, String label, String colorName) {
    }

    /** 抽選候補1件（主ステ/サブステ共通）。{@code percent} は表示専用フラグ（保存値は常に生の数値）。 */
    public record StatDef(String key, int weight, double min, double max, boolean percent) {
        public StatDef {
            if (max < min) {
                double swap = min;
                min = max;
                max = swap;
            }
        }
    }

    /** 抽選結果: 主ステ1つ + サブステ0本以上 + レア度。 */
    public record RolledStats(String rarityId, Map<String, Double> mainStat, Map<String, Double> subStats) {
        public RolledStats {
            mainStat = Map.copyOf(mainStat);
            subStats = Map.copyOf(subStats);
        }

        /** 全ステ合算（同じキーがあれば合算。抽選は排他なので通常は起こらない）。 */
        public Map<String, Double> allStats() {
            Map<String, Double> out = new LinkedHashMap<>(mainStat);
            subStats.forEach((key, value) -> out.merge(key, value, Double::sum));
            return out;
        }

        /**
         * ArsPaper 側 {@code ThreadRoll#decode} がそのまま読める形式（{@code
         * "<rarityId>|<mainKey>=<value>|<subKey>=<value>;<subKey>=<value>"}）でエンコードする。
         * PDC 互換を保つための唯一の理由なので、区切り文字（{@code | ; =}）は変更しないこと。
         */
        public String encode() {
            return rarityId + '|' + encodeStats(mainStat) + '|' + encodeStats(subStats);
        }

        private static String encodeStats(Map<String, Double> stats) {
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, Double> entry : stats.entrySet()) {
                Double value = entry.getValue();
                if (value == null || !Double.isFinite(value) || value == 0.0) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append(';');
                }
                out.append(entry.getKey()).append('=').append(value);
            }
            return out.toString();
        }
    }

    public RandomRollPool {
        rarities = List.copyOf(rarities == null ? List.of() : rarities);
        mainStats = List.copyOf(mainStats == null ? List.of() : mainStats);
        subStats = List.copyOf(subStats == null ? List.of() : subStats);
        subCount = Map.copyOf(subCount == null ? Map.of() : subCount);
    }

    /** 率として表示するキー集合（main/sub のどちらかで {@code percent: true} なら入る）。 */
    public Set<String> percentKeys() {
        Set<String> out = new LinkedHashSet<>();
        for (StatDef def : mainStats) {
            if (def.percent()) {
                out.add(def.key());
            }
        }
        for (StatDef def : subStats) {
            if (def.percent()) {
                out.add(def.key());
            }
        }
        return out;
    }

    public Optional<Rarity> rarity(String id) {
        return rarities.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** アイテム1個ぶんの厳選。quality情報が無い経路向け（quality=0扱い）。 */
    public Optional<RolledStats> roll(Random random) {
        return roll(random, 0);
    }

    /**
     * アイテム1個ぶんの厳選。{@code quality} は TF のクラフト品質（目安0〜100、範囲外はクランプ）。
     * {@code rarities}/{@code main-stats} が空なら {@link Optional#empty()}（＝厳選なしの従来挙動）。
     */
    public Optional<RolledStats> roll(Random random, int quality) {
        if (rarities.isEmpty() || mainStats.isEmpty()) {
            return Optional.empty();
        }
        Rarity rarity = pick(rarities, Rarity::weight, random);
        StatDef main = pick(mainStats, StatDef::weight, random);
        if (rarity == null || main == null) {
            return Optional.empty();
        }
        double lowMultiplier = qualitySpreadEnabled ? lowMultiplierFor(quality, qualityLowShrinkAtMaxQuality) : 1.0;
        double highMultiplier = qualitySpreadEnabled ? highMultiplierFor(quality, qualityHighExpandAtMaxQuality) : 1.0;

        Map<String, Double> mainStat = new LinkedHashMap<>();
        double base = rollValue(main, random, lowMultiplier, highMultiplier);
        mainStat.put(main.key(), round(base * rarity.multiplier()));

        Map<String, Double> subs = new LinkedHashMap<>();
        int wanted = pickSubCount(random);
        List<StatDef> pool = new ArrayList<>(subStats);
        pool.removeIf(def -> def.key().equals(main.key()));
        for (int i = 0; i < wanted && !pool.isEmpty(); i++) {
            StatDef picked = pick(pool, StatDef::weight, random);
            if (picked == null) {
                break;
            }
            pool.remove(picked);
            subs.put(picked.key(), rollValue(picked, random, lowMultiplier, highMultiplier));
        }
        return Optional.of(new RolledStats(rarity.id(), mainStat, subs));
    }

    private int pickSubCount(Random random) {
        if (subCount.isEmpty()) {
            return 0;
        }
        int total = subCount.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            return 0;
        }
        int cursor = random.nextInt(total);
        for (Map.Entry<Integer, Integer> entry : subCount.entrySet()) {
            cursor -= entry.getValue();
            if (cursor < 0) {
                return entry.getKey();
            }
        }
        return 0;
    }

    private static <T> T pick(List<T> pool, ToIntFunction<T> weight, Random random) {
        int total = 0;
        for (T candidate : pool) {
            total += Math.max(0, weight.applyAsInt(candidate));
        }
        if (total <= 0) {
            return null;
        }
        int cursor = random.nextInt(total);
        for (T candidate : pool) {
            cursor -= Math.max(0, weight.applyAsInt(candidate));
            if (cursor < 0) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * {@code lowMultiplier}/{@code highMultiplier} は quality-spread による幅の拡縮（両方1.0なら
     * 従来どおり def.min()〜def.max() の一様乱数）。刻み幅は config に authored された
     * 元の min/max の小数桁数から決める（{@link #decimalsOf}）— 拡縮後の値では決めない。
     */
    static double rollValue(StatDef def, Random random, double lowMultiplier, double highMultiplier) {
        double adjMin = def.min() * lowMultiplier;
        double adjMax = def.max() * highMultiplier;
        int decimals = decimalsOf(def);
        if (adjMax <= adjMin) {
            return quantize(adjMin, adjMin, adjMax, decimals);
        }
        double raw = adjMin + random.nextDouble() * (adjMax - adjMin);
        return quantize(raw, adjMin, adjMax, decimals);
    }

    /** {@code def.min()}/{@code def.max()} のうち、小数点以下の桁数が多い方（両方整数なら0=整数刻み）。 */
    static int decimalsOf(StatDef def) {
        return Math.max(decimalPlaces(def.min()), decimalPlaces(def.max()));
    }

    /** {@code v} を正確に表す小数桁数（末尾の0は無視）。最大6桁でクランプ（浮動小数点誤差対策）。 */
    static int decimalPlaces(double v) {
        if (!Double.isFinite(v)) {
            return 0;
        }
        BigDecimal bd = BigDecimal.valueOf(v).stripTrailingZeros();
        return Math.max(0, Math.min(6, bd.scale()));
    }

    /** {@code raw} を {@code [lo, hi]}（順不同）の範囲内で {@code decimals} 桁刻みの格子へ丸める。 */
    static double quantize(double raw, double lo, double hi, int decimals) {
        double min = Math.min(lo, hi);
        double max = Math.max(lo, hi);
        double step = Math.pow(10, -decimals);
        double stepsFromMin = step > 0 ? Math.round((raw - min) / step) : 0;
        double value = min + stepsFromMin * step;
        value = Math.max(min, Math.min(max, value));
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    /**
     * quality（0-100、範囲外はクランプ）から min 側の倍率を計算する純関数。quality&lt;=0 で必ず 1.0
     * （=元の min のまま）になる。
     */
    public static double lowMultiplierFor(int quality, double lowShrinkAtMaxQuality) {
        return 1.0 - lowShrinkAtMaxQuality * qualityFraction(quality);
    }

    /** {@link #lowMultiplierFor} の max 側。quality&lt;=0 で必ず 1.0（=元の max のまま）。 */
    public static double highMultiplierFor(int quality, double highExpandAtMaxQuality) {
        return 1.0 + highExpandAtMaxQuality * qualityFraction(quality);
    }

    /** quality を [0,100] にクランプしてから [0.0,1.0] の割合へ変換する。 */
    public static double qualityFraction(int quality) {
        int clamped = Math.max(0, Math.min(100, quality));
        return clamped / 100.0;
    }

    /**
     * 有効数字を落として保存する（レア度倍率を掛けた後の主ステ値専用）。率は小数4桁、実数値は小数2桁 ──
     * PDC 文字列が {@code 0.023456789012} のような長さになると、スロット数ぶん配列で持つ側の PDC が
     * 無駄に膨らむ。サブステ（倍率なし）は {@link #quantize} が既に格子上の値を返すのでこの丸めは不要。
     */
    private static double round(double raw) {
        double scale = Math.abs(raw) < 1.0 ? 10_000.0 : 100.0;
        return Math.round(raw * scale) / scale;
    }
}
