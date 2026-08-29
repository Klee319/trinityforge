package com.trinityforge.stats.status;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.command.StatsCategory;
import com.trinityforge.config.domains.LoreConfig;
import com.trinityforge.integration.ars.ArsArmorStatRefreshBridge;
import com.trinityforge.config.domains.RoleBuffsConfig.CombatRoleSpec;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.RoleChangeService;
import com.trinityforge.progression.RoleSelectGui;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.text.MiniText;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.runtime.NativePerkService;
import com.trinityforge.stats.StatDisplaySpec;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatValueRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@code /tf status} — 自分のステータスをインベントリGUIで確認する (2026-07-29)。
 *
 * <p>{@code /tf stats} のチャット出力と<b>同じ数値</b>が出る: 合算は
 * {@code PlayerCombatAggregate#combined()}、整形は {@link StatValueRenderer} と、どちらも
 * 1本の経路に集約してある。GUIで数字を作り直すとチャットとGUIで食い違うため。
 *
 * <p>1画面目に概要とカテゴリ、カテゴリをクリックすると2画面目でそのカテゴリの全ステを
 * 1ステ1アイテムで並べる(lore の行数制限に引っかからないように分けている)。
 *
 * <p><b>2026-08-05 (W-28): 職業(ロール)の確認・変更の入口をここへ統合した。</b>
 * {@code /tf menu}(統合メニュー)と {@code /tf role set} は廃止 — 同じことをする入口が
 * 3つあって、どれが正なのか分からない状態をたたむのが目的。可否判定は
 * {@link RoleChangeService} をそのまま読むので、この画面が独自のルールを持つことはない。
 */
public final class StatusGui implements Listener {

    private static final int SIZE = 54;
    private static final int HEAD_SLOT = 4;
    private static final int SKILLS_SLOT = 40;
    /** 職業(ロール)の確認・変更。{@code /tf menu} と {@code /tf role set} を畳んだ先(2026-08-05, W-28)。 */
    private static final int ROLE_SLOT = 38;
    private static final int CLOSE_SLOT = 49;
    private static final int BACK_SLOT = 49;
    /** カテゴリを並べる中段の左端スロット(19..25 の7枠)。 */
    private static final int CATEGORY_FIRST_SLOT = 19;
    /** 1カテゴリのloreに載せるステの最大行数。超えたぶんは詳細画面で見る。 */
    private static final int PREVIEW_ROWS = 8;
    /** スキルツリーGUIと同じ並び順(向こうと揃えないと同じスキルが別位置に見える)。 */
    private static final List<String> SKILL_ORDER = List.of(
            "POWER", "SMITHING", "ENCHANTING", "ALCHEMY",
            "MINING", "WOODCUTTING", "DIGGING", "FARMING",
            "LIGHT_WEAPONS", "HEAVY_WEAPONS", "FISHING", "ARCHERY",
            "LIGHT_ARMOR", "HEAVY_ARMOR", "ARS_MAGIC", "ARS_SMITHING");

    private final Plugin plugin;
    private final SymmetricCombatService combatService;
    private final PlayerStatAggregator aggregator;
    private final LoreConfig loreConfig;
    private final SkillLevelSource skillLevelSource;
    private final NativePerkService perkService;
    private final RoleChangeService roleChangeService;
    private final RoleSelectGui roleSelectGui;
    /** 日次逓減の倍率を引くためだけの参照（2026-08-18）。{@code null} 可 = 倍率を出さない。 */
    private final com.trinityforge.progression.NativeProgressionService progression;
    private final NamespacedKey categoryKey;

    public StatusGui(Plugin plugin, SymmetricCombatService combatService, PlayerStatAggregator aggregator,
                     LoreConfig loreConfig, SkillLevelSource skillLevelSource, NativePerkService perkService,
                     RoleChangeService roleChangeService, RoleSelectGui roleSelectGui) {
        this(plugin, combatService, aggregator, loreConfig, skillLevelSource, perkService,
                roleChangeService, roleSelectGui, null);
    }

    /**
     * 日次逓減の倍率つき（2026-08-18）。{@code progression} が {@code null} なら倍率を出さない
     * （逓減が無効なサーバ・テストでは従来どおりの表示になる）。
     */
    public StatusGui(Plugin plugin, SymmetricCombatService combatService, PlayerStatAggregator aggregator,
                     LoreConfig loreConfig, SkillLevelSource skillLevelSource, NativePerkService perkService,
                     RoleChangeService roleChangeService, RoleSelectGui roleSelectGui,
                     com.trinityforge.progression.NativeProgressionService progression) {
        this.progression = progression;
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combatService = Objects.requireNonNull(combatService, "combatService");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.loreConfig = Objects.requireNonNull(loreConfig, "loreConfig");
        this.skillLevelSource = Objects.requireNonNull(skillLevelSource, "skillLevelSource");
        this.perkService = perkService;
        this.roleChangeService = Objects.requireNonNull(roleChangeService, "roleChangeService");
        this.roleSelectGui = Objects.requireNonNull(roleSelectGui, "roleSelectGui");
        this.categoryKey = new NamespacedKey(plugin, "status_category");
    }

    public void open(Player player) {
        openOverview(player);
    }

    // ---- 1画面目: 概要 --------------------------------------------------------

    private void openOverview(Player player) {
        List<StatusGuiModel.Section> sections = sectionsOf(player);
        Session holder = new Session(null);
        Inventory inventory = plugin.getServer().createInventory(holder, SIZE,
                Component.text("ステータス", NamedTextColor.DARK_AQUA));
        holder.inventory = inventory;

        inventory.setItem(HEAD_SLOT, summaryHead(player));
        int slot = CATEGORY_FIRST_SLOT;
        for (StatusGuiModel.Section section : sections) {
            inventory.setItem(slot, categoryIcon(section));
            slot++;
        }
        inventory.setItem(ROLE_SLOT, roleIcon(player));
        inventory.setItem(SKILLS_SLOT, skillsIcon(player));
        inventory.setItem(CLOSE_SLOT, simple(Material.BARRIER, "閉じる", List.of()));
        player.openInventory(inventory);
    }

    /**
     * 職業(ロール)の確認と、選択GUIへの入口(2026-08-05, W-28)。
     *
     * <p>変更できるかどうかは {@link RoleChangeService} の枠ごとの拒否理由をそのまま出す。
     * ここで「待ち時間」「券が必要」を独自に組み立てると、実際に押したときの判定と食い違う。
     */
    private ItemStack roleIcon(Player player) {
        PlayerData data = PlayerData.of(player);
        CombatRoleSpec combat = data.rolePrimary()
                .map(id -> roleChangeService.config().combatRole(id)).orElse(null);
        SupportRoleSpec support = data.roleSupport()
                .map(id -> roleChangeService.config().supportRole(id)).orElse(null);

        ItemStack stack = new ItemStack(Material.LEATHER_CHESTPLATE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain("職業(ロール)", NamedTextColor.GOLD));
        List<Component> lore = new ArrayList<>();
        // label は MiniMessage 可なので、1行へ連結する前にタグを落とす。
        lore.add(entry("戦闘職", combat == null ? "(未選択)" : MiniText.plain(combat.label())));
        lore.add(entry("補助職", support == null ? "(未選択)" : MiniText.plain(support.label())));
        lore.add(Component.empty());
        lore.add(roleAvailability("戦闘職", roleChangeService.denyReasonForCombat(player).orElse(null)));
        lore.add(roleAvailability("補助職", roleChangeService.denyReasonForSupport(player).orElse(null)));
        lore.add(Component.empty());
        lore.add(plain("クリックで職業の確認・変更", NamedTextColor.YELLOW));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        stack.setItemMeta(meta);
        return stack;
    }

    private static Component roleAvailability(String slotLabel, String denyReason) {
        return denyReason == null
                ? plain(slotLabel + ": 変更できます", NamedTextColor.GREEN)
                : plain(denyReason, NamedTextColor.RED);
    }

    private ItemStack summaryHead(Player player) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(player);
        }
        meta.displayName(plain(player.getName(), NamedTextColor.GOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(entry("戦闘レベル", Integer.toString(combatService.combatLevelOf(player.getUniqueId()))));
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        lore.add(entry("体力", StatValueRenderer.plain(player.getHealth()) + " / "
                + StatValueRenderer.plain(maxHealth != null ? maxHealth.getValue() : player.getHealth())));
        // 2026-08-04: 「バニラ防御 (armor/toughness)」の行は削除した(stats チャット側と同じ扱い)。
        // 実際のダメージ計算を担うのは TF の守備力(物理/魔法)なので、バニラ属性値を並べると
        // どちらが効いているのか誤解を招く。
        lore.add(Component.empty());
        lore.add(plain("装備・パーク・アドオンを合算した実効値です。", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack categoryIcon(StatusGuiModel.Section section) {
        ItemStack stack = new ItemStack(iconOf(section.category()));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain(section.label() + " (" + section.rows().size() + ")", NamedTextColor.AQUA));
        List<Component> lore = new ArrayList<>();
        if (section.isEmpty()) {
            lore.add(plain("このカテゴリのステータスはありません。", NamedTextColor.DARK_GRAY));
        } else {
            for (StatusGuiModel.Row row : section.rows().subList(0, Math.min(PREVIEW_ROWS, section.rows().size()))) {
                lore.add(entry(row.label(), row.value()));
            }
            if (section.rows().size() > PREVIEW_ROWS) {
                lore.add(plain("… 他 " + (section.rows().size() - PREVIEW_ROWS) + " 件", NamedTextColor.DARK_GRAY));
            }
            lore.add(Component.empty());
            lore.add(plain("クリックで全件と発動条件を表示", NamedTextColor.YELLOW));
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(categoryKey, PersistentDataType.STRING, section.category().id());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack skillsIcon(Player player) {
        Map<String, Integer> levels = skillLevelSource.levelsOf(player.getUniqueId());
        ItemStack stack = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain("スキルレベル", NamedTextColor.LIGHT_PURPLE));
        List<Component> lore = new ArrayList<>();
        if (levels.isEmpty()) {
            lore.add(plain("まだスキルを習得していません。", NamedTextColor.DARK_GRAY));
        } else {
            for (String skillId : SKILL_ORDER) {
                Integer level = levels.get(skillId);
                if (level == null) {
                    continue;
                }
                lore.add(entry(skillLabel(skillId), levelText(player, skillId, level)));
            }
            // 並び順表に無いスキル(config追加分)も落とさない。
            for (Map.Entry<String, Integer> e : levels.entrySet()) {
                if (!SKILL_ORDER.contains(e.getKey())) {
                    lore.add(entry(skillLabel(e.getKey()),
                            levelText(player, e.getKey(), e.getValue())));
                }
            }
        }
        if (anyDiminished(player, levels.keySet())) {
            lore.add(Component.empty());
            lore.add(plain("×○% = 稼ぎすぎでEXP取得量が下がっています", NamedTextColor.RED));
            lore.add(plain("そのスキルを休むと戻ります(/skills で残り時間)", NamedTextColor.DARK_GRAY));
        }
        lore.add(Component.empty());
        lore.add(plain("/skills でスキルツリーを開けます", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * 「12」または「12  ×70%」。後者は日次逓減(直近24時間の稼ぎでEXP取得量が薄まる仕組み)が
     * 効いている状態で、2026-08-18 まで<b>プレイヤーがどこからも確認できなかった</b>。
     * 残り時間まではここに置かず {@code /skills} 側へ寄せる(この lore は16スキル分並ぶので行を増やせない)。
     */
    private String levelText(Player player, String skillId, int level) {
        String badge = rateBadge(player, skillId);
        return badge == null ? Integer.toString(level) : level + "  " + badge;
    }

    /** 1つでも逓減が効いていれば凡例を出す(効いていないときに出すと意味が分からない)。 */
    private boolean anyDiminished(Player player, java.util.Collection<String> skillIds) {
        for (String skillId : skillIds) {
            if (rateBadge(player, skillId) != null) {
                return true;
            }
        }
        return false;
    }

    /** 逓減が効いているときだけ {@code "×70%"}。未配線・無効・等倍なら {@code null}。 */
    private String rateBadge(Player player, String skillId) {
        if (progression == null) {
            return null;
        }
        return com.trinityforge.progression.DailyExpRateText.badge(
                progression.dailyExpRateStatus(player.getUniqueId(), skillId));
    }

    /** スキルの日本語名はスキルツリー定義の display-name が唯一の出所。無ければIDのまま。 */
    private String skillLabel(String skillId) {
        if (perkService == null) {
            return skillId;
        }
        SkillTree tree = perkService.tree(skillId);
        return tree != null && tree.displayName() != null && !tree.displayName().isBlank()
                ? tree.displayName() : skillId;
    }

    // ---- 2画面目: カテゴリ詳細 -------------------------------------------------

    private void openCategory(Player player, StatsCategory category) {
        StatusGuiModel.Section section = sectionsOf(player).stream()
                .filter(s -> s.category() == category)
                .findFirst().orElse(null);
        if (section == null) {
            openOverview(player);
            return;
        }
        Session holder = new Session(category);
        Inventory inventory = plugin.getServer().createInventory(holder, SIZE,
                Component.text("ステータス: " + section.label(), NamedTextColor.DARK_AQUA));
        holder.inventory = inventory;

        Map<String, StatDisplaySpec> table = loreConfig.displayTable();
        int slot = 0;
        for (StatusGuiModel.Row row : section.rows()) {
            if (slot >= 45) {
                break; // 46枠目以降は下段のボタン領域。溢れるほどのステは現状の語彙には無い。
            }
            inventory.setItem(slot, statIcon(row, table));
            slot++;
        }
        if (section.isEmpty()) {
            inventory.setItem(22, simple(Material.GRAY_DYE, "このカテゴリのステータスはありません",
                    List.of(plain("装備やパークで値が付くとここに並びます。", NamedTextColor.DARK_GRAY))));
        }
        inventory.setItem(BACK_SLOT, simple(Material.ARROW, "戻る", List.of()));
        player.openInventory(inventory);
    }

    private ItemStack statIcon(StatusGuiModel.Row row, Map<String, StatDisplaySpec> table) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(row.label() + ": ", NamedTextColor.GRAY)
                .append(Component.text(row.value(), NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(plain(row.key(), NamedTextColor.DARK_GRAY));
        StatDisplaySpec spec = lookup(table, row.key());
        // 発動条件/上限は stats/lore.yml に「宣言されている分だけ」出す。未宣言をそれらしく
        // 埋めると仕組みの目的(実装と宣言の一致を読める化)に反するので、無いものは無いと書く。
        if (spec != null && spec.trigger() != null) {
            lore.add(Component.empty());
            lore.add(plain("[発動条件]", NamedTextColor.AQUA));
            lore.add(entry("  発動タイミング", spec.trigger().when().label()));
            lore.add(entry("  合算対象", spec.trigger().sources().label()));
            lore.add(entry("  対象", spec.trigger().appliesTo().stream()
                    .map(a -> a.label()).reduce((a, b) -> a + " / " + b).orElse("-")));
        }
        if (spec != null && spec.limits() != null && !spec.limits().declaredBounds().isEmpty()) {
            lore.add(Component.empty());
            lore.add(plain("[上限]", NamedTextColor.AQUA));
            spec.limits().declaredBounds().forEach((field, bound) ->
                    lore.add(entry("  " + field, StatValueRenderer.plain(bound.value()))));
        }
        lore.add(Component.empty());
        lore.add(plain("/tf stats detail " + row.key() + " で詳細", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        stack.setItemMeta(meta);
        return stack;
    }

    // ---- 共通 -----------------------------------------------------------------

    private List<StatusGuiModel.Section> sectionsOf(Player player) {
        // プル型フォールバック(2026-08-25): ArsPaper の ArmorManaListener はプッシュ型(装備変更等
        // 6経路)でスレッド/マナ系ステをPDCへ書く。武器を「選択スロット番号が変わらない差し替え」
        // (コマンド等)で入れ替えるとどの経路も踏まず、表示直前まで古い値が残る。表示のたびに
        // 同期的に再計算を促す(ArsPaper未導入/失敗はfail-softで無害、既存のプッシュ型経路は不変)。
        ArsArmorStatRefreshBridge.refresh(player);
        return StatusGuiModel.sections(
                aggregator.aggregate(player).combined(), loreConfig.displayTable());
    }

    private static StatDisplaySpec lookup(Map<String, StatDisplaySpec> table, String canonicalKey) {
        StatDisplaySpec direct = table.get(canonicalKey);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, StatDisplaySpec> e : table.entrySet()) {
            if (StatKeys.canonical(e.getKey()).equals(canonicalKey)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static Material iconOf(StatsCategory category) {
        return switch (category) {
            case ATTACK -> Material.IRON_SWORD;
            case ARMOR -> Material.IRON_CHESTPLATE;
            case CRAFT -> Material.CRAFTING_TABLE;
            case GATHERING -> Material.IRON_PICKAXE;
            case UTILITY -> Material.CLOCK;
            case ARS -> Material.AMETHYST_SHARD;
            case OTHER, ALL -> Material.PAPER;
        };
    }

    private static ItemStack simple(Material material, String name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain(name, NamedTextColor.WHITE));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        stack.setItemMeta(meta);
        return stack;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component entry(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Session session)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        ItemStack clicked = event.getCurrentItem();
        String rawCategory = clicked != null && clicked.hasItemMeta()
                ? clicked.getItemMeta().getPersistentDataContainer().get(categoryKey, PersistentDataType.STRING)
                : null;
        boolean overview = session.category == null;
        // openInventory を InventoryClickEvent の処理中に呼ぶとカーソルがずれる(Bukkitの既知の癖)。
        // 必ず次tickへ逃がす。
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (overview) {
                if (slot == CLOSE_SLOT) {
                    player.closeInventory();
                    return;
                }
                if (slot == ROLE_SLOT) {
                    // 開くことはゲートで塞がない(ロールの効果を読む唯一の画面なので)。
                    // 実際に付け替える瞬間のゲートは RoleSelectGui 側で通す。
                    roleSelectGui.open(player);
                    return;
                }
                if (rawCategory != null) {
                    StatsCategory category = StatsCategory.parse(rawCategory.toLowerCase(Locale.ROOT));
                    if (category != null) {
                        openCategory(player, category);
                    }
                }
                return;
            }
            if (slot == BACK_SLOT) {
                openOverview(player);
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }

    /** 画面の種類を持つホルダー。{@code category == null} が概要画面。 */
    private static final class Session implements InventoryHolder {
        private final StatsCategory category;
        private Inventory inventory;

        private Session(StatsCategory category) {
            this.category = category;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
