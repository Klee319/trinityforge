package com.trinityforge.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/config-reference/stats/lore.md}(yml 本文コメントの退避台帳)のアンカーが
 * {@code stats/lore.yml} に実在することを機械照合する (2026-07-31 レビュー指摘5)。
 *
 * <p>なぜ必要か: config-editor で保存すると yml の本文コメントは復元されないため、
 * このリポジトリは本文コメントを md 側へ退避する運用になっている
 * ({@code lib/yamlio.js} / CLAUDE.md)。台帳は
 * {@code ### 直後: `<yml の行>`} という形でアンカー行を持つが、
 * <b>キーを廃止・改名するとアンカー行だけが消えて宛先不明のコメントが残る</b>
 * (実際 {@code name: 弓CT短縮} が廃止された後もその項が残っていた)。
 * 将来コメントを yml へ戻すとき、宛先不明の項は行き先が決められない。
 *
 * <p>アンカーは {@code lore.yml} の行を前後の空白を除いて一致比較する。
 * 台帳は「どのキーのコメントか」を一意に指せないと意味がないので、重複アンカーも失敗させる。
 */
class LoreCommentLedgerDriftTest {

    private static final Pattern ANCHOR = Pattern.compile("^### 直後: `([^`]+)`", Pattern.MULTILINE);

    @Test
    @DisplayName("lore.md の全アンカーが lore.yml に実在し、重複していない")
    void everyLedgerAnchorResolvesToAShippedLine() throws Exception {
        Set<String> ymlLines = new HashSet<>();
        try (InputStream in = LoreCommentLedgerDriftTest.class.getClassLoader()
                .getResourceAsStream("stats/lore.yml")) {
            assertNotNull(in, "出荷リソース stats/lore.yml が見つからない");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)) {
                ymlLines.add(line.strip());
            }
        }
        // 空振り防止のアンカー(現役の行で読み込み自体が成立していることを確認する)。
        assertTrue(ymlLines.contains("name: 矢ノックバック"),
                "lore.yml を行単位で読めていない(この照合は空振りしている)");

        Path ledger = ledgerPath();
        String md = Files.readString(ledger, StandardCharsets.UTF_8);

        List<String> unresolved = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = ANCHOR.matcher(md);
        int anchors = 0;
        while (m.find()) {
            String anchor = m.group(1).strip();
            anchors++;
            if (!seen.add(anchor)) {
                duplicated.add(anchor);
            }
            if (!ymlLines.contains(anchor)) {
                unresolved.add(anchor);
            }
        }
        assertTrue(anchors > 10, "lore.md からアンカーを抽出できていない(抽出数=" + anchors + ")");
        assertEquals(List.of(), unresolved,
                "lore.md のアンカーが lore.yml に無い(廃止・改名でコメントの宛先が消えている): " + ledger);
        assertEquals(List.of(), duplicated, "lore.md に同じアンカーの項が2つ以上ある(どちらが正か決められない)");
    }

    /**
     * 台帳の位置を解決する。Gradle の test 作業ディレクトリは {@code TrinityForge/} なので
     * まず {@code ../docs/...} を見る(フォークと違い docs/ は tracked なので必ず存在する)。
     */
    private static Path ledgerPath() {
        Path relative = Path.of("..", "docs", "config-reference", "stats", "lore.md");
        if (Files.isRegularFile(relative)) {
            return relative;
        }
        Path fromRoot = Path.of("docs", "config-reference", "stats", "lore.md");
        assertTrue(Files.isRegularFile(fromRoot),
                "docs/config-reference/stats/lore.md が見つからない(作業ディレクトリ="
                        + Path.of("").toAbsolutePath() + ")");
        return fromRoot;
    }
}
