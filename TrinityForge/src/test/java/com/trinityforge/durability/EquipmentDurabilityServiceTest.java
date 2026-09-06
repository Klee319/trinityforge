package com.trinityforge.durability;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 装備耐久ペナルティの適用(2026-07-30)。EliteMobsのインスタンスダンジョンは致死ダメージをキャンセルして
 * ダウンへ移すため {@code PlayerDeathEvent} が発火せず、死亡ペナルティと被弾時の防具耐久消費が両方
 * 失われていた件の回帰テスト。
 *
 * <p>{@code HumanEntity#damageItemStack} を使わない実装であることをここで担保する — MockBukkit が
 * 未実装のためあれを呼ぶとテストが<strong>失敗ではなく SKIPPED</strong> になり、耐久が減ることを誰も
 * 検証できなくなる(既知の罠)。
 */
class EquipmentDurabilityServiceTest {

    private ServerMock server;
    /**
     * ダイヤ装備の最大耐久(ペナルティ量の期待値計算用)。static初期化子で読むと
     * {@code MockBukkit.mock()} より前に Material レジストリへ触ってしまい、クラス初期化ごと
     * 落ちる(=7テストが道連れ)ので、必ず mock 後に解決する。
     */
    private int helmetMax;
    private int swordMax;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        helmetMax = Material.DIAMOND_HELMET.getMaxDurability();
        swordMax = Material.DIAMOND_SWORD.getMaxDurability();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static DurabilityPenaltySettings settings(boolean dungeonOnly) {
        return new DurabilityPenaltySettings(
                dungeonOnly, false, true,
                true, 0.001, 1, true,
                true, 0.1, 1, true);
    }

    private EquipmentDurabilityService service(DurabilityPenaltySettings settings, boolean inDungeon) {
        return new EquipmentDurabilityService(() -> settings, player -> inDungeon, () -> 0.0);
    }

    private Player equipped() {
        Player player = server.addPlayer();
        PlayerInventory inv = player.getInventory();
        inv.setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        inv.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        inv.setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
        inv.setBoots(new ItemStack(Material.DIAMOND_BOOTS));
        inv.setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        inv.setItemInOffHand(new ItemStack(Material.SHIELD));
        return player;
    }

    private static int damageOf(ItemStack item) {
        assertNotNull(item, "装備が消えている");
        return ((Damageable) item.getItemMeta()).getDamage();
    }

    @Test
    void onHitConsumesArmorAndOffhandButNotTheWeapon() {
        Player player = equipped();

        int damaged = service(settings(true), true).applyOnHit(player);

        assertEquals(5, damaged, "防具4部位＋オフハンドの5点");
        PlayerInventory inv = player.getInventory();
        // 0.1% は端数で0になるため下限1が効く(バニラの消費に上乗せする少量ペナルティ)。
        assertEquals(1, damageOf(inv.getHelmet()));
        assertEquals(1, damageOf(inv.getChestplate()));
        assertEquals(1, damageOf(inv.getLeggings()));
        assertEquals(1, damageOf(inv.getBoots()));
        assertEquals(1, damageOf(inv.getItemInOffHand()));
        assertEquals(0, damageOf(inv.getItemInMainHand()), "被弾で武器の耐久は減らさない");
    }

    @Test
    void onDeathConsumesTenPercentOfArmorAndBothHands() {
        Player player = equipped();

        int damaged = service(settings(true), true).applyOnDeath(player);

        assertEquals(6, damaged, "防具4部位＋両手の6点");
        PlayerInventory inv = player.getInventory();
        assertEquals(helmetMax / 10, damageOf(inv.getHelmet()), "最大耐久の10%");
        assertEquals(swordMax / 10, damageOf(inv.getItemInMainHand()), "死亡時は武器も減る");
    }

    @Test
    void dungeonOnlySkipsOverworldButAppliesInsideDungeon() {
        Player outside = equipped();
        assertEquals(0, service(settings(true), false).applyOnDeath(outside),
                "dungeon-only=true でダンジョン外は無干渉");
        assertEquals(0, damageOf(outside.getInventory().getHelmet()));

        Player inside = equipped();
        assertEquals(6, service(settings(true), true).applyOnDeath(inside));

        // dungeon-only=false にすれば通常世界でも効く(運営が全ワールドへ広げられる)。
        Player anywhere = equipped();
        assertEquals(6, service(settings(false), false).applyOnDeath(anywhere));
    }

    @Test
    void creativeAndSpectatorAreExempt() {
        Player creative = equipped();
        creative.setGameMode(GameMode.CREATIVE);
        assertEquals(0, service(settings(true), true).applyOnDeath(creative));

        Player spectator = equipped();
        spectator.setGameMode(GameMode.SPECTATOR);
        assertEquals(0, service(settings(true), true).applyOnHit(spectator));
    }

    @Test
    void preventBreakKeepsTheLastPointOfDurability() {
        Player player = server.addPlayer();
        ItemStack helmet = new ItemStack(Material.DIAMOND_HELMET);
        Damageable meta = (Damageable) helmet.getItemMeta();
        meta.setDamage(helmetMax - 2); // 残耐久2
        helmet.setItemMeta(meta);
        player.getInventory().setHelmet(helmet);

        service(settings(true), true).applyOnDeath(player);

        ItemStack after = player.getInventory().getHelmet();
        assertNotNull(after, "prevent-break=true では壊さない");
        assertEquals(helmetMax - 1, damageOf(after), "残耐久1で止まる");
    }

    @Test
    void breaksEquipmentWhenPreventBreakDisabled() {
        Player player = server.addPlayer();
        ItemStack helmet = new ItemStack(Material.DIAMOND_HELMET);
        Damageable meta = (Damageable) helmet.getItemMeta();
        meta.setDamage(helmetMax - 2);
        helmet.setItemMeta(meta);
        player.getInventory().setHelmet(helmet);

        DurabilityPenaltySettings noPrevent = new DurabilityPenaltySettings(
                true, false, false,
                true, 0.001, 1, true,
                true, 0.1, 1, true);
        AtomicReference<ItemStack> broken = new AtomicReference<>();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void on(PlayerItemBreakEvent event) {
                broken.set(event.getBrokenItem());
            }
        }, MockBukkit.createMockPlugin());
        service(noPrevent, true).applyOnDeath(player);

        assertNull(player.getInventory().getHelmet(), "prevent-break=false なら壊れて消える");
        assertNotNull(broken.get(), "手書き破壊でも PlayerItemBreakEvent が要る(装着スレッド返却)");
        assertEquals(Material.DIAMOND_HELMET, broken.get().getType());
    }

    @Test
    void emptySlotsAndNonDurableItemsAreIgnored() {
        Player player = server.addPlayer();
        player.getInventory().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND)); // 耐久を持たない

        assertEquals(1, service(settings(true), true).applyOnDeath(player),
                "耐久のないアイテムと空スロットは数に入らない");
    }
}
