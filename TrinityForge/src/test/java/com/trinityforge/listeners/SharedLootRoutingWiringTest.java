package com.trinityforge.listeners;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MobOverrideDropListener} が「確定ドロップは全員へ配る」経路を<b>まだ呼んでいる</b>ことを固定する。
 *
 * <p><b>なぜ挙動テストで書けないか。</b> 分配先を決めるのは EliteMobs 側
 * ({@code TrinityForgeSharedLoot}) で、TF からはリフレクション越しの soft bridge しか無い。
 * テスト環境には EliteMobs が居ないので {@code deliver} も {@code deliverToEveryDamager} も
 * 等しく {@code event.getDrops()} へ落ちる ＝ <b>どちらを呼んでも観測結果が同じ</b>。
 * したがって「呼んでいること」自体を固定するしかない。分配の中身は
 * {@code MobDropRollerTest#onlyGuaranteedDropsCountAsProgression}(判定)と、フォーク側の
 * {@code SharedLootTableTrinityForgeTest}(署名と実装)が受け持つ。
 *
 * <p><b>この検査の限界。</b> 定数プールはクラス単位でしか見ないので、if/else の分岐を<b>入れ替えた</b>
 * 場合(確定ドロップを need/greed へ、それ以外を全員配布へ)は素通りする。捕まえられるのは
 * 「全員配布経路を丸ごと消した」「判定を呼ばなくなった」の2つ。実際に起きた事故がその形
 * (2026-08-09 に全ドロップを need/greed へ一本化して進行アイテムが1人にしか渡らなくなった)なので、
 * そこだけを狙って固定する。
 */
class SharedLootRoutingWiringTest {

    @Test
    @DisplayName("ダンジョンボス表は確定ドロップを全員配布経路へ回している")
    void mobOverrideDropListenerAsksForThePerPlayerRoute() throws IOException {
        String bytecode = constantPoolOf("com/trinityforge/listeners/MobOverrideDropListener");
        for (String reference : new String[]{"isProgressionDrop", "deliverToEveryDamager"}) {
            assertTrue(bytecode.contains(reference),
                    "MobOverrideDropListener が " + reference + " を参照しなくなっている。"
                            + "確定ドロップ(ダンジョン印・試練の鍵)が need/greed の抽選に戻ると、"
                            + "複数人で潜ったとき片方が次の試練に入れず図鑑も埋まらない。"
                            + "意図して移設したならこのアサーションも一緒に移すこと。");
        }
    }

    @Test
    @DisplayName("レベル帯テーブルには全員配布経路を通していない")
    void mobLevelTableListenerStaysOnTheSharedTable() throws IOException {
        // add-drops は帯ごとのフィールド報酬で、確定でも「全員に配ってよいもの」ではない
        // (dragon_scale = chance 1.0 / min 0 max 2 / where: field)。ここへ全員配布を広げると
        // パーティ人数ぶん素材が増える。
        String bytecode = constantPoolOf("com/trinityforge/listeners/MobLevelTableListener");
        assertTrue(!bytecode.contains("deliverToEveryDamager"),
                "MobLevelTableListener が全員配布経路を呼んでいる。add-drops の確定ドロップは"
                        + "進行アイテムではないので、人数ぶん配ってはいけない。");
    }

    /** 生バイト列を ISO-8859-1 で読む(1バイト=1文字なので定数プールの文字列がそのまま探せる)。 */
    private static String constantPoolOf(String internalName) throws IOException {
        String resource = internalName + ".class";
        try (InputStream in = SharedLootRoutingWiringTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "compiled class not found on the test classpath: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
