package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.BindType;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code items/catalog.yml} の {@code external-source:} 宣言が
 * {@link CrossPluginItemResolver#create} の解決順を切り替えることを固定する(2026-08-02)。
 *
 * <h2>何が壊れていたか</h2>
 * スレッドが防具に装着できるかは ArsPaper フォークの {@code ThreadGui#isEffectThread} が
 * Ars の PDC 2種({@code arspaper:custom_item_id} と {@code arspaper:thread_item_type})で判定する。
 * <b>TrinityForge 本体は後者を1箇所も書かない</b>。それでも {@code catalog.yml} には
 * {@code thread_*} が 40 件あり、{@code create} は「TFカタログ→Arsレジストリ→バニラ材質」の順で
 * <b>カタログが当たった時点で return する</b>ので、TF 経由の配布(ダンジョンの {@code add-drops}、
 * {@code gacha.yml} の {@code thread_empty}、実績報酬 …)は必ずカタログ側で解決され、
 * <b>見た目は同じだが永久に装着できないスレッド</b>を配っていた。
 *
 * <h2>なぜ「全体の解決順を反転」ではないのか</h2>
 * Ars 側の loot-tables 経路({@code ItemCostRef#createStack})は元から「Ars→TF」の順で正しい。
 * 共有シームの順序を無条件に反転させると、今度は「TFカタログにしか実体が無いID」を
 * Ars の同名アイテムが横取りする逆向きの事故を作る。そこで<b>宣言のあるIDだけ</b>を対象にする。
 *
 * <h2>テストのシームについて</h2>
 * 本番の外部解決は {@code Bukkit.getPluginManager().getPlugin("ArsPaper")} 越しのリフレクション
 * ({@link CrossPluginItemResolver#createArs})で、ユニットテストからは「Ars が居ない」側しか
 * 再現できない。それでは肝心の<b>順序</b>を1行も検証できず空振りテストになるため、
 * パッケージプライベートのコンストラクタで外部解決関数を差し替えている。
 */
class CrossPluginItemResolverExternalSourceTest {

    private static final String THREAD_ID = "thread_mana_regen";

    /** 呼ばれた id を記録しつつ、指定 id にだけ「Ars 実体」を返す偽の外部レジストリ。 */
    private static final class FakeExternalRegistry implements Function<String, Optional<ItemStack>> {
        private final String resolvableId;
        private final ItemStack stack;
        private final List<String> calls = new ArrayList<>();

        FakeExternalRegistry(String resolvableId, ItemStack stack) {
            this.resolvableId = resolvableId;
            this.stack = stack;
        }

        @Override
        public Optional<ItemStack> apply(String id) {
            calls.add(id);
            return resolvableId != null && resolvableId.equals(id) ? Optional.of(stack) : Optional.empty();
        }
    }

    private ItemStack arsStack;
    private ItemStack catalogStack;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        // 「Ars 製のスレッド」と「TFカタログ製のスレッド」は材質で区別できるようにしておく
        // (実物は同じ材質だが、どちらが返ったかをテストで判別するため意図的にずらす)。
        arsStack = new ItemStack(Material.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE);
        catalogStack = new ItemStack(Material.PAPER);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemTemplate template(String id, String externalSource) {
        return new ItemTemplate(id, Material.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE, null, 300002,
                BindType.TRADEABLE, 0, null, List.of(), List.of(), null, true, externalSource);
    }

    @Test
    @DisplayName("external-source を宣言したIDは Ars を先に試す(カタログは組み立てもしない)")
    void declaredIdResolvesExternalFirst() {
        ItemTemplate declared = template(THREAD_ID, ItemTemplate.EXTERNAL_SOURCE_ARSPAPER);
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template(THREAD_ID)).thenReturn(Optional.of(declared));
        ItemFactory factory = mock(ItemFactory.class);
        when(factory.create(any(), anyLong(), anyInt())).thenReturn(catalogStack);
        FakeExternalRegistry ars = new FakeExternalRegistry(THREAD_ID, arsStack);

        Optional<ItemStack> result =
                new CrossPluginItemResolver(catalog, factory, ars).create(THREAD_ID, 7L, 0);

        assertTrue(result.isPresent(), "宣言済みIDが解決できていない");
        assertSame(arsStack, result.get(),
                "external-source: arspaper を宣言しているのにカタログ側の実体が返っている"
                        + "(= Ars の thread_item_type PDC が無い → 防具に装着できない)");
        verify(factory, never()).create(any(), anyLong(), anyInt());
        assertEquals(List.of(THREAD_ID), ars.calls);
    }

    @Test
    @DisplayName("宣言が無ければ従来どおりカタログ優先(全体の解決順は変えていない)")
    void undeclaredIdKeepsCatalogFirst() {
        // Ars 側も同じ id を解決できる状況をわざと作る。順序を変えていれば Ars 側が返ってしまう。
        ItemTemplate plain = template("tf_core_meat", null);
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template("tf_core_meat")).thenReturn(Optional.of(plain));
        ItemFactory factory = mock(ItemFactory.class);
        when(factory.create(plain, 7L, 0)).thenReturn(catalogStack);
        FakeExternalRegistry ars = new FakeExternalRegistry("tf_core_meat", arsStack);

        Optional<ItemStack> result =
                new CrossPluginItemResolver(catalog, factory, ars).create("tf_core_meat", 7L, 0);

        assertTrue(result.isPresent());
        assertSame(catalogStack, result.get(),
                "宣言の無いIDまで Ars 優先になっている(共有シームの解決順を無条件に変えてはいけない)");
        assertTrue(ars.calls.isEmpty(), "カタログで解決できたのに Ars を問い合わせている: " + ars.calls);
    }

    @Test
    @DisplayName("宣言していても Ars が居なければカタログへフォールバックする(Ars 非導入構成)")
    void declaredIdFallsBackToCatalogWhenExternalAbsent() {
        ItemTemplate declared = template(THREAD_ID, ItemTemplate.EXTERNAL_SOURCE_ARSPAPER);
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template(THREAD_ID)).thenReturn(Optional.of(declared));
        ItemFactory factory = mock(ItemFactory.class);
        when(factory.create(declared, 7L, 0)).thenReturn(catalogStack);
        // 何も解決できない外部レジストリ = ArsPaper が入っていない/そのIDを登録していないサーバ
        FakeExternalRegistry ars = new FakeExternalRegistry(null, arsStack);

        Optional<ItemStack> result =
                new CrossPluginItemResolver(catalog, factory, ars).create(THREAD_ID, 7L, 0);

        assertTrue(result.isPresent(),
                "Ars 非導入構成で宣言済みIDが配れなくなっている(宣言はフォールバックを消してはいけない)");
        assertSame(catalogStack, result.get());
        assertEquals(List.of(THREAD_ID), ars.calls, "外部を先に試してからカタログへ落ちるはず");
    }

    @Test
    @DisplayName("custom: プレフィックス付きでも宣言は効く(エディタが書く正規形)")
    void declarationAppliesToCustomPrefixedToken() {
        ItemTemplate declared = template(THREAD_ID, ItemTemplate.EXTERNAL_SOURCE_ARSPAPER);
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template(THREAD_ID)).thenReturn(Optional.of(declared));
        ItemFactory factory = mock(ItemFactory.class);
        when(factory.create(any(), anyLong(), anyInt())).thenReturn(catalogStack);
        FakeExternalRegistry ars = new FakeExternalRegistry(THREAD_ID, arsStack);

        Optional<ItemStack> result =
                new CrossPluginItemResolver(catalog, factory, ars).create("custom:" + THREAD_ID, 7L, 0);

        assertTrue(result.isPresent());
        assertSame(arsStack, result.get());
    }
}
