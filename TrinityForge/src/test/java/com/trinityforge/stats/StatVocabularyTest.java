package com.trinityforge.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link StatVocabulary}: the single-source channel mapping that replaced
 * {@code PerkBuffResolver}'s four hand-maintained {@code static Set}s (2026-07-23 stat-gate-overhaul §2).
 */
class StatVocabularyTest {

    @Test
    @DisplayName("pre-existing ATTACK keys keep their channel")
    void existingAttackKeys() {
        for (String key : new String[]{"attack_power", "flat_bonus_damage", "percent_bonus_damage",
                "crit_chance", "crit_damage", "penetration", "damage_modifier", "fixed_damage",
                "bleed_chance", "bleed_damage"}) {
            assertEquals(StatVocabulary.Channel.ATTACK, StatVocabulary.channelOf(key), key);
        }
    }

    @Test
    @DisplayName("pre-existing DEFENSE keys keep their channel")
    void existingDefenseKeys() {
        for (String key : new String[]{"phys_resistance", "magic_resistance", "flat_defense",
                "phys_flat_defense", "magic_flat_defense", "damage_reduction",
                "armor_defense_rate", "dodge_chance"}) {
            assertEquals(StatVocabulary.Channel.DEFENSE, StatVocabulary.channelOf(key), key);
        }
    }

    @Test
    @DisplayName("pre-existing ATTRIBUTE keys keep their channel")
    void existingAttributeKeys() {
        for (String key : new String[]{"move_speed", "attack_reach",
                "knockback_resistance", "max_health"}) {
            assertEquals(StatVocabulary.Channel.ATTRIBUTE, StatVocabulary.channelOf(key), key);
        }
    }

    @Test
    @DisplayName("pre-existing GENERAL keys keep their channel")
    void existingGeneralKeys() {
        for (String key : new String[]{"mining_fortune", "fishing_luck", "fishing_bonus"}) {
            assertEquals(StatVocabulary.Channel.GENERAL, StatVocabulary.channelOf(key), key);
        }
    }

    @Test
    @DisplayName("bow/archery new keys route to ATTACK")
    void archeryKeysRouteAttack() {
        for (String key : new String[]{"bow_accuracy", "ammo_save_chance", "distance_damage_bonus",
                "arrow_piercing", "arrow_velocity", "bow_cooldown_reduction", "arrow_knockback"}) {
            assertEquals(StatVocabulary.Channel.ATTACK, StatVocabulary.channelOf(key), key);
        }
    }

    @Test
    @DisplayName("melee new keys route to ATTACK")
    void meleeKeysRouting() {
        for (String key : new String[]{"melee_knockback", "stun_chance", "power_attack_damage",
                "power_attack_radius"}) {
            assertEquals(StatVocabulary.Channel.ATTACK, StatVocabulary.channelOf(key), key);
        }
    }

    @Test
    @DisplayName("cooldown_reduction is ATTACK; health_regen_bonus is DEFENSE (per design §2.1 exceptions)")
    void survivalKeyExceptions() {
        assertEquals(StatVocabulary.Channel.ATTACK, StatVocabulary.channelOf("cooldown_reduction"));
        assertEquals(StatVocabulary.Channel.DEFENSE, StatVocabulary.channelOf("health_regen_bonus"));
    }

    @Test
    @DisplayName("2026-07-25 CT設計一本化 §2: haste_active_mining_cooldown_reduction is ATTACK, independent of cooldown_reduction")
    void hasteActiveMiningCooldownReductionIsAttackAndDistinctFromWeaponCooldownReduction() {
        assertEquals(StatVocabulary.Channel.ATTACK,
                StatVocabulary.channelOf("haste_active_mining_cooldown_reduction"));
        assertEquals(StatVocabulary.Channel.ATTACK,
                StatVocabulary.channelOf("haste-active-mining-cooldown-reduction"));
        assertTrue(StatVocabulary.isKnown("haste_active_mining_cooldown_reduction"));
    }

