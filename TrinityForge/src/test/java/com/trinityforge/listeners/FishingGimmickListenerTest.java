package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FishingGimmickConfig;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DropTableConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FishingGimmickListener}: fallback mode (empty {@code fishing.groups}) keeps the pre-2026-07-23
 * junk-to-scrap/legacy-material-list behavior untouched, and table mode (§2.3/§4) replaces the catch with
 * a drawn drop-table entry and stamps the treasure/junk PDC flag {@link FishingQualityListener} reads.
 */
class FishingGimmickListenerTest {

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private FishingGimmickConfig gimmickConfig;
    private CrossPluginItemResolver itemResolver;
    private PlayerStatAggregator aggregator;
    private NamespacedKey treasureFlagKey;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        gimmickConfig = mock(FishingGimmickConfig.class);
        itemResolver = mock(CrossPluginItemResolver.class);
        aggregator = mock(PlayerStatAggregator.class);
        treasureFlagKey = new NamespacedKey("trinityforge", "fishing_table_treasure");

        PlayerCombatAggregate agg = mock(PlayerCombatAggregate.class);
        when(agg.totalOf(any())).thenReturn(0.0);
        // 2026-08-13バグ修正: luckTotalOf が2引数版から3引数版 aggregate(player, rod, rodFromOffhand) へ
        // 変わったため、3引数版もスタブする(未スタブだと Mockito が null を返し agg.totalOf で NPE)。
        // 2引数版の呼び出し経路は本テストクラス内に残っていないが、他コードパスとの取り違え防止のため
        // 消さずに両方スタブしておく。
        when(aggregator.aggregate(any(), any())).thenReturn(agg);
        when(aggregator.aggregate(any(), any(), anyBoolean())).thenReturn(agg);

        when(dedicatedEffects.dropGatePerks()).thenReturn(Map.of());
        when(dedicatedEffects.isActive(any(), eq("junk-to-scrap"))).thenReturn(false);
        when(gimmickConfig.fishingSkillId()).thenReturn("FISHING");
        when(gimmickConfig.luckPerLevel()).thenReturn(0.0);

