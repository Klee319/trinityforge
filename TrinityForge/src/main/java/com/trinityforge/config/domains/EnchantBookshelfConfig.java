package com.trinityforge.config.domains;

import com.trinityforge.config.ConfigDomain;
import com.trinityforge.config.ConfigSchema;
import com.trinityforge.config.SchemaField;

/**
 * Typed accessor for the {@code enchant-bookshelf-power} サブツリー of
 * {@code progression/crafting-features.yml}({@link CraftingFeaturesConfig#PATH} と同一ファイル、
 * over-enchant と同じ階層に置かれる新設サブツリー、2026-07-26)。
 *
 * <p>バニラ仕様(要確認済み、下記): エンチャントテーブルの提示レベルは周囲の本棚の数
 * (「エンチャントパワー」)で決まり、Bukkit/Paper API では
 * {@link org.bukkit.event.enchantment.PrepareItemEnchantEvent#getEnchantmentBonus()} がその値を返す。
 * バニラの内部計算式(本棚数を power として渡す {@code EnchantmentHelper.getEnchantmentCost}
 * 相当のロジック)は {@code power > 15} を一律 15 として扱うため、本棚は15個で頭打ちになる
 * (Minecraft Wiki "Enchanting table mechanics": 「最大の有効本棚数は15個、それを超える分は無視される」)。
 * この15個という上限は、Bukkit/Paperの公開APIには config 値として存在せず、NMS内部に固定されている
 * ため、TrinityForge独自の上乗せレイヤーとしてしか再現できない(このクラスの役割)。
 *
 * <p>この上限値(15)と「本棚1個=パワー1」という暗黙の係数を、config で調整可能にする。
 * 適用は {@code EnchantCostReductionListener}(相乗り、既存の {@code enchant_cost_reduction} 適用と
 * 同じイベント2点 — {@link org.bukkit.event.enchantment.PrepareItemEnchantEvent} と
 * {@link org.bukkit.event.enchantment.EnchantItemEvent} — に対して行う)。
 *
 * <p>既定値({@code max-bookshelves: 15}, {@code power-per-bookshelf: 1.0})では
 * 常に「実効パワー = バニラの実効パワー」となり、挙動は一切変化しない
 * ({@code EnchantCostReductionListenerTest} で検証)。
 */
public final class EnchantBookshelfConfig {

    /** {@code progression/crafting-features.yml} と同一ファイル(over-enchant と同階層のサブツリー)。 */
    public static final String PATH = CraftingFeaturesConfig.PATH;

    /** バニラが本棚パワーとして考慮する物理本棚数の上限(Minecraft Wiki 記載の固定値)。 */
    public static final int VANILLA_MAX_BOOKSHELVES = 15;

    private final ConfigDomain domain;

    public EnchantBookshelfConfig() {
        ConfigSchema schema = new ConfigSchema()
                .field(SchemaField.number(
                        "enchant-bookshelf-power.max-bookshelves", SchemaField.Type.INT,
                        VANILLA_MAX_BOOKSHELVES, 0, 1000))
                .field(SchemaField.number(
                        "enchant-bookshelf-power.power-per-bookshelf", SchemaField.Type.DOUBLE,
                        1.0, 0.0, 100.0));
        this.domain = new ConfigDomain(PATH, schema);
    }

    public ConfigDomain domain() {
        return domain;
    }

    /** 考慮する本棚の最大数(既定値はバニラと同じ15)。 */
    public int maxBookshelves() {
        return Math.max(0, domain.get().getInt("enchant-bookshelf-power.max-bookshelves"));
    }

    /** 本棚1個あたりのエンチャントパワー係数(既定値はバニラと同じ1.0)。 */
    public double powerPerBookshelf() {
        return Math.max(0.0, domain.get().getDouble("enchant-bookshelf-power.power-per-bookshelf"));
    }
}
