package com.trinityforge.command;

import java.util.Locale;
import java.util.Set;

/**
 * Category filter for {@link StatsCommand}
 * ({@code attack|armor|craft|gathering|utility|ars|other|all}). Top-level enum so Paper's plugin
 * classloader can load tab-complete lambdas without inner-class resolution failures.
 *
 * <p>2026-07-23 stat-gate-overhaul §2.3: craft/gathering/utility were split out of the {@code other}
 * bucket to mirror the new {@code stats/lore.yml} category set. §2.4 (adversarial review) added the
 * {@code ars} bucket for the same reason — Ars Nouveau mana/thread keys were falling into {@code other}.
 *
 * <p><b>キー追加時の注意</b>: このクラスの {@code *_KEYS} 集合は {@link com.trinityforge.stats.StatVocabulary}
 * や {@code stats/lore.yml} とは独立して手動管理されており、自動同期しない。新しい stat キーを追加する際は
 * {@code StatVocabulary}/{@code lore.yml} と合わせてここも更新すること（さもなくばドリフトが発生する）。
 */
public enum StatsCategory {
    ATTACK, ARMOR, CRAFT, GATHERING, UTILITY, ARS, OTHER, ALL;

    /**
     * {@link com.trinityforge.stats.StatVocabulary} に無いが、ここには意図的に載せているキー。
     * どちらも「プレイヤー合算ステではなくアイテム単体に付くステ」で、ボキャブラリ側は
     * {@code GENERAL_KEYS} の javadoc どおり item専用キーを対象外にしている。しかし {@code /tf stats} は
     * 装備由来のステも並べるため、分類先が無いと OTHER バケツに落ちる。
     *
     * <p>{@link StatsCategoryCoverageTest} の逆方向ドリフト検知テストが参照する許可リストでもある。
     * 新しい item専用キーを {@code *_KEYS} に足すときは、ここにも足すこと。
     */
    static final Set<String> ITEM_ONLY_KEYS = Set.of("item_cooldown", "thread_slots");

    private static final Set<String> ATTACK_KEYS = Set.of(
            // 2026-07-26: "attack_speed" を削除。同キーは stat-scope 境界引き直し §2 (C→A 降格) で
            // StatVocabulary から除外済みで、ここに残っていても永久に一致しない死んだ要素だった
            // (PerkAttributeApplier は DerivedItemStats.resolve でメインハンドを直接解決する経路へ移行済み)。
            // 割合版の "attack_speed_bonus" は総合ステとして現役なので巻き込み削除しないこと。
            "attack_power", "attack_speed_bonus", "attack_reach",
            "aoe_radius", "aoe_damage_rate", "aoe_max_targets",
            "flat_bonus_damage", "percent_bonus_damage", "damage_modifier", "fixed_damage",
            "item_cooldown", "crit_chance", "crit_damage", "penetration",
            "bleed_chance", "bleed_damage", "bleed_damage_rate",
            // 弓系・近接系・cooldown_reduction (stat-gate-overhaul §2.1)
            "bow_accuracy", "ammo_save_chance", "distance_damage_bonus", "arrow_piercing",
            // 2026-07-31: bow_cooldown_reduction を撤去(アイテムCT短縮へ一本化。StatVocabulary 参照)。
            "arrow_velocity", "arrow_knockback",
            "melee_knockback", "stun_chance", "power_attack_damage", "power_attack_radius",
            "cooldown_reduction",
            // 2026-07-26 M-stats分類: OTHER落ちしていた登録済みキーの回収。スタン時間は近接の
            // stun_chance と対。(charged_shot_unlocked は 2026-07-27 に死にキーとして撤去)
            "stun_duration_bonus",
            // 2026-07-25 CT設計一本化 §2: 旧グローバル skill_cooldown_reduction をActiveSkill単位へ分割。
            // 新しいActiveSkillを追加したら、対応する "<id>_cooldown_reduction" をここにも追加すること
            // (ActiveSkillCooldownKeys/StatVocabulary/PercentStatNormalize/StatCategoryInferenceと同様、
            // このSetも独立して手動管理されており自動同期しない)。
            "haste_active_mining_cooldown_reduction", "haste_active_digging_cooldown_reduction",
            "tree_fell_cooldown_reduction");

