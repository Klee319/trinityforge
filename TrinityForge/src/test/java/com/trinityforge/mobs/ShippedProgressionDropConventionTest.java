package com.trinityforge.mobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code combat/mob-overrides.yml} が「確定ドロップ＝進行アイテム」という規約を保っているかを見る。
 *
 * <p><b>なぜ必要か(2026-08-18)。</b> {@code MobOverrideDropListener} は複数人ダンジョンでの分配先を
 * {@link MobDropRoller#isProgressionDrop} ＝ yml の {@code chance} が 1.0 かどうかで切り替える。
 * 確定なら EliteMobs の need/greed 抽選(当選者1人)ではなく<b>ダメージ寄与者全員に1個ずつ</b>渡す。
 * この分岐が正しいのは「この表の確定ドロップはダンジョン印・試練の鍵・かけらしか無い」という
 * 現状の書かれ方に依存している。あとから確定ドロップでバニラ素材(骨や鉄など)を足すと、
 * <b>パーティ人数ぶんアイテムが増える無言のインフレ</b>になる。
 *
 * <p>そこで「確定ドロップは {@code custom:} のカタログ品だけ」を機械的に固定する。落ちたときは
 * 「その行を人数ぶん配ってよいのか」を考える合図であって、単にこのテストを直す作業ではない。
 * 全員配布が不適切なら {@code chance} を 1.0 未満にするか、この表ではなく
 * {@code combat/mob-level-table.yml} の {@code add-drops} 側へ置くこと(あちらは
 * {@code dragon_scale} のように確定でもフィールド報酬なので、全員配布経路を通していない)。
 */
class ShippedProgressionDropConventionTest {

    private static final String RESOURCE = "/combat/mob-overrides.yml";

    /** {@code - { item: "custom:x", chance: 1.0, ... }} の item と chance を1行から取る。 */
    private static final Pattern DROP_LINE = Pattern.compile(
            "item:\\s*\"?([^\",]+?)\"?\\s*,\\s*chance:\\s*([0-9.]+)");

    @Test
    @DisplayName("確定ドロップはすべて custom: のカタログ品(＝人数ぶん配ってよいもの)")
    void everyGuaranteedDropIsACatalogItem() throws IOException {
        List<String> guaranteed = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (String line : shippedLines()) {
            Matcher matcher = DROP_LINE.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            double chance = Double.parseDouble(matcher.group(2));
            if (!MobDropRoller.isProgressionDrop(chance)) {
                continue;
            }
            String item = matcher.group(1).trim();
            guaranteed.add(item);
            if (!item.startsWith("custom:")) {
                offenders.add(line.trim());
            }
        }

        // 前提そのものが消えていないか(表を書き換えて確定ドロップが1件も無くなったらこの検査は無意味)。
        assertTrue(guaranteed.size() >= 30,
                "確定ドロップが " + guaranteed.size() + " 件しか無い。ダンジョン印/試練の鍵の配線が"
                        + "消えていないか確認すること(2026-08-18 時点で 39 件)");
        assertTrue(offenders.isEmpty(),
                "確定ドロップにバニラ素材が混ざっている。この表の確定ドロップは複数人ダンジョンで"
                        + "【ダメージ寄与者全員に1個ずつ】配られるので、人数ぶん増えてよいものか判断すること:\n"
                        + String.join("\n", offenders));
    }

    private static List<String> shippedLines() throws IOException {
        try (InputStream in = ShippedProgressionDropConventionTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "出荷 yml がテストのクラスパスに無い: " + RESOURCE);
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R"));
        }
    }
}
