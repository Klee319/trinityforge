package com.trinityforge.progression;

import com.trinityforge.pdc.PdcKeys;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 称号の表示体が<b>二重に残らない</b>ことを固定する
 * (2026-08-21 実サーバ報告「称号が二個付いている」＋スクリーンショット)。
 *
 * <p><b>真因は「消す側が消せない状況でだけ諦める」形</b>だった:
 * <ol>
 *   <li>{@code safeRemove} が {@code isValid()} で門を張っていた。{@code CraftEntity#isValid()} は
 *       「生きている」だけでなく<b>チャンクがロード済みでワールドのエンティティリストに登録済み</b>まで
 *       要求するので、<b>遠距離テレポート直後・湧かせた直後</b>という「一番消し損ねやすい瞬間」に限って
 *       false を返す。そこで諦めると表示体は誰の追跡下にも無いまま世界に残る。</li>
 *   <li>{@code tick()} は表示体が無効になった行を {@code active} から外して張り直していたが、
 *       <b>実体を消していなかった</b>。1 に当たると必ず迷子が 1 体増える。</li>
 * </ol>
 * どちらも例外もログも出さず、症状は「称号が二段に見える」だけ。
 * 迷子は騎乗パケットが新しい個体に上書きされるため実座標＋平行移動の位置に描かれ、
 * 正しいほうより 1 ブロックほど<b>上</b>に並ぶ ── 報告のスクリーンショットの見え方と一致する。
 *
 * <p>spawn 経路は MockBukkit が {@code TextDisplay} の生成を未実装(踏むと FAILED ではなく
 * <b>SKIPPED</b> に化ける)なので、判定側だけを Bukkit 非依存の形で切り出して縛る。
 */
class TitleDisplayOrphanTest {

    private static TextDisplay titleDisplay(int entityId, boolean tagged) {
        TextDisplay display = mock(TextDisplay.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.has(PdcKeys.TITLE_DISPLAY, PersistentDataType.BYTE)).thenReturn(tagged);
        when(display.getPersistentDataContainer()).thenReturn(pdc);
        when(display.getEntityId()).thenReturn(entityId);
        return display;
    }

    @Test
    @DisplayName("追跡外の称号表示体は消す / 追跡中のものには触らない")
    void orphanTitleDisplaysAreRemovedButTrackedOnesSurvive() {
        TextDisplay tracked = titleDisplay(100, true);
        TextDisplay orphan = titleDisplay(200, true);

        int removed = TitleDisplayService.removeOrphans(List.of(tracked, orphan), Set.of(100));

        assertEquals(1, removed);
        verify(orphan, times(1)).remove();
        verify(tracked, never()).remove();
    }

    @Test
    @DisplayName("TFの印が無い TextDisplay と、そもそも別種のエンティティには触らない")
    void unrelatedEntitiesAreNeverTouched() {
        TextDisplay foreignDisplay = titleDisplay(300, false); // 他プラグインの TextDisplay
        Zombie zombie = mock(Zombie.class);

        int removed = TitleDisplayService.removeOrphans(List.of(foreignDisplay, zombie), Set.of());

        assertEquals(0, removed);
        verify(foreignDisplay, never()).remove();
        verify(zombie, never()).remove();
    }

    @Test
    @DisplayName("isValid() が false でも消しに行く —— ここで諦めるのが迷子の作り方そのもの")
    void removalDoesNotDependOnIsValid() {
        Entity stranded = mock(Entity.class);
        when(stranded.isValid()).thenReturn(false); // チャンク未ロード/未登録の個体

        TitleDisplayService.safeRemove(stranded);

        verify(stranded, times(1)).remove();
    }

    @Test
    @DisplayName("null は素通り(呼び出し側で毎回 null 判定を書かせない)")
    void nullIsIgnored() {
        TitleDisplayService.safeRemove(null);
        assertEquals(0, TitleDisplayService.removeOrphans(null, Set.of()));
    }

    @Test
    @DisplayName("追跡集合が空でも、印を持つ個体は全部消える(起動時の総なめと同じ意味)")
    void anEmptyTrackedSetRemovesEveryTaggedDisplay() {
        TextDisplay first = titleDisplay(1, true);
        TextDisplay second = titleDisplay(2, true);

        assertEquals(2, TitleDisplayService.removeOrphans(List.of(first, second), Set.of()));
    }
}
