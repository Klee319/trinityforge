package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.gathering.GatheringEfficiencyEnchantApplier;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.InventoryMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GatheringEfficiencyEnchantApplier}: the runtime Efficiency-enchant mirror for
 * {@code gathering-efficiency} (2026-07-25 採集効率エンチャント連動方式、属性ベースの取り下げ再設計).
 * Uses the same "reflective fake Plugin loads shipped defaults" pattern as
 * {@link PerkAttributeApplierAttackSpeedTest} via the package-private {@link CombatWiringSupport}.
 */
class GatheringEfficiencyEnchantApplierTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private GatheringEfficiencyEnchantApplier newApplier(File dir, String itemStatsYaml) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), itemStatsYaml);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        RoleBuffResolver role = new RoleBuffResolver(cm.roleBuffs());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), cm.combatDamage(), perks, role, null, null, cm.baseStats());
        Plugin plugin = MockBukkit.createMockPlugin();
        return new GatheringEfficiencyEnchantApplier(plugin, aggregator, cm.gatheringEfficiency());
    }

    /** A TF-stamped item whose {@code use-skill} is MINING (matches {@link ActivationDispatcher} contract). */
    private static ItemStack miningPickaxe() {
        ItemStack stack = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setUseRequirement("MINING", 0);
        stack.setItemMeta(meta);
        return stack;
    }

    private static int recordedDelta(ItemStack stack) {
        return stack.getItemMeta().getPersistentDataContainer()
                .getOrDefault(PdcKeys.ITEM_GATHERING_EFFICIENCY_APPLIED, PersistentDataType.INTEGER, 0);
    }

    private static int efficiencyLevel(ItemStack stack) {
        return stack.getItemMeta().hasEnchant(Enchantment.EFFICIENCY)
                ? stack.getItemMeta().getEnchantLevel(Enchantment.EFFICIENCY) : 0;
    }

    // --- 基本挙動: メインハンドがFARMING/MINING/WOODCUTTING/DIGGINGのいずれかのときだけ反映される ---
    @Test
    void appliesFlooredLevelToMainhandMatchingSkill(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed: { gathering-efficiency: 3.9 }
                """);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(miningPickaxe());

        applier.reconcileFull(player);

        ItemStack result = player.getInventory().getItemInMainHand();
        assertEquals(3, efficiencyLevel(result));
        assertEquals(3, recordedDelta(result));
    }

    // --- 危険な点3(条件): 該当4スキル以外のuse-skillには一切適用しない ---
    @Test
    void doesNotApplyToNonTargetSkillItem(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { gathering-efficiency: 5.0 }
                """);
        PlayerMock player = server.addPlayer();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        ItemData.of(meta).setUseRequirement("LIGHT_WEAPONS", 0);
        sword.setItemMeta(meta);
        player.getInventory().setItemInMainHand(sword);

        applier.reconcileFull(player);

        assertEquals(0, efficiencyLevel(player.getInventory().getItemInMainHand()));
    }

    // --- 調査結果: バニラの未刻印ツール(use-skill未解決)にはボーナスが乗らない ---
    @Test
    void vanillaUnstampedToolNeverGetsBonus(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed: { gathering-efficiency: 5.0 }
                """);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_PICKAXE)); // no use-skill PDC

        applier.reconcileFull(player);

        assertEquals(0, efficiencyLevel(player.getInventory().getItemInMainHand()));
    }

    // --- 危険な点3: 0以下の合算値はエンチャントを一切付与しない(既存レベルも削らない) ---
    @Test
    void zeroOrNegativeTotalNeverRemovesExistingEnchant(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, "items: {}\n");
        PlayerMock player = server.addPlayer();
        ItemStack pickaxe = miningPickaxe();
        ItemMeta meta = pickaxe.getItemMeta();
        meta.addEnchant(Enchantment.EFFICIENCY, 2, true); // player/anvil-earned level, not TF's
        pickaxe.setItemMeta(meta);
        player.getInventory().setItemInMainHand(pickaxe);

        applier.reconcileFull(player);

        ItemStack result = player.getInventory().getItemInMainHand();
        assertEquals(2, efficiencyLevel(result), "pre-existing anvil/player level must survive a zero total");
        assertEquals(0, recordedDelta(result));
    }

    // --- 危険な点6: 既存のtool-enchant-efficiency(クラフト刻印)由来のレベルを除去処理が巻き込まない ---
    @Test
    void removalNeverTouchesPreExistingBakedInLevel(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed: { gathering-efficiency: 2.0 }
                """);
        PlayerMock player = server.addPlayer();
        ItemStack pickaxe = miningPickaxe();
        ItemMeta meta = pickaxe.getItemMeta();
        meta.addEnchant(Enchantment.EFFICIENCY, 1, true); // baked-in by tool-enchant-efficiency at craft time
        pickaxe.setItemMeta(meta);
        player.getInventory().setItemInMainHand(pickaxe);

        applier.reconcileFull(player); // 1 (baked) + 2 (TF) = 3
        assertEquals(3, efficiencyLevel(player.getInventory().getItemInMainHand()));
        assertEquals(2, recordedDelta(player.getInventory().getItemInMainHand()));

        // Now stat total drops to 0 (e.g. unequipped some other gear); TF's delta must come off,
        // but the baked-in 1 must remain untouched.
        player.getInventory().setItemInMainHand(withNoGatheringEfficiencyStamp(pickaxe));
        applier.reconcileFull(player);
        ItemStack after = player.getInventory().getItemInMainHand();
        assertEquals(1, efficiencyLevel(after), "baked-in tool-enchant-efficiency level must survive removal");
        assertEquals(0, recordedDelta(after));
    }

    private static ItemStack withNoGatheringEfficiencyStamp(ItemStack pickaxe) {
        // Re-tag as a non-target skill so the resolver computes desired=0 (simulating the stat total
        // dropping to zero without re-deriving item-stats in this unit test).
        ItemStack copy = pickaxe.clone();
        ItemMeta meta = copy.getItemMeta();
        ItemData.of(meta).setUseRequirement("SMITHING", 0);
        copy.setItemMeta(meta);
        return copy;
    }

    // --- 冪等性(危険な点2): 同じ状態への複数回適用で増え続けない ---
    @Test
    void reapplyingSameStateIsIdempotent(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed: { gathering-efficiency: 3.0 }
                """);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(miningPickaxe());

        applier.reconcileFull(player);
        applier.reconcileFull(player);
        applier.reconcileFull(player);

        ItemStack result = player.getInventory().getItemInMainHand();
        assertEquals(3, efficiencyLevel(result));
        assertEquals(3, recordedDelta(result));
    }

    // --- 危険な点3(離脱経路: 持ち替え): 持ち替えでメインハンドを外れたアイテムの付与分は剥がされる ---
    @Test
    void swappingMainhandStripsThePreviouslyBoostedItem(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed: { gathering-efficiency: 3.0 }
                """);
        PlayerMock player = server.addPlayer();
        PlayerInventory inv = player.getInventory();
        inv.setItemInMainHand(miningPickaxe());
        applier.reconcileFull(player);
        assertEquals(3, efficiencyLevel(inv.getItemInMainHand()));

        // Move the boosted pickaxe to a backpack slot and put something else in the mainhand.
        ItemStack boosted = inv.getItemInMainHand();
        inv.setItem(9, boosted);
        inv.setItemInMainHand(new ItemStack(Material.AIR));

        applier.reconcileFull(player);

        assertEquals(0, efficiencyLevel(inv.getItem(9)), "item that left the mainhand slot must be stripped");
        assertEquals(0, recordedDelta(inv.getItem(9)));
    }

    // --- 危険な点3(離脱経路: インベントリを開く): 開いた瞬間、全スロットが素の状態に戻る ---
    @Test
    void openingAnInventoryStripsEveryTrackedSlot(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed: { gathering-efficiency: 3.0 }
                """);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(miningPickaxe());
        applier.reconcileFull(player);
        assertEquals(3, efficiencyLevel(player.getInventory().getItemInMainHand()));

        InventoryMock anvil = server.createInventory(player, org.bukkit.event.inventory.InventoryType.ANVIL);
        org.bukkit.inventory.InventoryView view = player.openInventory(anvil);
        applier.onInventoryOpen(new org.bukkit.event.inventory.InventoryOpenEvent(view));

        assertEquals(0, efficiencyLevel(player.getInventory().getItemInMainHand()),
                "opening any inventory must strip the boosted mainhand item back to baseline");
    }

    // --- 危険な点3(離脱経路: ドロップ): ドロップしたアイテムのスタックにも付与分が残らない ---
    @Test
    void droppingTheBoostedItemStripsTheDroppedStack(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed: { gathering-efficiency: 3.0 }
                """);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(miningPickaxe());
        applier.reconcileFull(player);
        ItemStack boosted = player.getInventory().getItemInMainHand();
        assertTrue(efficiencyLevel(boosted) > 0);

        org.bukkit.entity.Item dropped = player.getWorld().dropItem(player.getLocation(), boosted.clone());
        org.bukkit.event.player.PlayerDropItemEvent event =
                new org.bukkit.event.player.PlayerDropItemEvent(player, dropped);
        applier.onDrop(event);

        assertEquals(0, efficiencyLevel(dropped.getItemStack()),
                "a dropped stack that still carries TF's delta must be stripped");
    }

    // --- 危険な点4: ログイン時にPDC記録が残ったアイテムから付与分を正しく再計算・剥がす(クラッシュ復旧) ---
    @Test
    void joinReconcilesStrayRecordedItemsElsewhereInInventory(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, "items: {}\n");
        PlayerMock player = server.addPlayer();
        ItemStack crashed = miningPickaxe();
        ItemMeta meta = crashed.getItemMeta();
        meta.addEnchant(Enchantment.EFFICIENCY, 4, true);
        meta.getPersistentDataContainer().set(
                PdcKeys.ITEM_GATHERING_EFFICIENCY_APPLIED, PersistentDataType.INTEGER, 4);
        crashed.setItemMeta(meta);
        player.getInventory().setItem(9, crashed); // sitting in a backpack slot, not mainhand

        applier.reconcileFull(player); // simulates the join-time recovery pass

        ItemStack recovered = player.getInventory().getItem(9);
        assertEquals(0, efficiencyLevel(recovered));
        assertEquals(0, recordedDelta(recovered));
    }

    // --- 2026-07-26 ユーザー決定: 「効率」ステータス統合。旧tool-enchant-efficiencyキーで書かれた
    //     item-stats.ymlの値も、StatKeysのエイリアス経由でgathering-efficiencyの合算に混ざり、
    //     このapplierの実行時ミラー経路一本で反映されること(ItemAssemblerの静的焼き込みには回らない) ---
    @Test
    void legacyToolEnchantEfficiencyKeyFeedsIntoTheUnifiedRuntimeMirror(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed: { tool-enchant-efficiency: 3.0 }
                """);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(miningPickaxe());

        applier.reconcileFull(player);

        ItemStack result = player.getInventory().getItemInMainHand();
        assertEquals(3, efficiencyLevel(result),
                "旧tool-enchant-efficiencyの値もgathering-efficiencyへ統合され、実行時ミラーで反映されること");
        assertEquals(3, recordedDelta(result),
                "反映はGatheringEfficiencyEnchantApplierのPDC記録付き動的経路で行われること"
                        + "(ItemAssemblerの静的焼き込みではない)");
    }

    // --- 上限クランプ: config の max-enchant-level(既定5)を超えない ---
    @Test
    void clampsToConfiguredMaxEnchantLevel(@TempDir File dir) throws IOException {
        Files.createDirectories(new File(dir, "stats").toPath());
        Files.writeString(new File(dir, "stats/gathering-efficiency.yml").toPath(),
                "max-enchant-level: 2\n");
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed: { gathering-efficiency: 10.0 }
                """);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(miningPickaxe());

        applier.reconcileFull(player);

        assertEquals(2, efficiencyLevel(player.getInventory().getItemInMainHand()));
    }

    @Test
    void notAppliedWhenItemIsAir(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, "items: {}\n");
        PlayerMock player = server.addPlayer();

        applier.reconcileFull(player); // must not throw with an empty mainhand

        assertFalse(player.getInventory().getItemInMainHand().hasItemMeta());
    }

    // --- 危険な点3(離脱経路: ログアウト): ログアウトでメインハンドの付与分が剥がされる ---
    @Test
    void loggingOutStripsTheBoostedMainhandItem(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed: { gathering-efficiency: 3.0 }
                """);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(miningPickaxe());
        applier.reconcileFull(player);
        assertEquals(3, efficiencyLevel(player.getInventory().getItemInMainHand()));

        applier.onQuit(new org.bukkit.event.player.PlayerQuitEvent(player, "quit"));

        assertEquals(0, efficiencyLevel(player.getInventory().getItemInMainHand()));
        assertEquals(0, recordedDelta(player.getInventory().getItemInMainHand()));
    }

    // --- 危険な点3(離脱経路: 死亡): 死亡ドロップにも付与分が残らない ---
    @Test
    void deathDropsAreStrippedOfTheBoostedDelta(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed: { gathering-efficiency: 3.0 }
                """);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(miningPickaxe());
        applier.reconcileFull(player);
        ItemStack boosted = player.getInventory().getItemInMainHand();
        assertTrue(efficiencyLevel(boosted) > 0);

        java.util.List<ItemStack> drops = new java.util.ArrayList<>(java.util.List.of(boosted.clone()));
        org.bukkit.damage.DamageSource source =
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build();
        org.bukkit.event.entity.PlayerDeathEvent event = new org.bukkit.event.entity.PlayerDeathEvent(
                player, source, drops, 0, net.kyori.adventure.text.Component.text("died"), false);

        applier.onDeath(event);

        assertEquals(0, efficiencyLevel(event.getDrops().get(0)),
                "a death drop that still carries TF's delta must be stripped");
    }

    // --- 危険な点3(離脱経路: プラグイン無効化): 全オンラインプレイヤーの付与分を剥がす安全網 ---
    @Test
    void pluginDisableSafetyNetStripsAllOnlinePlayers(@TempDir File dir) throws IOException {
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed: { gathering-efficiency: 3.0 }
                """);
        PlayerMock playerA = server.addPlayer();
        PlayerMock playerB = server.addPlayer();
        playerA.getInventory().setItemInMainHand(miningPickaxe());
        playerB.getInventory().setItemInMainHand(miningPickaxe());
        applier.reconcileFull(playerA);
        applier.reconcileFull(playerB);
        assertEquals(3, efficiencyLevel(playerA.getInventory().getItemInMainHand()));
        assertEquals(3, efficiencyLevel(playerB.getInventory().getItemInMainHand()));

        applier.stripAllOnline();

        assertEquals(0, efficiencyLevel(playerA.getInventory().getItemInMainHand()));
        assertEquals(0, efficiencyLevel(playerB.getInventory().getItemInMainHand()));
    }

    // --- 危険な点5(他人に渡った場合): 受け取った側の合算値が0ならreconcileFullで即座に剥がれる ---
    @Test
    void itemTransferredToAnotherPlayerLosesTheBonusOnTheirNextReconcile(@TempDir File dir) throws IOException {
        // ownerYaml: プレイヤーAの世界では gathering-efficiency がある。受け取るプレイヤーBの世界は
        // 別のitem-stats(そのMaterialに何もステが無い)という想定を、単純化のため「Bの合算値が0になる」
        // ことで代表させる(PlayerStatAggregatorはプレイヤー固有ではなくアイテム/config駆動のため、実際に
        // 差をつけるにはBが装備/パーク等を持たないシナリオを使う)。
        GatheringEfficiencyEnchantApplier applier = newApplier(dir, """
                items:
                  DIAMOND_PICKAXE:
                    fixed: { gathering-efficiency: 3.0 }
                """);
        PlayerMock owner = server.addPlayer();
        owner.getInventory().setItemInMainHand(miningPickaxe());
        applier.reconcileFull(owner);
        ItemStack traded = owner.getInventory().getItemInMainHand().clone();
        assertTrue(efficiencyLevel(traded) > 0, "sanity: item left the original owner already boosted");

        // Bはこのツールを装備しない(バックパックに転がっているだけ)想定で受け取る。
        PlayerMock receiver = server.addPlayer();
        receiver.getInventory().setItem(9, traded);

        applier.reconcileFull(receiver); // その他プレイヤーの次回reconcile(join相当)で必ず剥がれる

        ItemStack afterTransfer = receiver.getInventory().getItem(9);
        assertEquals(0, efficiencyLevel(afterTransfer), "stray marker must not survive on the new owner");
        assertEquals(0, recordedDelta(afterTransfer));
    }
}
