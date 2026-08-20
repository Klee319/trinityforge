package com.trinityforge.config.domains;

import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code stats/food-gimmick.yml}: 9倍圧縮(_1x)の食料が全部 {@code custom-foods} に載っていること
 * (2026-08-19 / W-131 回帰)。
 *
 * <h2>なぜ必要か(実バグ)</h2>
 * {@code unregistered-custom-food-ban} は「custom-foods に載っていないカスタムID付き食料は素材扱いで
 * <b>食べられない</b>」という規則そのものが判定基準になっている。ここに載せ忘れると、
 * 81倍/729倍だけでなく<b>ふつうに食べたい9倍圧縮まで一律で食べられなくなる</b>のに、
 * 症状はアクションバーの一行だけで、ログにも警告が出ない(実際に圧縮ニンジン等14件が
 * 落ちていた)。だから「載っていること」を機械で固定する。
 *
 * <p>ID の一覧は ArsPaper フォークの {@code materials.yml} を突き合わせて作ったものだが、
 * フォークのソースは {@code .gitignore} 除外でクリーンなクローンには存在しないため、
 * このテストからは参照できない(参照するとフォーク不在の環境で必ず落ちる)。そのため
 * <b>期待値は固定リスト</b>にしてある —— 圧縮食料を新設したらここにも足すこと。
 */
class ShippedCompressedFoodRegistrationTest {

    /** materials.yml 側で base_material が食用の 9倍圧縮アイテム(=食べられるべきもの)。 */
    private static final List<String> EDIBLE_1X = List.of(
            "compressed_bread_1x", "compressed_cooked_beef_1x", "baked_potato_1x",
            "cooked_porkchop_1x", "cooked_mutton_1x", "cooked_chicken_1x", "cookie_1x",
            "pumpkin_pie_1x", "baked_salon_1x", "baked_cod_1x",
            "carrot_1x", "potato_1x", "beetroot_1x", "apple_1x",
            "sweet_berries_1x", "glow_berries_1x",
            "beef_1x", "porkchop_1x", "rabbit_1x", "chicken_1x", "mutton_1x",
            "cod_1x", "salmon_1x");

    /** 81倍/729倍は「素材」であって食料ではない(解凍して食べる)。ここに載ると事故。 */
    private static final List<String> MUST_STAY_UNREGISTERED = List.of(
            "carrot_2x", "carrot_3x", "potato_2x", "potato_3x", "beef_2x", "beef_3x",
            "apple_2x", "apple_3x");

    private static YamlConfiguration shipped() throws Exception {
        try (InputStream in = ShippedCompressedFoodRegistrationTest.class.getClassLoader()
                .getResourceAsStream("stats/food-gimmick.yml")) {
            assertNotNull(in, "出荷 stats/food-gimmick.yml が classpath に無い");
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void everyEdibleCompressedItemIsRegisteredAsCustomFood() throws Exception {
        YamlConfiguration yaml = shipped();
        for (String id : EDIBLE_1X) {
            assertTrue(yaml.contains("custom-foods." + id),
                    id + " が custom-foods に無い。unregistered-custom-food-ban が働いて"
                            + "このアイテムは【一切食べられなくなる】(症状はアクションバー1行だけ)");
            assertTrue(yaml.getInt("custom-foods." + id + ".food-level", 0) > 0,
                    id + " の food-level が 0 —— 食べても満腹度が回復しない");
        }
    }

    @Test
    void bulkCompressedItemsStayMaterialsNotFoods() throws Exception {
        YamlConfiguration yaml = shipped();
        for (String id : MUST_STAY_UNREGISTERED) {
            assertFalse(yaml.contains("custom-foods." + id),
                    id + " は 81倍/729倍のまとめ置き用。食料として登録すると、"
                            + "満腹度20のために729個ぶんを1個消し飛ばす事故になる");
        }
    }

    @Test
    void theBanItselfStaysEnabled() throws Exception {
        YamlConfiguration yaml = shipped();
        assertTrue(yaml.getBoolean("unregistered-custom-food-ban.enabled", false),
                "ban を切ると 729倍圧縮が素材のバニラ栄養値で食べられてしまう");
    }
}
