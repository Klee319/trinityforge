package com.trinityforge.listeners;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 図鑑エントリID解決の<b>分岐順序</b>の契約 (2026-07-31, K-11)。
 *
 * <p><b>このテストクラスがなぜ MockBukkit を一切使わないのか</b> — それが本題なので先に書く。
 * K-11 は「{@code CollectionListener} 冒頭の {@code !stack.hasItemMeta()} 早期 return が
 * Material 判定より前にあり、メタを持たない素のバニラ品が1件も記録されない」という不具合で、
 * {@code collection.yml} の {@code items.structure} 16件が永久に錠前
 * (⇒ {@code goal_completionist} が構造的に達成不能)だった。にもかかわらず
 * {@link CollectionArsItemRecordingTest#watchedVanillaMaterialStillRecorded()} は緑だった。理由:
 *
 * <ul>
 *   <li>MockBukkit 4.110.0 の {@code ItemStackMock} はコンストラクタで {@code itemMeta} を
 *       <b>無条件に代入</b>し、{@code hasItemMeta()} は
 *       {@code itemMeta != null && !ItemFactoryMock.equals(itemMeta, null)}(中身は
 *       {@code Objects.equals})を返す。⇒ 素のスタックでも<b>常に true</b>。</li>
 *   <li>実機の {@code CraftItemStack#hasItemMeta()} は {@code getComponentsPatch().isEmpty()} を
 *       見るので、ルートチェストから出た無傷の {@code ELYTRA} / {@code ECHO_SHARD} などは
 *       <b>false</b>。つまり MockBukkit では本番条件を表現できず、
 *       <b>本番で壊れているコードに対してアサーションが通ってしまう</b>
 *       (未実装APIの SKIPPED 素通りとは別型で、緑に見えるぶんこちらの方が危険)。</li>
 *   <li>{@code hasItemMeta()} を偽装したスタックを作っても、
 *       {@code InventoryMock#setItem}/{@code addItem}/{@code ItemMock} はすべて
 *       {@code ItemStack.clone()}(= {@code craftDelegate.clone()})を通し、
 *       {@code getContents()} は {@code ItemStackMirror} で包み直すため、
 *       <b>インベントリ経路ではラッパのクラスが消えて偽装が届かない</b>。</li>
 * </ul>
 *
 * <p>そこで判定順序を {@link CollectionListener#resolveEntryId} という純関数へ切り出し、
 * <b>meta の有無を {@code boolean} 引数として受ける</b>形にしてある。この形なら Bukkit も
 * MockBukkit も一切通らないので、テストが「実機では死んでいるのに緑」になりようがない。
 *
 * <p>同型のバグは既にこのリポジトリで一度直っている
 * ({@link com.trinityforge.combat.ProjectileWeapon#store} の javadoc: bare-material の
 * 弓/クロスボウ/トライデントを {@code hasItemMeta()} で落としていた High bug)。K-11 はその再発。
 */
class CollectionEntryResolutionTest {

    /** 実配置と同じ衝突を持つ監視集合: ECHO_SHARD は Material としても Ars 品の base としても登場する。 */
    private static final Set<String> WATCHED = Set.of(
            "ELYTRA", "ECHO_SHARD", "HEART_OF_THE_SEA",
            "reality_thread_core", "warden_tendril", "skill_tree_reset");

    /** meta 由来の事実は何も無いスタック(PDC刻印なし・CMDなし)。 */
    private static CollectionListener.MetaFacts emptyFacts() {
        return facts(null, null, null, null);
    }

    private static CollectionListener.MetaFacts facts(String stamped, String arsId,
                                                     Integer cmd, String templateId) {
        return new CollectionListener.MetaFacts() {
            @Override
            public Optional<String> stampedCatalogId() {
                return Optional.ofNullable(stamped);
            }

            @Override
            public Optional<String> arsItemId() {
                return Optional.ofNullable(arsId);
            }

            @Override
            public Integer customModelData() {
                return cmd;
            }

            @Override
            public Optional<String> catalogTemplateId(int customModelData) {
                return Optional.ofNullable(templateId);
            }
        };
    }

    /** meta を触ったら即座に落ちる番犬。meta 無しスタックの経路が本当に meta を見ないことの証明に使う。 */
    private static CollectionListener.MetaFacts forbiddenFacts() {
        return new CollectionListener.MetaFacts() {
            @Override
            public Optional<String> stampedCatalogId() {
                throw new AssertionError("meta を持たないスタックで PDC を読もうとした");
            }

            @Override
            public Optional<String> arsItemId() {
                throw new AssertionError("meta を持たないスタックで Ars PDC を読もうとした");
            }

            @Override
            public Integer customModelData() {
                throw new AssertionError("meta を持たないスタックで CustomModelData を読もうとした");
            }

            @Override
            public Optional<String> catalogTemplateId(int customModelData) {
                throw new AssertionError("meta を持たないスタックでカタログ照合しようとした");
            }
        };
    }

    // --- K-11 本体 ---

    @Test
    @DisplayName("K-11: メタを持たない素のバニラ品も Material 判定で記録される")
    void bareVanillaStackWithoutItemMetaIsResolvedByMaterial() {
        assertEquals(Optional.of("ELYTRA"),
                CollectionListener.resolveEntryId("ELYTRA", false, emptyFacts(), WATCHED),
                "ルートチェストから出た無傷のバニラ品は実機で hasItemMeta() が false になる。"
                        + "ここで Optional.empty() を返すと collection.yml の items.structure 16件が"
                        + "永久に埋まらず、goal_completionist(percent: 100) が構造的に達成不能になる");
    }

    @Test
    @DisplayName("K-11: メタ無しでも監視外の Material は記録しない")
    void bareVanillaStackOutsideWatchedSetIsIgnored() {
        assertTrue(CollectionListener.resolveEntryId("DIRT", false, emptyFacts(), WATCHED).isEmpty(),
                "拾った物を無条件に記録するとプレイヤーPDCが全Material分まで膨らむ");
    }

    @Test
    @DisplayName("メタ無しスタックでは PDC/CMD を一切読まない")
    void bareVanillaStackNeverTouchesMeta() {
        assertEquals(Optional.of("ELYTRA"),
                CollectionListener.resolveEntryId("ELYTRA", false, forbiddenFacts(), WATCHED));
        assertTrue(CollectionListener.resolveEntryId("DIRT", false, forbiddenFacts(), WATCHED).isEmpty());
    }

    // --- 順序の回帰ガード: 「Material 判定を前へ出すだけ」の素朴な修正を落とす ---

    @Test
    @DisplayName("TFカタログ刻印は監視対象 Material より優先される")
    void stampedCatalogIdWinsOverWatchedMaterial() {
        // items/catalog.yml の skill_tree_reset は material: ECHO_SHARD (cmd 100083)。
        assertEquals(Optional.of("skill_tree_reset"),
                CollectionListener.resolveEntryId("ECHO_SHARD", true,
                        facts("skill_tree_reset", null, null, null), WATCHED),
                "Material 判定を PDC 判定より前に置くと、ECHO_SHARD 系のカスタム品が"
                        + "item:ECHO_SHARD に潰れて別の枠が到達不能になる");
    }

    @Test
    @DisplayName("Ars刻印は監視対象 Material より優先される")
    void arsItemIdWinsOverWatchedMaterial() {
        // materials.yml: warden_tendril / reality_thread_core は base_material: ECHO_SHARD、
        // source_condenser は HEART_OF_THE_SEA。どちらの Material も items.structure に載っている。
        assertEquals(Optional.of("reality_thread_core"),
                CollectionListener.resolveEntryId("ECHO_SHARD", true,
                        facts(null, "reality_thread_core", null, null), WATCHED));
        assertEquals(Optional.of("warden_tendril"),
                CollectionListener.resolveEntryId("ECHO_SHARD", true,
                        facts(null, "warden_tendril", null, null), WATCHED));
    }

    @Test
    @DisplayName("監視外の Ars 品は、その base Material が監視対象でも記録しない")
    void unwatchedArsItemDoesNotFillItsBaseMaterialSlot() {
        // echo_shard_1x(圧縮アイテム)は意図的に図鑑へ出していない。ここで Material へ
        // フォールバックすると、圧縮品を1個持つだけで ECHO_SHARD の枠が無料で埋まる。
        assertTrue(CollectionListener.resolveEntryId("ECHO_SHARD", true,
                        facts(null, "echo_shard_1x", null, null), WATCHED).isEmpty(),
                "Ars刻印があるスタックは『そのIDが監視対象か』だけで決める。"
                        + "Material へ落とすと監視外にした品で枠が埋まる");
    }

    // --- meta ありスタックの fallback (K-11 修正で meta 無しと挙動が揃った経路) ---

    @Test
    @DisplayName("メタはあるが PDC も CMD も無いスタックは Material 判定へ落ちる")
    void metaWithoutPdcOrCustomModelDataFallsBackToMaterial() {
        assertEquals(Optional.of("ELYTRA"),
                CollectionListener.resolveEntryId("ELYTRA", true, emptyFacts(), WATCHED),
                "耐久が減った ELYTRA やリネーム品はこちらを通る。meta 無し経路と同じ結果になるのが正しい");
    }

    @Test
    @DisplayName("CMD がテンプレートに一致しないときは Material 判定へ落ちる")
    void unmatchedCustomModelDataFallsBackToMaterial() {
        assertEquals(Optional.of("HEART_OF_THE_SEA"),
                CollectionListener.resolveEntryId("HEART_OF_THE_SEA", true,
                        facts(null, null, 999, null), WATCHED));
    }

    @Test
    @DisplayName("material + CMD がカタログテンプレートに一致すればそのIDで記録する")
    void matchedCatalogTemplateWins() {
        assertEquals(Optional.of("skill_tree_reset"),
                CollectionListener.resolveEntryId("ECHO_SHARD", true,
                        facts(null, null, 100083, "skill_tree_reset"), WATCHED));
    }

    @Test
    @DisplayName("Material 名が無い/空なら何も記録しない")
    void blankMaterialNameIsIgnored() {
        assertTrue(CollectionListener.resolveEntryId(null, false, emptyFacts(), WATCHED).isEmpty());
        assertTrue(CollectionListener.resolveEntryId("  ", false, emptyFacts(), WATCHED).isEmpty());
    }
}
