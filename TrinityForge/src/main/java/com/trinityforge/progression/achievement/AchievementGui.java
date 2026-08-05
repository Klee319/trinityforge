package com.trinityforge.progression.achievement;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.config.domains.AchievementsConfig.Achievement;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.AchievementService;
import com.trinityforge.progression.CollectionService;
import com.trinityforge.skilltree.runtime.SkillTreeGuiVisuals;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.text.MiniText;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@code /achievement} — アチーブメントの進捗をノード＋分岐で見るGUI (2026-07-29)。
 *
 * <p>スキルツリーGUI({@code NativeSkillTreeMenu})と同じ流儀にそろえてある:
 * 54枠のうち 9x5=45 枠がキャンバス、タイトルはリソースパックのカスタムフォントで枠を消す。
 * コネクタ形状もスキルツリーと同じ {@code gui/connection/*} を使うので、
 * <b>新しいテクスチャは要らない</b>。
 *
 * <p><b>2026-08-06(W-31): 操作系をスキルツリーと完全に同じ配置へ揃えた。</b>
 * ①8方向の視点移動をキャンバスの<b>四隅と辺の中央</b>(スロット 0/4/8/18/26/36/40/44)へ移し、
 * ②最下段(45-52)を<b>系統(ルート実績)の切替バー</b>にした(スキルツリーのスキル選択バーと同じ位置)。
 * バーに並ぶのは<b>ルート実績だけ</b>(2026-08-06 ユーザー確定)で、分岐先は並べない。
 * 達成状況の本は最下段右端(53)へ。
 * ③ツリーは<b>ルートから上へ伸びる</b>({@link AchievementCanvas.AchievementLayout})。
 * 同じ操作系のGUIで伸びる向きだけ逆だと、上下の矢印の意味が画面ごとに入れ替わってしまう。
 *
 * <p><b>2026-08-04: 手動解放方式に変更した</b>(ロードマップを見に行く習慣づけが目的)。条件成立
 * (達成)だけでは報酬は付かず、GUIで明示的に解放してはじめて報酬が入る。クリック操作も
 * スキルツリーの {@code NativeSkillTreeMenu#handleNode} と同じ「1クリック目で確認待ち(pending)、
 * 同一ノードへの2クリック目で確定」パターンに揃えてある(新しい流儀は作らない方針)。
 * ノードの状態は3段階: 前提未達成/条件未達成(ロック)・条件成立済みで解放待ち(クリックで解放)・
 * 解放済み。解放できない/まだ解放していない場合の理由はクリック時にチャットで伝える。
 */
public final class AchievementGui implements Listener {

    private static final Component MENU_TITLE = Component.text("", NamedTextColor.WHITE)
            .font(Key.key("trinityforge", "skill_gui"));
    /**
     * 移動ボタン配置(2026-08-06, W-31 でスキルツリーと同一の操作系へ移した)。
     * 8方向はビューポート(0-44)の<b>四隅と辺の中央</b>に置く
     * ({@code NativeSkillTreeMenu.NAVIGATION_SLOTS} と同じスロット番号):
     *
     * <pre>
     *    0 ‥‥ 4 ‥‥ 8      ↖  ↑  ↗
     *   18       26   →    ←     →
     *   36 ‥ 40 ‥ 44      ↙  ↓  ↘
     * </pre>
     *
     * <p>2026-07-30〜08-05 は最下段9枠(45-53)に8方向＋達成状況の本を並べていたが、
     * 最下段は<b>系統(ルート実績)の切替バー</b>へ明け渡した(スキルツリーのスキル選択バーと同じ位置・
     * 同じ操作)。達成状況の本は最下段右端(53)へ移動 — スキルツリーが同じ位置にモード切替を
     * 固定しているのに合わせ、「バーの右端は常に固定ボタン」で揃えた。
     */
    private static final int SUMMARY_SLOT = 53;
    /** 系統切替バーの左端スロット。ルートがバー幅に収まるときはここから左詰めで並べる。 */
    private static final int HEAD_BAR_FIRST = 45;
    /** 系統切替バーの右端スロット(53 は達成状況の本で固定なので 52 まで)。 */
    private static final int HEAD_BAR_LAST = 52;
    /** 系統切替バーの中央スロット。バー幅に収まらないときは選択中がここに来る(offset -4..3)。 */
    private static final int HEAD_BAR_CENTER = 49;
    private static final Map<Integer, int[]> NAVIGATION = Map.of(
            0, new int[]{-1, -1},
            4, new int[]{0, -1},
            8, new int[]{1, -1},
            18, new int[]{-1, 0},
            26, new int[]{1, 0},
            36, new int[]{-1, 1},
            40, new int[]{0, 1},
            44, new int[]{1, 1});
    private static final Map<Integer, String> NAVIGATION_MODELS = Map.of(
            0, "move-nw",
            4, "move-n",
            8, "move-ne",
            18, "move-w",
            26, "move-e",
            36, "move-sw",
            40, "move-s",
            44, "move-se");
    private static final Material DEFAULT_ICON = Material.PAPER;

    private final Plugin plugin;
    private final AchievementsConfig config;
    private final CrossPluginItemResolver itemResolver;
    private final CollectionService collectionService;
    private final AchievementService achievementService;
    private final NamespacedKey navKeyX;
    private final NamespacedKey navKeyY;
    private final NamespacedKey focusKey;
    /**
     * 系統切替バーのボタンに刻む起点ID(2026-08-06, W-31)。{@link #focusKey} と分けているのは、
     * バーのクリックが解放(claim)の確認フローへ流れ込まないようにするため
     * (同じキーにすると、達成済みの起点をバーで選んだだけで解放が確定してしまう)。
     */
    private final NamespacedKey headKey;
    /**
     * プレイヤーごとの「最後に見ていた画面」(2026-08-06, W-30)。閉じて開き直しても
     * スクロール位置と選択中の系統が戻る。
     *
     * <p><b>確認待ち(pendingId)は意図的に持ち越さない。</b>持ち越すと「閉じて開いて1クリック」で
     * 解放が確定する経路ができ、誤爆で報酬を消費してしまう。
     *
     * <p>ログアウトで捨てる(＝ログイン跨ぎでは復元しない)。PDCへ載せれば跨げるが、PDCキーは
     * バックアップ網羅性の対象で保守コストが増える一方、この状態は「その場の作業位置」でしかない。
     */
    private final java.util.Map<java.util.UUID, View> lastView = new java.util.HashMap<>();

    /** 後方互換コンストラクタ(既存呼び出し元用): 解放操作は無効(fail-soft、クリックしても解放できない)。 */
    public AchievementGui(Plugin plugin, AchievementsConfig config,
                          CrossPluginItemResolver itemResolver, CollectionService collectionService) {
        this(plugin, config, itemResolver, collectionService, null);
    }

    /**
     * @param achievementService 解放(claim)を実行する経路(2026-08-04 手動解放方式)。{@code null} なら
     *                            クリックしても解放できない(fail-soft)。
     */
    public AchievementGui(Plugin plugin, AchievementsConfig config,
                          CrossPluginItemResolver itemResolver, CollectionService collectionService,
                          AchievementService achievementService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.itemResolver = itemResolver;
        this.collectionService = collectionService;
        this.achievementService = achievementService;
        this.navKeyX = new NamespacedKey(plugin, "achievement_nav_x");
        this.navKeyY = new NamespacedKey(plugin, "achievement_nav_y");
        this.focusKey = new NamespacedKey(plugin, "achievement_focus");
        this.headKey = new NamespacedKey(plugin, "achievement_head");
    }

    public void open(Player player) {
        open(player, null);
    }

    /**
     * @param focusId 中央に寄せたいアチーブメントID(null=前回の位置、無ければ既定の起点)
     */
    public void open(Player player, String focusId) {
        List<Achievement> achievements = config.achievements();
        if (achievements.isEmpty()) {
            player.sendMessage(Component.text("アチーブメントが未設定です。", NamedTextColor.GRAY));
            return;
        }
        AchievementCanvas canvas;
        try {
            canvas = AchievementCanvas.project(achievements);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("アチーブメントGUIを描画できません: " + ex.getMessage());
            player.sendMessage(Component.text("アチーブメント設定が不正です。", NamedTextColor.RED));
            return;
        }
        // 2026-08-06(W-30): 閉じて開き直しても、スクロール位置と選択中の系統が戻る。
        View view = lastView.get(player.getUniqueId());
        AchievementCanvas.Point center = focusId != null
                ? canvas.focusOn(focusId)
                : (view == null ? canvas.start() : canvas.clamp(view.center()));
        render(player, canvas, center, null, view == null ? null : view.headId());
    }

    /**
     * @param pendingId      確認待ち(1クリック目)のアチーブメントID。無ければ {@code null}
     * @param selectedHeadId 最下段の系統切替バーで選択中の系統。{@code null} なら先頭の系統
     */
    private void render(Player player, AchievementCanvas canvas, AchievementCanvas.Point center,
                        String pendingId, String selectedHeadId) {
        AchievementCanvas.Point safe = canvas.clamp(center);
        List<String> heads = AchievementCanvas.branchHeadIds(config.achievements());
        String head = selectedHeadId != null && heads.contains(selectedHeadId)
                ? selectedHeadId
                : (heads.isEmpty() ? null : heads.getFirst());
        Session holder = new Session(safe, pendingId, head);
        Inventory inventory = plugin.getServer().createInventory(holder, 54, MENU_TITLE);
        holder.inventory = inventory;

        List<String> achieved = PlayerData.of(player).achievedIds();
        List<String> claimed = claimedIds(player);
        for (Map.Entry<Integer, AchievementCanvas.Cell> entry : canvas.viewport(safe).entrySet()) {
            AchievementCanvas.Cell cell = entry.getValue();
            if (cell instanceof AchievementCanvas.NodeCell node) {
                inventory.setItem(entry.getKey(), nodeIcon(player, node.achievement(), achieved, claimed, pendingId));
            } else if (cell instanceof AchievementCanvas.ConnectorCell connector) {
                inventory.setItem(entry.getKey(), connectorIcon(connector, achieved, claimed));
            }
        }
        // 8方向はビューポートの上に重ねる(スキルツリーと同じで、そのセルの中身は隠れる)。
        for (Map.Entry<Integer, int[]> nav : NAVIGATION.entrySet()) {
            inventory.setItem(nav.getKey(), navButton(nav.getKey(), nav.getValue()));
        }
        renderHeadBar(inventory, heads, head, achieved, claimed);
        inventory.setItem(SUMMARY_SLOT, summaryIcon(achieved, claimed, canvas));
        lastView.put(player.getUniqueId(), new View(safe, head));
        player.openInventory(inventory);
    }

    /** ログアウトで作業位置を捨てる(オフラインプレイヤーのぶんを溜め込まない)。 */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        lastView.remove(event.getPlayer().getUniqueId());
    }

    /**
     * 最下段の系統(ルート実績)切替バー(2026-08-06, W-31)。
     *
     * <p>ルートがバー幅(8枠)に収まるなら<b>左詰めで1件ずつ</b>置く。収まらないときだけ
     * スキルツリーの {@code renderSkillSelector} と同じく、選択中を中央
     * ({@link #HEAD_BAR_CENTER})に置いて前後を巡回表示する。
     * スキルツリー側が常に巡回なのは16ツリーあってバーに収まらないからで、
     * 少数のときに巡回すると<b>同じルートが8枠に並ぶだけ</b>になり切替として読めない。
     */
    private void renderHeadBar(Inventory inventory, List<String> heads, String selectedHeadId,
                               List<String> achieved, List<String> claimed) {
        if (heads.isEmpty()) {
            return;
        }
        int width = HEAD_BAR_LAST - HEAD_BAR_FIRST + 1; // 45-52 の8枠
        if (heads.size() <= width) {
            for (int i = 0; i < heads.size(); i++) {
                String headId = heads.get(i);
                inventory.setItem(HEAD_BAR_FIRST + i,
                        headIcon(headId, headId.equals(selectedHeadId), achieved, claimed));
            }
            return;
        }
        int selected = Math.max(0, heads.indexOf(selectedHeadId));
        for (int offset = -4; offset <= 3; offset++) {
            String headId = heads.get(Math.floorMod(selected + offset, heads.size()));
            inventory.setItem(HEAD_BAR_CENTER + offset,
                    headIcon(headId, offset == 0, achieved, claimed));
        }
    }

    /** 系統切替バーの1枠。そのルート実績のアイコン・達成状況・配下の進捗を出す。 */
    private ItemStack headIcon(String headId, boolean selected, List<String> achieved, List<String> claimed) {
        Achievement achievement = achievementById(headId);
        ItemStack stack = achievement == null
                ? new ItemStack(DEFAULT_ICON)
                : buildBase(achievement.icon(), resolveIconMaterial(achievement.icon()));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        String label = achievement == null ? headId : MiniText.plain(achievement.displayName());
        meta.displayName(plain(label, selected ? NamedTextColor.GOLD : NamedTextColor.WHITE));
        List<String> section = sectionIds(headId);
        long done = section.stream().filter(achieved::contains).count();
        long got = section.stream().filter(claimed::contains).count();
        meta.lore(List.of(
                plain("この系統: " + section.size() + "件", NamedTextColor.GRAY),
                plain("達成: " + done + " / 解放: " + got, NamedTextColor.GRAY),
                plain(selected ? "表示中の系統" : "クリックでこの系統へ移動",
                        selected ? NamedTextColor.DARK_GRAY : NamedTextColor.YELLOW)));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(headKey, PersistentDataType.STRING, headId);
        stack.setItemMeta(meta);
        return stack;
    }

    private Achievement achievementById(String id) {
        for (Achievement achievement : config.achievements()) {
            if (achievement.id().equals(id)) {
                return achievement;
            }
        }
        return null;
    }

    /**
     * {@code headId} を起点に {@code parent} 鎖でたどれる子孫(自分を含む)。
     * 別の系統の起点に当たったらそこで打ち切る(系統ごとの件数を出すため)。循環しても止まる。
     */
    private List<String> sectionIds(String headId) {
        List<String> heads = AchievementCanvas.branchHeadIds(config.achievements());
        List<String> section = new ArrayList<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(headId));
        java.util.Set<String> seen = new java.util.HashSet<>(List.of(headId));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            section.add(current);
            for (Achievement achievement : config.achievements()) {
                if (current.equals(achievement.parent())
                        && !heads.contains(achievement.id())
                        && seen.add(achievement.id())) {
                    queue.add(achievement.id());
                }
            }
        }
        return section;
    }

    /** 解放済みID一覧。{@link #achievementService} 未注入なら移行なしでPDCをそのまま読む(fail-soft)。 */
    private List<String> claimedIds(Player player) {
        if (achievementService != null) {
            return achievementService.claimedIds(player);
        }
        return PlayerData.of(player).claimedAchievementIds();
    }

    /** 達成/解放状況のサマリ(中央下)。 */
    private ItemStack summaryIcon(List<String> achieved, List<String> claimed, AchievementCanvas canvas) {
        int total = canvas.nodes().size();
        long done = canvas.nodes().keySet().stream().filter(achieved::contains).count();
        long unclaimed = canvas.nodes().keySet().stream()
                .filter(id -> achieved.contains(id) && !claimed.contains(id)).count();
        ItemStack stack = new ItemStack(Material.BOOK);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain("達成状況: " + done + " / " + total, NamedTextColor.GOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(plain("四隅・辺の矢印で視点を動かせます。", NamedTextColor.GRAY));
        lore.add(plain("最下段のアイコンで系統を切り替えます。", NamedTextColor.GRAY));
        lore.add(plain("ノードをクリックすると中央に寄せます。", NamedTextColor.DARK_GRAY));
        if (unclaimed > 0) {
            lore.add(plain("解放待ち: " + unclaimed + "件（対象ノードをクリック）", NamedTextColor.GOLD));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack navButton(int slot, int[] delta) {
        SkillTreeGuiVisuals.Visual visual = SkillTreeGuiVisuals.control(NAVIGATION_MODELS.get(slot));
        ItemStack stack = new ItemStack(visual.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain("視点移動", NamedTextColor.AQUA));
        applyItemModel(meta, visual.itemModel());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(navKeyX, PersistentDataType.INTEGER, delta[0]);
        meta.getPersistentDataContainer().set(navKeyY, PersistentDataType.INTEGER, delta[1]);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack connectorIcon(AchievementCanvas.ConnectorCell connector, List<String> achieved,
                                    List<String> claimed) {
        // 線の色は「その線が向かう先」の状態にそろえる(スキルツリーと同じ考え方)。
        // 2026-08-04: 満点(緑)は「解放済み」のみ。達成済みだが未解放/条件成立に近いものは
        // unlockable(橙)にとどめる。
        SkillTreeGuiVisuals.ConnectorState state = SkillTreeGuiVisuals.ConnectorState.LOCKED;
        for (String ownerId : connector.ownerIds()) {
            if (claimed.contains(ownerId)) {
                state = SkillTreeGuiVisuals.ConnectorState.UNLOCKED;
                break;
            }
            if (achieved.contains(ownerId) || available(ownerId, achieved)) {
                state = SkillTreeGuiVisuals.ConnectorState.UNLOCKABLE;
            }
        }
        SkillTreeGuiVisuals.Visual visual;
        try {
            visual = SkillTreeGuiVisuals.connector(state, connector.suffix());
        } catch (RuntimeException ex) {
            return new ItemStack(Material.GRAY_DYE);
        }
        ItemStack stack = new ItemStack(visual.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.empty());
        applyItemModel(meta, visual.itemModel());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        stack.setItemMeta(meta);
        return stack;
    }

    /** そのIDが「前提を満たしていて、まだ達成していない」状態か。 */
    private boolean available(String achievementId, List<String> achieved) {
        for (Achievement achievement : config.achievements()) {
            if (achievement.id().equals(achievementId)) {
                return !achieved.contains(achievementId)
                        && AchievementsConfig.prerequisitesMet(achievement, achieved);
            }
        }
        return false;
    }

    private ItemStack nodeIcon(Player player, Achievement achievement, List<String> achieved,
                               List<String> claimed, String pendingId) {
        boolean isClaimed = claimed.contains(achievement.id());
        boolean isAchieved = achieved.contains(achievement.id());
        boolean gateOpen = AchievementsConfig.prerequisitesMet(achievement, achieved);
        boolean pending = achievement.id().equals(pendingId);
        Material configured = resolveIconMaterial(achievement.icon());
        // unlocked=解放済み/unlockable=達成済みでまだ解放していない(クリックで解放できる)。
        SkillTreeGuiVisuals.Visual visual =
                SkillTreeGuiVisuals.node(isClaimed, isAchieved && !isClaimed, pending, configured);

        ItemStack stack = buildBase(achievement.icon(), visual.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        NamedTextColor nameColor = isClaimed ? NamedTextColor.GREEN
                : (isAchieved ? NamedTextColor.GOLD
                : (gateOpen ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY));
        // 表示名は MiniMessage 可 (アイテムカタログの display-name と同じ記法)。
        // 色を書いていない表示名だけ、状態の色(解放済み=緑/解放待ち=金/挑戦中=黄/前提未達=灰)を当てる。
        meta.displayName(MiniText.render(achievement.displayName(), nameColor));

        List<Component> lore = new ArrayList<>();
        String statusLine = isClaimed ? "✔ 解放済み"
                : (isAchieved ? "★ 受け取り可能（クリックで解放）"
                : (gateOpen ? "… 挑戦中" : "✖ 前提未達成"));
        lore.add(plain(statusLine, nameColor));
        if (pending) {
            lore.add(plain("　もう一度クリックすると解放を確定します", NamedTextColor.GOLD));
        }
        for (String line : achievement.lore()) {
            lore.add(mini(line));
        }
        lore.add(plain("条件: " + conditionText(achievement), NamedTextColor.GRAY));
        String progress = progressText(player, achievement);
        if (progress != null) {
            lore.add(plain("進捗: " + progress, isAchieved ? NamedTextColor.GREEN : NamedTextColor.AQUA));
        }
        List<String> prerequisites = AchievementCanvas.prerequisiteIds(achievement);
        if (!prerequisites.isEmpty()) {
            lore.add(plain("前提: " + prerequisites.stream()
                    .map(id -> displayNameOf(id) + (achieved.contains(id) ? "(済)" : "(未)"))
                    .reduce((a, b) -> a + " / " + b).orElse(""),
                    gateOpen ? NamedTextColor.DARK_GRAY : NamedTextColor.RED));
            if (prerequisites.size() > 1 || achievement.parent() == null) {
                lore.add(plain("　※ いずれか1つを達成していれば挑戦できます", NamedTextColor.DARK_GRAY));
            }
        }
        String rewardSummary = rewardSummary(achievement);
        if (!rewardSummary.isBlank()) {
            lore.add(plain("報酬: " + rewardSummary, NamedTextColor.LIGHT_PURPLE));
        }
        meta.lore(lore);
        applyItemModel(meta, visual.itemModel());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(focusKey, PersistentDataType.STRING, achievement.id());
        stack.setItemMeta(meta);
        return stack;
    }

    /** アイコン文字列がカタログID/{@code custom:} ならその実物を、Material 名ならそれを組む。 */
    private ItemStack buildBase(String icon, Material fallback) {
        if (icon != null && !icon.isBlank() && itemResolver != null
                && Material.matchMaterial(icon) == null) {
            var built = itemResolver.create(icon);
            if (built.isPresent()) {
                ItemStack stack = built.get();
                stack.setAmount(1);
                return stack;
            }
        }
        return new ItemStack(fallback);
    }

    private static Material resolveIconMaterial(String icon) {
        if (icon == null || icon.isBlank()) {
            return DEFAULT_ICON;
        }
        Material material = Material.matchMaterial(icon);
        return material == null || material.isAir() ? DEFAULT_ICON : material;
    }

    /** 「前提: A(済) / B(未)」の1行へ連結するので、MiniMessage タグは落として返す。 */
    private String displayNameOf(String achievementId) {
        for (Achievement achievement : config.achievements()) {
            if (achievement.id().equals(achievementId)) {
                return MiniText.plain(achievement.displayName());
            }
        }
        return achievementId;
    }

    /**
     * 累計カウンタIDの表示名。未知のIDはそのまま返す(config でカウンタを増やせるようにしてあるので、
     * ここに無いIDが来るのは異常ではない)。
     */
    static String counterLabel(String counterId) {
        return switch (counterId) {
            case "source_spent" -> "儀式で消費した累計ソース";
            default -> counterId;
        };
    }

    /** 1億のような大きな数を読めるように3桁区切りにする。 */
    static String formatCount(long value) {
        return String.format(java.util.Locale.ROOT, "%,d", value);
    }

    /** 達成条件の1行要約。 */
    static String conditionText(Achievement achievement) {
        return switch (achievement.trigger().type()) {
            case STATISTIC -> {
                // 修飾子付き統計(MINE_BLOCK: DIAMOND_ORE 等)は「何を」まで書かないと条件が読めない。
                String qualifier = achievement.trigger().statisticQualifier().label();
                yield statisticLabel(achievement.trigger().statistic())
                        + (qualifier.isEmpty() ? "" : "(" + qualifier + ")")
                        + " を " + achievement.trigger().threshold();
            }
            case ADVANCEMENT -> "バニラ進捗「" + achievement.trigger().advancement() + "」";
            // カウンタIDはそのまま出すと "source_spent" のような内部語になるので日本語へ寄せる。
            case COUNTER -> counterLabel(achievement.trigger().counter())
                    + " を " + formatCount(achievement.trigger().threshold());
            case STATIC -> {
                String scope = switch (achievement.trigger().collectionScope()) {
                    case "category" -> "図鑑カテゴリ";
                    case "item" -> "図鑑アイテム";
                    case "mob" -> "図鑑モブ";
                    default -> "図鑑全体";
                };
                yield scope + " を " + achievement.trigger().threshold()
                        + (achievement.trigger().collectionPercent() ? "%" : "件");
            }
        };
    }

    /** 現在の進捗。分からない/意味が無い場合は null。 */
    private String progressText(Player player, Achievement achievement) {
        switch (achievement.trigger().type()) {
            case STATISTIC -> {
                Statistic statistic = achievement.trigger().statistic();
                if (statistic == null) {
                    return null;
                }
                try {
                    return achievement.trigger().statisticQualifier().read(player, statistic)
                            + " / " + achievement.trigger().threshold();
                } catch (RuntimeException ex) {
                    return null;
                }
            }
            case COUNTER -> {
                String counter = achievement.trigger().counter();
                if (counter.isEmpty()) {
                    return null;
                }
                return formatCount(com.trinityforge.pdc.PlayerData.of(player).lifetimeCounter(counter))
                        + " / " + formatCount(achievement.trigger().threshold());
            }
            case STATIC -> {
                if (collectionService == null) {
                    return null;
                }
                int[] progress = collectionService.progress(player,
                        achievement.trigger().collectionScope(), achievement.trigger().collectionTargets());
                if (achievement.trigger().collectionPercent()) {
                    int percent = progress[1] <= 0 ? 0 : (int) (progress[0] * 100L / progress[1]);
                    return percent + "% / " + achievement.trigger().threshold() + "%";
                }
                return progress[0] + " / " + achievement.trigger().threshold();
            }
            default -> {
                return null;
            }
        }
    }

    /** 報酬の1行要約(件数だけ。詳細は editor 側の設定を見る前提)。 */
    static String rewardSummary(Achievement achievement) {
        List<String> parts = new ArrayList<>();
        var rewards = achievement.rewards();
        if (!rewards.items().isEmpty()) {
            parts.add("アイテム" + rewards.items().size() + "種");
        }
        if (rewards.vanillaExp() > 0) {
            parts.add("経験値" + rewards.vanillaExp());
        }
        if (!rewards.jobExp().isEmpty()) {
            parts.add("職業EXP" + rewards.jobExp().size() + "件");
        }
        if (!rewards.special().isEmpty()) {
            parts.add("特殊報酬" + rewards.special().size() + "件");
        }
        if (!rewards.permanentBuffs().isEmpty()) {
            parts.add("永続バフ" + rewards.permanentBuffs().size() + "件");
        }
        return String.join(" / ", parts);
    }

    private static String statisticLabel(Statistic statistic) {
        if (statistic == null) {
            return "統計";
        }
        return switch (statistic) {
            case JUMP -> "ジャンプ回数";
            case MOB_KILLS -> "モブ討伐数";
            case PLAYER_KILLS -> "プレイヤー討伐数";
            case DEATHS -> "死亡回数";
            case FISH_CAUGHT -> "釣り上げ数";
            case ANIMALS_BRED -> "繁殖回数";
            case PLAY_ONE_MINUTE -> "プレイ時間(tick)";
            case WALK_ONE_CM -> "歩行距離(cm)";
            case SPRINT_ONE_CM -> "走行距離(cm)";
            case SWIM_ONE_CM -> "泳いだ距離(cm)";
            case DAMAGE_DEALT -> "与えたダメージ";
            case DAMAGE_TAKEN -> "受けたダメージ";
            case ITEM_ENCHANTED -> "エンチャント回数";
            case TRADED_WITH_VILLAGER -> "村人取引回数";
            case SLEEP_IN_BED -> "就寝回数";
            case RAID_WIN -> "襲撃勝利回数";
            default -> statistic.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private static void applyItemModel(ItemMeta meta, String itemModel) {
        if (itemModel == null || itemModel.isBlank()) {
            return;
        }
        NamespacedKey key = NamespacedKey.fromString("trinityforge:" + itemModel);
        if (key != null) {
            meta.setItemModel(key);
        }
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    /** lore は MiniMessage 可。壊れた記法はそのまま素の文字として出す(GUI全体を落とさない)。 */
    private static Component mini(String raw) {
        try {
            return MiniMessage.miniMessage().deserialize(raw).decoration(TextDecoration.ITALIC, false);
        } catch (RuntimeException ex) {
            return plain(raw, NamedTextColor.GRAY);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Session session)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        var pdc = clicked.getItemMeta().getPersistentDataContainer();
        Integer dx = pdc.get(navKeyX, PersistentDataType.INTEGER);
        Integer dy = pdc.get(navKeyY, PersistentDataType.INTEGER);
        String focus = pdc.get(focusKey, PersistentDataType.STRING);
        String head = pdc.get(headKey, PersistentDataType.STRING);
        // openInventory を InventoryClickEvent の処理中に呼ぶのは Bukkit のアンチパターン
        // (ゴーストカーソル/クライアント desync)。必ず次tickへ逃がす。
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            AchievementCanvas canvas;
            try {
                canvas = AchievementCanvas.project(config.achievements());
            } catch (RuntimeException ex) {
                return;
            }
            if (dx != null && dy != null) {
                // 視点移動は保留中の解放操作を打ち切る(スキルツリーの move と同じ規則)。
                render(player, canvas, canvas.move(session.center, dx, dy), null, session.headId);
            } else if (head != null) {
                // 系統切替バー: その起点を中央に寄せ、バーの選択も動かす(解放操作は挟まない)。
                render(player, canvas, canvas.focusOn(head), null, head);
            } else if (focus != null) {
                handleNodeClick(player, session, canvas, focus);
            }
        });
    }

    /**
     * ノードクリックの解放フロー(2026-08-04)。{@code NativeSkillTreeMenu#handleNode} と同じ
     * 「1クリック目で確認待ち(pending)、同一ノードへの2クリック目で確定」パターン。
     * 解放待ちでないノード(ロック中/解放済み)をクリックした場合は、従来どおり中央へ寄せるだけ。
     */
    private void handleNodeClick(Player player, Session session, AchievementCanvas canvas, String achievementId) {
        List<String> claimed = claimedIds(player);
        List<String> achieved = PlayerData.of(player).achievedIds();
        boolean isClaimed = claimed.contains(achievementId);
        boolean isAchieved = achieved.contains(achievementId);
        if (isClaimed || !isAchieved) {
            render(player, canvas, canvas.focusOn(achievementId), null, session.headId);
            return;
        }
        if (!achievementId.equals(session.pendingId)) {
            // 1クリック目: 解放の確認待ちにする。
            render(player, canvas, canvas.focusOn(achievementId), achievementId, session.headId);
            return;
        }
        // 2クリック目: 解放を確定する。
        if (achievementService == null) {
            player.sendMessage(Component.text("現在アチーブメントを解放できません。", NamedTextColor.RED));
            render(player, canvas, canvas.focusOn(achievementId), null, session.headId);
            return;
        }
        AchievementService.ClaimResult result = achievementService.claim(player, achievementId);
        player.sendMessage(claimResultMessage(result));
        render(player, canvas, canvas.focusOn(achievementId), null, session.headId);
    }

    private static Component claimResultMessage(AchievementService.ClaimResult result) {
        return switch (result) {
            case CLAIMED -> Component.text("アチーブメントを解放しました。", NamedTextColor.GREEN);
            case ALREADY_CLAIMED -> Component.text("既に解放済みです。", NamedTextColor.GRAY);
            case NOT_ACHIEVED -> Component.text("まだ条件を満たしていません。", NamedTextColor.RED);
            case UNKNOWN_ACHIEVEMENT -> Component.text("アチーブメントが見つかりません。", NamedTextColor.RED);
        };
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }

    /** 「最後に見ていた画面」(2026-08-06, W-30)。{@link Session} から pending を落としたもの。 */
    private record View(AchievementCanvas.Point center, String headId) {
    }

    private static final class Session implements InventoryHolder {
        private final AchievementCanvas.Point center;
        /** 確認待ち(1クリック目)のアチーブメントID。無ければ {@code null}。 */
        private final String pendingId;
        /**
         * 最下段の系統切替バーで選択中の起点(2026-08-06, W-31)。
         * ノードを直接クリックして別系統へ飛んでもバーは動かさない(スキルツリーの
         * スキル選択バーと同じで、バーが回るのはバーを押したときだけ)。
         */
        private final String headId;
        private Inventory inventory;

        private Session(AchievementCanvas.Point center, String pendingId, String headId) {
            this.center = center;
            this.pendingId = pendingId;
            this.headId = headId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
