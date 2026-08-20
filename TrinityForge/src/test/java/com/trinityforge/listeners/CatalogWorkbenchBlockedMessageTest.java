package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import com.trinityforge.stats.ExternalItemRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W-87（実サーバ報告「ネザライトアップグレードの鍛冶型が複製できない」）の回帰ガード。
 *
 * <p><b>真因は「バニラのレシピが潰れている」ではなく「バニラに見えるカスタム品が材料に入っている」</b>。
 * 盤面にカタログ品（TF カタログ／{@link ExternalItemRegistry} 経由の ArsPaper 品）が乗ると、
 * {@code CatalogWorkbenchListener} は「カタログ品を消費してよいのはその identity を明示的に受け取る
 * レシピだけ」という規則で結果枠を消す。これ自体は圧縮ブロックがバニラの分解レシピで
 * 素材に溶けるのを防ぐための正しい保護。
 *
 * <p><b>2026-08-18 追記: 実物の犯人を特定した。</b> 当初は {@code netherite_block_1x} を例にしていたが、
 * サーバの jar から取り出した実際のバニラレシピは
 * <pre>
 *   "#": minecraft:diamond / "C": minecraft:netherrack / "S": netherite_upgrade_smithing_template
 *   pattern: ["#S#", "#C#", "###"]
 * </pre>
 * で、<b>中央はネザーラック</b>。ArsPaper には {@code netherrack_1x}〜{@code netherrack_4x}
 * （「9倍圧縮ネザーラック」〜「6561倍圧縮ネザーラック」、base=NETHERRACK, CMD 1001〜1004）があり、
 * <b>リソースパックに item 定義が無いので素のネザーラックと見た目が完全に同一</b>。
 * ネザーラックは誰でも大量に持つ＝誰でも圧縮する素材なので、
 * <b>サーバの複数人が同時に「バニラの鍛冶型が複製できない」と報告した</b>のはこれが理由。
 * 鍛冶型もダイヤもバニラのままで正しかった。
 *
 * <p>材料欄ではホバーしない限り区別が付かないので、名前を出さない限り
 * プレイヤーは「バニラのレシピが壊れている」としか判断できない。
 * よってここで固定するのは「止めること」ではなく「<b>止めた理由として実物の名前を出すこと</b>」。
 */
class CatalogWorkbenchBlockedMessageTest {

    private static final String PLUGIN_LAYER = "arspaper";
    private static final int COMPRESSED_NETHERRACK_CMD = 1001;
    private static final String COMPRESSED_NETHERRACK_NAME = "81倍圧縮ネザーラック";

    private CatalogWorkbenchListener listener;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        CatalogRecipeRegistrar registrar = mock(CatalogRecipeRegistrar.class);
        when(registrar.registered(any())).thenReturn(Optional.empty());
        listener = new CatalogWorkbenchListener(registrar, mock(ItemCatalogConfig.class));
        ExternalItemRegistry.updateExternalPlugin(PLUGIN_LAYER, Map.of(
                "netherrack_2x", new ExternalItemRegistry.Definition(
                        "netherrack_2x", Material.NETHERRACK,
                        COMPRESSED_NETHERRACK_CMD, COMPRESSED_NETHERRACK_NAME)));
    }

    @AfterEach
    void tearDown() {
        ExternalItemRegistry.updateExternalPlugin(PLUGIN_LAYER, Map.of());
        MockBukkit.unmock();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** 見た目は素のネザーラックそのままの「81倍圧縮ネザーラック」。 */
    private static ItemStack compressedNetherrack(boolean withDisplayName) {
        ItemStack stack = new ItemStack(Material.NETHERRACK);
        stack.editMeta(meta -> {
            meta.setCustomModelData(COMPRESSED_NETHERRACK_CMD);
            if (withDisplayName) {
                meta.displayName(Component.text(COMPRESSED_NETHERRACK_NAME));
            }
        });
        return stack;
    }

    /**
     * バニラの鍛冶型複製の盤面（ダイヤ7 + 鍛冶型 + <b>ネザーラック</b>）。
     * 中央がネザーラックであることは server jar の
     * {@code data/minecraft/recipe/netherite_upgrade_smithing_template.json} で確認済み。
     */
    private static ItemStack[] templateDuplicationGrid(ItemStack center) {
        ItemStack diamond = new ItemStack(Material.DIAMOND);
        return new ItemStack[] {
                diamond.clone(), new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE), diamond.clone(),
                diamond.clone(), center, diamond.clone(),
                diamond.clone(), diamond.clone(), diamond.clone(),
        };
    }

    @Test
    @DisplayName("止めた原因のアイテム名を名指しする(見た目がバニラと同じなので名前が出ないと解決しない)")
    void namesTheItemThatBlockedTheRecipe() {
        String message = plain(listener.blockedMessage(
                templateDuplicationGrid(compressedNetherrack(true))));

        assertTrue(message.contains(COMPRESSED_NETHERRACK_NAME),
                "止めた原因のアイテム名が出ていない。見た目が素のネザーラックと"
                        + "1ピクセルも変わらないので、名前が無いと「バニラのレシピが壊れている」"
                        + "としか判断できない(W-87)。実際のメッセージ: " + message);
        assertTrue(message.contains("材料にできません"), "何が起きたのかが書かれていない: " + message);
    }

    @Test
    @DisplayName("表示名の無いカタログ品ならカタログidで代用する")
    void fallsBackToCatalogIdWhenTheItemHasNoDisplayName() {
        String message = plain(listener.blockedMessage(
                templateDuplicationGrid(compressedNetherrack(false))));

        assertTrue(message.contains("netherrack_2x"),
                "表示名が無いときに何も名指しできていない: " + message);
    }

    @Test
    @DisplayName("カタログ品が無い盤面では汎用メッセージへ落ちる(名指しを捏造しない)")
    void plainVanillaGridFallsBackToTheGenericMessage() {
        // この盤面ではそもそも結果はクリアされない(呼び出し元が gridHasCatalogItem で弾く)。
        // ここで固定するのは「名前が取れないときに嘘の名前を出さない」こと。
        String message = plain(listener.blockedMessage(
                templateDuplicationGrid(new ItemStack(Material.NETHERRACK))));

        assertFalse(message.contains(COMPRESSED_NETHERRACK_NAME),
                "素のバニラ素材をカスタム品として名指ししている: " + message);
        assertTrue(message.contains("専用アイテム"), "汎用メッセージへ落ちていない: " + message);
    }

    @Test
    @DisplayName("CustomModelData の無いスタックは常にバニラ扱い(名指ししない)")
    void stacksWithoutCustomModelDataAreNeverNamed() {
        ItemStack noCmd = new ItemStack(Material.NETHERRACK);
        noCmd.editMeta(meta -> meta.displayName(Component.text(COMPRESSED_NETHERRACK_NAME)));

        String message = plain(listener.blockedMessage(templateDuplicationGrid(noCmd)));

        assertFalse(message.contains(COMPRESSED_NETHERRACK_NAME),
                "CMD が無いスタックはカタログ品ではない(既存規約)のに名指ししている: " + message);
    }
}
