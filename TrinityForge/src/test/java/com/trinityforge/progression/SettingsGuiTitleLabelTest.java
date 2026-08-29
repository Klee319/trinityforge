package com.trinityforge.progression;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /tf settings} の称号ボタンが「内部IDそのまま」ではなく
 * {@code special-rewards.yml} の {@code display} を描画すること（2026-08-05）。
 *
 * <p><b>直した不具合</b>: 以前の {@code titleButton} は {@code title} 引数を受け取りながら一度も使わず、
 * ボタン名に {@code title_completionist} のような内部IDを出していた。プレイヤーの頭上に出る実際の称号は
 * {@code <gradient:...>万象を知る者</gradient>} なので、選択画面と実物が繋がらない。
 *
 * <p><b>同時に防いでいる二つ目の壊し方</b>: {@code display} は MiniMessage 文字列なので、
 * ID をやめる修正を {@code Component.text(title.display())} で書くと、今度は {@code <gradient:...>} が
 * 生で見える。図鑑ティア解放の通知で実際に起きた不具合（{@code 6215a0d}）と同じ形なので、
 * 「タグが残っていないこと」まで固定する。
 */
class SettingsGuiTitleLabelTest {

    /** 単色。MiniMessage を通せば「収集家」だけが残る。 */
    private static final String PLAIN_TAGGED = "<gray>収集家</gray>";
    /** グラデーション。素の連結だと最も派手に壊れる形。 */
    private static final String GRADIENT_TAGGED = "<gradient:#ffd700:#ff8c00>万象を知る者</gradient>";

    private ServerMock server;
    private SettingsGui gui;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Map<String, SpecialRewardsConfig.Title> titles = new LinkedHashMap<>();
        titles.put("title_collector", new SpecialRewardsConfig.Title("title_collector", PLAIN_TAGGED));
        titles.put("title_completionist",
                new SpecialRewardsConfig.Title("title_completionist", GRADIENT_TAGGED));

        SpecialRewardsConfig config = mock(SpecialRewardsConfig.class);
        when(config.titles()).thenReturn(titles);
        when(config.particles()).thenReturn(Map.of());

        SpecialRewardService rewardService = mock(SpecialRewardService.class);
        // 解放済みでないとボタンが「？？？ (未解放)」になり、表示名の検証にならない。
        when(rewardService.isUnlocked(any(), anyString())).thenReturn(true);

        gui = new SettingsGui(MockBukkit.createMockPlugin(), config, rewardService,
                id -> java.util.Optional.empty());
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("称号ボタンは display を描画する（内部IDでもタグ生表示でもない）")
    void titleButtonsRenderTheConfiguredDisplayName() {
        gui.open(player);
        Inventory open = player.getOpenInventory().getTopInventory();

        // TITLE_ROW_START(9) は「称号を外す」ボタン。称号本体はその次から並ぶ。
        assertEquals("収集家", buttonName(open, 10),
                "1件目の称号ボタンが display を描画していない");
        assertEquals("万象を知る者", buttonName(open, 11),
                "2件目の称号ボタンが display を描画していない");
    }

    @Test
    @DisplayName("称号ボタン名に MiniMessage タグも内部IDも残らない")
    void titleButtonNamesLeakNeitherTagsNorIds() {
        gui.open(player);
        Inventory open = player.getOpenInventory().getTopInventory();

        for (int slot : new int[] {10, 11}) {
            String name = buttonName(open, slot);
            assertFalse(name.contains("<") || name.contains(">"),
                    "slot " + slot + " の称号名に MiniMessage タグが生のまま出ている: " + name);
            assertFalse(name.contains("title_"),
                    "slot " + slot + " の称号名が内部IDのまま: " + name);
        }
    }

    @Test
    @DisplayName("内部IDは lore に残す（運用で id 指定が要るため）")
    void titleButtonKeepsTheIdInLore() {
        gui.open(player);
        Inventory open = player.getOpenInventory().getTopInventory();

        ItemStack button = open.getItem(11);
        assertNotNull(button, "称号ボタンが無い");
        var lore = button.getItemMeta().lore();
        assertNotNull(lore, "称号ボタンに lore が無い");
        assertTrue(lore.stream().map(SettingsGuiTitleLabelTest::plain)
                        .anyMatch(line -> line.contains("title_completionist")),
                "lore に内部IDが残っていない: " + lore.stream()
                        .map(SettingsGuiTitleLabelTest::plain).toList());
    }

    private static String buttonName(Inventory inventory, int slot) {
        ItemStack button = inventory.getItem(slot);
        assertNotNull(button, "slot " + slot + " に称号ボタンが無い");
        assertTrue(button.hasItemMeta(), "slot " + slot + " の称号ボタンに meta が無い");
        Component name = button.getItemMeta().displayName();
        assertNotNull(name, "slot " + slot + " の称号ボタンに表示名が無い");
        return plain(name);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
