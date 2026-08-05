package com.trinityforge.skilltree.runtime;

import com.trinityforge.progression.NativeProgressionService;
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
            case "prestige" -> handlePrestige(player, session, value);
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

    private void handlePrestige(Player player, Session session, String perkId) {
        SkillTree tree = perks.tree(session.skillId);
        if (tree == null || tree.prestige() == null) return;
        var skill = progression.snapshot(player.getUniqueId())
                .skillOrDefault(tree.skill(), 100);
        int tier = prestigeTier(perkId);
        boolean unlockable = tier == skill.prestige() + 1
                && skill.level() >= tree.prestige().atLevel();
        if (!unlockable) {
            player.sendMessage(Component.text(
                    skill.prestige() >= tier ? "このプレステージは解放済みです。"
                            : "プレステージ条件を満たしていません。",
                    NamedTextColor.RED));
            reopenNextTick(player, session, null);
            return;
        }
        if (!perkId.equals(session.pendingPerkId)) {
            reopenNextTick(player, session, perkId);
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
                session.mode, session.page);
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
        List<Component> lore = new ArrayList<>();
        if (!cell.perk().description().isBlank()) {
            // フレーバー説明文は \n で複数行に分割し、editor で付けた legacy & 色コードを解釈して表示する。
            // 色未指定の行は既定のグレーにフォールバックし、lore 既定のイタリックは無効化する。
            for (String descLine : cell.perk().description().split("\n", -1)) {
                lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(descLine)
                        .colorIfAbsent(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }
        lore.add(Component.text("────────────────", NamedTextColor.DARK_GRAY));
        lore.add(Component.text("必要Lv: " + cell.perk().requiredLv() + " / 現在Lv: " + level,
                level >= cell.perk().requiredLv() ? NamedTextColor.GRAY : NamedTextColor.RED));
        lore.add(Component.text("コスト: " + cell.perk().cost() + " / 所持: " + availablePoints,
                NamedTextColor.GRAY));
        lore.add(Component.text(status.unlocked ? "解放済み"
                        : pending ? "もう一度クリックして確定"
                        : status.unlockable ? "クリックして確認" : "解放条件を満たしていません",
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
            inventory.setItem(49 + offset, button(
                    SkillTreeGuiVisuals.skill(
                            tree.skill(), material(tree.icon(), Material.NETHER_STAR)),
                    "select-skill", tree.skill(),
                    Component.text(tree.displayName() + suffix,
                            offset == 0 ? NamedTextColor.GOLD : NamedTextColor.WHITE),
                    List.of(
                            Component.text("レベル: " + skill.level(), NamedTextColor.GRAY),
                            Component.text("EXP: " + Math.round(skill.residualExp()), NamedTextColor.GRAY),
                            Component.text("スキルポイント: " + snapshot.availablePoints(),
                                    NamedTextColor.GRAY))));
        }
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
     * パーク一覧の1マス。全ツリー一覧の体裁(アイコン+短い集計行+「クリックで移動」)に合わせ、
     * 解放状態はツリー本体と同じ配色・同じロックテクスチャで示す。フレーバー説明文は
     * 通常モードの方が読みやすいので載せない(一覧は「どこにあるか探す」ための画面)。
     */
    private ItemStack perkListIcon(SkillTree tree, NativeSkillTreeCanvas.NodeCell cell, SkillNode node,
                                   int level, long availablePoints, int prestige,
                                   Set<String> owned, Set<String> lockedPerks) {
        NodeStatus status = status(
                tree, cell.perkId(), node, level, availablePoints, prestige, owned, null);
        Material icon = material(cell.perk().icon(), material(tree.icon(), Material.STONE));
        List<Component> lore = new ArrayList<>();
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
            inventory.setItem(slot, button(
                    SkillTreeGuiVisuals.skill(
                            tree.skill(), material(tree.icon(), Material.NETHER_STAR)),
                    "select-skill", tree.skill(),
                    Component.text(tree.displayName(),
                            isCurrent ? NamedTextColor.GOLD : NamedTextColor.WHITE),
                    List.of(
                            Component.text("レベル: " + skill.level(), NamedTextColor.GRAY),
                            Component.text("プレステージ: " + skill.prestige(), NamedTextColor.GRAY),
                            Component.text("解放済みノード: " + unlockedCount, NamedTextColor.GRAY),
                            Component.text("クリックでこのツリーへ移動", NamedTextColor.DARK_GRAY))));
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
        PERK_LIST
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
