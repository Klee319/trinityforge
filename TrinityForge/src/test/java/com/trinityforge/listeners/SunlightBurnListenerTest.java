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

    // ---- 空が見えているかの判定 (2026-08-19 / W-114) -------------------------------------------
    //
    // バニラの日光焼却は【目の高さ】で空が見えるかを見る(Mob#isSunBurnTick)。旧実装は足元ブロック
    // だけを見ていたので、浅い水に立つ個体(水がスカイライトを減衰させる)で
    // 「バニラは焼いているのに TF の置換だけ効かない = 1ダメージのまま」になっていた。
    // 出荷対象に DROWNED が居ること、水は火を消すことから、報告の
    // 「一度鎮火してから再炎上するとき」と状況が一致する。

    @Test
    @DisplayName("足元のスカイライトが落ちていても、目の高さで空が見えていれば日光扱いにする")
    void eyeLevelSkyLightAloneIsEnough() {
        // 浅い水に立つ DROWNED: 足元(水中)は 11、頭は水面より上で 15。
        assertTrue(SunlightBurnListener.seesSky(15, 11),
                "目の高さで空が見えているのに置換しないと、バニラの1ダメージのまま焼け死ななくなる");
    }

    @Test
    @DisplayName("足元だけで空が見えている場合も日光扱いにする(小型モブ・頭がめり込む個体の保険)")
    void feetLevelSkyLightAloneIsEnough() {
        assertTrue(SunlightBurnListener.seesSky(11, 15));
    }

    @Test
    @DisplayName("目の高さも足元も空が見えていなければ日光扱いにしない")
    void obstructedBothWaysIsNotDaylight() {
        assertFalse(SunlightBurnListener.seesSky(11, 11),
                "屋内や地下で火属性エンチャントに着火されただけの個体まで%HPダメージ化してはいけない");
        assertFalse(SunlightBurnListener.seesSky(0, 0));
    }
}
