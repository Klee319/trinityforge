package com.trinityforge.stats;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.EquipmentAssetsConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.WeaponBaseFormula;
import com.trinityforge.pdc.ItemData;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.key.Key;
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
     * Legacy dummy used before Paper's enchantment-glint override. Kept only to (a) strip that dummy
     * off already-issued items and (b) fall back when MockBukkit does not implement the override API.
     * Production Paper 1.21 uses {@link ItemMeta#setEnchantmentGlintOverride(Boolean)} so the item is
     * not "already enchanted" (enchanting table / anvil books) and the grindstone has nothing to peel.
     */
    private static final NamespacedKey ENCHANT_GLOW_KEY = NamespacedKey.minecraft("unbreaking");

    /**
     * 装備レイヤーの名前空間。パック側の {@code assets/trinityforge/equipment/<name>.json} と
     * 一致していなければならない（{@code resourcepack/build_equipment_assets.py} の {@code NAMESPACE}）。
     */
    private static final String EQUIPMENT_ASSET_NAMESPACE = "trinityforge";

    private final ItemAssembler assembler;
    private final ItemStatsConfig itemStats;
    private final CraftingFeaturesConfig craftingFeatures;
    private final EquipmentAssetsConfig equipmentAssets;
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
        this(assembler, itemStats, craftingFeatures, null);
    }

    /**
     * 防具の「装備したときの見た目」を差し替える {@link EquipmentAssetsConfig} まで含めた完全形。
     * {@code equipmentAssets} は {@code null} 可（既存の呼び出し・テストは何も貼らない挙動になる）。
     */
    public ItemFactory(ItemAssembler assembler, ItemStatsConfig itemStats,
            CraftingFeaturesConfig craftingFeatures, EquipmentAssetsConfig equipmentAssets) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.itemStats = itemStats;
        this.craftingFeatures = craftingFeatures;
        this.equipmentAssets = equipmentAssets;
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

    /** {@link ItemAssembler#appendOwnerLoreIfMissing(ItemStack)} への委譲。 */
    public boolean appendOwnerLoreIfMissing(ItemStack stack) {
        return assembler.appendOwnerLoreIfMissing(stack);
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
        stampEquipmentAsset(stack, template);
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
        stampEquipmentAsset(stack, template);
        return stack;
    }

    /**
     * 防具の「装備したときに体の上へ乗るレイヤー」を、{@code items/equipment-assets.yml} の
     * 割り当てどおりに差し替える。
     *
     * <p><b>CMD ではここは変わらない。</b>手持ち/インベントリの見た目は
     * {@code custom-model-data}、着たときのレイヤーは {@code minecraft:equippable} の
     * {@code asset_id} と、系統がそもそも別（{@link EquipmentAssetsConfig} 参照）。
     *
     * <p>3つの理由で「壊れない側」に倒してある:
     * <ul>
     *   <li>割り当てが無ければ何もしない → 既定では全防具がバニラの見た目のまま。
     *       yml はパックに実物があるセットだけを載せる生成物なので、
     *       「定義の無い asset_id を書いて防具が透明になる」事故が構造的に起きない。</li>
     *   <li>元の {@code equippable} が無いアイテム（＝そもそも装備できない）には触らない。</li>
     *   <li>{@code setData} はコンポーネントを丸ごと差し替えるので、必ず既存値から
     *       {@code toBuilder()} して {@code asset_id} だけを上書きする。
     *       新規に組むと装備スロット・装備音・ダメージ挙動・ディスペンサー可否まで
     *       既定値へ巻き戻る（防具が着られなくなる）。</li>
     * </ul>
     *
     * <p>{@link ItemMeta} ではなく {@link ItemStack} 側の API なので、
     * {@code setItemMeta} の【後】に呼ぶこと。先に呼ぶと meta の書き戻しで消える。
     */
    private void stampEquipmentAsset(ItemStack stack, ItemTemplate template) {
        if (equipmentAssets == null) return;
        String asset = equipmentAssets.assetFor(template.id());
        if (asset == null) return;
        applyEquipmentAsset(stack, asset);
    }

    /**
     * {@code stack} の {@code equippable} の {@code asset_id} だけを {@code asset} に差し替える。
     * 差し替えたら {@code true}、装備できないアイテム（{@code equippable} が無い）なら
     * 何もせず {@code false}。
     *
     * <p>{@link #stampEquipmentAsset} から切り出してあるのは、
     * <b>MockBukkit がバニラ既定のデータコンポーネントを持たない</b>ため
     * （{@code new ItemStack(DIAMOND_HELMET).getData(EQUIPPABLE)} が実サーバでは非 null、
     * MockBukkit では null）。ここを直接叩けるようにしておかないと、
     * 「既存値を保ったまま asset_id だけ差し替える」という肝心の挙動が
     * <b>一度も実行されないまま緑になる</b>。
     */
    static boolean applyEquipmentAsset(ItemStack stack, String asset) {
        Equippable current = stack.getData(DataComponentTypes.EQUIPPABLE);
        if (current == null) return false;
        stack.setData(DataComponentTypes.EQUIPPABLE,
                current.toBuilder().assetId(Key.key(EQUIPMENT_ASSET_NAMESPACE, asset)));
        return true;
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
     * カタログの {@code enchant-glow: true} 由来の見た目光沢を (再)付与する。
     * Paper 1.21 の {@link ItemMeta#setEnchantmentGlintOverride(Boolean)} を使う。
     * ダミー {@code UNBREAKING} + {@link ItemFlag#HIDE_ENCHANTS} は使わない
     * （テーブルが「既にエンチャント済み」と見なす／砥石で剥がれる／後付けエンチャントが
     * ツールチップごと隠れる）。{@link #buildIdentity} 以外に、砥石復元
     * ({@code GrindstonePreserveListener}) と {@link ItemAssembler#assemble} からも呼ぶ。
     */
    public static void applyEnchantGlow(ItemMeta meta) {
        Objects.requireNonNull(meta, "meta");
        stripLegacyGlowDummy(meta);
        if (trySetGlintOverride(meta, Boolean.TRUE)) {
            return;
        }
        Enchantment glow = Registry.ENCHANTMENT.get(ENCHANT_GLOW_KEY);
        if (glow == null) {
            return;
        }
        meta.addEnchant(glow, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
    }

    /**
     * カタログの {@code enchant-glow: false} へ合わせる。glint override を外し、旧実装が残した
     * {@link ItemFlag#HIDE_ENCHANTS}（とダミー耐久力 I）を落とす。砥石でダミーだけ剥がしたあとに
     * フラグが残ると、後から付けた本物のエンチャントがツールチップに出ない。
     */
    public static void clearEnchantGlow(ItemMeta meta) {
        Objects.requireNonNull(meta, "meta");
        stripLegacyGlowDummy(meta);
        trySetGlintOverride(meta, null);
    }

    /** {@code glow} に合わせて付与または解除する（カタログ現値の単一入口）。 */
    public static void syncEnchantGlow(ItemMeta meta, boolean glow) {
        if (glow) {
            applyEnchantGlow(meta);
        } else {
            clearEnchantGlow(meta);
        }
    }

    /**
     * 旧実装（ダミーエンチャントを隠すための {@link ItemFlag#HIDE_ENCHANTS}）が残っているか。
     * テーブル世代が最新でも、持ち替え時に一度 assemble してフラグを落とす判定に使う。
     */
    public static boolean hasLegacyGlowResidue(ItemMeta meta) {
        return meta != null && meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS);
    }

    /**
     * 旧 glow の署名は「{@link ItemFlag#HIDE_ENCHANTS} が立っている」。立っていればフラグを外し、
     * 同時に付いていたダミー耐久力 I だけを剥がす。プレイヤーが付けた耐久力 II 以上や、
     * フラグ無しの本物の耐久力 I には触らない。
     */
    private static void stripLegacyGlowDummy(ItemMeta meta) {
        boolean hidden = meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS);
        meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        if (!hidden) {
            return;
        }
        Enchantment unbreaking = Registry.ENCHANTMENT.get(ENCHANT_GLOW_KEY);
        if (unbreaking != null && meta.getEnchantLevel(unbreaking) == 1) {
            meta.removeEnchant(unbreaking);
        }
    }

    /**
     * @return {@code true} when the override was stored. MockBukkit may no-op or throw
     *         {@code UnimplementedOperationException}; callers then fall back to the dummy enchant.
     */
    private static boolean trySetGlintOverride(ItemMeta meta, Boolean value) {
        try {
            meta.setEnchantmentGlintOverride(value);
            if (value == null) {
                return !meta.hasEnchantmentGlintOverride()
                        || meta.getEnchantmentGlintOverride() == null;
            }
            return value.equals(meta.getEnchantmentGlintOverride());
        } catch (UnsupportedOperationException | NoSuchMethodError e) {
            return false;
        } catch (RuntimeException e) {
            if (e.getClass().getName().contains("UnimplementedOperation")) {
                return false;
            }
            throw e;
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

    /**
     * 厳選の護符の対象: {@code item-stats.yml} に random 層(または grant の seed 抽選)がある。
     * プロファイルが無いカタログ品(護符・素材など)は {@code false}。{@code itemStats} 未配線の
     * テストでは {@code true}(既存テストは rollSeed の有無だけを見ている)。
     */
    public boolean hasRerollableRandom(ItemStack stack) {
        if (itemStats == null) {
            return true;
        }
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        Integer cmd = DerivedItemStats.customModelDataOf(stack.getItemMeta());
        return itemStats.profileFor(stack.getType(), cmd)
                .map(ItemStatProfile::randomApplies)
                .orElse(false);
    }

    /**
     * 品質昇華の結晶の対象: 品質で値が動く層がある。固定値だけの素材・特殊アイテムは {@code false}。
     * {@code itemStats} 未配線のテストでは {@code true}。
     */
    public boolean qualityVaries(ItemStack stack) {
        if (itemStats == null) {
            return true;
        }
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        Integer cmd = DerivedItemStats.customModelDataOf(stack.getItemMeta());
        return itemStats.profileFor(stack.getType(), cmd)
                .map(ItemStatProfile::qualityApplies)
                .orElse(false);
    }
}
