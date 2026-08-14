package com.trinityforge.combat;

import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.stats.StatKeys;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;

/**
 * Resolves the item-side half of a player's defender profile (LD-8 γ = armor-skill baseline + item
 * add-on; this class supplies the item add-on). #3 全ステ合算: item 側は {@link PlayerStatAggregator} が
 * 防具4部位 + メインハンド + (設定により)オフハンドを合算した {@link PlayerCombatAggregate#item()} を単一の
 * ソースとして使い、{@link DefenseStatBridge} で {@link DefenderProfile} へブリッジする。
 *
 * <p>防御率% のみ {@link DefenseStatBridge} が意図的に除外している(double-count guard): それは引き続き
 * 防具のみ の vanilla armor attribute にロールし {@link VanillaArmorMapping} で読み戻され、サービスが二者を
 * {@link DefenseStats#combine} で無二重加算に合成する。防具強度(会心軽減率%)は役割変更に伴い vanilla mirror
 * 経由をやめ、{@link DefenseStatBridge} が derived stat map から直接読む(item/perk/addon/vanilla-fallback を
 * combine で加算 → choke で cap)。
 *
 * <p>ValhallaMMOスキルツリーパーク(TF所有の {@code buffs}、LD-9)の防御側 addend は3つ目の加算項として同じ
 * combine を通る: {@link PlayerCombatAggregate#perkDefense()} は {@link PerkBuffResolver} が供給する
 * プレイヤーの解禁済み防御側 buff を canonical stat map として持ち、ここで {@link DefenseStats} 化して
 * {@link DefenseStats#combine} する。何も解禁されていなければ addend は全ゼロ(パーク導入前と完全に同一挙動)。
 *
 * <p>アドオン(ArsPaper防具スレッド等、{@link AddonCombatStats})の防御側寄与も同様に3つ目相当として合成
 * される({@link PlayerCombatAggregate#addon()})。攻撃側キーは無視され、アドオン未導入時は全ゼロ。
 *
 * <p>装備を毎回ヒットごとにライブ読み取りする(攻撃側の {@code CombatListener} のメインハンド読み取りと対称)
 * ので、{@code /trinityforge reload} は焼き込み無しで即座に再導出される。Bukkit読み取りのためサーバー
 * メインスレッドでの実行が前提。
 */
public final class PlayerDefenseResolver {

    private final DefenseStatKeys keys;
    private final PlayerStatAggregator aggregator;

    public PlayerDefenseResolver(DefenseStatKeys keys, PlayerStatAggregator aggregator) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    /**
     * The player's full TF-only defense for this hit: the item side (typed 耐性 + common 守備力/被ダメ軽減)
     * plus the unlocked skill-tree perk addend (LD-9) plus the addon addend, combined field-wise, and the
     * summed dodge chance.
     */
    public DefenderProfile resolve(Player player, DamageType type) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(type, "type");

        // #3 全ステ合算: 防具4部位 + メインハンド + (設定により)オフハンド + パーク + アドオンを
        // PlayerStatAggregator で一括集計する(攻撃側 CombatListener と同じ単一集計者)。
        PlayerCombatAggregate agg = aggregator.aggregate(player);
        if (type == DamageType.TYPELESS) {
            Map<String, Double> multipliedItem = agg.applyMultipliers(agg.item());
            Map<String, Double> multipliedPerk = agg.applyMultipliers(agg.perkDefense());
            Map<String, Double> multipliedAddon = agg.applyMultipliers(agg.addon());
            double dodge = DefenseStatBridge.dodgeChance(multipliedItem, keys)
                    + multipliedPerk.getOrDefault(StatKeys.canonical("dodge-chance"), 0.0)
                    + multipliedAddon.getOrDefault(StatKeys.canonical("dodge-chance"), 0.0);
            // 2026-07-26 stat-cap カバレッジ拡大: DEFENSE チャネルの唯一の出口(TYPELESS分岐)。
            // item+perk+addon(乗算適用済み)を合算した直後の最終値へ上限を掛ける。
            return new DefenderProfile(DefenseStats.NONE, agg.clamp(DODGE_CHANCE_KEY, dodge));
        }

