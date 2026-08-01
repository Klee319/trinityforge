package com.trinityforge.listeners;

import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>本番実装</b> {@link BrewStandOwners#blockPdc} の配線 (2026-07-31 レビュー指摘#1 / #2)。
 *
 * <h2>なぜこのテストが必要だったか</h2>
 * 醸造ゲートのテスト2本はどちらも {@link BrewStandOwners} をインターフェースごと差し替えるため、
 * <b>唯一の本番実装が1行も走っていなかった</b>。外れたときの症状は
 * 「ゲート付き醸造が誰にも成立しない(しかもログは FINE なので何も出ない)」という無言死なので、
 * 依存している Bukkit の前提をここで固定する:
 * <ol>
 *   <li>{@code BrewerInventory#getHolder()} が {@link BrewingStand} へ解決する
 *       (1.21.11 では共変オーバーライドで戻り値型そのものが {@code BrewingStand})。</li>
 *   <li>ブロックの PDC は {@link TileState} のスナップショットに乗るので、
 *       書いたあと {@code update()} を呼ばないと実体へ反映されない。</li>
 *   <li>読み書きに使うキーが {@link BrewOwnership}(所有者記録の単一定義)と<b>同一</b>である。</li>
 * </ol>
 *
 * <p>MockBukkit は使わない: {@code ServerMock} の未実装 API は {@code SKIPPED} に化けて
 * 検証そのものが消えるため、Mockito とリフレクションだけで固定する。
 */
class BrewStandOwnersBlockPdcTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    // ---- 前提1/2: Bukkit API の形そのもの ----

    @Test
    void brewerInventoryHolderIsTypedAsABrewingStand() throws Exception {
        assertEquals(BrewingStand.class,
                BrewerInventory.class.getMethod("getHolder").getReturnType(),
                "getHolder() が BrewingStand を返さなくなったら standOf は常に null を返し、"
                        + "ゲート付き醸造が誰にも成立しなくなる");
    }

    @Test
    void brewingStandIsATileStateSoItsPdcNeedsAnExplicitUpdate() {
        assertTrue(TileState.class.isAssignableFrom(BrewingStand.class),
                "PDC はスナップショット側に乗るので、書き込みは必ず update() と対でなければならない");
    }

    // ---- 本番実装の配線 ----

    @Test
    void rememberWritesTheBrewOwnershipKeyThroughTheHolderAndUpdatesTheBlock() {
        Plugin plugin = plugin();
        Stand stand = stand();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(OWNER);

        BrewStandOwners.blockPdc(plugin).remember(stand.inventory, player);

        InOrder order = inOrder(stand.pdc, stand.state);
        order.verify(stand.pdc).set(new BrewOwnership(plugin).lastBrewerKey(),
                PersistentDataType.STRING, OWNER.toString());
        order.verify(stand.state).update();
    }

    @Test
    void rememberDoesNotOverwriteAnAlreadyRecordedOwner() {
        // 先着優先(BrewOwnership の書き込み規則1)。上書きできると、他人の醸造の報酬を
        // 最後にクリックするだけで奪えるうえ、未解放プレイヤーが最後に触るだけで
        // 解放済みの台のゲート付き醸造を止められる。
        Plugin plugin = plugin();
        Stand stand = stand();
        when(stand.pdc.get(new BrewOwnership(plugin).lastBrewerKey(), PersistentDataType.STRING))
                .thenReturn(OWNER.toString());
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());

        BrewStandOwners.blockPdc(plugin).remember(stand.inventory, other);

        verify(stand.pdc, never()).set(org.mockito.ArgumentMatchers.any(NamespacedKey.class),
                org.mockito.ArgumentMatchers.eq(PersistentDataType.STRING),
                org.mockito.ArgumentMatchers.anyString());
        verify(stand.state, never()).update(); // 書き込み経路は必ず update() と対なので、これも証拠になる
    }

    @Test
    void replaceOverwritesTheRecordedOwner() {
        Plugin plugin = plugin();
        Stand stand = stand();
        when(stand.pdc.get(new BrewOwnership(plugin).lastBrewerKey(), PersistentDataType.STRING))
                .thenReturn(UUID.randomUUID().toString());
        Player claimant = mock(Player.class);
        when(claimant.getUniqueId()).thenReturn(OWNER);

        BrewStandOwners.blockPdc(plugin).replace(stand.inventory, claimant);

        verify(stand.pdc).set(new BrewOwnership(plugin).lastBrewerKey(),
                PersistentDataType.STRING, OWNER.toString());
        verify(stand.state).update();
    }

    @Test
    void ownerOfReadsTheSameKeyTheExperienceListenerWrites() {
        Plugin plugin = plugin();
        Stand stand = stand();
        when(stand.pdc.get(new BrewOwnership(plugin).lastBrewerKey(), PersistentDataType.STRING))
                .thenReturn(OWNER.toString());

        assertEquals(Optional.of(OWNER), BrewStandOwners.blockPdc(plugin).ownerOf(stand.inventory));
    }

    @Test
    void ownerOfIsEmptyWhenTheHolderIsNotABrewingStandOrTheValueIsBroken() {
        Plugin plugin = plugin();
        BrewerInventory notAStand = mock(BrewerInventory.class);
        when(notAStand.getHolder()).thenReturn(null);
        assertEquals(Optional.empty(), BrewStandOwners.blockPdc(plugin).ownerOf(notAStand));

        Stand stand = stand();
        when(stand.pdc.get(new BrewOwnership(plugin).lastBrewerKey(), PersistentDataType.STRING))
                .thenReturn("not-a-uuid");
        assertEquals(Optional.empty(), BrewStandOwners.blockPdc(plugin).ownerOf(stand.inventory),
                "壊れた値で醸造経路へ例外を投げてはいけない");
        assertEquals(Optional.empty(), BrewStandOwners.blockPdc(plugin).ownerOf(null));
    }

    @Test
    void onlineResolvesThroughTheServerAndToleratesNull() {
        Plugin plugin = plugin();
        Player player = mock(Player.class);
        when(plugin.getServer().getPlayer(OWNER)).thenReturn(player);

        assertSame(player, BrewStandOwners.blockPdc(plugin).online(OWNER));
        assertNull(BrewStandOwners.blockPdc(plugin).online(null));
    }

    // ---- 指摘#1: 所有者記録は1本しか無い ----

    @Test
    void theBrewingStandOwnerIsRecordedByExactlyOnePdcKeyInTheWholePlugin() {
        // 2026-07-31 に BrewUnlockListener が PdcKeys.BREW_STAND_OWNER という2本目の所有者記録を
        // 新設し、「誰に醸造を許可するか」と「誰にEXP/品質を付けるか」が別キーで決まる状態になっていた。
        // 書き込み規則も寿命も違う2本が同じブロックに同居する事故を再発させないため、
        // 所有者キーを名乗る NamespacedKey が BrewOwnership の1本だけであることを本文で縛る。
        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources()) {
            String body = read(source);
            String name = source.getFileName().toString();
            if (body.contains("brew_stand_owner")) {
                offenders.add(name + ": brew_stand_owner (所有者記録は BrewOwnership の last_brewer 1本)");
            }
            if (body.contains("\"last_brewer\"") && !name.equals("BrewOwnership.java")) {
                offenders.add(name + ": last_brewer をキー文字列で手書きしている"
                        + "(BrewOwnership#lastBrewerKey() を使うこと)");
            }
        }

        assertEquals(List.of(), offenders, "醸造所有者の記録を2本にしてはいけない");
    }

    private static List<Path> javaSources() {
        Path root = Path.of("src/main/java/com/trinityforge");
        assertTrue(Files.isDirectory(root), "ソースルートが見つからない: " + root.toAbsolutePath());
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".java")).toList();
            assertTrue(files.size() > 100, "ソースを列挙できていない: " + files.size() + " 件");
            return files;
        } catch (java.io.IOException ex) {
            throw new AssertionError("ソースを走査できない: " + root.toAbsolutePath(), ex);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new AssertionError("読めない: " + path.toAbsolutePath(), ex);
        }
    }

    // ---- ヘルパー ----

    private static Plugin plugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("trinityforge");
        // Paper の NamespacedKey(Plugin, key) は getName() ではなく Plugin#namespace() を読む
        // (1.21.11 で確認済み)。ここを stub しないと namespace が null で NamespacedKey が落ちる。
        when(plugin.namespace()).thenReturn("trinityforge");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BrewStandOwnersBlockPdcTest"));
        when(plugin.getServer()).thenReturn(mock(Server.class));
        return plugin;
    }

    /** 醸造台の {@code BlockState} とその PDC、そこから取れる {@link BrewerInventory}。 */
    private record Stand(BrewingStand state, PersistentDataContainer pdc, BrewerInventory inventory) {}

    private static Stand stand() {
        BrewingStand state = mock(BrewingStand.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(state.getPersistentDataContainer()).thenReturn(pdc);
        BrewerInventory inventory = mock(BrewerInventory.class);
        when(inventory.getHolder()).thenReturn(state);
        return new Stand(state, pdc, inventory);
    }
}
