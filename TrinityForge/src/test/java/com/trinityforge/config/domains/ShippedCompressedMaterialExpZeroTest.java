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
 * 出荷 {@code stats/skill-exp.yml} の素材別鍛冶EXP表で、
 * <b>圧縮素材（{@code custom:<id>_Nx}）のEXPが必ず 0</b> であることを固定する（J-12, 2026-08-10）。
 * 表は2本ある（儀式/Ars用の {@code ars-smithing.exp-per-material} と作業台用の
 * {@code smithing.exp-per-material}）ので、<b>両方</b>を見る。
 *
 * <p><b>なぜ 0 が要るか</b>: 圧縮素材は「素材をブロックに固めただけ」なので、圧縮してから
 * クラフトするとEXPが増える経路を作ってはいけない（ユーザー判断「圧縮素材は経験値不要」）。
 *
 * <p><b>2026-08-17 に緩めた点</b>: 以前はここで「行を消すのは不可」も固定していた。
 * 当時 {@code ArsProgressionBridge#grantSmithingCraftExp} は消費素材が1つでも表に無いと
 * 素材合計を丸ごと捨てて {@code ars-smithing.exp-per-craft} の定額（100）へ戻したので、
 * <b>0 と書くのと行を消すのが逆向きの意味</b>になっていた（消すと定額へ跳ね上がる）。
 * その定額を機能ごと廃止したため、いまは「行が無い＝0で積む」で一致する。
 * よって行数の下限は見ない。<b>0 以外の値が入ることだけ</b>が今も実害のある壊れ方。
 *
 * <p>あわせて<b>キーの重複</b>も見る。SnakeYAML は重複キーを<b>後勝ちで黙って通す</b>ので、
 * 同じ素材を2回書いても警告が出ない（2026-08-08 に {@code custom:amethyst_block_2x} が
 * 12 と 1944 の2行で入り、実際には 1944 が効いていた）。
 * 生テキストを走査しないと検出できない種類の壊れ方なので、ここで固定する。
 */
class ShippedCompressedMaterialExpZeroTest {

    private static final String SKILL_EXP = "src/main/resources/stats/skill-exp.yml";

    /** 素材別EXP表の節パス。儀式用と作業台用の2本。 */
    private static final List<String> MATERIAL_TABLES =
            List.of("ars-smithing.exp-per-material", "smithing.exp-per-material");

    /** {@code custom:stone_2x} のような圧縮素材のキー。段数は 1x 〜 9x。 */
    private static final Pattern COMPRESSED_KEY = Pattern.compile("^custom:[a-z0-9_]+_\\d+x$");

    @Test
    @DisplayName("圧縮素材(_Nx)の鍛冶EXPは儀式表・作業台表ともに 0(圧縮するだけでEXPが増えない)")
    void everyCompressedMaterialIsWorthZeroExp() {
        for (String path : MATERIAL_TABLES) {
            Map<String, Double> table = loadMaterialTable(path);

            TreeMap<String, Double> nonZero = new TreeMap<>();
            for (Map.Entry<String, Double> entry : table.entrySet()) {
                if (COMPRESSED_KEY.matcher(entry.getKey()).matches() && entry.getValue() != 0.0) {
                    nonZero.put(entry.getKey(), entry.getValue());
                }
            }

            assertTrue(nonZero.isEmpty(),
                    path + " の圧縮素材に 0 以外の鍛冶EXPが設定されている: " + nonZero
                            + " — 圧縮素材は経験値の対象外(J-12)。"
                            + "圧縮するだけでEXPが増える経路になるので 0 にすること"
                            + "(不要なら行を消してもよい。2026-08-17 に定額フォールバックを廃止したので"
                            + "「行が無い」と「0」は同じ意味になった)。");
        }
    }

    @Test
    @DisplayName("素材別EXP表にキーの重複が無い(SnakeYAML は後勝ちで黙って通す)")
    void materialTablesHaveNoDuplicateKeys() throws IOException {
        List<String> lines = readSkillExpLines();

        // exp-per-material: は儀式用と作業台用の2箇所に出る。両方走査する。
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("exp-per-material:")) {
                starts.add(i + 1);
            }
        }
        assertTrue(starts.size() >= MATERIAL_TABLES.size(),
                SKILL_EXP + " の exp-per-material: 節が " + starts.size() + " 箇所しかない"
                        + "(期待: " + MATERIAL_TABLES.size() + " 箇所 ―― 儀式用と作業台用)。"
                        + "節が消えていないか、片方だけ改名されていないか確認すること");

        Pattern row = Pattern.compile("^(\\s+)([^\\s#][^:]*):\\s*[-\\d.]+\\s*$");
        for (int start : starts) {
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

            assertNotNull(indent, SKILL_EXP + ":" + start + " の exp-per-material: の直後に素材行が1つも無い");

            TreeMap<String, List<Integer>> duplicates = new TreeMap<>();
            seen.forEach((key, at) -> {
                if (at.size() > 1) {
                    duplicates.put(key, at);
                }
            });

            assertTrue(duplicates.isEmpty(),
                    SKILL_EXP + ":" + start + " から始まる exp-per-material にキーの重複がある"
                            + "(キー -> 行番号): " + duplicates
                            + " — SnakeYAML は後勝ちで黙って通すので、"
                            + "上に書いた値が無視されていることに気づけない。片方を消すこと。");
        }
    }

    private static Map<String, Double> loadMaterialTable(String path) {
        File file = new File(SKILL_EXP);
        assertTrue(file.isFile(), "出荷 skill-exp.yml が見つからない: " + file.getAbsolutePath());
        ConfigurationSection table = YamlConfiguration.loadConfiguration(file)
                .getConfigurationSection(path);
        assertNotNull(table, SKILL_EXP + " に " + path + " 節が無い");
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
