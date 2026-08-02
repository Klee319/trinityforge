package com.trinityforge.stats;

import com.trinityforge.pdc.BindType;
import org.bukkit.Material;

import java.util.List;
import java.util.Objects;

/**
 * One catalog item definition from {@code items/catalog.yml} (concern: add items by config alone).
 * Immutable. Holds only the fixed identity of an item; its stats are still derived live from
 * {@code stats/item-stats.yml} ({@link DerivedItemStats#profileStats}) at build time, so a catalog
 * entry never bakes numbers. {@link ItemFactory} turns a template + (rollSeed, quality) into a
 * finished item.
 *
 * @param id                  catalog key (matches the YAML section name)
 * @param material            the base Bukkit material
 * @param displayName         MiniMessage display name, or {@code null} to keep the vanilla name
 * @param customModelData     resource-pack model id, or {@code null} for none
 * @param bindType            ownership / trade constraint stamped on the item
 * @param useLevelRequirement required skill-tree level to use the item ({@code 0} = unrestricted)
 * @param useSkill            skill tree the requirement is measured against, or {@code null}
 * @param lore                flavor lore lines (MiniMessage), inserted ahead of the auto-generated
 *                            stat lore by {@link ItemAssembler}; empty when the catalog entry has none
 * @param recipes             crafting recipes ({@code recipe:} single section and/or {@code recipes:}
 *                            list); empty when the catalog entry declares none (the item is then
 *                            give/drop/gacha-only, as before). {@link #recipe()} returns the first
 *                            entry (or {@code null}) for single-recipe call sites.
 * @param color               optional leather-armor dye color as {@code "#RRGGBB"} hex, or {@code null}
 *                            for none. Only meaningful when {@code material} is a {@code LEATHER_*}
 *                            armor piece; validity/material-applicability is checked by
 *                            {@code ItemCatalogConfig} at parse time (fail-soft: an invalid or
 *                            non-leather {@code color:} is warned about and dropped, never fails the
 *                            whole item), so this record trusts whatever it is handed.
 * @param enchantGlow         when {@code true}, stamps a hidden enchant so the item shows the vanilla
 *                            enchant shimmer with no enchant listed in its tooltip (same
 *                            hidden-enchant + {@code HIDE_ENCHANTS} technique as the reference
 *                            {@code ConfigurableArmor} fork implementation). Defaults to {@code false}.
 * @param externalSource      「このIDの<b>実体</b>を持っているのは別プラグインだ」という宣言
 *                            ({@code items/catalog.yml} の {@code external-source:})。{@code null} =
 *                            TF カタログが実体を持つ(従来どおり)。現在の唯一の有効値は
 *                            {@link #EXTERNAL_SOURCE_ARSPAPER}。{@link CrossPluginItemResolver#create}
 *                            は<b>この宣言があるIDに限り</b>先に外部プラグイン側の実体を作りに行く。
 *                            <p>なぜ要るか: スレッドの装着可否はフォーク側 {@code ThreadGui#isEffectThread}
 *                            が Ars の PDC 2種({@code arspaper:custom_item_id} と
 *                            {@code arspaper:thread_item_type})で判定するが、<b>TF 本体は後者を
 *                            1箇所も書かない</b>。TF カタログ側で解決した「見た目だけ同じ」スレッドは
 *                            防具に挿さらないまま配られる。カタログ側にも同IDのエントリが要るのは
 *                            CMD 台帳・レシピ・図鑑・エディタ表示がカタログを真源にしているためで、
 *                            エントリごと消すことはできない ── そこで<b>実体の持ち主だけ</b>を宣言する。
 */