        // 乗算モード: 各addend(item/perk/addon)へ同一倍率を掛ける = 合算後の総合値に掛けるのと等価
        // (m*(a+b+c) = m*a + m*b + m*c)。ブリッジ構造(armor-defense-rate の二重計上ガード等)を保つため
        // マージせず個別にスケールする。
        Map<String, Double> multipliedItem = agg.applyMultipliers(agg.item());
        Map<String, Double> multipliedPerk = agg.applyMultipliers(agg.perkDefense());
        Map<String, Double> multipliedAddon = agg.applyMultipliers(agg.addon());

        DefenseStats itemDefense = DefenseStatBridge.bridge(multipliedItem, keys, type);
        double itemDodge = DefenseStatBridge.dodgeChance(multipliedItem, keys);

        // LD-9 skill-tree perk addend. Perk buff keys are the design's fixed canonical stat keys, so they
        // are mapped straight to DefenseStats fields (not through the roll-key bridge, which cannot carry
        // 防御率/armor-defense-rate). Perk 防御率 lands on defenseRate here and is then combined with the
        // vanilla armor mirror by the service (DefenseStats#combine's documented [0,1] saturation caveat
        // applies once this overlapping rate is non-zero). All-zero when nothing is unlocked.
        DefenseStats perkStats = defenseStatsFrom(multipliedPerk, type);
        double perkDodge = multipliedPerk.getOrDefault(StatKeys.canonical("dodge-chance"), 0.0);

        // Addon addend (ArsPaper armor threads, AddonCombatStats): the same per-player addon stat map that
        // CombatListener reads for the attacker side, here folded into defense — its DEFENSIVE keys map to
        // DefenseStats and combine on top of item + perk; offensive keys are simply ignored by the mapping.
        // All-zero (empty) when no addon wrote it, so behaviour is unchanged without the fork.
        DefenseStats addonStats = defenseStatsFrom(multipliedAddon, type);
        double addonDodge = multipliedAddon.getOrDefault(StatKeys.canonical("dodge-chance"), 0.0);

        // 2026-07-26 stat-cap カバレッジ拡大: DEFENSE チャネルの唯一の出口(typed分岐)。item+perk+addon
        // (各addendへ乗算レイヤ適用済み)を DefenseStats#combine で合成した直後、SymmetricCombatService
        // へ返す直前のこの一点でキーごとに上限を適用する。守備力(flatDefense)は type に応じて
        // phys-flat-defense/magic-flat-defense のいずれかへ振り分けて解決したのと対称に、クランプも
        // 同じキーで行う(legacy flat-defense 単体の上限はここでは見ない — defenseStatsFrom 側で既に
        // typed キーへ正規化された後の値だけがここに残るため)。
        DefenseStats combined = itemDefense.combine(perkStats).combine(addonStats);
        String flatDefenseKey = type == DamageType.PHYSICAL
                ? StatKeys.canonical("phys-flat-defense") : StatKeys.canonical("magic-flat-defense");
        String resistanceKey = type == DamageType.PHYSICAL
                ? StatKeys.canonical("phys-resistance") : StatKeys.canonical("magic-resistance");
        DefenseStats capped = new DefenseStats(
                agg.clamp(DEFENSE_RATE_KEY, combined.defenseRate()),
                agg.clamp(resistanceKey, combined.resistance()),
                agg.clamp(StatKeys.canonical("damage-reduction"), combined.damageReduction()),
                agg.clamp(flatDefenseKey, combined.flatDefense()),
                agg.clamp(StatKeys.canonical("armor-strength"), combined.armorStrength()));
        double dodgeTotal = agg.clamp(DODGE_CHANCE_KEY, itemDodge + perkDodge + addonDodge);

