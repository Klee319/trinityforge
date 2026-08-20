package com.trinityforge.skilltree.runtime;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Material;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.AllayMock;
import org.mockbukkit.mockbukkit.entity.LivingEntityMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回帰テスト(2026-08-01): {@code mob_drop_bonus} によるアイテム複製3件。
 *
 * <ul>
 *   <li>U15: {@code PlayerDeathEvent} は {@code EntityDeathEvent} のサブクラスなので同じハンドラに来る。
 *       Player 除外ガードが無いと、PvPで被害者の持ち物が倍率で増える＝複製。</li>
 *   <li>U11: プレイヤーが持たせた/モブが拾った装備は {@code getDrops()} に戦利品と混ざって現れるため、
 *       装備欄と突合せて除外しないと渡した装備が増える＝複製。</li>
 *   <li>U12: 旧実装の {@code Math.round} は +50% を1個ドロップに対して常に2個(＝+100%)にしていた。</li>
 * </ul>
 */
class NativeSurvivalPerkDropDuplicationTest {

    private static final String MOB_DROP_BONUS = StatKeys.canonical("mob_drop_bonus");

    private ServerMock server;
    private WorldMock world;
    private NativeSurvivalPerkListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");

        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        // +100%。2026-08-13 以降これは「確定で+1個」なので、端数の確率化を挟まず乱数に依存しない。
        PlayerCombatAggregate totals = new PlayerCombatAggregate(
                Map.of(MOB_DROP_BONUS, 1.0), Map.of(), Map.of(), Map.of(), Map.of());
        when(aggregator.aggregate(any(Player.class))).thenReturn(totals);
        listener = new NativeSurvivalPerkListener(aggregator);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static org.bukkit.damage.DamageSource genericSource() {
        return org.bukkit.damage.DamageSource
                .builder(org.bukkit.damage.DamageType.GENERIC_KILL).build();
    }

