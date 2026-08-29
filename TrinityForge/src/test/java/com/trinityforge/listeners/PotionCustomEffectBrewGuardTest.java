package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.AlchemyQualityConfig;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * カスタム効果ポーションの醸造そのものを禁止する仕組み(2026-08-25 / W-116)。
 *
 * <h2>なぜ必要か</h2>
 * TF はカスタム効果ポーションを base {@code WATER} + custom effects で表現するが、
 * バニラの醸造表は「(ベースの種類, 素材) → 結果」でしか引かない。既に救済されている
 * 延長/強化/スプラッシュ化/残留化と、品質で倒したバニラ由来の反転以外の素材を入れると、
 * バニラが base を別のベースへ書き換えて<b>カスタム効果が丸ごと消える</b>。
 * 解放式カスタムの反転は意味定義せず、このガードで止める。
 */
class PotionCustomEffectBrewGuardTest {

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private BrewingStand stand;
    private PlayerStatAggregator aggregator;
    private AlchemyQualityConfig alchemyQuality;
    private NativeSkillCatalog progressionCatalog;
    private BrewOwnership ownership;

    private static final SkillCatalogEntry ALCHEMY_ENTRY = new SkillCatalogEntry(
            "ALCHEMY", 100, "1", level -> 1L, Map.of(),
            Map.of("alchemy.auto_mult", 0.25, "alchemy.manual_mult", 2.0, "alchemy.brew", 25.0));

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.BREWING_STAND);
        stand = (BrewingStand) block.getState();
        aggregator = mock(PlayerStatAggregator.class);
        alchemyQuality = mock(AlchemyQualityConfig.class);
        progressionCatalog = mock(NativeSkillCatalog.class);
        when(progressionCatalog.get(SkillId.ALCHEMY)).thenReturn(ALCHEMY_ENTRY);
        ownership = new BrewOwnership(plugin);

        PlayerCombatAggregate totals = mock(PlayerCombatAggregate.class);
        when(totals.totalOf("potion_quality_bonus")).thenReturn(0.0);
        when(totals.totalOf("brew_speed_bonus")).thenReturn(0.0);
        when(aggregator.aggregate(player)).thenReturn(totals);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void writeManualOwner(Player owner) {
        stand.getPersistentDataContainer().set(
                ownership.lastBrewerKey(), PersistentDataType.STRING, owner.getUniqueId().toString());
        stand.getPersistentDataContainer().set(
                ownership.brewModeKey(), PersistentDataType.STRING, BrewOwnership.MODE_MANUAL);
        stand.update();
    }

    private PotionQualityListener listener() {
        return new PotionQualityListener(plugin, aggregator, alchemyQuality, progressionCatalog);
    }

    private static ItemStack customEffectBottle(Material type, PotionEffectType effect) {
        ItemStack bottle = new ItemStack(type);
        PotionMeta meta = (PotionMeta) bottle.getItemMeta();
        meta.setBasePotionType(PotionType.WATER);
        meta.clearCustomEffects();
        meta.addCustomEffect(new PotionEffect(effect, 3600, 0), true);
        bottle.setItemMeta(meta);
        return bottle;
    }

    private BrewEvent brewEvent(List<ItemStack> results) {
        BrewerInventory inv = stand.getInventory();
        return new BrewEvent(stand.getBlock(), inv, results, 20);
    }

    @Test
    @DisplayName("カスタム効果ポーションに無関係な素材(SUGAR)を使うと醸造がキャンセルされる")
    void unrelatedIngredientCancelsTheBrewWhenACustomPotionIsLoaded() {
        writeManualOwner(player);
        stand.getInventory().setItem(0, customEffectBottle(Material.POTION, PotionEffectType.STRENGTH));
        stand.getInventory().setItem(3, new ItemStack(Material.SUGAR));

        BrewEvent event = brewEvent(new ArrayList<>(List.of(new ItemStack(Material.AIR))));
        listener().onBrew(event);

        assertTrue(event.isCancelled(), "カスタム効果ポーションが載っているのに無関係の素材で醸造が進んでしまっている");
    }

    @Test
    @DisplayName("カスタム効果を持たない素のポーション/水入り瓶は通常どおり醸造できる")
    void plainBottlesAreNotBlocked() {
        writeManualOwner(player);
        ItemStack plainWater = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) plainWater.getItemMeta();
        meta.setBasePotionType(PotionType.WATER);
        plainWater.setItemMeta(meta);
        stand.getInventory().setItem(0, plainWater);
        stand.getInventory().setItem(3, new ItemStack(Material.NETHER_WART));

        BrewEvent event = brewEvent(new ArrayList<>(List.of(new ItemStack(Material.AIR))));
        listener().onBrew(event);

        assertFalse(event.isCancelled(), "カスタム効果を持たない醸造まで塞いではいけない");
    }

    @Test
    @DisplayName("救済済みの延長(レッドストーン)はブロックされない(W-108を壊さない)")
    void redstoneExtensionIsStillRescuedNotBlocked() {
        writeManualOwner(player);
        stand.getInventory().setItem(0, customEffectBottle(Material.POTION, PotionEffectType.STRENGTH));
        stand.getInventory().setItem(3, new ItemStack(Material.REDSTONE));

        BrewEvent event = brewEvent(new ArrayList<>(List.of(new ItemStack(Material.AIR))));
        listener().onBrew(event);

        assertFalse(event.isCancelled(), "延長(レッドストーン)は救済対象であり、キャンセルしてはいけない");
    }

    @Test
    @DisplayName("解放式カスタムに発酵したクモの目を使うと醸造がキャンセルされる(反転しない)")
    void fermentedSpiderEyeOnTrueCustomPotionStaysBlocked() {
        writeManualOwner(player);
        stand.getInventory().setItem(0, customEffectBottle(Material.POTION, PotionEffectType.STRENGTH));
        stand.getInventory().setItem(3, new ItemStack(Material.FERMENTED_SPIDER_EYE));

        BrewEvent event = brewEvent(new ArrayList<>(List.of(new ItemStack(Material.AIR))));
        listener().onBrew(event);

        assertTrue(event.isCancelled(),
                "brew_source の無いカスタム効果ポーションまで反転救済してはいけない");
    }

    @Test
    @DisplayName("救済済みのスプラッシュ化(火薬)はブロックされない(既存救済を壊さない)")
    void gunpowderSplashIsStillRescuedNotBlocked() {
        writeManualOwner(player);
        stand.getInventory().setItem(0, customEffectBottle(Material.POTION, PotionEffectType.STRENGTH));
        stand.getInventory().setItem(3, new ItemStack(Material.GUNPOWDER));

        BrewEvent event = brewEvent(new ArrayList<>(List.of(new ItemStack(Material.AIR))));
        listener().onBrew(event);

        assertFalse(event.isCancelled(), "スプラッシュ化(火薬)は救済対象であり、キャンセルしてはいけない");
    }
}
