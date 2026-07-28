package com.trinityforge.progression.achievement;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.config.domains.AchievementsConfig.Achievement;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.CollectionService;
import com.trinityforge.skilltree.runtime.SkillTreeGuiVisuals;
import com.trinityforge.stats.CrossPluginItemResolver;
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
 * <p>スキルツリーと違って<b>クリックで取得する操作は無い</b>(アチーブメントは条件達成で自動)。
 * ノードのクリックは詳細lore付きの拡大表示ではなく、そのノードを中央へ寄せるだけにしてある。
 */
public final class AchievementGui implements Listener {

    private static final Component MENU_TITLE = Component.text("", NamedTextColor.WHITE)
            .font(Key.key("trinityforge", "skill_gui"));
    /** スキルツリーGUIと同じ移動ボタン配置。 */
    private static final Map<Integer, int[]> NAVIGATION = Map.of(
            45, new int[]{-1, -1},
            49, new int[]{0, -1},
            53, new int[]{1, -1},
            46, new int[]{-1, 0},
            52, new int[]{1, 0},
            47, new int[]{-1, 1},
            50, new int[]{0, 1},
            51, new int[]{1, 1});
    private static final Map<Integer, String> NAVIGATION_MODELS = Map.of(
            45, "move-nw",
            49, "move-n",
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
    private final NamespacedKey navKeyX;
    private final NamespacedKey navKeyY;
    private final NamespacedKey focusKey;

    public AchievementGui(Plugin plugin, AchievementsConfig config,
                          CrossPluginItemResolver itemResolver, CollectionService collectionService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.itemResolver = itemResolver;
        this.collectionService = collectionService;
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
        render(player, canvas, focusId == null ? canvas.start() : canvas.focusOn(focusId));
    }

    private void render(Player player, AchievementCanvas canvas, AchievementCanvas.Point center) {
        AchievementCanvas.Point safe = canvas.clamp(center);
        Session holder = new Session(safe);
        Inventory inventory = plugin.getServer().createInventory(holder, 54, MENU_TITLE);
        holder.inventory = inventory;

        List<String> achieved = PlayerData.of(player).achievedIds();
        for (Map.Entry<Integer, AchievementCanvas.Cell> entry : canvas.viewport(safe).entrySet()) {
            AchievementCanvas.Cell cell = entry.getValue();
            if (cell instanceof AchievementCanvas.NodeCell node) {
                inventory.setItem(entry.getKey(), nodeIcon(player, node.achievement(), achieved));
            } else if (cell instanceof AchievementCanvas.ConnectorCell connector) {
                inventory.setItem(entry.getKey(), connectorIcon(connector, achieved));
            }
        }
        for (Map.Entry<Integer, int[]> nav : NAVIGATION.entrySet()) {
            inventory.setItem(nav.getKey(), navButton(nav.getKey(), nav.getValue()));
        }
        inventory.setItem(48, summaryIcon(achieved, canvas));
        player.openInventory(inventory);
    }

    /** 達成数のサマリ(中央下)。 */
    private ItemStack summaryIcon(List<String> achieved, AchievementCanvas canvas) {
        int total = canvas.nodes().size();
        long done = canvas.nodes().keySet().stream().filter(achieved::contains).count();
        ItemStack stack = new ItemStack(Material.BOOK);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain("達成状況: " + done + " / " + total, NamedTextColor.GOLD));
        meta.lore(List.of(
                plain("矢印で視点を動かせます。", NamedTextColor.GRAY),
                plain("ノードをクリックすると中央に寄せます。", NamedTextColor.DARK_GRAY)));
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

    private ItemStack connectorIcon(AchievementCanvas.ConnectorCell connector, List<String> achieved) {
        // 線の色は「その線が向かう先」の状態にそろえる(スキルツリーと同じ考え方)。
        SkillTreeGuiVisuals.ConnectorState state = SkillTreeGuiVisuals.ConnectorState.LOCKED;
        for (String ownerId : connector.ownerIds()) {
            if (achieved.contains(ownerId)) {
                state = SkillTreeGuiVisuals.ConnectorState.UNLOCKED;
                break;
            }
            if (available(ownerId, achieved)) {
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

    private ItemStack nodeIcon(Player player, Achievement achievement, List<String> achieved) {
        boolean done = achieved.contains(achievement.id());
        boolean gateOpen = AchievementsConfig.prerequisitesMet(achievement, achieved);
        Material configured = resolveIconMaterial(achievement.icon());
        SkillTreeGuiVisuals.Visual visual = SkillTreeGuiVisuals.node(done, gateOpen, false, configured);

        ItemStack stack = buildBase(achievement.icon(), visual.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        NamedTextColor nameColor = done ? NamedTextColor.GREEN
                : (gateOpen ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY);
        meta.displayName(plain(achievement.displayName(), nameColor));

        List<Component> lore = new ArrayList<>();
        lore.add(plain(done ? "✔ 達成済み" : (gateOpen ? "… 挑戦中" : "✖ 前提未達成"), nameColor));
        for (String line : achievement.lore()) {
            lore.add(mini(line));
        }
        lore.add(plain("条件: " + conditionText(achievement), NamedTextColor.GRAY));
        String progress = progressText(player, achievement);
        if (progress != null) {
            lore.add(plain("進捗: " + progress, done ? NamedTextColor.GREEN : NamedTextColor.AQUA));
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

    private String displayNameOf(String achievementId) {
        for (Achievement achievement : config.achievements()) {
            if (achievement.id().equals(achievementId)) {
                return achievement.displayName();
            }
        }
        return achievementId;
    }

    /** 達成条件の1行要約。 */
    static String conditionText(Achievement achievement) {
        return switch (achievement.trigger().type()) {
            case STATISTIC -> statisticLabel(achievement.trigger().statistic())
                    + " を " + achievement.trigger().threshold();
            case ADVANCEMENT -> "バニラ進捗「" + achievement.trigger().advancement() + "」";
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
                    return player.getStatistic(statistic) + " / " + achievement.trigger().threshold();
                } catch (RuntimeException ex) {
                    return null;
                }
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
                render(player, canvas, canvas.move(session.center, dx, dy));
            } else if (focus != null) {
                render(player, canvas, canvas.focusOn(focus));
            }
        });
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }

    private static final class Session implements InventoryHolder {
        private final AchievementCanvas.Point center;
        private Inventory inventory;

        private Session(AchievementCanvas.Point center) {
            this.center = center;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
