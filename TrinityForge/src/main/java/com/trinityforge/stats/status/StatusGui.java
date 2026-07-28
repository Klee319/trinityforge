package com.trinityforge.stats.status;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.command.StatsCategory;
import com.trinityforge.config.domains.LoreConfig;
import com.trinityforge.progression.SkillLevelSource;
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
 */
public final class StatusGui implements Listener {

    private static final int SIZE = 54;
    private static final int HEAD_SLOT = 4;
    private static final int SKILLS_SLOT = 40;
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
    private final NamespacedKey categoryKey;

    public StatusGui(Plugin plugin, SymmetricCombatService combatService, PlayerStatAggregator aggregator,
                     LoreConfig loreConfig, SkillLevelSource skillLevelSource, NativePerkService perkService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combatService = Objects.requireNonNull(combatService, "combatService");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.loreConfig = Objects.requireNonNull(loreConfig, "loreConfig");
        this.skillLevelSource = Objects.requireNonNull(skillLevelSource, "skillLevelSource");
        this.perkService = perkService;
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
        inventory.setItem(SKILLS_SLOT, skillsIcon(player));
        inventory.setItem(CLOSE_SLOT, simple(Material.BARRIER, "閉じる", List.of()));
        player.openInventory(inventory);
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
        AttributeInstance armor = player.getAttribute(Attribute.ARMOR);
        AttributeInstance toughness = player.getAttribute(Attribute.ARMOR_TOUGHNESS);
        lore.add(entry("バニラ防御 (armor/toughness)",
                StatValueRenderer.plain(armor != null ? armor.getValue() : 0.0) + " / "
                        + StatValueRenderer.plain(toughness != null ? toughness.getValue() : 0.0)));
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
                lore.add(entry(skillLabel(skillId), Integer.toString(level)));
            }
            // 並び順表に無いスキル(config追加分)も落とさない。
            for (Map.Entry<String, Integer> e : levels.entrySet()) {
                if (!SKILL_ORDER.contains(e.getKey())) {
                    lore.add(entry(skillLabel(e.getKey()), Integer.toString(e.getValue())));
                }
            }
        }
        lore.add(Component.empty());
        lore.add(plain("/skills でスキルツリーを開けます", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        stack.setItemMeta(meta);
        return stack;
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
