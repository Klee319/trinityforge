package com.trinityforge.config.domains;

import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.effects.DedicatedEffectGateIndex;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code recipe:} / {@code ritual:} ゲートの「チャンネル取り違え」と「無言の再混入」を検出するドリフト
 * テスト(2026-07-28)。
 *
 * <p><b>なぜ必要か</b> — この2つのゲートは ArsPaper 側の
 * {@code UnlockGate#hasRecipePermission} / {@code #hasRitualPermission} が<b>別々のマップ</b>を引く。
 * 儀式(items.yml {@code method: ritual})のアイテムを {@code recipe:} 側に置いても、儀式経路は
 * recipe マップを一切見ないので<b>ゲートは無言で常時解放になる</b>(逆も同じ)。実際 enchant_book_* 8件と
 * volcanic_sourcelink は {@code recipe:} 側に置かれていて何もゲートしていなかった。
 * この取り違えは実行時に例外もエラーも出ないため、ここで固定するしかない。
 *
 * <p>また TF は ArsPaper にコンパイル依存できず、ArsPaper の items.yml / materials.yml は TF の
 * テストクラスパスに存在しないので、「そのidが本当にArs側に存在するか」はビルド時には検証できない。
 * よって<b>期待集合を明示的に書き下し、完全一致を要求する</b>。ゲートを足す/消す/プレフィックスを変える
 * 変更は必ずこのテストを落とすので、そのとき初めて「実体はどっちの method か」を確認させられる。
 *
 * <p>関連: 起動時の {@code [PRG-02]} 警告
 * ({@code CatalogCraftGateListener#verifyRecipeGateIds})は Bukkit 実行時にしか働かず、しかも
 * ArsPaper の作業台レシピは TF の enable 時点では未登録なので、TF側だけで完結する保証にはならない。
 */
class RecipeRitualGateChannelDriftTest {

    private static final String[] SKILL_TREE_FILES = {
        "light_weapons.yml", "heavy_weapons.yml", "archery.yml", "light_armor.yml",
        "heavy_armor.yml", "ars_magic.yml", "mining.yml", "woodcutting.yml", "farming.yml",
        "enchanting.yml", "digging.yml", "smithing.yml", "alchemy.yml", "fishing.yml",
        "ars_smithing.yml", "power.yml"
    };

    /**
     * 全16ツリーに置いてよい {@code recipe:} ゲートidの全集合。
     *
     * <p>いずれも ArsPaper の {@code materials.yml} で {@code method: workbench} として定義された
     * 作業台レシピ = recipe チャンネルが正しい。TFカタログ品でもバニラレシピでもないため、TF単体では
     * 存在確認ができない(だからここに列挙する)。
     *
     * <p><b>ここに道具・防具のティア解放(stone_sword 等)を再び足さないこと。</b> 2026-07-28に
     * skilltree/smithing.yml 主軸A〜Dから41件を撤去した — 道具/防具側に既に使用可能レベルがあり、
     * クラフト側の二重ティアゲートは進行に何も足していなかった(ユーザー決定。撤去理由の詳細は
     * smithing.yml のヘッダコメント)。
     */
    private static final Set<String> EXPECTED_RECIPE_GATE_IDS = Set.of(
            "source_gem_block",
            "core_wood",
            // 2026-07-31 追加: 切削(digging.yml) の主軸E からダートコアを解放する。
            // コア4種のうちダートコアだけ解放先が無く、素材(圧縮土)が作れてもコアが作れなかった。
            // 2026-08-04: tf_core_dirt → core_ground へ改名(ArsPaper materials.yml の実IDに合わせた)。
            "core_ground",
            "core_jewelry",
            "core_vegetable",
            "core_meat",
            "compressed_bread_1x",
            "compressed_cooked_beef_1x");

    /**
     * {@code ritual:} チャンネルでなければ機能しないid(ArsPaper側で {@code method: ritual})。
     * {@code recipe:} 側に現れたら取り違え = 無言で常時解放。
     */
    private static final Set<String> RITUAL_ONLY_IDS = Set.of(
            "enchant_book_mana_regen_1", "enchant_book_mana_regen_2", "enchant_book_mana_regen_3",
            "enchant_book_mana_boost_1", "enchant_book_mana_boost_2", "enchant_book_mana_boost_3",
            "enchant_book_share", "enchant_book_soulbound",
            "volcanic_sourcelink",
            "waystone", "teleport_compass");

    private static Plugin fakePlugin(File dataFolder, Logger logger) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> logger;
            case "saveResource" -> null;
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static DedicatedEffectGateIndex loadIndex(File dataFolder) throws IOException {
        File dir = new File(dataFolder, SkillTreeConfig.DIR);
        Files.createDirectories(dir.toPath());
        for (String fileName : SKILL_TREE_FILES) {
            try (InputStream in = RecipeRitualGateChannelDriftTest.class.getClassLoader()
                    .getResourceAsStream(SkillTreeConfig.DIR + "/" + fileName)) {
                assertTrue(in != null, "bundled skilltree/" + fileName + " must be on the test classpath");
                Files.copy(in, new File(dir, fileName).toPath());
            }
        }
        SkillTreeConfig config = new SkillTreeConfig();
        Logger logger = Logger.getLogger("RecipeRitualGateChannelDriftTest-" + System.nanoTime());
        assertTrue(config.load(fakePlugin(dataFolder, logger)), "skill trees must load OK");
        java.util.Collection<SkillTree> trees = config.all().values();
        assertTrue(trees.size() == SKILL_TREE_FILES.length,
                "test setup sanity: expected " + SKILL_TREE_FILES.length + " trees, got " + trees.size());
        return DedicatedEffectGateIndex.build(trees);
    }

    @Test
    @DisplayName("recipe: ゲートidの集合が期待どおり(道具ティアゲートの再混入・id綴り間違いを検出)")
    void recipeGateIds_matchExpectedSetExactly(@TempDir File dataFolder) throws IOException {
        Set<String> actual = new TreeSet<>(loadIndex(dataFolder).recipeGatePerks().keySet());

        assertEquals(new TreeSet<>(EXPECTED_RECIPE_GATE_IDS), actual,
                "skilltree の recipe: ゲート集合が変わった。追加したidが本当に作業台レシピ"
                        + "(TFカタログ品 or ArsPaper materials.yml の method: workbench)として実在するかを"
                        + "確認したうえで EXPECTED_RECIPE_GATE_IDS を更新すること。"
                        + "儀式アイテムなら recipe: ではなく ritual: が正しい");
    }

    @Test
    @DisplayName("儀式(method: ritual)のidが recipe: チャンネルに紛れていない")
    void ritualOnlyIds_neverAppearOnRecipeChannel(@TempDir File dataFolder) throws IOException {
        DedicatedEffectGateIndex index = loadIndex(dataFolder);

        Set<String> misplaced = new TreeSet<>(index.recipeGatePerks().keySet());
        misplaced.retainAll(RITUAL_ONLY_IDS);

        assertTrue(misplaced.isEmpty(),
                "ArsPaper 側で method: ritual のidが recipe: ゲートに置かれている。ArsPaper の"
                        + " UnlockGate は儀式経路で recipe マップを見ないため、このゲートは無言で"
                        + "常時解放になる。ritual: へ直すこと: " + misplaced);
    }

    @Test
    @DisplayName("儀式ゲートidが ritual: チャンネル側に揃っている(recipe:へ戻す差し戻しを検出)")
    void ritualOnlyIds_allPresentOnRitualChannel(@TempDir File dataFolder) throws IOException {
        Set<String> actualRitual = loadIndex(dataFolder).ritualGatePerks().keySet();

        Set<String> missing = new TreeSet<>(RITUAL_ONLY_IDS);
        missing.removeAll(actualRitual);

        assertTrue(missing.isEmpty(),
                "ritual: ゲートとして配置されているはずのidがスキルツリーから消えた(または recipe: へ"
                        + "戻された)。フェイルオープンなので実行時は無警告で常時解放になる: " + missing);
    }
}
