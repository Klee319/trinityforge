package com.trinityforge.stats;

import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for which perk-buff "channel" a canonical stat key routes into
 * (2026-07-23 stat-gate-overhaul §2: stat語彙 全面移行). Replaces the four hand-maintained
 * {@code static Set}s that used to live in {@code PerkBuffResolver} (ATTACK_KEYS / DEFENSE_KEYS /
 * ATTRIBUTE_KEYS / GENERAL_KEYS) — {@link PerkBuffResolver} now only asks this class
 * {@link #channelOf(String)} instead of maintaining its own allow-lists.
 *
 * <p>A key absent from this vocabulary has no channel ({@link Channel#NONE}) and is dropped by the
 * perk-buff pipeline — the same "disallowed key" defence-in-depth behaviour the old allow-lists gave.
 *
 * <p><b>実際の参照元</b>(2026-07-23 敵対的レビューで実態に合わせて訂正): perk-buff の許可判定は
 * {@link PerkBuffResolver} と {@code com.trinityforge.config.domains.SkillTreeConfig}
 * （{@code buffs}/{@code multipliers} のパース）、および両者に追随するユニットテストがここを参照する。
 * ただし {@code com.trinityforge.command.StatsCategory}（{@code /tf stats} のカテゴリ絞り込み）と
 * {@link PercentStatNormalize}（% 系キーの正規化リスト）はこのクラスとは<b>独立したキー集合</b>を
 * 手動管理しており、ここを更新しても自動追随しない — キー追加時はドリフトに注意し、両クラスも合わせて更新すること。
 */
public final class StatVocabulary {

    /** Which perk-buff aggregate a stat key folds into. */
    public enum Channel {
        /** {@link PerkBuffs#attack()} — combat pipeline attacker-side addend. */
        ATTACK,
        /** {@link PerkBuffs#defense()} — combat pipeline defender-side addend. */
        DEFENSE,
        /** {@link PerkBuffs#attributes()} — applied as a vanilla {@code Attribute} modifier. */
        ATTRIBUTE,
        /** {@link PerkBuffs#general()} — non-combat total-stat addend (gathering/craft/ars/utility/…). */
        GENERAL,
        /** Not a recognised perk-buff key; dropped. */
        NONE
    }

    /** Attacker-side canonical buff keys (design section B / stat-gate-overhaul §2.1 弓系・近接系等). */
    private static final Set<String> ATTACK_KEYS = Set.of(
            "attack_power", "flat_bonus_damage", "percent_bonus_damage", "crit_chance", "crit_damage",
            "penetration", "damage_modifier", "fixed_damage", "bleed_chance", "bleed_damage",
            // 弓系 (NativeCombatPerkListener → totalOf(shooter, bow))
            // 2026-07-31: bow_cooldown_reduction を撤去。アイテムCT短縮(cooldown_reduction)と同じ
            // Player#setCooldown を二重に掛ける設計で、しかも item-stats.yml の BOW/CROSSBOW に
            // item-cooldown が無いため残CTが常に0 = 完全な no-op だった。弓のCTはアイテムCT短縮へ一本化する。
            "bow_accuracy", "ammo_save_chance", "distance_damage_bonus", "arrow_piercing",
            "arrow_velocity", "arrow_knockback",
            // 近接系 (NativeCombatPerkListener → totalOf(attacker, weapon))
            "melee_knockback", "stun_chance", "power_attack_damage", "power_attack_radius",
            // スタン時間のtick加算値。base-statsの初期tick、装備、パークを合算し、
            // NativeCombatPerkListener.onMeleeで上限クランプして消費する。
            "stun_duration_bonus",
            // 2026-07-26 stat-scope 境界引き直し §3 (B→C 昇格): 攻撃範囲(AoE)3キー。従来は
            // CombatListener が agg.item() のみを直接読んでおり、スキルツリーパーク由来の分が
            // PerkBuffResolver の channel NONE ガードで無言ドロップされていた(B層のバグ)。
            "aoe_radius", "aoe_max_targets", "aoe_damage_rate",
            // 生存・汎用系 (CombatListener.startItemCooldown でアイテムCT短縮として消費 — 2026-07-25
            // CT短縮ステータス分離 §1-A: 表示名は「アイテムCT短縮」に訂正。アクティブスキルのCTは短縮しない)。
            "cooldown_reduction",
            // 2026-07-25 CT設計一本化 §2: 旧グローバル skill_cooldown_reduction(あらゆるアクティブスキルに
            // 波及する単一キー)を、ActiveSkill単位のキーへ分割した第一号。ActiveSkillCooldownKeys.forSkill
            // ("<id>-cooldown-reduction")が導出するキーと一致させること — 新しいActiveSkillを追加するたびに
            // 対応するキーをここへ追加しないと、ActiveSkillCooldownKeys.verifyRegistered(起動時に呼ばれる)が
            // IllegalStateExceptionで落ちる(意図的な「静かに壊れるより騒がしく落ちる」ガード)。
            // haste-active-mining (ActivationDispatcher/ActiveCommand → CooldownManager.applyReduction で消費)。
            // cooldown_reduction(アイテムCT)とは完全に独立で、互いのCTには一切影響しない。
            "haste_active_mining_cooldown_reduction",
            // 2026-07-25 PRG-07/伐採一括伐採CT短縮: tree-fellはActiveSkillRegistryに載らない
            // (sneak+クリック発動ではなくパッシブなブロック破壊ギミックのため)が、CT短縮キーの命名規約は
            // ActiveSkillCooldownKeys.forSkill("tree-fell")と揃える(TreeFellingListenerが
            // CooldownManager.applyReductionで消費)。旧cooldown-reduction(アイテムCT)の誤配線を置換。
            "tree_fell_cooldown_reduction");

    /** Defender-side canonical buff keys. */
    private static final Set<String> DEFENSE_KEYS = Set.of(
            "phys_resistance", "magic_resistance", "flat_defense", "phys_flat_defense",
            "magic_flat_defense", "damage_reduction",
            "armor_defense_rate", "dodge_chance",
            // 2026-08-15 単位2重問題の解消: armor_defense_rate は
            // 「アイテム側=バニラ防具値(整数の点数)」「パーク側=[0,1]の軽減率」という
            // 互換性の無い2つの単位を1キーで運んでいた(ロア表示も FLAT のまま同じ行に出る)。
            // 割合のほうを defense_rate へ独立させ、armor_defense_rate はアイテム専用の
            // 「防具値」に戻す。ComponentDamageCalculator の防御率(貫通で相殺される唯一の乗算軽減)は
            // このキーとバニラ防具値ミラーの合算で決まる。
            "defense_rate",
            // 生存系: 自然回復量ボーナス
            "health_regen_bonus",
            // 2026-07-25 課題2: 棘の鎧ステータス化(反射率)。実=固定値、割=被ダメージ割合。
            "reflect_flat", "reflect_percent",
            // 2026-07-26 stat-scope 境界引き直し §3 (B→C 昇格): 防具強度(会心軽減率%)。消費者
            // (PlayerDefenseResolver/DefenseStatBridge)は既に item/perk/addon を combine 済みだったが、
            // PerkBuffResolver が channel NONE として全パーク由来分を無言ドロップしていた。
            "armor_strength");

    /**
     * Vanilla Attribute buffs (applied by {@code PerkAttributeApplier}, not the combat pipeline).
     *
     * <p>2026-07-26 stat-scope 境界引き直し §2 (C→A 降格): {@code attack_speed}(絶対値・メインハンド専用)
     * はここから除外した — 総合ステとしての消費者が存在せず(挙動としても常にメインハンド1点しか読まない)、
     * {@link com.trinityforge.skilltree.runtime.PerkAttributeApplier} は {@code DerivedItemStats.resolve}
     * でメインハンド武器を直接解決する経路へ切替済み。{@code attack_speed_bonus}(割合・全ソース横断)は
     * 引き続き総合ステとして必要なため残す — 巻き込み削除しないこと。
     */
    private static final Set<String> ATTRIBUTE_KEYS = Set.of(
            "move_speed", "attack_speed_bonus", "attack_reach",
            "knockback_resistance", "max_health");

    /**
     * Non-combat total-stat buffs consumed by gathering/craft/ars/utility and similar aggregate paths.
     * item専用キー (item_cooldown / durability / tool_enchant_* 等) は対象外 — perkの buffs: からは
     * 付与できないアイテム固有の解決経路を持つため、このボキャブラリには含めない。
     */
    private static final Set<String> GENERAL_KEYS = Set.of(
            "mining_fortune", "fishing_luck", "fishing_bonus",
            // 2026-07-25 採掘効率エンチャント連動方式(統合版/Geyser対応、属性ベースを取り下げ再設計):
            // 農業/採掘/伐採/切削のメインハンド道具にだけ「効率強化」エンチャントのレベルとして実行時反映
            // される総合ステ(GatheringEfficiencyEnchantApplier消費)。装備/防具/装飾品からも通常通り合算
            // される(メインハンド限定なのは適用先のみ)。
            "gathering_efficiency",
            // 経済連携(2026-07-25、Vault対応): 釣りの売却額倍率(fish-sell-toggle消費) /
            // 解体の戻り係数(DisassemblyListener消費、既存グローバル設定への追加乗算項) /
            // 海釣り判定(釣り位置が海洋系バイオームのときだけ fishing_bonus の期待値へ加算される追加分)。
            "fish_sell_price_bonus", "disassembly_return_bonus", "ocean_fishing_bonus",
            // 生存・汎用系
            "hunger_save_chance", "mob_drop_bonus", "skill_exp_bonus", "loot_luck", "mob_drop_quality",
            // スキル別EXP倍率は buildIndex() で SkillExpBonusKeys から機械的に登録する
            // (2026-08-02 柱5-3 では伐採/農業/切削の3件だけをここへ手書きしていたため、
            //  残り12スキル分が丸ごと欠けていた。2026-08-05 に SkillId.ALL 由来へ切替)。
            "gacha_rate_bonus", "suspicious_respawn_chance", "hive_harvest_fortune",
            "food_save_chance",
            // クラフト系 (CraftQualityService → クラフターの totalOf())
            // workbench_quality_bonus=作業台品質, ritual_quality_bonus=儀式品質。
            "workbench_quality_bonus", "ritual_quality_bonus",
            // 2026-07-31: 旧 craft_upswing_bonus / craft_downswing_reduction を作業台用と儀式用の
            // 2組へ分割した。旧キーは両経路の同じ計算を読んでいたため、鍛冶ツリーのパークが儀式クラフトに、
            // 魔法鍛冶ツリーのパークが作業台クラフトに漏れていた。命名は quality_bonus 側に合わせる。
            "workbench_upswing_bonus", "ritual_upswing_bonus",
            "workbench_downswing_reduction", "ritual_downswing_reduction",
            // ロール3キーは分割しない: craft_roll_* は PdcKeys でアイテム PDC に焼かれており、
            // キー名を変えると流通済みアイテムのロール補正が読めなくなる(ステータスが変わる)。
            "craft_roll_up_bonus", "craft_roll_down_reduction", "craft_roll_inset",
            // バニラEXP倍率系 (kill/break/breeding/常時)。乗算適用は各リスナー(EntityDeath/BlockBreak/
            // EntityBreed)で 1 + always + 個別 として消費。break は前提 feature:break-vanilla-exp が必要。
            // 2026-08-15: break_vanilla_exp_bonus は「採取全般」のスコープ無しキーとして残すが、
            // 出荷スキルツリーは採取スキル別の <skill>_break_vanilla_exp_bonus
            // (buildIndex() で BreakVanillaExpBonusKeys から機械的に登録) を使う。
            // 以前は6ノード全部がこの共通キーへ配っていたため、採掘で取った +50% が
            // 伐採・整地・農業の破壊EXPにも乗っていた(解放ゲートだけ職業別で倍率が漏れていた)。
            "vanilla_exp_bonus", "kill_vanilla_exp_bonus", "break_vanilla_exp_bonus",
            "breeding_vanilla_exp_bonus",
            // 追加ドロップ確率系 (伐採/収穫)。対応リスナーが確率でドロップを増やす。
            "woodcutting_extra_drop_chance", "harvest_extra_drop_chance",
            // 満腹度系 (FoodGimmick/回復)。food=見える満腹度回復量, hidden=隠し満腹度(saturation)回復量。
            "food_restore_bonus", "hidden_saturation_bonus",
            // 繁殖/成長系。extra_child=繁殖時に追加で子が増える確率, 成長速度は自身が増やした動物/植えた作物。
            "breeding_extra_child_chance", "bred_animal_growth_bonus", "planted_crop_growth_bonus",
            // Ars系 (perk buffs general → ArsNativeBridge。装備分との二重計上はbridge側で回避)
            "mana_bonus", "mana_regen", "ars_tier_bonus", "glyph_slot_bonus",
            // 2026-07-26 stat-scope 境界引き直し §3 (B→C 昇格): マナ回復/コスト削減4キー。TF側の直接消費者は
            // 無く(fork側 ArsPaper が TrinityForgeBridge.tfStatTotal/resolveCatalystManaReduction 経由で読む)、
            // これまで PerkBuffResolver が channel NONE として全パーク由来分を無言ドロップしていた。
            // mana_cost_reduction_flat/percent の fork 側 触媒(catalyst)専用読み取り(resolveCatalystManaReduction)
            // は意図的にアイテム単体のみを見る設計(coating-chargesと同様の per-item 経路)で、今回は変更しない
            // — 本登録は「パーク/base-statsから供給できる」ことの実現が目的で、fork既存の item専用経路とは別物。
            "hit_mana_recovery", "damage_mana_recovery",
            "mana_cost_reduction_flat", "mana_cost_reduction_percent",
            // 2026-07-25 (config editor T2): combat/base-stats.yml 専用のマナ初期値キー
            // (ArsPaper config.yml の mana.default-max 等の移設先)。stats/lore.yml へは意図的に
            // 未登録(config editor の labels.js 全stat必須説明テストと衝突するため)。フォークは
            // TrinityForgeBridge.manaBaseStat 経由で BaseStatsConfig.stats() を直接読む。
            "mana_max_base", "mana_regen_base", "mana_regen_interval_ticks",
            // 2026-07-29(重複ステ間引き): mana_onhit_flat / mana_onattack_flat を廃止。
            // 「被弾/与ダメ時に固定量回復」は hit_mana_recovery / damage_mana_recovery と完全に同じで、
            // fork 側の ArmorManaListener と ManaRecoveryListener が同じイベントで別々に加算していた。
            // 残す %系は「最大マナの何%」で意味が違うので統合対象外。
            "mana_onhit_percent", "mana_onattack_percent",
            "mana_idle_seconds", "mana_idle_bonus_percent", "mana_idle_bonus_flat",
            // スキルツリー由来の条件付き装備効果。NativeAttributeBridge が装備部位数を判定して消費する。
            // 2026-07-27: charged_shot_unlocked をここから削除。「解放フラグ」を名乗りながら、
            // 解放対象の効果(貫通/初速/矢ノックバック)がいずれも自分自身のステの非0判定を
            // 個別に持っており、フラグのOR条件にも同じステが並ぶ同語反復だった(挙動ゼロ)。
            // 2026-07-31: light_armor_move_speed_per_piece / heavy_armor_move_speed_per_piece も撤去。
            // 「装備部位数×係数」の専用経路(NativeAttributeBridge)を持つだけのキーで、
            // 同じ効果は set-buffs スキーマ(段3/4条件バフ)の move-speed で表現できるため統合した。
            // 2026-07-27 (armor-set-buffs 全面移行): 旧4キー(light/heavy-armor-set-bonus-multiplier,
            // light-armor-set-dodge-chance, heavy-armor-set-knockback-resistance)を廃止し、
            // set-buffs スキーマ(段3/4条件バフ)+ このキー1本(軽装/重装共通の増幅率)へ統一。
            // NativeAttributeBridge が PerkBuffResolver#setBuffsFor の結果へ × (1 + max(0, この値)) を掛ける。
            "armor_set_bonus",
            // fork consumer系 (fork は TF static API statTotal(player, key) 経由で読む)
            // ※ lapis_cost_reduction は 2026-08-14 に廃止(ユーザー判断「ラピス効率は使わない」)。
            //   消費側は ArsPaper フォークの com.arspaper.enchant.LapisCostReductionListener で、
            //   TF の語彙を消すだけでは tfStatTotal が常に0を返す no-op として残り続けるため、
            //   フォークのリスナーと ArsPaper.java の registerEvents、TrinityForgeBridge の
            //   STAT_LAPIS_COST_REDUCTION も同時に削除した(フォークの再ビルドと jar 差し替えが要る)。
            "source_cost_reduction", "material_refund_chance",
            "ingredient_save_chance",
            // エンチャント/ポーション品質 (2026-07-25、かまど・エンチャント・ポーションは実行者限定ステ):
            // enchant_luck=エンチャントテーブルの良エンチャント出現率格上げ用ポイント(EnchantLuckListener消費)。
            // potion_quality_bonus=醸造ポーションの効果時間/強度換算に使うポイント(PotionQualityListener消費)。
            // brew_speed_bonus=醸造時間短縮率(PotionQualityListener/BrewSpeedListener消費)。
            // ※ enchant_exp_gain_bonus は 2026-08-14 に廃止。ENCHANTING の EXP 付与点が
            //   onEnchant の1箇所しかなく、職業EXP増加の enchanting_exp_bonus と同じ量に
            //   別経路で掛かる重複だった(StatKeys のエイリアスで読み替える)。
            "enchant_luck", "potion_quality_bonus", "brew_speed_bonus",
            // 2026-07-26 新設: エンチャント費用軽減(EnchantCostReductionListener消費)。
            // エンチャントテーブルのレベルコストと金床の修理コストの両方を割合で軽減する。
            "enchant_cost_reduction",
            // 2026-07-28 (数値のギミックyml集約): feature:coating-stack-increase から降格。パーク由来の
            // 武器コーティング上限追加回数(全保持ノード分を単純合算するだけで feature である必然性が
            // 無かった)。WeaponCoatingListener が PlayerStatAggregator#totalOf 経由で読む。
            "coating_charges_bonus",
            // 2026-07-25 害悪グリフ強化(ars_magic.yml B-3): 特定グリフのダメージ倍率ボーナス(fraction、
            // 例0.3=+30%)。harmに決め打ちしない汎用stat — フォーク側がどのグリフに適用するかを選ぶ
            // (TF static API statTotal(caster, "glyph_damage_multiplier_bonus") 経由で読む想定)。
            "glyph_damage_multiplier_bonus");

    /** Every key registered in this vocabulary, keyed by canonical form, mapped to its channel. */
    private static final Map<String, Channel> BY_KEY = buildIndex();

    private StatVocabulary() {
    }

    private static Map<String, Channel> buildIndex() {
        Map<String, Channel> index = new java.util.LinkedHashMap<>();
        ATTACK_KEYS.forEach(key -> index.put(key, Channel.ATTACK));
        DEFENSE_KEYS.forEach(key -> index.put(key, Channel.DEFENSE));
        ATTRIBUTE_KEYS.forEach(key -> index.put(key, Channel.ATTRIBUTE));
        GENERAL_KEYS.forEach(key -> index.put(key, Channel.GENERAL));
        // 職業EXP増加(スキル別)。実行時の消費側がスキルIDから機械的にキーを組むので、
        // 語彙側も SkillId.ALL から導出する(手書きだと新スキル追加時に無言で欠ける)。
        SkillExpBonusKeys.all().forEach(key -> index.put(key, Channel.GENERAL));
        // 破壊時バニラEXP増加(採取スキル別)。こちらも消費側が破壊の採取スキルから機械的に組む。
        BreakVanillaExpBonusKeys.all().forEach(key -> index.put(key, Channel.GENERAL));
        return Map.copyOf(index);
    }

    /** The channel a (kebab- or snake-case) stat key routes into; {@link Channel#NONE} if unregistered. */
    public static Channel channelOf(String statKey) {
        return BY_KEY.getOrDefault(StatKeys.canonical(statKey), Channel.NONE);
    }

    public static boolean isAttack(String statKey) {
        return channelOf(statKey) == Channel.ATTACK;
    }

    public static boolean isDefense(String statKey) {
        return channelOf(statKey) == Channel.DEFENSE;
    }

    public static boolean isAttribute(String statKey) {
        return channelOf(statKey) == Channel.ATTRIBUTE;
    }

    public static boolean isGeneral(String statKey) {
        return channelOf(statKey) == Channel.GENERAL;
    }

    /** {@code true} when the key is registered in any channel (i.e. not silently dropped). */
    public static boolean isKnown(String statKey) {
        return channelOf(statKey) != Channel.NONE;
    }

    /** All canonical keys registered in the vocabulary, across every channel. */
    public static Set<String> allKeys() {
        return BY_KEY.keySet();
    }

    /**
     * combat/base-stats.yml 専用の「全プレイヤー共通の定数」キー。装備・パークからは供給されず、
     * アイテムのロアに一行も出ない — したがって {@code stats/lore.yml} に表示定義を持たない。
     *
     * <p>2026-08-13: この集合を明示した。以前は「lore.yml へは意図的に未登録」と
     * コメントに書いてあるだけで機械可読な印が無く、実際には 3 キーとも lore.yml へ紛れ込んで
     * いた（{@code LoreVocabularyCoverageTest} が「語彙にあるのに lore.yml に無い」を全キーに
     * 課すため、そこを通すためだけに足されたもの）。結果、config editor の「ロア表示」画面に
     * 「マナ上限(基礎値)」が並び、アイテムに設定できる「マナ上限」({@code mana_bonus})との
     * 区別が付かなくなっていた（2026-08-13 ユーザー報告）。
     *
     * <p>値の編集は config editor の「共通変数」画面（combat/base-stats.yml）で行う。
     * フォーク側は {@code TrinityForgeBridge.manaBaseStat} → {@code BaseStatsConfig#statOrDefault}
     * 経由で直接読む。
     */
    public static final Set<String> BASE_STATS_ONLY_KEYS = Set.of(
            "mana_max_base", "mana_regen_base", "mana_regen_interval_ticks");
}
