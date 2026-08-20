package com.trinityforge.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PercentStatNormalizeTest {

    @Test
    @DisplayName("damage-modifier 75 (percent points) becomes 0.75")
    void damageModifierPercentPoints() {
        assertEquals(0.75, PercentStatNormalize.coerce("damage-modifier", 75.0), 1e-9);
        assertEquals(0.70, PercentStatNormalize.coerce("damage-modifier", 70.0), 1e-9);
        assertEquals(0.75, PercentStatNormalize.coerce("damage-modifier", 0.75), 1e-9);
        assertEquals(1.2, PercentStatNormalize.coerce("damage-modifier", 1.2), 1e-9);
    }

    @Test
    @DisplayName("crit-damage is not coerced (10 may mean +1000%)")
    void critDamageUntouched() {
        assertEquals(10.0, PercentStatNormalize.coerce("crit-damage", 10.0), 1e-9);
    }

    @Test
    @DisplayName("2026-08-15 防具値の廃止: defense-rate は率系なので 30 -> 0.30 へ矯正される")
    void defenseRateIsCoercedLikeEveryOtherRate() {
        // 旧 armor-defense-rate は「アイテム側=バニラ防具値(点数)」を守るため矯正の対象外だった。
        // その結果パーク側に 10 と書くと 1000% 軽減として通っていた。防具値を廃止して率へ一本化したので、
        // ここが対象外に戻ったら同じ事故が再発する。
        assertTrue(PercentStatNormalize.isRateKey("defense-rate"));
        assertEquals(0.30, PercentStatNormalize.coerce("defense-rate", 30.0), 1e-9);
        assertEquals(0.12, PercentStatNormalize.coerce("defense-rate", 0.12), 1e-9);
        // 廃止済みキーは語彙にも矯正対象にも残っていない。
        assertFalse(PercentStatNormalize.isRateKey("armor-defense-rate"));
    }

    @Test
    @DisplayName("2026-07-23 stat-gate-overhaul: new PERCENT keys are rate keys and coerce 75 -> 0.75")
    void newPercentKeysAreRateKeys() {
        String[] newPercentKeys = {
                "bow-accuracy", "ammo-save-chance", "distance-damage-bonus", "arrow-velocity",
                "stun-chance", "power-attack-damage",
                "health-regen-bonus", "hunger-save-chance", "mob-drop-bonus",
                "skill-exp-bonus", "cooldown-reduction", "haste-active-mining-cooldown-reduction",
                "gacha-rate-bonus", "suspicious-respawn-chance",
                "hive-harvest-fortune", "food-save-chance",
                "source-cost-reduction", "material-refund-chance", "ingredient-save-chance",
                "fishing-luck"
        };
        for (String key : newPercentKeys) {
            assertTrue(PercentStatNormalize.isRateKey(key), key + " should be a rate key");
            assertEquals(0.75, PercentStatNormalize.coerce(key, 75.0), 1e-9, key + " should coerce 75 -> 0.75");
        }
    }

    @Test
    @DisplayName("new INTEGER/FLAT keys (arrow-piercing, craft-*) are NOT rate keys")
    void newNonPercentKeysAreNotRateKeys() {
        assertFalse(PercentStatNormalize.isRateKey("arrow-piercing"));
        assertFalse(PercentStatNormalize.isRateKey("craft-roll-inset"));
        // 2026-07-31: melee-knockback は arrow-knockback と単位系を揃えて FLAT + 単位 m にしたので
        // rate キーから外した(残すと 2(=2m) が 0.02 へ矯正される)。
        assertFalse(PercentStatNormalize.isRateKey("melee-knockback"));
        assertEquals(2.0, PercentStatNormalize.coerce("melee-knockback", 2.0), 1e-9);
    }

    @Test
    @DisplayName("enchant-cost-reduction is a rate; stun-duration-bonus is a flat tick count")
    void stunDurationUsesTicksInsteadOfPercentNormalization() {
        assertTrue(PercentStatNormalize.isRateKey("enchant-cost-reduction"));
        assertEquals(0.75, PercentStatNormalize.coerce("enchant-cost-reduction", 75.0), 1e-9);
        assertFalse(PercentStatNormalize.isRateKey("stun-duration-bonus"));
        assertEquals(50.0, PercentStatNormalize.coerce("stun-duration-bonus", 50.0), 1e-9);
        assertEquals(5.0, PercentStatNormalize.coerce("stun-duration-bonus", 0.2), 1e-9,
                "legacy addends must be converted before different sources are aggregated");
        assertEquals(1.0, PercentStatNormalize.coerce("stun-duration-bonus", 1.0), 1e-9,
                "one tick is a valid value in the new unit and must not be treated as legacy 100%");
        assertEquals(-1.0, PercentStatNormalize.coerce("stun-duration-bonus", -1.0), 1e-9);
    }

    /**
     * 2026-08-05 実サーバ報告「釣りボーナスは%では？ドロップ増加ステと同じ期待値仕様だったはず」。
     *
     * <p>登録を外すと出荷 {@code skilltree/fishing.yml} のパーセントポイント表記(A:5 / C:10 /
     * prestige:10)が矯正されず、合算 25 がそのまま {@code GatheringPolicy.expectedExtra} の期待個数に
     * なる(=1回の釣りで追加ドロップ25個)。mining-fortune と同じ扱いであることを固定する。
     */
    @Test
    @DisplayName("fishing-bonus / ocean-fishing-bonus は mining-fortune と同じ%系(期待値)")
    void fishingBonusIsARateKeyLikeMiningFortune() {
        for (String key : new String[]{"fishing-bonus", "ocean-fishing-bonus"}) {
            assertTrue(PercentStatNormalize.isRateKey(key), key + " should be a rate key");
            assertEquals(0.10, PercentStatNormalize.coerce(key, 10.0), 1e-9,
                    key + ": 出荷スキルツリーの 10 は +10%(期待値0.10個)でなければならない");
            assertEquals(0.05, PercentStatNormalize.coerce(key, 0.05), 1e-9,
                    key + ": 既に分数で書かれた値(0.05)は素通りする");
        }
        assertEquals(PercentStatNormalize.coerce("mining-fortune", 15.0),
                PercentStatNormalize.coerce("fishing-bonus", 15.0), 1e-9,
                "ドロップ増加ステ(mining-fortune)と単位系が一致していること");
    }

    @Test
    @DisplayName("2026-08-14 廃止: lapis-cost-reduction は RATE_KEYS にも戻らない(復活の検知)")
    void lapisCostReductionStaysRetired() {
        // 廃止キーなので RATE_KEYS へ入れ直されていないことだけを見る。ここが true になったら
        // 消費側(ArsPaper)を消したのに coerce だけ復活した状態で、yml に書けてしまう。
        assertFalse(PercentStatNormalize.isRateKey("lapis-cost-reduction"));
    }
}
