package com.trinityforge.progression.catalog;

import com.trinityforge.farming.FarmingCropCatalog;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 出荷 {@code skills/base/farming_progression.yml} の農業EXPを実データで固定する drift 検出テスト
 * （2026-08-21 / W-180「農業の経験値効率が良すぎる」）。
 *
 * <h2>なぜ要るか — 農業EXPの間違いは全部「無言」で出る</h2>
 * <ul>
 *   <li><b>一括収穫(area-harvest)は連鎖した1ブロックごとに単価を丸ごと配る。</b>
 *       半径4の tier なら1回の収穫で最大81ブロックぶん入るので、<b>単価を1つ書き戻すだけで
 *       農業だけ他スキルと桁が変わる</b>。しかも例外は出ない。</li>
 *   <li><b>一括収穫の対象作物は Java 側（{@link FarmingCropCatalog#CROPS}）が持っている。</b>
 *       そこへ作物を足しても yml の表に行が無ければ<b>その作物だけEXP 0</b>。
 *       ゼロは「行が無い」と区別できないので、config を読んでも気づけない。</li>
 *   <li><b>討伐EXPを弱めようとして {@code entity_breed} を触ると繁殖EXPまで巻き添えになる。</b>
 *       しかも {@code entity_breed} は討伐EXPの<b>ゲート</b>でもあるので
 *       （{@code NativeSkillExperienceListener#onFarmingMobDeath}）、0 を書くと
 *       <b>その種の討伐EXPが丸ごと消える</b>。</li>
 * </ul>
 */
class ShippedFarmingExpCompressionTest {

    private static final NativeSkillCatalog CATALOG =
            NativeSkillCatalog.load(ShippedFarmingExpCompressionTest.class.getClassLoader());

    /**
     * 一括収穫が対象にする作物1ブロックあたりの農業EXP。
     *
     * <p>2026-08-21（W-180）にユーザー要望で<b>それまでの 48 の 40% = 19.2</b> へ圧縮した。
     * 5種を同値に揃えてあるのは、どれを植えても収穫効率が同じになるようにするため
     * （2026-08-21 の直前に 40/40/10 から 48 へ統一されたのを引き継いでいる）。
     *
     * <p><b>意図的に調整するときはここを書き換える。</b> 定数を動かさずに yml だけ動かすと落ちる
     * ── それがこのテストの目的で、「気づかないうちに戻っていた」を防ぐためにある。
     */
    private static final double AREA_HARVEST_CROP_EXP = 19.2;

    @Test
    @DisplayName("一括収穫の対象作物5種すべてに block_drops の行があり、同じ単価に揃っている")
    void everyAreaHarvestCropCarriesTheCompressedRate() {
        SkillCatalogEntry farming = CATALOG.get(SkillId.FARMING);
        assertNotNull(farming, "FARMING の catalog エントリが読めていない");

        List<String> problems = new ArrayList<>();
        for (Material crop : new TreeSet<>(FarmingCropCatalog.CROPS)) {
            double exp = farming.expFor("block_drops", crop.name());
            if (exp != AREA_HARVEST_CROP_EXP) {
                problems.add(crop.name() + "=" + (exp <= 0.0 ? "行が無い(EXP 0)" : String.valueOf(exp)));
            }
        }
        assertEquals(List.of(), problems,
                "一括収穫の対象作物の農業EXPが " + AREA_HARVEST_CROP_EXP + " から外れている: " + problems + "。"
                        + "対象一覧は FarmingCropCatalog.CROPS が持っているので、"
                        + "【そちらへ作物を足したら yml の block_drops にも行を足す】こと —— "
                        + "行が無いとその作物だけ農業EXPが 0 になり、例外もログも出ない。"
                        + "単価を意図的に変えるときは AREA_HARVEST_CROP_EXP も一緒に動かすこと。"
                        + "一括収穫は連鎖した1ブロックごとにこの値を丸ごと配る(半径4で最大81ブロック)ので、"
                        + "ここを戻すと農業だけ他スキルと桁が変わる。");
    }

    /**
     * {@code entity_breed} は繁殖EXPの表であると同時に<b>家畜討伐EXPのゲート</b>でもある
     * （{@code NativeSkillExperienceListener#onFarmingMobDeath} は
     * {@code entity_breed} の値が 0 以下の種を討伐EXPの対象から外す）。
     * 「討伐EXPを下げたい」でここを 0 にすると、繁殖EXPと討伐EXPが<b>両方まとめて消える</b>。
     * 討伐だけを触りたいときの正しい置き場は {@code entity_drops}。
     */
    @Test
    @DisplayName("entity_breed に 0 の種が居ない(0 は繁殖EXPと討伐EXPを同時に殺すゲート)")
    void breedTableHasNoZeroGates() {
        SkillCatalogEntry farming = CATALOG.get(SkillId.FARMING);
        List<String> zeroed = valuesOf(farming, "entity_breed").entrySet().stream()
                .filter(e -> e.getValue() <= 0.0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        assertEquals(List.of(), zeroed,
                "entity_breed に 0 の種がある: " + zeroed + "。"
                        + "この表は繁殖EXPの表であると同時に【家畜討伐EXPのゲート】なので、"
                        + "0 を書くとその種は繁殖EXPも討伐EXPも両方 0 になる。"
                        + "討伐EXPだけを調整したいときは entity_drops を触ること。");
    }

    @Test
    @DisplayName("entity_drops に 0 の行が無い(0 行は「書いたのに効かない」と見分けが付かない)")
    void killDropTableHasNoZeroRows() {
        SkillCatalogEntry farming = CATALOG.get(SkillId.FARMING);
        List<String> zeroed = valuesOf(farming, "entity_drops").entrySet().stream()
                .filter(e -> e.getValue() <= 0.0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        assertEquals(List.of(), zeroed,
                "entity_drops に 0 の行がある: " + zeroed + "。"
                        + "0 は「行が無い」と完全に同じ挙動なので、残しても誤解を生むだけ。"
                        + "その品でEXPを出したくないなら行ごと消すこと。");
    }

    /** {@code actionExp} の {@code <action>.<MATERIAL>} キーから、1つの action ぶんだけ取り出す。 */
    private static Map<String, Double> valuesOf(SkillCatalogEntry entry, String action) {
        String prefix = action + ".";
        return entry.actionExp().entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().substring(prefix.length()),
                        Map.Entry::getValue));
    }
}
