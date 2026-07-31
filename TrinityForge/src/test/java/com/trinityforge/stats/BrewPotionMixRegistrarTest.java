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
        assertNotNull(BrewPotionMixRegistrar.vanillaCollision("THICK", "REDSTONE"),
                "レッドストーン/グロウストーン/発酵した蜘蛛の目/火薬/ドラゴンブレスは"
                        + "「どのポーションでも」変換するのでベースに関係なく衝突する");
        assertNotNull(BrewPotionMixRegistrar.vanillaCollision("THICK", "GUNPOWDER"));
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
                fakePlugin(), () -> groups, sink, spec -> mock(ItemStack.class));

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
