package com.trinityforge.skilltree.runtime;

import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * プレステージの確認モーダル (実サーバ報告 2026-08-21「確認画面が出ない」→
 * 2026-08-23 再報告「トーテムアイコンを押すと勝手にプレステージされる」, W-188)。
 *
 * <p>プレステージは<b>そのツリーのパークを全部剥がしてレベルを0に戻す</b>ので、誤爆の代償が
 * 通常の解放とは桁違いに大きい。
 *
 * <p>⚠ <b>2026-08-21 の「同じマスをもう一度クリックで確定」方式は直っていなかった。</b>
 * 確定ボタンが押したのと同じマスに居座るので、ダブルクリックの2打目が 1 tick 後に描き直された
 * 確認状態へそのまま入り、プレイヤーから見れば「1回押したら確定した」になる。
 * ここで固定するのはその再発防止:
 * <ol>
 *   <li>1クリック目で<b>プレステージが起きない</b>（＝確認で止まっている）</li>
 *   <li>止まったことが<b>チャットに出る</b>（ツリーリセットと同じ作法。アイコンの文言だけだと
 *       スクロール位置や統合版クライアントの描画次第で気付けない）</li>
 *   <li><b>同じスロットを続けて押しても確定しない</b>（＝ダブルクリックで走らない）</li>
 *   <li>確認画面の「はい」を押せば確定する（確認が「押しても何も起きない」で終わらない）</li>
 *   <li>「いいえ」でツリーへ戻り、何も起きない</li>
 *   <li>トーテムの lore に<b>赤字でレベルが0に戻ると書いてある</b></li>
 * </ol>
 *
 * <p>{@code NativeSkillTreeMenuOverviewTest} と同じ流儀で、実物のカタログ／リポジトリ／
 * サービスを組み、ツリー供給元だけ固定リストへ差し替える。
 */
