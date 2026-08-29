package com.trinityforge.skilltree.runtime;

import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.repository.LoadResult;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.generator.PerkNaming;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Native Valhalla-style 54-slot skill-tree canvas with two-click confirmation. */
public final class NativeSkillTreeMenu implements Listener {

    private static final Component MENU_TITLE = Component.text("\uF808\uF001", NamedTextColor.WHITE)
            .font(Key.key("trinityforge", "skill_gui"));
    private static final Map<Integer, String> NAVIGATION_SLOTS = Map.of(
            0, "move-nw",
            4, "move-n",
            8, "move-ne",
            18, "move-w",
            26, "move-e",
            36, "move-sw",
            40, "move-s",
            44, "move-se");
    private static final List<String> SKILL_ORDER = List.of(
            "POWER", "SMITHING", "ENCHANTING", "ALCHEMY",
            "MINING", "WOODCUTTING", "DIGGING", "FARMING",
            "LIGHT_WEAPONS", "HEAVY_WEAPONS", "FISHING", "ARCHERY",
            "LIGHT_ARMOR", "HEAVY_ARMOR", "ARS_MAGIC", "ARS_SMITHING");

    /**
     * ツリーリセット確認中を表す pendingPerkId のプレフィックス。
     * perk ID は {@code <skill>_perk_<node>} 形式なのでこの値と衝突せず、ノード描画にも影響しない。
     */
    private static final String RESET_PENDING_PREFIX = "reset:";

    /** プレステージ確認画面: 警告アイコン(トーテム)を置くスロット。 */
    private static final int PRESTIGE_WARNING_SLOT = 13;
    /** プレステージ確認画面: 「はい」の既定スロット。 */
    private static final int PRESTIGE_YES_SLOT = 29;
    /** プレステージ確認画面: 「いいえ」の既定スロット。 */
    private static final int PRESTIGE_NO_SLOT = 33;

    private final Plugin plugin;
    private final NativeProgressionService progression;
    private final NativePerkService perks;
    private final Consumer<Player> refreshPerks;
    private final NamespacedKey actionKey;
    private final NamespacedKey valueKey;
    /**
     * プレイヤーごとの「最後に見ていた画面」(2026-08-06, W-30)。
     * 閉じて開き直しても、ツリー・スクロール位置・モード・ページが戻る。
     *
     * <p><b>保留中の解放確認(pendingPerkId)は意図的に持ち越さない。</b>
     * 持ち越すと「閉じて開いて1クリック」で解放が確定する経路ができ、誤爆が起きる。
     *
     * <p>ログアウトで捨てる(ログイン跨ぎでは復元しない)。PDCへ載せれば跨げるが、
     * PDCキーはバックアップ網羅性の対象で保守コストが増える一方、この状態は
     * 「その場の作業位置」でしかないため、セッション内に留める判断。
     */
    private final Map<UUID, View> lastView = new HashMap<>();

    public NativeSkillTreeMenu(Plugin plugin, NativeProgressionService progression,
                               NativePerkService perks, Consumer<Player> refreshPerks) {
        this.plugin = plugin;
        this.progression = progression;
        this.perks = perks;
        this.refreshPerks = refreshPerks;
        this.actionKey = new NamespacedKey(plugin, "skill_menu_action");
        this.valueKey = new NamespacedKey(plugin, "skill_menu_value");
    }

    public void open(Player player) {
        List<SkillTree> trees = orderedTrees();
        if (trees.isEmpty()) {
            player.sendMessage(Component.text("スキルツリー設定がありません。", NamedTextColor.RED));
            return;
        }
        View view = lastView.get(player.getUniqueId());
        if (view != null && perks.tree(view.skillId()) != null) {
            openInternal(player, view.skillId(), view.center(), null, view.mode(), view.page());
            return;
        }
        SkillTree tree = trees.getFirst();
        openInternal(player, tree.skill(), NativeSkillTreeCanvas.project(tree).start(), null,
                Mode.DETAIL, 0);
    }

    public void open(Player player, String rawSkillId) {
        SkillTree tree = perks.tree(rawSkillId);
        if (tree == null) {
            open(player);
            return;
        }
        // 同じツリーを開き直したときだけ位置を復元する(別ツリーの指定は、そのツリーの起点から)。
        View view = lastView.get(player.getUniqueId());
        if (view != null && view.skillId().equals(tree.skill())) {
            openInternal(player, view.skillId(), view.center(), null, view.mode(), view.page());
            return;
        }
        openInternal(player, tree.skill(), NativeSkillTreeCanvas.project(tree).start(), null,
                Mode.DETAIL, 0);
    }

    /** 描き終えた画面を「最後に見ていた画面」として覚える(2026-08-06, W-30)。 */
    private void remember(Player player, Session session) {
        lastView.put(player.getUniqueId(),
                new View(session.skillId, session.center, session.mode, session.page));
    }

