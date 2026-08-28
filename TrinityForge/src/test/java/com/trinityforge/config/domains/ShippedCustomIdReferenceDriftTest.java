package com.trinityforge.config.domains;

import com.trinityforge.testsupport.KnownCustomItemIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code src/main/resources/**&#47;*.yml} 全体に散らばる {@code custom:<id>} 参照が、
 * <b>どのファイルに書かれていても</b>既知の custom アイテムID({@link KnownCustomItemIds})で
 * 実際に解決できることを固定する drift ガード。
 *
 * <h2>なぜこのテストが要るのか</h2>
 * <p>{@code items/catalog.yml} からアイテムを消しても(あるいは ID を書き間違えても)、それを
 * {@code custom:<id>} で参照している側 —— ドロップ表・レシピ・醸造・図鑑・EXP表など ——
 * は<b>誰も文句を言わない</b>。実行時は「解決できなかった」という WARNING 1 行でその抽選/
 * レシピが黙って捨てられるだけなので、プレイヤーからは「(そのアイテムが)落ちない」
 * 「(そのレシピで)作れない」としか見えない。
 *
 * <p>実例が {@code hoglin_tusk}: 改名で {@code hoglin_fang} に差し替わったのに、旧IDへの参照が
 * 消し忘れのまま出荷 yml に残り続けていた(2026-08-16 に発覚。当時はリソースパックのアート削除で
 * ようやく気づけた)。ドメインをまたいだ全走査でないと、この種の消し忘れは机上の grep 頼みになる。
 *
 * <h2>なぜコメントを除去してから拾うのか</h2>
 * <p>{@code stats/food-gimmick.yml} の説明コメントは {@code custom:<id>} トークンの書式を
 * 「例: custom:tf_rotten_ration」という<b>実在しない ID</b>で説明している。同様に
 * {@code combat/mob-level-table.yml} のヘッダコメントにも {@code custom:gacha_ticket_0} を
 * 使った書式説明が並ぶ。コメントを外さずに拾うと、このガードは<b>初日から赤くなって
 * 無効化される</b>(=「常に失敗するテストは無視されるようになる」という別種の無言死)。
 * 除去規則は「行頭、または空白の直後に来る {@code #} 以降を切り捨てる」だけに絞っている
 * ({@link #stripComment(String)})。値中の {@code #FFFFFF} のような16進カラーコードは
 * 直前が {@code '} や {@code "} で空白ではないため切り捨てられない —— この2つの取り違えを
 * 混同すると「カラーコード以降が消えて YAML が壊れる」か「コメント中の偽IDを本物として
 * 誤検知する」のどちらかに転ぶので、判定基準はこの1点に集約している。
 *
 * <h2>空振り防止</h2>
 * <p>許可リスト方式のこの手のテストは、走査対象や正規表現が壊れて0件ヒットになっても
 * 「差分なしだから緑」で通ってしまう(このリポジトリで実際に踏んだ事故クラス)。
 * 走査したファイル数と拾った {@code custom:<id>} 参照の種類数それぞれに実測値ベースの
 * 下限を assert し、パス崩れ・正規表現崩れを検出可能にしている
 * (2026-08-16 時点の実測: 出荷 yml 79 本 / {@code custom:} 参照 222 種)。
 */
class ShippedCustomIdReferenceDriftTest {

    private static final Path RESOURCES_ROOT = Path.of("src/main/resources");
    private static final Pattern CUSTOM_ID = Pattern.compile("custom:([A-Za-z0-9_]+)");

