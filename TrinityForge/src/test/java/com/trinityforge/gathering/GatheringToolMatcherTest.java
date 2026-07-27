package com.trinityforge.gathering;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 採取ツール判定({@link GatheringToolMatcher})の純関数テスト。
 *
 * <p>2026-07-27 の決定「use-skill 判定へ移行＋バニラ製も許可」を固定する。特に
 * <b>戦闘用の斧では一括伐採が発動しない</b>ことと、<b>素のバニラの斧では従来どおり発動する</b>ことの
 * 両方が同時に成り立つ必要がある — 片方だけなら簡単に満たせてしまうので、両方を明示的に押さえる。
 */
class GatheringToolMatcherTest {

    @Test
    @DisplayName("TFの伐採斧(use-skill: WOODCUTTING)は伐採ツールとして通る")
    void taggedWoodcuttingAxe_matchesWoodcutting() {
        assertEquals(GatheringToolMatcher.WOODCUTTING,
                GatheringToolMatcher.resolve("WOODCUTTING", Material.DIAMOND_AXE));
    }

    @Test
    @DisplayName("TFの戦闘斧(use-skill: HEAVY_WEAPONS)は伐採ツールにならない — 今回の修正の本体")
    void taggedCombatAxe_doesNotMatchWoodcutting() {
        assertEquals("HEAVY_WEAPONS",
                GatheringToolMatcher.resolve("HEAVY_WEAPONS", Material.DIAMOND_AXE));
    }

    @Test
    @DisplayName("素のバニラの斧はマテリアル推論で伐採ツールとして通る(素の斧を殺さない)")
    void bareVanillaAxe_infersWoodcutting() {
        assertEquals(GatheringToolMatcher.WOODCUTTING,
                GatheringToolMatcher.resolve(null, Material.DIAMOND_AXE));
    }

    @Test
    @DisplayName("採取文脈では _AXE は伐採であって武器ではない(UseSkillDefaults とは意図的に異なる)")
    void axeIsWoodcuttingNotHeavyWeapons() {
        // stats.UseSkillDefaults は装備ゲート用なので _AXE を HEAVY_WEAPONS に落とす。
        // それをそのまま流用すると素の斧で一括伐採ができなくなるため、推論表を分けている。
        assertEquals(GatheringToolMatcher.WOODCUTTING,
                GatheringToolMatcher.inferFromMaterial(Material.WOODEN_AXE));
    }

    @Test
    @DisplayName("ツルハシは MINING に推論される(_PICKAXE が _AXE に吸われない)")
    void pickaxeIsMiningNotWoodcutting() {
        assertEquals(GatheringToolMatcher.MINING,
                GatheringToolMatcher.inferFromMaterial(Material.NETHERITE_PICKAXE));
    }

    @Test
    @DisplayName("シャベルは DIGGING、鍬は FARMING")
    void shovelAndHoe() {
        assertEquals(GatheringToolMatcher.DIGGING,
                GatheringToolMatcher.inferFromMaterial(Material.IRON_SHOVEL));
        assertEquals(GatheringToolMatcher.FARMING,
                GatheringToolMatcher.inferFromMaterial(Material.GOLDEN_HOE));
    }

    @Test
    @DisplayName("素手・杖・無関係なアイテムは採取ツールとして解決されない")
    void nonToolsResolveToNothing() {
        assertNull(GatheringToolMatcher.inferFromMaterial(null));
        assertNull(GatheringToolMatcher.inferFromMaterial(Material.STICK));
        assertNull(GatheringToolMatcher.inferFromMaterial(Material.DIAMOND_SWORD));
        assertNull(GatheringToolMatcher.resolve(null, Material.AIR));
    }

    @Test
    @DisplayName("タグは大小文字とも前後空白とも無関係に正規化される")
    void tagIsNormalised() {
        assertEquals(GatheringToolMatcher.MINING,
                GatheringToolMatcher.resolve("  mining ", Material.STICK));
    }

    @Test
    @DisplayName("空白だけのタグはタグ無し扱いでマテリアル推論へ落ちる")
    void blankTagFallsBackToMaterial() {
        assertEquals(GatheringToolMatcher.MINING,
                GatheringToolMatcher.resolve("   ", Material.STONE_PICKAXE));
    }
}
