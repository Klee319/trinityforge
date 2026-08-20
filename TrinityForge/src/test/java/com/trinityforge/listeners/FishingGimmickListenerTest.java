package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FishingGimmickConfig;
import com.trinityforge.pdc.PlayerData;
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
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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

    // ---- 2026-08-15追加: fishing.unlock-groups (デフォルトテーブル/機能解放追加テーブルの2本立て) ----
    //
    // 設計: groups(デフォルト)とunlock-groups(機能解放追加)は同じ重みプールへ合流する(希釈あり・別ロール
    // にはしない)。ゲート照会キーはこのマージの副産物として"<groupId>:<catId>"へ前置きされ、
    // categoryOpen(prof, key, ...)が"<prof>:<groupId>:<catId>"(3セグメント)を引くようになる — これは
    // 旧実装の既知バグ(groupId抜きの2セグメントキーしか引けず、正しく登録されたゲートが常時オープンに
    // 化けていた)の修正でもある。

    @Test
    void unlockGroupsAbsentKeepsResultIdenticalToPreExistingBehavior() {
        // #1 後方互換: unlock-groups未設定(空Map)でも、既存のtableModeテストと完全に同じ結果になる。
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(100.0);
        Map<String, DropTableConfig.Category> treasureCategories = Map.of(
                "treasure_vanilla", new DropTableConfig.Category("treasure_vanilla", "Treasure", 0.0,
                        List.of(new DropTableConfig.Entry("NAME_TAG", 1, 1)), false));
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", treasureCategories, "junk", Map.of()));
        when(gimmickConfig.unlockGroups()).thenReturn(Map.of());
        when(itemResolver.create(eq("NAME_TAG"))).thenReturn(Optional.of(new ItemStack(Material.NAME_TAG)));

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        assertEquals(Material.NAME_TAG, caught.getItemStack().getType());
        assertTrue(caught.getPersistentDataContainer().get(treasureFlagKey, PersistentDataType.BOOLEAN));
    }

    @Test
    void unlockCategoryNeverDrawnWithoutGatePerk() {
        // #2: 追加カテゴリのゲートperkを持たないプレイヤーには、そのカテゴリのエントリが1件も出ない。
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(100.0);
        Map<String, DropTableConfig.Category> defaults = Map.of(
                "a", new DropTableConfig.Category("a", "A", 0.0,
                        List.of(new DropTableConfig.Entry("ITEM_A", 100, 1)), false));
        Map<String, DropTableConfig.Category> unlocks = Map.of(
                "b", new DropTableConfig.Category("b", "B", 0.0,
                        List.of(new DropTableConfig.Entry("ITEM_B", 100, 1)), false));
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", defaults, "junk", Map.of()));
        when(gimmickConfig.unlockGroups()).thenReturn(Map.of("treasure", unlocks));
        when(dedicatedEffects.dropGatePerks()).thenReturn(Map.of("fishing:treasure:b", Set.of("unlock_perk")));
        when(itemResolver.create(eq("ITEM_A"))).thenReturn(Optional.of(new ItemStack(Material.NAME_TAG)));
        when(itemResolver.create(eq("ITEM_B"))).thenReturn(Optional.of(new ItemStack(Material.DIAMOND)));
        // プレイヤーはperk未所持のまま(setHeldPerksを呼ばない)。

        for (double roll : new double[] {0.0, 0.4, 0.99}) {
            try (MockedStatic<ThreadLocalRandom> rngStatic = mockStatic(ThreadLocalRandom.class)) {
                ThreadLocalRandom rng = mock(ThreadLocalRandom.class);
                rngStatic.when(ThreadLocalRandom::current).thenReturn(rng);
                when(rng.nextDouble()).thenReturn(roll);

                Item caught = realCaughtItem(Material.COD);
                listener().onFish(fishEvent(caught));

                assertEquals(Material.NAME_TAG, caught.getItemStack().getType(),
                        "roll=" + roll + ": unlock-groups category must never be drawn without the gate perk");
            }
        }
    }

    @Test
    void unlockCategoryIsDrawableAndDilutesExistingCategoryShareOnceGatePerkIsHeld() {
        // #3: ゲートperkを持つプレイヤーには追加カテゴリのエントリも出る。かつ「同じ重みプールへの合流」で
        // 分母が増え既存エントリのシェアが薄まる方向を、固定roll値(0.99)で
        // 「解放前はAが出る -> 解放後は同じrollでBが出る」の形で固定する(希釈の向きを間違えたら赤になる)。
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(100.0);
        Map<String, DropTableConfig.Category> defaults = Map.of(
                "a", new DropTableConfig.Category("a", "A", 0.0,
                        List.of(new DropTableConfig.Entry("ITEM_A", 100, 1)), false));
        Map<String, DropTableConfig.Category> unlocks = Map.of(
                "b", new DropTableConfig.Category("b", "B", 0.0,
                        List.of(new DropTableConfig.Entry("ITEM_B", 100, 1)), false));
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", defaults, "junk", Map.of()));
        when(gimmickConfig.unlockGroups()).thenReturn(Map.of("treasure", unlocks));
        when(dedicatedEffects.dropGatePerks()).thenReturn(Map.of("fishing:treasure:b", Set.of("unlock_perk")));
        when(itemResolver.create(eq("ITEM_A"))).thenReturn(Optional.of(new ItemStack(Material.NAME_TAG)));
        when(itemResolver.create(eq("ITEM_B"))).thenReturn(Optional.of(new ItemStack(Material.DIAMOND)));

        // 解放前(perk未所持): プールはA(重み100)だけ -> roll=0.99でもAが出る。
        try (MockedStatic<ThreadLocalRandom> rngStatic = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom rng = mock(ThreadLocalRandom.class);
            rngStatic.when(ThreadLocalRandom::current).thenReturn(rng);
            when(rng.nextDouble()).thenReturn(0.99);

            Item caught = realCaughtItem(Material.COD);
            listener().onFish(fishEvent(caught));

            assertEquals(Material.NAME_TAG, caught.getItemStack().getType(),
                    "before unlock: pool is A-only -> the same roll must draw A");
        }

        // 解放後(perk所持): プールはA+B(重み200)へ希釈 -> 同じroll=0.99は旧Aの範囲[0,100)の外(198)へ
        // 落ちるのでBが出る。
        PlayerData.of(player).setHeldPerks(List.of("unlock_perk"));
        try (MockedStatic<ThreadLocalRandom> rngStatic = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom rng = mock(ThreadLocalRandom.class);
            rngStatic.when(ThreadLocalRandom::current).thenReturn(rng);
            when(rng.nextDouble()).thenReturn(0.99);

            Item caught = realCaughtItem(Material.COD);
            listener().onFish(fishEvent(caught));

            assertEquals(Material.DIAMOND, caught.getItemStack().getType(),
                    "after unlock: the same roll now lands in B's diluted share -> B must be drawn");
        }
    }

    @Test
    void gateKeyIsThreeSegmentGroupPrefixed() {
        // #4 2026-08-15バグ修正の固定テスト: ゲートは "<prof>:<groupId>:<catId>" (3セグメント)で照会する。
        // 旧実装は "<prof>:<catId>" (2セグメント、groupId抜き)でしか照会しなかったため、ここで正しい
        // 3セグメントキー("fishing:treasure:cat_x")で登録したゲートが旧実装からは常に未登録=オープン
        // 扱いになり、ロックされているはずのNAME_TAGが引けてしまっていた(本来直すべき既知バグ)。
        // このテストは修正前のコードに戻すと必ず赤くなる(=Material.NAME_TAGに置換されてしまい、
        // assertEquals(Material.COD, ...)が落ちる)。
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(100.0);
        Map<String, DropTableConfig.Category> defaults = Map.of(
                "cat_x", new DropTableConfig.Category("cat_x", "X", 0.0,
                        List.of(new DropTableConfig.Entry("NAME_TAG", 1, 1)), false));
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", defaults, "junk", Map.of()));
        when(dedicatedEffects.dropGatePerks()).thenReturn(Map.of("fishing:treasure:cat_x", Set.of("some_perk")));
        // 2セグメントキー("fishing:cat_x")で誤って開いてしまった場合にNAME_TAGへ実際に置換され、
        // テストが正しく赤くなるようにitemResolverをスタブしておく(未スタブだとOptional.empty()経由の
        // fail-safeでcaughtがCODのまま残り、ゲートの開閉に関係なくテストが常に緑になってしまう)。
        when(itemResolver.create(eq("NAME_TAG"))).thenReturn(Optional.of(new ItemStack(Material.NAME_TAG)));
        // プレイヤーはperk未所持のまま。

        Item caught = realCaughtItem(Material.COD);

        listener().onFish(fishEvent(caught));

        assertEquals(Material.COD, caught.getItemStack().getType(),
                "a category gated by the correct 3-segment key must stay locked (vanilla catch kept) "
                        + "for a player without the perk");
    }

    @Test
    void duplicateCategoryIdInUnlockGroupsIsIgnoredInFavorOfDefaultGroup() {
        // #5: groups/unlock-groupsの同一グループ内に同じカテゴリidがあるとき、unlock-groups側は
        // 警告ログのうえ無視される(ゲートキーが衝突しどちらが効いているか判別不能になるため)。
        when(gimmickConfig.dropTablesEmpty()).thenReturn(false);
        when(gimmickConfig.treasurePercent()).thenReturn(100.0);
        Map<String, DropTableConfig.Category> defaults = Map.of(
                "dup", new DropTableConfig.Category("dup", "Dup", 0.0,
                        List.of(new DropTableConfig.Entry("NAME_TAG", 1, 1)), false));
        Map<String, DropTableConfig.Category> unlocks = Map.of(
                "dup", new DropTableConfig.Category("dup", "Dup", 0.0,
                        List.of(new DropTableConfig.Entry("DIAMOND", 1, 1)), false));
        when(gimmickConfig.groups()).thenReturn(Map.of("treasure", defaults, "junk", Map.of()));
        when(gimmickConfig.unlockGroups()).thenReturn(Map.of("treasure", unlocks));
        when(itemResolver.create(eq("NAME_TAG"))).thenReturn(Optional.of(new ItemStack(Material.NAME_TAG)));
        when(itemResolver.create(eq("DIAMOND"))).thenReturn(Optional.of(new ItemStack(Material.DIAMOND)));

        Logger fishingLogger = Logger.getLogger(FishingGimmickListener.class.getName());
        List<String> captured = new ArrayList<>();
        Handler captureHandler = new Handler() {
            @Override public void publish(LogRecord record) {
                captured.add(record.getMessage());
            }
            @Override public void flush() {
            }
            @Override public void close() {
            }
        };
        fishingLogger.addHandler(captureHandler);
        try {
            for (double roll : new double[] {0.0, 0.99}) {
                try (MockedStatic<ThreadLocalRandom> rngStatic = mockStatic(ThreadLocalRandom.class)) {
                    ThreadLocalRandom rng = mock(ThreadLocalRandom.class);
                    rngStatic.when(ThreadLocalRandom::current).thenReturn(rng);
                    when(rng.nextDouble()).thenReturn(roll);

                    Item caught = realCaughtItem(Material.COD);
                    listener().onFish(fishEvent(caught));

                    assertEquals(Material.NAME_TAG, caught.getItemStack().getType(),
                            "roll=" + roll + ": a duplicate category id in unlock-groups must be ignored, "
                                    + "the default-group entry must always win");
                }
            }
        } finally {
            fishingLogger.removeHandler(captureHandler);
        }
        assertTrue(captured.stream().anyMatch(msg -> msg != null && msg.contains("dup") && msg.contains("treasure")),
                "a Japanese warning naming the group/category must be logged when unlock-groups is ignored");
    }
}
