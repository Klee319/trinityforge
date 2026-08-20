package com.trinityforge.farming;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 「成熟しないと収穫できない作物」の判定を一元化するクラス（2026-08-01 U9）。
 *
 * <p><b>なぜ {@link Ageable} だけでは判定できないか:</b> Bukkit の {@code Ageable} は
 * 「{@code age} プロパティを持つブロック」という意味しか持たず、<b>{@code age} の意味はブロックごとに
 * 違う</b>。小麦・ニンジン・ネザーウォート等は {@code age} が文字どおり成熟度で、
 * {@code age == maximumAge} が完熟＝収穫適期になる。しかしサトウキビ・コンブ・サボテン・ねじれツタ・
 * 泣きツタ・光ツタは {@code age} が<b>「次の1段を上（下）へ伸ばすまでのカウンタ」</b>で、
 * {@code maximumAge}(=15 や 25)に達した瞬間に新しい段を生やして {@code age} が 0 に戻る。竹の
 * {@code age} は太さ(0/1)にすぎない。つまり<b>これらは「収穫できる状態＝age が最大」ではない</b>ので、
 * {@code Ageable} を実装するだけで未成熟扱いにすると、収穫できるのに永久に未成熟と判定される。
 *
 * <p>実際にこれで壊れていたのが農業EXP: {@code NativeSkillExperienceListener#grantGathering} の
 * 成熟ガードが {@code instanceof Ageable} で入口を弾いていたため、サトウキビ/コンブ/竹/ねじれツタ/
 * 泣きツタ/光ツタは <b>農業EXPも破壊時バニラEXP({@code break-vanilla-exp})も常に0</b>だった。
 *
 * <p>そのためガードは<b>ホワイトリスト方式</b>にしてある。未知のブロックは「成熟の概念なし」＝
 * 常に収穫可能として素通しする（fail-open）。逆向き（除外リスト方式）にすると、Paper が新しい
 * {@code Ageable} ブロックを追加するたびに<b>無言でEXP0</b>という同じ事故が再発するため。
 *
 * <p>Paper 1.21.11 で {@code Ageable} を実装する Material は22種で、内訳は次のとおり。
 * <ul>
 *   <li><b>成熟する（ここでガードする）:</b> WHEAT / CARROTS / POTATOES / BEETROOTS / NETHER_WART /
 *       COCOA / SWEET_BERRY_BUSH / TORCHFLOWER_CROP / PITCHER_CROP / MELON_STEM / PUMPKIN_STEM</li>
 *   <li><b>age が周回する・成熟の概念が無い（ガードしない）:</b> SUGAR_CANE / KELP / CACTUS / BAMBOO /
 *       TWISTING_VINES / WEEPING_VINES / CAVE_VINES / CHORUS_FLOWER / MANGROVE_PROPAGULE /
 *       FROSTED_ICE / FIRE</li>
 * </ul>
 * （列挙は paper-api の {@code Material} が持つブロックデータクラスを実際に走査して確認したもの。
 * Paper を上げたら再確認すること。）
 *
 * <p>Bukkit非依存（{@link Material} の列挙と {@link Ageable} の読み取りだけ）なので、実行中サーバ無しの
 * 単体テストで直接検証できる。
 */
public final class CropMaturity {

    /**
     * 成熟してからでないと収穫できない作物。ここに載っているものだけが「未成熟なら収穫扱いしない」
     * ガードの対象になる。
     *
     * <p>MELON_STEM/PUMPKIN_STEM は現在どのEXP表にも載っていないが、age が成熟度である点は同じなので
     * 分類として入れてある（表に載った時点で正しく振る舞う）。
     */
    private static final Set<Material> MATURITY_GATED = Collections.unmodifiableSet(EnumSet.of(
            Material.WHEAT,
            Material.CARROTS,
            Material.POTATOES,
            Material.BEETROOTS,
            Material.NETHER_WART,
            Material.COCOA,
            Material.SWEET_BERRY_BUSH,
            Material.TORCHFLOWER_CROP,
            Material.PITCHER_CROP,
            Material.MELON_STEM,
            Material.PUMPKIN_STEM));

    private CropMaturity() {
    }

    /** {@code material} が「成熟しないと収穫できない作物」か。 */
    public static boolean isMaturityGated(Material material) {
        return material != null && MATURITY_GATED.contains(material);
    }

    /** 成熟ガード対象の作物Material一覧（読み取り専用）。 */
    public static Set<Material> maturityGatedCrops() {
        return MATURITY_GATED;
    }

    /**
     * {@code block} が「成熟ガード対象の作物で、かつまだ完熟していない」＝収穫扱いしてはいけない状態か。
     *
     * <p>成熟の概念を持たない植物（サトウキビ等）と作物以外のブロックでは<b>常に {@code false}</b>
     * （＝収穫扱いを妨げない）。{@code null} も {@code false}。
     */
    public static boolean isImmatureCrop(Block block) {
        if (block == null || !isMaturityGated(block.getType())) {
            return false;
        }
        return block.getBlockData() instanceof Ageable ageable
                && ageable.getAge() < ageable.getMaximumAge();
    }
}