    /** ログアウトで作業位置を捨てる(オフラインプレイヤーのぶんを溜め込まない)。 */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        lastView.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Re-renders and re-opens the menu on the next server tick. Every re-open triggered from
     * within {@link #onClick} must go through this method rather than {@link #openInternal}
     * directly: calling {@code player.openInventory} while still inside
     * {@link InventoryClickEvent} dispatch is a Bukkit anti-pattern (ghost cursor / client
     * desync), and deferring by one tick also moves the cache-miss progression/perk reload that
     * follows an unlock or prestige mutation out of the click-handling call stack so it can no
     * longer stall the event dispatch itself.
     */
    private void reopenNextTick(Player player, String skillId, NativeSkillTreeCanvas.Point center,
                                String pendingPerkId, Mode mode, int page) {
        plugin.getServer().getScheduler().runTask(plugin,
                () -> openInternal(player, skillId, center, pendingPerkId, mode, page));
    }

    /**
     * 「同じ画面のまま描き直す」用の短縮形。モード・中心座標・ページを保つので、
     * 呼び出し側で {@code session.mode}/{@code session.page} を書き写す必要が無い
     * (書き写し漏れでモードが勝手に戻る事故を機構で防ぐ)。
     */
    private void reopenNextTick(Player player, Session session, String pendingPerkId) {
        reopenNextTick(player, session.skillId, session.center, pendingPerkId,
                session.mode, session.page);
    }

    private void openInternal(Player player, String skillId, NativeSkillTreeCanvas.Point center,
                      String pendingPerkId, Mode mode, int page) {
        List<SkillTree> trees = orderedTrees();
        if (trees.isEmpty()) {
            player.sendMessage(Component.text("スキルツリー設定がありません。", NamedTextColor.RED));
            return;
        }
        SkillTree tree = perks.tree(skillId);
        if (tree == null) {
            tree = trees.getFirst();
        }
        // 一覧モード(2026-08-04新設): ツリーの中身は描画せず、全ツリーのアイコンだけを並べる。
        // 通常モードへ戻す/一覧へ入るのは常に select-skill / toggle-view の2アクションだけ。
        if (mode == Mode.OVERVIEW) {
            openOverview(player, trees, tree.skill(), center, pendingPerkId);
            return;
        }
        NativeSkillTreeCanvas canvas;
        try {
            canvas = NativeSkillTreeCanvas.project(tree);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("スキルツリーを描画できません: " + tree.skill() + " - " + ex.getMessage());
            player.sendMessage(Component.text("スキルツリー設定が不正です。", NamedTextColor.RED));
            return;
        }
        // パーク一覧モード(2026-08-05新設, W-29): 現ツリーのパークだけを一覧の体裁で並べる。
        if (mode == Mode.PERK_LIST) {
            openPerkList(player, trees, tree, canvas, center, page);
            return;
        }
        NativeSkillTreeCanvas.Point safeCenter = canvas.clamp(center);
        Session holder = new Session(tree.skill(), safeCenter, pendingPerkId, Mode.DETAIL, 0);
        Inventory inventory = plugin.getServer().createInventory(holder, 54, MENU_TITLE);
        holder.inventory = inventory;

        Set<String> owned = loadOwnedPerkIds(player.getUniqueId());
        Set<String> locked = Set.copyOf(com.trinityforge.pdc.PlayerData.of(player).lockedPerks());
        var snapshot = progression.snapshot(player.getUniqueId());
        int level = snapshot.skillOrDefault(tree.skill(), 100).level();
        Map<String, SkillNode> nodesByPerk = new HashMap<>();
        for (SkillNode node : tree.nodes().values()) {
            nodesByPerk.put(PerkNaming.perkId(tree.skill(), node.id()), node);
        }

        for (Map.Entry<Integer, NativeSkillTreeCanvas.Cell> entry
                : canvas.viewport(safeCenter).entrySet()) {
            if (entry.getValue() instanceof NativeSkillTreeCanvas.NodeCell nodeCell) {
                inventory.setItem(entry.getKey(), nodeIcon(
                        tree, nodeCell, nodesByPerk.get(nodeCell.perkId()), level,
                        snapshot.availablePoints(), snapshot.skillOrDefault(tree.skill(), 100).prestige(),
                        owned, locked, pendingPerkId));
            } else if (entry.getValue() instanceof NativeSkillTreeCanvas.ConnectorCell connector) {
                boolean anyUnlocked = false;
                boolean anyUnlockable = false;
                for (String owner : connector.ownerPerkIds()) {
                    NodeStatus ownerStatus = status(
                            tree, owner, nodesByPerk.get(owner), level, snapshot.availablePoints(),
                            snapshot.skillOrDefault(tree.skill(), 100).prestige(),
                            owned, pendingPerkId);
                    anyUnlocked |= ownerStatus.unlocked();
                    anyUnlockable |= ownerStatus.unlockable();
                }
                SkillTreeGuiVisuals.ConnectorState connectorState = anyUnlocked
                        ? SkillTreeGuiVisuals.ConnectorState.UNLOCKED
                        : anyUnlockable
                                ? SkillTreeGuiVisuals.ConnectorState.UNLOCKABLE
                                : SkillTreeGuiVisuals.ConnectorState.LOCKED;
                inventory.setItem(entry.getKey(), display(
                        SkillTreeGuiVisuals.connector(connectorState, connector.suffix()),
                        Component.text(" "), List.of()));
            }
        }

        NAVIGATION_SLOTS.forEach((slot, action) -> inventory.setItem(slot,
                button(SkillTreeGuiVisuals.control(action), action, "",
                        Component.text(directionLabel(action), NamedTextColor.WHITE), List.of())));
        renderSkillSelector(inventory, trees, tree.skill(), snapshot);
        renderModeButtons(inventory, Mode.DETAIL, null);
        remember(player, holder);
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Session session)
                || !(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= 54) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String action = clicked.getItemMeta().getPersistentDataContainer()
                .get(actionKey, PersistentDataType.STRING);
        String value = clicked.getItemMeta().getPersistentDataContainer()
                .get(valueKey, PersistentDataType.STRING);
        if (action == null) {
            // プレステージ確認画面はモーダル。余白をクリックしても描き直さない
            // (描き直すと pendingPerkId が落ち、「はい」を押しても無反応な画面が残る)。
            if (session.mode == Mode.PRESTIGE_CONFIRM) {
                return;
            }
            if (session.pendingPerkId != null) {
                reopenNextTick(player, session, null);
            }
            return;
        }
        switch (action) {
            case "move-nw", "move-n", "move-ne", "move-e",
                    "move-se", "move-s", "move-sw", "move-w" -> move(player, session, action);
            case "select-skill" -> {
                SkillTree selected = perks.tree(value);
                if (selected != null) {
                    // アイコンをクリックしたら一覧/選択バーどちらから来ても常に通常モードへ戻る
                    // (2026-08-04: 一覧モードの「そのツリーの位置へ移動」要件と同じ経路)。
                    reopenNextTick(player, selected.skill(),
                            NativeSkillTreeCanvas.project(selected).start(), null, Mode.DETAIL, 0);
                }
            }
            // 2026-08-04新設: 通常モードとスキルアイコンだけの一覧モードを切り替える。
            // パーク一覧モードから押したときも一覧モードへ入る(3モードを1つのボタンで回さない)。
            case "toggle-view" -> reopenNextTick(
                    player, session.skillId, session.center, null,
                    session.mode == Mode.OVERVIEW ? Mode.DETAIL : Mode.OVERVIEW, 0);
            // 2026-08-05新設(W-29): 通常モードと現ツリーのパーク一覧モードを切り替える。
            case "toggle-perk-list" -> reopenNextTick(
                    player, session.skillId, session.center, null,
                    session.mode == Mode.PERK_LIST ? Mode.DETAIL : Mode.PERK_LIST, 0);
            case "perk-page" -> reopenNextTick(
                    player, session.skillId, session.center, null, Mode.PERK_LIST, parsePage(value));
            case "jump-perk" -> jumpToPerk(player, session, value);
            case "node" -> handleNode(player, session, value);
            case "prestige" -> handlePrestige(player, session, value, event.getRawSlot());
            case "prestige-confirm" -> confirmPrestige(player, session, value);
            case "prestige-cancel" -> reopenNextTick(
                    player, session.skillId, session.center, null, Mode.DETAIL, 0);
            default -> { }
        }
    }

    /**
     * パーク一覧(W-29)でクリックされたパークのマスを中心に置いて通常モードへ戻る。
     * 中心は {@link NativeSkillTreeCanvas#clamp} を通すので、端のパークは寄った位置に出る
     * (ビューポート外へ出さないため)。
     */
    private void jumpToPerk(Player player, Session session, String perkId) {
        if (perkId == null || perkId.isEmpty()) return;
        SkillTree tree = perks.tree(session.skillId);
        if (tree == null) return;
        NativeSkillTreeCanvas canvas;
        try {
            canvas = NativeSkillTreeCanvas.project(tree);
        } catch (RuntimeException ex) {
            return;
        }
        NativeSkillTreeCanvas.NodeCell cell = canvas.nodes().get(perkId);
        if (cell == null) return;
        reopenNextTick(player, session.skillId, canvas.clamp(cell.point()), null, Mode.DETAIL, 0);
    }

