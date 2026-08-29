package com.trinityforge.listeners;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.SpecialRewardService;
import com.trinityforge.stats.ParticleSeedLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 刻印を消すシード({@code clears: true})と、刻印を lore に出す行 (2026-08-25 / W-221、ユーザー要望
 * 「デフォルトでシード埋め込みを消すシードが欲しい」「埋め込んでいるシードを lore に注入（日本語で表記）」)。
 *
 * <p>着手前は<b>付けたら二度と外せず、何が付いているのかを見る手段も無かった</b> ──
 * 刻印は道具の PDC にしか無いので、付けた本人ですら実際に殴って粒子を見るしか確認方法が無い。
 */
class ParticleSeedClearAndLoreTest {

    private static final String FLAME = "seed_flame";
    private static final String FROST = "seed_frost";
    private static final String CLEAR = "seed_clear";

    private ServerMock server;
    private SpecialRewardsConfig config;
    private SpecialRewardService rewards;
    private PlayerMock player;
    private AnvilInventory inventory;
    private PrepareAnvilEvent event;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        config = mock(SpecialRewardsConfig.class);
        rewards = mock(SpecialRewardService.class);
        player = server.addPlayer();

        Map<String, SpecialRewardsConfig.ParticleSeed> seeds = new LinkedHashMap<>();
        seeds.put(FLAME, new SpecialRewardsConfig.ParticleSeed(
                FLAME, Material.BLAZE_POWDER.name(), Particle.FLAME, 6, "焔", false));
        seeds.put(FROST, new SpecialRewardsConfig.ParticleSeed(
                FROST, Material.BLUE_ICE.name(), Particle.SNOWFLAKE, 6, "氷華", false));
        seeds.put(CLEAR, new SpecialRewardsConfig.ParticleSeed(
                CLEAR, Material.INK_SAC.name(), null, 1, "消去", true));
        when(config.particleSeeds()).thenReturn(seeds);
        when(rewards.isUnlocked(any(), any())).thenReturn(true);

        inventory = mock(AnvilInventory.class);
        AnvilView view = mock(AnvilView.class);
        when(view.getPlayer()).thenReturn(player);
        event = mock(PrepareAnvilEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getView()).thenReturn(view);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void place(ItemStack tool, ItemStack material) {
        when(inventory.getFirstItem()).thenReturn(tool);
        when(inventory.getSecondItem()).thenReturn(material);
    }

    private static ItemStack seededPickaxe(String seedId) {
        ItemStack tool = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = tool.getItemMeta();
        ItemData.of(meta).setParticleSeed(seedId);
        tool.setItemMeta(meta);
        return tool;
    }

    private static List<String> loreText(ItemStack stack) {
        List<Component> lore = stack.getItemMeta().lore();
        return lore == null ? List.of()
                : lore.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }

    private InventoryClickEvent resultClick() {
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getRawSlot()).thenReturn(ParticleSeedListener.ANVIL_RESULT_SLOT);
        when(click.getInventory()).thenReturn(inventory);
        when(click.getWhoClicked()).thenReturn(player);
        when(click.getClick()).thenReturn(ClickType.LEFT);
        return click;
    }

    private ItemStack prepareResult() {
        new ParticleSeedListener(config, rewards).onPrepareAnvil(event);
        ArgumentCaptor<ItemStack> captor = ArgumentCaptor.forClass(ItemStack.class);
        verify(event, times(1)).setResult(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("付与すると刻印の日本語名が lore に出る")
    void applyingASeedWritesItsJapaneseNameIntoTheLore() {
        place(new ItemStack(Material.DIAMOND_PICKAXE), new ItemStack(Material.BLAZE_POWDER));

        ItemStack result = prepareResult();

        assertEquals(Optional.of(FLAME), ItemData.of(result.getItemMeta()).particleSeed());
        assertTrue(loreText(result).contains("粒子: 焔"),
                "lore に日本語名が出ていない: " + loreText(result));
    }

    @Test
    @DisplayName("付け替えると前のシードの行は残らない(1行だけ)")
    void replacingASeedDoesNotLeaveTheOldLine() {
        place(seededPickaxe(FLAME), new ItemStack(Material.BLUE_ICE));

        ItemStack result = prepareResult();

        List<String> lore = loreText(result);
        assertEquals(List.of("粒子: 氷華"), lore, "前の「焔」の行が残っている: " + lore);
    }

    @Test
    @DisplayName("消すシードは解放していなくても使え、刻印と lore の行の両方が消える")
    void theClearingSeedStripsBothThePdcAndTheLoreLine() {
        // 解放判定を全部 false にしても使えること(消す操作は報酬ではない)。
        when(rewards.isUnlocked(any(), any())).thenReturn(false);
        place(seededPickaxe(FLAME), new ItemStack(Material.INK_SAC));

        ItemStack result = prepareResult();

        assertTrue(ItemData.of(result.getItemMeta()).particleSeed().isEmpty(), "刻印が残っている");
        assertFalse(loreText(result).stream().anyMatch(line -> line.startsWith(ParticleSeedLore.PREFIX)),
                "lore の粒子行が残っている: " + loreText(result));
    }

    @Test
    @DisplayName("何も刻印されていない道具に消すシードは効かない(素材とレベルを無駄にしない)")
    void theClearingSeedIsNotOfferedForAnUnseededTool() {
        place(new ItemStack(Material.DIAMOND_PICKAXE), new ItemStack(Material.INK_SAC));

        new ParticleSeedListener(config, rewards).onPrepareAnvil(event);

        verify(event, never()).setResult(any());
    }

    @Test
    @DisplayName("消すシードで取り出すと素材は1個だけ消え、道具が返る")
    void takingTheClearedToolConsumesOneInkSac() {
        place(seededPickaxe(FLAME), new ItemStack(Material.INK_SAC, 16));
        player.setLevel(5);

        InventoryClickEvent click = resultClick();
        new ParticleSeedListener(config, rewards).onAnvilResultTake(click);

        verify(click).setCancelled(true);
        ArgumentCaptor<ItemStack> left = ArgumentCaptor.forClass(ItemStack.class);
        verify(inventory).setSecondItem(left.capture());
        assertEquals(15, left.getValue().getAmount());
        assertTrue(ItemData.of(player.getItemOnCursor().getItemMeta()).particleSeed().isEmpty(),
                "カーソルへ返った道具から刻印が消えていない");
    }

    /**
     * 表示名を変えても古い行が残らないこと。行の除去を「その名前の行と構造が一致するか」で
     * 書くと、{@code display} を変えた瞬間に古い行が永久に残る(それが表示名を変える理由なのに)。
     */
    @Test
    @DisplayName("表示名を変えた後でも、前の名前の行は回収される")
    void aRenamedSeedStillReplacesTheLineWrittenUnderItsOldName() {
        ItemStack tool = seededPickaxe(FLAME);
        ItemMeta meta = tool.getItemMeta();
        meta.lore(List.of(Component.text(ParticleSeedLore.PREFIX + "むかしの名前")));
        tool.setItemMeta(meta);
        place(tool, new ItemStack(Material.BLUE_ICE));

        ItemStack result = prepareResult();

        assertEquals(List.of("粒子: 氷華"), loreText(result));
    }
}
