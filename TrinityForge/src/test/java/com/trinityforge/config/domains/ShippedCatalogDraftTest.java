package com.trinityforge.config.domains;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「準備中」({@code items/catalog.yml} の {@code draft: true})の契約を固定する(2026-08-02)。
 *
 * <h2>準備中とは何か</h2>
 * エディタ上では通常のアイテムとして<b>編集も参照もできる</b>が、
 * <b>ゲーム側には一切配線されない</b>アイテム。「先にドロップ表やレシピを書いておいて、
 * 後から一斉に解禁する」ための状態で、解禁は {@code draft:} を外すだけで済む。
 *
 * <h2>なぜ「配布サイトごとのガード」ではなく「参照面を絞る」なのか</h2>
 * カタログのアイテムを配る経路は {@code itemResolver.create(...)} を呼ぶ場所だけで 12 箇所以上ある
 * (モブドロップ3種 / ガチャ / 実績報酬 / 図鑑報酬 / 分解 / 採掘・伐採・釣り・農耕・整地の各ギミック …)。
 * 個別にガードを足す方式だと<b>経路が1本増えるたびに漏れる</b>ので、
 * {@code ItemCatalogConfig#load} が {@code template(id)} / {@code all()} から draft を落とす。
 * これで「ゲーム側から見えない」が構造的に保証される。
 *
 * <h2>ガチャだけ別扱いが要る理由(このテストが守る本丸)</h2>
 * {@code GachaListener} は<b>景品が解決できないと券を消費しない</b>(景品ロスト防止)。
 * 準備中のアイテムを景品欄に置いたままにすると、抽選のたびに解決に失敗して
 * <b>券が減らないまま何度でも引ける</b>＝実質無限ガチャになる。
 * だから準備中の景品は「解決に失敗させる」のではなく<b>抽選前にプールから外す</b>。
 */
class ShippedCatalogDraftTest {

    private static final String CATALOG = "src/main/resources/items/catalog.yml";

    /** 準備中の下限。節ごと消えた/一括で外れたことに気づくため。 */
    private static final int MIN_EXPECTED_DRAFTS = 90;

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ShippedCatalogDraftTest");
            case "saveResource" -> throw new AssertionError("既にファイルがあるのに saveResource が呼ばれた");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, handler);
    }

    /** 出荷 catalog.yml をそのまま {@code load()} させた設定を返す(＝本番と同じ経路)。 */
    private static ItemCatalogConfig loadShipped() throws Exception {
        java.nio.file.Path source = java.nio.file.Path.of(CATALOG);
        assertTrue(Files.isRegularFile(source), "出荷カタログが見つからない: " + source.toAbsolutePath());

        File dataFolder = Files.createTempDirectory("catalog-draft").toFile();
        File target = new File(dataFolder, ItemCatalogConfig.PATH);
        Files.createDirectories(target.getParentFile().toPath());
        Files.copy(source, target.toPath());

        ItemCatalogConfig config = new ItemCatalogConfig();
        config.load(fakePlugin(dataFolder));
        return config;
    }

    /** yml に直接書いてある {@code draft: true} の ID(load のフィルタを通す前の真値)。 */
    private static List<String> declaredDraftIds() throws Exception {
        org.bukkit.configuration.file.YamlConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        cfg.loadFromString(Files.readString(java.nio.file.Path.of(CATALOG)));
        var items = cfg.getConfigurationSection("items");
        assertNotNull(items, CATALOG + " に items: 節が無い");
        List<String> declared = new ArrayList<>();
        for (String id : items.getKeys(false)) {
            var entry = items.getConfigurationSection(id);
            if (entry != null && entry.getBoolean("draft", false)) {
                declared.add(id);
            }
        }
        return declared;
    }

    @Test
    @DisplayName("準備中は yml に残るが、ゲーム側の参照面(template/all)からは1件残らず落ちる")
    void draftItemsAreInvisibleToGameplay() throws Exception {
        List<String> declared = declaredDraftIds();
        assertTrue(declared.size() >= MIN_EXPECTED_DRAFTS,
                "準備中が " + declared.size() + " 件しかない(期待: " + MIN_EXPECTED_DRAFTS
                        + " 件以上)。まとめて解禁されていないか確認すること");

        ItemCatalogConfig config = loadShipped();

        List<String> leaked = new ArrayList<>();
        for (String id : declared) {
            if (config.template(id).isPresent() || config.all().containsKey(id)) {
                leaked.add(id);
            }
        }
        assertTrue(leaked.isEmpty(),
                "準備中なのにゲーム側から見えているアイテムがある。レシピ登録・ドロップ・ガチャに"
                        + "そのまま流れるので入手できてしまう。該当: " + leaked);

        assertEquals(declared.size(), config.draftIds().size(),
                "yml の draft 件数と draftIds() の件数が食い違っている");
    }

    @Test
    @DisplayName("準備中を除いた出荷アイテムは1件も巻き込まれていない(素材や既存装備が消えていない)")
    void nonDraftItemsAreUntouched() throws Exception {
        ItemCatalogConfig config = loadShipped();
        List<String> declared = declaredDraftIds();

        // 出荷 yml の全 ID から draft を引いたものが、そのまま live に残っていること。
        org.bukkit.configuration.file.YamlConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        cfg.loadFromString(Files.readString(java.nio.file.Path.of(CATALOG)));
        var items = cfg.getConfigurationSection("items");
        assertNotNull(items, CATALOG + " に items: 節が無い");

        List<String> missing = new ArrayList<>();
        for (String id : items.getKeys(false)) {
            if (declared.contains(id)) {
                continue;
            }
            if (!config.all().containsKey(id)) {
                missing.add(id);
            }
        }
        // material 不正などで元から skip されるエントリがあると偽陽性になるため、
        // 「draft 導入で消えた」かどうかは件数ではなく draft フラグの有無で判定している。
        assertTrue(missing.isEmpty(),
                "draft ではないのに読み込まれていないアイテムがある(draft の巻き込み、"
                        + "または material 不正で元から skip されている)。該当: " + missing);
    }

    @Test
    @DisplayName("isDraft は draft のIDにだけ true を返す(既存アイテムを巻き込まない)")
    void isDraftOnlyMatchesDeclaredDrafts() throws Exception {
        ItemCatalogConfig config = loadShipped();
        for (String id : declaredDraftIds()) {
            assertTrue(config.isDraft(id), id + " は draft のはず");
        }
        for (String id : config.all().keySet()) {
            assertFalse(config.isDraft(id), id + " は出荷済みなのに draft 扱いされている");
        }
        assertFalse(config.isDraft(null), "null は draft ではない");
        assertFalse(config.isDraft("no_such_item_id"), "存在しないIDは draft ではない");
    }
}
