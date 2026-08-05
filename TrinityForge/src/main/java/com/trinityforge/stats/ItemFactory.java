package com.trinityforge.stats;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.WeaponBaseFormula;
import com.trinityforge.pdc.ItemData;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Write-side builder of a catalog item: turns an {@link ItemTemplate} plus a {@code (rollSeed,
 * quality)} pair into a finished {@link ItemStack}. The single place items are created from the
 * catalog, so identity (material, name, model, bind, use requirement) is stamped consistently and
 * the stats/lore/attributes go through {@link ItemAssembler} exactly as drop/craft flows do.
 *
 * <p>Thin Bukkit adapter only: all parsing/validation lives in {@link ItemTemplate} and
 * {@code ItemCatalogConfig}; the numeric derivation lives in {@link ItemAssembler}.
 */
public final class ItemFactory {

    /**
     * Hidden enchant stamped for {@link ItemTemplate#enchantGlow()}: any vanilla enchant works for the
     * shimmer effect, {@code UNBREAKING} is picked because it is valid on every enchantable material
     * (armor, tools, weapons), and {@link ItemFlag#HIDE_ENCHANTS} keeps it out of the tooltip so the
     * glow shows with no enchant text (same technique as the reference {@code ConfigurableArmor} fork).
     */
    private static final NamespacedKey ENCHANT_GLOW_KEY = NamespacedKey.minecraft("unbreaking");

    private final ItemAssembler assembler;
    private final ItemStatsConfig itemStats;
    private final CraftingFeaturesConfig craftingFeatures;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public ItemFactory(ItemAssembler assembler) {
        this(assembler, null);
    }

    public ItemFactory(ItemAssembler assembler, ItemStatsConfig itemStats) {
        this(assembler, itemStats, null);
    }

