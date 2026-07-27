package com.trinityforge.farming;

import org.bukkit.Material;

import java.util.Map;
import java.util.Set;

/**
 * 農業スキルツリー{@code auto-replant}/{@code area-harvest}が対象にする作物Materialと、その
 * 再設置(植え直し)に消費される種/苗Materialの対応表。Bukkit非依存(Materialは列挙のみで実行時サーバ
 * 不要)なので単体テストで直接検証できる。
 *
 * <p>{@code CARROTS}/{@code POTATOES}/{@code NETHER_WART}は収穫物そのものが種(自己参照)、
 * {@code WHEAT}/{@code BEETROOTS}は専用の種アイテムを持つ点に注意。
 */
public final class FarmingCropCatalog {

    private FarmingCropCatalog() {
    }

    private static final Map<Material, Material> SEED_OF = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS,
            Material.NETHER_WART, Material.NETHER_WART);

    /** auto-replant/area-harvest が対象にする作物Material一覧。 */
    public static final Set<Material> CROPS = SEED_OF.keySet();

    public static boolean isCrop(Material material) {
        return material != null && SEED_OF.containsKey(material);
    }

    /** 再設置で消費される種/苗Material。未知の作物Materialには{@code null}を返す(呼び出し側でnull安全)。 */
    public static Material seedMaterial(Material crop) {
        return SEED_OF.get(crop);
    }
}
