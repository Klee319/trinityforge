package com.trinityforge.stats;

import java.util.Locale;

/** Infers lore / editor stat category from stat key when lore.yml omits {@code category}. */
public final class StatCategoryInference {

    private StatCategoryInference() {
    }

    public static StatCategory infer(String statKey) {
        String key = StatKeys.canonical(statKey).toLowerCase(Locale.ROOT);
        // アイテムCT(表示名: CT)と効率強化増幅(tool-enchant-*)は「その他」カテゴリ(2026-07 仕様変更)。
        // 2026-07-26 キー統合(ユーザー決定): weapon_cooldown → item_cooldown へリネーム。
        if (key.contains("item_cooldown") || key.startsWith("tool_enchant_")) {
            return StatCategory.OTHER;
        }
        // 2026-07-23 敵対的レビュー指摘: 下の部分一致ルールより先に評価する明示的な例外。
        // 例: arrow_knockback/melee_knockback は「knockback」を含むが防御ではなく攻撃(弓系・近接系)。
        if (key.startsWith("arrow_")
                || key.equals("melee_knockback")
                || key.equals("bow_accuracy")
                || key.equals("ammo_save_chance")
                || key.equals("stun_chance")
                || key.equals("cooldown_reduction")
                // 2026-07-25 CT設計一本化 §2: 旧 skill_cooldown_reduction をActiveSkill単位へ分割。
                // 新しいActiveSkillを追加したら、対応する "<id>_cooldown_reduction" をここにも追加すること
                // (さもないと下の key.contains("mining") 等の部分一致ルールに先取りされ、意図しない
                // カテゴリに落ちる)。
                || key.equals("haste_active_mining_cooldown_reduction")
                || key.equals("tree_fell_cooldown_reduction")
                || key.equals("bow_cooldown_reduction")
                || key.equals("distance_damage_bonus")) {
            return StatCategory.ATTACK;
        }
        if (key.equals("health_regen_bonus")
                || key.equals("reflect_flat") || key.equals("reflect_percent")) {
            return StatCategory.DEFENSE;
        }
        if (key.equals("lapis_cost_reduction")
                || key.equals("material_refund_chance")
                || key.equals("ingredient_save_chance")
                || key.equals("enchant_luck")
                || key.equals("enchant_exp_gain_bonus")
                || key.equals("potion_quality_bonus")
                || key.equals("brew_speed_bonus")) {
            return StatCategory.CRAFT;
        }
        if (key.equals("source_cost_reduction")) {
            return StatCategory.ARS;
        }
        // 2026-07-26 stat-scope 境界引き直し §1 (C→A 降格): coating-charges はここから除外した
        // (item専用ステとなり総合ステvocabularyから外れたため。lore/エディタ表示は default の OTHER
        // カテゴリへ落ちる。他の item専用ステ (item-cooldown/tool-enchant-*) と同じ位置付け)。
        if (key.equals("hunger_save_chance")
                || key.equals("food_save_chance")
                || key.equals("loot_luck")
                || key.equals("gacha_rate_bonus")
                || key.startsWith("mob_drop_")
                || key.equals("skill_exp_bonus")) {
            return StatCategory.UTILITY;
        }
        if (key.equals("suspicious_respawn_chance") || key.equals("hive_harvest_fortune")) {
            return StatCategory.GATHERING;
        }
        if (key.contains("mana")
                || key.contains("spell")
                || key.contains("glyph")
                || key.contains("thread")
                || key.contains("slot")
                || key.contains("arcane")) {
            return StatCategory.ARS;
        }
        if (key.contains("armor")
                || key.contains("defense")
                || key.contains("resistance")
                || key.contains("dodge")
                || key.contains("damage_reduction")
                || key.contains("max_health")
                || key.contains("knockback")) {
            return StatCategory.DEFENSE;
        }
        if (key.contains("attack")
                || key.contains("crit")
                || key.contains("penetration")
                || key.contains("bleed")
                || key.contains("aoe")
                || key.contains("bonus_damage")
                || key.contains("damage_modifier")
                || key.contains("fixed_damage")
                || key.contains("damage")) {
            return StatCategory.ATTACK;
        }
        if (key.contains("craft")) {
            return StatCategory.CRAFT;
        }
        if (key.contains("mining")
                || key.contains("fishing")
                || key.contains("fortune")
                || key.contains("gathering")) {
            return StatCategory.GATHERING;
        }
        if (key.contains("move_speed")) {
            return StatCategory.UTILITY;
        }
        return StatCategory.OTHER;
    }
}
