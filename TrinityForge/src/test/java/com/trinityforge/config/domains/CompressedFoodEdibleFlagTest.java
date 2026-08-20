package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 圧縮食料が食べられるための条件は<b>2箇所に分かれている</b>ので、その2つが食い違っていないことを固定する
 * (2026-08-19 W-131/W-149 の再発防止)。
 *
 * <ul>
 *   <li>ArsPaper の {@code materials.yml} の {@code edible: true}
 *       ── 立っていないと {@code CustomItemListener#onConsumeMaterial} が食事イベントごと
 *       キャンセルする(モーションだけ再生されアイテムも満腹度も動かない)。</li>
 *   <li>TF 本体の {@code stats/food-gimmick.yml} の {@code custom-foods}
 *       ── 登録が無いと {@code unregistered-custom-food-ban} が塞ぐ。</li>
 * </ul>
 *
 * <p><b>この2つは片方だけ直しても症状が変わらない。</b> 実際 W-131 → W-149 では TF 側だけを何度も
 * 直していて、Ars 側が無条件にキャンセルしていることに最後まで気づけなかった。だからここでは
 * 「両方に載っているか」を突き合わせる。
 *
 * <p><b>フォークが無いときは検査せずスキップする。</b> {@code fork-handoff/arspaper/fork} は
 * 外側リポジトリの {@code .gitignore} 除外なので、クリーンなクローンや新しい worktree には
 * 存在しない ── そこで落とすと「フォークを持っていない環境では常に赤」になる。
 * スキップ時のメッセージにその旨を書いてあるので、フォークを持っている環境で緑なら実効性がある。
 */
class CompressedFoodEdibleFlagTest {

    private static final File ARS_MATERIALS =
            new File("../fork-handoff/arspaper/fork/src/main/resources/materials.yml");
    private static final File TF_FOOD_GIMMICK =
            new File("src/main/resources/stats/food-gimmick.yml");

    @Test
    void arsEdibleFlagAndTrinityForgeCustomFoodsAgree() throws Exception {
        Assumptions.assumeTrue(ARS_MATERIALS.isFile(),
                "ArsPaper フォークがこのワークツリーに無いのでスキップ(.gitignore 除外)。"
                        + " フォークを持つ環境でのみ検査される: " + ARS_MATERIALS.getPath());

        YamlConfiguration ars = new YamlConfiguration();
        ars.load(ARS_MATERIALS);
        ConfigurationSection materials = ars.getConfigurationSection("materials");
        assertTrue(materials != null, "materials.yml に materials: セクションが必要");

        YamlConfiguration tf = new YamlConfiguration();
        tf.load(TF_FOOD_GIMMICK);
        ConfigurationSection customFoods = tf.getConfigurationSection("custom-foods");
        assertTrue(customFoods != null, "food-gimmick.yml に custom-foods: セクションが必要");
        Set<String> registered = new LinkedHashSet<>(customFoods.getKeys(false));

        Set<String> flaggedEdible = new LinkedHashSet<>();
        Set<String> allMaterialIds = new LinkedHashSet<>();
        for (String id : materials.getKeys(false)) {
            ConfigurationSection entry = materials.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            allMaterialIds.add(id);
            if (entry.getBoolean("edible", false)) {
                flaggedEdible.add(id);
            }
        }

        // (1) Ars が食用として通すのに TF に栄養値の登録が無い素材 → TF の禁止ギミックが塞ぐので食べられない。
        Set<String> edibleButUnregistered = new TreeSet<>(flaggedEdible);
        edibleButUnregistered.removeAll(registered);
        assertTrue(edibleButUnregistered.isEmpty(),
                "materials.yml で edible: true なのに TF の custom-foods に未登録の素材がある"
                        + "(unregistered-custom-food-ban に塞がれて食べられない): " + edibleButUnregistered);

        // (2) TF に栄養値を登録したのに Ars 側の旗が立っていない素材 → Ars が食事ごとキャンセルするので
        //     やはり食べられない。これが W-131/W-149 で実際に起きていた向き。
        Set<String> registeredButNotFlagged = new TreeSet<>(registered);
        registeredButNotFlagged.retainAll(allMaterialIds);
        registeredButNotFlagged.removeAll(flaggedEdible);
        assertTrue(registeredButNotFlagged.isEmpty(),
                "TF の custom-foods に登録済みなのに materials.yml で edible: true が立っていない素材がある"
                        + "(CustomItemListener#onConsumeMaterial に食事ごとキャンセルされて食べられない): "
                        + registeredButNotFlagged);
    }
}
