package com.trinityforge.stats;

import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewUnlockGroup;
import io.papermc.paper.potion.PotionMix;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BrewPotionMixRegistrar} (2026-07-31 D10 = K-13): 討伐素材の醸造が 18 件中 2 件しか成立しない件。
 *
 * <p><b>MockBukkit を使わない理由</b>: {@code ServerMock#getPotionBrewer()} は
 * {@code UnimplementedOperationException} を投げる(=TF の skip ガードがビルド失敗にする)。
 * したがって「Paper へ渡す内容」を純粋なデータとして組み立てる部分を切り出し、
 * {@code MixSink} と result factory の 2 つのシームでサーバに触れずに検証する。
 */
class BrewPotionMixRegistrarTest {

    private static final PotionEffectType ANY_EFFECT = mock(PotionEffectType.class);

    private static Logger log() {
        return Logger.getLogger("BrewPotionMixRegistrarTest");
    }

    private static BrewPotionSpec spec(String base, String ingredient) {
        return new BrewPotionSpec(base, ingredient, ANY_EFFECT, 3600, 1);
    }

    // ---- 登録計画 (純関数) ----

    @Test
    void everySpecGetsAStableUniqueKeyPerGroup() {
        Map<String, BrewUnlockGroup> groups = new LinkedHashMap<>();
        groups.put("apex-brew", new BrewUnlockGroup(List.of(
                spec("THICK", "custom:hoglin_tusk"),
                spec("THICK", "custom:piglin_brute_plate"))));
        groups.put("hunter-hex", new BrewUnlockGroup(List.of(
                spec("THICK", "custom:witch_elixir"))));

        List<BrewPotionMixRegistrar.MixPlan> plans = BrewPotionMixRegistrar.plan(groups, log());

        assertEquals(List.of(
                        new NamespacedKey("trinityforge", "brew_apex_brew_1"),
                        new NamespacedKey("trinityforge", "brew_apex_brew_2"),
                        new NamespacedKey("trinityforge", "brew_hunter_hex_1")),
                plans.stream().map(BrewPotionMixRegistrar.MixPlan::key).toList(),
                "キーは grouId+連番で安定していること (addPotionMix は同一キーで IllegalArgumentException)");
    }

    @Test
    void specsWithoutAnIngredientAreSkipped() {
        Map<String, BrewUnlockGroup> groups = Map.of("g", new BrewUnlockGroup(List.of(
                spec("THICK", ""), spec("THICK", "custom:hoglin_tusk"))));

        List<BrewPotionMixRegistrar.MixPlan> plans = BrewPotionMixRegistrar.plan(groups, log());

        assertEquals(1, plans.size());
        assertEquals("custom:hoglin_tusk", plans.get(0).spec().ingredient());
    }

    @Test
    void vanillaCollidingPairsAreNotRegistered() {
        // AWKWARD + GLISTERING_MELON_SLICE はバニラの「治癒のポーション」。custom mix は
        // PotionBrewing#mix でバニラより先に評価されるので、登録するとサーバ全体で治癒が作れなくなる。
        Map<String, BrewUnlockGroup> groups = Map.of("healthboost-haste", new BrewUnlockGroup(List.of(
                spec("AWKWARD", "GLISTERING_MELON_SLICE"),
                spec("THICK", "GOLDEN_CARROT"))));

        List<BrewPotionMixRegistrar.MixPlan> plans = BrewPotionMixRegistrar.plan(groups, log());

        assertEquals(1, plans.size(), "バニラと衝突する組み合わせだけ登録から外れる");
        assertEquals("GOLDEN_CARROT", plans.get(0).spec().ingredient());
    }

    @Test
    void vanillaCollisionIsReportedPerPair() {
        assertNotNull(BrewPotionMixRegistrar.vanillaCollision("AWKWARD", "GLISTERING_MELON_SLICE"),
                "AWKWARD + グリスタリングメロン = バニラの治癒");
        assertNotNull(BrewPotionMixRegistrar.vanillaCollision("AWKWARD", "GOLDEN_CARROT"),
                "AWKWARD + 金のニンジン = バニラの暗視");
        assertNotNull(BrewPotionMixRegistrar.vanillaCollision("THICK", "GUNPOWDER"),
                "火薬/ドラゴンブレスは容器mix(スプラッシュ化/残留化)なのでどのベースからでも成立する");
        assertNotNull(BrewPotionMixRegistrar.vanillaCollision("THICK", "DRAGON_BREATH"));
        assertNotNull(BrewPotionMixRegistrar.vanillaCollision("WATER", "NETHER_WART"),
                "WATER + ネザーウォート = バニラの awkward");

        assertNull(BrewPotionMixRegistrar.vanillaCollision("THICK", "GOLDEN_CARROT"),
                "THICK はバニラに出発点の mix が無いので衝突しない");
        assertNull(BrewPotionMixRegistrar.vanillaCollision("THICK", "SUGAR"));
        assertNull(BrewPotionMixRegistrar.vanillaCollision("AWKWARD", "GOLDEN_APPLE"),
                "金のリンゴはバニラの醸造素材ではない");
        assertNull(BrewPotionMixRegistrar.vanillaCollision("THICK", "custom:endermite_soot"),
                "custom: 素材は述語照合(PDC一致)なので素の火薬には当たらず、バニラを潰さない");
    }

    @Test
    void extendUpgradeInvertIngredientsOnlyCollideWhereVanillaActuallyBrewsThem() {
        // 2026-07-31 レビュー指摘#3: 延長/強化/反転はバニラでは「WATER」と「効果付きポーション」を
        // 出発点にする mix しか無い。THICK / MUNDANE を from とする mix は1件も無いので、
        // ベースを見ずに衝突扱いすると実在しないバニラレシピを守るために登録を拒否してしまう
        // (= 運営者が editor で書いた組が無言で成立しない = K-13 と同じ症状の再発)。
        for (String ingredient : java.util.List.of("REDSTONE", "GLOWSTONE_DUST", "FERMENTED_SPIDER_EYE")) {
            assertNull(BrewPotionMixRegistrar.vanillaCollision("THICK", ingredient),
                    "THICK + " + ingredient + " はバニラに存在しない組み合わせ");
            assertNull(BrewPotionMixRegistrar.vanillaCollision("MUNDANE", ingredient),
                    "MUNDANE + " + ingredient + " はバニラに存在しない組み合わせ");
            assertNotNull(BrewPotionMixRegistrar.vanillaCollision("WATER", ingredient),
                    "WATER + " + ingredient + " はバニラが MUNDANE / THICK / 弱化 を作る");
            assertNotNull(BrewPotionMixRegistrar.vanillaCollision("SWIFTNESS", ingredient),
                    "効果付きポーション + " + ingredient + " は延長/強化/反転そのもの");
        }
        assertNull(BrewPotionMixRegistrar.vanillaCollision("AWKWARD", "REDSTONE"),
                "AWKWARD + レッドストーンもバニラには無い(延長できる効果を持っていないため)");
    }

    @Test
    void deadEndBasesStillRefuseContainerIngredients() {
        // 容器mix だけは THICK / MUNDANE でも衝突する(スプラッシュ化はどのポーションからでも成立する)。
        Map<String, BrewUnlockGroup> groups = Map.of("g", new BrewUnlockGroup(List.of(
                spec("MUNDANE", "GUNPOWDER"), spec("MUNDANE", "REDSTONE"))));

        List<BrewPotionMixRegistrar.MixPlan> plans = BrewPotionMixRegistrar.plan(groups, log());

        assertEquals(List.of("REDSTONE"), plans.stream()
                        .map(p -> p.spec().ingredient()).toList(),
                "火薬だけが衝突として外れ、レッドストーンは登録される");
    }

    // ---- 重複した (base, ingredient) の解決 (レビュー指摘#2) ----

    @Test
    void duplicatePairsKeepOnlyTheHigherRequirementLevel() {
        // 実害だった形: Lv60 と Lv80 が THICK+GOLDEN_CARROT を重複宣言 → yml 順で Lv60 が先に一致し、
        // Lv80 の amplifier 1 が永久に出なかった。
        Map<String, BrewUnlockGroup> groups = new LinkedHashMap<>();
        groups.put("healthboost-haste", new BrewUnlockGroup(List.of(spec("THICK", "GOLDEN_CARROT"))));
        groups.put("healthboost-haste-2", new BrewUnlockGroup(List.of(spec("THICK", "GOLDEN_CARROT"))));
        Map<String, Integer> levels = Map.of("healthboost-haste", 60, "healthboost-haste-2", 80);

        List<BrewPotionMixRegistrar.MixPlan> plans =
                BrewPotionMixRegistrar.plan(groups, levels, log());

        assertEquals(1, plans.size(), "同じ (base, ingredient) は1件しか登録しない");
        assertEquals("healthboost-haste-2", plans.get(0).groupId(),
                "要求レベルが高い側(上位段)が勝つ");
        assertEquals(80, plans.get(0).requirementLevel());
    }

    @Test
    void duplicatePairsAreResolvedDeterministicallyWhenLevelsTie() {
        Map<String, BrewUnlockGroup> groups = new LinkedHashMap<>();
        groups.put("first", new BrewUnlockGroup(List.of(spec("THICK", "custom:hoglin_tusk"))));
        groups.put("second", new BrewUnlockGroup(List.of(spec("THICK", "custom:hoglin_tusk"))));

        List<BrewPotionMixRegistrar.MixPlan> plans = BrewPotionMixRegistrar.plan(groups, log());

        assertEquals(1, plans.size());
        assertEquals("first", plans.get(0).groupId(), "同値なら yml 順の先頭(順序で結果が揺れない)");
    }

    @Test
    void differentBasesForTheSameIngredientAreBothRegistered() {
        // 出荷 config が採った段の分け方(下位段 THICK / 上位段 MUNDANE)が重複扱いされないこと。
        Map<String, BrewUnlockGroup> groups = new LinkedHashMap<>();
        groups.put("lower", new BrewUnlockGroup(List.of(spec("THICK", "GOLDEN_CARROT"))));
        groups.put("upper", new BrewUnlockGroup(List.of(spec("MUNDANE", "GOLDEN_CARROT"))));

        assertEquals(2, BrewPotionMixRegistrar.plan(groups, log()).size());
    }

    @Test
    void pairKeyNormalizesCaseAndAliasesSoDuplicatesCannotHideBehindSpelling() {
        assertEquals(BrewRecipeSupport.pairKey("THICK", "SUGAR"),
                BrewRecipeSupport.pairKey(" thick ", "minecraft:sugar"));
        assertEquals(BrewRecipeSupport.pairKey("", "SUGAR"),
                BrewRecipeSupport.pairKey(null, "SUGAR"),
                "base 空欄と未指定は同じ「任意のビン」");
    }

    @Test
    void pairKeyFollowsTheSameCaseRulesAsTheRuntimeIngredientMatcher() {
        // 2026-07-31 レビュー指摘#8: pairKey の正規化が matchesIngredient より緩いと
        // 「重複として片方を黙って落としたのに、実行時には別アイテムとして扱う」取り違えになる。
        // matchesIngredient は custom: の id を大小区別して比較する(CrossPluginItemResolver の
        // PDC 値と equals)ので、pairKey も小文字化してはいけない。
        ItemStack tusk = TestStacks.withArsId(Material.BONE, "hoglin_tusk");
        assertTrue(BrewRecipeSupport.matchesIngredient(tusk, "custom:hoglin_tusk"));
        assertFalse(BrewRecipeSupport.matchesIngredient(tusk, "custom:Hoglin_Tusk"),
                "実行時は大小を区別する = 別の素材");
        assertNotEquals(BrewRecipeSupport.pairKey("THICK", "custom:Hoglin_Tusk"),
                BrewRecipeSupport.pairKey("THICK", "custom:hoglin_tusk"),
                "実行時に別物なら重複扱いしてはいけない(片方が WARNING だけで消えると"
                        + "「登録されているのに永久に一致しない」組が残る)");

        // 逆にバニラ材質は matchesIngredient も Material.matchMaterial で解決する = 大小/別名を吸収する。
        ItemStack sugar = TestStacks.plain(Material.SUGAR);
        assertTrue(BrewRecipeSupport.matchesIngredient(sugar, "sugar"));
        assertTrue(BrewRecipeSupport.matchesIngredient(sugar, "minecraft:SUGAR"));
        assertEquals(BrewRecipeSupport.pairKey("THICK", "sugar"),
                BrewRecipeSupport.pairKey("THICK", "minecraft:SUGAR"));
    }

    @Test
    void waterBaseOnlyCollidesWithTheElevenIngredientsVanillaActuallyBrewsFromWater() {
        // 2026-07-31 レビュー指摘#3(の残り): WATER の衝突条件が「AWKWARD 起点の素材」まで
        // 巻き込んでいたため、バニラに WATER mix が無い8素材でも登録を拒否していた。
        for (String ingredient : List.of("GLISTERING_MELON_SLICE", "GHAST_TEAR", "RABBIT_FOOT",
                "BLAZE_POWDER", "SPIDER_EYE", "SUGAR", "MAGMA_CREAM", "REDSTONE",
                "GLOWSTONE_DUST", "FERMENTED_SPIDER_EYE", "NETHER_WART")) {
            assertNotNull(BrewPotionMixRegistrar.vanillaCollision("WATER", ingredient),
                    "WATER + " + ingredient + " はバニラの mix(登録するとサーバ全体で潰れる)");
        }
        // AWKWARD 起点しか無い素材: WATER + これらはバニラに1件も無い。
        for (String ingredient : List.of("GOLDEN_CARROT", "PUFFERFISH", "TURTLE_SCUTE",
                "PHANTOM_MEMBRANE", "BREEZE_ROD", "SLIME_BLOCK", "STONE", "COBWEB")) {
            assertNull(BrewPotionMixRegistrar.vanillaCollision("WATER", ingredient),
                    "WATER + " + ingredient + " はバニラに存在しないので拒否してはいけない");
            assertNotNull(BrewPotionMixRegistrar.vanillaCollision("AWKWARD", ingredient),
                    "AWKWARD + " + ingredient + " は実在するバニラの mix");
            assertNotNull(BrewPotionMixRegistrar.vanillaCollision("", ingredient),
                    "base 未指定は AWKWARD ビンも含むので衝突する");
        }
    }

    // ---- 要求レベルの解決 ----

    @Test
    void requirementLevelsTakeTheLowestNodeThatPlacesTheGate() {
        com.trinityforge.skilltree.SkillTree tree = new com.trinityforge.skilltree.SkillTree(
                "ALCHEMY", "錬金", "BREWING_STAND", "0,0", null,
                Map.of(
                        "C-2-upper", node("C-2-upper", 60, "brew:healthboost-haste"),
                        "E-1-1", node("E-1-1", 80, "brew:healthboost-haste-2"),
                        "E-1-2", node("E-1-2", 70, "brew:healthboost-haste-2")));

        Map<String, Integer> levels = BrewPotionMixRegistrar.requirementLevels(List.of(tree));

        assertEquals(60, levels.get("healthboost-haste"));
        assertEquals(70, levels.get("healthboost-haste-2"),
                "同じ gate を複数ノードが置いているなら、最初に届くノードのレベルが実際の要求レベル");
        assertNull(levels.get("apex-brew"), "未参照グループは記録しない(既定 0 扱い)");
    }

    private static com.trinityforge.skilltree.SkillNode node(String id, int level, String gateId) {
        return new com.trinityforge.skilltree.SkillNode(id, id, level,
                com.trinityforge.skilltree.SkillRole.BRANCH, null, null, null, 1, null,
                Map.of(), Map.of(), List.of(), List.of(),
                List.of(new com.trinityforge.skilltree.DedicatedEffectEntry(gateId, null)));
    }

    @Test
    void blankBaseMatchesEveryPotionSoAnyVanillaIngredientCollides() {
        assertNotNull(BrewPotionMixRegistrar.vanillaCollision("", "SUGAR"),
                "base 省略は「任意のビン」= AWKWARD も含むのでバニラ素材は必ず衝突する");
        assertNull(BrewPotionMixRegistrar.vanillaCollision("", "custom:witch_elixir"));
    }

    @Test
    void unknownIngredientMaterialIsSkippedInsteadOfRegisteringADeadMix() {
        Map<String, BrewUnlockGroup> groups = Map.of("g", new BrewUnlockGroup(List.of(
                spec("THICK", "NOT_A_REAL_MATERIAL"))));

        assertTrue(BrewPotionMixRegistrar.plan(groups, log()).isEmpty());
    }

    // ---- 述語 (Bukkit サーバ不要: ItemStack/ItemMeta/PDC はモック) ----

    @Test
    void customIngredientPredicateMatchesTheArsPdcIdOnly() {
        RecipeChoice choice = BrewPotionMixRegistrar.ingredientChoice("custom:witch_elixir");

        assertTrue(choice.test(TestStacks.withArsId(Material.GLASS_BOTTLE, "witch_elixir")));
        assertFalse(choice.test(TestStacks.plain(Material.GLASS_BOTTLE)),
                "素のガラス瓶を醸造素材にしてしまうと MaterialChoice と同じ過剰一致になる");
        assertFalse(choice.test(TestStacks.withArsId(Material.GLASS_BOTTLE, "other_material")));
    }

    @Test
    void plainMaterialIngredientPredicateMatchesTheMaterial() {
        RecipeChoice choice = BrewPotionMixRegistrar.ingredientChoice("SUGAR");

        assertTrue(choice.test(TestStacks.plain(Material.SUGAR)));
        assertFalse(choice.test(TestStacks.plain(Material.REDSTONE)));
    }

    @Test
    void inputPredicateMatchesEveryBottleShapeOfTheConfiguredBase() {
        RecipeChoice choice = BrewPotionMixRegistrar.inputChoice("THICK");

        assertTrue(choice.test(TestStacks.potion(Material.POTION, org.bukkit.potion.PotionType.THICK)));
        assertTrue(choice.test(TestStacks.potion(Material.SPLASH_POTION, org.bukkit.potion.PotionType.THICK)));
        assertTrue(choice.test(TestStacks.potion(Material.LINGERING_POTION, org.bukkit.potion.PotionType.THICK)));
        assertFalse(choice.test(TestStacks.potion(Material.POTION, org.bukkit.potion.PotionType.AWKWARD)));
        assertFalse(choice.test(TestStacks.plain(Material.GLASS_BOTTLE)));
    }

    @Test
    void blankInputBaseAcceptsAnyPotionButNeverANonPotion() {
        RecipeChoice choice = BrewPotionMixRegistrar.inputChoice("");

        assertTrue(choice.test(TestStacks.potion(Material.POTION, org.bukkit.potion.PotionType.AWKWARD)));
        assertFalse(choice.test(TestStacks.plain(Material.GLASS_BOTTLE)));
    }

    // ---- registerAll (fake sink) ----

    @Test
    void registerAllIsIdempotentAndRemovesItsOwnKeysFirst() {
        RecordingSink sink = new RecordingSink();
        Map<String, BrewUnlockGroup> groups = Map.of("apex-brew", new BrewUnlockGroup(List.of(
                spec("THICK", "custom:hoglin_tusk"))));

        BrewPotionMixRegistrar registrar = new BrewPotionMixRegistrar(
                fakePlugin(), () -> groups, Map::of, sink, spec -> mock(ItemStack.class));

        registrar.registerAll();
        assertEquals(List.of(new NamespacedKey("trinityforge", "brew_apex_brew_1")), sink.added);
        assertTrue(sink.removed.isEmpty(), "初回は外すものが無い");

        registrar.registerAll();
        assertEquals(List.of(new NamespacedKey("trinityforge", "brew_apex_brew_1")), sink.removed,
                "2回目は先に自分のキーを外す (addPotionMix は同一キーで IllegalArgumentException を投げる)");
        assertEquals(2, sink.added.size());
        assertEquals(1, registrar.registeredKeys().size());
    }

    @Test
    void livePlansExposeExactlyWhatWasRegisteredSoTheGateCannotDivergeFromTheMixes() {
        // BrewUnlockListener はこの一覧だけを見る。登録されなかった組(バニラ衝突など)が混じると
        // 「登録されていないのにゲートだけ掛かる」= バニラのポーションが作れない誤爆になる。
        RecordingSink sink = new RecordingSink();
        Map<String, BrewUnlockGroup> groups = Map.of("healthboost-haste", new BrewUnlockGroup(List.of(
                spec("AWKWARD", "GLISTERING_MELON_SLICE"), spec("THICK", "GOLDEN_CARROT"))));

        BrewPotionMixRegistrar registrar = new BrewPotionMixRegistrar(
                fakePlugin(), () -> groups, Map::of, sink, spec -> mock(ItemStack.class));
        assertTrue(registrar.livePlans().isEmpty(), "registerAll 前は空(ゲートも掛からない)");

        registrar.registerAll();

        assertEquals(List.of("GOLDEN_CARROT"), registrar.livePlans().stream()
                        .map(p -> p.spec().ingredient()).toList(),
                "バニラ衝突で登録から外れた組は livePlans にも入らない");
    }

    @Test
    void theSinkOffersNoResetSoOtherPluginsMixesCanNeverBeWipedByAccident() {
        // PaperPotionBrewer#resetPotionMixes は potionBrewing を bootstrap() から作り直す実装なので、
        // 他プラグインが登録した mix まで全部消える。シームに reset を生やさないことで
        // 「うっかり呼ぶ」経路を型で塞いでいる — この保証が緩んだら気づけるようにテストで固定する。
        List<String> methods = java.util.Arrays.stream(BrewPotionMixRegistrar.MixSink.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .sorted()
                .toList();

        assertEquals(List.of("add", "remove"), methods);
    }

    private static Plugin fakePlugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(log());
        return plugin;
    }

    private static final class RecordingSink implements BrewPotionMixRegistrar.MixSink {
        private final List<NamespacedKey> added = new ArrayList<>();
        private final List<NamespacedKey> removed = new ArrayList<>();

        @Override
        public void add(PotionMix mix) {
            added.add(mix.getKey());
        }

        @Override
        public void remove(NamespacedKey key) {
            removed.add(key);
        }
    }

    /** Bukkit サーバ無しで ItemStack/PDC を用意するヘルパー。 */
    private static final class TestStacks {
        private static final NamespacedKey ARS_ID = new NamespacedKey("arspaper", "custom_item_id");

        static ItemStack plain(Material type) {
            ItemStack stack = mock(ItemStack.class);
            when(stack.getType()).thenReturn(type);
            when(stack.hasItemMeta()).thenReturn(false);
            return stack;
        }

        static ItemStack withArsId(Material type, String id) {
            ItemStack stack = mock(ItemStack.class);
            org.bukkit.inventory.meta.ItemMeta meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
            org.bukkit.persistence.PersistentDataContainer pdc =
                    mock(org.bukkit.persistence.PersistentDataContainer.class);
            when(stack.getType()).thenReturn(type);
            when(stack.hasItemMeta()).thenReturn(true);
            when(stack.getItemMeta()).thenReturn(meta);
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.get(ARS_ID, org.bukkit.persistence.PersistentDataType.STRING)).thenReturn(id);
            return stack;
        }

        static ItemStack potion(Material type, org.bukkit.potion.PotionType base) {
            ItemStack stack = mock(ItemStack.class);
            org.bukkit.inventory.meta.PotionMeta meta = mock(org.bukkit.inventory.meta.PotionMeta.class);
            when(stack.getType()).thenReturn(type);
            when(stack.hasItemMeta()).thenReturn(true);
            when(stack.getItemMeta()).thenReturn(meta);
            when(meta.getBasePotionType()).thenReturn(base);
            return stack;
        }
    }
}
