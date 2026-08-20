package com.trinityforge.skilltree.effects;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Fixed Java-defined vocabulary for {@code feature:<id>} gate placements (2026-07-23 動的ID方式改修 §3.2):
 * the "機能解放" flags that have no existing recipe/ritual/glyph gate surface to key into. Unlike the former
 * {@code dedicated-effects.yml} catalog (removed 2026-07-23) this is not config-driven — the vocabulary is a fixed set the editor
 * and {@code SkillTreeConfig} both validate {@code feature:<id>} placements against, so a typo'd or removed
 * feature id is caught at load time instead of silently becoming a dead flag no consumer ever reads.
 */
public final class FeatureEffectRegistry {

    private static final Map<String, FeatureEffectDefinition> ENTRIES = buildEntries();

    private FeatureEffectRegistry() {
    }

    private static Map<String, FeatureEffectDefinition> buildEntries() {
        Map<String, FeatureEffectDefinition> map = new LinkedHashMap<>();
        add(map, "vein-mining", "鉱脈一括破壊", FeatureEffectParam.SCALE);
        add(map, "haste-active-mining", "採掘ハステアクティブ", FeatureEffectParam.SCALE);
        // 2026-08-18 (W-59): シャベル専用の独立アクティブスキル。digging.yml には元々
        // feature:haste-active-mining の配置が一度も無く(mining.yml A-1だけがこのゲートを置いていた)、
        // 「MINING/DIGGING両対応」を謳っていたHasteActiveSkillは実質シャベルから発動不能だった。専用ゲート
        // を新設し、digging.yml A-1 に配置する(mining側のCTバケツはActiveSkill#cooldownGroup()で
        // 共有するので、ツールを持ち替えても合計アップタイムは増えない)。
        add(map, "haste-active-digging", "掘削ハステアクティブ", FeatureEffectParam.SCALE);
        add(map, "spawner-silktouch-harvest", "スポナーST回収", FeatureEffectParam.NONE);
        // 2026-07-25 gather-rework-active-framework §6 Q1: small-tree-fell/large-tree-fell を統合。
        add(map, "tree-fell", "木一括伐採", FeatureEffectParam.SCALE);
        add(map, "auto-replant", "自動再植", FeatureEffectParam.NONE);
        add(map, "area-harvest", "範囲収穫", FeatureEffectParam.SCALE);
        add(map, "animal-damage-4x", "動物特効", FeatureEffectParam.NONE);
        add(map, "bee-no-aggro", "蜂非敵対", FeatureEffectParam.NONE);
        add(map, "junkfood-immunity", "ゴミ食免疫", FeatureEffectParam.NONE);
        // 2026-07-27 農業「ゴミ食」段階化: NONE(単純on/off) -> LEVEL(%)化。ノードのvalue(%)を
        // stats/food-gimmick.yml junkfood-inversion.junk-saturation-bonus /
        // non-junk-saturation-penalty の倍率として掛ける(100=基準量そのまま)。LEVELはrequiresValue()な
        // ので、valueを欠いた配置はSkillTreeConfigがparse時に破棄する(SCALEのdefaultsMissingValue()の
        // ような「無指定→tier1」の自動補完は無い) — 既存のfarming.yml A-alpha-1にはvalue:100を必須で
        // 明示することで後方互換を保つ(値なしでの100%フォールバックはしない。tree-fell等のSCALE化前例は
        // ここには適用できない — 2026-07-27に一度誤って適用しようとし、訂正済み)。
        add(map, "junkfood-inversion", "ゴミ食反転%", FeatureEffectParam.LEVEL);
        add(map, "satiety-buff", "満腹バフ", FeatureEffectParam.NONE);
        add(map, "junk-to-scrap", "釣りゴミ→スクラップ", FeatureEffectParam.NONE);
        // 2026-07-25 経済連携(vault対応)により再導入: 恒久no-op(#5 exploit fix)だった時期を終え、
        // 保持者が釣った魚を自動でVault通貨へ換金する(EconomyBridge経由、Vault不在時は無効化のみ)。
        add(map, "fish-sell-toggle", "釣った魚を自動売却", FeatureEffectParam.NONE);
        // 2026-07-26 tier-expand: NONE(単純on/off) -> SCALE化。既存の単一解放ノードに value: が
        // 無くてもSCALEのdefaultsMissingValue()によりtier1が自動補完されるため、この変更は無条件で
        // 後方互換(SkillTreeConfig参照)。
        add(map, "xp-bottle-store-unlock", "経験値瓶保存", FeatureEffectParam.SCALE);
        add(map, "dismantle-unlock", "装備解体", FeatureEffectParam.LEVEL);
        add(map, "potion-merge", "ポーション統合", FeatureEffectParam.SCALE);
        add(map, "wood-repair-unlock", "木材修繕", FeatureEffectParam.NONE);
        add(map, "weapon-coating-unlock", "武器コーティング解放", FeatureEffectParam.NONE);
        // 2026-07-28 (数値のギミックyml集約): coating-stack-increase は単純加算(全保持ノード分の合算)
        // でしかなく、feature である必然性が無かった。通常 stat `coating_charges_bonus`(StatVocabulary)へ
        // 移設し、ここから削除した。skilltree/alchemy.yml 側は dedicated-effects ではなく buffs: へ書く。
        // WeaponCoatingListener は PlayerStatAggregator#totalOf 経由でこの stat を読む。
        add(map, "source-auto-consume", "ソース自動消費", FeatureEffectParam.NONE);
        // 2026-08-18 (W-58): 単一の feature:break-vanilla-exp をスキルごとに分割した。旧来は
        // mining/digging/farming/woodcutting の4ツリーが全員この1本の gate id を共有しており、
        // NativeSkillExperienceListener が3引数(ツリー限定)の isActive を呼んでいても「そのツリーの
        // A ノードを取っていない別ツリー経由の解放」を誤って認識しうる余地が残っていた(2026-08-01/
        // 08-15の同種スコープ漏れ修正の延長)。BreakVanillaExpBonusKeys#featureId(skillId) が
        // id⇄featureId変換の唯一の窓口(NativeSkillExperienceListenerもここを経由する)。
        // 解放状態の永続化(PlayerData#heldPerks)はノードID(PerkNaming.perkId)基準でfeature:idの文字列
        // 自体は保持しないため、この改名にプレイヤーデータ移行は不要。
        add(map, "break-vanilla-exp-mining", "破壊時バニラEXP解放(採掘)", FeatureEffectParam.NONE);
        add(map, "break-vanilla-exp-digging", "破壊時バニラEXP解放(掘削)", FeatureEffectParam.NONE);
        add(map, "break-vanilla-exp-farming", "破壊時バニラEXP解放(農業)", FeatureEffectParam.NONE);
        add(map, "break-vanilla-exp-woodcutting", "破壊時バニラEXP解放(伐採)", FeatureEffectParam.NONE);
        // 2026-07-28 (数値のギミックyml集約): 精錬速度/精錬ボーナスは LEVEL(生%直書き)から SCALE(tier
        // 番号)へ変更した。数値の実体は stats/smithing-gimmick.yml の furnace-smelt.speed/bonus.tiers に
        // 移し、ノードは tier(1/2/3)だけを持つ。SCALEのdefaultsMissingValue()により value省略時はtier1が
        // 自動補完されるが、既存配置は全てvalue明示済みなので後方互換上の影響はない(旧value 10/20/30を
        // そのまま tier 1/2/3 に読み替えた — smithing.yml 側も同時に更新済み)。
        // 2026-08-19 (W-150): ラベルの「短縮」を外した。tier表の%は「時間の短縮率」ではなく
        // 「速度の増加率」(時間 = 基準 ÷ (1 + %/100))なので、旧ラベルは意味が逆に読めた。
        add(map, "furnace-smelt-speed", "精錬速度tier", FeatureEffectParam.SCALE);
        add(map, "furnace-smelt-bonus", "精錬ボーナスtier", FeatureEffectParam.SCALE);
        // 農業ツリーA-alpha-2: ゴミ食のみの満腹度回復ボーナス%。同じ1ノードが「非ゴミ食のfood_restore_bonus
        // を戻す(適用しない)」動作も兼ねる(FoodBonusListener#JUNK_FOOD_RESTORE_BOOST 参照)。
        add(map, "junk-food-restore-boost", "ゴミ食回復ボーナス%", FeatureEffectParam.LEVEL);
        // 2026-07-28 (数値のギミックyml集約): 切削C-1/C-2も精錬と同じ理由でLEVEL(生%直書き、しかも
        // その同じ%を裏でtier番号としても流用する二重定義だった旧実装)からSCALEへ変更。数値の実体は
        // stats/digging-gimmick.yml の durability-exp.vanilla-exp/job-exp.tiers[tier].cap-percent。
        // 旧value 50/25 → tier 1/2 相当が無く単一tier(1)化されたため、digging.yml側もvalue 50→1/25→1へ
        // 変更済み(cap-percentの実値はyml側のtier1行が保持する)。
        add(map, "digging-durability-vanilla-exp", "耐久累計→バニラEXP tier", FeatureEffectParam.SCALE);
        add(map, "digging-durability-job-exp", "耐久累計→職業EXP tier", FeatureEffectParam.SCALE);
        return Map.copyOf(map);
    }

    private static void add(Map<String, FeatureEffectDefinition> map, String id, String label,
                            FeatureEffectParam param) {
        map.put(id, new FeatureEffectDefinition(id, label, param));
    }

    /** The definition for bare feature {@code id} (no {@code feature:} prefix), or empty when unknown. */
    public static Optional<FeatureEffectDefinition> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(ENTRIES.get(id));
    }

    public static boolean isKnown(String id) {
        return id != null && ENTRIES.containsKey(id);
    }

    /** Every known feature entry, keyed by bare id. Immutable. */
    public static Map<String, FeatureEffectDefinition> all() {
        return ENTRIES;
    }
}