    /**
     * Overload that also wires {@code progression/crafting-features.yml}'s thread-slot category caps
     * ({@link CraftingFeaturesConfig#threadSlotMaxByCategory()}), required by
     * {@link #expandRitualThreadSlot(ItemStack, int)} to pre-validate a ritual's slot expansion before
     * consuming materials. {@code craftingFeatures} may be {@code null} (matches the existing
     * {@code itemStats == null} tolerance in tests/callers that never invoke that method).
     */
    public ItemFactory(ItemAssembler assembler, ItemStatsConfig itemStats, CraftingFeaturesConfig craftingFeatures) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.itemStats = itemStats;
        this.craftingFeatures = craftingFeatures;
    }

    /**
     * 装備とまったく同じ体裁のステ lore ブロックを組む
     * ({@link ItemAssembler#statLoreBlock} への委譲)。
     *
     * <p>用途は「TF が組んだ lore をフォークが自前の lore で上書きするアイテム」— ArsPaper の
     * スレッドがこれで、種類ごとの効果説明とスロット案内を自前で足す都合上 lore を作り直している。
     * その作り直しの<b>ステ部分だけ</b>をこの経路に委ねれば、品質行/区切り線/ロール色まで含めて
     * 装備と一致する(フォークが自前で連結すると必ず食い違う — 2026-08-04/08-05 の実害2件)。
     */
    public java.util.List<net.kyori.adventure.text.Component> statLoreBlock(
            org.bukkit.Material material, Integer cmd, int quality, long rollSeed) {
        return assembler.statLoreBlock(material, cmd, quality, rollSeed);
    }

    /**
     * Builds a fresh item from {@code template}, seeded by {@code rollSeed} at {@code quality}.
     *
     * <p>{@code setCustomModelData(int)} is deprecated in favour of the 1.21 component API but
     * remains the simplest stable way to set a single model id; suppressed deliberately.
     */
    @SuppressWarnings("deprecation")
    public ItemStack create(ItemTemplate template, long rollSeed, int quality) {
        ItemStack stack = new ItemStack(template.material());
        ItemMeta meta = buildIdentity(template, stack);

        // Stamps rollSeed + quality and applies the derived stats, lore, and attribute modifiers,
        // scoped to the item's own material (item 8: no armor-in-hand double-dip).
        assembler.assemble(meta, template.material(), rollSeed, quality);

        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Builds ONLY the template's identity (display name, model, bind type, use requirement, catalog
     * id) with NO rollSeed/quality/stats/lore stamp — deliberately leaves the item without a
     * TrinityForge roll so it reads as a "fresh, unstamped" item to any downstream stamping hook.
     *
     * <p>Used as the registered RESULT item of a catalog {@code recipe:} ({@link
     * com.trinityforge.stats.CatalogRecipeRegistrar}): {@code CraftQualityListener} only re-stamps a
     * craft result that has no rollSeed yet ({@code ItemData#hasRollSeed()} guard), exactly like a
     * vanilla/ValhallaMMO/ArsPaper recipe result. Reusing {@link #create}'s stamped output instead
     * would bake one fixed rollSeed into the recipe itself, so every crafter got byte-identical rolls
     * and CraftQualityListener would skip re-stamping entirely (design decision, ITEM_ECONOMY_SPEC
     * 5.2c parity with other craft flows).
     */
    @SuppressWarnings("deprecation")
    public ItemStack createIdentityOnly(ItemTemplate template) {
        ItemStack stack = new ItemStack(template.material());
        ItemMeta meta = buildIdentity(template, stack);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Shared identity-only build step for {@link #create} and {@link #createIdentityOnly}. */
    @SuppressWarnings("deprecation")
    private ItemMeta buildIdentity(ItemTemplate template, ItemStack stack) {
        Objects.requireNonNull(template, "template");
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            // Defensive: the catalog already rejects air, but a meta-less material would NPE below.
            throw new IllegalStateException("material " + template.material() + " has no item meta");
        }

        if (template.displayName() != null) {
            meta.displayName(miniMessage.deserialize(template.displayName())
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        if (template.customModelData() != null) {
            meta.setCustomModelData(template.customModelData());
        }
        if (template.color() != null && meta instanceof LeatherArmorMeta leatherMeta) {
            leatherMeta.setColor(hexToColor(template.color()));
        }
        if (template.enchantGlow()) {
            applyEnchantGlow(meta);
        }

        ItemData data = ItemData.of(meta);
        data.setBindType(template.bindType());
        // Prefer item-stats use-skill/level when present; fall back to catalog template.
        Optional<ItemUseRequirement> fromStats = itemStats == null
                ? Optional.empty()
                : itemStats.useRequirementFor(template.material(), template.customModelData());
        if (fromStats.isPresent() && fromStats.get().shouldStamp()) {
            ItemUseRequirement req = fromStats.get();
            data.setUseRequirement(req.skill(), req.levelOrZero());
        } else if (template.hasUseRequirement()) {
            data.setUseRequirement(template.useSkill(), template.useLevelRequirement());
        }
        // Stamps the catalog id BEFORE assemble() so ItemAssembler can resolve this template's flavor
        // lore on this build AND on every future re-assembly (ItemRefreshListener has no template, only
        // the item's own PDC). Not set by stamp() below: that path is not catalog-sourced.
        data.setCatalogId(template.id());
        return meta;
    }

    /**
     * カタログの {@code enchant-glow: true} 由来の隠しエンチャント(+{@link ItemFlag#HIDE_ENCHANTS})を
     * (再)付与する。{@link #buildIdentity} 以外に、砥石でエンチャントを剥がした後の復元
     * ({@code GrindstonePreserveListener}) からも呼ぶため public: キー({@link #ENCHANT_GLOW_KEY})を
     * 呼び出し側で複製すると、glow の実装を変えたときに片方だけ取り残される。
     */
    public static void applyEnchantGlow(ItemMeta meta) {
        Objects.requireNonNull(meta, "meta");
        Enchantment glow = Registry.ENCHANTMENT.get(ENCHANT_GLOW_KEY);
        if (glow != null) {
            meta.addEnchant(glow, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
    }

    /**
     * Parses a validated {@code "#RRGGBB"} hex string (already checked by
     * {@code ItemCatalogConfig#parseColor}) into a Bukkit {@link Color}. Not defensive against a
     * malformed string on purpose: the catalog loader is the sole caller path that fills
     * {@link ItemTemplate#color()}, and it never hands this a string that fails the hex-format check.
     */
    private static Color hexToColor(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return Color.fromRGB(rgb);
    }

    /**
     * Stamps {@code rollSeed} + {@code quality} onto an EXISTING item in place and applies the derived
     * stats/lore/attributes/tool-enchants, preserving the item's own identity (display name, model) and
     * any other plugin's PDC — {@link ItemAssembler} only writes TrinityForge-namespaced keys. Used by
     * the craft/fishing stamp hooks to give a crafted or caught piece of equipment its TrinityForge
     * quality (ITEM_ECONOMY_SPEC 5.2c/5.2d), without rebuilding it from a catalog template.
     */
    public void stamp(ItemStack stack, long rollSeed, int quality) {
        stamp(stack, rollSeed, quality, CraftRollMods.NONE);
    }

    /**
     * Overload of {@link #stamp(ItemStack, long, int)} that also bakes the crafter's stage-2 (ステータス
     * ロール) perk deltas into the item's PDC BEFORE assembly, since {@link ItemAssembler} has no crafter
     * context at derivation time. {@code rollMods} defaults to {@link CraftRollMods#NONE} when null.
     */
    public void stamp(ItemStack stack, long rollSeed, int quality, CraftRollMods rollMods) {
        Objects.requireNonNull(stack, "stack");
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        ItemData data = ItemData.of(meta);
        data.setCraftRollMods(rollMods == null ? CraftRollMods.NONE : rollMods);
        assembler.assemble(meta, stack.getType(), rollSeed, quality);
        stack.setItemMeta(meta);
    }

    /**
     * 「スレッド枠拡張」儀式(ArsPaper {@code ThreadSlotExpandRitualEffect})用: 既存装備の seed/quality
     * はそのまま維持し、儀式由来の累計付与カウンタ ({@code ritual_thread_slot_bonus})
     * のみ +1 して再組み立てする。呼び出し側(儀式)は結果を元コアへ書き戻す。
     *
     * <p>失敗(素材消費なし)して {@link Optional#empty()} を返す条件は2つ:
     * <ol>
     *   <li>この儀式による累計付与数が {@code maxSlots}(儀式の {@code max-slots} パラメータ = 累計上限)に
     *       既に到達している。</li>
     *   <li>カテゴリ上限({@code progression/crafting-features.yml} の {@code thread-slot-max-by-category}、
     *       {@link ThreadSlotPolicy#capFor}) を現在の解決済み {@code thread-slots} が既に満たしている
     *       (これ以上増やしても {@link ThreadSlotPolicy#applyCategoryCap} で切り捨てられるだけの無駄打ち)。</li>
     * </ol>
     * {@code craftingFeatures}/{@code itemStats} が未配線(null)の呼び出し元ではカテゴリ上限の事前チェックを
     * スキップする(累計上限チェックのみ行う) — 最終的な安全網は {@link ThreadSlotPolicy#applyCategoryCap}
     * が derivation 時に必ずクランプするため、事前チェック省略は UX 上の失敗メッセージが出ないだけで安全。
     */
    public Optional<ItemStack> expandRitualThreadSlot(ItemStack stack, int maxSlots) {
        Objects.requireNonNull(stack, "stack");
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        ItemData data = ItemData.of(meta);
        int ritualBonus = data.ritualThreadSlotBonus();
        if (ritualBonus >= maxSlots) {
            return Optional.empty();
        }
        if (itemStats != null && craftingFeatures != null) {
            Map<String, Double> currentStats = DerivedItemStats.resolve(
                    stack, itemStats, WeaponBaseFormula.disabled(), craftingFeatures.threadSlotMaxByCategory());
            int currentSlots = (int) Math.floor(
                    currentStats.getOrDefault(StatKeys.canonical("thread-slots"), 0.0));
            int cap = ThreadSlotPolicy.capFor(stack.getType(), craftingFeatures.threadSlotMaxByCategory());
            if (cap <= 0 || currentSlots >= cap) {
                return Optional.empty();
            }
        }

        long rollSeed = data.rollSeed().orElse(0L);
        int quality = data.quality();
        data.setRitualThreadSlotBonus(ritualBonus + 1);
        assembler.assemble(meta, stack.getType(), rollSeed, quality);

        ItemStack result = stack.clone();
        result.setItemMeta(meta);
        return Optional.of(result);
    }
}
