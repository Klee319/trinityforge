package com.trinityforge.progression;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /tf settings} 最下段のパーティクルシード一覧 (2026-08-25 / W-215、実サーバ報告
 * 「現状何のパーティクルシードがあるかをGUIで確認するすべがない」)。
 *
 * <p>着手前は、解放時のチャット通知を見逃すと<b>自分が何のシードを持っているのか確かめる手段が
 * 1つも無かった</b>(称号とパーティクルには装備画面があるが、シードは道具へ刻印するものなので
 * どの画面にも現れない)。素材が何かも分からないので、金床へ持っていくものが決められない。
 *
 * <p>ここで固定するのは (a) 未解放と解放済みが見分けられること、
 * (b) 解放済みの枠が<b>その素材そのもののアイコン</b>で出ること
 * (プレイヤーが探すのは ID ではなく「どのアイテムを持っていくか」)。
 */
class SettingsGuiParticleSeedRowTest {

    private static final int SEED_INFO_SLOT = 45;
    private static final int SEED_ROW_START = 46;
    private static final int SEED_NEXT_SLOT = 53;

    private static final String OWNED = "seed_flame";
    private static final String LOCKED = "seed_frost";
    /** 特殊アイテム設定(items/catalog.yml)側で material/テクスチャ/レシピを定義したシード。 */
    private static final String CATALOG = "seed_catalog";
    private static final String CATALOG_ITEM = "custom:ember_seed";
    private static final int CATALOG_CMD = 7401;

    private ServerMock server;
    private SettingsGui gui;
    private PlayerMock player;

    private static SpecialRewardsConfig.ParticleSeed seed(String id, Material item) {
        return new SpecialRewardsConfig.ParticleSeed(id, item.name(), Particle.FLAME, 6);
    }

    private static SpecialRewardsConfig.ParticleSeed seed(String id, String itemSpec) {
        return new SpecialRewardsConfig.ParticleSeed(id, itemSpec, Particle.FLAME, 6);
    }

    /** カタログが返す実体の代役: material と custom-model-data(=テクスチャ)と表示名を持つ。 */
    private static ItemStack catalogItem() {
        ItemStack stack = new ItemStack(Material.FIRE_CHARGE, 3);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(CATALOG_CMD);
        meta.displayName(Component.text("燃え種"));
        stack.setItemMeta(meta);
        return stack;
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        SpecialRewardsConfig config = mock(SpecialRewardsConfig.class);
        when(config.titles()).thenReturn(Map.of());
        when(config.particles()).thenReturn(Map.of());
        Map<String, SpecialRewardsConfig.ParticleSeed> seeds = new LinkedHashMap<>();
        seeds.put(OWNED, seed(OWNED, Material.BLAZE_POWDER));
        seeds.put(LOCKED, seed(LOCKED, Material.BLUE_ICE));
        seeds.put(CATALOG, seed(CATALOG, CATALOG_ITEM));
        when(config.particleSeeds()).thenReturn(seeds);

        SpecialRewardService rewardService = mock(SpecialRewardService.class);
        when(rewardService.isUnlocked(any(), eq(OWNED))).thenReturn(true);
        when(rewardService.isUnlocked(any(), eq(LOCKED))).thenReturn(false);
        when(rewardService.isUnlocked(any(), eq(CATALOG))).thenReturn(true);

        gui = new SettingsGui(MockBukkit.createMockPlugin(), config, rewardService,
                id -> CATALOG_ITEM.equals(id) ? java.util.Optional.of(catalogItem()) : java.util.Optional.empty());
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("解放済みは素材アイコン、未解放はバリアで並ぶ")
    void ownedSeedsShowTheirMaterialAndLockedOnesAreHidden() {
        gui.open(player);
        Inventory open = player.getOpenInventory().getTopInventory();

        assertNotNull(open.getItem(SEED_INFO_SLOT), "使い方の見出しが無い");
        assertEquals(Material.ANVIL, open.getItem(SEED_INFO_SLOT).getType(),
                "付与の場が金床であることを見出しで示す");

        ItemStack owned = open.getItem(SEED_ROW_START);
        assertNotNull(owned, "解放済みシードの枠が空");
        assertEquals(Material.BLAZE_POWDER, owned.getType(),
                "金床へ持っていく素材そのものをアイコンにする");

        ItemStack locked = open.getItem(SEED_ROW_START + 1);
        assertNotNull(locked, "未解放シードも枠は出す(存在は見えてよい)");
        assertEquals(Material.BARRIER, locked.getType(), "未解放は素材を明かさない");

        assertNull(open.getItem(SEED_NEXT_SLOT), "3件なら1ページなのでページ送りは出さない");
    }

    /**
     * {@code seed-item: custom:<カタログID>} のシードは、特殊アイテム設定側で決めた
     * material と custom-model-data(=テクスチャ)でアイコンが出ること
     * (2026-08-25、ユーザー要望「seedは特殊アイテム設定みたいな感じでクラフトレシピや
     * material/テクスチャを定義できるようにしたい」)。
     *
     * <p>着手前は custom: 指定を {@link Material} へ解決できないので<b>一律 FIREWORK_STAR</b> に
     * 落としていた。カタログでテクスチャを定義しても一覧には出ず、
     * 「金床へ持っていくのはどれか」が見た目から分からないまま。
     */
    @Test
    @DisplayName("カタログ品のシードはそのカタログ品の見た目(material と custom-model-data)で出る")
    void aCatalogBackedSeedShowsTheCatalogItemsOwnLook() {
        gui.open(player);
        Inventory open = player.getOpenInventory().getTopInventory();

        ItemStack icon = open.getItem(SEED_ROW_START + 2);
        assertNotNull(icon, "カタログ品シードの枠が空");
        assertEquals(Material.FIRE_CHARGE, icon.getType(),
                "カタログ側の material ではなく代用アイコンで出ている");
        assertNotNull(icon.getItemMeta(), "meta が無い");
        assertEquals(CATALOG_CMD, icon.getItemMeta().getCustomModelData(),
                "custom-model-data(テクスチャ)が落ちている");
        assertEquals(1, icon.getAmount(), "一覧のアイコンは個数を持ち込まない");
    }

    @Test
    @DisplayName("7件以上は黙って切らずページ送りへ回す")
    void moreThanOnePageGetsAPageButton() {
        // 称号が1行8件で打ち切られ12件が永久に見えなかった 2026-08-17 の再発防止。
        assertEquals(1, SettingsGui.seedPageCount(6));
        assertEquals(2, SettingsGui.seedPageCount(7));
        assertEquals(1, SettingsGui.seedPageCount(0), "0件でも1ページ扱い");
    }
}