public record ItemTemplate(String id,
                           Material material,
                           String displayName,
                           Integer customModelData,
                           BindType bindType,
                           int useLevelRequirement,
                           String useSkill,
                           List<String> lore,
                           List<RecipeSpec> recipes,
                           String color,
                           boolean enchantGlow,
                           String externalSource) {

    /** {@code external-source: arspaper} — 実体は ArsPaper フォークの ItemRegistry が持つ。 */
    public static final String EXTERNAL_SOURCE_ARSPAPER = "arspaper";

    /**
     * TF が実際に「先に問い合わせる」経路を持っている外部ソース名(小文字)。ここに無い名前を
     * yml へ書いても解決先が存在せず<b>無言で何も起きない</b>ので、{@code ItemCatalogConfig} は
     * 警告を出して宣言ごと無視する(fail-soft: アイテム本体は従来どおりロードされる)。
     */
    public static final java.util.Set<String> KNOWN_EXTERNAL_SOURCES =
            java.util.Set.of(EXTERNAL_SOURCE_ARSPAPER);

    public ItemTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(bindType, "bindType");
        if (useLevelRequirement < 0) {
            throw new IllegalArgumentException("useLevelRequirement must be >= 0: " + useLevelRequirement);
        }
        lore = lore == null ? List.of() : List.copyOf(lore);
        recipes = recipes == null ? List.of() : List.copyOf(recipes);
        externalSource = (externalSource == null || externalSource.isBlank())
                ? null : externalSource.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 従来の 11 引数(正準)形。{@code external-source} 未宣言 = TF カタログが実体を持つ、として委譲する。
     * 既存の全呼び出し側(テスト含む)をそのままコンパイルさせるために残してある。
     */
    public ItemTemplate(String id, Material material, String displayName, Integer customModelData,
                        BindType bindType, int useLevelRequirement, String useSkill, List<String> lore,
                        List<RecipeSpec> recipes, String color, boolean enchantGlow) {
        this(id, material, displayName, customModelData, bindType, useLevelRequirement, useSkill, lore,
                recipes, color, enchantGlow, null);
    }

    /** 実体を別プラグインが持つと宣言されているか({@code external-source:} が有効値で書かれている)。 */
    public boolean hasExternalSource() {
        return externalSource != null;
    }

    /** この template に {@code external-source} だけを付け替えた複製。 */
    public ItemTemplate withExternalSource(String source) {
        return new ItemTemplate(id, material, displayName, customModelData, bindType, useLevelRequirement,
                useSkill, lore, recipes, color, enchantGlow, source);
    }

    /** Convenience constructor for callers with a single (possibly null) recipe. */
    public ItemTemplate(String id, Material material, String displayName, Integer customModelData,
                        BindType bindType, int useLevelRequirement, String useSkill, List<String> lore,
                        RecipeSpec recipe, String color, boolean enchantGlow) {
        this(id, material, displayName, customModelData, bindType, useLevelRequirement, useSkill, lore,
                recipe == null ? List.of() : List.of(recipe), color, enchantGlow);
    }

    /**
     * Convenience constructor for callers with no flavor lore and no recipe (source-compatible with
     * the pre-flavor-lore 7-arg shape).
     */
    public ItemTemplate(String id, Material material, String displayName, Integer customModelData,
                        BindType bindType, int useLevelRequirement, String useSkill) {
        this(id, material, displayName, customModelData, bindType, useLevelRequirement, useSkill, List.of(),
                (RecipeSpec) null);
    }

    /**
     * Convenience constructor for callers with flavor lore but no recipe (source-compatible with the
     * pre-recipe 8-arg shape).
     */
    public ItemTemplate(String id, Material material, String displayName, Integer customModelData,
                        BindType bindType, int useLevelRequirement, String useSkill, List<String> lore) {
        this(id, material, displayName, customModelData, bindType, useLevelRequirement, useSkill, lore,
                (RecipeSpec) null);
    }

    /**
     * Convenience constructor for callers with lore/recipe but no color/enchant-glow (source-compatible
     * with the pre-color/enchant-glow 9-arg canonical shape).
     */
    public ItemTemplate(String id, Material material, String displayName, Integer customModelData,
                        BindType bindType, int useLevelRequirement, String useSkill, List<String> lore,
                        RecipeSpec recipe) {
        this(id, material, displayName, customModelData, bindType, useLevelRequirement, useSkill, lore, recipe,
                null, false);
    }

    /** True when this template declares at least one recipe that parsed cleanly. */
    public boolean hasRecipe() {
        return !recipes.isEmpty();
    }

    /**
     * First declared recipe, or {@code null} when the entry has none. Kept for single-recipe call
     * sites (anvil combine / netherite lookups iterate {@link #recipes()} instead).
     */
    public RecipeSpec recipe() {
        return recipes.isEmpty() ? null : recipes.get(0);
    }

    /** True when a use requirement should be stamped (a skill is named and a level is set). */
    public boolean hasUseRequirement() {
        return useSkill != null && !useSkill.isBlank() && useLevelRequirement > 0;
    }
}