    private static final Set<String> ARMOR_KEYS = Set.of(
            // 2026-08-15: 防具値(armor_defense_rate)を廃止し defense_rate(軽減率%)へ一本化した。
            "defense_rate",
            "armor_strength", "max_health", "knockback_resistance",
            "phys_resistance", "magic_resistance", "flat_defense",
            "phys_flat_defense", "magic_flat_defense", "damage_reduction", "dodge_chance",
            "health_regen_bonus",
            // 2026-07-25 課題2: 棘の鎧ステータス化(反射率実/割)。
            "reflect_flat", "reflect_percent",
            // 2026-07-26 M-stats分類: 軽装/重装のセット効果。移動速度・回避に効くが「防具を着ることで
            // 得られる効果」なので UTILITY ではなく ARMOR に置く(lore.yml の防具カテゴリと揃える)。
            // 2026-07-31: heavy/light_armor_move_speed_per_piece は語彙ごと撤去(set-buffs の
            // move-speed へ統合)。armor_set_bonus だけがこの分類に残る。
            // 2026-07-27 (armor-set-buffs 全面移行): 旧4キー(light/heavy-armor-set-bonus-multiplier,
            // light-armor-set-dodge-chance, heavy-armor-set-knockback-resistance)を armor-set-bonus
            // 1本(軽装/重装共通の増幅率)へ統一。
            "armor_set_bonus");

    private static final Set<String> CRAFT_KEYS = Set.of(
            // 2026-07-31: 旧 craft_upswing_bonus / craft_downswing_reduction を作業台/儀式の2組へ分割。
            "workbench_upswing_bonus", "ritual_upswing_bonus",
            "workbench_downswing_reduction", "ritual_downswing_reduction",
            "craft_roll_up_bonus", "craft_roll_down_reduction", "craft_roll_inset",
            // ※ lapis_cost_reduction は 2026-08-14 に廃止(ArsPaper の消費側リスナーごと削除)。
            "material_refund_chance",
            "ingredient_save_chance",
            // 経済連携(2026-07-25): 解体(DisassemblyListener)は生産系の一つ。
            "disassembly_return_bonus",
            // エンチャント/ポーション品質(2026-07-25): 生産系スキルの一つとしてクラフトへ分類。
            // ※ enchant_exp_gain_bonus は 2026-08-14 に enchanting_exp_bonus へ統合して廃止。
            "enchant_luck", "potion_quality_bonus", "brew_speed_bonus",
            // 2026-07-26 M-stats分類: エンチャント費用・作業台/儀式の品質。いずれも生産の成果物に効く。
            "enchant_cost_reduction", "workbench_quality_bonus", "ritual_quality_bonus",
            // 2026-07-28 (数値のギミックyml集約): feature:coating-stack-increase から降格。
            "coating_charges_bonus");

    private static final Set<String> GATHERING_KEYS = Set.of(
            "mining_fortune", "fishing_luck", "fishing_bonus",
            "suspicious_respawn_chance", "hive_harvest_fortune",
            // 経済連携(2026-07-25): 釣りの売却額倍率 / 海釣り限定の fishing_bonus 追加分。
            "fish_sell_price_bonus", "ocean_fishing_bonus",
            // 2026-07-25 採掘効率エンチャント連動方式(属性ベースを取り下げ再設計):
            // 農業/採掘/伐採/切削のメインハンド道具にだけ効率強化エンチャントとして反映される総合ステ。
            "gathering_efficiency",
            // 2026-07-26 M-stats分類: 伐採/収穫の追加ドロップと、農業(作物成長・繁殖)。どれも
            // 「採る量を増やす」系なので GATHERING に置く。
            "woodcutting_extra_drop_chance", "harvest_extra_drop_chance",
            "planted_crop_growth_bonus", "bred_animal_growth_bonus", "breeding_extra_child_chance");

    private static final Set<String> FIXED_UTILITY_KEYS = Set.of(
            "move_speed", "hunger_save_chance", "mob_drop_bonus", "skill_exp_bonus",
            "loot_luck", "mob_drop_quality", "gacha_rate_bonus", "food_save_chance",
            // 2026-07-26 M-stats分類: バニラEXP倍率(全源+源別)と満腹度系。特定の生産/採取スキルに
            // 属さない「生活まわり」なので UTILITY。
            "vanilla_exp_bonus", "kill_vanilla_exp_bonus", "break_vanilla_exp_bonus",
            "breeding_vanilla_exp_bonus", "food_restore_bonus", "hidden_saturation_bonus");

    /**
     * 上の固定リスト + 職業EXP増加(スキル別)。後者は {@code skill_exp_bonus} と同じ扱いで UTILITY。
     * 2026-08-02 の新設時は3件だけ手書きされており、残り12スキル分は語彙にも無かった。
     * 2026-08-05 に {@link com.trinityforge.stats.SkillExpBonusKeys}（{@code SkillId.ALL} 由来）
     * からの導出へ切り替え、スキルが増えたときに {@code /tf stats} のタブから無言で
     * 落ちる（OTHER 扱いになる）のを防ぐ。
     */
    private static final Set<String> UTILITY_KEYS = utilityKeys();

