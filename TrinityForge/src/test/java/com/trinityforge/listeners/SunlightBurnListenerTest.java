package com.trinityforge.listeners;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日光炎上の置換対象判定(2026-07-28、{@code combat/damage.yml} の {@code sunlight-burn.mobs})。
 *
 * <p>ここが緩いと「野外・昼間に火属性で着火しただけ」のボスまで毎秒10%ずつ溶けるため、
 * 既定は日光焼却される種別だけに絞ってある。
 */
class SunlightBurnListenerTest {

    @Test
    @DisplayName("列挙された EntityType だけが対象になる")
    void onlyListedTypesAreTargeted() {
        List<String> mobs = List.of("ZOMBIE", "SKELETON");

        assertTrue(SunlightBurnListener.isTargeted(EntityType.ZOMBIE, mobs));
        assertTrue(SunlightBurnListener.isTargeted(EntityType.SKELETON, mobs));
        assertFalse(SunlightBurnListener.isTargeted(EntityType.CREEPER, mobs),
                "リストに無い種別を置換すると火属性エンチャントが%HPダメージ化する");
    }

    @Test
    @DisplayName("大文字小文字・前後空白は正規化される")
    void namesAreNormalized() {
        assertTrue(SunlightBurnListener.isTargeted(EntityType.ZOMBIE, List.of("  zombie  ")));
    }

    @Test
    @DisplayName("空リスト/未知の名前だけのリストは「全モブ対象」へ倒す")
    void emptyOrUnknownListTargetsEverything() {
        assertTrue(SunlightBurnListener.isTargeted(EntityType.CREEPER, List.of()));
        assertTrue(SunlightBurnListener.isTargeted(EntityType.CREEPER, null));
        assertTrue(SunlightBurnListener.isTargeted(EntityType.CREEPER, List.of("NOT_A_MOB")));
    }

    @Test
    @DisplayName("未知の名前が混ざっても、既知のエントリの絞り込みは生きる")
    void unknownNamesDoNotDisableFiltering() {
        List<String> mobs = List.of("ZOMBIE", "NOT_A_MOB");

        assertTrue(SunlightBurnListener.isTargeted(EntityType.ZOMBIE, mobs));
        assertFalse(SunlightBurnListener.isTargeted(EntityType.CREEPER, mobs));
    }
}