    @Test
    @DisplayName("remaining new survival/craft/gathering/ars/fork-consumer keys route to GENERAL")
    void otherNewKeysRouteGeneral() {
        for (String key : new String[]{
                "hunger_save_chance", "mob_drop_bonus", "skill_exp_bonus", "loot_luck",
                "mob_drop_quality", "gacha_rate_bonus", "suspicious_respawn_chance",
                "hive_harvest_fortune", "food_save_chance",
                "workbench_quality_bonus", "ritual_quality_bonus", "craft_upswing_bonus", "craft_downswing_reduction",
                "craft_roll_up_bonus", "craft_roll_down_reduction", "craft_roll_inset",
                "mana_bonus", "mana_regen",
                "lapis_cost_reduction", "source_cost_reduction", "material_refund_chance",
                "ingredient_save_chance"}) {
            assertEquals(StatVocabulary.Channel.GENERAL, StatVocabulary.channelOf(key), key);
        }
    }

    @Test
    @DisplayName("kebab-case spellings canonicalize identically to snake_case")
    void kebabCaseCanonicalizes() {
        assertEquals(StatVocabulary.Channel.ATTACK, StatVocabulary.channelOf("bow-accuracy"));
        assertEquals(StatVocabulary.Channel.GENERAL, StatVocabulary.channelOf("workbench-quality-bonus"));
    }

    @Test
    @DisplayName("unknown keys are NONE / not known")
    void unknownKeyIsNone() {
        assertEquals(StatVocabulary.Channel.NONE, StatVocabulary.channelOf("not-a-real-stat"));
        assertFalse(StatVocabulary.isKnown("not-a-real-stat"));
        assertTrue(StatVocabulary.isKnown("attack_power"));
    }

    /**
     * 注記(このタスクの範囲外・発見のみ): {@code tool_enchant_efficiency} は本テストの対象から外した。
     * 2026-07-26 の別セッション(効率ステータス統合、{@link StatKeys#canonical} のレガシーエイリアス表)で
     * {@code tool_enchant_efficiency -> gathering_efficiency} への読み替えが導入済みで、
     * {@code gathering_efficiency} 自体は総合ステ(GENERAL)なので、もはや NONE にはならない
     * (item専用ステ卒業はここでの意図的仕様変更ではなく、先行セッションの既存挙動)。同じ
     * "tool-enchant-<enchant>" 接頭辞方式でエイリアスされていない {@code tool_enchant_fortune} で
     * 「真の item専用キーは NONE」という本テストの意図を保つ。
     */
    @Test
    @DisplayName("item-only keys (item_cooldown/durability/tool_enchant_*) are out of scope: NONE")
    void itemOnlyKeysAreNone() {
        assertEquals(StatVocabulary.Channel.NONE, StatVocabulary.channelOf("item_cooldown"));
        assertEquals(StatVocabulary.Channel.NONE, StatVocabulary.channelOf("durability"));
        assertEquals(StatVocabulary.Channel.NONE, StatVocabulary.channelOf("tool_enchant_fortune"));
    }

    @Test
    @DisplayName("2026-07-25 economy-integration keys (fish-sell/disassembly/ocean-fishing) route to GENERAL")
    void economyIntegrationKeysRouteGeneral() {
        for (String key : new String[]{"fish_sell_price_bonus", "disassembly_return_bonus", "ocean_fishing_bonus"}) {
            assertEquals(StatVocabulary.Channel.GENERAL, StatVocabulary.channelOf(key), key);
            assertTrue(StatVocabulary.isKnown(key), key);
        }
        // kebab-case spellings canonicalize identically.
        assertEquals(StatVocabulary.Channel.GENERAL, StatVocabulary.channelOf("fish-sell-price-bonus"));
    }

    /**
     * 2026-07-26 stat-scope 境界引き直し §1/§2 (C→A 降格): coating-charges/attack-speed は総合ステ
     * (StatVocabulary)から外れ、アイテム固有ステとなった(消費者はメインハンド武器1点だけを直接
     * DerivedItemStats.resolve で読む)。この2キーがここへ再登録されない(=巻き込み復活しない)ことを
     * 固定するリグレッションガード。
     */
    @Test
    @DisplayName("2026-07-26 coating-charges/attack-speed are demoted to item-only (A層): NONE/not known")
    void coatingChargesAndAttackSpeedAreItemOnlyNotVocabulary() {
        assertEquals(StatVocabulary.Channel.NONE, StatVocabulary.channelOf("coating_charges"));
        assertFalse(StatVocabulary.isKnown("coating_charges"));
        assertEquals(StatVocabulary.Channel.NONE, StatVocabulary.channelOf("attack_speed"));
        assertFalse(StatVocabulary.isKnown("attack_speed"));
        // kebab-case spellings too.
        assertFalse(StatVocabulary.isKnown("coating-charges"));
        assertFalse(StatVocabulary.isKnown("attack-speed"));
    }

