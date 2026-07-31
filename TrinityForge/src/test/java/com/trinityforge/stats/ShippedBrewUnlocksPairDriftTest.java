package com.trinityforge.stats;

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
 * 出荷 {@code progression/crafting-features.yml} の {@code brew-unlocks} に
 * <b>同じ {@code (base, ingredient)} の組が2つ以上無い</b>ことを固定する drift ガード
 * (2026-07-31 D10 レビュー指摘#2)。
 *
 * <h2>なぜテストで縛るのか</h2>
 * 重複した組は「壊れて見えない」形で効かなくなる: Paper の customMixes も
 * {@code BrewUnlockListener} も<b>先に一致した1件</b>で確定するので、yml で後ろに書いた側
 * (実際に起きたのは Lv80 の上位段 amplifier 1)が<b>絶対に出ない</b>。ランタイムは
 * {@code BrewPotionMixRegistrar} が要求レベルの高い側を残して WARNING を出すが、
 * 起動ログを誰も見ないと「片方のノードのポーションが永久に作れない」まま運用される。
 * 出荷 config の側で重複ゼロを保証しておけば、editor でうっかり増やした瞬間にビルドで落ちる。
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
