package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 敵対的レビュー指摘1(2026-08-02): {@code draft: true} を宣言したカタログエントリが
 * {@link CrossPluginItemResolver#create} の Ars/バニラ Material フォールバックへ素通りして
 * <b>実際にドロップしてしまう</b>不具合の回帰ガード。
 *
 * <h2>何が壊れていたか</h2>
 * {@code ItemCatalogConfig#load} は draft を {@code template(id)}/{@code all()} から落とすが、
 * それは「カタログに無いID」を意味するだけで、{@code create} はカタログ miss を
 * 外部プラグイン(Ars)・バニラ {@link Material} フォールバックへの合図として扱っていた。
 * {@code items/catalog.yml} の24件が draft かつ {@code external-source: arspaper} で、
 * うち8件が {@code combat/mob-level-table.yml} のドロップに実在するため、Lv65帯の敵撃破で
 * 実際にドロップしていた({@code thread_luck} 等)。
 *
 * <p>ドロップ表の記述自体(editorでの参照)は消してはいけない仕様(準備中でも設定はできる)なので、
 * このテストは「参照が残っていても create() が解決を拒否する」ことだけを固定する。
 */
class CrossPluginItemResolverDraftGateTest {

    private static final String DRAFT_ID = "thread_luck";

    /** 呼ばれた id を記録しつつ常に成功する偽の外部レジストリ(=「Ars側には実在する」状況の再現)。 */
    private static final class AlwaysResolvingExternalRegistry implements Function<String, Optional<ItemStack>> {
        private final ItemStack stack;
        private final List<String> calls = new java.util.ArrayList<>();

        AlwaysResolvingExternalRegistry(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public Optional<ItemStack> apply(String id) {
            calls.add(id);
            return Optional.of(stack);
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("draft かつ external-source: arspaper のIDは、Ars 側に実在してもドロップを解決しない")
    void draftWithExternalSourceNeverResolvesViaArsFallback() {
        // draft: true のエントリは ItemCatalogConfig#load が template()/all() から既に落としている
        // のと同じ状態を再現する: template() は miss、isDraft() だけが true を返す。
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template(DRAFT_ID)).thenReturn(Optional.empty());
        when(catalog.isDraft(DRAFT_ID)).thenReturn(true);
        ItemFactory factory = mock(ItemFactory.class);
        ItemStack arsStack = new ItemStack(Material.PAPER);
        AlwaysResolvingExternalRegistry ars = new AlwaysResolvingExternalRegistry(arsStack);

        Optional<ItemStack> result =
                new CrossPluginItemResolver(catalog, factory, ars).create(DRAFT_ID, 1L, 0);

        assertTrue(result.isEmpty(), "draft IDが Ars フォールバックを経由して実際にドロップしている");
        assertTrue(ars.calls.isEmpty(),
                "draft ゲートが外部プラグイン問い合わせより先に効くはず(呼ばれた: " + ars.calls + ")");
    }

    @Test
    @DisplayName("draft のIDはバニラ Material フォールバックにも落ちない")
    void draftIdNeverFallsBackToVanillaMaterial() {
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template("DIAMOND")).thenReturn(Optional.empty());
        when(catalog.isDraft("DIAMOND")).thenReturn(true);
        ItemFactory factory = mock(ItemFactory.class);

        CrossPluginItemResolver resolver = new CrossPluginItemResolver(catalog, factory);
        Optional<ItemStack> result = resolver.create("DIAMOND", 1L, 0);

        assertTrue(result.isEmpty(), "draft 宣言のあるIDがバニラMaterialへ素通りしている");
    }

    @Test
    @DisplayName("draft でないIDは従来どおり解決される(回帰なし)")
    void nonDraftIdStillResolves() {
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template("DIAMOND")).thenReturn(Optional.empty());
        when(catalog.isDraft("DIAMOND")).thenReturn(false);
        ItemFactory factory = mock(ItemFactory.class);

        CrossPluginItemResolver resolver = new CrossPluginItemResolver(catalog, factory);
        Optional<ItemStack> result = resolver.create("DIAMOND", 1L, 0);

        assertTrue(result.isPresent());
        assertEquals(Material.DIAMOND, result.get().getType());
    }

    @Test
    @DisplayName("exists() も draft IDには false を返す(可否判定の食い違いを防ぐ)")
    void existsAlsoRejectsDraftId() {
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.isDraft(DRAFT_ID)).thenReturn(true);
        ItemFactory factory = mock(ItemFactory.class);
        AlwaysResolvingExternalRegistry ars = new AlwaysResolvingExternalRegistry(new ItemStack(Material.PAPER));

        boolean exists = new CrossPluginItemResolver(catalog, factory, ars).exists(DRAFT_ID);

        assertFalse(exists, "draft IDが exists() では true を返している(create()と食い違う)");
    }

    /**
     * 2026-08-03 の穴: {@code /tf give} と村人取引の catalog フォールバックは、TFカタログより先に
     * <b>静的な</b> {@code createArs} を直接呼んでいた。{@code createArs} はカタログを持たないので
     * draft を見られず、{@code external-source: arspaper} の準備中スレッド24種が
     * そのまま配れていた。ゲート付き入口 {@code createArsGated} へ寄せたことを固定する。
     */
    @Test
    @DisplayName("createArsGated は draft のIDを Ars 側に問い合わせる前に弾く")
    void createArsGatedRejectsDraftBeforeQueryingArs() {
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.isDraft(DRAFT_ID)).thenReturn(true);
        ItemFactory factory = mock(ItemFactory.class);

        Optional<ItemStack> result =
                new CrossPluginItemResolver(catalog, factory).createArsGated(DRAFT_ID);

        assertTrue(result.isEmpty(),
                "draft IDが Ars 直接解決(/tf give・村人取引)を素通りしている");
        verify(catalog).isDraft(DRAFT_ID);
    }

    @Test
    @DisplayName("createArsGated は custom: 接頭辞つきでも draft を弾く")
    void createArsGatedStripsCustomPrefix() {
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.isDraft(DRAFT_ID)).thenReturn(true);
        ItemFactory factory = mock(ItemFactory.class);

        Optional<ItemStack> result = new CrossPluginItemResolver(catalog, factory)
                .createArsGated("custom:" + DRAFT_ID);

        assertTrue(result.isEmpty(), "custom: 接頭辞つきの draft IDがすり抜けている");
        verify(catalog, never()).isDraft("custom:" + DRAFT_ID);
    }

    @Test
    @DisplayName("createArsGated は draft でないIDのゲートを閉じない(Ars 未導入なら空でよい)")
    void createArsGatedPassesNonDraft() {
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.isDraft("source_gem")).thenReturn(false);
        ItemFactory factory = mock(ItemFactory.class);

        // Ars 非導入のテスト環境では結果は空になるが、「draft ゲートで弾いた」のではなく
        // 「Ars へ問い合わせた結果」であることを、判定が呼ばれた事実で確かめる。
        new CrossPluginItemResolver(catalog, factory).createArsGated("source_gem");
        verify(catalog).isDraft("source_gem");
    }

    @Test
    @DisplayName("CrossPluginItemResolver#isDraft は custom: 接頭辞を剥がしてから判定する")
    void isDraftStripsCustomPrefix() {
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.isDraft(DRAFT_ID)).thenReturn(true);
        ItemFactory factory = mock(ItemFactory.class);
        CrossPluginItemResolver resolver = new CrossPluginItemResolver(catalog, factory);

        assertTrue(resolver.isDraft("custom:" + DRAFT_ID));
        verify(catalog, never()).isDraft("custom:" + DRAFT_ID);
    }
}
