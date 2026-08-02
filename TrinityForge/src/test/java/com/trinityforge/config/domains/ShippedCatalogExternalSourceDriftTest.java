package com.trinityforge.config.domains;

import com.trinityforge.stats.ItemTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code items/catalog.yml} の {@code thread_*} 全件に
 * {@code external-source: arspaper} が付いていることを固定する(2026-08-02)。
 *
 * <h2>なぜ「1件でも欠けたら赤」なのか</h2>
 * スレッドを防具のスレッド枠へ挿せるかは、ArsPaper フォークの {@code ThreadGui#isEffectThread} が
 * Ars の PDC <b>2種</b>({@code arspaper:custom_item_id} が {@code thread_} 始まりであること、および
 * {@code arspaper:thread_item_type} が刻まれていること)で判定する。
 * <b>TrinityForge 本体は後者を1箇所も書かない</b>ので、TF カタログ側で組み立てられたスレッドは
 * 「見た目・名前・CMD まで同じなのに、GUI が受け付けない」という形で無言に壊れる。
 * {@code external-source} が1件でも落ちると、そのIDだけが
 * ダンジョンドロップ / ガチャ / 実績報酬 で装着不可の個体を配り始める。
 *
 * <h2>気づけない理由(だからテストで縛る)</h2>
 * <ul>
 *   <li>ログにも警告にも出ない。プレイヤーが「挿せない」と報告して初めて分かる。</li>
 *   <li>Ars 側 loot-tables 経由で出た個体は正常なので、<b>同じ名前のスレッドが2種類</b>
 *       出回り、「たまに挿せる」という再現性の低い報告になる。</li>
 *   <li>設定エディタは未知キーを保存時に落とさない(サーバの PUT は受け取った data をそのまま
 *       書き戻す)ので現状は安全だが、将来カタログフォームがエントリを再構築する実装に変われば
 *       静かに消える。その回帰をここで検出する。</li>
 * </ul>
 *
 * <p>逆方向(thread_ 以外に宣言が付く)も見る。カタログにしか実体が無いIDへ宣言を付けると、
 * Ars の同名アイテムに横取りされる逆向きの事故になるため。
 */
class ShippedCatalogExternalSourceDriftTest {

    private static final Logger LOG = Logger.getLogger("ShippedCatalogExternalSourceDriftTest");
    private static final String CATALOG = "src/main/resources/items/catalog.yml";

    /** スレッドの本数。節ごと消えた/大量に消えたことに気づくための下限。 */
    private static final int MIN_EXPECTED_THREADS = 40;

    private static ItemCatalogConfig.ParseResult parseShipped() throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of(CATALOG);
        assertTrue(java.nio.file.Files.isRegularFile(path),
                "出荷カタログが見つからない: " + path.toAbsolutePath());
        org.bukkit.configuration.file.YamlConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        cfg.loadFromString(java.nio.file.Files.readString(path));
        var items = cfg.getConfigurationSection("items");
        assertNotNull(items, CATALOG + " に items: 節が無い");
        return ItemCatalogConfig.parse(items, LOG);
    }

    @Test
    @DisplayName("出荷カタログの thread_* 全件が external-source: arspaper を宣言している")
    void everyShippedThreadDeclaresArsPaperAsItsSource() throws Exception {
        var templates = parseShipped().templates();

        List<String> threads = templates.keySet().stream()
                .filter(id -> id.startsWith("thread_"))
                .sorted()
                .toList();
        assertTrue(threads.size() >= MIN_EXPECTED_THREADS,
                "出荷カタログのスレッドが " + threads.size() + " 件しかない(期待: "
                        + MIN_EXPECTED_THREADS + " 件以上)。節ごと消えていないか確認すること");

        List<String> missing = new ArrayList<>();
        for (String id : threads) {
            ItemTemplate template = templates.get(id);
            if (!ItemTemplate.EXTERNAL_SOURCE_ARSPAPER.equals(template.externalSource())) {
                missing.add(id + "(external-source=" + template.externalSource() + ")");
            }
        }
        assertTrue(missing.isEmpty(),
                "出荷カタログの thread_* に external-source: arspaper が付いていないものがある。"
                        + "TF 経由(ダンジョンの add-drops / gacha.yml / 実績報酬)で配られた個体は"
                        + "Ars の arspaper:thread_item_type PDC を持たず、ThreadGui#isEffectThread が"
                        + "弾くため【防具のスレッド枠に永久に挿せない】。ログにも何も出ない。"
                        + "欠けているID: " + missing);
    }

    @Test
    @DisplayName("thread_* 以外には external-source を付けない(カタログ実体を Ars に横取りさせない)")
    void nonThreadEntriesDoNotDeclareExternalSource() throws Exception {
        var templates = parseShipped().templates();

        List<String> unexpected = templates.entrySet().stream()
                .filter(e -> !e.getKey().startsWith("thread_"))
                .filter(e -> e.getValue().hasExternalSource())
                .map(e -> e.getKey() + "(" + e.getValue().externalSource() + ")")
                .sorted()
                .toList();

        assertTrue(unexpected.isEmpty(),
                "thread_* 以外に external-source が付いている。宣言したIDは Ars を先に解決するので、"
                        + "TF カタログにしか実体が無いIDへ付けると Ars の同名アイテムに横取りされる"
                        + "(あるいは毎回カタログへフォールバックするだけの死に設定になる)。"
                        + "意図して増やすなら、その経路が本当に Ars 実体を要求するのか"
                        + "(=フォーク側が固有 PDC で判定しているか)を確認してからここを更新すること: "
                        + unexpected);
    }
}
