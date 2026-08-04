package com.trinityforge.config.domains;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-04 のカタログID改名（{@code tf_core_wood/meat/vegetable/jewelry/dirt} →
 * {@code core_wood/meat/vegetable/jewelry/ground}、{@code tf_gacha_ticket[_1..5]} →
 * {@code gacha_ticket_0..5}）で、出荷 yml のどこにも旧IDが残っていないことを固定する。
 *
 * <h2>なぜ「1件でも残ったら赤」なのか</h2>
 * {@code CrossPluginItemResolver} は完全一致でしか解決しない（接頭辞剥がしのような救済ロジックは無い）。
 * 旧IDが1件でも残ると、そのレシピ・ドロップ・報酬・EXP表だけが<b>無言で解決失敗</b>する
 * （例外にならず、ログにも出ないことがある）。改名は完了しているので、以後この文字列が
 * 出荷ymlへ再混入したら即座に検出する。
 *
 * <p>2026-08-04 時点で確認した実在ID（{@code fork-handoff/arspaper/fork/src/main/resources/materials.yml}）:
 * {@code core_wood} / {@code core_meat} / {@code core_vegetable} / {@code core_jewelry} /
 * {@code core_ground}（旧 {@code tf_core_dirt}）/ {@code gacha_ticket_0}〜{@code gacha_ticket_5}
 * （旧 {@code tf_gacha_ticket} / {@code tf_gacha_ticket_1}〜{@code _5}）。
 */
class ShippedLegacyCatalogIdDriftTest {

    private static final Path RESOURCES_ROOT = Path.of("src/main/resources");

    /**
     * 旧ID文字列。接尾辞の有無を問わず検出するため、あえて単純な部分一致にしている
     * （{@code tf_gacha_ticket_5} も {@code tf_gacha_ticket} を含むので拾える）。
     */
    private static final Pattern LEGACY_ID_PATTERN =
            Pattern.compile("tf_core_(?:wood|meat|vegetable|jewelry|dirt)|tf_gacha_ticket");

    @Test
    @DisplayName("出荷ymlのどこにも旧カタログID(tf_core_* / tf_gacha_ticket*)が残っていない")
    void noShippedYamlReferencesLegacyRenamedCatalogIds() throws IOException {
        assertTrue(Files.isDirectory(RESOURCES_ROOT),
                "出荷リソースディレクトリが見つからない: " + RESOURCES_ROOT.toAbsolutePath());

        List<String> hits = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(RESOURCES_ROOT)) {
            List<Path> ymlFiles = paths
                    .filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
            for (Path file : ymlFiles) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = LEGACY_ID_PATTERN.matcher(lines.get(i));
                    if (m.find()) {
                        hits.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            }
        }

        assertTrue(hits.isEmpty(),
                "旧カタログID(tf_core_wood/meat/vegetable/jewelry/dirt, tf_gacha_ticket[_1..5])が "
                        + hits.size() + " 件残っている。2026-08-04 の改名で "
                        + "core_wood/meat/vegetable/jewelry/ground, gacha_ticket_0..5 へ統一済みのため、"
                        + "この文字列が残っていると CrossPluginItemResolver が無言で解決失敗する:\n"
                        + String.join("\n", hits));
    }
}