    @Test
    void everyCustomIdReferenceInShippedYamlResolvesToAKnownItem() throws IOException {
        KnownCustomItemIds.Result knownResult = KnownCustomItemIds.load();
        Set<String> known = knownResult.ids();
        assertTrue(known.size() >= 100,
                "custom アイテムIDの一覧を読めていない(パスが壊れている?): " + known.size() + " 件"
                        + " / モード=" + knownResult.describe());

        List<Path> files = collectYamlFiles(RESOURCES_ROOT);
        assertTrue(files.size() >= 50,
                "出荷 yml の走査に失敗している(RESOURCES_ROOT が壊れている?): " + files.size() + " 本 / "
                        + RESOURCES_ROOT.toAbsolutePath());

        List<Reference> references = new ArrayList<>();
        for (Path file : files) {
            references.addAll(findCustomIdReferences(file));
        }
        Set<String> distinctIds = references.stream().map(Reference::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        assertTrue(distinctIds.size() >= 200,
                "custom:<id> 参照の走査結果が少なすぎる(正規表現かコメント除去が壊れている?): "
                        + distinctIds.size() + " 種 / 走査ファイル " + files.size() + " 本");

        List<String> unresolvable = new ArrayList<>();
        for (Reference ref : references) {
            if (known.contains(ref.id())) {
                continue;
            }
            String hint = known.stream()
                    .filter(id -> id.equalsIgnoreCase(ref.id()))
                    .findFirst()
                    .map(id -> " — 大小違いの候補あり: '" + id + "'")
                    .orElse(" — どのレジストリにも存在しない");
            unresolvable.add(RESOURCES_ROOT.relativize(ref.file()) + ":" + ref.lineNumber()
                    + " — custom:" + ref.id() + hint);
        }

        assertEquals(List.of(), unresolvable,
                "出荷 yml が、出荷設定のどこにも定義が無い custom:<id> を参照している。解決は実行時にしか"
                        + "行われず、失敗しても WARNING 1行でその抽選/レシピ/効果が捨てられるだけなので、"
                        + "プレイヤーからは『落ちない』『作れない』としか見えない。IDのタイプミス・"
                        + "改名時の消し忘れ(実例: hoglin_tusk → hoglin_fang)を疑うこと"
                        + " / モード=" + knownResult.describe());
    }

    /**
     * ガード本体の検出ロジックが「実際に壊れを検出し、直せば緑に戻る」ことを自己完結で示す
     * (修正前に戻すと落ちる、を出荷 yml に触らずに実演する)。他セッションが出荷 yml を
     * 並行して編集しているワークツリーなので、実物を一時的に書き換える形の実証は避けている。
     */
    @Test
    void detectionLogicCatchesAnInjectedUnknownIdAndClearsOnceFixed(@TempDir Path tempDir) throws IOException {
        Set<String> known = KnownCustomItemIds.load().ids();
        String realId = known.stream().filter(id -> id.matches("[A-Za-z0-9_]+")).findFirst()
                .orElseThrow(() -> new AssertionError("既知IDから正規表現に合う例を1件も拾えなかった"));

        Path broken = tempDir.resolve("broken.yml");
        Files.writeString(broken, """
                # コメント中の例: custom:this_is_not_a_real_item_id_in_comment
                add-drops:
                  - { material: "custom:%s", chance: 0.1 }
                  - { material: "custom:definitely_not_a_real_item_id_zzz", chance: 0.1 }
                """.formatted(realId), StandardCharsets.UTF_8);

        List<Reference> brokenRefs = findCustomIdReferences(broken);
        List<String> brokenUnresolved = brokenRefs.stream()
                .filter(ref -> !known.contains(ref.id()))
                .map(Reference::id)
                .toList();
        assertEquals(List.of("definitely_not_a_real_item_id_zzz"), brokenUnresolved,
                "コメント中の偽IDは無視しつつ、実参照側の未知IDだけを検出できていない");

        Path fixed = tempDir.resolve("fixed.yml");
        Files.writeString(fixed, """
                # コメント中の例: custom:this_is_not_a_real_item_id_in_comment
                add-drops:
                  - { material: "custom:%s", chance: 0.1 }
                """.formatted(realId), StandardCharsets.UTF_8);

        List<Reference> fixedRefs = findCustomIdReferences(fixed);
        List<String> fixedUnresolved = fixedRefs.stream()
                .filter(ref -> !known.contains(ref.id()))
                .map(Reference::id)
                .toList();
        assertEquals(List.of(), fixedUnresolved, "未知IDを取り除いた後まで検出が残っている(誤検知)");
    }

    /**
     * 2026-08-16 レビュー指摘: 「{@code materials.yml} が無いときは台帳フォールバックで検査が
     * 弱くなる」という挙動そのものを固定する。catalog から削除済みの id(=materials には無いが
     * 台帳にだけ残っている id)を用意し、{@link com.trinityforge.testsupport.KnownCustomItemIds.Mode#STRICT}
     * では検出され、{@link com.trinityforge.testsupport.KnownCustomItemIds.Mode#LEDGER_FALLBACK}
     * では素通りすることを両方示す。
     */
    @Test
    void ledgerFallbackAcceptsDeletedIdsThatStrictModeWouldCatch(@TempDir Path tempDir) throws IOException {
        Path catalog = tempDir.resolve("catalog.yml");
        Files.writeString(catalog, """
                items:
                  surviving_item:
                    material: STONE
                """, StandardCharsets.UTF_8);

        Path materials = tempDir.resolve("materials.yml");
        Files.writeString(materials, """
                materials:
                  surviving_item:
                    material: STONE
                """, StandardCharsets.UTF_8);

        // 台帳には「かつて存在した」deleted_item の割当が残っている。
        // reconcile 前の ledger フォールバック経路は残骸 id を既知として通す。
        Path ledger = tempDir.resolve("cmd-registry.json");
        Files.writeString(ledger, """
                [
                  { "id": "surviving_item" },
                  { "id": "deleted_item" }
                ]
                """, StandardCharsets.UTF_8);

        KnownCustomItemIds.Result strict = KnownCustomItemIds.loadFrom(catalog.toFile(), materials.toFile(),
                ledger.toFile());
        assertEquals(KnownCustomItemIds.Mode.STRICT, strict.mode());
        assertFalse(strict.ids().contains("deleted_item"),
                "STRICT モード(materials.yml あり)は削除済み id を既知集合に含めてはいけない");
        assertTrue(strict.ids().contains("surviving_item"));

        Path missingMaterials = tempDir.resolve("no-such-materials.yml"); // わざと存在しないパス
        KnownCustomItemIds.Result fallback = KnownCustomItemIds.loadFrom(catalog.toFile(), missingMaterials.toFile(),
                ledger.toFile());
        assertEquals(KnownCustomItemIds.Mode.LEDGER_FALLBACK, fallback.mode());
        assertTrue(fallback.ids().contains("deleted_item"),
                "LEDGER_FALLBACK モード(materials.yml 無し)は台帳の残骸を既知として通す"
                        + "(=このモードでは削除検出が効かないことの実演)");
    }

    // ------------------------------------------------------------------------------------------
    // 検出ロジック(実 yml とテスト用 temp yml の両方から呼ぶ)
    // ------------------------------------------------------------------------------------------

    private record Reference(Path file, int lineNumber, String id) {
    }

    private static List<Path> collectYamlFiles(Path root) throws IOException {
        assertTrue(Files.isDirectory(root), "出荷 yml のルートが見つからない: " + root.toAbsolutePath());
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        }
    }

    private static List<Reference> findCustomIdReferences(Path file) throws IOException {
        List<Reference> found = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String code = stripComment(lines.get(i));
            Matcher matcher = CUSTOM_ID.matcher(code);
            while (matcher.find()) {
                found.add(new Reference(file, i + 1, matcher.group(1)));
            }
        }
        return found;
    }

    /**
     * 行頭、または空白の直後に来る {@code #} 以降を切り捨てる。それ以外の {@code #}
     * (例: {@code '#FFFFFF'} のような16進カラーコード。直前が引用符で空白ではない)は残す。
     */
    private static String stripComment(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }
}
