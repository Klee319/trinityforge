package com.trinityforge.progression;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * W-97(実サーバ報告「以前設定していたレシピ・図鑑のソートの記憶保持をするようにしてほしい」)の
 * 回帰ガード。
 *
 * <p>図鑑は開くたびに {@code SortMode.DEFAULT} / {@code FilterMode.ALL} で作り直していたので、
 * 並び順を変えても画面を閉じた瞬間に消えていた。プレイヤーの PDC へ保存して復元する。
 *
 * <p><b>ここで固定したい不変条件は「保存形式」</b>。
 * <ul>
 *   <li><b>{@code name()} で保存すること</b> —— ordinal で保存すると、{@code SortMode} に定数を
 *       1つ挿しただけで<b>保存済みの全プレイヤーの設定が無言で別の並び順に化ける</b>。</li>
 *   <li><b>未知の値で例外を投げないこと</b> —— 定数を削除/改名した後に古い値が残っていると、
 *       図鑑が<b>開かなくなる</b>。既定値へ落として開けること。</li>
 * </ul>
 * GUI 本体は Bukkit のインベントリ生成を伴い直接は叩けないので、
 * その唯一の分岐点である {@code CollectionGui#readEnum} を検査する。
 */
class CollectionGuiPreferenceTest {

    private NamespacedKey key;
    private PersistentDataContainer pdc;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        key = new NamespacedKey("trinityforge", "collection_pref_sort");
        ItemStack carrier = new ItemStack(Material.PAPER);
        ItemMeta meta = carrier.getItemMeta();
        pdc = meta.getPersistentDataContainer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("未設定なら既定値")
    void unsetFallsBackToTheDefault() {
        assertEquals(CollectionGuiModel.SortMode.DEFAULT,
                CollectionGui.readEnum(pdc, key, CollectionGuiModel.SortMode.class,
                        CollectionGuiModel.SortMode.DEFAULT));
    }

    @Test
    @DisplayName("保存した並び順がそのまま戻る")
    void storedValueRoundTrips() {
        for (CollectionGuiModel.SortMode mode : CollectionGuiModel.SortMode.values()) {
            pdc.set(key, PersistentDataType.STRING, mode.name());

            assertEquals(mode, CollectionGui.readEnum(pdc, key, CollectionGuiModel.SortMode.class,
                            CollectionGuiModel.SortMode.DEFAULT),
                    mode + " が復元できていない");
        }
    }

    @Test
    @DisplayName("削除された定数が残っていても既定値で開ける(例外を投げない)")
    void unknownStoredValueDoesNotBreakTheGui() {
        pdc.set(key, PersistentDataType.STRING, "SORT_MODE_THAT_NO_LONGER_EXISTS");

        assertEquals(CollectionGuiModel.SortMode.DEFAULT,
                CollectionGui.readEnum(pdc, key, CollectionGuiModel.SortMode.class,
                        CollectionGuiModel.SortMode.DEFAULT),
                "未知の値で例外が漏れると図鑑が開かなくなる");
    }

    @Test
    @DisplayName("ordinal ではなく name() で保存されている(定数を挿しても設定が化けない)")
    void storageFormatIsTheEnumName() {
        // ordinal 保存だと "0"/"1" のような数字が入る。name 保存ならその文字列は未知の定数として
        // 既定値へ落ちる = 数字を書いても復元できない、という形で保存形式を固定する。
        pdc.set(key, PersistentDataType.STRING,
                String.valueOf(CollectionGuiModel.SortMode.NAME.ordinal()));

        assertEquals(CollectionGuiModel.SortMode.DEFAULT,
                CollectionGui.readEnum(pdc, key, CollectionGuiModel.SortMode.class,
                        CollectionGuiModel.SortMode.DEFAULT),
                "ordinal を受け付けている。定数を1つ挿すと全プレイヤーの設定が別の並び順に化ける");
    }

    @Test
    @DisplayName("絞り込みも同じ形式で往復する")
    void filterModeRoundTripsToo() {
        NamespacedKey filterKey = new NamespacedKey("trinityforge", "collection_pref_filter");
        for (CollectionGuiModel.FilterMode mode : CollectionGuiModel.FilterMode.values()) {
            pdc.set(filterKey, PersistentDataType.STRING, mode.name());

            assertEquals(mode, CollectionGui.readEnum(pdc, filterKey,
                            CollectionGuiModel.FilterMode.class, CollectionGuiModel.FilterMode.ALL),
                    mode + " が復元できていない");
        }
    }
}
