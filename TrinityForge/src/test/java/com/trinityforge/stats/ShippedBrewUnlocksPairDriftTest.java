package com.trinityforge.stats;

import com.trinityforge.testsupport.KnownCustomItemIds;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code progression/crafting-features.yml} の {@code brew-unlocks} が
 * <b>実際に登録され、かつ素材IDが実行時に一致しうる</b>ことを固定する drift ガード
 * (2026-07-31 D10 レビュー指摘#2 / 指摘#7)。
 *
 * <h2>なぜテストで縛るのか</h2>
 * <ul>
 *   <li><b>重複した組</b>は「壊れて見えない」形で効かなくなる: Paper の customMixes も
 *       {@code BrewUnlockListener} も<b>先に一致した1件</b>で確定するので、yml で後ろに書いた側
 *       (実際に起きたのは Lv80 の上位段 amplifier 1)が<b>絶対に出ない</b>。</li>
 *   <li><b>バニラ衝突する組</b>は起動時に登録がスキップされる(登録するとそのバニラレシピが
 *       サーバ全体で作れなくなるため)。スキップされた組は醸造が始まらない。</li>
 *   <li><b>解決できない素材ID</b>は最悪の形で死ぬ: mix は登録されるので
 *       「素材スロットに入るのに永久に一致しない」「レシピ帳には出るのに永久に作れない」になり、
 *       ログにも出ない(このリポジトリの既知の無言死クラス)。指摘#7 の指摘どおり、
 *       {@code vanillaCollision} だけを見るテストはこの状態を「登録可能」と表明してしまう。</li>
 * </ul>
 * どれも起動ログを誰も見ないと運用され続けるので、出荷 config の側でビルド時に落とす。
 *
 * <p>ベースは {@code THICK} / {@code MUNDANE} のような「バニラが出発点にしないベース」で
 * 段を分けるのが正しい形 — 素材が同じでもベースが違えば別の組になる。
 */
class ShippedBrewUnlocksPairDriftTest {

    /** 段の分け方(下位段と上位段でベースを変える)を明示的に固定する。 */
    private static final Map<String, String> EXPECTED_TIER_BASES = Map.of(
            "healthboost-haste", "THICK",
            "healthboost-haste-2", "MUNDANE");

    @Test
    void noTwoGroupsDeclareTheSameBaseIngredientPair() {
        Map<String, List<String>> byPair = new LinkedHashMap<>();
        forEachPotion((groupId, base, ingredient) ->
                byPair.computeIfAbsent(BrewRecipeSupport.pairKey(base, ingredient), k -> new ArrayList<>())
                        .add(groupId));

        assertTrue(byPair.size() >= 15, "brew-unlocks の読み取りに失敗している (組が " + byPair.size() + " 件)");
        List<String> duplicated = byPair.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " → " + e.getValue())
                .toList();

        assertEquals(List.of(), duplicated,
                "同じ (base, ingredient) を複数の spec が宣言している。先に一致した1件しか成立しないので、"
                        + "残りのグループのポーションは永久に作れない。段を分けたいならベースを変える");
    }

    @Test
    void everyShippedPairIsActuallyRegisterable() {
        // 登録から外れる組(バニラ衝突・素材名の綴り間違い)は「醸造が始まらない」か
        // 「バニラのレシピとして成立する」だけで、TF 独自の効果は永久に出ない。
        List<String> unregisterable = new ArrayList<>();
        forEachPotion((groupId, base, ingredient) -> {
            String collision = BrewPotionMixRegistrar.vanillaCollision(base, ingredient);
            if (collision != null) {
                unregisterable.add(groupId + ": " + base + " + " + ingredient + " — " + collision);
            }
        });

        assertEquals(List.of(), unregisterable,
                "出荷 config にバニラ衝突する組が残っている(起動時に登録がスキップされる)");
    }

    /**
     * 指摘#7: 登録できることだけでなく<b>素材IDが実行時に一致しうる</b>ことまで見る。
     * ここを見ないと「mix は登録されるのに素材が上段に入らない」= 無言死を通してしまう。
     */
    @Test
    void everyShippedIngredientIdCanActuallyResolveAtRuntime() {
        KnownCustomItemIds.Result knownResult = KnownCustomItemIds.load();
        Set<String> knownCustomIds = knownResult.ids();
        assertTrue(knownCustomIds.size() >= 100,
                "custom アイテムIDの一覧を読めていない(パスが壊れている?): " + knownCustomIds.size() + " 件"
                        + " / モード=" + knownResult.describe());

        List<String> unresolvable = new ArrayList<>();
        forEachPotion((groupId, base, ingredient) -> {
            String where = groupId + ": " + base + " + " + ingredient;
            if (BrewRecipeSupport.isCustomKey(ingredient)) {
                String id = BrewRecipeSupport.customId(ingredient);
                if (id == null) {
                    unresolvable.add(where + " — custom: の後ろが空");
                } else if (!knownCustomIds.contains(id)) {
                    // 大小違いは実行時に別物(BrewRecipeSupport#matchesIngredient は equals 比較)。
                    String hint = knownCustomIds.stream()
                            .filter(known -> known.equalsIgnoreCase(id))
                            .findFirst()
                            .map(known -> " — 大小違いの候補あり: '" + known + "'")
                            .orElse(" — どのレジストリにも存在しない");
                    unresolvable.add(where + hint);
                }
            } else if (Material.matchMaterial(ingredient.trim()) == null) {
                unresolvable.add(where + " — 不明なバニラ材質");
            }
        });

        assertEquals(List.of(), unresolvable,
                "醸造素材のIDが解決できない。mix は登録されるので症状は「素材スロットに入るのに"
                        + "永久に一致しない/レシピ帳に出るのに作れない」= 無言死になる");
    }

    @Test
    void healthBoostTiersAreSeparatedByBaseNotByDuplicatingThePair() {
        Map<String, Set<String>> basesByGroup = new LinkedHashMap<>();
        forEachPotion((groupId, base, ingredient) -> basesByGroup
                .computeIfAbsent(groupId, k -> new LinkedHashSet<>())
                .add(base.trim().toUpperCase(Locale.ROOT)));

        EXPECTED_TIER_BASES.forEach((groupId, expectedBase) -> assertEquals(Set.of(expectedBase),
                basesByGroup.get(groupId),
                groupId + " は " + expectedBase + " ベースで段を表す (両段が同じベースだと組が重複する)"));
    }

    private interface PotionVisitor {
        void accept(String groupId, String base, String ingredient);
    }

    private static void forEachPotion(PotionVisitor visitor) {
        File file = new File("src/main/resources/progression/crafting-features.yml");
        assertTrue(file.isFile(), "出荷 yml が見つからない: " + file.getAbsolutePath());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("brew-unlocks");
        assertNotNull(root, "brew-unlocks セクションが無い");
        for (String groupId : root.getKeys(false)) {
            ConfigurationSection group = root.getConfigurationSection(groupId);
            if (group == null) {
                continue;
            }
            for (Map<?, ?> raw : group.getMapList("potions")) {
                Object base = raw.get("base");
                Object ingredient = raw.get("ingredient");
                visitor.accept(groupId,
                        base == null ? "AWKWARD" : String.valueOf(base),
                        ingredient == null ? "" : String.valueOf(ingredient));
            }
        }
    }
}
