package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.AlchemyQualityConfig;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
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
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * {@link PotionQualityListener}: potion_quality_bonus stat による効果時間/強度の換算
 * (品質1あたりの時間・強度をconfig駆動で加算し、強度は切り捨てで整数化)と、
 * 所有者PDCの読み取り順序({@link BrewOwnership} javadoc参照)を検証する。
 *
 * <p><b>MockBukkit回避策</b>: {@link PotionQualityListener#applyQuality} は
 * {@code PotionMeta#getAllEffects()} を呼ぶが、MockBukkitはこのメソッドを未実装で
 * {@code UnimplementedOperationException}(= {@code TestAbortedException} 継承のためJUnitは
 * FAILEDでなくSKIPPEDにする)を投げる。品質が実際に適用される全テストはこの呼び出しに到達するため、
 * 何も対策しないと肝心のアサーションが1つも実行されずに「緑」に見えてしまう。対策として、テスト用の
 * ポーション{@link ItemStack}を{@link #potionSpyItem}で包み、{@code getItemMeta()}が返す
 * {@link PotionMeta}を{@link org.mockito.Mockito#spy}し、{@code getAllEffects()}だけを実装済みの
 * {@code getCustomEffects()}に委譲させる。ここではbasePotionTypeを常に{@code WATER}(素の効果を持たない)
 * に設定しているため、{@code getAllEffects()}(= base効果 + custom効果)と{@code getCustomEffects()}は
 * 常に同じ値になり、挙動を弱めずに済む。
 */
class PotionQualityListenerTest {

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private BrewingStand stand;
    private PlayerStatAggregator aggregator;
    private AlchemyQualityConfig alchemyQuality;
    private NativeSkillCatalog progressionCatalog;
    private BrewOwnership ownership;

    /** alchemy.auto_mult 相当。25% damping. */
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

    private void writeAutomatedOwner(Player owner) {
        stand.getPersistentDataContainer().set(
                ownership.lastBrewerKey(), PersistentDataType.STRING, owner.getUniqueId().toString());
        stand.getPersistentDataContainer().set(
                ownership.brewModeKey(), PersistentDataType.STRING, BrewOwnership.MODE_AUTO);
        stand.update();
    }

    private void stubQuality(double points) {
        PlayerCombatAggregate totals = mock(PlayerCombatAggregate.class);
        when(totals.totalOf("potion_quality_bonus")).thenReturn(points);
        when(totals.totalOf("brew_speed_bonus")).thenReturn(0.0);
        when(aggregator.aggregate(player)).thenReturn(totals);
    }

    private ItemStack healingPotion() {
        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) potion.getItemMeta();
        meta.setBasePotionType(PotionType.WATER);
        meta.clearCustomEffects();
        meta.addCustomEffect(new PotionEffect(PotionEffectType.INSTANT_HEALTH, 1, 0), true);
        potion.setItemMeta(meta);
        return potionSpyItem(potion);
    }

    private ItemStack strengthPotion(int durationTicks, int amplifier) {
        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) potion.getItemMeta();
        meta.setBasePotionType(PotionType.WATER);
        meta.clearCustomEffects();
        meta.addCustomEffect(new PotionEffect(PotionEffectType.STRENGTH, durationTicks, amplifier), true);
        potion.setItemMeta(meta);
        return potionSpyItem(potion);
    }

    /**
     * MockBukkitが未実装の{@code PotionMeta#getAllEffects()}を、実装済みの{@code getCustomEffects()}へ
     * 委譲するspyでラップする(クラスjavadoc参照)。basePotionTypeは常にWATERのため両者は等価。
     */
    private ItemStack potionSpyItem(ItemStack real) {
        ItemStack itemSpy = spy(real);
        doAnswer(invocation -> {
            Object rawMeta = invocation.callRealMethod();
            if (rawMeta instanceof PotionMeta pm) {
                PotionMeta metaSpy = spy(pm);
                doReturn(pm.getCustomEffects()).when(metaSpy).getAllEffects();
                return metaSpy;
            }
            return rawMeta;
        }).when(itemSpy).getItemMeta();
        return itemSpy;
    }

    private BrewEvent brewEvent(List<ItemStack> results) {
        BrewerInventory inv = stand.getInventory();
        return new BrewEvent(stand.getBlock(), inv, results, 20);
    }

    @Test
    void zeroQualityLeavesPotionUntouched() {
        writeManualOwner(player);
        stubQuality(0.0);
        when(alchemyQuality.durationTicksPerQuality()).thenReturn(20.0);
        when(alchemyQuality.amplifierPerQuality()).thenReturn(0.5);
        when(alchemyQuality.lingeringSplashDurationTicksPerQuality()).thenReturn(10.0);
        PotionQualityListener listener = new PotionQualityListener(plugin, aggregator, alchemyQuality, progressionCatalog);

        List<ItemStack> results = new ArrayList<>();
        results.add(strengthPotion(3600, 0));
        listener.onBrew(brewEvent(results));

        PotionMeta meta = (PotionMeta) results.get(0).getItemMeta();
        PotionEffect effect = meta.getCustomEffects().get(0);
        assertEquals(3600, effect.getDuration());
        assertEquals(0, effect.getAmplifier());
    }

    @Test
    void qualityAddsDurationAndFlooredAmplifier() {
        writeManualOwner(player);
        // 2品質ポイント: amplifier-per-quality=0.5 -> floor(0.5*2)=1 (2品質ごとに+1という設計を固定)。
        stubQuality(2.0);
        when(alchemyQuality.durationTicksPerQuality()).thenReturn(20.0);
        when(alchemyQuality.amplifierPerQuality()).thenReturn(0.5);
        when(alchemyQuality.lingeringSplashDurationTicksPerQuality()).thenReturn(10.0);
        PotionQualityListener listener = new PotionQualityListener(plugin, aggregator, alchemyQuality, progressionCatalog);

        List<ItemStack> results = new ArrayList<>();
        results.add(strengthPotion(3600, 0));
        listener.onBrew(brewEvent(results));

        PotionMeta meta = (PotionMeta) results.get(0).getItemMeta();
        PotionEffect effect = meta.getCustomEffects().get(0);
        assertEquals(3640, effect.getDuration(), "3600 + 20*2 quality points");
        assertEquals(1, effect.getAmplifier(), "floor(0.5 * 2 quality points) == 1 (2品質ごとに+1)");
    }

    /**
     * 2026-08-18 実サーバ報告「進捗バーも動いて完了音も鳴るのに、出てくるのが水入り瓶」の再発防止。
     *
     * <p>品質を適用するときに base を {@code WATER} へ倒すのは効果を一意に確定させるために必要だが、
     * <b>Minecraft のポーション名はベースの種類からしか引かれない</b>ため、名前を焼き直さないと
     * 全ての完成品が画面上「水入り瓶」になる。効果は正しく付いたままなので<b>ログにも例外にも出ない</b>。
     *
     * <p>このクラスの他のテストは(MockBukkit 回避のため)最初から base を {@code WATER} にした
     * ポーションを渡しているので、<b>名前の破壊を構造的に観測できなかった</b>。
     * ここだけは明示的に名前をアサートする。
     */
    @Test
    void 品質適用後のポーションは水入り瓶のままにならず効果名が付く() {
        writeManualOwner(player);
        stubQuality(2.0);
        when(alchemyQuality.durationTicksPerQuality()).thenReturn(20.0);
        when(alchemyQuality.amplifierPerQuality()).thenReturn(0.5);
        when(alchemyQuality.lingeringSplashDurationTicksPerQuality()).thenReturn(10.0);
        PotionQualityListener listener = new PotionQualityListener(plugin, aggregator, alchemyQuality, progressionCatalog);

        List<ItemStack> results = new ArrayList<>();
        results.add(strengthPotion(3600, 0));
        listener.onBrew(brewEvent(results));

        Component name = results.get(0).getItemMeta().displayName();
        assertNotNull(name, "表示名が付いていない(= 画面上は『水入り瓶』のまま)");
        assertTrue(PlainTextComponentSerializer.plainText().serialize(name).endsWith("のポーション"),
                "実際の名前: " + PlainTextComponentSerializer.plainText().serialize(name));
    }

    @Test
    void oddQualityPointFloorsAmplifierDown() {
        writeManualOwner(player);
        // 1品質ポイント: floor(0.5*1)=0 -> まだ強度は上がらない(2品質ごとに+1の"1品質目"を確認)。
        stubQuality(1.0);
        when(alchemyQuality.durationTicksPerQuality()).thenReturn(20.0);
        when(alchemyQuality.amplifierPerQuality()).thenReturn(0.5);
        when(alchemyQuality.lingeringSplashDurationTicksPerQuality()).thenReturn(10.0);
        PotionQualityListener listener = new PotionQualityListener(plugin, aggregator, alchemyQuality, progressionCatalog);

        List<ItemStack> results = new ArrayList<>();
        results.add(strengthPotion(1000, 2));
        listener.onBrew(brewEvent(results));

        PotionMeta meta = (PotionMeta) results.get(0).getItemMeta();
        PotionEffect effect = meta.getCustomEffects().get(0);
        assertEquals(0, effect.getAmplifier() - 2, "floor(0.5 * 1) == 0, amplifier unchanged at odd quality");
    }

    @Test
    void instantEffectsAreNotGivenABonusDuration() {
        writeManualOwner(player);
        stubQuality(5.0);
        when(alchemyQuality.durationTicksPerQuality()).thenReturn(20.0);
        when(alchemyQuality.amplifierPerQuality()).thenReturn(0.0);
        when(alchemyQuality.lingeringSplashDurationTicksPerQuality()).thenReturn(0.0);
        PotionQualityListener listener = new PotionQualityListener(plugin, aggregator, alchemyQuality, progressionCatalog);

        List<ItemStack> results = new ArrayList<>();
        results.add(healingPotion());
        listener.onBrew(brewEvent(results));

        PotionMeta meta = (PotionMeta) results.get(0).getItemMeta();
        PotionEffect effect = meta.getCustomEffects().get(0);
        assertEquals(1, effect.getDuration(), "instant effects keep their (irrelevant) 1-tick duration untouched");
    }

    @Test
    void unownedStandIsUntouched() {
        // Never written -> no owner.
        stubQuality(5.0);
        PotionQualityListener listener = new PotionQualityListener(plugin, aggregator, alchemyQuality, progressionCatalog);

        List<ItemStack> results = new ArrayList<>();
        results.add(strengthPotion(1000, 0));
        listener.onBrew(brewEvent(results));

        PotionMeta meta = (PotionMeta) results.get(0).getItemMeta();
        assertEquals(1000, meta.getCustomEffects().get(0).getDuration());
    }

    @Test
    void otherPlayerNearbyDoesNotGetBonus_onlyTheRecordedOwnerDoes() {
        Player bystander = server.addPlayer();
        writeManualOwner(player); // owner = player, not bystander
        stubQuality(0.0); // player has no quality
        PlayerCombatAggregate bystanderTotals = mock(PlayerCombatAggregate.class);
        when(bystanderTotals.totalOf("potion_quality_bonus")).thenReturn(10.0); // bystander WOULD get a huge bonus
        when(aggregator.aggregate(bystander)).thenReturn(bystanderTotals);
        when(alchemyQuality.durationTicksPerQuality()).thenReturn(20.0);
        when(alchemyQuality.amplifierPerQuality()).thenReturn(0.5);
        PotionQualityListener listener = new PotionQualityListener(plugin, aggregator, alchemyQuality, progressionCatalog);

        List<ItemStack> results = new ArrayList<>();
        results.add(strengthPotion(1000, 0));
        listener.onBrew(brewEvent(results));

        PotionMeta meta = (PotionMeta) results.get(0).getItemMeta();
        assertEquals(1000, meta.getCustomEffects().get(0).getDuration(),
                "only the recorded owner's stat may affect the brew, never a bystander's");
    }

    @Test
    void automatedBrewIsDampedByAutoMult() {
        writeAutomatedOwner(player);
        stubQuality(4.0); // damped by 0.25 -> effective 1.0 quality point
        when(alchemyQuality.durationTicksPerQuality()).thenReturn(20.0);
        when(alchemyQuality.amplifierPerQuality()).thenReturn(1.0);
        when(alchemyQuality.lingeringSplashDurationTicksPerQuality()).thenReturn(0.0);
        PotionQualityListener listener = new PotionQualityListener(plugin, aggregator, alchemyQuality, progressionCatalog);

        List<ItemStack> results = new ArrayList<>();
        results.add(strengthPotion(1000, 0));
        listener.onBrew(brewEvent(results));

        PotionMeta meta = (PotionMeta) results.get(0).getItemMeta();
        PotionEffect effect = meta.getCustomEffects().get(0);
        assertEquals(1020, effect.getDuration(), "4 quality points * 0.25 auto_mult damping == 1 effective point");
        assertEquals(1, effect.getAmplifier());
    }

    /**
     * 順序依存が無いことの証明: {@link NativeSkillExperienceListener#onBrew} と
     * {@link PotionQualityListener#onBrew} の両方を実際のBukkitイベントバスへ登録し、
     * わざと NativeSkillExperienceListener を先に登録しても(=登録順ではPotionQualityListenerが後)、
     * HIGH優先度のPotionQualityListenerがMONITOR優先度のクリアより必ず先に実行されるため、
     * 品質ボーナスが正しく反映されることを確認する。
     */
    @Test
    void potionQualityAppliesBeforeOwnerPdcIsClearedRegardlessOfRegistrationOrder() {
        writeManualOwner(player);
        stubQuality(2.0);
        when(alchemyQuality.durationTicksPerQuality()).thenReturn(20.0);
        when(alchemyQuality.amplifierPerQuality()).thenReturn(0.0);
        when(alchemyQuality.lingeringSplashDurationTicksPerQuality()).thenReturn(0.0);

        PotionQualityListener qualityListener =
                new PotionQualityListener(plugin, aggregator, alchemyQuality, progressionCatalog);
        // A minimal MONITOR-priority listener standing in for NativeSkillExperienceListener#onBrew's
        // "clear the owner PDC after this brew" behaviour, registered BEFORE the quality listener so a
        // same-priority/registration-order assumption would have it run first (and thus clear before
        // the quality listener reads) — proving the ordering guarantee comes from EventPriority, not
        // registration order.
        Listener clearer = new Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
            public void onBrew(BrewEvent event) {
                if (event.getBlock().getState() instanceof BrewingStand s) {
                    s.getPersistentDataContainer().remove(ownership.lastBrewerKey());
                    s.getPersistentDataContainer().remove(ownership.brewModeKey());
                    s.update();
                }
            }
        };
        server.getPluginManager().registerEvents(clearer, plugin);
        server.getPluginManager().registerEvents(qualityListener, plugin);

        List<ItemStack> results = new ArrayList<>();
        results.add(strengthPotion(1000, 0));
        BrewEvent event = brewEvent(results);
        server.getPluginManager().callEvent(event);

        PotionMeta meta = (PotionMeta) results.get(0).getItemMeta();
        assertEquals(1040, meta.getCustomEffects().get(0).getDuration(),
                "HIGH-priority quality listener must read the owner before the MONITOR-priority clear runs");
        assertTrue(ownership.ownerOf(stand).isEmpty(), "the clearer still ran afterward and removed the owner PDC");
    }
}
