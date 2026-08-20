package com.trinityforge.progression.catalog;

import com.trinityforge.progression.core.SkillId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that loads the real {@code skills/base/*.yml} resources from the test
 * classpath and verifies all 16 entries are parsed correctly. No MockBukkit required.
 */
class NativeSkillCatalogTest {

    private static final NativeSkillCatalog CATALOG =
            NativeSkillCatalog.load(NativeSkillCatalogTest.class.getClassLoader());

    // ---- all-16 presence ----

    @Test
    void allSixteenSkillsLoaded() {
        assertEquals(16, CATALOG.size(), "Expected all 16 skills to be loaded");
    }

    @Test
    void allSkillIdsPresent() {
        for (String skillId : SkillId.ALL) {
            assertNotNull(CATALOG.get(skillId), "Missing catalog entry for: " + skillId);
        }
    }

    @Test
    void skillIds_areUppercase() {
        for (SkillCatalogEntry entry : CATALOG.entries().values()) {
            assertEquals(entry.skillId().toUpperCase(), entry.skillId(),
                    "Skill ID is not uppercase: " + entry.skillId());
        }
    }

    // ---- max_level ----

    @Test
    void standardSkills_maxLevelIs100() {
        for (String skillId : SkillId.ALL) {
            if (SkillId.POWER.equals(skillId)) continue;
            assertEquals(100, CATALOG.get(skillId).maxLevel(),
                    "Expected max_level=100 for " + skillId);
        }
    }

    @Test
    void powerSkill_maxLevelIs160() {
        // 2026-07-25 PRG-08: 256は現行のPOWER EXP供給(15スキル×Lv100×exp_gain)では到達不能な表記だった
        // (監査推定でL≈92止まり)。exp_gain引き上げ後の実効到達点(L≈160)に max_level を合わせた。
        assertEquals(160, CATALOG.get(SkillId.POWER).maxLevel());
    }

    // ---- formula evaluation spot-checks ----

    @Test
    void standardFormula_level0_gives375() {
        // (%level% + 75 * 2^(%level%/7.6)) + 300 at 0 = 375
        assertEquals(375L, CATALOG.get(SkillId.MINING).curve().expRequiredAt(0));
    }

    @Test
    void standardFormula_level0_archery() {
        assertEquals(375L, CATALOG.get(SkillId.ARCHERY).curve().expRequiredAt(0));
    }

    @Test
    void standardFormula_level0_arsMagic() {
        assertEquals(375L, CATALOG.get(SkillId.ARS_MAGIC).curve().expRequiredAt(0));
    }

    @Test
    void standardFormula_level0_arsSmithing() {
        assertEquals(375L, CATALOG.get(SkillId.ARS_SMITHING).curve().expRequiredAt(0));
    }

    @Test
    void powerFormula_level0_gives800() {
        // (%level%/100) * 1800 + 800 at 0 = 800
        assertEquals(800L, CATALOG.get(SkillId.POWER).curve().expRequiredAt(0));
    }

    @Test
    void powerFormula_level100_gives2600() {
        assertEquals(2600L, CATALOG.get(SkillId.POWER).curve().expRequiredAt(100));
    }

    // ---- curve quality ----

    @Test
    void allCurves_returnAtLeastOne() {
        for (String skillId : SkillId.ALL) {
            long cost = CATALOG.get(skillId).curve().expRequiredAt(0);
            assertTrue(cost >= 1L,
                    "Curve for " + skillId + " returned < 1 at level 0: " + cost);
        }
    }

    @Test
    void standardCurve_isIncreasing_overFirstTenLevels() {
        long prev = 0L;
        for (int lvl = 0; lvl < 10; lvl++) {
            long cost = CATALOG.get(SkillId.MINING).curve().expRequiredAt(lvl);
            assertTrue(cost > prev,
                    "Expected cost[" + lvl + "] > cost[" + (lvl - 1) + "], got " + cost + " <= " + prev);
            prev = cost;
        }
    }

    @Test
    void formulaStrings_areNonBlank() {
        for (String skillId : SkillId.ALL) {
            assertFalse(CATALOG.get(skillId).formulaString().isBlank(),
                    "formulaString is blank for " + skillId);
        }
    }

    @Test
    void authoritativeActionExpTablesAreLoaded() {
        assertEquals(400.0,
                CATALOG.get(SkillId.MINING).expFor("mining_break", "DIAMOND_ORE"));
        assertEquals(48.0,
                CATALOG.get(SkillId.FARMING).expFor("block_drops", "WHEAT"));
        assertEquals(40.0,
                CATALOG.get(SkillId.WOODCUTTING).expFor("woodcutting_break", "OAK_LOG"));
        assertEquals(0.0,
                CATALOG.get(SkillId.MINING).expFor("mining_break", "GLASS"));
    }

    @Test
    void nestedValhallaEnchantingTablesAreLoaded() {
        SkillCatalogEntry enchanting = CATALOG.get(SkillId.ENCHANTING);

        assertEquals(180.0,
                enchanting.expFor("exp_gain.enchantment_base", "sharpness"));
        assertEquals(3.4,
                enchanting.expFor("exp_gain.enchantment_level_multiplier", "3"));
        assertEquals(1.0,
                enchanting.expFor("exp_gain.enchantment_type_multiplier", "DIAMOND"));
        assertEquals(1.0,
                enchanting.expFor("exp_gain.enchantment_item_multiplier", "SWORD"));
    }

    @Test
    void allValhallaNonCombatActionTablesAreLoaded() {
        assertEquals(160.0,
                CATALOG.get(SkillId.FARMING).expFor("entity_breed", "FROG"));
        assertEquals(60.0,
                CATALOG.get(SkillId.FARMING).expFor("entity_drops", "BEEF"));
        assertEquals(200.0,
                CATALOG.get(SkillId.FARMING).expFor("entity_shear", "SHEEP"));
        assertEquals(20.0,
                CATALOG.get(SkillId.WOODCUTTING).expFor("woodcutting_strip", "STRIPPED_OAK_LOG"));
        assertEquals(150.0,
                CATALOG.get(SkillId.DIGGING).expFor("archaeology_brush", "DIAMOND"));
    }

    /**
     * 2026-08-03 実サーバ報告の回帰: グロウベリー(CAVE_VINES_PLANT/CAVE_VINES への右クリック採取)は
     * {@code onFarmingInteract}({@link com.trinityforge.listeners.NativeSkillExperienceListener})が
     * {@code block_interact} 表のゲート値を見るが、出荷 yml で長らく 0 になっていたため
     * {@code isHarvestableFarmingInteraction} が true を返しても EXP が常に 0 だった
     * (ブロック行はゲート専用/実量はドロップ材質側という `block_drops` の規約と混同し、
     * `block_interact` は単一行だけで完結する点を見落としたのが原因)。ジャガイモ/ニンジン/
     * ビートルート等の {@code block_drops} 側(ブロック行=ゲート、ドロップ材質行=実量の2行制)は
     * 出荷 yml で既に揃っているので、こちらは値の存在だけを固定する。
     */
    @Test
    void farmingRightClickHarvestGatesAreConfigured() {
        SkillCatalogEntry farming = CATALOG.get(SkillId.FARMING);
        assertTrue(farming.expFor("block_interact", "CAVE_VINES_PLANT") > 0.0,
                "グロウベリー(CAVE_VINES_PLANT右クリック採取)のEXPゲートが0のまま");
        assertTrue(farming.expFor("block_interact", "CAVE_VINES") > 0.0,
                "グロウベリー(CAVE_VINES右クリック採取)のEXPゲートが0のまま");
        assertTrue(farming.expFor("block_interact", "SWEET_BERRY_BUSH") > 0.0);
    }

    /**
     * {@code block_drops} は「ブロック行=ゲート専用、実量はドロップ材質行」の2行制
     * ({@code drop_sum} モード)。ブロック行だけあってドロップ材質行が無いとゲートは通るのに
     * 実量が0になる、という取りこぼしが起きやすいので主要作物を突き合わせて固定する。
     */
    @Test
    void farmingBlockBreakGateAndDropRowsBothConfigured() {
        SkillCatalogEntry farming = CATALOG.get(SkillId.FARMING);
        assertTrue(farming.expFor("block_drops", "POTATOES") > 0.0, "POTATOES(ゲート)");
        assertTrue(farming.expFor("block_drops", "POTATO") > 0.0, "POTATO(実量)");
        assertTrue(farming.expFor("block_drops", "CARROTS") > 0.0, "CARROTS(ゲート)");
        assertTrue(farming.expFor("block_drops", "CARROT") > 0.0, "CARROT(実量)");
        assertTrue(farming.expFor("block_drops", "BEETROOTS") > 0.0, "BEETROOTS(ゲート)");
        assertTrue(farming.expFor("block_drops", "BEETROOT") > 0.0, "BEETROOT(実量)");
        assertTrue(farming.expFor("block_drops", "GLOW_BERRIES") > 0.0,
                "GLOW_BERRIES(CAVE_VINES破壊時のドロップ実量)");
        assertTrue(farming.expFor("block_drops", "SUGAR_CANE") > 0.0);
        assertTrue(farming.expFor("block_drops", "BAMBOO") > 0.0);
        assertTrue(farming.expFor("block_drops", "NETHER_WART") > 0.0);
        assertTrue(farming.expFor("block_drops", "PUMPKIN") > 0.0);
        assertTrue(farming.expFor("block_drops", "MELON") > 0.0);
        assertTrue(farming.expFor("block_drops", "MELON_SLICE") > 0.0, "MELON(実量)");
    }

    /**
     * 2026-08-03 追加(グロウベリー0EXP事故の再発防止): 個別アサーションだけだと同型の事故
     * (「行はあるが値が0のまま出荷される」)が別の材質でまた起きる。<b>到達可能なアクション表
     * (単一行=そのままゲート兼実量、というシンプルな{@code block_drops}系)は1行たりとも0であっては
     * いけない</b>——0はコード上「その材質は無報酬」を意味し、行が存在すること自体が
     * 「実装側は対応しているのに値だけ空」という設定漏れの証拠になるため。
     *
     * <p>ここで掃く対象は「行の値がそのままゲート兼実量になる」単純な表だけ
     * ({@code mining_break}/{@code woodcutting_break}/{@code woodcutting_strip}/{@code digging_break}/
     * {@code archaeology_brush}/{@code entity_breed}/{@code entity_drops}/{@code entity_shear}/
     * {@code fishing_catch}/FARMINGの{@code block_drops})。倍率・乗数系の表
     * (enchanting の {@code exp_gain} nested multipliers、防具の {@code entity_exp_multipliers}、
     * alchemy の {@code brew_result}/{@code brew_ingredient})は0が意味を持ちうる(倍率1.0基準からの
     * 相対値等)ため対象外——単純な「値そのものがEXP量」の表だけに絞る。
     *
     * <p>FARMINGの{@code block_interact}だけは例外的に部分ホワイトリスト方式: 実際に読まれるのは
     * {@link com.trinityforge.listeners.NativeSkillExperienceListener#isHarvestableFarmingInteraction}
     * が true を返す5種({@code SWEET_BERRY_BUSH}/{@code CAVE_VINES}/{@code CAVE_VINES_PLANT}/
     * {@code BEEHIVE}/{@code BEE_NEST})だけで、それ以外の行(PUMPKIN/KELP/ツタ類)は同判定に
     * 引っかからない到達不能な死に行なので0のままでよい(2026-08-03 棚卸しで確定)。
     */
    @Test
    void reachableSimpleActionTablesHaveNoZeroRows() {
        record Sweep(String skillId, String action) {
        }
        List<Sweep> sweeps = List.of(
                new Sweep(SkillId.MINING, "mining_break"),
                new Sweep(SkillId.WOODCUTTING, "woodcutting_break"),
                new Sweep(SkillId.WOODCUTTING, "woodcutting_strip"),
                new Sweep(SkillId.DIGGING, "digging_break"),
                new Sweep(SkillId.DIGGING, "archaeology_brush"),
                new Sweep(SkillId.FARMING, "block_drops"),
                new Sweep(SkillId.FARMING, "entity_breed"),
                new Sweep(SkillId.FARMING, "entity_drops"),
                new Sweep(SkillId.FARMING, "entity_shear"),
                new Sweep(SkillId.FISHING, "fishing_catch"));

        // FARMINGの block_interact は「到達可能な5行だけ」を掃く部分ホワイトリスト。
        Set<String> reachableFarmingInteract = Set.of(
                "SWEET_BERRY_BUSH", "CAVE_VINES", "CAVE_VINES_PLANT", "BEEHIVE", "BEE_NEST");

        List<String> zeroRows = new ArrayList<>();
        for (Sweep sweep : sweeps) {
            SkillCatalogEntry entry = CATALOG.get(sweep.skillId());
            String prefix = sweep.action() + ".";
            for (Map.Entry<String, Double> row : entry.actionExp().entrySet()) {
                if (!row.getKey().startsWith(prefix)) continue;
                if (row.getValue() == null || row.getValue() <= 0.0) {
                    zeroRows.add(sweep.skillId() + "/" + row.getKey());
                }
            }
        }
        SkillCatalogEntry farming = CATALOG.get(SkillId.FARMING);
        for (Map.Entry<String, Double> row : farming.actionExp().entrySet()) {
            if (!row.getKey().startsWith("block_interact.")) continue;
            String material = row.getKey().substring("block_interact.".length());
            if (!reachableFarmingInteract.contains(material)) continue; // 到達不能な死に行は対象外
            if (row.getValue() == null || row.getValue() <= 0.0) {
                zeroRows.add("FARMING/" + row.getKey());
            }
        }

        assertTrue(zeroRows.isEmpty(),
                "到達可能なアクション表に0のままの行がある(設定漏れ、EXPが常に0になる): " + zeroRows);
    }

    // ---- entries() view ----

    @Test
    void entries_isUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> CATALOG.entries().put("FAKE", null));
    }

    @Test
    void allSkillIdConstants_resolveInCatalog() {
        assertNotNull(CATALOG.get(SkillId.ALCHEMY));
        assertNotNull(CATALOG.get(SkillId.DIGGING));
        assertNotNull(CATALOG.get(SkillId.ENCHANTING));
        assertNotNull(CATALOG.get(SkillId.FARMING));
        assertNotNull(CATALOG.get(SkillId.FISHING));
        assertNotNull(CATALOG.get(SkillId.HEAVY_ARMOR));
        assertNotNull(CATALOG.get(SkillId.HEAVY_WEAPONS));
        assertNotNull(CATALOG.get(SkillId.LIGHT_ARMOR));
        assertNotNull(CATALOG.get(SkillId.LIGHT_WEAPONS));
        assertNotNull(CATALOG.get(SkillId.SMITHING));
        assertNotNull(CATALOG.get(SkillId.WOODCUTTING));
    }
}
