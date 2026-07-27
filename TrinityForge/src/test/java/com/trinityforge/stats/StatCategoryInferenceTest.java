package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatCategoryInferenceTest {

    @Test
    void itemCooldownIsOther() {
        // 2026-07 仕様変更: アイテムCT(表示名: CT)は「その他」カテゴリへ移動。
        // 2026-07-26 キー統合(ユーザー決定): weapon-cooldown → item-cooldown へリネーム。
        assertEquals(StatCategory.OTHER, StatCategoryInference.infer("item-cooldown"));
    }

    /**
     * 注記(このタスクの範囲外・発見のみ): tool-enchant-efficiency は 2026-07-26 の別セッション
     * (効率ステータス統合)で {@code StatKeys.canonical} のレガシーエイリアスにより
     * gathering_efficiency へ読み替えられるようになった(このタスクの変更ではない)。canonical化後の
     * キーは "gathering" を含むため GATHERING カテゴリへ倒れる。「tool-enchant-*接頭辞は原則その他」
     * という趣旨は tool-enchant-fortune(未エイリアス)で確認する。
     */
    @Test
    void toolEnchantEfficiencyIsGatheringViaLegacyAlias() {
        assertEquals(StatCategory.GATHERING, StatCategoryInference.infer("tool-enchant-efficiency"));
    }

    @Test
    void toolEnchantIsOther() {
        // 2026-07 仕様変更: 効率強化増幅(tool-enchant-*)は「その他」カテゴリへ移動。
        assertEquals(StatCategory.OTHER, StatCategoryInference.infer("tool-enchant-fortune"));
    }

    @Test
    void fixedDamageIsAttack() {
        assertEquals(StatCategory.ATTACK, StatCategoryInference.infer("fixed-damage"));
    }

    @Test
    void damageReductionIsDefenseNotAttack() {
        assertEquals(StatCategory.DEFENSE, StatCategoryInference.infer("damage-reduction"));
    }

    @Test
    void physFlatDefenseIsDefense() {
        assertEquals(StatCategory.DEFENSE, StatCategoryInference.infer("phys-flat-defense"));
    }

    @Test
    void maxHealthIsDefense() {
        assertEquals(StatCategory.DEFENSE, StatCategoryInference.infer("max-health"));
    }

    @Test
    void durabilityIsOther() {
        // 2026-07-23 カテゴリ再編: SUPPORT廃止に伴い durability は「その他」へ(lore.ymlの実エントリと一致)。
        assertEquals(StatCategory.OTHER, StatCategoryInference.infer("durability"));
    }

    @Test
    void miningFortuneIsGathering() {
        assertEquals(StatCategory.GATHERING, StatCategoryInference.infer("mining-fortune"));
    }

    @Test
    void moveSpeedIsUtility() {
        assertEquals(StatCategory.UTILITY, StatCategoryInference.infer("move-speed"));
    }

    @Test
    void manaBonusIsArs() {
        assertEquals(StatCategory.ARS, StatCategoryInference.infer("mana-bonus"));
    }

    // --- 2026-07-23 敵対的レビュー指摘: 誤分類の明示的例外検証 -----------------------------------

    @Test
    void arrowKnockbackIsAttackNotDefense() {
        assertEquals(StatCategory.ATTACK, StatCategoryInference.infer("arrow-knockback"));
    }

    @Test
    void meleeKnockbackIsAttackNotDefense() {
        assertEquals(StatCategory.ATTACK, StatCategoryInference.infer("melee-knockback"));
    }

    @Test
    void bowAccuracyIsAttack() {
        assertEquals(StatCategory.ATTACK, StatCategoryInference.infer("bow-accuracy"));
    }

    @Test
    void ammoSaveChanceIsAttack() {
        assertEquals(StatCategory.ATTACK, StatCategoryInference.infer("ammo-save-chance"));
    }

    @Test
    void stunChanceIsAttack() {
        assertEquals(StatCategory.ATTACK, StatCategoryInference.infer("stun-chance"));
    }

    @Test
    void cooldownReductionIsAttack() {
        assertEquals(StatCategory.ATTACK, StatCategoryInference.infer("cooldown-reduction"));
    }

    @Test
    void hasteActiveMiningCooldownReductionIsAttack() {
        assertEquals(StatCategory.ATTACK, StatCategoryInference.infer("haste-active-mining-cooldown-reduction"));
    }

    @Test
    void bowCooldownReductionIsAttack() {
        assertEquals(StatCategory.ATTACK, StatCategoryInference.infer("bow-cooldown-reduction"));
    }

    @Test
    void distanceDamageBonusIsAttack() {
        assertEquals(StatCategory.ATTACK, StatCategoryInference.infer("distance-damage-bonus"));
    }

    @Test
    void arrowPiercingIsAttack() {
        assertEquals(StatCategory.ATTACK, StatCategoryInference.infer("arrow-piercing"));
    }

    @Test
    void healthRegenBonusIsDefense() {
        assertEquals(StatCategory.DEFENSE, StatCategoryInference.infer("health-regen-bonus"));
    }

    @Test
    void lapisCostReductionIsCraft() {
        assertEquals(StatCategory.CRAFT, StatCategoryInference.infer("lapis-cost-reduction"));
    }

    @Test
    void materialRefundChanceIsCraft() {
        assertEquals(StatCategory.CRAFT, StatCategoryInference.infer("material-refund-chance"));
    }

    @Test
    void ingredientSaveChanceIsCraft() {
        assertEquals(StatCategory.CRAFT, StatCategoryInference.infer("ingredient-save-chance"));
    }

    @Test
    void sourceCostReductionIsArs() {
        assertEquals(StatCategory.ARS, StatCategoryInference.infer("source-cost-reduction"));
    }

    @Test
    void hungerSaveChanceIsUtility() {
        assertEquals(StatCategory.UTILITY, StatCategoryInference.infer("hunger-save-chance"));
    }

    @Test
    void foodSaveChanceIsUtility() {
        assertEquals(StatCategory.UTILITY, StatCategoryInference.infer("food-save-chance"));
    }

    @Test
    void lootLuckIsUtility() {
        assertEquals(StatCategory.UTILITY, StatCategoryInference.infer("loot-luck"));
    }

    @Test
    void gachaRateBonusIsUtility() {
        assertEquals(StatCategory.UTILITY, StatCategoryInference.infer("gacha-rate-bonus"));
    }

    @Test
    void mobDropBonusIsUtility() {
        assertEquals(StatCategory.UTILITY, StatCategoryInference.infer("mob-drop-bonus"));
    }

    @Test
    void mobDropQualityIsUtility() {
        assertEquals(StatCategory.UTILITY, StatCategoryInference.infer("mob-drop-quality"));
    }

    @Test
    void skillExpBonusIsUtility() {
        assertEquals(StatCategory.UTILITY, StatCategoryInference.infer("skill-exp-bonus"));
    }

    /**
     * 2026-07-26 stat-scope 境界引き直し §1 (C→A 降格): coating-charges は明示的な UTILITY 分岐から
     * 除外された(item専用ステとなったため)。他の item専用ステ(item-cooldown/tool-enchant-*)と同じく
     * デフォルトの OTHER カテゴリへ落ちる。
     */
    @Test
    void coatingChargesIsOther() {
        assertEquals(StatCategory.OTHER, StatCategoryInference.infer("coating-charges"));
    }

    @Test
    void suspiciousRespawnChanceIsGathering() {
        assertEquals(StatCategory.GATHERING, StatCategoryInference.infer("suspicious-respawn-chance"));
    }

    @Test
    void hiveHarvestFortuneIsGathering() {
        assertEquals(StatCategory.GATHERING, StatCategoryInference.infer("hive-harvest-fortune"));
    }
}
