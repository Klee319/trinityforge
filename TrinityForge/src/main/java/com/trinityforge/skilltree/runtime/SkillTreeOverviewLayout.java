package com.trinityforge.skilltree.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code /skills} 一覧モード(2026-08-04新設)の純粋なレイアウト(Bukkit非依存)。
 *
 * <p>通常モードは1ツリーを9x5ビューポートで描画するが({@link NativeSkillTreeCanvas})、
 * 一覧モードはツリーの中身を描画せず、全ツリーを表すアイコンだけを固定の格子へ並べる。
 * {@code AchievementCanvas}/{@code NativeSkillTreeCanvas}と同じ「純粋な投影 + 薄いBukkit層」の
 * 分け方に倣い、スロット割当だけをここへ切り出す(実際のアイコン描画・クリック処理は
 * {@link NativeSkillTreeMenu} が担う)。
 *
 * <p>格子は通常モードのビューポート(スロット0-44)・選択バー(46-52)・移動矢印(8隅)とスロット番号が
 * 重なるが、一覧モードではそれらを描画しない(モードごとに54枠を全く別の内容で作り直すだけ)ため
 * 実害は無い。
 *
 * <p>2026-08-05(W-29): 同じ格子を「現ツリーのパーク一覧モード」でも使う(ユーザー要件
 * 「スキルツリー一覧と同じ体裁で並べ」)。ツリー数(16)は格子容量(20)に収まるが、パーク数は
 * POWER で 35 を超えるので、パーク一覧側だけページ送り({@link #PAGE_PREV_SLOT} /
 * {@link #PAGE_NEXT_SLOT})を持つ。
 */
final class SkillTreeOverviewLayout {

    /**
     * モード切替ボタンの固定スロット。通常モード({@code NativeSkillTreeMenu#renderSkillSelector})・
     * 一覧モードの両方でここに置く(同じ位置に固定することで、モードを跨いでも押す場所が変わらない)。
     */
    static final int TOGGLE_SLOT = 53;

    /**
     * パーク一覧モードの切替ボタン(時計アイコン)の固定スロット = 最下段の左端(2026-08-05, W-29)。
     * {@link #TOGGLE_SLOT} と同じ理由で全モードに置く。ここを明け渡した分、通常モードの
     * スキル選択バーは 8 枠(45-52)から 7 枠(46-52)へ縮めた(選択中のツリーは 49 のまま中央)。
     */
    static final int PERK_LIST_SLOT = 45;

    /**
     * パーク一覧モードのページ送りボタン。格子(列2-6)の左右外側の列に置くので格子とは重ならない。
     * 送り先のページが無いときは描画しない。
     */
    static final int PAGE_PREV_SLOT = 19;
    static final int PAGE_NEXT_SLOT = 25;

    /** 一覧グリッド: 絶対行1-4・絶対列2-6を行優先で埋める(最大20件。現状16ツリーに対し余裕がある)。 */
    private static final List<Integer> GRID_SLOTS = buildGridSlots();

    private SkillTreeOverviewLayout() {
    }

    private static List<Integer> buildGridSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            for (int col = 2; col <= 6; col++) {
                slots.add(row * 9 + col);
            }
        }
        return List.copyOf(slots);
    }

    /** 一覧グリッドが収容できる最大件数。 */
    static int capacity() {
        return GRID_SLOTS.size();
    }

    /**
     * {@code total} 件を格子へ並べたときの総ページ数(2026-08-05, W-29)。
     * 0 件でも 1 ページ(空の一覧を出す)を返す — 0 を返すと呼び出し側で
     * {@code floorMod(page, 0)} が例外になる。
     */
    static int pageCount(int total) {
        if (total <= GRID_SLOTS.size()) {
            return 1;
        }
        return (total + GRID_SLOTS.size() - 1) / GRID_SLOTS.size();
    }

    /**
     * {@code page} ページ目(0始まり)に載る要素の範囲を {@code items} から切り出す。
     * 範囲外のページは空リスト。
     */
    static <T> List<T> page(List<T> items, int page) {
        Objects.requireNonNull(items, "items");
        int from = Math.max(0, page) * GRID_SLOTS.size();
        if (from >= items.size()) {
            return List.of();
        }
        return List.copyOf(items.subList(from, Math.min(items.size(), from + GRID_SLOTS.size())));
    }

    /**
     * スキルID順にスロットを割り当てる。{@link #capacity()} を超える分は割り当てられない
     * (GUIから溢れる — 現状16ツリーに対し20枠あるので発生しない)。
     */
    static Map<String, Integer> assign(List<String> skillIdsInOrder) {
        Objects.requireNonNull(skillIdsInOrder, "skillIdsInOrder");
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < skillIdsInOrder.size() && i < GRID_SLOTS.size(); i++) {
            result.put(skillIdsInOrder.get(i), GRID_SLOTS.get(i));
        }
        return Map.copyOf(result);
    }

    /** クリックされたスロットが、一覧のどのスキルへ解決されるか({@link #TOGGLE_SLOT} 自身は含まない)。 */
    static Optional<String> skillForSlot(int slot, List<String> skillIdsInOrder) {
        Map<String, Integer> assignment = assign(skillIdsInOrder);
        for (Map.Entry<String, Integer> entry : assignment.entrySet()) {
            if (entry.getValue() == slot) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }
}
