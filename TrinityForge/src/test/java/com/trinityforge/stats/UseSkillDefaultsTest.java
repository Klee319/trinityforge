package com.trinityforge.stats;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UseSkillDefaultsTest {

    @Test
    void removedLegacyCmdDoesNotClassifyUnrelatedMaterial() {
        assertTrue(UseSkillDefaults.infer(Material.STICK, 1_981_826).isEmpty());
    }

    @Test
    void removedLegacyCmdDoesNotOverrideBaseMaterialClassification() {
        assertEquals("LIGHT_WEAPONS",
                UseSkillDefaults.infer(Material.DIAMOND_SWORD, 1_981_827).orElseThrow());
    }

    @Test
    void ironArmorInfersHeavyArmor() {
        assertEquals("HEAVY_ARMOR",
                UseSkillDefaults.infer(Material.IRON_CHESTPLATE, null).orElseThrow());
    }

    @Test
    void leatherArmorInfersLightArmor() {
        assertEquals("LIGHT_ARMOR",
                UseSkillDefaults.infer(Material.LEATHER_HELMET, null).orElseThrow());
    }

    @Test
    void bowInfersArchery() {
        assertEquals("ARCHERY", UseSkillDefaults.infer(Material.BOW, null).orElseThrow());
    }

    @Test
    void crossbowInfersArchery() {
        assertEquals("ARCHERY", UseSkillDefaults.infer(Material.CROSSBOW, null).orElseThrow());
    }

    /**
     * N5(2026-07-31): トライデントは ARCHERY ではなく LIGHT_WEAPONS。
     *
     * <p>出荷 {@code stats/item-stats.yml} のトライデント14行はすべて {@code use-skill: LIGHT_WEAPONS} を
     * 明記していたのに、推論だけが ARCHERY で食い違っていた。推論が効くのは {@code use-skill} を
     * 書かなかった行だけなので現物では不発だったが、書き忘れた行を1つ足した瞬間にそのトライデントの
     * 戦闘EXPが(当時の per-hit 弓術式が BOW/CROSSBOW 以外へ 0.0 を返す実装だったため)
     * <b>警告なしで完全に0</b>になる landmine だった。
     */
    @Test
    void tridentInfersLightWeaponsNotArchery() {
        assertEquals("LIGHT_WEAPONS", UseSkillDefaults.infer(Material.TRIDENT, null).orElseThrow(),
                "トライデントは近接武器扱い(出荷 item-stats の実態に合わせる)");
    }

    @Test
    void nonEquipmentEmpty() {
        assertTrue(UseSkillDefaults.infer(Material.STONE, null).isEmpty());
    }
}