    /**
     * 2026-07-26: attack-speed-bonus(割合・全ソース横断)は attack-speed とは別物で、C層に残る。
     * attack-speed の巻き込み削除で attack-speed-bonus まで一緒に消えていないことを固定する
     * (再発防止 — model-delegation の "巻き込み削除" 事故と同種のパターン)。
     */
    @Test
    @DisplayName("2026-07-26 attack-speed-bonus remains a vocabulary (ATTRIBUTE) key — not swept away with attack-speed")
    void attackSpeedBonusRemainsRegistered() {
        assertEquals(StatVocabulary.Channel.ATTRIBUTE, StatVocabulary.channelOf("attack_speed_bonus"));
        assertTrue(StatVocabulary.isKnown("attack_speed_bonus"));
        assertTrue(StatVocabulary.isKnown("attack-speed-bonus"));
    }

    /**
     * 2026-07-26 stat-scope 境界引き直し §3 (B→C 昇格): 8キーがそれぞれ想定チャネルへ登録されたことを
     * 固定する。従来はアイテムにしか書けず装備間で合算されるだけの中途半端な層(B層)だったが、
     * StatVocabulary登録によりパーク/base-statsからも供給できる総合ステ(C層)になった。
     */
    @Test
    @DisplayName("2026-07-26 B→C promoted 8 keys route to their designed channel")
    void bToCPromotedKeysRouteToDesignedChannel() {
        assertEquals(StatVocabulary.Channel.DEFENSE, StatVocabulary.channelOf("armor_strength"));
        assertEquals(StatVocabulary.Channel.ATTACK, StatVocabulary.channelOf("aoe_radius"));
        assertEquals(StatVocabulary.Channel.ATTACK, StatVocabulary.channelOf("aoe_max_targets"));
        assertEquals(StatVocabulary.Channel.ATTACK, StatVocabulary.channelOf("aoe_damage_rate"));
        assertEquals(StatVocabulary.Channel.GENERAL, StatVocabulary.channelOf("hit_mana_recovery"));
        assertEquals(StatVocabulary.Channel.GENERAL, StatVocabulary.channelOf("damage_mana_recovery"));
        assertEquals(StatVocabulary.Channel.GENERAL, StatVocabulary.channelOf("mana_cost_reduction_flat"));
        assertEquals(StatVocabulary.Channel.GENERAL, StatVocabulary.channelOf("mana_cost_reduction_percent"));
        for (String key : new String[]{"armor_strength", "aoe_radius", "aoe_max_targets", "aoe_damage_rate",
                "hit_mana_recovery", "damage_mana_recovery", "mana_cost_reduction_flat",
                "mana_cost_reduction_percent"}) {
            assertTrue(StatVocabulary.isKnown(key), key);
        }
    }

    @Test
    @DisplayName("2026-07-26 新設: enchant_cost_reduction is GENERAL, stun_duration_bonus is ATTACK")
    void newlyAddedKeysRouteToDesignedChannel() {
        assertEquals(StatVocabulary.Channel.GENERAL, StatVocabulary.channelOf("enchant_cost_reduction"));
        assertTrue(StatVocabulary.isKnown("enchant_cost_reduction"));
        assertEquals(StatVocabulary.Channel.ATTACK, StatVocabulary.channelOf("stun_duration_bonus"));
        assertTrue(StatVocabulary.isKnown("stun_duration_bonus"));
        // kebab-case spellings canonicalize identically.
        assertEquals(StatVocabulary.Channel.GENERAL, StatVocabulary.channelOf("enchant-cost-reduction"));
        assertEquals(StatVocabulary.Channel.ATTACK, StatVocabulary.channelOf("stun-duration-bonus"));
    }
}