    /** ページ送りボタンの value(0始まりのページ番号)。壊れていたら先頭ページへ落とす。 */
    private static int parsePage(String value) {
        if (value == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }

    private void move(Player player, Session session, String action) {
        SkillTree tree = perks.tree(session.skillId);
        if (tree == null) return;
        NativeSkillTreeCanvas canvas = NativeSkillTreeCanvas.project(tree);
        NativeSkillTreeCanvas.Point moved = canvas.move(session.center, direction(action));
        reopenNextTick(player, session.skillId, moved,
                moved.equals(session.center) ? session.pendingPerkId : null,
                session.mode, session.page);
    }

    private void handleNode(Player player, Session session, String nodeId) {
        SkillTree tree = perks.tree(session.skillId);
        if (tree == null) return;
        SkillNode node = tree.nodes().get(nodeId);
        if (node == null) return;
        String perkId = PerkNaming.perkId(tree.skill(), nodeId);

        // 機能アイテム(2026-07-27): メインハンドに持った状態でノードをクリックすると発動する。
        // 通常の解放フローより先に判定する — 持っている間は解放操作にならない。
        String function = heldFunction(player);
        if (SkillTreeItems.NODE_LOCK.equals(function)) {
            applyNodeLock(player, session, tree, perkId);
            return;
        }
        if (SkillTreeItems.TREE_RESET.equals(function)) {
            applyTreeReset(player, session, tree);
            return;
        }

        if (!perkId.equals(session.pendingPerkId)) {
            var snapshot = progression.snapshot(player.getUniqueId());
            Set<String> owned = loadOwnedPerkIds(player.getUniqueId());
            var check = NativePerkService.validateUnlock(
                    tree, node, snapshot.skillOrDefault(tree.skill(), 100).level(),
                    snapshot.availablePoints(), owned);
            if (check != NativePerkService.UnlockResult.ELIGIBLE) {
                player.sendMessage(Component.text(blockedReason(check), NamedTextColor.RED));
                reopenNextTick(player, session, null);
                return;
            }
            reopenNextTick(player, session, perkId);
            return;
        }
        var result = perks.unlock(player.getUniqueId(), tree.skill(), nodeId);
        if (result == NativePerkService.UnlockResult.UNLOCKED) {
            refreshPerks.accept(player);
        }
        player.sendMessage(Component.text("Unlock: " + result,
                result == NativePerkService.UnlockResult.UNLOCKED
                        ? NamedTextColor.GREEN : NamedTextColor.RED));
        reopenNextTick(player, session, null);
    }

    /**
     * スキルノードロック: 解放済みノードのロックを反転する。ロックを「付ける」ときだけアイテムを1個消費し、
     * 「外す」ときは消費しない(外すのにコストを取ると、掛け直せなくなって詰む)。
     */
    private void applyNodeLock(Player player, Session session, SkillTree tree, String perkId) {
        if (!loadOwnedPerkIds(player.getUniqueId()).contains(perkId)) {
            player.sendMessage(Component.text("未解放のノードはロックできません。", NamedTextColor.RED));
            reopenNextTick(player, session, null);
            return;
        }
        var data = com.trinityforge.pdc.PlayerData.of(player);
        boolean nowLocked = data.lockedPerks().contains(perkId);
        if (nowLocked) {
            data.toggleLockedPerk(perkId);
            player.sendMessage(Component.text("ノードのロックを解除しました。", NamedTextColor.YELLOW));
        } else {
            data.toggleLockedPerk(perkId);
            consumeHeldItem(player);
            player.sendMessage(Component.text(
                    "ノードをロックしました（プレステージしても解放が維持されます）。", NamedTextColor.GREEN));
        }
        reopenNextTick(player, session, null);
    }

    /**
     * スキルツリーリセット: レベルとプレステージ段を維持したまま、このツリーのノードを全解除してSPを返却する。
     * 誤爆が致命的なので2クリック確認を挟む(通常の解放/プレステージと同じ作法)。
     */
    private void applyTreeReset(Player player, Session session, SkillTree tree) {
        String pendingToken = RESET_PENDING_PREFIX + tree.skill();
        if (!pendingToken.equals(session.pendingPerkId)) {
            player.sendMessage(Component.text(
                    "「" + tree.displayName() + "」をリセットします。もう一度ノードをクリックして確定してください。",
                    NamedTextColor.YELLOW));
            reopenNextTick(player, session, pendingToken);
            return;
        }
        var result = perks.resetTree(player.getUniqueId(), tree.skill());
        switch (result) {
            case RESET -> {
                consumeHeldItem(player);
                refreshPerks.accept(player);
                player.sendMessage(Component.text(
                        "スキルツリーをリセットしました（レベルは維持、SPを返却）。", NamedTextColor.GREEN));
            }
            case NOTHING_TO_RESET -> player.sendMessage(
                    Component.text("解除できるノードがありません。", NamedTextColor.RED));
            default -> player.sendMessage(
                    Component.text("スキルツリーをリセットできませんでした。", NamedTextColor.RED));
        }
        reopenNextTick(player, session, null);
    }

    /** メインハンドのTFカタログアイテムが機能アイテムなら、その機能IDを返す(それ以外は null)。 */
    private static String heldFunction(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        return SkillTreeItems.functionOf(held);
    }

    /** メインハンドのアイテムを1個消費する。 */
    private static void consumeHeldItem(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) return;
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held.getAmount() <= 0 ? null : held);
    }

    /**
     * プレステージ枠(トーテム)を押したときの入口。<b>ここでは決してプレステージを実行しない</b> ──
     * 必ず専用の確認モーダル({@link #openPrestigeConfirm})を開く。
     *
     * <p>⚠ <b>2026-08-21 に入れた「同じマスをもう一度クリックで確定」方式は実サーバで直らなかった</b>
     * (2026-08-23 再報告「トーテムアイコンを押すと勝手にプレステージされる」)。原因は方式そのもので、
     * 確定ボタンが<b>押したのと同じマスに居座る</b>こと。1 tick(50ms)後に確認状態へ描き直されるので、
     * ダブルクリック(統合版やタッチ操作では素の操作として出る)の2打目がそこへそのまま入り、
     * プレイヤーから見れば「1回押したら確定した」になる。
     * 別画面へ移し、さらに{@link #prestigeYesSlot}で<b>直前に押したスロットには「はい」を置かない</b>
     * ことで、2打目がどこへ落ちても確定にならないようにした。
     */
    private void handlePrestige(Player player, Session session, String perkId, int originSlot) {
        SkillTree tree = perks.tree(session.skillId);
        if (tree == null || tree.prestige() == null) return;
        var skill = progression.snapshot(player.getUniqueId())
                .skillOrDefault(tree.skill(), 100);
        int tier = prestigeTier(perkId);
        if (!prestigeUnlockable(tree, skill.prestige(), skill.level(), tier)) {
            player.sendMessage(Component.text(
                    skill.prestige() >= tier ? "このプレステージは解放済みです。"
                            : "プレステージ条件を満たしていません。",
                    NamedTextColor.RED));
            reopenNextTick(player, session, null);
            return;
        }
        // アイコンの lore だけだと、スクロール位置や統合版クライアントの描画次第で気付けない。
        // ツリーリセット(applyTreeReset)と同じく、チャットにも何が起きるかを出す。
        player.sendMessage(Component.text(
                "「" + prestigeLabel(tree) + "」の確認画面を開きます（" + tier + "回目）。"
                        + "このツリーの解放済みパークは全て外れ（SPは返却／ロック中のパークは維持）、"
                        + prestigeLevelNotice(tree)
                        + " 実行するには確認画面の「はい」を押してください。",
                NamedTextColor.YELLOW));
        plugin.getServer().getScheduler().runTask(plugin, () ->
                openPrestigeConfirm(player, session.skillId, session.center, perkId, originSlot));
    }

    /**
     * 確認モーダルの「はい」。<b>この画面から以外は絶対に通さない</b>
     * (ツリー本体には {@code prestige-confirm} のボタンを一切描かないが、
     * 将来どこかへ紛れ込んでも実行されないよう、モードと対象IDの両方を突き合わせる)。
     */
    private void confirmPrestige(Player player, Session session, String perkId) {
        if (session.mode != Mode.PRESTIGE_CONFIRM
                || perkId == null || !perkId.equals(session.pendingPerkId)) {
            return;
        }
        SkillTree tree = perks.tree(session.skillId);
        if (tree == null || tree.prestige() == null) return;
        var skill = progression.snapshot(player.getUniqueId())
                .skillOrDefault(tree.skill(), 100);
        // 確認画面を開いてから条件が変わっている可能性があるので、実行直前にもう一度見る。
        if (!prestigeUnlockable(tree, skill.prestige(), skill.level(), prestigeTier(perkId))) {
            player.sendMessage(Component.text(
                    "プレステージ条件を満たしていません。", NamedTextColor.RED));
            reopenNextTick(player, session.skillId, session.center, null, Mode.DETAIL, 0);
            return;
        }
        var result = perks.prestige(player.getUniqueId(), session.skillId);
        if (result == NativePerkService.PrestigeResult.PRESTIGED) {
            refreshPerks.accept(player);
        }
        player.sendMessage(Component.text("Prestige: " + result,
                result == NativePerkService.PrestigeResult.PRESTIGED
                        ? NamedTextColor.GREEN : NamedTextColor.RED));
        reopenNextTick(player, session.skillId, NativeSkillTreeCanvas.project(tree).start(), null,
                Mode.DETAIL, 0);
    }

    /** そのティアのプレステージが今まさに解放できるか。判定を1箇所に集約する。 */
    private static boolean prestigeUnlockable(SkillTree tree, int currentPrestige, int level,
                                              int tier) {
        return tree.prestige() != null
                && tier == currentPrestige + 1
                && level >= tree.prestige().atLevel();
    }

    /**
     * 確認モーダルの「はい」を置くスロット。<b>直前に押したスロットとは必ず別になる。</b>
     *
     * <p>ダブルクリックの2打目は、1 tick 後に開くこの画面へ落ちる。同じ位置に確定ボタンがあると
     * 「押したら確定した」に逆戻りするので、ぶつかるときは「はい」と「いいえ」を入れ替える
     * (＝2打目は必ず<b>取り消し</b>になる)。
     */
    static int prestigeYesSlot(int originSlot) {
        return originSlot == PRESTIGE_YES_SLOT ? PRESTIGE_NO_SLOT : PRESTIGE_YES_SLOT;
    }

    /**
     * プレステージ専用の確認モーダル。ツリーの中身もナビも描かず、赤字の警告と「はい」「いいえ」
     * だけを置く。ここは {@code remember} を呼ばない ── 覚えるとメニューを開き直しただけで
     * 確定ボタンのある画面が出てしまう。
     */
    private void openPrestigeConfirm(Player player, String skillId,
                                     NativeSkillTreeCanvas.Point center, String perkId,
                                     int originSlot) {
        SkillTree tree = perks.tree(skillId);
        if (tree == null || tree.prestige() == null) return;
        Session holder = new Session(skillId, center, perkId, Mode.PRESTIGE_CONFIRM, 0);
        Inventory inventory = plugin.getServer().createInventory(holder, 54, MENU_TITLE);
        holder.inventory = inventory;

        List<Component> warning = new ArrayList<>(prestigeWarningLore(tree));
        warning.add(separatorLine());
        warning.add(Component.text(prestigeTier(perkId) + " 回目のプレステージです。",
                NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        inventory.setItem(PRESTIGE_WARNING_SLOT, display(
                new SkillTreeGuiVisuals.Visual(Material.TOTEM_OF_UNDYING, null),
                Component.text("「" + prestigeLabel(tree) + "」でプレステージしますか？",
                        NamedTextColor.RED),
                warning));

        int yesSlot = prestigeYesSlot(originSlot);
        int noSlot = yesSlot == PRESTIGE_YES_SLOT ? PRESTIGE_NO_SLOT : PRESTIGE_YES_SLOT;
        inventory.setItem(yesSlot, button(Material.LIME_DYE, "prestige-confirm", perkId,
                Component.text("はい、プレステージする", NamedTextColor.GREEN),
                List.of(Component.text("押した瞬間に実行されます。取り消せません。",
                        NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))));
        inventory.setItem(noSlot, button(Material.BARRIER, "prestige-cancel", "",
                Component.text("いいえ、やめる", NamedTextColor.WHITE),
                List.of(Component.text("何もせずスキルツリーへ戻ります。",
                        NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))));
        player.openInventory(inventory);
    }

    /**
     * プレステージ枠に必ず出す赤字の警告。確認モーダルとツリー/パーク一覧のアイコンで
     * <b>同じ関数</b>を使う ── 2箇所で書き分けると、片方だけ「レベルが0に戻る」が抜けて嘘になる。
     */
    private static List<Component> prestigeWarningLore(SkillTree tree) {
        return List.of(
                warningLine("⚠ これはプレステージです"),
                warningLine("解放すると永続バフを獲得しますが、"),
                warningLine("このツリーの解放済みパークは全て外れます（SPは返却）。"),
                warningLine(prestigeLevelNotice(tree)),
                Component.text("🔒 ロック中のパークだけは維持されます。", NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false));
    }

    private static Component warningLine(String text) {
        return Component.text(text, NamedTextColor.RED).decoration(TextDecoration.ITALIC, false);
    }

    private static Component separatorLine() {
        return Component.text("────────────────", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** プレステージ枠の表示名(未設定なら生成器と同じ既定名)。 */
    private static String prestigeLabel(SkillTree tree) {
        String name = tree.prestige() == null ? null : tree.prestige().name();
        return name == null || name.isBlank() ? tree.displayName() + " プレステージ" : name;
    }

    /**
     * プレステージ後にレベルがどうなるかの一文。POWERだけは0リセットではなく
     * 他スキルの現在レベルから再導出される({@link NativePerkService} の 2026-08-04 修正)ので、
     * 同じ文言を出すと嘘になる。
     */
    private static String prestigeLevelNotice(SkillTree tree) {
        return SkillId.POWER.equals(tree.skill())
                ? "レベルは他スキルの現在レベルから引き直されます。"
                : "スキルレベルは0に戻ります。";
    }

    /**
     * パークのフレーバー説明文を lore の行へ整形する。説明が空なら空リスト。
     *
     * <p>通常モード({@link #nodeIcon})とパーク一覧({@link #perkListIcon})の<b>両方から呼ぶ</b>。
     * 同じ文字列を2箇所で別々に整形すると、片方だけ色コードが素通りするような食い違いが起きる。
     * {@code \n} で複数行に分割し、editor で付けた legacy の {@code &} 色コードを解釈する。
     * 色を書いていない行は既定のグレーへ、lore 既定のイタリックは無効化する。
     */
    private static List<Component> descriptionLines(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        for (String line : description.split("\n", -1)) {
            lines.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line)
                    .colorIfAbsent(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return List.copyOf(lines);
    }

    private ItemStack nodeIcon(SkillTree tree, NativeSkillTreeCanvas.NodeCell cell, SkillNode node,
                               int level, long availablePoints, int prestige,
                               Set<String> owned, Set<String> lockedPerks, String pendingPerkId) {
        NodeStatus status = status(
                tree, cell.perkId(), node, level, availablePoints, prestige, owned, pendingPerkId);
        boolean pending = cell.perkId().equals(pendingPerkId);
        Material icon = material(cell.perk().icon(), material(tree.icon(), Material.STONE));
        String action = node == null
                ? cell.perkId().contains("_perk_ng") ? "prestige" : ""
                : "node";
        String value = node == null ? cell.perkId() : node.id();
        Component name = Component.text(
                pending ? "「" + cell.perk().name() + "」を解放しますか？" : cell.perk().name(),
                status.unlocked ? NamedTextColor.GREEN
                        : status.unlockable ? NamedTextColor.AQUA : NamedTextColor.GRAY);
        // プレステージ枠だけは、何が起きるかを赤字で必ず出す(2026-08-23, W-188)。
        // 生成器が入れる説明文は「永続ボーナス」程度なので、レベルが0に戻ることが読み取れない。
        boolean prestigeNode = "prestige".equals(action);
        List<Component> lore = new ArrayList<>(descriptionLines(cell.perk().description()));
        if (prestigeNode) {
            if (!lore.isEmpty()) {
                lore.add(separatorLine());
            }
            lore.addAll(prestigeWarningLore(tree));
        }
        lore.add(separatorLine());
        lore.add(Component.text("必要Lv: " + cell.perk().requiredLv() + " / 現在Lv: " + level,
                level >= cell.perk().requiredLv() ? NamedTextColor.GRAY : NamedTextColor.RED));
        lore.add(Component.text("コスト: " + cell.perk().cost() + " / 所持: " + availablePoints,
                NamedTextColor.GRAY));
        lore.add(Component.text(status.unlocked ? "解放済み"
                        : pending ? "もう一度クリックして確定"
                        : status.unlockable
                                ? prestigeNode ? "クリックすると確認画面が開きます" : "クリックして確認"
                                : "解放条件を満たしていません",
                pending ? NamedTextColor.YELLOW
                        : status.unlockable ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY));
        if (lockedPerks.contains(cell.perkId())) {
            lore.add(Component.text("🔒 ロック中（プレステージしても維持）", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
        }
        SkillTreeGuiVisuals.Visual visual = action.isEmpty()
                ? new SkillTreeGuiVisuals.Visual(icon, null)
                : SkillTreeGuiVisuals.node(status.unlocked, status.unlockable, pending, icon);
        return action.isEmpty()
                ? display(visual, name, lore)
                : button(visual, action, value, name, lore);
    }

    private NodeStatus status(SkillTree tree, String perkId, SkillNode node,
                              int level, long availablePoints, int prestige,
                              Set<String> owned, String pendingPerkId) {
        if (node != null) {
            NativePerkService.UnlockResult result = NativePerkService.validateUnlock(
                    tree, node, level, availablePoints, owned);
            return new NodeStatus(
                    result == NativePerkService.UnlockResult.ALREADY_UNLOCKED,
                    result == NativePerkService.UnlockResult.ELIGIBLE,
                    perkId.equals(pendingPerkId));
        }
        if (perkId.contains("_perk_ng")) {
            int tier = prestigeTier(perkId);
            boolean unlocked = prestige >= tier;
            boolean unlockable = !unlocked && tier == prestige + 1
                    && tree.prestige() != null && level >= tree.prestige().atLevel();
            return new NodeStatus(unlocked, unlockable, perkId.equals(pendingPerkId));
        }
        return new NodeStatus(true, false, false);
    }

    private void renderSkillSelector(
            Inventory inventory, List<SkillTree> trees, String selectedSkill,
            com.trinityforge.progression.core.PlayerProgression snapshot) {
        int selected = 0;
        for (int i = 0; i < trees.size(); i++) {
            if (trees.get(i).skill().equals(selectedSkill)) {
                selected = i;
                break;
            }
        }
        // 2026-08-04: 末尾1枠(offset +4 = スロット53 = SkillTreeOverviewLayout.TOGGLE_SLOT)は
        // 一覧モード切替ボタンに固定で明け渡す(通常/一覧の両方で同じスロットに置く)。
        // 2026-08-05(W-29): 先頭1枠(offset -4 = スロット45 = PERK_LIST_SLOT)も
        // パーク一覧切替ボタン(時計)へ明け渡したので、選択バーは 7 枠(46-52)になった。
        for (int offset = -3; offset <= 3; offset++) {
            SkillTree tree = trees.get(Math.floorMod(selected + offset, trees.size()));
            var skill = snapshot.skillOrDefault(tree.skill(), 100);
            String suffix = skill.prestige() > 0 ? " " + roman(skill.prestige()) : "";
            List<Component> lore = new ArrayList<>(List.of(
                    Component.text("レベル: " + skill.level(), NamedTextColor.GRAY),
                    Component.text("EXP: " + Math.round(skill.residualExp()), NamedTextColor.GRAY),
                    Component.text("スキルポイント: " + snapshot.availablePoints(),
                            NamedTextColor.GRAY)));
            lore.addAll(dailyRateLore(snapshot.playerId(), tree.skill()));
            inventory.setItem(49 + offset, button(
                    SkillTreeGuiVisuals.skill(
                            tree.skill(), material(tree.icon(), Material.NETHER_STAR)),
                    "select-skill", tree.skill(),
                    Component.text(tree.displayName() + suffix,
                            offset == 0 ? NamedTextColor.GOLD : NamedTextColor.WHITE),
                    lore));
        }
    }

    /**
     * 日次逓減(直近24時間の稼ぎで薄まるEXP取得量)の行。逓減が無効なら空。
     *
     * <p>2026-08-18 に足した。逓減自体は 2026-07-31 から動いていたが、<b>倍率を確認できる画面が
     * 1つも無かった</b>ため「なんとなくEXPが渋い」としか分からなかった。段が離散なのは
     * 「あと何EXPで落ちるか数えられるように」という設計なので、その数字をここで出す。
     *
     * <p>2026-08-25: 「戻るまでの残り時間が、下がった通知のときにしか見えない」というユーザー報告を
     * 受けて2点足した。(1) <b>この lore を通常モードの選択バーだけでなく、一覧モード
     * （{@link #openOverview}）の各ツリーアイコンにも出す</b>。従来はここが「レベル/プレステージ/
     * 解放済みノード数」しか出さず、EXP取得量が下がっている最中でも一覧からは分からなかった。
     * (2) <b>「次の段階まで」の残り時間を足す</b>。「段階的に戻る」設計（{@link DailyExpDiminishing}
     * のコメント参照）なのに、従来は「完全に等倍へ戻るまで」の1本しか出しておらず、途中経過が
     * 見えなかった（{@link DailyExpDiminishing.Status#millisUntilImproved()} は最初から存在して
     * いたが production から一度も呼ばれていなかった）。EXPバッジ（{@code ×70%}等）自体には
     * 手を入れない（ユーザー確定: バッジへ他の係数を足さない）── ここは lore だけの変更。
     *
     * <p><b>⚠ 回復までの時間は「そのスキルを稼がずにいる」前提の目安</b>。蓄積は 2026-08-18 から
     * 永続化されており、オフライン時間も同じ式で減衰するので、ログアウトして待っても同じだけ掛かる
     * （それ以前の「再ログインで即リセット」はもう起きない）。
     * 表示する時間には W-154 の強制解除（発動から {@code lock-release-hours}）も織り込んである
     * ── 2026-08-21 まではここが指数減衰だけの見積りで、出荷設定では平然と 24 時間を超える数字を
     * 出していた（実際には遅くとも 24 時間で等倍へ戻る）。
     */
    private List<Component> dailyRateLore(java.util.UUID playerId, String skillId) {
        com.trinityforge.progression.DailyExpDiminishing.Status status =
                progression.dailyExpRateStatus(playerId, skillId);
        if (status == null) {
            return List.of();
        }
        if (status.atFullRate()) {
            String untilDrop = status.expUntilNextStep() < 0 ? null
                    : com.trinityforge.progression.DailyExpRateText.exp(status.expUntilNextStep());
            if (untilDrop == null) {
                return List.of();
            }
            return List.of(
                    Component.text("EXP取得量: 100%", NamedTextColor.GREEN),
                    Component.text("あと " + untilDrop + " EXP でこのスキルの取得量が下がります",
                            NamedTextColor.DARK_GRAY));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("EXP取得量: "
                + com.trinityforge.progression.DailyExpRateText.percent(status.multiplier())
                + "（稼ぎすぎによる逓減）", NamedTextColor.RED));
        String untilImproved =
                com.trinityforge.progression.DailyExpRateText.duration(status.millisUntilImproved());
        if (untilImproved != null) {
            lines.add(Component.text("次の段階まで: " + untilImproved, NamedTextColor.DARK_GRAY));
        }
        String untilFull = com.trinityforge.progression.DailyExpRateText.duration(status.millisUntilFull());
        if (untilFull != null) {
            lines.add(Component.text("休むと戻ります: 等倍まで " + untilFull, NamedTextColor.DARK_GRAY));
        }
        return lines;
    }

    /**
     * 最下段の両端に固定するモード切替ボタン(左端=パーク一覧の時計 / 右端=全ツリー一覧)。
     * 全モードで同じ2スロットへ置くので、どのモードから見ても押す場所が変わらない
     * (2026-08-04 の toggle-view と同じ方針。時計は 2026-08-05 の W-29 で追加)。
     *
     * @param pageLabel パーク一覧モードのときだけ渡すページ表記(それ以外は null)
     */
    private void renderModeButtons(Inventory inventory, Mode mode, String pageLabel) {
        List<Component> perkListLore = new ArrayList<>();
        perkListLore.add(Component.text(mode == Mode.PERK_LIST
                        ? "ツリーの詳細表示へ戻ります。"
                        : "このスキルツリーの全パークを一覧表示します。", NamedTextColor.GRAY));
        if (pageLabel != null) {
            perkListLore.add(Component.text(pageLabel, NamedTextColor.DARK_GRAY));
        }
        inventory.setItem(SkillTreeOverviewLayout.PERK_LIST_SLOT, button(
                SkillTreeGuiVisuals.control("perk-list"), "toggle-perk-list", "",
                Component.text(mode == Mode.PERK_LIST ? "通常表示に戻る" : "パーク一覧に切り替え",
                        NamedTextColor.WHITE),
                perkListLore));
        inventory.setItem(SkillTreeOverviewLayout.TOGGLE_SLOT, button(
                SkillTreeGuiVisuals.control("toggle-view"), "toggle-view", "",
                Component.text(mode == Mode.OVERVIEW ? "通常表示に戻る" : "一覧表示に切り替え",
                        NamedTextColor.WHITE),
                List.of(Component.text(mode == Mode.OVERVIEW
                        ? "直前に見ていたツリーの詳細表示へ戻ります。"
                        : "全スキルツリーをアイコンだけで一覧表示します。", NamedTextColor.GRAY))));
    }

    /**
     * パーク一覧モード(2026-08-05新設, W-29): 現在のスキルツリーのパークだけを、全ツリー一覧
     * ({@link #openOverview})と同じ格子・同じ体裁で並べる。クリックすると{@code jump-perk}で
     * そのパークの座標を中心に据えて通常モードへ戻る。
     *
     * <p>ツリー数(16)は格子容量(20)に収まるが、パーク数は POWER で 35 を超えるので
     * ここだけページ送りを持つ(黙って切り落とすと「一覧に無いパークがある」ことに気づけない)。
     * ページ送り以外の最下段(スキル選択バー・モード切替)は通常モードと同じ配置にして、
     * モードを跨いでも押す場所が変わらないようにしている。
     */
    private void openPerkList(Player player, List<SkillTree> trees, SkillTree tree,
                              NativeSkillTreeCanvas canvas, NativeSkillTreeCanvas.Point center,
                              int page) {
        List<NativeSkillTreeCanvas.NodeCell> allPerks = List.copyOf(canvas.nodes().values());
        int pages = SkillTreeOverviewLayout.pageCount(allPerks.size());
        int safePage = Math.floorMod(page, pages);
        List<NativeSkillTreeCanvas.NodeCell> visible = SkillTreeOverviewLayout.page(allPerks, safePage);

        Session holder = new Session(tree.skill(), canvas.clamp(center), null, Mode.PERK_LIST, safePage);
        Inventory inventory = plugin.getServer().createInventory(holder, 54, MENU_TITLE);
        holder.inventory = inventory;

        Set<String> owned = loadOwnedPerkIds(player.getUniqueId());
        Set<String> locked = Set.copyOf(com.trinityforge.pdc.PlayerData.of(player).lockedPerks());
        var snapshot = progression.snapshot(player.getUniqueId());
        var skill = snapshot.skillOrDefault(tree.skill(), 100);
        Map<String, SkillNode> nodesByPerk = new HashMap<>();
        for (SkillNode node : tree.nodes().values()) {
            nodesByPerk.put(PerkNaming.perkId(tree.skill(), node.id()), node);
        }

        Map<String, Integer> slots = SkillTreeOverviewLayout.assign(
                visible.stream().map(NativeSkillTreeCanvas.NodeCell::perkId).toList());
        for (NativeSkillTreeCanvas.NodeCell cell : visible) {
            Integer slot = slots.get(cell.perkId());
            if (slot == null) {
                continue; // page() が容量で切っているので発生しない。
            }
            inventory.setItem(slot, perkListIcon(tree, cell, nodesByPerk.get(cell.perkId()),
                    skill.level(), snapshot.availablePoints(), skill.prestige(), owned, locked));
        }

        if (safePage > 0) {
            inventory.setItem(SkillTreeOverviewLayout.PAGE_PREV_SLOT, button(
                    SkillTreeGuiVisuals.control("move-w"), "perk-page", Integer.toString(safePage - 1),
                    Component.text("前のページ", NamedTextColor.WHITE),
                    List.of(Component.text((safePage) + " / " + pages + " ページ", NamedTextColor.GRAY))));
        }
        if (safePage < pages - 1) {
            inventory.setItem(SkillTreeOverviewLayout.PAGE_NEXT_SLOT, button(
                    SkillTreeGuiVisuals.control("move-e"), "perk-page", Integer.toString(safePage + 1),
                    Component.text("次のページ", NamedTextColor.WHITE),
                    List.of(Component.text((safePage + 2) + " / " + pages + " ページ", NamedTextColor.GRAY))));
        }
        renderSkillSelector(inventory, trees, tree.skill(), snapshot);
        renderModeButtons(inventory, Mode.PERK_LIST, (safePage + 1) + " / " + pages + " ページ");
        remember(player, holder);
        player.openInventory(inventory);
    }

    /**
     * パーク一覧の1マス。解放状態はツリー本体と同じ配色・同じロックテクスチャで示す。
     *
     * <p><b>2026-08-06: 説明文(description)も載せる。</b>当初は「一覧は"どこにあるか探す"ための画面」
     * として省いていたが、それだと<b>一覧から目的のパークを選べない</b>(名前だけでは何をするパークか
     * 分からず、1つずつ通常モードへ飛んで戻る操作が要る)。整形は {@link #nodeIcon} と同じ
     * (改行分割・legacy の &amp; 色コード解釈・色未指定はグレー・イタリック無効)にそろえてある。
     * 同じ内容を2箇所で別々に整形すると、通常モードと一覧で見え方が食い違う。
     */
    private ItemStack perkListIcon(SkillTree tree, NativeSkillTreeCanvas.NodeCell cell, SkillNode node,
                                   int level, long availablePoints, int prestige,
                                   Set<String> owned, Set<String> lockedPerks) {
        NodeStatus status = status(
                tree, cell.perkId(), node, level, availablePoints, prestige, owned, null);
        Material icon = material(cell.perk().icon(), material(tree.icon(), Material.STONE));
        List<Component> lore = new ArrayList<>(descriptionLines(cell.perk().description()));
        // ツリー本体({@link #nodeIcon})と同じ赤字警告を出す。片方だけだと、一覧から飛ぶ経路で
        // 「レベルが0に戻る」を読まないままトーテムに辿り着ける。
        if (node == null && cell.perkId().contains("_perk_ng")) {
            if (!lore.isEmpty()) {
                lore.add(separatorLine());
            }
            lore.addAll(prestigeWarningLore(tree));
        }
        if (!lore.isEmpty()) {
            lore.add(separatorLine());
        }
        lore.add(Component.text("必要Lv: " + cell.perk().requiredLv() + " / 現在Lv: " + level,
                level >= cell.perk().requiredLv() ? NamedTextColor.GRAY : NamedTextColor.RED));
        lore.add(Component.text("コスト: " + cell.perk().cost() + " / 所持: " + availablePoints,
                NamedTextColor.GRAY));
        lore.add(Component.text(status.unlocked() ? "解放済み"
                        : status.unlockable() ? "解放可能" : "解放条件を満たしていません",
                status.unlocked() ? NamedTextColor.GREEN
                        : status.unlockable() ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY));
        if (lockedPerks.contains(cell.perkId())) {
            lore.add(Component.text("🔒 ロック中（プレステージしても維持）", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("クリックでこのパークへ移動", NamedTextColor.DARK_GRAY));
        return button(
                SkillTreeGuiVisuals.node(status.unlocked(), status.unlockable(), false, icon),
                "jump-perk", cell.perkId(),
                Component.text(cell.perk().name(),
                        status.unlocked() ? NamedTextColor.GREEN
                                : status.unlockable() ? NamedTextColor.AQUA : NamedTextColor.GRAY),
                lore);
    }

    /**
     * 一覧モード(2026-08-04新設): 各スキルツリーを表すアイコンだけを格子状に並べる。
     * クリックすると{@code select-skill}(既存アクション)へ合流し、そのツリーの位置へ移動して
     * 通常モードへ戻る({@link #onClick}の{@code select-skill}分岐が常に{@code overview=false}で
     * 再オープンするため、ここでは専用アクションを新設しない)。
     *
     * <p>座標そのものは意味を持たないが({@link SkillTreeOverviewLayout}が固定格子へ割り当てる)、
     * トグルで通常モードへ戻ったときに直前の位置(center)へ戻れるよう{@link Session}へは
     * そのまま引き継ぐ。
     */
    private void openOverview(Player player, List<SkillTree> trees, String currentSkillId,
                              NativeSkillTreeCanvas.Point center, String pendingPerkId) {
        Session holder = new Session(currentSkillId, center, pendingPerkId, Mode.OVERVIEW, 0);
        Inventory inventory = plugin.getServer().createInventory(holder, 54, MENU_TITLE);
        holder.inventory = inventory;

        List<String> skillIds = trees.stream().map(SkillTree::skill).toList();
        Map<String, Integer> slots = SkillTreeOverviewLayout.assign(skillIds);
        Set<String> owned = loadOwnedPerkIds(player.getUniqueId());
        var snapshot = progression.snapshot(player.getUniqueId());

        for (SkillTree tree : trees) {
            Integer slot = slots.get(tree.skill());
            if (slot == null) {
                continue; // 格子の容量(20)を超えた分。現状16ツリーなので発生しない。
            }
            var skill = snapshot.skillOrDefault(tree.skill(), 100);
            long unlockedCount = owned.stream()
                    .filter(id -> id.startsWith(PerkNaming.compact(tree.skill()) + "_perk_"))
                    .count();
            boolean isCurrent = tree.skill().equals(currentSkillId);
            // 一覧モードにも日次逓減の状態を出す(2026-08-25)。従来はここが
            // レベル/プレステージ/解放済みノード数しか出さず、EXP取得量が下がっている最中でも
            // 「全スキルを一目で見る」画面からは分からなかった(通常モードの選択バーだけにしか
            // 出ておらず、そこは常に7ツリーぶんしか見えない)。整形は dailyRateLore に一本化する。
            List<Component> lore = new ArrayList<>(List.of(
                    Component.text("レベル: " + skill.level(), NamedTextColor.GRAY),
                    Component.text("プレステージ: " + skill.prestige(), NamedTextColor.GRAY),
                    Component.text("解放済みノード: " + unlockedCount, NamedTextColor.GRAY)));
            lore.addAll(dailyRateLore(snapshot.playerId(), tree.skill()));
            lore.add(Component.text("クリックでこのツリーへ移動", NamedTextColor.DARK_GRAY));
            inventory.setItem(slot, button(
                    SkillTreeGuiVisuals.skill(
                            tree.skill(), material(tree.icon(), Material.NETHER_STAR)),
                    "select-skill", tree.skill(),
                    Component.text(tree.displayName(),
                            isCurrent ? NamedTextColor.GOLD : NamedTextColor.WHITE),
                    lore));
        }
        renderModeButtons(inventory, Mode.OVERVIEW, null);
        remember(player, holder);
        player.openInventory(inventory);
    }

    private ItemStack button(Material material, String action, String value,
                             Component name, List<Component> lore) {
        return button(new SkillTreeGuiVisuals.Visual(material, null), action, value, name, lore);
    }

    private ItemStack button(SkillTreeGuiVisuals.Visual visual, String action, String value,
                             Component name, List<Component> lore) {
        ItemStack item = display(visual, name, lore);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        meta.getPersistentDataContainer().set(valueKey, PersistentDataType.STRING, value);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack display(SkillTreeGuiVisuals.Visual visual,
                              Component name, List<Component> lore) {
        ItemStack item = new ItemStack(visual.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (visual.itemModel() != null) {
            meta.setItemModel(new NamespacedKey(plugin, visual.itemModel()));
        }
        item.setItemMeta(meta);
        return item;
    }

    private static String blockedReason(NativePerkService.UnlockResult result) {
        return switch (result) {
            case LEVEL_TOO_LOW -> "レベルが不足しています";
            case MISSING_PARENT -> "前提ノードが未解放です";
            case EXCLUSIVE_CONFLICT -> "排他ルートを選択済みです";
            case INSUFFICIENT_POINTS -> "スキルポイントが不足しています";
            case ALREADY_UNLOCKED -> "解放済み";
            default -> "現在は解放できません";
        };
    }

    private List<SkillTree> orderedTrees() {
        List<SkillTree> result = new ArrayList<>();
        for (String id : SKILL_ORDER) {
            SkillTree tree = perks.tree(id);
            if (tree != null) result.add(tree);
        }
        return result;
    }

    private static NativeSkillTreeCanvas.Direction direction(String action) {
        return switch (action) {
            case "move-nw" -> NativeSkillTreeCanvas.Direction.NORTH_WEST;
            case "move-n" -> NativeSkillTreeCanvas.Direction.NORTH;
            case "move-ne" -> NativeSkillTreeCanvas.Direction.NORTH_EAST;
            case "move-e" -> NativeSkillTreeCanvas.Direction.EAST;
            case "move-se" -> NativeSkillTreeCanvas.Direction.SOUTH_EAST;
            case "move-s" -> NativeSkillTreeCanvas.Direction.SOUTH;
            case "move-sw" -> NativeSkillTreeCanvas.Direction.SOUTH_WEST;
            case "move-w" -> NativeSkillTreeCanvas.Direction.WEST;
            default -> throw new IllegalArgumentException("unknown direction: " + action);
        };
    }

    private static String directionLabel(String action) {
        return switch (action) {
            case "move-nw" -> "左上へ移動";
            case "move-n" -> "上へ移動";
            case "move-ne" -> "右上へ移動";
            case "move-e" -> "右へ移動";
            case "move-se" -> "右下へ移動";
            case "move-s" -> "下へ移動";
            case "move-sw" -> "左下へ移動";
            case "move-w" -> "左へ移動";
            default -> "";
        };
    }

    private static int prestigeTier(String perkId) {
        int index = perkId.lastIndexOf("_ng");
        if (index < 0) return 0;
        try {
            return Integer.parseInt(perkId.substring(index + 3));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String roman(int value) {
        return switch (value) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(value);
        };
    }

    private static Material material(String raw, Material fallback) {
        if (raw == null) return fallback;
        Material parsed = Material.matchMaterial(raw);
        return parsed == null || parsed.isAir() ? fallback : parsed;
    }

    private Set<String> loadOwnedPerkIds(UUID playerId) {
        LoadResult<Set<String>> result = progression.repository().loadPerkIds(playerId);
        if (result.isFailed()) {
            plugin.getLogger().warning("[progression] Failed to load perks for skill menu: " + playerId
                    + " - " + result.error().getMessage());
            return Set.of();
        }
        return result.orElseThrow();
    }

    /**
     * GUI の表示モード。
     *
     * <p>2026-08-04 は「通常 / 一覧」の 2 値だったので {@code boolean overview} で持っていたが、
     * 2026-08-05(W-29)でパーク一覧が 3 つ目のモードになったので列挙へ移した
     * (boolean 2 本にすると「両方 true」という存在しない状態を型が許してしまう)。
     */
    private enum Mode {
        /** スキルツリーの中身を 9x5 ビューポートで描画する既定モード。 */
        DETAIL,
        /** 全スキルツリーをアイコンだけで並べる一覧モード(2026-08-04)。 */
        OVERVIEW,
        /** 現在のスキルツリーのパークだけを一覧の体裁で並べるモード(2026-08-05, W-29)。 */
        PERK_LIST,
        /**
         * プレステージ専用の確認モーダル(2026-08-23, W-188)。ツリーの中身もナビも描かず、
         * 警告と「はい」「いいえ」だけを置く。{@code lastView} へは覚えさせない
         * (覚えるとメニューを開き直しただけで確定ボタンのある画面が出る)。
         */
        PRESTIGE_CONFIRM
    }

    private static final class Session implements InventoryHolder {
        private final String skillId;
        private final NativeSkillTreeCanvas.Point center;
        private final String pendingPerkId;
        private final Mode mode;
        /** パーク一覧モードのページ(0始まり)。他モードでは常に 0。 */
        private final int page;
        private Inventory inventory;

        private Session(String skillId, NativeSkillTreeCanvas.Point center, String pendingPerkId,
                        Mode mode, int page) {
            this.skillId = skillId;
            this.center = center;
            this.pendingPerkId = pendingPerkId;
            this.mode = mode;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record NodeStatus(boolean unlocked, boolean unlockable, boolean pending) {
    }

    /** 「最後に見ていた画面」(2026-08-06, W-30)。{@link Session} から pending を落としたもの。 */
    private record View(String skillId, NativeSkillTreeCanvas.Point center, Mode mode, int page) {
    }
}
