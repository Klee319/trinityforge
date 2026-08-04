package com.trinityforge.stats;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoreComposer#statLines} が<b>ステ行だけ</b>を返すこと(区切り線を作らないこと)の回帰ガード。
 *
 * <h2>なぜ要るのか(2026-08-05)</h2>
 * {@link LoreComposer#compose} は非空カテゴリごとに {@code ====} の区切り線を入れ、
 * その<b>幅はそのときの最長行から決まる</b>({@code resolveSeparators})。
 * 「他のアイテムの lore へ差し込む行」をこれで作ると、差し込み先が
 * 「前回差し込んだ行を内容一致で消してから新しい行を足す」方式のときに壊れる ──
 * ArsPaper のスレッド返却({@code ThreadGui#restoreRoll})とスレッドのリロール
 * ({@code ThreadRerollRitualEffect})がまさにその方式で、<b>値の桁が変わって区切り線の幅が変わった
 * 瞬間に古い線が消えずに溜まり続ける</b>(装着/取り外しを繰り返すほど {@code ====} が増える)。
 *
 * <p>差し込み用の経路では区切り線を最初から作らない、というのがこの API の存在理由。
 * {@code statLines} が {@code compose} へ退化したら 1 本目のテストが落ちる。
 */
class LoreComposerStatLinesTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static StatDisplaySpec spec(String key, String name, int order, StatCategory category) {
        return new StatDisplaySpec(key, name, "", LoreValueFormat.FLAT, 1, order, true, true, "",
                category);
    }

    /** 攻撃系と防御系にまたがる = compose なら区切り線が 2 本入る構成。 */
    private static final Map<String, StatDisplaySpec> TABLE = Map.of(
            "attack_power", spec("attack_power", "攻撃力", 10, StatCategory.ATTACK),
            "bleed_damage", spec("bleed_damage", "出血ダメージ", 20, StatCategory.ATTACK),
            "damage_reduction", spec("damage_reduction", "被ダメ軽減", 30, StatCategory.DEFENSE));

    private static Map<String, Double> stats(double attack) {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put("attack_power", attack);
        stats.put("bleed_damage", 20.0);
        stats.put("damage_reduction", 0.5);
        return stats;
    }

    private static List<String> plain(List<Component> lines) {
        return lines.stream().map(PLAIN::serialize).toList();
    }

    @Test
    @DisplayName("statLines は区切り線を1本も作らない(compose は作る)")
    void statLinesNeverEmitsSeparators() {
        LoreComposer composer = new LoreComposer();

        List<String> lines = plain(composer.statLines(stats(1.0), TABLE, LoreLayout.defaults()));
        assertFalse(lines.stream().anyMatch(line -> line.contains("==")),
                "statLines が区切り線を出している。差し込み先(スレッド返却/リロール)は旧行を"
                        + "内容一致で消すので、幅が変わる線が混ざると消えずに溜まる: " + lines);

        // 対照: compose は同じ入力で区切り線を出す(=この API を分けている理由そのもの)。
        List<String> composed = plain(composer.compose(stats(1.0), TABLE, LoreLayout.defaults()));
        assertTrue(composed.stream().anyMatch(line -> line.contains("==")),
                "compose が区切り線を出さなくなった。テストの前提が変わったので両者の差を再確認すること。");
    }

    @Test
    @DisplayName("値の桁が変わっても statLines の行数と非ステ行の有無は変わらない(差分消去が成立する)")
    void statLinesAreStableAcrossValueWidths() {
        LoreComposer composer = new LoreComposer();

        List<String> narrow = plain(composer.statLines(stats(1.0), TABLE, LoreLayout.defaults()));
        List<String> wide = plain(composer.statLines(stats(123456.0), TABLE, LoreLayout.defaults()));

        assertEquals(3, narrow.size(), "ステ3件ぶんの行だけが返るはず: " + narrow);
        assertEquals(narrow.size(), wide.size(),
                "値の桁で行数が変わっている。幅依存の行(区切り線)が混ざっている証拠: " + wide);
        // 攻撃力の行だけが差し替わり、他の2行は完全一致する = 内容一致の差分消去が使える。
        assertEquals(narrow.subList(1, 3), wide.subList(1, 3),
                "値を変えていないステの行が一致しない(幅依存の描画が混ざっている)");
    }

    @Test
    @DisplayName("statLines も inertStatKeys / hide-when-zero / カテゴリ順を compose と同じに扱う")
    void statLinesSharesTheSameFilteringAsCompose() {
        LoreComposer composer = new LoreComposer();
        composer.useInertStatKeys(() -> Set.of("bleed_damage"));

        List<String> lines = plain(composer.statLines(stats(1.0), TABLE, LoreLayout.defaults()));
        assertFalse(lines.stream().anyMatch(line -> line.contains("出血ダメージ")),
                "config で機構ごと殺したステが statLines から落ちていない: " + lines);

        // 0 のステは hide-when-zero で落ちる。
        Map<String, Double> zeroed = stats(0.0);
        composer.useInertStatKeys(null);
        List<String> withZero = plain(composer.statLines(zeroed, TABLE, LoreLayout.defaults()));
        assertFalse(withZero.stream().anyMatch(line -> line.contains("攻撃力")),
                "0 のステが落ちていない(compose と規約が食い違う): " + withZero);

        // カテゴリ順: ATTACK が DEFENSE より先。
        List<String> ordered = plain(composer.statLines(stats(1.0), TABLE, LoreLayout.defaults()));
        int attack = indexOfContaining(ordered, "攻撃力");
        int defense = indexOfContaining(ordered, "被ダメ軽減");
        assertTrue(attack >= 0 && defense >= 0 && attack < defense,
                "カテゴリ順が compose と違う(ATTACK→DEFENSE の順であること): " + ordered);
    }

    private static int indexOfContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
