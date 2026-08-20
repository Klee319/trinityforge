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
                // 2026-08-18 (W-58): 単一の"break-vanilla-exp"を4ツリー専用idへ分割
                // (BreakVanillaExpBonusKeys#featureId参照)。S9(2026-07-24)由来の前提機能。
                "break-vanilla-exp-mining", "break-vanilla-exp-digging",
                "break-vanilla-exp-farming", "break-vanilla-exp-woodcutting",
                // 2026-08-18 (W-59): シャベル専用の独立ハステアクティブ(digging.yml A-1)。
                "haste-active-digging",
                // 2026-07-25: 経済連携(Vault対応)により復活。docs/archive/2026-07-23-stat-gate-overhaul.md
                // §3.2 の表を合わせて更新済み(以前は #5 exploit fix でno-op化され語彙から除外されていた)。
                "fish-sell-toggle",
                // 2026-07-28(数値のギミックyml集約): furnace-smelt-*/digging-durability-* は
                // LEVEL(生%直書き)からSCALE(tier番号)へ変更。数値の実体は各ギミックyml側のtierテーブル。
                "furnace-smelt-speed",
                "furnace-smelt-bonus",
                "junk-food-restore-boost",
                "digging-durability-vanilla-exp",
                "digging-durability-job-exp"
                // coating-stack-increase は 2026-07-28 に通常stat coating_charges_bonus へ降格し、
                // このボキャブラリから削除された(skilltree/alchemy.yml は buffs: 経由へ移設)。
                }) {
            assertTrue(FeatureEffectRegistry.isKnown(id), "missing feature vocab entry: " + id);
        }
        assertFalse(FeatureEffectRegistry.isKnown("break-vanilla-exp"),
                "旧・4ツリー共通の break-vanilla-exp は W-58 で分割済みのため語彙から消えているはず");
        // 2026-08-18 (W-58/W-59): 25(旧) - 1(break-vanilla-exp削除) + 4(スキル別break-vanilla-exp)
        // + 1(haste-active-digging新設) = 29。
        assertEquals(29, FeatureEffectRegistry.all().size());
    }
}
