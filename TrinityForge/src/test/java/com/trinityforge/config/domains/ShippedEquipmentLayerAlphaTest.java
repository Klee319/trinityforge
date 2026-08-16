package com.trinityforge.config.domains;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * リソースパックの装備レイヤー PNG が<b>バニラと同じ「抜き」を持っている</b>ことを固定する。
 *
 * <p><b>これが守っている事故（2026-08-16 のユーザー報告）:</b>
 * 「インフィニティ等の防具を着るとヘルメットが頭全体を覆う」。
 * モデルは最初からバニラそのもの（{@code equipment/<asset>.json} はバニラの
 * {@code humanoid} / {@code humanoid_leggings} レイヤーを指すだけ）で、壊れていたのは
 * <b>テクスチャのアルファ</b>だった。生成スクリプトが 64x32 を全面 {@code alpha=255} で
 * 塗っていたため、バニラなら透明な部分まで埋まっていた。
 *
 * <p>バニラの装備レイヤーは 7 割前後が透明で、その透明部分が見た目を作っている:
 * <pre>
 *   humanoid/turtle_scute 10.5% / chainmail 19.0% / diamond 30.9% / netherite 34.1%  (不透明率)
 *   humanoid_leggings/diamond 13.7%
 * </pre>
 * 全面不透明にすると同じモデルでも
 * <b>ヘルメットは顔の開口が埋まった無地の箱</b>、<b>ブーツは腰から足先までの脚全体</b>、
 * <b>チェストプレートは腕まで全面ベタ塗り</b>になる。
 *
 * <p>ここでは特定のバニラ素材に依存しない形で不変条件を置く（テスト実行環境に
 * クライアント jar があるとは限らないので、バニラ PNG との直接比較はしない）。
 * しきい値はバニラ 8 種を実測した値に余裕を持たせたもの。
 */
class ShippedEquipmentLayerAlphaTest {

    private static final File PACK =
            new File("../resourcepack/trinityforge-items/assets/trinityforge/textures/entity/equipment");

    /** バニラ実測 10.5〜34.1%。全面塗り(100%)を確実に落としつつ、手描き版の余地を残す上限。 */
    private static final double MAX_OPAQUE_RATIO = 0.60;

    /** 頭の正面 UV 矩形。ここが全部埋まると「顔の無いヘルメット」になる。バニラ実測 22〜58px が透明。 */
    private static final int[] HEAD_FRONT = {8, 8, 8, 8};

    /** 脚の正面 UV 矩形。humanoid レイヤーではブーツしか描かないので上側は必ず空く。バニラ実測 6行以上。 */
    private static final int[] LEG_FRONT = {4, 20, 4, 12};
    private static final int LEG_FRONT_CLEAR_ROWS = 4;

    private static List<File> layerFiles(String layer) {
        File dir = new File(PACK, layer);
        File[] found = dir.listFiles((d, name) -> name.endsWith(".png"));
        return found == null ? List.of() : List.of(found);
    }

    private static BufferedImage read(File file) {
        try {
            BufferedImage img = ImageIO.read(file);
            assertTrue(img != null, file + " が PNG として読めない");
            return img;
        } catch (IOException e) {
            throw new AssertionError("failed to read " + file, e);
        }
    }

    private static int alpha(BufferedImage img, int x, int y) {
        return (img.getRGB(x, y) >>> 24) & 0xFF;
    }

    @Test
    @DisplayName("装備レイヤー PNG は 64x32 で、バニラ同様に大半が透明（全面塗りでない）")
    void everyLayerKeepsVanillaStyleTransparency() {
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (String layer : List.of("humanoid", "humanoid_leggings")) {
            for (File file : layerFiles(layer)) {
                checked++;
                BufferedImage img = read(file);
                if (img.getWidth() != 64 || img.getHeight() != 32) {
                    broken.add(layer + "/" + file.getName() + " が "
                            + img.getWidth() + "x" + img.getHeight() + "（64x32 でない）");
                    continue;
                }
                int opaque = 0;
                for (int y = 0; y < 32; y++) {
                    for (int x = 0; x < 64; x++) {
                        if (alpha(img, x, y) == 255) {
                            opaque++;
                        }
                    }
                }
                double ratio = opaque / (double) (64 * 32);
                if (ratio > MAX_OPAQUE_RATIO) {
                    broken.add(String.format("%s/%s の不透明率が %.1f%%（バニラは 10〜35%%）",
                            layer, file.getName(), ratio * 100));
                }
            }
        }
        assertTrue(checked > 0, "装備レイヤー PNG が 1 枚も見つからない。"
                + PACK.getPath() + " を見ているが、パスが変わるとこの検査は「全部OK」に化ける");
        assertEquals(List.of(), broken,
                "装備レイヤーの透明部分が失われている。バニラのモデルに描いても、"
                        + "アルファを埋めるとヘルメットが頭全体を覆い、ブーツが脚全体になる: " + broken);
    }

    @Test
    @DisplayName("頭の正面に透明が残っている（顔が埋まったヘルメットにならない）")
    void helmetKeepsTheFaceOpening() {
        List<String> broken = new ArrayList<>();
        for (File file : layerFiles("humanoid")) {
            BufferedImage img = read(file);
            if (img.getWidth() != 64 || img.getHeight() != 32) {
                continue; // サイズ違反は上のテストが報告する
            }
            int clear = 0;
            for (int y = HEAD_FRONT[1]; y < HEAD_FRONT[1] + HEAD_FRONT[3]; y++) {
                for (int x = HEAD_FRONT[0]; x < HEAD_FRONT[0] + HEAD_FRONT[2]; x++) {
                    if (alpha(img, x, y) == 0) {
                        clear++;
                    }
                }
            }
            if (clear < 8) {
                broken.add(file.getName() + " の顔の透明ピクセルが " + clear + "/64（バニラは 22 以上）");
            }
        }
        assertEquals(List.of(), broken,
                "頭の正面 UV が埋まっている = 着ると顔の無い箱をかぶった状態になる: " + broken);
    }

    @Test
    @DisplayName("脚の上側が透明（ブーツが腰までの脚全体にならない）")
    void bootsDoNotCoverTheWholeLeg() {
        List<String> broken = new ArrayList<>();
        for (File file : layerFiles("humanoid")) {
            BufferedImage img = read(file);
            if (img.getWidth() != 64 || img.getHeight() != 32) {
                continue;
            }
            int clearRows = 0;
            for (int ly = 0; ly < LEG_FRONT[3]; ly++) {
                boolean rowClear = true;
                for (int x = LEG_FRONT[0]; x < LEG_FRONT[0] + LEG_FRONT[2]; x++) {
                    if (alpha(img, x, LEG_FRONT[1] + ly) != 0) {
                        rowClear = false;
                        break;
                    }
                }
                if (!rowClear) {
                    break;
                }
                clearRows++;
            }
            if (clearRows < LEG_FRONT_CLEAR_ROWS) {
                broken.add(file.getName() + " の脚正面で透明な行が上から " + clearRows
                        + " 行しかない（バニラは 6 行以上）");
            }
        }
        assertEquals(List.of(), broken,
                "humanoid レイヤーで脚が上まで塗られている = ブーツが腰から足先までの"
                        + "脚全体として描かれる（humanoid レイヤーの脚はブーツ専用）: " + broken);
    }
}