        player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.FISHING_ROD));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private FishingGimmickListener listener() {
        return new FishingGimmickListener(dedicatedEffects, gimmickConfig, itemResolver, aggregator,
                SkillLevelSource.EMPTY, treasureFlagKey);
    }

    private PlayerFishEvent fishEvent(Item caught) {
        PlayerFishEvent event = mock(PlayerFishEvent.class);
        when(event.getState()).thenReturn(PlayerFishEvent.State.CAUGHT_FISH);
        when(event.getPlayer()).thenReturn(player);
        when(event.getCaught()).thenReturn(caught);
        return event;
    }

    private Item realCaughtItem(Material material) {
        Location loc = new Location(player.getWorld(), 0, 64, 0);
        return player.getWorld().dropItem(loc, new ItemStack(material));
    }

    // ---- 2026-08-13バグ修正の固定テスト: luckTotalOf が aggregate() の第3引数(rodFromOffhand)へ
    // 実際にオフハンド判定を渡していることを固定する。この検証がこれまで存在しなかったため、
    // 3引数版への切り替えが本当に効いているかを確認する手段が無かった。----

    @Test
    void luckTotalOfPassesOffhandTrueWhenRodIsHeldInOffhand() {
        // メインハンドは竿以外、オフハンドに釣竿 -> resolveRod はオフハンド側の竿を選ぶので rodFromOffhand=true。
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        player.getInventory().setItemInOffHand(new ItemStack(Material.FISHING_ROD));
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(0.0);
        when(gimmickConfig.junkPercent()).thenReturn(0.0);
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", Map.of(), "junk", Map.of()));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        verify(aggregator).aggregate(eq(player), any(), eq(true));
    }

    @Test
    void luckTotalOfPassesOffhandFalseWhenRodIsHeldInMainhand() {
        // setUp() で既にメインハンドに釣竿を持たせている(オフハンドは空) -> rodFromOffhand=false。
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(0.0);
        when(gimmickConfig.junkPercent()).thenReturn(0.0);
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", Map.of(), "junk", Map.of()));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        verify(aggregator).aggregate(eq(player), any(), eq(false));
    }

    @Test
    void fallbackModeLeavesTreasureCatchUntouched() {
        when(gimmickConfig.dropTablesEmpty()).thenReturn(true);
        when(gimmickConfig.junkMaterials()).thenReturn(Set.of(Material.BONE, Material.STRING));
        when(dedicatedEffects.isActive(any(), eq("junk-to-scrap"))).thenReturn(true);

        Item caught = realCaughtItem(Material.NAME_TAG);
        ItemStack before = caught.getItemStack();

        listener().onFish(fishEvent(caught));

        assertEquals(before.getType(), caught.getItemStack().getType(),
                "a non-junk-listed catch must never be replaced by the fallback junk-to-scrap logic");
    }

    @Test
    void fallbackModeReplacesJunkListedCatchWithScrapWhenPerkActive() {
        when(gimmickConfig.dropTablesEmpty()).thenReturn(true);
        when(gimmickConfig.junkMaterials()).thenReturn(Set.of(Material.BONE));
        when(dedicatedEffects.isActive(any(), eq("junk-to-scrap"))).thenReturn(true);
        when(itemResolver.create(eq("tf_scrap"))).thenReturn(Optional.of(new ItemStack(Material.PAPER)));

        Item caught = realCaughtItem(Material.BONE);

        listener().onFish(fishEvent(caught));

        assertEquals(Material.PAPER, caught.getItemStack().getType());
    }

    @Test
    void tableModeReplacesCatchAndStampsTreasureFlag() {
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(100.0); // always treasure at this ratio
        Map<String, DropTableConfig.Category> treasureCategories = Map.of(
                "treasure_vanilla", new DropTableConfig.Category("treasure_vanilla", "Treasure", 0.0,
                        java.util.List.of(new DropTableConfig.Entry("NAME_TAG", 1, 1)), false));
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", treasureCategories, "junk", Map.of()));
        when(itemResolver.create(eq("NAME_TAG"))).thenReturn(Optional.of(new ItemStack(Material.NAME_TAG)));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        assertEquals(Material.NAME_TAG, caught.getItemStack().getType());
        assertTrue(caught.getPersistentDataContainer().has(treasureFlagKey, PersistentDataType.BOOLEAN));
        assertTrue(caught.getPersistentDataContainer().get(treasureFlagKey, PersistentDataType.BOOLEAN));
    }

    @Test
    void tableModeJunkDrawIsReplacedWithScrapWhenJunkToScrapActive() {
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(0.0);
        when(gimmickConfig.junkPercent()).thenReturn(100.0); // always junk at this ratio (0% treasure + 100% junk)
        when(dedicatedEffects.isActive(any(), eq("junk-to-scrap"))).thenReturn(true);
        Map<String, DropTableConfig.Category> junkCategories = Map.of(
                "junk_vanilla", new DropTableConfig.Category("junk_vanilla", "Junk", 0.0,
                        java.util.List.of(new DropTableConfig.Entry("STRING", 1, 1)), false));
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", Map.of(), "junk", junkCategories));
        when(itemResolver.create(eq("tf_scrap"))).thenReturn(Optional.of(new ItemStack(Material.PAPER)));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        assertEquals(Material.PAPER, caught.getItemStack().getType());
        assertFalse(caught.getPersistentDataContainer().get(treasureFlagKey, PersistentDataType.BOOLEAN));
    }

    @Test
    void tableModeAlwaysLeavesNormalFishCatchUntouchedWhenTreasureAndJunkPercentAreZero() {
        // 2026-07-23 仕様確定・三択モデル化の境界: 宝%=0/ゴミ%=0 なら常に「通常の魚」= バニラキャッチ維持。
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(0.0);
        when(gimmickConfig.junkPercent()).thenReturn(0.0);
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", Map.of(), "junk", Map.of()));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        assertEquals(Material.COD, caught.getItemStack().getType());
        assertFalse(caught.getPersistentDataContainer().has(treasureFlagKey, PersistentDataType.BOOLEAN),
                "normal-fish outcome must not stamp the treasure/junk PDC flag at all");
    }

    @Test
    void tableModeLeavesCatchUntouchedWhenDrawnGroupIsEmpty() {
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(100.0);
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", Map.of(), "junk", Map.of()));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        assertEquals(Material.COD, caught.getItemStack().getType());
    }

    // ---- T2 (2026-07-25経済連携): B-alpha-1 fish-sell-toggle の「宝が釣れなくなりゴミが釣れる」 ----

    @Test
    void fishSellToggleRedirectsTreasureDrawToJunkWhenJunkTableIsPopulated() {
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(100.0); // would always draw treasure...
        when(dedicatedEffects.isActive(any(), eq("fish-sell-toggle"))).thenReturn(true);
        Map<String, DropTableConfig.Category> treasureCategories = Map.of(
                "treasure_vanilla", new DropTableConfig.Category("treasure_vanilla", "Treasure", 0.0,
                        java.util.List.of(new DropTableConfig.Entry("NAME_TAG", 1, 1)), false));
        Map<String, DropTableConfig.Category> junkCategories = Map.of(
                "junk_vanilla", new DropTableConfig.Category("junk_vanilla", "Junk", 0.0,
                        java.util.List.of(new DropTableConfig.Entry("STRING", 1, 1)), false));
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", treasureCategories, "junk", junkCategories));
        when(itemResolver.create(eq("STRING"))).thenReturn(Optional.of(new ItemStack(Material.STRING)));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        assertEquals(Material.STRING, caught.getItemStack().getType(),
                "fish-sell-toggle must redirect a treasure draw to the junk table");
        assertFalse(caught.getPersistentDataContainer().get(treasureFlagKey, PersistentDataType.BOOLEAN));
    }

    @Test
    void fishSellToggleLeavesTreasureDrawUntouchedWhenJunkTableIsEmpty() {
        // Fail-safe: no junk categories configured -> nothing to redirect into, so the original
        // treasure draw must be kept rather than yielding an unresolvable/empty outcome.
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(100.0);
        when(dedicatedEffects.isActive(any(), eq("fish-sell-toggle"))).thenReturn(true);
        Map<String, DropTableConfig.Category> treasureCategories = Map.of(
                "treasure_vanilla", new DropTableConfig.Category("treasure_vanilla", "Treasure", 0.0,
                        java.util.List.of(new DropTableConfig.Entry("NAME_TAG", 1, 1)), false));
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", treasureCategories, "junk", Map.of()));
        when(itemResolver.create(eq("NAME_TAG"))).thenReturn(Optional.of(new ItemStack(Material.NAME_TAG)));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        assertEquals(Material.NAME_TAG, caught.getItemStack().getType());
        assertTrue(caught.getPersistentDataContainer().get(treasureFlagKey, PersistentDataType.BOOLEAN));
    }

    // ---- T1 (2026-07-25): fishing.groups.fish 「通常の魚」枠の設定可能化 ----

    @Test
    void normalFishOutcomeLeavesCatchUntouchedWhenFishGroupIsUnset() {
        // 後方互換: fishグループが未設定(空)なら、通常の魚の抽選結果は旧来どおり何もしない。
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(0.0);
        when(gimmickConfig.junkPercent()).thenReturn(0.0);
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", Map.of(), "junk", Map.of()));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        assertEquals(Material.COD, caught.getItemStack().getType());
        assertFalse(caught.getPersistentDataContainer().has(treasureFlagKey, PersistentDataType.BOOLEAN));
    }

    @Test
    void normalFishOutcomeReplacesCatchFromFishGroupWhenConfigured() {
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(0.0);
        when(gimmickConfig.junkPercent()).thenReturn(0.0); // always NORMAL_FISH at this ratio
        Map<String, DropTableConfig.Category> fishCategories = Map.of(
                "fish_vanilla", new DropTableConfig.Category("fish_vanilla", "Fish", 0.0,
                        java.util.List.of(new DropTableConfig.Entry("SALMON", 1, 1)), false));
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", Map.of(), "junk", Map.of(), "fish", fishCategories));
        when(itemResolver.create(eq("SALMON"))).thenReturn(Optional.of(new ItemStack(Material.SALMON)));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        assertEquals(Material.SALMON, caught.getItemStack().getType());
    }

    @Test
    void normalFishOutcomeStampsTreasureFlagFalseWhenFishGroupConfigured() {
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(0.0);
        when(gimmickConfig.junkPercent()).thenReturn(0.0);
        Map<String, DropTableConfig.Category> fishCategories = Map.of(
                "fish_vanilla", new DropTableConfig.Category("fish_vanilla", "Fish", 0.0,
                        java.util.List.of(new DropTableConfig.Entry("SALMON", 1, 1)), false));
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", Map.of(), "junk", Map.of(), "fish", fishCategories));
        when(itemResolver.create(eq("SALMON"))).thenReturn(Optional.of(new ItemStack(Material.SALMON)));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        assertTrue(caught.getPersistentDataContainer().has(treasureFlagKey, PersistentDataType.BOOLEAN),
                "fish-group draws must stamp the treasure PDC flag just like junk draws");
        assertFalse(caught.getPersistentDataContainer().get(treasureFlagKey, PersistentDataType.BOOLEAN),
                "fish-group draws are not treasure -> flag must be false");
    }

    @Test
    void normalFishOutcomeLeavesCatchUntouchedWhenFishGroupDrawIsEmpty() {
        // Fail-safe: fish group configured but its only category is fully gated/unresolvable -> vanilla keep.
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(0.0);
        when(gimmickConfig.junkPercent()).thenReturn(0.0);
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", Map.of(), "junk", Map.of(), "fish", Map.of()));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        assertEquals(Material.COD, caught.getItemStack().getType());
    }

    @Test
    void normalFishOutcomeIsNeverScrapSwappedByJunkToScrapEvenWhenActive() {
        // 魚枠には junk-to-scrap の一律スクラップ差し替えを適用してはならない(魚はゴミではないため)。
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(0.0);
        when(gimmickConfig.junkPercent()).thenReturn(0.0);
        when(dedicatedEffects.isActive(any(), eq("junk-to-scrap"))).thenReturn(true);
        // If the fish-group draw were wrongly scrap-swapped, tf_scrap would resolve to PAPER instead of
        // SALMON — stubbing it distinguishably makes a regression here actually fail the assertion below.
        when(itemResolver.create(eq("tf_scrap"))).thenReturn(Optional.of(new ItemStack(Material.PAPER)));
        Map<String, DropTableConfig.Category> fishCategories = Map.of(
                "fish_vanilla", new DropTableConfig.Category("fish_vanilla", "Fish", 0.0,
                        java.util.List.of(new DropTableConfig.Entry("SALMON", 1, 1)), false));
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", Map.of(), "junk", Map.of(), "fish", fishCategories));
        when(itemResolver.create(eq("SALMON"))).thenReturn(Optional.of(new ItemStack(Material.SALMON)));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        assertEquals(Material.SALMON, caught.getItemStack().getType(),
                "fish-group draw must never be redirected to tf_scrap by junk-to-scrap");
    }

    @Test
    void fishSellToggleDoesNotAffectJunkOrNormalOutcomes() {
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(0.0);
        when(gimmickConfig.junkPercent()).thenReturn(100.0);
        when(dedicatedEffects.isActive(any(), eq("fish-sell-toggle"))).thenReturn(true);
        Map<String, DropTableConfig.Category> junkCategories = Map.of(
                "junk_vanilla", new DropTableConfig.Category("junk_vanilla", "Junk", 0.0,
                        java.util.List.of(new DropTableConfig.Entry("STRING", 1, 1)), false));
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", Map.of(), "junk", junkCategories));
        when(itemResolver.create(eq("STRING"))).thenReturn(Optional.of(new ItemStack(Material.STRING)));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        assertEquals(Material.STRING, caught.getItemStack().getType());
    }
}
