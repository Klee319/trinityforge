package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.RecipeSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * U4「カスタム装備のエンチャントがはがせない」の回帰テスト。
 *
 * <p>このクラスは元々存在せず、{@link GrindstonePreserveListener} が
 * {@link CatalogVanillaOperationGuardListener}(HIGHEST で結果枠を null にしていた)によって
 * 完全な死にコードになっていたことを誰も検知できなかった。縛る対象:
 * <ol>
 *   <li>除去後も表示名・lore・TF 付与エンチャント(= 派生ステの見える形)が残る</li>
 *   <li>rollSeed / quality / 耐久 / 支払い済み PDC が変わらない
 *       (rollSeed を引き直したら砥石が厳選ロールのガチャになる)</li>
 *   <li>外せるエンチャントが1つも無いときは結果枠を出さない(砥石を無限EXP源にしない)</li>
 * </ol>
 *
 * <p>{@link ItemAssembler} は {@code ItemFactoryTest} と同じ理由でモックにしている
 * (実物は MockBukkit 未実装の {@code Material#getDefaultAttributeModifiers} を踏む)。
 * 代わりに「rollSeed/quality を刻印し、lore を書き、TF 付与エンチャントを1つ付ける」という
 * 再組み立ての最小再現を仕込み、リスナーが本当に再組み立て経路を通ったかを観測する。
 */
class GrindstonePreserveListenerTest {

    private static final Component RESTORED_LORE = Component.text("攻撃力 +12");
    private static final long ORIGINAL_ROLL_SEED = 1234567890123L;
    private static final int ORIGINAL_QUALITY = 7;

    /** 静的初期化で {@code Enchantment.X} を触ると MockBukkit.mock() 前にレジストリを読んで落ちる。 */
    private Enchantment tfGranted;
    private Enchantment playerApplied;

    private ItemAssembler assembler;
    private ItemFactory itemFactory;
    private Map<String, ItemTemplate> templates;
    private ItemTemplate blade;
    private GrindstonePreserveListener listener;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        tfGranted = Enchantment.EFFICIENCY;
        playerApplied = Enchantment.SHARPNESS;
        assembler = mock(ItemAssembler.class);
        // 実 ItemAssembler の代役: rollSeed/quality を刻印し、lore と TF 付与エンチャントを復元する。
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenAnswer(invocation -> {
            ItemMeta meta = invocation.getArgument(0, ItemMeta.class);
            ItemData data = ItemData.of(meta);
            data.setRollSeed(invocation.getArgument(2, Long.class));
            data.setQuality(invocation.getArgument(3, Integer.class));
            meta.lore(List.of(RESTORED_LORE));
            meta.addEnchant(tfGranted, 3, true);
            return 1;
        });
        itemFactory = new ItemFactory(assembler);
        blade = new ItemTemplate("guard_blade", Material.DIAMOND_SWORD, "<gold>守護の刃</gold>", 111,
                BindType.TRADEABLE, 0, null);
        templates = new HashMap<>();
        templates.put(blade.id(), blade);
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.all()).thenReturn(templates);
        when(catalog.template(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(templates.get(invocation.getArgument(0, String.class))));
        listener = new GrindstonePreserveListener(catalog, itemFactory);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pureRemovalKeepsNameLoreAndTfEnchantWhileDroppingThePlayerEnchant() {
        ItemStack input = tfBlade();
        input.addUnsafeEnchantment(playerApplied, 4);
        PrepareGrindstoneEvent event = grindstoneEvent(input, null, strippedByVanilla(input));

        listener.onPrepareGrindstone(event);

        ItemStack restored = capturedResult(event);
        assertNotNull(restored, "純粋なエンチャント除去は結果枠を消してはいけない");
        ItemMeta meta = restored.getItemMeta();
        assertEquals(plain(input.getItemMeta().displayName()), plain(meta.displayName()),
                "砥石が剥がした表示名は復元されなければならない");
        assertNotNull(meta.lore(), "砥石が剥がした lore(= 派生ステの表示)は復元されなければならない");
        assertEquals(List.of(plain(RESTORED_LORE)), meta.lore().stream().map(GrindstonePreserveListenerTest::plain).toList());
        assertEquals(3, restored.getEnchantmentLevel(tfGranted),
                "TF が付与した tool-enchant は再組み立てで戻る");
        assertEquals(0, restored.getEnchantmentLevel(playerApplied),
                "プレイヤーが付けたエンチャントは外れていなければならない(U4 の本体)");
    }

    @Test
    void pureRemovalReusesTheOriginalRollSeedAndQuality() {
        ItemStack input = tfBlade();
        input.addUnsafeEnchantment(playerApplied, 4);
        PrepareGrindstoneEvent event = grindstoneEvent(input, null, strippedByVanilla(input));

        listener.onPrepareGrindstone(event);

        verify(assembler, never()).assemble(any(), any(), longThat(seed -> seed != ORIGINAL_ROLL_SEED), anyInt());
        ItemData data = ItemData.of(capturedResult(event).getItemMeta());
        assertEquals(ORIGINAL_ROLL_SEED, data.rollSeed().orElseThrow(),
                "rollSeed を引き直すと砥石が厳選ロールのガチャになる");
        assertEquals(ORIGINAL_QUALITY, data.quality(), "品質はリセットしない");
    }

    @Test
    void pureRemovalKeepsDurabilityAndPaidPdc() {
        ItemStack input = tfBlade();
        input.addUnsafeEnchantment(playerApplied, 4);
        ItemMeta inputMeta = input.getItemMeta();
        ((Damageable) inputMeta).setDamage(400);
        ItemData paid = ItemData.of(inputMeta);
        paid.setOwner(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
        paid.setRitualThreadSlotBonus(2);
        input.setItemMeta(inputMeta);
        PrepareGrindstoneEvent event = grindstoneEvent(input, null, strippedByVanilla(input));

        listener.onPrepareGrindstone(event);

        ItemStack restored = capturedResult(event);
        assertEquals(400, ((Damageable) restored.getItemMeta()).getDamage(),
                "砥石は修理道具ではない: 耐久はリセットしない");
        ItemData data = ItemData.of(restored.getItemMeta());
        assertTrue(data.owner().isPresent(), "SOULBOUND の所有者が消えると砥石が魂縛外しになる");
        assertEquals(2, data.ritualThreadSlotBonus(), "儀式で支払ったスレッド枠加算を失わせない");
    }

    @Test
    void nothingRemovableClearsTheResultSoTheGrindstoneIsNotAnInfiniteExpSource() {
        // TF 付与エンチャントしか無い = プレイヤーが外せるものは1つも無い。
        ItemStack input = tfBlade();
        PrepareGrindstoneEvent event = grindstoneEvent(input, null, strippedByVanilla(input));

        listener.onPrepareGrindstone(event);

        verify(event).setResult(null);
    }

    @Test
    void nonTfItemIsLeftToVanilla() {
        ItemStack plainSword = new ItemStack(Material.DIAMOND_SWORD);
        plainSword.addUnsafeEnchantment(playerApplied, 4);
        PrepareGrindstoneEvent event = grindstoneEvent(plainSword, null, strippedByVanilla(plainSword));

        listener.onPrepareGrindstone(event);

        verify(event, never()).setResult(any());
    }

    @Test
    void emptyVanillaResultIsNotRevived() {
        PrepareGrindstoneEvent event = grindstoneEvent(tfBlade(), null, null);

        listener.onPrepareGrindstone(event);

        verify(event, never()).setResult(any());
    }

    @Test
    void enchantGlowIsRestoredSoGrindingDoesNotPermanentlyDullTheItem() {
        ItemTemplate glowing = new ItemTemplate("glow_blade", Material.DIAMOND_SWORD, "<gold>光の刃</gold>", 112,
                BindType.TRADEABLE, 0, null, List.of(), (RecipeSpec) null, null, true);
        templates.put(glowing.id(), glowing);
        ItemStack input = itemFactory.create(glowing, ORIGINAL_ROLL_SEED, ORIGINAL_QUALITY);
        input.addUnsafeEnchantment(playerApplied, 4);
        PrepareGrindstoneEvent event = grindstoneEvent(input, null, strippedByVanilla(input));

        listener.onPrepareGrindstone(event);

        ItemStack restored = capturedResult(event);
        assertEquals(0, restored.getEnchantmentLevel(playerApplied));
        assertEquals(3, restored.getEnchantmentLevel(tfGranted),
                "TF が付与した tool-enchant は再組み立てで戻る");
        assertTrue(hasGlowPresentation(restored.getItemMeta()),
                "enchant-glow の光沢は砥石通過で永久に消えてはいけない");
        assertFalse(glintHidesEnchants(restored.getItemMeta()),
                "光沢の復元が HIDE_ENCHANTS を立てると本物のエンチャントがツールチップに出ない");
    }

    @Test
    void turningCatalogGlowOffClearsLeftoverHideSoNewEnchantsCanShow() {
        ItemTemplate glowing = new ItemTemplate("glow_blade", Material.DIAMOND_SWORD, "<gold>光の刃</gold>", 112,
                BindType.TRADEABLE, 0, null, List.of(), (RecipeSpec) null, null, true);
        templates.put(glowing.id(), glowing);
        ItemStack input = itemFactory.create(glowing, ORIGINAL_ROLL_SEED, ORIGINAL_QUALITY);
        input.addUnsafeEnchantment(playerApplied, 4);
        ItemMeta leftover = input.getItemMeta();
        leftover.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        input.setItemMeta(leftover);
        templates.put(glowing.id(), new ItemTemplate("glow_blade", Material.DIAMOND_SWORD, "<gold>光の刃</gold>", 112,
                BindType.TRADEABLE, 0, null, List.of(), (RecipeSpec) null, null, false));

        PrepareGrindstoneEvent event = grindstoneEvent(input, null, strippedByVanilla(input));

        listener.onPrepareGrindstone(event);

        ItemStack restored = capturedResult(event);
        assertFalse(restored.getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS),
                "カタログで glow を切ったあと砥石に通すと、残った HIDE_ENCHANTS を落とさなければならない");
        assertEquals(0, restored.getEnchantmentLevel(playerApplied));
        assertEquals(3, restored.getEnchantmentLevel(tfGranted));
    }

    private static boolean hasGlowPresentation(ItemMeta meta) {
        try {
            if (Boolean.TRUE.equals(meta.getEnchantmentGlintOverride())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return meta.hasEnchants() && meta.getItemFlags().contains(ItemFlag.HIDE_ENCHANTS);
    }

    private static boolean glintHidesEnchants(ItemMeta meta) {
        try {
            return Boolean.TRUE.equals(meta.getEnchantmentGlintOverride())
                    && meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** カタログ由来の TF 装備(rollSeed/quality 刻印済み)。 */
    private ItemStack tfBlade() {
        return itemFactory.create(blade, ORIGINAL_ROLL_SEED, ORIGINAL_QUALITY);
    }

    /**
     * バニラの砥石結果の再現: エンチャントを全部落とし、表示名/lore も剥がされた最悪ケース
     * (PDC と耐久だけは残る)を作る。復元は「バニラが何を残したか」に依存しないことを確かめたいので、
     * ここでは剥がす側に振っておく。
     */
    private static ItemStack strippedByVanilla(ItemStack input) {
        ItemStack result = input.clone();
        ItemMeta meta = result.getItemMeta();
        for (Enchantment enchantment : Map.copyOf(meta.getEnchants()).keySet()) {
            meta.removeEnchant(enchantment);
        }
        meta.displayName(null);
        meta.lore(null);
        result.setItemMeta(meta);
        return result;
    }

    private static PrepareGrindstoneEvent grindstoneEvent(ItemStack upper, ItemStack lower, ItemStack result) {
        GrindstoneInventory grindstone = mock(GrindstoneInventory.class);
        when(grindstone.getItem(0)).thenReturn(upper);
        when(grindstone.getItem(1)).thenReturn(lower);
        PrepareGrindstoneEvent event = mock(PrepareGrindstoneEvent.class);
        when(event.getInventory()).thenReturn(grindstone);
        when(event.getResult()).thenReturn(result);
        return event;
    }

    private static ItemStack capturedResult(PrepareGrindstoneEvent event) {
        ArgumentCaptor<ItemStack> captor = ArgumentCaptor.forClass(ItemStack.class);
        verify(event).setResult(captor.capture());
        return captor.getValue();
    }

    private static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }
}
