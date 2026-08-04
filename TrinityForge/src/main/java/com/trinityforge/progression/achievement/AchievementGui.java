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
 * 54枠のうち 9x5=45 枠がキャンバス、外周8か所が視点移動、タイトルはリソースパックの
 * カスタムフォントで枠を消す。コネクタ形状もスキルツリーと同じ {@code gui/connection/*} を使う
 * ので、<b>新しいテクスチャは要らない</b>。
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
     * 移動ボタン配置(2026-07-30 左右対称化)。最下段9枠を、中央の達成状況アイコン(48→49)を軸に
     * 左右対称へ組み替えた。上(北)と下(南)がその本アイコンを挟む形になる。
     *
     * <pre>
     *   45   46   47   48   49   50   51   52   53
     *   ↖    ←    ↙    ↑   [本]   ↓    ↘    →    ↗
     * </pre>
     * 水平の鏡像対: 45↔53(↖↗) / 46↔52(←→) / 47↔51(↙↘)、48↔50 が上下ペア。
     * 以前は 48 に本、49 が↑、50 が↓ で、本が中央から1枠ずれて左右非対称になっていた。
     */
    private static final int SUMMARY_SLOT = 49;
    private static final Map<Integer, int[]> NAVIGATION = Map.of(
            45, new int[]{-1, -1},
            48, new int[]{0, -1},
            53, new int[]{1, -1},
            46, new int[]{-1, 0},
            52, new int[]{1, 0},
            47, new int[]{-1, 1},
            50, new int[]{0, 1},
            51, new int[]{1, 1});
    private static final Map<Integer, String> NAVIGATION_MODELS = Map.of(
            45, "move-nw",
            48, "move-n",
            53, "move-ne",
            46, "move-w",
            52, "move-e",
            47, "move-sw",
            50, "move-s",
            51, "move-se");
    private static final Material DEFAULT_ICON = Material.PAPER;

    private final Plugin plugin;
    private final AchievementsConfig config;
    private final CrossPluginItemResolver itemResolver;
    private final CollectionService collectionService;
    private final AchievementService achievementService;
    private final NamespacedKey navKeyX;
    private final NamespacedKey navKeyY;
    private final NamespacedKey focusKey;

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
    }

    public void open(Player player) {
        open(player, null);
    }

    /** @param focusId 中央に寄せたいアチーブメントID(null=既定の起点) */
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
        render(player, canvas, focusId == null ? canvas.start() : canvas.focusOn(focusId), null);
    }

    private void render(Player player, AchievementCanvas canvas, AchievementCanvas.Point center) {
        render(player, canvas, center, null);
    }

    /** @param pendingId 確認待ち(1クリック目)のアチーブメントID。無ければ {@code null}。 */
    private void render(Player player, AchievementCanvas canvas, AchievementCanvas.Point center, String pendingId) {
        AchievementCanvas.Point safe = canvas.clamp(center);
        Session holder = new Session(safe, pendingId);
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
        for (Map.Entry<Integer, int[]> nav : NAVIGATION.entrySet()) {
            inventory.setItem(nav.getKey(), navButton(nav.getKey(), nav.getValue()));
        }
        inventory.setItem(SUMMARY_SLOT, summaryIcon(achieved, claimed, canvas));
        player.openInventory(inventory);
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
        lore.add(plain("矢印で視点を動かせます。", NamedTextColor.GRAY));
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
                render(player, canvas, canvas.move(session.center, dx, dy), null);
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
            render(player, canvas, canvas.focusOn(achievementId), null);
            return;
        }
        if (!achievementId.equals(session.pendingId)) {
            // 1クリック目: 解放の確認待ちにする。
            render(player, canvas, canvas.focusOn(achievementId), achievementId);
            return;
        }
        // 2クリック目: 解放を確定する。
        if (achievementService == null) {
            player.sendMessage(Component.text("現在アチーブメントを解放できません。", NamedTextColor.RED));
            render(player, canvas, canvas.focusOn(achievementId), null);
            return;
        }
        AchievementService.ClaimResult result = achievementService.claim(player, achievementId);
        player.sendMessage(claimResultMessage(result));
        render(player, canvas, canvas.focusOn(achievementId), null);
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

    private static final class Session implements InventoryHolder {
        private final AchievementCanvas.Point center;
        /** 確認待ち(1クリック目)のアチーブメントID。無ければ {@code null}。 */
        private final String pendingId;
        private Inventory inventory;

        private Session(AchievementCanvas.Point center, String pendingId) {
            this.center = center;
            this.pendingId = pendingId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
