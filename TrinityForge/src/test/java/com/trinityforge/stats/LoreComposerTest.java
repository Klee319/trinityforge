package com.trinityforge.stats;

import com.trinityforge.config.domains.LoreConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoreComposerTest {

    private final LoreComposer composer = new LoreComposer();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private static StatDisplaySpec spec(String key, String name, LoreValueFormat fmt,
                                        int decimals, int order, boolean hideZero) {
        return new StatDisplaySpec(key, name, "", fmt, decimals, order, true, hideZero, "",
                StatCategoryInference.infer(key));
    }

    private static List<Component> flatCompose(Map<String, Double> stats,
                                               Map<String, StatDisplaySpec> table, LoreLayout layout) {
        return new LoreComposer().compose(stats, table, layout);
    }

    /** Index of first stat line (after optional section separator; width now adapts to content). */
    private static int firstStatLine(List<Component> lore) {
        for (int i = 0; i < lore.size(); i++) {
            String text = PLAIN.serialize(lore.get(i));
            if (!text.matches("=+") && !text.isBlank()) {
                return i;
            }
        }
        return 0;
    }

    @Test
    void rendersVisibleStatsInOrder() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put("crit-chance", 0.15);
        stats.put("flat-bonus-damage", 4.0);
        Map<String, StatDisplaySpec> table = Map.of(
                "crit-chance", spec("crit-chance", "Crit Chance", LoreValueFormat.PERCENT, 0, 30, true),
                "flat-bonus-damage", spec("flat-bonus-damage", "Bonus Damage", LoreValueFormat.FLAT, 1, 10, true));

        List<Component> lore = flatCompose(stats, table, LoreLayout.defaults());

        assertEquals(3, lore.size()); // separator + 2 stats
        assertEquals("Bonus Damage：+4.0", PLAIN.serialize(lore.get(firstStatLine(lore))));
        assertEquals("Crit Chance：+15%", PLAIN.serialize(lore.get(firstStatLine(lore) + 1)));
    }

    @Test
    void skipsStatsWithoutDisplaySpec() {
        Map<String, Double> stats = Map.of("internal-only", 5.0);
        assertTrue(flatCompose(stats, Map.of(), LoreLayout.defaults()).isEmpty());
    }

    @Test
    void hidesZeroWhenConfigured() {
        Map<String, Double> stats = Map.of("crit-chance", 0.0);
        Map<String, StatDisplaySpec> table = Map.of(
                "crit-chance", spec("crit-chance", "Crit Chance", LoreValueFormat.PERCENT, 0, 30, true));
        assertTrue(flatCompose(stats, table, LoreLayout.defaults()).isEmpty());
    }

    @Test
    void keepsZeroWhenNotHidden() {
        Map<String, Double> stats = Map.of("crit-chance", 0.0);
        Map<String, StatDisplaySpec> table = Map.of(
                "crit-chance", spec("crit-chance", "Crit Chance", LoreValueFormat.PERCENT, 0, 30, false));
        assertEquals(2, flatCompose(stats, table, LoreLayout.defaults()).size()); // sep + line
    }

    @Test
    void negativeValueUsesNegativeColorDistinctFromPositive() {
        Map<String, StatDisplaySpec> table = Map.of(
                "crit-chance", spec("crit-chance", "Crit Chance", LoreValueFormat.PERCENT, 0, 30, true));

        String positiveJson = GSON.serialize(
                flatCompose(Map.of("crit-chance", 0.15), table, LoreLayout.defaults()).get(firstStatLine(
                        flatCompose(Map.of("crit-chance", 0.15), table, LoreLayout.defaults()))));
        String negativeJson = GSON.serialize(
                flatCompose(Map.of("crit-chance", -0.15), table, LoreLayout.defaults()).get(firstStatLine(
                        flatCompose(Map.of("crit-chance", -0.15), table, LoreLayout.defaults()))));

        assertTrue(positiveJson.contains("white"), positiveJson);
        assertTrue(negativeJson.contains("red"), negativeJson);
        assertNotEquals(positiveJson, negativeJson);
    }

    @Test
    void hidesValueWithinEpsilonWhenHideWhenZero() {
        Map<String, Double> stats = Map.of("crit-chance", 5.0e-10); // |v| < EPSILON (1e-9)
        Map<String, StatDisplaySpec> table = Map.of(
                "crit-chance", spec("crit-chance", "Crit Chance", LoreValueFormat.PERCENT, 0, 30, true));
        assertTrue(flatCompose(stats, table, LoreLayout.defaults()).isEmpty());
    }

    @Test
    void breaksOrderTieAlphabeticallyByStatKey() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put("z-stat", 1.0);
        stats.put("a-stat", 2.0);
        Map<String, StatDisplaySpec> table = Map.of(
                "z-stat", spec("z-stat", "Zed", LoreValueFormat.FLAT, 0, 50, true),
                "a-stat", spec("a-stat", "Alpha", LoreValueFormat.FLAT, 0, 50, true)); // same order 50

        List<Component> lore = flatCompose(stats, table, LoreLayout.defaults());

        assertEquals(3, lore.size());
        assertTrue(PLAIN.serialize(lore.get(firstStatLine(lore))).contains("Alpha"));
        assertTrue(PLAIN.serialize(lore.get(firstStatLine(lore) + 1)).contains("Zed"));
    }

    @Test
    void matchesSnakeCaseStatAgainstKebabCaseDisplayTableEntry() {
        // item-stats.yml (via DerivedItemStats) now emits canonical (snake_case) stat keys, while lore.yml
        // is still authored in kebab-case; the two must match via StatKeys.canonical (gap I1).
        Map<String, Double> stats = Map.of("crit_chance", 0.15);
        Map<String, StatDisplaySpec> table = Map.of(
                "crit-chance", spec("crit-chance", "Crit Chance", LoreValueFormat.PERCENT, 0, 30, true));

        List<Component> lore = flatCompose(stats, table, LoreLayout.defaults());

        assertEquals(2, lore.size());
        assertEquals("Crit Chance：+15%", PLAIN.serialize(lore.get(firstStatLine(lore))));
    }

    @Test
    void hidesWhenRoundedDisplayValueIsZeroEvenIfRawValueIsNotExactlyZero() {
        // A raw value that rounds to "+0%" at the configured decimals must be hidden, not just a
        // near-machine-epsilon raw value (item 7).
        Map<String, Double> stats = Map.of("crit-chance", 0.001); // renders "+0%" at decimals=0
        Map<String, StatDisplaySpec> table = Map.of(
                "crit-chance", spec("crit-chance", "Crit Chance", LoreValueFormat.PERCENT, 0, 30, true));
        assertTrue(flatCompose(stats, table, LoreLayout.defaults()).isEmpty());
    }

    @Test
    void orderWithinCategoryControlsDisplaySequence() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put("crit-chance", 0.1);
        stats.put("attack-power", 5.0);
        Map<String, StatDisplaySpec> table = Map.of(
                "crit-chance", new StatDisplaySpec("crit-chance", "Crit", "", LoreValueFormat.PERCENT,
                        0, 20, true, true, "", StatCategory.ATTACK),
                "attack-power", new StatDisplaySpec("attack-power", "ATK", "", LoreValueFormat.FLAT,
                        1, 1, true, true, "", StatCategory.ATTACK));
        List<Component> lore = flatCompose(stats, table, LoreLayout.defaults());
        assertEquals("ATK：+5.0", PLAIN.serialize(lore.get(firstStatLine(lore))));
        assertEquals("Crit：+10%", PLAIN.serialize(lore.get(firstStatLine(lore) + 1)));
    }

    @Test
    void forceShowKeysBypassHideWhenZero() {
        Map<String, StatDisplaySpec> table = Map.of(
                "attack-power", new StatDisplaySpec("attack-power", "ATK", "", LoreValueFormat.FLAT,
                        1, 1, true, true, "", StatCategory.ATTACK));
        LoreComposeRequest request = new LoreComposeRequest(
                Map.of("attack-power", 0.0), Map.of(), "", 0, null, null, "", 0,
                Set.of("attack-power"));
        List<Component> lore = composer.compose(request, table, LoreLayout.defaults());
        assertTrue(lore.stream().anyMatch(c -> PLAIN.serialize(c).contains("ATK")));
    }

    @Test
    void wrapsHeaderAndFooterAroundStats() {
        LoreLayout layout = new LoreLayout(List.of("<gray>Stats"), List.of("<dark_gray>Bound"),
                LoreLayout.defaults().lineTemplate(), "green", "red");
        Map<String, Double> stats = Map.of("crit-chance", 0.15);
        Map<String, StatDisplaySpec> table = Map.of(
                "crit-chance", spec("crit-chance", "Crit Chance", LoreValueFormat.PERCENT, 0, 30, true));

        List<Component> lore = flatCompose(stats, table, layout);

        assertEquals(4, lore.size());
        assertEquals("Stats", PLAIN.serialize(lore.get(0)));
        assertEquals("Bound", PLAIN.serialize(lore.get(lore.size() - 1)));
    }

    @Test
    void showsUseRequirementAtLevelZeroWhenSkillPresent() {
        LoreComposeRequest request = new LoreComposeRequest(
                Map.of(), Map.of(), "並", 12, null, null, "軽量武器", 0);
        List<Component> lore = composer.compose(request, Map.of(), LoreLayout.defaults());
        String joined = lore.stream().map(PLAIN::serialize).reduce("", (a, b) -> a + "\n" + b);
        // 品質行は quality-tiers.yml の tier 名を表示する (固定文言【品質】ではない)。
        assertTrue(joined.contains("【並】:Score→12"), joined);
        assertTrue(joined.contains("使用可能レベル: 0"), joined);
        assertTrue(joined.contains("軽量武器"), joined);
    }

    @Test
    void useRequirementLineFollowsConfiguredBindTemplate() {
        // bind.use-requirement-line is admin-editable (previously hardcoded verbatim in
        // LoreComposer; the parsed config value was silently ignored).
        LoreConfig.BindLore customBind = new LoreConfig.BindLore(
                true, "<gray>所有者: <white><owner></white></gray>",
                true, "<yellow>Lv<level> required (<skill>)</yellow>");
        LoreComposeRequest request = new LoreComposeRequest(
                Map.of(), Map.of(), "並", 12, null, null, "軽量武器", 7);

        List<Component> lore = composer.compose(request, Map.of(), LoreLayout.defaults(), customBind);

        String joined = lore.stream().map(PLAIN::serialize).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joined.contains("Lv7 required (軽量武器)"), joined);
    }

    @Test
    void useRequirementLineHiddenWhenBindShowUseRequirementIsFalse() {
        LoreConfig.BindLore hiddenBind = new LoreConfig.BindLore(
                true, "<gray>所有者: <white><owner></white></gray>",
                false, "<yellow>Lv<level> required (<skill>)</yellow>");
        LoreComposeRequest request = new LoreComposeRequest(
                Map.of(), Map.of(), "並", 12, null, null, "軽量武器", 7);

        List<Component> lore = composer.compose(request, Map.of(), LoreLayout.defaults(), hiddenBind);

        String joined = lore.stream().map(PLAIN::serialize).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(!joined.contains("軽量武器"), joined);
    }

    @Test
    void threeArgOverloadDefaultsToBindLoreDefaultsAndMatchesLegacyHardcodedWording() {
        // Regression guard: the 3-arg overload must render byte-for-byte what the old hardcoded
        // string produced, so admins who never touch stats/lore.yml see no change.
        LoreComposeRequest request = new LoreComposeRequest(
                Map.of(), Map.of(), "並", 12, null, null, "軽量武器", 7);

        List<Component> lore = composer.compose(request, Map.of(), LoreLayout.defaults());

        String joined = lore.stream().map(PLAIN::serialize).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joined.contains("使用可能レベル: 7"), joined);
        assertTrue(joined.contains("軽量武器"), joined);
    }

    @Test
    void scoreLineFollowsConfiguredTemplate() {
        // score-line-template のプレースホルダ <tier>/<tier-name>/<score> が解決される。
        LoreLayout layout = new LoreLayout(List.of(), List.of(),
                LoreLayout.defaults().lineTemplate(), "<gray>品質<tier-name> (<score>pt) <tier>",
                "green", "red", LoreColorRules.defaults(), List.of());
        LoreComposeRequest request = new LoreComposeRequest(
                Map.of(), Map.of(), "上質", 8, null, null, "", 0,
                Set.of(), Set.of(), Map.of(), "aqua");
        List<Component> lore = composer.compose(request, Map.of(), layout);
        assertTrue(lore.stream().anyMatch(c ->
                        PLAIN.serialize(c).equals("品質上質 (8pt) 【上質】")),
                lore.stream().map(PLAIN::serialize).toList().toString());
    }

    @Test
    void defaultScoreLineTemplateMatchesLegacyFormat() {
        // 既定テンプレートは従来ハードコードの「【tier】:Score→N」と同一表示。
        LoreComposeRequest request = new LoreComposeRequest(
                Map.of(), Map.of(), "並", 12, null, null, "", 0);
        List<Component> lore = composer.compose(request, Map.of(), LoreLayout.defaults());
        assertTrue(lore.stream().anyMatch(c -> PLAIN.serialize(c).equals("【並】:Score→12")),
                lore.stream().map(PLAIN::serialize).toList().toString());
    }

    @Test
    void qualityTierColorIsAppliedToTierLabel() {
        LoreComposeRequest request = new LoreComposeRequest(
                Map.of(), Map.of(), "上質", 8, null, null, "", 0,
                Set.of(), Set.of(), Map.of(), "aqua");
        List<Component> lore = composer.compose(request, Map.of(), LoreLayout.defaults());
        Component tierLine = lore.stream()
                .filter(c -> PLAIN.serialize(c).contains("【上質】"))
                .findFirst().orElseThrow();
        String json = GSON.serialize(tierLine);
        assertTrue(json.contains("aqua"), json);
    }

    @Test
    void durabilityUsesConfiguredOtherSectionAndShowsMaxOnly() {
        StatDisplaySpec durability = new StatDisplaySpec(
                "durability", "耐久値", "", LoreValueFormat.INTEGER, 0, 20,
                false, true, "", StatCategory.OTHER);
        StatDisplaySpec cooldown = new StatDisplaySpec(
                "item-cooldown", "CT", "", LoreValueFormat.FLAT, 0, 10,
                true, true, "s", StatCategory.OTHER);
        LoreComposeRequest request = new LoreComposeRequest(
                Map.of("durability", 100.0, "item-cooldown", 2.0),
                Map.of(), "", 0, 40, 100, "", 0);

        List<Component> lore = composer.compose(request,
                Map.of("durability", durability, "item-cooldown", cooldown), LoreLayout.defaults());

        assertEquals(3, lore.size());
        assertEquals("CT：+2s", PLAIN.serialize(lore.get(1)));
        assertEquals("耐久値：100", PLAIN.serialize(lore.get(2)));
        assertTrue(lore.stream().anyMatch(c -> PLAIN.serialize(c).contains("耐久値：100")));
        assertTrue(lore.stream().noneMatch(c -> PLAIN.serialize(c).contains("40/100")));
    }

    @Test
    void fixedStatValueUsesWhiteColor() {
        Map<String, StatDisplaySpec> table = Map.of(
                "crit-chance", spec("crit-chance", "Crit Chance", LoreValueFormat.PERCENT, 0, 30, true));
        LoreComposeRequest request = new LoreComposeRequest(
                Map.of("crit-chance", 0.15),
                Map.of("crit-chance", StatSource.FIXED),
                "", 0, null, null, "", 0);
        String json = GSON.serialize(composer.compose(request, table, LoreLayout.defaults())
                .get(firstStatLine(composer.compose(request, table, LoreLayout.defaults()))));
        assertTrue(json.contains("white"), json);
    }

    @Test
    void multiplierLinesRenderWithXSignAndNoUnit() {
        // 乗算モード: 加算行の直後に x1.2 形式(符号=x, 単位なし)で描画され、レイヤ順は
        // multiplier-layers の並び順。レイヤ名は行末に [名前] で添えられる。
        Map<String, StatDisplaySpec> table = Map.of(
                "attack-power", new StatDisplaySpec("attack-power", "攻撃力", "", LoreValueFormat.FLAT,
                        1, 1, true, true, "ダメージ", StatCategory.ATTACK));
        LoreLayout layout = new LoreLayout(List.of(), List.of(),
                LoreLayout.defaults().lineTemplate(), "green", "red",
                LoreColorRules.defaults(),
                List.of(new LoreLayout.MultiplierLayer("burst", "バースト"),
                        new LoreLayout.MultiplierLayer("aura", "オーラ")));
        LoreComposeRequest request = new LoreComposeRequest(
                Map.of("attack-power", 5.0), Map.of(), "", 0, null, null, "", 0,
                Set.of(), Set.of(),
                Map.of("aura", Map.of("attack-power", 1.5),
                        "burst", Map.of("attack-power", 1.2)));
        List<Component> lore = composer.compose(request, table, layout);
        List<String> texts = lore.stream().map(PLAIN::serialize).toList();
        int base = firstStatLine(lore);
        assertEquals("攻撃力：+5.0ダメージ", texts.get(base));
        assertEquals("攻撃力：x1.2 [バースト]", texts.get(base + 1)); // layer順: burst -> aura
        assertEquals("攻撃力：x1.5 [オーラ]", texts.get(base + 2));
    }

    @Test
    void multiplierOnlyStatStillRendersItsSection() {
        // 加算値ゼロ(非表示)でも乗算だけあるステは行が出る。
        Map<String, StatDisplaySpec> table = Map.of(
                "attack-power", new StatDisplaySpec("attack-power", "ATK", "", LoreValueFormat.FLAT,
                        1, 1, true, true, "", StatCategory.ATTACK));
        LoreLayout layout = new LoreLayout(List.of(), List.of(),
                LoreLayout.defaults().lineTemplate(), "green", "red",
                LoreColorRules.defaults(),
                List.of(new LoreLayout.MultiplierLayer("burst", "")));
        LoreComposeRequest request = new LoreComposeRequest(
                Map.of(), Map.of(), "", 0, null, null, "", 0,
                Set.of(), Set.of(), Map.of("burst", Map.of("attack-power", 2.0)));
        List<Component> lore = composer.compose(request, table, layout);
        // レイヤ名未設定はid("burst")にフォールバックして表示される
        assertTrue(lore.stream().anyMatch(c -> PLAIN.serialize(c).equals("ATK：x2 [burst]")),
                lore.stream().map(PLAIN::serialize).toList().toString());
    }

    @Test
    void chanceKeysUseAdvancedChanceColor() {
        Map<String, StatDisplaySpec> table = Map.of(
                "crit-chance", spec("crit-chance", "Crit", LoreValueFormat.PERCENT, 0, 30, true));
        LoreLayout layout = new LoreLayout(List.of(), List.of(),
                LoreLayout.defaults().lineTemplate(), "green", "red",
                new LoreColorRules("white", "red", "green", "red", "aqua", "", "", ""),
                List.of());
        LoreComposeRequest request = new LoreComposeRequest(
                Map.of("crit-chance", 0.15), Map.of("crit-chance", StatSource.FIXED),
                "", 0, null, null, "", 0,
                Set.of(), Set.of("crit-chance"), Map.of());
        String json = GSON.serialize(composer.compose(request, table, layout)
                .get(firstStatLine(composer.compose(request, table, layout))));
        assertTrue(json.contains("aqua"), json);
    }

    @Test
    void separatorWidthAdaptsToWidestLine() {
        Map<String, Double> stats = Map.of("attack-power", 5.0);
        Map<String, StatDisplaySpec> table = Map.of(
                "attack-power", new StatDisplaySpec("attack-power", "とても長いステータス名前テスト", "",
                        LoreValueFormat.FLAT, 1, 1, true, true, "", StatCategory.ATTACK));
        List<Component> lore = flatCompose(stats, table, LoreLayout.defaults());
        String sep = PLAIN.serialize(lore.get(0));
        assertTrue(sep.matches("=+"), sep);
        // 最長行に合わせて既定の最小(8)より長くなる
        assertTrue(sep.length() > 8, "separator should adapt to content width: " + sep.length());
        // ただしピクセル幅換算で最長行(CJK15文字*9px + ：9px + "+5.0"20px = 164px)を超えない
        // ('='は6px。超えるとセパレータ自身がツールチップ横幅を押し広げてしまう)
        assertTrue(sep.length() * 6 <= 164,
                "separator must not widen the tooltip: " + sep.length() + " chars");
    }

    @Test
    void randomStatValueUsesGreenColor() {
        Map<String, StatDisplaySpec> table = Map.of(
                "crit-chance", spec("crit-chance", "Crit Chance", LoreValueFormat.PERCENT, 0, 30, true));
        LoreComposeRequest request = new LoreComposeRequest(
                Map.of("crit-chance", 0.15),
                Map.of("crit-chance", StatSource.RANDOM),
                "", 0, null, null, "", 0);
        String json = GSON.serialize(composer.compose(request, table, LoreLayout.defaults())
                .get(firstStatLine(composer.compose(request, table, LoreLayout.defaults()))));
        assertTrue(json.contains("green"), json);
    }
}
