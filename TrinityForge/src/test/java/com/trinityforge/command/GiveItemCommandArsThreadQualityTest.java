package com.trinityforge.command;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.stats.ItemFactory;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W-53(2026-08-18)の回帰テスト: {@code /tf give <スレッド> <quality>} でスレッドへ quality が
 * 実際に反映される経路({@link GiveItemCommand#stampArsItem})を、ArsPaper 本体を用意せずに直接検証する。
 *
 * <p><b>なぜ {@code resolver.createArsGated} 経由(コマンド文字列実行)で書かないか</b>:
 * {@link com.trinityforge.stats.CrossPluginItemResolver} は ArsPaper プラグインが実際にロードされて
 * いないと Ars 経路へ入らず(このテスト環境には無い)、{@link GiveItemCommandDeliveryTest} と同様
 * カタログ経路しか通せない。{@link GiveItemCommand#stampArsItem} をパッケージ非公開にして直接呼べる
 * ようにしてあるのは、まさにこの理由による(コメント参照)。
 *
 * <p>{@link GiveItemCommand.ThreadQualityRestamper} は package-private シーム経由でテスト用の
 * ラムダに差し替える({@link com.trinityforge.stats.CrossPluginItemResolverExternalSourceTest} と
 * 同じ「外部プラグイン有無に依存する reflection をテストではDIで差し替える」規約)。
 */
class GiveItemCommandArsThreadQualityTest {

    private ServerMock server;
    private PlayerMock admin;
    private ItemFactory factory;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        admin = server.addPlayer("Admin");

        plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("GiveItemCommandArsThreadQualityTest"));

        factory = mock(ItemFactory.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemStack anyLeatherThreadShapedStack() {
        // ThreadItem の実体材質は防具トリム/陶器の欠片/旗の模様等、非装備の Material。
        // ここではゲート挙動だけが検証対象なので材質そのものの意味は問わない。
        return new ItemStack(Material.LEATHER);
    }

    @Test
    @DisplayName("threadRestamper が true を返したら factory.stamp は一切呼ばれない(スレッド専用経路で処理済み)")
    void doesNotCallGenericStampWhenThreadRestamperHandledIt() {
        GiveItemCommand.ThreadQualityRestamper fakeRestamper =
                (itemId, item, quality) -> true; // 「スレッドとして処理した」を装う。
        GiveItemCommand command = new GiveItemCommand(plugin, factory,
                mock(ItemCatalogConfig.class), new QualityConfig(), fakeRestamper);

        ItemStack built = anyLeatherThreadShapedStack();
        GiveItemCommand.Built result = command.stampArsItem(admin, "any_thread", built, 7);

        assertNotNull(result, "スレッド専用経路が処理したら成功として扱うこと");
        assertEquals(built, result.stack());
        verify(factory, never()).stamp(anyStack(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("threadRestamper が false を返したら、従来の isQualityStamped/isEquipment ゲートへ"
            + "フォールバックする(非スレッドAras品の既存挙動を壊さない)")
    void fallsBackToTheExistingGateWhenThreadRestamperDeclines() {
        GiveItemCommand.ThreadQualityRestamper fakeRestamper =
                (itemId, item, quality) -> false; // 「スレッドではない」を装う。
        GiveItemCommand command = new GiveItemCommand(plugin, factory,
                mock(ItemCatalogConfig.class), new QualityConfig(), fakeRestamper);

        // NETHERITE_SWORD は MaterialTier.isEquipment() == true になる装備材質。
        ItemStack built = new ItemStack(Material.NETHERITE_SWORD);
        GiveItemCommand.Built result = command.stampArsItem(admin, "some_ars_equipment", built, 7);

        assertNotNull(result);
        // Mockito は「1引数でも matcher を使ったら全引数を matcher にする」ため、
        // built/7 を生値のまま混ぜると 3 matchers expected, 1 recorded で落ちる。
        verify(factory).stamp(org.mockito.ArgumentMatchers.eq(built), anyLong(),
                org.mockito.ArgumentMatchers.eq(7));
    }

    @Test
    @DisplayName("threadRestamper が false を返し、かつ非装備材質(isQualityStamped=falseのAras素材)なら、"
            + "従来どおり無刻印のまま返す(触媒/魔導書以外の素材の既存挙動)")
    void nonEquipmentNonThreadArsItemsAreLeftUnstamped() {
        GiveItemCommand.ThreadQualityRestamper fakeRestamper =
                (itemId, item, quality) -> false;
        GiveItemCommand command = new GiveItemCommand(plugin, factory,
                mock(ItemCatalogConfig.class), new QualityConfig(), fakeRestamper);

        ItemStack built = anyLeatherThreadShapedStack(); // 非装備材質、isQualityStamped も false
        GiveItemCommand.Built result = command.stampArsItem(admin, "some_ars_material", built, 7);

        assertNotNull(result);
        verify(factory, never()).stamp(anyStack(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("threadRestamper が例外を投げても、既存の刻印失敗と同じ経路(送信者へ通知しnullを返す)"
            + "で処理される")
    void restamperExceptionIsHandledLikeAStampFailure() {
        GiveItemCommand.ThreadQualityRestamper throwingRestamper = (itemId, item, quality) -> {
            throw new IllegalStateException("boom");
        };
        GiveItemCommand command = new GiveItemCommand(plugin, factory,
                mock(ItemCatalogConfig.class), new QualityConfig(), throwingRestamper);

        ItemStack built = anyLeatherThreadShapedStack();
        GiveItemCommand.Built result = command.stampArsItem(admin, "any_thread", built, 7);

        assertNull(result, "失敗時はnullを返す契約(呼び出し元がそのまま伝播する)");
        verify(factory, never()).stamp(anyStack(), anyLong(), anyInt());
    }

    private static ItemStack anyStack() {
        return org.mockito.ArgumentMatchers.any(ItemStack.class);
    }
}
