package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Crafter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmithingInventory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Gates catalog AND vanilla recipes via the {@code recipe:<id>} dynamic gate id (2026-07-25 PRG-02):
 * a recipe nobody's skill tree references stays open (this addon's existing content default); the
 * moment any node places {@code recipe:<id>}, the recipe locks to players holding one of those nodes'
 * perks.
 *
 * <p><b>ユーザー決定(2026-07-25)</b>: 「ツリーにセットしない限り解放状態」— ゲート対象は
 * {@code dedicatedEffects.recipeGatePerks()}(=いずれかのスキルツリーノードが{@code recipe:<id>}として
 * 実際に配置しているID集合)に限られる。TFカタログレシピ(namespace {@code trinityforge}, path
 * {@code catalog_<id>})だけでなく、バニラレシピ(namespace {@code minecraft}等、path=アイテムID)も
 * 同じ判定に乗る。<b>配置されていないIDは常に開放</b>のため、バニラの剣が作れなくなる事態は起きない。
 *
 * <p>3経路をカバーする:
 * <ul>
 *   <li>{@link #onPrepareCraft} — 作業台/インベントリクラフト(既存catalog経路 + 新設vanilla経路)。</li>
 *   <li>{@link #onCrafterCraft} — Crafter(自動作業台、1.21)。{@code PrepareItemCraftEvent}はCrafterでは
 *       発火しない({@link CatalogWorkbenchListener#onCrafterCraft}と同じ理由、過去に移植漏れが実際に
 *       発生している)。Crafterはプレイヤー操作を伴わないため個々の解放状態を判定できず、ゲート対象
 *       レシピは安全側に倒して常時ブロックする。</li>
 *   <li>{@link #onPrepareSmithing} — ダイヤ→ネザライトのバニラ装備アップグレード(スミステーブル)。
 *       {@link org.bukkit.inventory.SmithingTransformRecipe}のキーpathは{@code "<id>_smithing"}という
 *       接尾辞を持ち{@link #resolveGateId}の直接一致では拾えないため、素材固定対応表
 *       ({@link #NETHERITE_UPGRADE_GATE_IDS})で解決する。</li>
 * </ul>
 */
public final class CatalogCraftGateListener implements Listener {

    private static final String CATALOG_PREFIX = "catalog_";
    private static final String GATE_PREFIX = "recipe:";
    private static final Component GATE_MESSAGE = Component.text(
            "このレシピを使うにはスキルツリーで解放する必要があります", NamedTextColor.RED);

    /**
     * バニラのネザライト装備アップグレード(スミステーブル)base材質→gate id 対応表。
     * smithing.yml D が配置する9件(netherite_axe〜netherite_sword)はこの固定表で解決する
     * (SmithingTransformRecipeのキーはpathが{@code "<id>_smithing"}でありPrepareItemCraftEventは
     * そもそも発火しないため、{@link #resolveGateId}の一般ロジックでは解決できない)。
     */
    private static final Map<Material, String> NETHERITE_UPGRADE_GATE_IDS = Map.ofEntries(
            Map.entry(Material.DIAMOND_SWORD, "netherite_sword"),
            Map.entry(Material.DIAMOND_PICKAXE, "netherite_pickaxe"),
            Map.entry(Material.DIAMOND_AXE, "netherite_axe"),
            Map.entry(Material.DIAMOND_SHOVEL, "netherite_shovel"),
            Map.entry(Material.DIAMOND_HOE, "netherite_hoe"),
            Map.entry(Material.DIAMOND_HELMET, "netherite_helmet"),
            Map.entry(Material.DIAMOND_CHESTPLATE, "netherite_chestplate"),
            Map.entry(Material.DIAMOND_LEGGINGS, "netherite_leggings"),
            Map.entry(Material.DIAMOND_BOOTS, "netherite_boots"));

    private final DedicatedEffectsConfig dedicatedEffects;

    public CatalogCraftGateListener(DedicatedEffectsConfig dedicatedEffects) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        CraftingInventory inv = event.getInventory();
        if (event.isRepair()) {
            if (!dedicatedEffects.isActive(player, "feature:wood-repair-unlock")) {
                inv.setResult(null);
                player.sendActionBar(GATE_MESSAGE);
            }
            return;
        }
        String gateId = resolveGateId(inv.getRecipe());
        if (isBlocked(gateId, player)) {
            inv.setResult(new ItemStack(Material.AIR));
            player.sendActionBar(GATE_MESSAGE);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        // バニラの同種修理にはKeyedレシピがないため、通常のrecipe gate判定だけでは
        // 自動作業台がwood-repair-unlockの迂回経路になる。Crafterには解放状態を
        // 帰属できるプレイヤーがいないので、同種修理は常に安全側で停止する。
        if (isCrafterRepair(event)) {
            event.setCancelled(true);
            return;
        }
        String gateId = resolveGateId(event.getRecipe());
        // Crafterはプレイヤー操作を伴わない(レッドストーン駆動)ため個々の解放状態を判定できない。
        // ゲート対象(=いずれかのスキルツリーノードが実際に配置しているID)である限り、安全側に倒して
        // 常時ブロックする(バイパス経路として悪用されるのを防ぐ)。未配置IDは従来通り素通し。
        if (gateId != null && dedicatedEffects.recipeGatePerks().containsKey(gateId)) {
            event.setCancelled(true);
        }
    }

    static boolean isCrafterRepair(CrafterCraftEvent event) {
        if (event.getBlock().getState() instanceof Crafter crafter) {
            return CraftQualityListener.isVanillaSameItemRepair(
                    crafter.getInventory().getContents(), event.getResult());
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        String gateId = netheriteGateId(event.getInventory());
        if (isBlocked(gateId, player)) {
            event.setResult(null);
        }
    }

    /**
     * Resolves the {@code recipe:<id>} gate id a keyed recipe corresponds to, or {@code null} when the
     * recipe isn't keyed / has no key (never gateable). TF catalog recipes strip the
     * {@code trinityforge:catalog_} prefix (existing behaviour); every other namespace (in practice
     * {@code minecraft}) uses the recipe key's path as-is — this is exactly how smithing.yml's 41
     * {@code recipe:<vanilla_item_id>} entries are authored (e.g. {@code minecraft:diamond_sword} ->
     * {@code "diamond_sword"}).
     */
    static String resolveGateId(Recipe recipe) {
        if (!(recipe instanceof Keyed keyed)) {
            return null;
        }
        NamespacedKey key = keyed.getKey();
        if (key == null) {
            return null;
        }
        String path = key.getKey();
        if ("trinityforge".equals(key.getNamespace())) {
            if (!path.startsWith(CATALOG_PREFIX)) {
                return null;
            }
            return path.substring(CATALOG_PREFIX.length());
        }
        return path;
    }

    private static String netheriteGateId(SmithingInventory inventory) {
        ItemStack template = inventory.getInputTemplate();
        ItemStack base = inventory.getInputEquipment();
        ItemStack addition = inventory.getInputMineral();
        if (template == null || template.getType() != Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE) {
            return null;
        }
        if (addition == null || addition.getType() != Material.NETHERITE_INGOT) {
            return null;
        }
        if (base == null || base.getType().isAir()) {
            return null;
        }
        return NETHERITE_UPGRADE_GATE_IDS.get(base.getType());
    }

    /**
     * True when {@code gateId} is configured (placed in some skill-tree node) and {@code player} lacks it.
     * Package-private (not {@code private}) for direct unit-test coverage (matches the "pure helper"
     * testability convention used elsewhere in this package, e.g. {@code CatalogWorkbenchListener#matches}).
     */
    boolean isBlocked(String gateId, Player player) {
        if (gateId == null) {
            return false;
        }
        if (!dedicatedEffects.recipeGatePerks().containsKey(gateId)) {
            return false; // no skill-tree node references this recipe -> open by default
        }
        return !dedicatedEffects.isActive(player, GATE_PREFIX + gateId);
    }

    /**
     * 起動時検証(PRG-02 要件5): {@code recipeGatePerks()} が持つ全gate idについて、実際にクラフト可能な
     * 何か(TFカタログ品 / バニラの実在レシピ / 既知のネザライトアップグレード対応表)へ解決できるかを
     * チェックする。解決できないIDは「配置されているのに何もゲートしない」サイレント無効ゲートになる
     * ため、綴り間違い等を起動時に警告で検出する(コードは書き換えない、報告のみ)。
     *
     * <p><b>呼び出しタイミングの制約 (2026-07-28)</b>: {@link #hasVanillaRecipe} が
     * {@code Bukkit.recipeIterator()} を舐めるので、<b>全プラグインの enable 完了後</b>
     * (= {@code TrinityForge#onEnable} 内ではなく {@code runTask} の最初のtick)に呼ぶこと。
     * ArsPaper は TF に depend しており TF より後に enable するため、onEnable 内で呼ぶと
     * ArsPaper の作業台レシピ(tf_core_* など)が未登録で、実在するのに「解決できない」と誤警告する。
     *
     * <p>なお {@code ritual:} ゲートはここでは検証<b>しない</b>。儀式は Bukkit のレシピではなく
     * ArsPaper 内部の {@code RitualRecipe} なので TF からは列挙できない。{@code recipe:}/{@code ritual:}
     * のチャンネル取り違え(儀式アイテムを recipe: に置くと無言で常時解放になる)は、代わりに
     * ビルド時の {@code RecipeRitualGateChannelDriftTest} が固定している。
     */
    public static void verifyRecipeGateIds(DedicatedEffectsConfig dedicatedEffects,
                                            ItemCatalogConfig itemCatalog, Logger log) {
        List<String> unresolved = new ArrayList<>();
        for (String id : dedicatedEffects.recipeGatePerks().keySet()) {
            if (itemCatalog.template(id).isPresent()) {
                continue; // TFカタログ品
            }
            if (NETHERITE_UPGRADE_GATE_IDS.containsValue(id)) {
                continue; // 既知のネザライトアップグレード対応表
            }
            if (hasVanillaRecipe(id)) {
                continue; // 何らかの実在レシピ(minecraft等)のキーpathと一致
            }
            unresolved.add(id);
        }
        if (!unresolved.isEmpty()) {
            log.warning("[PRG-02] recipe:<id> gate(s) placed in a skill tree do not resolve to any "
                    + "TF catalog item, vanilla recipe, or known netherite upgrade — these gates are "
                    + "silently inert (the recipe, if it exists at all under a different id, stays "
                    + "permanently unlocked): " + unresolved);
        }
    }

    private static boolean hasVanillaRecipe(String id) {
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe = it.next();
            if (recipe instanceof Keyed keyed && keyed.getKey() != null
                    && id.equals(keyed.getKey().getKey())) {
                return true;
            }
        }
        return false;
    }
}
