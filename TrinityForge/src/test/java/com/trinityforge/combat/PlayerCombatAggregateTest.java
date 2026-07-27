package com.trinityforge.combat;

import com.trinityforge.config.domains.StatCapsConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerCombatAggregateTest {

    @Test
    void negativeMultiplierIsPreservedInsteadOfClampedToZero() {
        PlayerCombatAggregate aggregate = new PlayerCombatAggregate(
                Map.of("attack_power", 10.0),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                // Multiplier layers store Σ(v-1), so authored x-0.5 is represented as -1.5.
                Map.of("curse", Map.of("attack_power", -1.5)));

        assertEquals(-0.5, aggregate.multiplierFor("attack_power"));
        assertEquals(-5.0, aggregate.totalOf("attack_power"));
    }

    // ------------------------------------------------------------------------------------------
    // 2026-07-26 stat-scope 境界引き直し §3 (B→C 昇格): 二重計上回避のリグレッションテスト。
    // ------------------------------------------------------------------------------------------

    /**
     * armor-strength(DEFENSE チャネル)は {@link PlayerStatAggregator} 内で3つの互いに素なチャネルへ
     * 振り分けられる: (1) アイテム自身のステ + base-stats.yml の一律加算 + 永続バフ → すべて
     * {@code item} マップへ merge(Double::sum) される単一の合流先、(2) パーク(スキルツリー)の
     * DEFENSE チャネル buff → {@code perkDefense}、(3) アドオン(ArsPaper防具スレッド等) →
     * {@code addon}。{@link PlayerCombatAggregate#totalOf} はこの3マップを1回ずつ加算するだけなので、
     * base-stats 由来の値が perkDefense や addon 側でも再カウントされない限り二重計上は起きない。
     * ここでは3系統に別々の値を仕込み、合計が単純な総和(倍率1.0)になることを固定する。
     */
    @Test
    void armorStrengthTotalOfSumsEachSourceExactlyOnce() {
        // item = ガチのアイテム側ステ(防具本体) + base-stats.yml 一律加算(PlayerStatAggregator が
        // 同じ item マップへ merge するため、ここでは既に合算済みの1値として表現する)。
        PlayerCombatAggregate aggregate = new PlayerCombatAggregate(
                Map.of("armor_strength", 5.0),   // item側(防具本体 + base-stats 合算済み)
                Map.of(),
                Map.of(),
                Map.of("armor_strength", 3.0),   // perkDefense側(スキルツリーパークbuff)
                Map.of("armor_strength", 2.0),   // addon側(ArsPaper防具スレッド等)
                Map.of());

        assertEquals(10.0, aggregate.totalOf("armor_strength"), 1e-9,
                "armor-strength must be the plain sum of its 3 disjoint sources, not double-counted");
    }

    /**
     * mana-cost-reduction-percent(GENERAL チャネル)は {@link PlayerStatAggregator} 内でパークの
     * general() buff がアイテム側と同じ {@code item} マップへ merge される(§3昇格の消費者にアイテム
     * 直読み経路は存在せず、TF側は現状 addon(ArsPaper側の直接消費)のみが独立チャネル)。ここでは
     * item(=アイテム+base-stats+perk-generalの合流結果)と addon の2系統に値を仕込み、合計が単純な
     * 総和になる(item側の合流自体が2重に足されない)ことを固定する。
     */
    @Test
    void manaCostReductionPercentTotalOfSumsEachSourceExactlyOnce() {
        PlayerCombatAggregate aggregate = new PlayerCombatAggregate(
                Map.of("mana_cost_reduction_percent", 0.10),  // item側(アイテム+base-stats+perk-general合流済み)
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("mana_cost_reduction_percent", 0.05),  // addon側
                Map.of());

        assertEquals(0.15, aggregate.totalOf("mana_cost_reduction_percent"), 1e-9,
                "mana-cost-reduction-percent must be the plain sum of its disjoint sources, not double-counted");
    }

    // ------------------------------------------------------------------------------------------
    // 2026-07-26 stat-cap導入: totalOf が唯一のクランプ適用点であることの回帰テスト。
    // ------------------------------------------------------------------------------------------

    /** statCaps 未設定(null、後方互換コンストラクタ経由)なら既存の計算結果と1つも変わらないこと。 */
    @Test
    void nullStatCapsLeavesTotalOfUnchanged() {
        PlayerCombatAggregate aggregate = new PlayerCombatAggregate(
                Map.of("mining_fortune", 500.0), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

        assertEquals(500.0, aggregate.totalOf("mining_fortune"), 1e-9);
    }

    /** statCaps が設定されていても、対象キーに上限が無ければ既存の計算結果と変わらないこと。 */
    @Test
    void statCapsConfiguredButKeyUncappedLeavesTotalOfUnchanged() {
        StatCapsConfig statCaps = StatCapsConfig.withCaps(Map.of("fishing_luck", 10.0));
        PlayerCombatAggregate aggregate = new PlayerCombatAggregate(
                Map.of("mining_fortune", 500.0), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), statCaps);

        assertEquals(500.0, aggregate.totalOf("mining_fortune"), 1e-9,
                "a cap configured for a different key must not affect this key's total");
    }

    /**
     * クランプは「乗算まで適用し終えた後の最終値」に掛かること(倍率の前ではない)。乗算レイヤで
     * 500 -> 1000 に増幅された後、上限200で切られることを確認する。
     */
    @Test
    void capIsAppliedAfterMultipliersNotBeforeThem() {
        StatCapsConfig statCaps = StatCapsConfig.withCaps(Map.of("mining_fortune", 200.0));
        PlayerCombatAggregate aggregate = new PlayerCombatAggregate(
                Map.of("mining_fortune", 500.0), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("gear", Map.of("mining_fortune", 1.0)), // Σ(v-1)=1.0 -> x2 倍率
                statCaps);

        assertEquals(1000.0, aggregate.item().get("mining_fortune") * aggregate.multiplierFor("mining_fortune"),
                1e-9, "sanity: pre-cap total really is 500 * 2 = 1000");
        assertEquals(200.0, aggregate.totalOf("mining_fortune"), 1e-9,
                "cap must clamp the post-multiplier total, not the raw pre-multiplier sum");
    }

    /** 上限を下回る合算値はそのまま(クランプされない)。 */
    @Test
    void totalBelowCapIsUnaffected() {
        StatCapsConfig statCaps = StatCapsConfig.withCaps(Map.of("mining_fortune", 200.0));
        PlayerCombatAggregate aggregate = new PlayerCombatAggregate(
                Map.of("mining_fortune", 50.0), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), statCaps);

        assertEquals(50.0, aggregate.totalOf("mining_fortune"), 1e-9);
    }

    /** CT短縮系キー(*-cooldown-reduction)へ上限を設定しても totalOf には一切効かないこと。 */
    @Test
    void cooldownReductionKeysAreNeverClampedEvenIfCapConfigured() {
        StatCapsConfig statCaps = StatCapsConfig.withCaps(Map.of(
                "cooldown_reduction", 1.0,
                "bow_cooldown_reduction", 1.0));
        PlayerCombatAggregate aggregate = new PlayerCombatAggregate(
                Map.of("cooldown_reduction", 999.0, "bow_cooldown_reduction", 999.0),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), statCaps);

        assertEquals(999.0, aggregate.totalOf("cooldown_reduction"), 1e-9,
                "cooldown-reduction family must never be clamped by stat-caps (CooldownManager owns this)");
        assertEquals(999.0, aggregate.totalOf("bow_cooldown_reduction"), 1e-9);
    }

    // ------------------------------------------------------------------------------------------
    // 2026-07-26 stat-caps カバレッジ拡大: #clamp(String,double) — CombatListener/PlayerDefenseResolver
    // が独自に組み立てた「最終値」向けの生クランプ出口。totalOf と同じ意味論を共有することを固定する。
    // ------------------------------------------------------------------------------------------

    /** statCaps 未設定(null)なら #clamp は完全な恒等関数であること。 */
    @Test
    void clampWithNullStatCapsIsIdentity() {
        PlayerCombatAggregate aggregate = new PlayerCombatAggregate(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

        assertEquals(123456.0, aggregate.clamp("attack_power", 123456.0), 1e-9);
        assertEquals(-77.0, aggregate.clamp("armor_strength", -77.0), 1e-9);
    }

    /** statCaps 設定済みで対象キーに上限があれば #clamp が上限で切り詰めること。 */
    @Test
    void clampAppliesConfiguredCap() {
        StatCapsConfig statCaps = StatCapsConfig.withCaps(Map.of("attack_power", 50.0));
        PlayerCombatAggregate aggregate = new PlayerCombatAggregate(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), statCaps);

        assertEquals(50.0, aggregate.clamp("attack_power", 500.0), 1e-9);
        assertEquals(30.0, aggregate.clamp("attack_power", 30.0), 1e-9,
                "a value already below the cap must be unaffected");
    }

    /** #clamp は CT短縮系キーを totalOf と同じくスルーすること(二重管理防止の意味論を共有)。 */
    @Test
    void clampSkipsCooldownReductionFamilyLikeTotalOf() {
        StatCapsConfig statCaps = StatCapsConfig.withCaps(Map.of("cooldown_reduction", 1.0));
        PlayerCombatAggregate aggregate = new PlayerCombatAggregate(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), statCaps);

        assertEquals(999.0, aggregate.clamp("cooldown_reduction", 999.0), 1e-9);
    }
}