    private static Set<String> utilityKeys() {
        Set<String> keys = new java.util.LinkedHashSet<>(FIXED_UTILITY_KEYS);
        keys.addAll(com.trinityforge.stats.SkillExpBonusKeys.all());
        // 破壊時バニラEXP増加(採取スキル別、2026-08-15)。スコープ無しの break_vanilla_exp_bonus と
        // 同じ UTILITY タブに置く(別タブへ散ると「採掘のだけ採集タブ」のような表示になる)。
        keys.addAll(com.trinityforge.stats.BreakVanillaExpBonusKeys.all());
        return Set.copyOf(keys);
    }

    private static final Set<String> ARS_KEYS = Set.of(
            "mana_bonus", "mana_regen", "hit_mana_recovery", "damage_mana_recovery", "thread_slots",
            "mana_cost_reduction_flat", "mana_cost_reduction_percent", "source_cost_reduction",
            // 2026-07-25 害悪グリフ強化(ars_magic.yml B-3): 特定グリフのダメージ倍率ボーナス。
            "glyph_damage_multiplier_bonus",
            // 2026-08-16: mana_max_base / mana_regen_base / mana_regen_interval_ticks はここからも外した。
            // ArsPaper の config.yml (mana.default-max / mana.default-regen-rate / mana.regen-interval-ticks)
            // へ移設して StatVocabulary から削除したため、残すと「語彙に無い取り残し」になる
            // (StatsCategoryCoverageTest.noCategoryKeyIsMissingFromVocabulary が落ちる)。
            // 2026-07-29(重複ステ間引き): mana_onhit_flat / mana_onattack_flat を廃止し、
            // hit_mana_recovery / damage_mana_recovery へ一本化した(同じ効果の2経路だった)。
            "mana_onhit_percent", "mana_onattack_percent",
            "mana_idle_seconds", "mana_idle_bonus_percent", "mana_idle_bonus_flat",
            // 2026-07-26 M-stats分類: Ars のティア/グリフ枠。魔導書まわりなので ARS。
            "ars_tier_bonus", "glyph_slot_bonus");

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * このカテゴリが受理するキー集合。{@link #ALL}/{@link #OTHER} は「他の全部」を意味する動的な
     * バケツなので空集合を返す。
     *
     * <p>package-private なのは {@link StatsCategoryCoverageTest} の逆方向ドリフト検知
     * （ここにあるのに {@code StatVocabulary} に無い＝削除済みキーの取り残し）のためだけの露出。
     * ランタイムの分類判定は {@link #includes(String)} を使うこと。
     */
    Set<String> keys() {
        return switch (this) {
            case ALL, OTHER -> Set.of();
            case ATTACK -> ATTACK_KEYS;
            case ARMOR -> ARMOR_KEYS;
            case CRAFT -> CRAFT_KEYS;
            case GATHERING -> GATHERING_KEYS;
            case UTILITY -> UTILITY_KEYS;
            case ARS -> ARS_KEYS;
        };
    }

    public static StatsCategory parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "attack" -> ATTACK;
            case "armor" -> ARMOR;
            case "craft" -> CRAFT;
            case "gathering" -> GATHERING;
            case "utility" -> UTILITY;
            case "ars" -> ARS;
            case "other" -> OTHER;
            case "all" -> ALL;
            default -> null;
        };
    }

    public boolean includes(String canonicalKey) {
        return switch (this) {
            case ALL -> true;
            case ATTACK -> ATTACK_KEYS.contains(canonicalKey);
            case ARMOR -> ARMOR_KEYS.contains(canonicalKey);
            case CRAFT -> CRAFT_KEYS.contains(canonicalKey);
            case GATHERING -> GATHERING_KEYS.contains(canonicalKey);
            case UTILITY -> UTILITY_KEYS.contains(canonicalKey);
            case ARS -> ARS_KEYS.contains(canonicalKey);
            case OTHER -> !ATTACK_KEYS.contains(canonicalKey) && !ARMOR_KEYS.contains(canonicalKey)
                    && !CRAFT_KEYS.contains(canonicalKey) && !GATHERING_KEYS.contains(canonicalKey)
                    && !UTILITY_KEYS.contains(canonicalKey) && !ARS_KEYS.contains(canonicalKey);
        };
    }
}
