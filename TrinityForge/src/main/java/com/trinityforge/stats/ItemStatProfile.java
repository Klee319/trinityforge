package com.trinityforge.stats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The per-item individual stat overlay authored in {@code stats/item-stats.yml} (keyed by Material or
 * {@code Material#CustomModelData}). Applied by {@link DerivedItemStats#resolve} as the ONLY per-item
 * stat source (fully deterministic; no roll/material-base layers remain):
 *
 * <ul>
 *   <li>{@code fixed} — canonical stat key -&gt; exact value; applied to EVERY matching item (even a
 *       PDC-less vanilla/Valhalla item), overwriting (via {@code put}) rather than summing.</li>
 * </ul>
 *
 * <p>{@code perQuality} — canonical stat key -&gt; per-quality-level increment; ADDED (summed, not
 * overwritten) on top of the fixed overlay in proportion to the item's quality level
 * ({@code step * qualityLevel}), so an undefined stat contributes nothing and quality 0 is a no-op.
 *
 * <p>{@code random} — canonical stat key -&gt; {@link StatRange}; ADDED on top of the fixed/per-quality
 * overlay via a quality-dependent roll ({@code min + reach * (max - min)}, {@link QualityRollModel}), with
 * the reach seeded deterministically by the item's {@code rollSeed} ({@link RollHash}). Only stats listed
 * here are rolled; an undefined base counts as 0. Physical and magic stats share the one mechanism.
 *
 * <p>{@code randomizeGrants} / {@code grantChances} — optional advanced grant gating. When
 * {@code randomizeGrants} is true, each assigned stat key (union of fixed/per-quality/random) is included
 * only when a deterministic unit draw ({@link RollHash#unitInterval} with key {@code grant:&lt;stat&gt;})
 * is below that key's chance (default 1.0). Failed keys are omitted from all three layers.
 *
 * <p>All maps are keyed canonically ({@link StatKeys#canonical}) so they line up with the lore /
 * attribute-projection lookups. The compact constructor defensively copies the input maps, so a later
 * mutation of a caller-held map cannot alter a stored profile.
 *
 * <p>{@code durability} — 任意の最大耐久力(最大ダメージ)上書き値。{@code null} なら上書きなし(バニラ既定の
 * ままで、{@link com.trinityforge.stats.ItemAssembler} は {@code setMaxDamage(null)} でリセットする)。0以下は
 * 無効として {@code null} に丸める。武器/ツール/防具など {@link org.bukkit.inventory.meta.Damageable} を持つ
 * アイテムにのみ効果がある。
 *
 * <p>{@code offhandApplies} — このアイテムがオフハンドにあるとき、その派生ステを戦闘集約に合算するか。既定
 * {@code false}(合算しない)。旧・グローバルトグル {@code stat-unification.offhand-enabled} を廃止し、per-item
 * ({@code offhand-stats-apply: true})で制御するようにした(ユーザー確定)。
 *
 * <p>{@code offhandRequiresBlocking} — {@code offhandApplies} が真のアイテムのうち、<b>盾を構えている間だけ</b>
 * ステを乗せるもの(既定 {@code false} = 持っているだけで常時乗る)。盾({@code SHIELD})向け
 * (2026-08-20 / W-163 ユーザー確定「しゃがんだ時＝構えているときだけ設定したステータスが乗る」)。
 * {@code offhandApplies} が偽ならこのフラグは意味を持たない。
 *
 * <p>{@code multipliers} — 乗算モードのステ(レイヤID→{@link MultiplierSpec})。加算集計とは別に、
 * プレイヤーの合算済み総合ステータスへ「x倍率」として掛かる。同一レイヤの倍率はプレイヤー全体で
 * {@code 1 + Σ(v-1)} に合成され、レイヤ同士は乗算される。authored value は倍率そのもの(1.2 = x1.2)。
 *
 * <p>{@code socketedOnly} — <b>装着専用</b>フラグ({@code socketed-only-stats: true}、既定 false)。
 * true のアイテムは「プレイヤーが装備/手に持っているスロット」からはステを一切寄与しない。
 * ArsPaper のスレッド(防具のスレッド枠へ挿して使う素材)専用のフラグで、これが無いと
 * {@code stats/item-stats.yml} にスレッドのステを書いた瞬間「防具に挿さず手に持つだけで効く」穴が開く
 * ({@code PlayerStatAggregator} がメインハンド寄与を材質フィルタ無しで合算するため)。
 * <b>装着済みスレッドの寄与は別経路</b>({@code ArmorManaListener} →
 * {@code WeaponAttackStatResolver#resolveItemStats(material, cmd, quality, rollSeed)} →
 * {@link DerivedItemStats#profileStats})なので、このフラグでは止まらない。ロア表示も
 * {@link DerivedItemStats#resolve} をそのまま通るので「挿したら何が付くか」は今まで通り見える。
 *
 * @param fixed                canonical stat key -&gt; fixed value (overwrite, always applied when granted)
 * @param perQuality           canonical stat key -&gt; per-quality-level increment
 * @param random               canonical stat key -&gt; {@link StatRange} rolled additively
 * @param durability           任意の最大耐久力上書き(正の整数)。{@code null}=上書きなし
 * @param offhandApplies       オフハンド時にステを合算するか(既定 false)
 * @param randomizeGrants      付与ステ種類のランダム化を有効にするか
 * @param grantChances         ステキーごとの付与確率(0.0〜1.0)。未記載キーは 1.0
 * @param multipliers          乗算レイヤID -&gt; 乗算ステ定義(fixed/per-quality/random)。空=乗算なし
 * @param socketedOnly         装着専用(スロット由来の寄与を全面禁止)。既定 false
 * @param offhandRequiresBlocking オフハンド寄与を「構えている間」に限定するか(既定 false)
 */
public record ItemStatProfile(Map<String, Double> fixed, Map<String, Double> perQuality,
                              Map<String, StatRange> random, Integer durability, boolean offhandApplies,
                              boolean randomizeGrants, Map<String, Double> grantChances,
                              Map<String, MultiplierSpec> multipliers, boolean socketedOnly,
                              boolean offhandRequiresBlocking) {

    /**
     * 乗算モードのステ定義(1レイヤ分)。値の解決は加算ステと同じ fixed + per-quality×品質 + random ロール
     * だが、意味は「倍率」(1.2 = x1.2)。解決値 v のプレイヤー合算への寄与は {@code v - 1}。
     */
    public record MultiplierSpec(Map<String, Double> fixed, Map<String, Double> perQuality,
                                 Map<String, StatRange> random) {
        public MultiplierSpec {
            Map<String, Double> fixedCopy = new LinkedHashMap<>();
            if (fixed != null) {
                fixed.forEach((key, value) -> fixedCopy.put(StatKeys.canonical(key), value));
            }
            fixed = Map.copyOf(fixedCopy);
            Map<String, Double> pqCopy = new LinkedHashMap<>();
            if (perQuality != null) {
                perQuality.forEach((key, step) -> pqCopy.put(StatKeys.canonical(key), step));
            }
            perQuality = Map.copyOf(pqCopy);
            Map<String, StatRange> randomCopy = new LinkedHashMap<>();
            if (random != null) {
                random.forEach((key, range) -> randomCopy.put(StatKeys.canonical(key), range));
            }
            random = Map.copyOf(randomCopy);
        }

        public boolean isEmpty() {
            return fixed.isEmpty() && perQuality.isEmpty() && random.isEmpty();
        }
    }

    public ItemStatProfile {
        fixed = fixed == null ? Map.of() : Map.copyOf(fixed);
        Map<String, Double> perQualityCopy = new LinkedHashMap<>();
        if (perQuality != null) {
            perQuality.forEach((key, step) -> perQualityCopy.put(StatKeys.canonical(key), step));
        }
        perQuality = Map.copyOf(perQualityCopy);
        Map<String, StatRange> randomCopy = new LinkedHashMap<>();
        if (random != null) {
            random.forEach((key, range) -> randomCopy.put(StatKeys.canonical(key), range));
        }
        random = Map.copyOf(randomCopy);
        // 0以下の耐久上書きは無効(バニラ既定を尊重)。null=上書きなし。
        if (durability != null && durability <= 0) {
            durability = null;
        }
        Map<String, Double> chancesCopy = new LinkedHashMap<>();
        if (grantChances != null) {
            grantChances.forEach((key, chance) -> {
                if (chance == null || !Double.isFinite(chance)) {
                    return;
                }
                double clamped = Math.max(0.0, Math.min(1.0, chance));
                chancesCopy.put(StatKeys.canonical(key), clamped);
            });
        }
        grantChances = Map.copyOf(chancesCopy);
        Map<String, MultiplierSpec> multCopy = new LinkedHashMap<>();
        if (multipliers != null) {
            multipliers.forEach((layer, spec) -> {
                if (spec != null && !spec.isEmpty()) {
                    multCopy.put(layer, spec);
                }
            });
        }
        multipliers = Map.copyOf(multCopy);
    }

    /**
     * {@code offhandRequiresBlocking} 無しの9引数コンストラクタ(back-compat)。
     * 「構えている間だけ」の制限は掛からない(＝従来どおり持っているだけで乗る)。
     */
    public ItemStatProfile(Map<String, Double> fixed, Map<String, Double> perQuality,
                           Map<String, StatRange> random, Integer durability, boolean offhandApplies,
                           boolean randomizeGrants, Map<String, Double> grantChances,
                           Map<String, MultiplierSpec> multipliers, boolean socketedOnly) {
        this(fixed, perQuality, random, durability, offhandApplies, randomizeGrants, grantChances,
                multipliers, socketedOnly, false);
    }

    /**
     * {@code socketedOnly} 無しの8引数コンストラクタ(back-compat)。装着専用フラグは {@code false}。
     */
    public ItemStatProfile(Map<String, Double> fixed, Map<String, Double> perQuality,
                           Map<String, StatRange> random, Integer durability, boolean offhandApplies,
                           boolean randomizeGrants, Map<String, Double> grantChances,
                           Map<String, MultiplierSpec> multipliers) {
        this(fixed, perQuality, random, durability, offhandApplies, randomizeGrants, grantChances,
                multipliers, false);
    }

    /**
     * 乗算レイヤ無しの7引数コンストラクタ(back-compat)。
     */
    public ItemStatProfile(Map<String, Double> fixed, Map<String, Double> perQuality,
                           Map<String, StatRange> random, Integer durability, boolean offhandApplies,
                           boolean randomizeGrants, Map<String, Double> grantChances) {
        this(fixed, perQuality, random, durability, offhandApplies, randomizeGrants, grantChances, Map.of());
    }

    /**
     * 耐久上書き付き5引数コンストラクタ(back-compat)。高度オプションは無効。
     */
    public ItemStatProfile(Map<String, Double> fixed, Map<String, Double> perQuality,
                           Map<String, StatRange> random, Integer durability, boolean offhandApplies) {
        this(fixed, perQuality, random, durability, offhandApplies, false, Map.of(), Map.of());
    }

    /**
     * 耐久上書き付き4引数コンストラクタ(back-compat)。{@code offhandApplies} は {@code false} になる。
     */
    public ItemStatProfile(Map<String, Double> fixed, Map<String, Double> perQuality,
                           Map<String, StatRange> random, Integer durability) {
        this(fixed, perQuality, random, durability, false, false, Map.of(), Map.of());
    }

    /**
     * 従来3引数コンストラクタ(back-compat)。durability は {@code null}、offhandApplies は {@code false} になる。
     * 既存の呼び出し/テストをそのまま通すために残す。
     */
    public ItemStatProfile(Map<String, Double> fixed, Map<String, Double> perQuality,
                           Map<String, StatRange> random) {
        this(fixed, perQuality, random, null, false, false, Map.of(), Map.of());
    }

    /**
     * True when the profile carries no fixed, per-quality, random stats, no durability override,
     * offhand合算も既定(false)、高度オプションも無効、乗算レイヤも無し、装着専用でもない —
     * 完全な no-op オーバーレイ。
     *
     * <p>{@code socketedOnly} を条件に含めるのは必須: {@code ItemStatsConfig#profileFor} は
     * 空プロファイルを {@code Optional.empty()} に潰すので、含めないと
     * 「ステを1つも書かずに装着専用だけ立てたエントリ」がフォールバックへ落ちてフラグごと消える。
     */
    public boolean isEmpty() {
        return fixed.isEmpty() && perQuality.isEmpty() && random.isEmpty()
                && durability == null && !offhandApplies && !randomizeGrants && grantChances.isEmpty()
                && multipliers.isEmpty() && !socketedOnly;
    }

    /** Grant chance for a canonical (or raw) stat key; defaults to 1.0 when unset. */
    public double grantChance(String statKey) {
        Double c = grantChances.get(StatKeys.canonical(statKey));
        return c == null ? 1.0 : c;
    }

    /**
     * 品質で値が変動する層(per-quality / random、乗算レイヤ内のものも含む)を1つでも持つか。
     * {@code false} なら fixed のみの完全に品質非依存なプロファイル(素材・触媒など) — 呼び出し側
     * ({@link com.trinityforge.stats.ItemAssembler}) はこの場合、品質を0に固定しlore品質行を出さない。
     */
    public boolean qualityApplies() {
        if (!perQuality.isEmpty() || !random.isEmpty()) {
            return true;
        }
        for (MultiplierSpec spec : multipliers.values()) {
            if (!spec.perQuality().isEmpty() || !spec.random().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 厳選ロール(random 層、乗算レイヤ内 random、または grant の seed 抽選)が1つでもあるか。
     * {@code false} なら rollSeed を引き直しても数値は変わらない(fixed / per-quality のみ、
     * あるいはプロファイル自体が無い素材・特殊アイテム)。カタログ作成は品質が無くても
     * identity として rollSeed を刻むので、厳選の護符は {@link #qualityApplies()} ではなく
     * こちらで対象を絞る。
     */
    public boolean randomApplies() {
        if (randomizeGrants || !random.isEmpty()) {
            return true;
        }
        for (MultiplierSpec spec : multipliers.values()) {
            if (!spec.random().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
