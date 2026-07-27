package com.trinityforge.skilltree.effects;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Fixed Java-defined vocabulary for {@code feature:<id>} gate placements (2026-07-23 動的ID方式改修 §3.2):
 * the "機能解放" flags that have no existing recipe/ritual/glyph gate surface to key into. Unlike the old
 * {@code dedicated-effects.yml} catalog this is not config-driven — the vocabulary is a fixed set the editor
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
        add(map, "spawner-silktouch-harvest", "スポナーST回収", FeatureEffectParam.NONE);
        // 2026-07-25 gather-rework-active-framework §6 Q1: small-tree-fell/large-tree-fell を統合。
        add(map, "tree-fell", "木一括伐採", FeatureEffectParam.SCALE);
        add(map, "auto-replant", "自動再植", FeatureEffectParam.NONE);
        add(map, "area-harvest", "範囲収穫", FeatureEffectParam.SCALE);
        add(map, "animal-damage-4x", "動物特効", FeatureEffectParam.NONE);
        add(map, "bee-no-aggro", "蜂非敵対", FeatureEffectParam.NONE);
        add(map, "junkfood-immunity", "ゴミ食免疫", FeatureEffectParam.NONE);
        add(map, "junkfood-inversion", "ゴミ食反転", FeatureEffectParam.NONE);
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
        // 2026-07-26 (stat-scope 境界引き直し): coating-charges を総合ステ(StatVocabulary)から
        // アイテム固有ステへ降格したことに伴い、skilltree/alchemy.yml のパーク由来コーティング回数追加は
        // ここ(LEVEL: ノードのvalueがそのまま加算量)経由へ移設。WeaponCoatingListener が
        // dedicatedEffects.valueSum(player, "coating-stack-increase") で全保持ノード分を合算する。
        add(map, "coating-stack-increase", "武器コーティング上限追加", FeatureEffectParam.LEVEL);
        add(map, "source-auto-consume", "ソース自動消費", FeatureEffectParam.NONE);
        add(map, "break-vanilla-exp", "破壊時バニラEXP解放", FeatureEffectParam.NONE);
        // 2026-07-25 かまど/ゴミ食/シャベル耐久EXP 5件追加: いずれもノード側 value がそのまま domain 値
        // (%)として使われる LEVEL param(tree-fell等の SCALE tierテーブルとは違い、間接テーブルを挟まない)。
        // 精錬速度/精錬ボーナスは smithing.yml A-1〜3 / B-1〜3 が prerequisite 連結(A-1→A-2→A-3)のため、
        // DedicatedEffectsConfig#valueMax がプレイヤーの保持ノード中の最大 value を自動的に採用する
        // (A-3保持者はA-1/A-2のperkも保持しているため、tierテーブルなしでそのまま最大%が引ける)。
        add(map, "furnace-smelt-speed", "精錬速度短縮%", FeatureEffectParam.LEVEL);
        add(map, "furnace-smelt-bonus", "精錬ボーナス%", FeatureEffectParam.LEVEL);
        // 農業ツリーA-alpha-2: ゴミ食のみの満腹度回復ボーナス%。同じ1ノードが「非ゴミ食のfood_restore_bonus
        // を戻す(適用しない)」動作も兼ねる(FoodBonusListener#JUNK_FOOD_RESTORE_BOOST 参照)。
        add(map, "junk-food-restore-boost", "ゴミ食回復ボーナス%", FeatureEffectParam.LEVEL);
        // 切削C-1/C-2: 消費シャベル耐久累計に応じたバニラ/職業EXPボーナスの上限%(それぞれ独立)。
        add(map, "digging-durability-vanilla-exp", "耐久累計→バニラEXP上限%", FeatureEffectParam.LEVEL);
        add(map, "digging-durability-job-exp", "耐久累計→職業EXP上限%", FeatureEffectParam.LEVEL);
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
