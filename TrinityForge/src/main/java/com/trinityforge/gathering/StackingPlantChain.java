package com.trinityforge.gathering;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 「置く→壊す」ガード対象だが縦に連結して育つ植物(サトウキビ/竹/コンブ/サボテン/ツタ)を分類する。
 *
 * <p>これらは {@link com.trinityforge.farming.CropMaturity#isMaturityGated} が {@code false}
 * (成熟の概念が無い＝根元は常に「置く→壊す」ガード対象のまま)だが、根元を壊すとバニラの物理挙動で
 * <b>上(ツタは下)に伸びた段がまとめて消える</b>。この消滅は{@code BlockBreakEvent}を伴わない物理崩壊
 * (支持ブロック喪失による自動破壊)なので、何もしなければ<b>育った段のEXPがまるごと失われる</b>
 * (2026-08-03 実サーバ報告「サトウキビの根元を壊すと経験値が入らなかった」の真因)。
 *
 * <p>対策は{@code NativeSkillExperienceListener}側: 起点(プレイヤーが直接壊したブロック)がここに
 * 分類される場合、崩壊方向へ同系統のブロックが続く限り連鎖対象として集め、バニラが崩す<b>前</b>に
 * 自前で({@link ChainBreakSupport}経由)1つずつ崩してEXPを付与する。連鎖対象の各ブロックも
 * {@code grantChainBreak}を通るため、<b>手植えで積み上げた段は個別に「置く→壊す」ガードへ掛かり続け、
 * 抜け道にはならない</b>(設置マークはチャンクPDC座標ベースで、そのブロックがどの由来のMaterialへ
 * 育っても消えない)。
 *
 * <p><b>コーラスプラント(CHORUS_PLANT)は意図的に対象外</b>: (1) コーラスフルーツは設置不可アイテムで
 * 通常は「置く→壊す」ガードの対象にすらならない(Silk Touchで入手した本体ブロックの再設置という稀な
 * 例外はあるが主経路ではない)、(2) 分岐構造で単純な直線連結ではないため、この直線探索方式では
 * 安全に連鎖範囲を確定できない。
 */
public final class StackingPlantChain {

    /** 1系統＝連結方向＋同一系統とみなす{@link Material}集合(成長点と本体で別Materialのものがある)。 */
    public record Family(BlockFace direction, Set<Material> members) {
    }

    private static final Map<Material, Family> FAMILIES = buildFamilies();

    private StackingPlantChain() {
    }

    /** {@code type}が連鎖崩壊系統に属するなら、その系統定義。属さないなら{@code null}。 */
    public static Family familyOf(Material type) {
        return FAMILIES.get(type);
    }

    private static Map<Material, Family> buildFamilies() {
        Map<Material, Family> map = new EnumMap<>(Material.class);
        register(map, BlockFace.UP, Material.SUGAR_CANE);
        register(map, BlockFace.UP, Material.CACTUS);
        register(map, BlockFace.UP, Material.BAMBOO);
        // KELPが成長点(移動していく)、KELP_PLANTが本体。プレイヤーが置けるのはKELPのみだが、
        // 育つと設置座標のMaterialもKELP_PLANTへ変わりうるため両方を同一系統として扱う。
        register(map, BlockFace.UP, Material.KELP, Material.KELP_PLANT);
        register(map, BlockFace.UP, Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT);
        // ねじれツタと違い、泣きツタは上のブロックから垂れ下がって育つ(崩壊方向が逆)。
        register(map, BlockFace.DOWN, Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT);
        return Map.copyOf(map);
    }

    private static void register(Map<Material, Family> map, BlockFace direction, Material... members) {
        Family family = new Family(direction, Set.of(members));
        for (Material material : members) {
            map.put(material, family);
        }
    }
}
