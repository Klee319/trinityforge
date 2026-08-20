package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.AlchemyQualityConfig;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>実サーバ報告（2026-08-20 / W-170）「醸造の見習い・調合の職人を解放後、耐火などの効果付きポーションを
 * 作ると入る職業経験値が全て一律になる」の回帰ガード。</b>
 *
 * <p><b>真因（機構レベル）</b>: {@link PotionQualityListener}(HIGH) は段階の違う効果を一意に確定させるため、
 * 品質が乗るポーションのベースを必ず {@code WATER} へ倒して全部カスタム効果で表現する。ところが EXP を出す
 * {@link NativeSkillExperienceListener#onBrew} は {@code MONITOR}（＝その<b>後</b>）で、完成品の
 * {@code getBasePotionType()} から {@code alchemy_progression.yml} の {@code brew_result} を引いていた。
 * 倒された後に読めるのは常に {@code WATER} で表に無いため、<b>どの効果ポーションを作っても
 * {@code alchemy_brew_exp} の定額へ落ちる</b> ＝ EXP が一律になる。
 *
 * <p><b>「解放後」に壊れる理由</b>: {@code potion_quality_bonus} が 0 のプレイヤーは
 * {@code applyQuality} に到達しないのでベースが倒れず、正しく {@code brew_result} を引ける。
 * つまり<b>スキルツリーで品質を取った人だけ</b>が壊れる（ログにも例外にも一切出ない無言死）。
 *
 * <p>直し方: 倒す直前の種別を {@link PdcKeys#ITEM_BREW_SOURCE_POTION} へ焼き付け、EXP 側はそれを最優先で読む。
 */
class AlchemyBrewResultExpTest {

    private static final String POTION_QUALITY_BONUS = StatKeys.canonical("potion_quality_bonus");

    /** 出荷 yml と同じ形: 効果ごとの brew_result 表 + 何も引けないときの定額 alchemy_brew_exp。 */
    private static final SkillCatalogEntry ALCHEMY_ENTRY = new SkillCatalogEntry(
            "ALCHEMY", 100, "1", level -> 1L,
            Map.of("brew_result.FIRE_RESISTANCE", 300.0, "brew_result.NIGHT_VISION", 450.0),
            Map.of("alchemy.brew", 150.0, "alchemy.manual_mult", 1.0, "alchemy.auto_mult", 0.25));

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private BrewingStand stand;
    private BrewOwnership ownership;
    private NativeExperienceDispatcher dispatcher;
    private NativeSkillExperienceListener expListener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.BREWING_STAND);
        stand = (BrewingStand) block.getState();
        ownership = new BrewOwnership(plugin);

        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.ALCHEMY)).thenReturn(ALCHEMY_ENTRY);
        dispatcher = mock(NativeExperienceDispatcher.class);
        expListener = new NativeSkillExperienceListener(
                plugin, dispatcher, catalog, mock(PlacedBlockTracker.class));

        stand.getPersistentDataContainer().set(
                ownership.lastBrewerKey(), PersistentDataType.STRING, player.getUniqueId().toString());
        stand.getPersistentDataContainer().set(
                ownership.brewModeKey(), PersistentDataType.STRING, BrewOwnership.MODE_MANUAL);
        stand.update();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------ EXP 側（読み取り）

    @Test
    @DisplayName("WATERへ倒された完成品でも、焼き付けた元の種別からbrew_resultを引く")
    void waterFlattenedResultStillResolvesItsOriginalPotionType() {
        expListener.onBrew(brewEventWith(flattenedPotion(PotionType.FIRE_RESISTANCE)));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ALCHEMY, 300.0);
    }

    @Test
    @DisplayName("印が無いWATER完成品は定額へ落ちる —— これがW-170の『一律』そのもの")
    void withoutTheStampAWaterFlattenedResultCollapsesToTheFlatFallback() {
        ItemStack unstamped = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) unstamped.getItemMeta();
        meta.setBasePotionType(PotionType.WATER);
        unstamped.setItemMeta(meta);

        expListener.onBrew(brewEventWith(unstamped));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ALCHEMY, 150.0);
    }

    @Test
    @DisplayName("品質0で倒されていない完成品は従来どおり base から引く")
    void plainResultStillUsesItsBasePotionType() {
        ItemStack plain = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) plain.getItemMeta();
        meta.setBasePotionType(PotionType.NIGHT_VISION);
        plain.setItemMeta(meta);

        expListener.onBrew(brewEventWith(plain));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ALCHEMY, 450.0);
    }

    // ------------------------------------------------------------------ 品質側（書き込み）

    @Test
    @DisplayName("品質リスナはWATERへ倒す【前】の種別を焼き付ける —— ここが欠けると上の読み取りが効かない")
    void qualityListenerStampsTheSourcePotionBeforeFlattening() {
        ItemStack potion = potionSpy(PotionType.FIRE_RESISTANCE,
                new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 3600, 0));
        BrewEvent event = brewEventWith(potion);

        qualityListener().onBrew(event);

        PotionMeta after = (PotionMeta) event.getResults().get(0).getItemMeta();
        assertNotNull(after);
        assertEquals(PotionType.WATER, after.getBasePotionType(),
                "品質が乗るときベースは WATER へ倒される(この前提が変わったらこのテストごと見直す)");
        assertEquals("FIRE_RESISTANCE",
                after.getPersistentDataContainer()
                        .get(PdcKeys.ITEM_BREW_SOURCE_POTION, PersistentDataType.STRING),
                "倒す前の種別が焼き付いていない。EXP 側が brew_result を引けず一律になる(W-170)");
    }

    // ------------------------------------------------------------------ helpers

    private PotionQualityListener qualityListener() {
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        PlayerCombatAggregate aggregate = mock(PlayerCombatAggregate.class);
        when(aggregate.totalOf(POTION_QUALITY_BONUS)).thenReturn(2.0);
        when(aggregator.aggregate(player)).thenReturn(aggregate);
        AlchemyQualityConfig quality = mock(AlchemyQualityConfig.class);
        when(quality.durationTicksPerQuality()).thenReturn(200.0);
        when(quality.amplifierPerQuality()).thenReturn(0.0);
        when(quality.lingeringSplashDurationTicksPerQuality()).thenReturn(0.0);
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.ALCHEMY)).thenReturn(ALCHEMY_ENTRY);
        return new PotionQualityListener(plugin, aggregator, quality, catalog);
    }

    /** 品質適用後の完成品と同じ形: ベースは WATER、元の種別は PDC に残っている。 */
    private static ItemStack flattenedPotion(PotionType original) {
        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) potion.getItemMeta();
        meta.setBasePotionType(PotionType.WATER);
        meta.getPersistentDataContainer()
                .set(PdcKeys.ITEM_BREW_SOURCE_POTION, PersistentDataType.STRING, original.name());
        potion.setItemMeta(meta);
        return potion;
    }

    /**
     * MockBukkit が {@code PotionMeta#getAllEffects()} を未実装（＝呼ぶとテストが FAILED でなく
     * <b>SKIPPED</b> に化ける）ため、そこだけ固定効果を返す spy で包む。
     * {@link PotionQualityListenerTest} と同じ回避策。
     */
    private static ItemStack potionSpy(PotionType base, PotionEffect effect) {
        ItemStack real = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) real.getItemMeta();
        meta.setBasePotionType(base);
        real.setItemMeta(meta);

        ItemStack itemSpy = spy(real);
        doAnswer(invocation -> {
            Object rawMeta = invocation.callRealMethod();
            if (rawMeta instanceof PotionMeta pm) {
                PotionMeta metaSpy = spy(pm);
                doReturn(List.of(effect)).when(metaSpy).getAllEffects();
                return metaSpy;
            }
            return rawMeta;
        }).when(itemSpy).getItemMeta();
        return itemSpy;
    }

    private BrewEvent brewEventWith(ItemStack result) {
        BrewerInventory inv = stand.getInventory();
        List<ItemStack> results = new ArrayList<>();
        results.add(result);
        return new BrewEvent(stand.getBlock(), inv, results, 20);
    }
}
