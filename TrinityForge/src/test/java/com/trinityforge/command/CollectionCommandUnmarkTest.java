package com.trinityforge.command;

import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /tf collection unmark} — クリエイティブ由来マーカーを剥がす管理コマンド(2026-08-01)。
 *
 * <p><b>なぜこの経路が必須か</b>: 印({@code trinityforge:creative_origin})は best-effort で、
 * 正当に入手した品にも付きうる(クリエイティブ滞在中に地面から拾った品など)。印が付いたスタックは
 * {@code CollectionListener#resolveEntryId} が図鑑判定から丸ごと外すので、誤って付くと
 * <b>そのスタックは以後どのサバイバル走査でも永久に図鑑に載らない</b>。エラーも通知も出ないため、
 * 剥がす手段が無いと「このアイテムは一生図鑑に載らない」状態を運用で回復できない。
 */
class CollectionCommandUnmarkTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemStack stamped(Material material) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).markCreativeOrigin();
        stack.setItemMeta(meta);
        return stack;
    }

    private static boolean creativeOrigin(ItemStack stack) {
        ItemMeta meta = stack == null ? null : stack.getItemMeta();
        return meta != null && ItemData.of(meta).creativeOrigin();
    }

    @Test
    @DisplayName("インベントリの印を剥がして件数を返す")
    void clearsTheMarkerFromEveryStackInTheInventory() {
        Player player = server.addPlayer();
        player.getInventory().setItem(0, stamped(Material.ELYTRA));
        player.getInventory().setItem(1, stamped(Material.TOTEM_OF_UNDYING));

        int cleared = CollectionCommand.clearCreativeOrigin(player.getInventory());

        assertEquals(2, cleared);
        assertFalse(creativeOrigin(player.getInventory().getItem(0)),
                "剥がせないと、このスタックは以後どのサバイバル走査でも永久に図鑑に載らない");
        assertFalse(creativeOrigin(player.getInventory().getItem(1)));
    }

    @Test
    @DisplayName("印の無いスタックは書き戻さない(ちらつき回避)")
    void leavesUnstampedStacksAlone() {
        Player player = server.addPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        player.getInventory().setItem(1, stamped(Material.ELYTRA));

        int cleared = CollectionCommand.clearCreativeOrigin(player.getInventory());

        assertEquals(1, cleared, "印の無い分まで数える/書き戻すと全スロットが再送信されてちらつく");
    }

    @Test
    @DisplayName("エンダーチェストも対象にする(遺物系はそこにしまわれている)")
    void alsoClearsTheEnderChest() {
        Player player = server.addPlayer();
        player.getEnderChest().setItem(0, stamped(Material.ELYTRA));

        int cleared = CollectionCommand.clearCreativeOrigin(player.getEnderChest());

        assertEquals(1, cleared);
        assertFalse(creativeOrigin(player.getEnderChest().getItem(0)));
    }

    @Test
    @DisplayName("剥がしたあとは印が本当に消えている(getOrDefault の既定値に落ちる)")
    void clearedMarkerReadsBackAsAbsent() {
        ItemStack stack = stamped(Material.ELYTRA);
        assertTrue(creativeOrigin(stack), "前提: 印が付いていること");

        ItemMeta meta = stack.getItemMeta();
        assertTrue(ItemData.of(meta).clearCreativeOrigin(), "初回は実際に消すので true");
        assertFalse(ItemData.of(meta).clearCreativeOrigin(), "既に無ければ書き込まず false");
        stack.setItemMeta(meta);

        assertFalse(creativeOrigin(stack));
    }

    /**
     * <b>drift ガード</b>: {@code /tf collection} 自体はプレイヤー向けなので登録側
     * ({@code TrinityForge#registerCommands})に {@code .requires} が付いておらず、
     * {@code unmark} は<b>自分で</b>権限を要求している。判定に使う権限ノードが
     * {@code TrinityForge#isTfAdmin} と食い違うと、<b>管理コマンドが誰でも撃てる形で漏れる</b>
     * (印を剥がせる＝図鑑ガードを実質無効化できる)のに、テストは全部緑のままになる。
     */
    @Test
    @DisplayName("unmark の権限ノードが TrinityForge.java の管理者判定と一致している")
    void adminPermissionNodeMatchesTheWiring() throws Exception {
        String wiring = Files.readString(Path.of("src/main/java/com/trinityforge/TrinityForge.java"));

        assertTrue(wiring.contains('"' + CollectionCommand.ADMIN_PERMISSION + '"'),
                "TrinityForge.java の管理者判定に使われている権限ノードと食い違っている: "
                        + CollectionCommand.ADMIN_PERMISSION);
    }
}
