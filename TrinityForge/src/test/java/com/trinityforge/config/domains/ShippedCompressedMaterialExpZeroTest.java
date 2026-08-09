package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code stats/skill-exp.yml} の {@code smithing.exp-per-material} で、
 * <b>圧縮素材（{@code custom:<id>_Nx}）の鍛冶EXPが必ず 0</b> であることを固定する（J-12, 2026-08-10）。
 *
 * <p><b>なぜ「行を消す」ではなく「0 と書く」なのか（実装事実）</b>:
 * {@link com.trinityforge.integration.ars.ArsProgressionBridge#grantSmithingCraftExp} は
 * 消費素材が<b>1つでも表に無いと</b>素材合計を丸ごと捨てて
 * {@code ars-smithing.exp-per-craft} の定額（100）へ戻す。
 * つまり圧縮素材の行を削除すると「EXPなし」ではなく
 * <b>「素材価値と無関係な定額EXP」</b>になり、意図とちょうど逆に振れる。
 * 実際 2026-08-10 時点の作業ツリーでは 16 行が削除されており、
 * それを使う儀式 15〜18 件がまとめて定額へ落ちる状態だった。
 *
 * <p>あわせて<b>キーの重複</b>も見る。SnakeYAML は重複キーを<b>後勝ちで黙って通す</b>ので、
 * 同じ素材を2回書いても警告が出ない（2026-08-08 に {@code custom:amethyst_block_2x} が
 * 12 と 1944 の2行で入り、実際には 1944 が効いていた）。
 * 生テキストを走査しないと検出できない種類の壊れ方なので、ここで固定する。
 */
class ShippedCompressedMaterialExpZeroTest {

    private static final String SKILL_EXP = "src/main/resources/stats/skill-exp.yml";

    /** {@code custom:stone_2x} のような圧縮素材のキー。段数は 1x 〜 9x。 */
    private static final Pattern COMPRESSED_KEY = Pattern.compile("^custom:[a-z0-9_]+_\\d+x$");

    /** 表ごと消えた・命名規約が変わったことに気づくための下限。 */
    private static final int MIN_EXPECTED_COMPRESSED_ROWS = 15;

    @Test
    @DisplayName("圧縮素材(_Nx)の鍛冶EXPは全て 0（行を消すと定額EXPへ落ちるので 0 で明示する）")
    void everyCompressedMaterialIsWorthZeroExp() {
        Map<String, Double> table = loadMaterialTable();

        TreeMap<String, Double> nonZero = new TreeMap<>();
        int compressed = 0;
        for (Map.Entry<String, Double> entry : table.entrySet()) {
            if (!COMPRESSED_KEY.matcher(entry.getKey()).matches()) {
                continue;
            }
            compressed++;
            if (entry.getValue() != 0.0) {
                nonZero.put(entry.getKey(), entry.getValue());
            }
        }

        assertTrue(compressed >= MIN_EXPECTED_COMPRESSED_ROWS,
                "圧縮素材の行が " + compressed + " 件しか無い。節ごと消えていないか、"
                        + "命名規約(custom:<id>_Nx)が変わっていないか確認すること"
                        + "(期待: " + MIN_EXPECTED_COMPRESSED_ROWS + " 件以上)");

        assertTrue(nonZero.isEmpty(),
                "圧縮素材に 0 以外の鍛冶EXPが設定されている: " + nonZero
                        + " — 圧縮素材は経験値の対象外(J-12)。"
                        + "圧縮するだけでEXPが増える経路になるので 0 にすること。"
                        + "行を消すのは不可(ArsProgressionBridge が定額EXPへ落とす)。");
    }

    @Test
    @DisplayName("smithing.exp-per-material にキーの重複が無い（SnakeYAML は後勝ちで黙って通す）")
    void materialTableHasNoDuplicateKeys() throws IOException {
        List<String> lines = readSkillExpLines();

        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("exp-per-material:")) {
                start = i + 1;
                break;
            }
        }
        assertTrue(start > 0, SKILL_EXP + " に exp-per-material: の行が無い");

        Pattern row = Pattern.compile("^(\\s+)([^\\s#][^:]*):\\s*[-\\d.]+\\s*$");
        Map<String, List<Integer>> seen = new LinkedHashMap<>();
        Integer indent = null;
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            Matcher matcher = row.matcher(line);
            if (!matcher.matches()) {
                break; // 節の終わり（インデントが戻った、または数値でない行）
            }
            int width = matcher.group(1).length();
            if (indent == null) {
                indent = width;
            } else if (width != indent) {
                break;
            }
            seen.computeIfAbsent(matcher.group(2).trim(), key -> new ArrayList<>()).add(i + 1);
        }

        assertNotNull(indent, "exp-per-material: の直後に素材行が1つも無い");

        TreeMap<String, List<Integer>> duplicates = new TreeMap<>();
        seen.forEach((key, at) -> {
            if (at.size() > 1) {
                duplicates.put(key, at);
            }
        });

        assertTrue(duplicates.isEmpty(),
                "smithing.exp-per-material にキーの重複がある(キー -> 行番号): " + duplicates
                        + " — SnakeYAML は後勝ちで黙って通すので、"
                        + "上に書いた値が無視されていることに気づけない。片方を消すこと。");
    }

    private static Map<String, Double> loadMaterialTable() {
        File file = new File(SKILL_EXP);
        assertTrue(file.isFile(), "出荷 skill-exp.yml が見つからない: " + file.getAbsolutePath());
        ConfigurationSection table = YamlConfiguration.loadConfiguration(file)
                .getConfigurationSection("smithing.exp-per-material");
        assertNotNull(table, SKILL_EXP + " に smithing.exp-per-material 節が無い");
        Map<String, Double> values = new LinkedHashMap<>();
        for (String key : table.getKeys(false)) {
            values.put(key, table.getDouble(key));
        }
        return values;
    }

    private static List<String> readSkillExpLines() throws IOException {
        File file = new File(SKILL_EXP);
        assertTrue(file.isFile(), "出荷 skill-exp.yml が見つからない: " + file.getAbsolutePath());
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }
}
