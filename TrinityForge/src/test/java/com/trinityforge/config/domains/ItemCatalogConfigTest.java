package com.trinityforge.config.domains;

import com.trinityforge.config.domains.ItemCatalogConfig.ParseResult;
import com.trinityforge.pdc.BindType;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.RecipeIngredient;
import com.trinityforge.stats.RecipeSpec;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemCatalogConfigTest {

    private static final Logger LOG = Logger.getLogger("ItemCatalogConfigTest");

    private static ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return ItemCatalogConfig.parse(cfg.getConfigurationSection("items"), LOG);
    }

    /** Runs {@code action} while capturing every log message LOG emits, for warning assertions. */
    private static List<String> captureLogMessages(Runnable action) {
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        LOG.addHandler(handler);
        try {
            action.run();
        } finally {
            LOG.removeHandler(handler);
        }
        return messages;
    }

    @Test
    void parsesValidEntryWithAllFields() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                    display-name: "Blade"
                    custom-model-data: 7
                    bind-type: SOULBOUND
                    use-level-requirement: 12
                    use-skill: HEAVY_WEAPONS
                """);
        assertEquals(0, r.skipped());
        ItemTemplate t = r.templates().get("blade");
        assertNotNull(t);
        assertEquals(Material.DIAMOND_SWORD, t.material());
        assertEquals("Blade", t.displayName());
        assertEquals(7, t.customModelData());
        assertEquals(BindType.SOULBOUND, t.bindType());
        assertEquals(12, t.useLevelRequirement());
        assertEquals("HEAVY_WEAPONS", t.useSkill());
    }

    @Test
    void skipsUnknownMaterialButKeepsRest() throws Exception {
        ParseResult r = parse("""
                items:
                  good:
                    material: STICK
                  bad:
                    material: NOT_A_REAL_MATERIAL
                """);
        assertEquals(1, r.skipped());
        assertEquals(1, r.templates().size());
        assertTrue(r.templates().containsKey("good"));
    }

    @Test
    void appliesDefaultsForOptionalFields() throws Exception {
        ParseResult r = parse("""
                items:
                  plain:
                    material: STICK
                """);
        ItemTemplate t = r.templates().get("plain");
        assertEquals(BindType.TRADEABLE, t.bindType());
        assertNull(t.displayName());
        assertNull(t.customModelData());
        assertNull(t.useSkill());
        assertEquals(0, t.useLevelRequirement());
        assertTrue(t.lore().isEmpty(), "no lore: section -> empty list, not null");
    }

    // Flavor lore (catalog `lore:`): optional MiniMessage lines inserted ahead of the auto-generated
    // stat lore by ItemAssembler. Parsing here is back-compat: absent/empty is exactly the pre-flavor
    // behaviour (empty, immutable list).

    @Test
    void parsesLoreListInOrder() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                    lore:
                      - "<gray>line one</gray>"
                      - "<gray>line two</gray>"
                """);
        assertEquals(0, r.skipped());
        ItemTemplate t = r.templates().get("blade");
        assertEquals(List.of("<gray>line one</gray>", "<gray>line two</gray>"), t.lore());
    }

    @Test
    void absentLoreYieldsEmptyImmutableList() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                """);
        ItemTemplate t = r.templates().get("blade");
        assertTrue(t.lore().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> t.lore().add("x"),
                "template lore must be an immutable defensive copy");
    }

    @Test
    void emptySectionYieldsNoTemplates() throws Exception {
        ParseResult r = parse("other: 1\n");
        assertEquals(0, r.skipped());
        assertTrue(r.templates().isEmpty());
    }

    // Item 6: use-level-requirement / use-skill type-mismatch handling must warn, not silently
    // coerce a config typo into "unrestricted" with no signal.

    @Test
    void nonIntegerUseLevelRequirementWarnsAndDefaultsToUnrestricted() throws Exception {
        List<ParseResult> results = new ArrayList<>();
        List<String> messages = captureLogMessages(() -> {
            try {
                results.add(parse("""
                        items:
                          blade:
                            material: STICK
                            use-level-requirement: "20"
                        """));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ParseResult r = results.get(0);
        assertEquals(0, r.skipped(), "a bad use-level-requirement warns but does not skip the item");
        assertEquals(0, r.templates().get("blade").useLevelRequirement());
        assertTrue(messages.stream().anyMatch(m -> m.contains("use-level-requirement")),
                "expected a warning mentioning use-level-requirement, got: " + messages);
    }

    @Test
    void integerUseLevelRequirementParsesWithoutWarning() throws Exception {
        List<ParseResult> results = new ArrayList<>();
        List<String> messages = captureLogMessages(() -> {
            try {
                results.add(parse("""
                        items:
                          blade:
                            material: STICK
                            use-level-requirement: 20
                        """));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(20, results.get(0).templates().get("blade").useLevelRequirement());
        assertTrue(messages.stream().noneMatch(m -> m.contains("use-level-requirement")),
                "a valid integer must not warn, got: " + messages);
    }

    @Test
    void blankUseSkillWarnsAndTreatsAsUnrestricted() throws Exception {
        List<ParseResult> results = new ArrayList<>();
        List<String> messages = captureLogMessages(() -> {
            try {
                results.add(parse("""
                        items:
                          blade:
                            material: STICK
                            use-skill: "   "
                        """));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertNull(results.get(0).templates().get("blade").useSkill());
        assertTrue(messages.stream().anyMatch(m -> m.contains("use-skill")),
                "expected a warning mentioning use-skill, got: " + messages);
    }

    @Test
    void useSkillWithControlCharacterWarnsAndTreatsAsUnrestricted() throws Exception {
        List<ParseResult> results = new ArrayList<>();
        List<String> messages = captureLogMessages(() -> {
            try {
                results.add(parse("""
                        items:
                          blade:
                            material: STICK
                            use-skill: "HEAVY\\tWEAPONS"
                        """));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertNull(results.get(0).templates().get("blade").useSkill());
        assertTrue(messages.stream().anyMatch(m -> m.contains("use-skill")),
                "expected a warning mentioning use-skill, got: " + messages);
    }

    @Test
    void absentUseSkillIsUnrestrictedWithoutWarning() throws Exception {
        List<ParseResult> results = new ArrayList<>();
        List<String> messages = captureLogMessages(() -> {
            try {
                results.add(parse("""
                        items:
                          blade:
                            material: STICK
                        """));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertNull(results.get(0).templates().get("blade").useSkill());
        assertTrue(messages.stream().noneMatch(m -> m.contains("use-skill")),
                "an absent use-skill must not warn, got: " + messages);
    }

    // --- catalog `recipe:` (crafting recipes authored by config alone) ---

    @Test
    void parsesShapedRecipeWithSymbolIngredients() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shaped
                      shape:
                        - "AAA"
                        - " B "
                        - "   "
                      ingredients:
                        A: DIAMOND
                        B: STICK
                      amount: 2
                """);
        assertEquals(0, r.skipped());
        RecipeSpec recipe = r.templates().get("blade").recipe();
        assertNotNull(recipe);
        assertEquals(RecipeSpec.Type.SHAPED, recipe.type());
        assertEquals(List.of("AAA", " B ", "   "), recipe.shape());
        assertEquals(RecipeIngredient.ofMaterial(Material.DIAMOND), recipe.shapedIngredients().get('A'));
        assertEquals(RecipeIngredient.ofMaterial(Material.STICK), recipe.shapedIngredients().get('B'));
        assertEquals(2, recipe.amount());
        // strict-orientation 未指定はデフォルト false (バニラ同様、反転配置も可)。
        assertEquals(false, recipe.strictOrientation());
    }

    @Test
    void parsesShapedRecipeStrictOrientationFlag() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shaped
                      shape: ["A ", "AB"]
                      ingredients:
                        A: DIAMOND
                        B: STICK
                      strict-orientation: true
                """);
        assertEquals(0, r.skipped());
        RecipeSpec recipe = r.templates().get("blade").recipe();
        assertNotNull(recipe);
        assertEquals(true, recipe.strictOrientation());
    }

    @Test
    void parsesShapelessRecipeWithMaterialList() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shapeless
                      ingredients: [DIAMOND, DIAMOND, STICK]
                """);
        assertEquals(0, r.skipped());
        RecipeSpec recipe = r.templates().get("blade").recipe();
        assertNotNull(recipe);
        assertEquals(RecipeSpec.Type.SHAPELESS, recipe.type());
        assertEquals(List.of(
                        RecipeIngredient.ofMaterial(Material.DIAMOND),
                        RecipeIngredient.ofMaterial(Material.DIAMOND),
                        RecipeIngredient.ofMaterial(Material.STICK)),
                recipe.shapelessIngredients());
        assertEquals(1, recipe.amount(), "amount defaults to 1 when absent");
    }

    @Test
    void defaultRecipeTypeIsShapedWhenTypeOmitted() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                    recipe:
                      shape: ["A"]
                      ingredients: { A: STICK }
                """);
        assertEquals(RecipeSpec.Type.SHAPED, r.templates().get("blade").recipe().type());
    }

    @Test
    void absentRecipeSectionLeavesTemplateRecipeless() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                """);
        assertEquals(0, r.skipped());
        assertNull(r.templates().get("blade").recipe());
        assertFalse(r.templates().get("blade").hasRecipe());
    }

    @Test
    void unknownIngredientMaterialSkipsRecipeButKeepsItem() throws Exception {
        List<ParseResult> results = new ArrayList<>();
        List<String> messages = captureLogMessages(() -> {
            try {
                results.add(parse("""
                        items:
                          blade:
                            material: DIAMOND_SWORD
                            display-name: "Blade"
                            recipe:
                              type: shaped
                              shape: ["A"]
                              ingredients:
                                A: NOT_A_REAL_MATERIAL
                        """));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ParseResult r = results.get(0);
        assertEquals(1, r.skipped(), "an invalid recipe is reported as an issue");
        ItemTemplate t = r.templates().get("blade");
        assertNotNull(t, "the item itself must still load despite the bad recipe");
        assertEquals("Blade", t.displayName());
        assertNull(t.recipe(), "the malformed recipe is dropped, not the whole item");
        assertTrue(messages.stream().anyMatch(m -> m.contains("recipe")),
                "expected a warning mentioning the recipe, got: " + messages);
    }

    @Test
    void undefinedShapeSymbolSkipsRecipeButKeepsItem() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shaped
                      shape: ["AB"]
                      ingredients:
                        A: STICK
                """);
        assertEquals(1, r.skipped());
        assertNotNull(r.templates().get("blade"));
        assertNull(r.templates().get("blade").recipe());
    }

    @Test
    void oversizedShapeRowSkipsRecipeButKeepsItem() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shaped
                      shape: ["AAAA"]
                      ingredients:
                        A: STICK
                """);
        assertEquals(1, r.skipped());
        assertNull(r.templates().get("blade").recipe());
    }

    @Test
    void tooManyShapeRowsSkipsRecipeButKeepsItem() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shaped
                      shape: ["A", "A", "A", "A"]
                      ingredients:
                        A: STICK
                """);
        assertEquals(1, r.skipped());
        assertNull(r.templates().get("blade").recipe());
    }

    @Test
    void unknownRecipeTypeSkipsRecipeButKeepsItem() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                    recipe:
                      type: bogus
                      shape: ["A"]
                      ingredients: { A: STICK }
                """);
        assertEquals(1, r.skipped());
        assertNull(r.templates().get("blade").recipe());
    }

    @Test
    void shapelessWithUnknownMaterialSkipsRecipeButKeepsItem() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shapeless
                      ingredients: [NOT_A_REAL_MATERIAL]
                """);
        assertEquals(1, r.skipped());
        assertNull(r.templates().get("blade").recipe());
    }

    // --- catalog `color:` / `enchant-glow:` (leather dye color + hidden-enchant shimmer) ---

    @Test
    void parsesValidColorOnLeatherArmor() throws Exception {
        ParseResult r = parse("""
                items:
                  vest:
                    material: LEATHER_CHESTPLATE
                    color: "#8B0000"
                """);
        assertEquals(0, r.skipped());
        assertEquals("#8B0000", r.templates().get("vest").color());
    }

    @Test
    void absentColorIsNullWithoutWarning() throws Exception {
        List<ParseResult> results = new ArrayList<>();
        List<String> messages = captureLogMessages(() -> {
            try {
                results.add(parse("""
                        items:
                          vest:
                            material: LEATHER_CHESTPLATE
                        """));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertNull(results.get(0).templates().get("vest").color());
        assertTrue(messages.stream().noneMatch(m -> m.contains("color")),
                "an absent color must not warn, got: " + messages);
    }

    @Test
    void colorOnNonLeatherMaterialWarnsAndIsIgnoredButKeepsItem() throws Exception {
        List<ParseResult> results = new ArrayList<>();
        List<String> messages = captureLogMessages(() -> {
            try {
                results.add(parse("""
                        items:
                          blade:
                            material: DIAMOND_SWORD
                            color: "#8B0000"
                        """));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ParseResult r = results.get(0);
        assertEquals(0, r.skipped(), "an invalid color is fail-soft: warns but does not skip the item");
        assertNotNull(r.templates().get("blade"), "the item itself must still load despite the bad color");
        assertNull(r.templates().get("blade").color());
        assertTrue(messages.stream().anyMatch(m -> m.contains("color")),
                "expected a warning mentioning color, got: " + messages);
    }

    @Test
    void malformedHexColorWarnsAndIsIgnoredButKeepsItem() throws Exception {
        List<ParseResult> results = new ArrayList<>();
        List<String> messages = captureLogMessages(() -> {
            try {
                results.add(parse("""
                        items:
                          vest:
                            material: LEATHER_CHESTPLATE
                            color: "not-a-color"
                        """));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ParseResult r = results.get(0);
        assertEquals(0, r.skipped());
        assertNotNull(r.templates().get("vest"));
        assertNull(r.templates().get("vest").color());
        assertTrue(messages.stream().anyMatch(m -> m.contains("color")),
                "expected a warning mentioning color, got: " + messages);
    }

    @Test
    void enchantGlowDefaultsToFalse() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                """);
        assertFalse(r.templates().get("blade").enchantGlow());
    }

    @Test
    void enchantGlowTrueParses() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                    enchant-glow: true
                """);
        assertTrue(r.templates().get("blade").enchantGlow());
    }

    @Test
    void netheriteSourceItemStripsCustomPrefix() throws Exception {
        // source-item はカタログID直書きが仕様だが、custom:<id> 形式も正規化して受け付ける
        // (剥がさないと template() ルックアップに失敗し鍛冶レシピが無言で不成立になる)。
        ParseResult r = parse("""
                items:
                  netherite_blade:
                    material: NETHERITE_SWORD
                    recipe:
                      method: netherite
                      source-item: custom:diamond_blade
                """);
        RecipeSpec spec = r.templates().get("netherite_blade").recipes().get(0);
        assertTrue(spec.isNetherite());
        assertEquals("diamond_blade", spec.sourceItem());
    }

    @Test
    void combineItemsStripCustomPrefix() throws Exception {
        ParseResult r = parse("""
                items:
                  fused_blade:
                    material: DIAMOND_SWORD
                    recipe:
                      method: combine
                      source-item: custom:diamond_blade
                      addition-item: CUSTOM:ember_core
                """);
        RecipeSpec spec = r.templates().get("fused_blade").recipes().get(0);
        assertTrue(spec.isCombine());
        assertEquals("diamond_blade", spec.sourceItem());
        assertEquals("ember_core", spec.additionItem());
    }

    @Test
    void netheritePlainSourceItemStillParses() throws Exception {
        ParseResult r = parse("""
                items:
                  netherite_blade:
                    material: NETHERITE_SWORD
                    recipe:
                      method: netherite
                      source-item: diamond_blade
                """);
        assertEquals("diamond_blade",
                r.templates().get("netherite_blade").recipes().get(0).sourceItem());
    }

    // ------------------------------------------------------------------
    // D2: inventory method
    // ------------------------------------------------------------------

    @Test
    void parsesInventoryMethodShaped() throws Exception {
        ParseResult r = parse("""
                items:
                  mini_ingot:
                    material: IRON_NUGGET
                    recipe:
                      method: inventory
                      type: shaped
                      shape: ["AA", "AA"]
                      ingredients:
                        A: IRON_INGOT
                """);
        assertEquals(0, r.skipped());
        RecipeSpec spec = r.templates().get("mini_ingot").recipe();
        assertNotNull(spec);
        assertTrue(spec.isInventory());
        assertTrue(spec.isBukkitCrafting());
        assertFalse(spec.isWorkbench());
    }

    @Test
    void inventoryShapedRejectsShapeWiderThan2x2() throws Exception {
        ParseResult r = parse("""
                items:
                  too_big:
                    material: IRON_NUGGET
                    recipe:
                      method: inventory
                      type: shaped
                      shape: ["AAA", "   ", "   "]
                      ingredients:
                        A: IRON_INGOT
                """);
        assertEquals(1, r.skipped());
        assertTrue(r.templates().get("too_big").recipes().isEmpty());
    }

    @Test
    void inventoryShapelessRejectsMoreThanFourIngredients() throws Exception {
        ParseResult r = parse("""
                items:
                  too_many:
                    material: IRON_NUGGET
                    recipe:
                      method: inventory
                      type: shapeless
                      ingredients: [IRON_INGOT, IRON_INGOT, IRON_INGOT, IRON_INGOT, IRON_INGOT]
                """);
        assertEquals(1, r.skipped());
    }

    // ------------------------------------------------------------------
    // D3: reversible
    // ------------------------------------------------------------------

    @Test
    void reversibleParsesTrueForUniformShapedWorkbenchRecipe() throws Exception {
        ParseResult r = parse("""
                items:
                  compressed_iron:
                    material: IRON_BLOCK
                    recipe:
                      type: shaped
                      shape: ["AAA", "AAA", "AAA"]
                      ingredients:
                        A: IRON_INGOT
                      reversible: true
                """);
        assertEquals(0, r.skipped());
        RecipeSpec spec = r.templates().get("compressed_iron").recipe();
        assertNotNull(spec);
        assertTrue(spec.reversible());
        assertEquals(RecipeIngredient.ofMaterial(Material.IRON_INGOT), spec.reversibleIngredient());
        assertEquals(9, spec.reversibleMaterialCount());
    }

    @Test
    void reversibleFalseWhenAbsent() throws Exception {
        ParseResult r = parse("""
                items:
                  blade:
                    material: DIAMOND_SWORD
                    recipe:
                      type: shapeless
                      ingredients: [STICK]
                """);
        RecipeSpec spec = r.templates().get("blade").recipe();
        assertNotNull(spec);
        assertFalse(spec.reversible());
    }

    @Test
    void reversibleIgnoredAndWarnsWhenIngredientsAreNotUniform() throws Exception {
        List<ParseResult> results = new ArrayList<>();
        List<String> messages = captureLogMessages(() -> {
            try {
                results.add(parse("""
                        items:
                          blade:
                            material: DIAMOND_SWORD
                            recipe:
                              type: shaped
                              shape: ["AB", "  "]
                              ingredients:
                                A: DIAMOND
                                B: STICK
                              reversible: true
                        """));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ParseResult r = results.get(0);
        assertEquals(0, r.skipped());
        RecipeSpec spec = r.templates().get("blade").recipe();
        assertNotNull(spec);
        assertFalse(spec.reversible());
        assertTrue(messages.stream().anyMatch(m -> m.contains("reversible ignored")),
                "expected a warning mentioning reversible, got: " + messages);
    }

    @Test
    void reversibleIgnoredAndWarnsForRitualMethod() throws Exception {
        List<ParseResult> results = new ArrayList<>();
        List<String> messages = captureLogMessages(() -> {
            try {
                results.add(parse("""
                        items:
                          ritual_item:
                            material: DIAMOND
                            recipe:
                              method: ritual
                              core-item: STICK
                              reversible: true
                        """));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        RecipeSpec spec = results.get(0).templates().get("ritual_item").recipe();
        assertNotNull(spec);
        assertFalse(spec.reversible());
        assertTrue(messages.stream().anyMatch(m -> m.contains("reversible ignored")),
                "expected a warning mentioning reversible, got: " + messages);
    }

    // ---- T1 (2026-07-25 軽装レビュー): shaped recipe の非矩形検出 ----
    // Bukkit の ShapedRecipe#shape は全行が同じ長さ(矩形)を要求する。それ以前は検査がなく
    // Bukkit.addRecipe() が投げた IllegalArgumentException を CatalogRecipeRegistrar の
    // catch (RuntimeException) が WARN 1行で握り潰していたため、実際に7件の胴レシピが
    // クラフト不能なまま気づかれずに本番投入されていた (bone_guard_chestplate 等)。
    // RecipeSpec のコンストラクタ(=config読み込み時点)で検査することで、item本体のロードは
    // 継続しつつ (fail-soft) 「そのレシピだけ skipped」として警告に必ず現れるようにする。

    @Test
    void nonRectangularShapedRecipeIsRejectedAndItemStillLoads() throws Exception {
        List<ParseResult> results = new ArrayList<>();
        List<String> messages = captureLogMessages(() -> {
            try {
                results.add(parse("""
                        items:
                          bad_chestplate:
                            material: LEATHER_CHESTPLATE
                            recipe:
                              method: workbench
                              type: shaped
                              shape:
                                - YY
                                - XXX
                                - XXX
                              ingredients:
                                X: BONE
                                Y: LEATHER
                        """));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ParseResult r = results.get(0);
        assertEquals(1, r.skipped(), "non-rectangular shape should be counted as a skipped recipe");
        ItemTemplate template = r.templates().get("bad_chestplate");
        assertNotNull(template, "item itself must still load (fail-soft)");
        assertNull(template.recipe(), "invalid recipe must not be registered");
        assertTrue(messages.stream().anyMatch(m -> m.contains("not rectangular")),
                "expected a warning mentioning the non-rectangular shape, got: " + messages);
    }

    @Test
    void rectangularShapedRecipeWithBlankSlotsIsAccepted() throws Exception {
        ParseResult r = parse("""
                items:
                  good_chestplate:
                    material: LEATHER_CHESTPLATE
                    recipe:
                      method: workbench
                      type: shaped
                      shape:
                        - "Y Y"
                        - XXX
                        - XXX
                      ingredients:
                        X: BONE
                        Y: LEATHER
                """);
        assertEquals(0, r.skipped());
        RecipeSpec spec = r.templates().get("good_chestplate").recipe();
        assertNotNull(spec);
        assertEquals(List.of("Y Y", "XXX", "XXX"), spec.shape());
    }

    /**
     * T1 の再発防止テスト: 本番 {@code items/catalog.yml} を丸ごとロードし、shaped recipe が
     * ひとつも skip されないこと(= 全て矩形であること)を検査する。7件の胴レシピが非矩形のまま
     * 気づかれずに投入された事故の再発防止ガード。
     */
    @Test
    void productionCatalogHasNoNonRectangularShapedRecipes() throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of("src/main/resources/items/catalog.yml");
        String yaml = java.nio.file.Files.readString(path);
        List<ParseResult> results = new ArrayList<>();
        List<String> messages = captureLogMessages(() -> {
            try {
                results.add(parse(yaml));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        List<String> rectangularityWarnings = messages.stream()
                .filter(m -> m.contains("not rectangular"))
                .toList();
        assertTrue(rectangularityWarnings.isEmpty(),
                "production catalog.yml has non-rectangular shaped recipe(s): " + rectangularityWarnings);
    }
}