    /**
     * U15。{@code PlayerDeathEvent} を組み立てずに {@code EntityDeathEvent} に Player を渡しているのは、
     * 縛りたい契約が「死亡エンティティが Player なら倍率を一切適用しない」という分岐そのものであり、
     * PlayerDeathEvent 固有の引数(死亡メッセージ・keepInventory 等)は分岐に無関係だから。
     */
    @Test
    void playerVictimDropsAreNeverMultiplied() {
        Player victim = server.addPlayer("victim");
        Player killer = server.addPlayer("killer");
        ((LivingEntityMock) victim).setKiller(killer);
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.DIAMOND, 3)));

        listener.onDeathDrops(new EntityDeathEvent(victim, genericSource(), drops));

        assertEquals(1, drops.size(), "PvPでドロップの本数が変わってはならない");
        assertEquals(3, drops.get(0).getAmount(),
                "被害者の持ち物に mob_drop_bonus が乗るとアイテム複製になる");
    }

    /** U11。装備スロット由来のスタックは戦利品ではないので倍率から除外する。 */
    @Test
    void mobEquipmentIsExcludedWhileLootIsStillMultiplied() {
        Player killer = server.addPlayer("killer");
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        ((LivingEntityMock) zombie).setKiller(killer);
        assertNotNull(zombie.getEquipment(), "MockBukkitのZombieに装備欄が無いとこのテストは無意味");
        ItemStack given = new ItemStack(Material.DIAMOND_SWORD, 1);
        zombie.getEquipment().setItemInMainHand(given.clone());

        List<ItemStack> drops = new ArrayList<>(List.of(
                new ItemStack(Material.ROTTEN_FLESH, 1), given.clone()));
        listener.onDeathDrops(new EntityDeathEvent(zombie, genericSource(), drops));

        int flesh = 0;
        int swords = 0;
        for (ItemStack drop : drops) {
            if (drop.getType() == Material.ROTTEN_FLESH) flesh = drop.getAmount();
            if (drop.getType() == Material.DIAMOND_SWORD) swords = drop.getAmount();
        }
        assertEquals(2, flesh, "戦利品(腐肉)には mob_drop_bonus が乗る");
        assertEquals(1, swords, "プレイヤーが持たせた装備は増えてはならない(複製)");
    }

    /** U11。同じ材質が「装備1個＋戦利品1個」で落ちるとき、除外は1件だけ消費する。 */
    @Test
    void exclusionConsumesOnlyOneMatchingStack() {
        Player killer = server.addPlayer("killer");
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        ((LivingEntityMock) zombie).setKiller(killer);
        zombie.getEquipment().setItemInMainHand(new ItemStack(Material.BONE, 1));

        List<ItemStack> drops = new ArrayList<>(List.of(
                new ItemStack(Material.BONE, 1), new ItemStack(Material.BONE, 1)));
        listener.onDeathDrops(new EntityDeathEvent(zombie, genericSource(), drops));

        int total = 0;
        for (ItemStack drop : drops) total += drop.getAmount();
        assertEquals(3, total,
                "装備1個は据え置き・戦利品1個は2倍なので合計3個。両方除外(2個)や両方倍率(4個)は誤り");
    }

    /**
     * 2026-08-03 回帰(実サーバ報告「アレイ等に意図的に持たせたアイテムが増える」)。
     *
     * <p><b>このテストは "インベントリが空のまま" 死亡イベントを流す</b>のが肝。2026-08-02 の修正は
     * イベント中に {@code getInventory()} を読んで除外リストを作っていたが、バニラは
     * {@code Allay.dropEquipment}/{@code Piglin.dropCustomDeathLoot} で
     * {@code inventory.removeAllItems()} を済ませて<b>から</b> {@link EntityDeathEvent} を発火するため、
     * 実サーバではこの読み取りが必ず空になり除外が1件も成立しなかった。MockBukkit はこの順序を
     * 再現しないので、インベントリに中身を入れたままのテストは<b>壊れたままでも緑になる</b>。
     * ここでは実サーバと同じ「収納は既に空」の状態を再現し、それでも倍率が掛からないことを縛る。
     */
    @Test
    void allayDropsAreNeverMultipliedEvenWhenItsInventoryIsAlreadyEmptied() {
        Player killer = server.addPlayer("killer");
        Allay allay = world.spawn(world.getSpawnLocation(), Allay.class);
        ((LivingEntityMock) allay).setKiller(killer);
        // 実サーバの EntityDeathEvent 時点と同じ状態: 収納は既にバニラが空にしている。
        assertEquals(0, countNonEmpty(((AllayMock) allay).getInventory().getContents()),
                "前提: 収納が空であること(バニラの死亡順序と同じ状態)");
        ItemStack given = new ItemStack(Material.DIAMOND, 5);

        List<ItemStack> drops = new ArrayList<>(List.of(
                new ItemStack(Material.ROTTEN_FLESH, 1), given.clone()));
        listener.onDeathDrops(new EntityDeathEvent(allay, genericSource(), drops));

        int flesh = 0;
        int diamonds = 0;
        for (ItemStack drop : drops) {
            if (drop.getType() == Material.ROTTEN_FLESH) flesh = drop.getAmount();
            if (drop.getType() == Material.DIAMOND) diamonds = drop.getAmount();
        }
        assertEquals(5, diamonds,
                "アレイに持たせたプレイヤーのダイヤは増えてはならない(収納持ちモブは倍率対象外)");
        assertEquals(1, flesh,
                "収納持ちモブは丸ごと倍率対象外にする(戦利品と持ち物を区別する手段がイベント時に無いため)");
    }

    /** 収納を持たない通常モブは従来どおり倍率対象のまま(丸ごと除外に巻き込まない)。 */
    @Test
    void nonCarrierMobsKeepTheDropMultiplier() {
        assertTrue(NativeSurvivalPerkListener.carriesPlayerFillableStorage(
                        world.spawn(world.getSpawnLocation(), Allay.class)),
                "アレイは収納持ち＝倍率対象外");
        assertFalse(NativeSurvivalPerkListener.carriesPlayerFillableStorage(
                        world.spawn(world.getSpawnLocation(), Zombie.class)),
                "ゾンビは収納を持たない＝倍率対象のまま(装備欄の突合せで守る)");
    }

    private static int countNonEmpty(ItemStack[] contents) {
        int count = 0;
        for (ItemStack item : contents) {
            if (item != null && !item.getType().isAir()) count++;
        }
        return count;
    }

    /**
     * 2026-08-13 仕様変更。バニラドロップへのドロップ増加ステは<b>乗算ではなく加算</b>。
     * 整数部は確定で+、小数部だけその確率で+1する。
     */
    @Test
    void dropBonusAddsCountInsteadOfMultiplying() {
        assertEquals(2, NativeSurvivalPerkListener.scaleAmount(1, 0.5, 64, 0.49),
                "+50% は50%の確率で1個増える");
        assertEquals(1, NativeSurvivalPerkListener.scaleAmount(1, 0.5, 64, 0.51),
                "外れたら増えない");
        assertEquals(2, NativeSurvivalPerkListener.scaleAmount(1, 1.0, 64, 0.99),
                "+100% は乱数に関係なく確定で1個増える");
        assertEquals(3, NativeSurvivalPerkListener.scaleAmount(1, 1.5, 64, 0.49),
                "+150% は確定で1個、さらに超過50%の確率でもう1個");
        assertEquals(2, NativeSurvivalPerkListener.scaleAmount(1, 1.5, 64, 0.51),
                "+150% の超過分を外したら+1個止まり");
    }

    /** 2026-08-13 仕様変更。旧実装(乗算)との差が最も大きいのは大きなスタック。 */
    @Test
    void largeStacksNoLongerDoubleWithFullBonus() {
        assertEquals(33, NativeSurvivalPerkListener.scaleAmount(32, 1.0, 64, 0.99),
                "旧実装は32個×2=64個だった。新仕様は+1個(ユーザー指示による意図的な弱体化)");
    }

    /** ボーナスの上限(+200%)と、個数の上限・下限は旧実装から変えない(maxStackSize×8 / 最低1個)。 */
    @Test
    void scaleAmountKeepsStackCapAndMinimum() {
        assertEquals(3, NativeSurvivalPerkListener.scaleAmount(1, 5.0, 64, 0.0),
                "ボーナスは+200%で頭打ちなので、いくら盛っても+2個まで");
        assertEquals(512, NativeSurvivalPerkListener.scaleAmount(1000, 2.0, 64, 0.0));
        assertEquals(8, NativeSurvivalPerkListener.scaleAmount(10, 2.0, 1, 0.0),
                "最大スタック1のツールでも上限は maxStackSize×8");
        assertTrue(NativeSurvivalPerkListener.scaleAmount(0, 2.0, 64, 0.99) >= 1,
                "0個スタックを渡されても負や0を返さない");
    }
}
