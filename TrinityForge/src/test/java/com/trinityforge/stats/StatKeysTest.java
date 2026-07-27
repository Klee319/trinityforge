package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatKeysTest {

    @Test
    void foldsKebabToSnake() {
        assertEquals("attack_damage", StatKeys.canonical("attack-damage"));
    }

    @Test
    void leavesSnakeUnchanged() {
        assertEquals("attack_damage", StatKeys.canonical("attack_damage"));
    }

    @Test
    void lowercasesMixedCase() {
        assertEquals("max_health", StatKeys.canonical("Max-Health"));
    }

    @Test
    void kebabAndSnakeShareOneCanonicalForm() {
        assertEquals(StatKeys.canonical("move-speed"), StatKeys.canonical("move_speed"));
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> StatKeys.canonical(null));
    }

    // ------------------------------------------------------------------------
    // 後方互換エイリアス (2026-07-26 キー統合: weapon-cooldown → item-cooldown)
    //
    // エイリアスが壊れると、ユーザーが手編集した yml に残っている旧キーが
    // 「警告も出さずにステータスごと消える」= このプロジェクトで最も避けたい無言の破壊になる。
    // 素の改名では検知できないため、ここで挙動を固定する。
    // ------------------------------------------------------------------------

    @Test
    void legacyWeaponCooldownSpellingsAllFoldToItemCooldown() {
        assertEquals("item_cooldown", StatKeys.canonical("weapon-cooldown"));
        assertEquals("item_cooldown", StatKeys.canonical("weapon_cooldown"));
        assertEquals("item_cooldown", StatKeys.canonical("Weapon-Cooldown"));
    }

    @Test
    void newItemCooldownKeyIsUnaffectedByTheAlias() {
        assertEquals("item_cooldown", StatKeys.canonical("item-cooldown"));
        assertEquals("item_cooldown", StatKeys.canonical("item_cooldown"));
    }

    @Test
    void unrelatedKeysAreNeverAliased() {
        assertEquals("crit_chance", StatKeys.canonical("crit-chance"));
        // CT短縮側(cooldown-reduction)はキー統合の対象外 — 巻き込まれていないこと。
        assertEquals("cooldown_reduction", StatKeys.canonical("cooldown-reduction"));
        assertNotEquals("item_cooldown", StatKeys.canonical("cooldown-reduction"));
    }

    @Test
    void repeatedLegacyLookupsReturnTheSameResult() {
        // 「警告は1回だけ」の実装(WARNED_LEGACY_KEYS)は副作用付きなので、
        // 2回目以降で戻り値が変わらないことを固定する。
        String first = StatKeys.canonical("weapon-cooldown");
        for (int i = 0; i < 100; i++) {
            assertEquals(first, StatKeys.canonical("weapon-cooldown"));
        }
    }

    // ------------------------------------------------------------------------
    // 後方互換エイリアス (2026-07-26 キー統合: tool-enchant-efficiency → gathering-efficiency)
    //
    // 「効率」ステータスの2経路(アイテム単位のtool-enchant-efficiencyと、総合ステータスの
    // gathering-efficiency)を統合する。エイリアスが壊れると、二重付与(両経路が別々にエンチャント
    // レベルを決める)か無言のステ消失のどちらかが起きる — このプロジェクトで最も避けたい事故。
    // ------------------------------------------------------------------------

    @Test
    void legacyToolEnchantEfficiencySpellingsAllFoldToGatheringEfficiency() {
        assertEquals("gathering_efficiency", StatKeys.canonical("tool-enchant-efficiency"));
        assertEquals("gathering_efficiency", StatKeys.canonical("tool_enchant_efficiency"));
        assertEquals("gathering_efficiency", StatKeys.canonical("Tool-Enchant-Efficiency"));
    }

    @Test
    void newGatheringEfficiencyKeyIsUnaffectedByTheAlias() {
        assertEquals("gathering_efficiency", StatKeys.canonical("gathering-efficiency"));
        assertEquals("gathering_efficiency", StatKeys.canonical("gathering_efficiency"));
    }

    @Test
    void otherToolEnchantKeysAreNeverCaughtByTheEfficiencyAlias() {
        // 効率(efficiency)以外のtool-enchant-*(幸運/シルクタッチ/耐久力等)は今までどおり
        // ItemAssemblerのTOOL_ENCHANT_PREFIX経路で焼かれ続けなければならない。これが壊れると
        // 全ツールのエンチャントが無言で消える。
        assertEquals("tool_enchant_fortune", StatKeys.canonical("tool-enchant-fortune"));
        assertEquals("tool_enchant_silk_touch", StatKeys.canonical("tool-enchant-silk-touch"));
        assertEquals("tool_enchant_unbreaking", StatKeys.canonical("tool-enchant-unbreaking"));
        assertNotEquals("gathering_efficiency", StatKeys.canonical("tool-enchant-fortune"));
        assertNotEquals("gathering_efficiency", StatKeys.canonical("tool-enchant-silk-touch"));
        assertNotEquals("gathering_efficiency", StatKeys.canonical("tool-enchant-unbreaking"));
    }
}
