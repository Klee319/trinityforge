package com.trinityforge.listeners;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.CollectionService;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 図鑑が ArsPaper 側で定義したアイテムを記録できることの契約(2026-07-31)。
 *
 * <p>{@code CollectionListener} は TF の catalog PDC しか読んでいなかったため、
 * ArsPaper の {@code materials.yml} / {@code sourcejars.yml} 由来のアイテム
 * (モブドロップ素材17件・ダンジョン踏破の証22件・ソースの階梯9件)が永久に記録されず、
 * 図鑑カテゴリに書いてある116件のうち<b>48件が「絶対に埋まらない枠」</b>として並んでいた。
 * 例外もログも出ないので、実物を集めてみるまで気づけない種類の不具合。
 *
 * <p>同時に、Ars の登録アイテムを無条件に記録しないことも固定する。Ars 側にはグリフ120件を
 * 含めて300件超の登録アイテムがあり、全部記録すると (1) プレイヤーPDCがその分膨らみ、
 * (2) 図鑑の報酬ティア(10/30/60/120/200件)の重みが黙って変わる。
 */
class CollectionArsItemRecordingTest {

    private static final NamespacedKey ARS_CUSTOM_ITEM_ID =
            new NamespacedKey("arspaper", "custom_item_id");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 図鑑カテゴリに ravager_hide(Ars 素材) と DIAMOND(バニラ) を載せた設定。 */
    private static CollectionListener listener() {
        CollectionConfig config = mock(CollectionConfig.class);
        when(config.enabled()).thenReturn(true);
        when(config.catalogItemsEnabled()).thenReturn(true);
        when(config.mobKillsEnabled()).thenReturn(true);
        when(config.itemCategories()).thenReturn(List.of(
                new CollectionConfig.Category("mob_parts", "討伐素材", 1,
                        List.of("ravager_hide", "DIAMOND", "custom:reality_thread_core"))));
        when(config.tiers()).thenReturn(List.of());

        AchievementsConfig achievements = mock(AchievementsConfig.class);
        when(achievements.achievements()).thenReturn(List.of());

        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.all()).thenReturn(java.util.Map.of());

        CollectionService service = new CollectionService(config, Logger.getLogger("CollectionArsItemRecordingTest"));
        return new CollectionListener(config, service, catalog, achievements);
    }

    private static ItemStack arsItem(Material material, String arsId) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(ARS_CUSTOM_ITEM_ID, PersistentDataType.STRING, arsId);
        stack.setItemMeta(meta);
        return stack;
    }

    /** インベントリを閉じたときの全スロット走査を通して記録させる(拾得と同じ解決経路)。 */
    private static void scan(Player player) {
        listener().onInventoryClose(new InventoryCloseEvent(player.getOpenInventory()));
    }

    /** PDC は {@code id|epochMillis|maxQualityPt} で入るので、ID だけを取り出して比べる。 */
    private static boolean recorded(Player player, String entryId) {
        return PlayerData.of(player).collectionEntries().stream()
                .map(com.trinityforge.progression.CollectionRecord::parse)
                .filter(java.util.Objects::nonNull)
                .anyMatch(r -> r.id().equals("item:" + entryId));
    }

    @Test
    @DisplayName("図鑑カテゴリに載っている Ars 素材は記録される")
    void arsItemListedInACategoryIsRecorded() {
        Player player = server.addPlayer();
        player.getInventory().addItem(arsItem(Material.LEATHER, "ravager_hide"));

        scan(player);

        assertTrue(recorded(player, "ravager_hide"),
                "ArsPaper の materials.yml 由来アイテムが図鑑に載らないと、"
                        + "カテゴリに書いた枠が永久に埋まらない");
    }

    @Test
    @DisplayName("custom: 接頭辞付きで書いたエントリも一致する")
    void customPrefixInTheConfigIsStripped() {
        Player player = server.addPlayer();
        player.getInventory().addItem(arsItem(Material.ECHO_SHARD, "reality_thread_core"));

        scan(player);

        assertTrue(recorded(player, "reality_thread_core"),
                "editor は custom アイテムを custom:<id> へ正規化するので、両方の書き方を通す必要がある");
    }

    @Test
    @DisplayName("設定から参照されていない Ars アイテムは記録しない")
    void unreferencedArsItemIsIgnored() {
        Player player = server.addPlayer();
        // グリフや魔導書のように「Ars に登録はされているが図鑑には出さない」アイテム。
        player.getInventory().addItem(arsItem(Material.PAPER, "glyph_projectile"));

        scan(player);

        assertFalse(recorded(player, "glyph_projectile"),
                "Ars の登録アイテムは300件超あるので、無条件に記録すると PDC が膨らみ"
                        + "報酬ティアの重みも黙って変わる");
    }

    @Test
    @DisplayName("バニラ Material の監視は従来どおり効く")
    void watchedVanillaMaterialStillRecorded() {
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND));

        scan(player);

        assertTrue(recorded(player, "DIAMOND"));
    }

    @Test
    @DisplayName("監視外のバニラ Material は記録しない")
    void unwatchedVanillaMaterialIsIgnored() {
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIRT));

        scan(player);

        assertFalse(recorded(player, "DIRT"));
    }
}