        return new DefenderProfile(capped, dodgeTotal);
    }

    /**
     * Whether the player currently wears armor rejected by the shared use-requirement gate.
     * Exposed to the symmetric combat service so its separate vanilla armor/enchantment mirror can
     * honor the same deferred-removal safety boundary as the item-stat aggregate.
     */
    public boolean hasDeniedArmor(Player player) {
        return aggregator.hasDeniedArmor(Objects.requireNonNull(player, "player"));
    }

    private static final String DODGE_CHANCE_KEY = StatKeys.canonical("dodge-chance");

    /**
     * パーク/アドオンが配る防御率([0,1] の乗算軽減)のキー。2026-08-15 に
     * {@code armor-defense-rate} から分離した。
     *
     * <p><b>なぜ分けたか</b>: {@code armor-defense-rate} は<b>アイテム側ではバニラ防具値(点数)</b>で
     * ({@link com.trinityforge.stats.AttributeProjection} が {@code Attribute.ARMOR} へ ADD_NUMBER する)、
     * <b>パーク側では [0,1] の軽減率</b>という、互換性の無い2つの単位を1キーで運んでいた。
     * ロア表示も {@code FLAT} 1本なので「防御力 +0.1」と「防具値 +8」が同じ書式で並び、
     * さらに {@link com.trinityforge.stats.PercentStatNormalize} は(防具値のほうを守るために)
     * このキーを%矯正の対象外にしていたので、パーク側に 10 と書くと 1000% 軽減として通っていた。
     */
    private static final String DEFENSE_RATE_KEY = StatKeys.canonical("defense-rate");

    /** 分離前のキー。出荷スキルツリーからは撤去済みだが、配備先の記述を無言で殺さないため読み続ける。 */
    private static final String LEGACY_DEFENSE_RATE_KEY = StatKeys.canonical("armor-defense-rate");

    /**
     * Maps a canonical defender stat map (a skill-tree perk addend or an addon contribution) to a
     * {@link DefenseStats} for {@code type}: 耐性/守備力 are typed (physical/magical), 被ダメ軽減/防御率/
     * 防具強度(会心軽減率%) are common. 防具強度 is now read here too (perks/threads may grant crit
     * reduction; it is summed with the item + vanilla sources and capped once downstream). The
     * {@link DefenseStats} constructor finite-guards every field, so an out-of-range value is safe.
     */
    private static DefenseStats defenseStatsFrom(Map<String, Double> stats, DamageType type) {
        double resistance = switch (type) {
            case PHYSICAL -> stats.getOrDefault(StatKeys.canonical("phys-resistance"), 0.0);
            case MAGICAL -> stats.getOrDefault(StatKeys.canonical("magic-resistance"), 0.0);
            case TYPELESS -> 0.0;
        };
        if (type == DamageType.TYPELESS) return DefenseStats.NONE;
        // 守備力(flat-defense) は物理/魔法へ分離されたが、legacy flat-defense も後方互換で許容する。
        String physKey = StatKeys.canonical("phys-flat-defense");
        String magicKey = StatKeys.canonical("magic-flat-defense");
        String legacyFlatKey = StatKeys.canonical("flat-defense");
        boolean hasTyped = type == DamageType.PHYSICAL ? stats.containsKey(physKey) : stats.containsKey(magicKey);
        double flatDefense = type == DamageType.PHYSICAL
                ? (hasTyped ? stats.getOrDefault(physKey, 0.0) : stats.getOrDefault(legacyFlatKey, 0.0))
                : (hasTyped ? stats.getOrDefault(magicKey, 0.0) : stats.getOrDefault(legacyFlatKey, 0.0));
        // 防御率: 新キー defense-rate を優先し、無いときだけ旧 armor-defense-rate を読む。
        // 両方あっても足さない — 同じ量を2回数えることになるため(守備力の typed/legacy と同じ規則)。
        double defenseRate = stats.containsKey(DEFENSE_RATE_KEY)
                ? stats.getOrDefault(DEFENSE_RATE_KEY, 0.0)
                : stats.getOrDefault(LEGACY_DEFENSE_RATE_KEY, 0.0);
        return new DefenseStats(
                defenseRate,                                                        // 防御率%
                resistance,                                                         // 該当耐性%
                stats.getOrDefault(StatKeys.canonical("damage-reduction"), 0.0),    // 被ダメージ軽減%
                flatDefense,                                                       // 守備力(flat: typed + legacy fallback)
                stats.getOrDefault(StatKeys.canonical("armor-strength"), 0.0));      // 防具強度(会心軽減率%)
    }
}