class NativeSkillTreeMenuPrestigeConfirmTest {

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private SqliteProgressionRepository repository;
    private NativeSkillTreeMenu menu;
    private NativePerkService perks;
    private NativeProgressionService progression;
    private NamespacedKey actionKey;
    private NamespacedKey valueKey;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("TrinityForge");
        player = server.addPlayer();

        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        repository = new SqliteProgressionRepository("jdbc:sqlite::memory:");
        progression = new NativeProgressionService(repository, catalog);
        perks = new NativePerkService(progression, () -> List.of(prestigeableTree()));
        // プレステージ判定は「そのスキルの行が在ること」が前提(行が無いと LEVEL_TOO_LOW)。
        // at-level 0 のツリーなので、行さえ作れば最初からプレステージできる。
        progression.grantExp(player.getUniqueId(), SkillId.MINING, 1.0);
        menu = new NativeSkillTreeMenu(plugin, progression, perks, ignored -> { });
        actionKey = new NamespacedKey(plugin, "skill_menu_action");
        valueKey = new NamespacedKey(plugin, "skill_menu_value");
    }

    @AfterEach
    void tearDown() throws Exception {
        repository.close();
        MockBukkit.unmock();
    }

    /** at-level 0 なので、EXPを積まなくても最初からプレステージ可能なツリー。 */
    private static SkillTree prestigeableTree() {
        SkillNode root = new SkillNode("A", "A", 0, SkillRole.MAIN, null, null, "STONE", 0,
                "", Map.of(), Map.of(), List.of(), List.of(), List.of());
        Prestige prestige = new Prestige(true, 0, "採掘王の栄光", "永続ボーナス",
                Map.of(), Map.of(), 1);
        return new SkillTree(SkillId.MINING, "採掘", "STONE", "2,10", prestige, Map.of("A", root));
    }

    @Test
    @DisplayName("プレステージは1クリック目で必ず止まり、確認が出る")
    void firstClickOnPrestigeAsksForConfirmationInsteadOfPrestiging() {
        menu.open(player);
        int slot = prestigeSlot();

        player.nextMessage(); // 開いた時点の未読を捨てる
        clickSlot(slot);
        server.getScheduler().performOneTick();

        assertFalse(hasPrestiged(),
                "1クリック目でプレステージが確定している。"
                        + "パークを全部剥がしてレベルを0に戻す操作なので、確認なしで走ってはいけない");

        String message = drainMessages();
        // ⚠ 「プレステージ」という語は当てにできない —— 文中に出るのは config の枠名
        //    (このツリーなら「採掘王の栄光」)。何が起きるかと、次に何を押すかで固定する。
        assertTrue(message.contains("確認画面") && message.contains("0に戻ります"),
                "確認したことがプレイヤーに伝わっていない（ツリーリセットは同じ場面でチャットに出す）。"
                        + " 実際に出たメッセージ: [" + message + "]");
    }

    @Test
    @DisplayName("同じスロットを続けて押してもプレステージは走らない（ダブルクリック誤爆）")
    void clickingTheSameSlotTwiceNeverPrestiges() {
        menu.open(player);
        int slot = prestigeSlot();

        // 実機のダブルクリックの再現: 2打目は 1 tick 後に開いた確認画面へ落ちる。
        clickSlot(slot);
        server.getScheduler().performOneTick();
        clickSlot(slot);
        server.getScheduler().performOneTick();

        assertFalse(hasPrestiged(),
                "同じマスを2回押しただけでプレステージが確定した。"
                        + "確定ボタンを押した位置へ置いてはいけない（2026-08-23 実サーバ報告の真因）");
    }

    @Test
    @DisplayName("確認画面の「はい」を押せばプレステージが確定する")
    void confirmButtonPrestiges() {
        menu.open(player);
        clickSlot(prestigeSlot());
        server.getScheduler().performOneTick();
        assertFalse(hasPrestiged(), "前提: 1クリック目では確定しない");

        int yes = slotWithAction("prestige-confirm");
        assertTrue(yes >= 0, "確認画面に「はい」のボタンが無い");
        clickSlot(yes);
        server.getScheduler().performOneTick();

        assertTrue(hasPrestiged(),
                "「はい」でも確定しない。確認が『押しても何も起きない』になっている");
    }

    @Test
    @DisplayName("確認画面の「いいえ」は何もせずツリーへ戻る")
    void cancelButtonDoesNothing() {
        menu.open(player);
        clickSlot(prestigeSlot());
        server.getScheduler().performOneTick();

        int no = slotWithAction("prestige-cancel");
        assertTrue(no >= 0, "確認画面に「いいえ」のボタンが無い");
        clickSlot(no);
        server.getScheduler().performOneTick();

        assertFalse(hasPrestiged(), "「いいえ」でプレステージが走っている");
        assertTrue(slotWithAction("prestige-confirm") < 0,
                "「いいえ」のあとも確認画面が開いたまま（ツリーへ戻っていない）");
    }

    @Test
    @DisplayName("「はい」は直前に押したスロットには置かれない")
    void confirmButtonNeverLandsOnTheSlotThatWasJustClicked() {
        // 純関数として全スロットで固定する。実機のトーテムの位置はビューポートで動くので、
        // 「たまたま今日は重ならない」では再発を止められない。
        for (int origin = 0; origin < 54; origin++) {
            assertTrue(NativeSkillTreeMenu.prestigeYesSlot(origin) != origin,
                    "スロット " + origin + " を押したとき、同じスロットに「はい」が置かれる。"
                            + "ダブルクリックの2打目がそのまま確定に入る");
        }
    }

    @Test
    @DisplayName("トーテムの lore に赤字でレベルが0に戻ると書いてある")
    void prestigeNodeCarriesTheRedWarningInItsLore() {
        menu.open(player);
        int slot = prestigeSlot();
        ItemStack totem = player.getOpenInventory().getTopInventory().getItem(slot);
        assertNotNull(totem, "プレステージノードが描かれていない");
        String lore = plainLore(totem);
        assertTrue(lore.contains("これはプレステージです") && lore.contains("0に戻ります"),
                "プレステージであることと、レベルが0に戻ることが lore に出ていない。"
                        + " 実際の lore: [" + lore + "]");
    }

    /** lore を1本の平文へ潰す。 */
    private static String plainLore(ItemStack item) {
        List<Component> lines = item.getItemMeta().lore();
        if (lines == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Component line : lines) {
            text.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        }
        return text.toString();
    }

    // ---- helpers ----------------------------------------------------------

    private boolean hasPrestiged() {
        return repository.loadPerkIds(player.getUniqueId()).orElseThrow()
                .stream().anyMatch(id -> id.contains("_perk_ng"));
    }

    /**
     * プレステージノードが載っているスロット。ビューポートは幹の起点(最下段)に開くので、
     * 幹の先端にあるプレステージノードまで北へスクロールしてから探す。
     */
    private int prestigeSlot() {
        for (int step = 0; step < 40; step++) {
            int found = slotWithAction("prestige");
            if (found >= 0) {
                return found;
            }
            int northSlot = slotWithAction("move-n");
            if (northSlot < 0) {
                break;
            }
            clickSlot(northSlot);
            server.getScheduler().performOneTick();
        }
        int found = slotWithAction("prestige");
        if (found >= 0) {
            return found;
        }
        return fail("プレステージノードが描画されていない（北へスクロールしても現れない）");
    }

    private int slotWithAction(String wanted) {
        Inventory top = player.getOpenInventory().getTopInventory();
        for (int slot = 0; slot < top.getSize(); slot++) {
            ItemStack item = top.getItem(slot);
            if (item == null || !item.hasItemMeta()) continue;
            String action = item.getItemMeta().getPersistentDataContainer()
                    .get(actionKey, PersistentDataType.STRING);
            if (wanted.equals(action)) {
                if ("prestige".equals(wanted)) {
                    assertNotNull(item.getItemMeta().getPersistentDataContainer()
                            .get(valueKey, PersistentDataType.STRING));
                }
                return slot;
            }
        }
        return -1;
    }

    /**
     * MockBukkit の {@code callEvent} には登録済みリスナーしか居ないので、
     * 既存のメニューテストと同じく {@link NativeSkillTreeMenu#onClick} を直接呼ぶ。
     */
    private void clickSlot(int slot) {
        menu.onClick(new InventoryClickEvent(
                player.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                slot, ClickType.LEFT, InventoryAction.PICKUP_ALL));
    }

    /** 溜まっているチャットを全部つなげて返す。 */
    private String drainMessages() {
        StringBuilder all = new StringBuilder();
        String next;
        while ((next = player.nextMessage()) != null) {
            all.append(next).append('\n');
        }
        return PlainTextComponentSerializer.plainText()
                .serialize(Component.text(all.toString()));
    }
}
