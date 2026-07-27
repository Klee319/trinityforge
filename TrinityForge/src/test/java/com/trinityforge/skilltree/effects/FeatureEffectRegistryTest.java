package com.trinityforge.skilltree.effects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FeatureEffectRegistry}: the fixed Java-defined vocabulary {@code feature:<id>} placements are
 * validated against (2026-07-23 動的ID方式改修 §3.2), replacing the old config-driven catalog.
 */
class FeatureEffectRegistryTest {

    @Test
    void knownFeatureIdsResolveWithExpectedParam() {
        assertTrue(FeatureEffectRegistry.isKnown("vein-mining"));
        assertEquals(FeatureEffectParam.SCALE, FeatureEffectRegistry.get("vein-mining").orElseThrow().param());
        assertTrue(FeatureEffectRegistry.get("vein-mining").orElseThrow().param().defaultsMissingValue());

        assertTrue(FeatureEffectRegistry.isKnown("dismantle-unlock"));
        assertEquals(FeatureEffectParam.LEVEL, FeatureEffectRegistry.get("dismantle-unlock").orElseThrow().param());
        assertTrue(FeatureEffectRegistry.get("dismantle-unlock").orElseThrow().param().requiresValue());
    }

    @Test
    void gatherRework20260725ScaleFeaturesResolveWithScaleParam() {
        for (String id : new String[] {"vein-mining", "tree-fell", "area-harvest", "haste-active-mining"}) {
            assertEquals(FeatureEffectParam.SCALE, FeatureEffectRegistry.get(id).orElseThrow().param(),
                    "expected SCALE param for " + id);
        }
        // small-tree-fell/large-tree-fell were consolidated into tree-fell (design doc §6 Q1).
        assertFalse(FeatureEffectRegistry.isKnown("small-tree-fell"));
        assertFalse(FeatureEffectRegistry.isKnown("large-tree-fell"));
    }

    @Test
    void unknownFeatureIdIsAbsent() {
        assertFalse(FeatureEffectRegistry.isKnown("not-a-real-feature"));
        assertTrue(FeatureEffectRegistry.get("not-a-real-feature").isEmpty());
        assertFalse(FeatureEffectRegistry.isKnown(null));
    }

    @Test
    void everyDesignDocEntryIsPresent() {
        for (String id : new String[] {
                // 2026-07-25 gather-rework-active-framework §6 Q1: small-tree-fell/large-tree-fell を
                // "tree-fell" へ統合。
                "vein-mining", "haste-active-mining", "spawner-silktouch-harvest", "tree-fell",
                "auto-replant", "area-harvest", "animal-damage-4x", "bee-no-aggro",
                "junkfood-immunity", "junkfood-inversion", "satiety-buff", "junk-to-scrap",
                "xp-bottle-store-unlock", "dismantle-unlock", "potion-merge", "wood-repair-unlock",
                "weapon-coating-unlock", "source-auto-consume",
                // S9(2026-07-24): 破壊時バニラEXP解放の前提機能。
                "break-vanilla-exp",
                // 2026-07-25: 経済連携(Vault対応)により復活。docs/design/2026-07-23-stat-gate-overhaul.md
                // §3.2 の表を合わせて更新済み(以前は #5 exploit fix でno-op化され語彙から除外されていた)。
                "fish-sell-toggle",
                // 2026-07-25 スキルツリー機構ゼロノード解消: 鍛冶A-1〜A-3/B-1〜B-3(精錬)、農業A-alpha-2
                // (ゴミ食)、切削C-1/C-2(耐久累計→EXP)。いずれも param=level で、複数ノードへ段階配置し
                // valueMax が最大値を採る方式のため tierテーブルを持たない。§3.2 の表も更新済み。
                "furnace-smelt-speed",
                "furnace-smelt-bonus",
                "junk-food-restore-boost",
                "digging-durability-vanilla-exp",
                "digging-durability-job-exp",
                // 2026-07-26 stat-scope 境界引き直し §1 (C→A 降格): coating-charges の生きた経路。
                // skilltree/alchemy.yml のC/E/B-beta-2ノードがここへ移設された(値は不変)。
                "coating-stack-increase"}) {
            assertTrue(FeatureEffectRegistry.isKnown(id), "missing feature vocab entry: " + id);
        }
        assertEquals(26, FeatureEffectRegistry.all().size());
    }
}
