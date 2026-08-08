package com.trinityforge.stats;

import com.trinityforge.config.domains.EquipmentAssetsConfig;
import com.trinityforge.pdc.BindType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 防具の「装備したときの見た目」割り当て（{@code items/equipment-assets.yml} → {@code ItemFactory}）。
 *
 * <p>守りたい不変条件は 3 つで、どれも壊れると<b>無言で</b>おかしくなる:
 *
 * <ol>
 *   <li>割り当ての無いアイテムには {@code asset_id} を書かない
 *       — 書いてしまうとパックに定義が無い場合その防具が透明になる</li>
 *   <li>書くときは既存の {@code equippable} を残したまま {@code asset_id} だけ差し替える
 *       — 丸ごと差し替えると装備スロットが既定へ巻き戻り、防具が着られなくなる</li>
 *   <li>そもそも装備できないアイテム（{@code equippable} 自体が無い）には触らない</li>
 * </ol>
 */
class EquipmentAssetStampTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static EquipmentAssetsConfig configFrom(String yaml) {
        EquipmentAssetsConfig config = new EquipmentAssetsConfig();
        config.parse(YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml))
                .getConfigurationSection("equipment-assets"), Logger.getLogger("test"));
        return config;
    }

    private static ItemFactory factoryWith(EquipmentAssetsConfig equipmentAssets) {
        ItemAssembler assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenReturn(0);
        return new ItemFactory(assembler, null, null, equipmentAssets);
    }

    private static ItemTemplate template(String id, Material material) {
        return new ItemTemplate(id, material, null, null, BindType.TRADEABLE, 0, null, List.of());
    }

    /**
     * ⚠ MockBukkit は<b>バニラ既定のデータコンポーネントを持たない</b>。実サーバでは
     * {@code new ItemStack(DIAMOND_HELMET).getData(EQUIPPABLE)} が非 null だが、ここでは null。
     * なので「防具に見立てたスタック」はテスト側で明示的に組む。組めない（=MockBukkit が
     * データコンポーネント自体を保持しない）なら、そのことを assert で落として気づけるようにする
     * — 黙って skip すると、肝心の差し替えが一度も走らないまま緑になる。
     */
    private static ItemStack equippableStack(Material material, EquipmentSlot slot) {
        ItemStack stack = new ItemStack(material);
        stack.setData(DataComponentTypes.EQUIPPABLE,
                Equippable.equippable(slot)
                        .equipSound(Key.key("minecraft", "item.armor.equip_diamond"))
                        .dispensable(false));
        assertNotNull(stack.getData(DataComponentTypes.EQUIPPABLE),
                "MockBukkit が equippable を保持しない。このテストは何も検証できていないので直すこと");
        return stack;
    }

    @Test
    void stampingAnAssetKeepsEveryOtherFieldOfTheEquippableComponent() {
        ItemStack helmet = equippableStack(Material.DIAMOND_HELMET, EquipmentSlot.HEAD);
        Equippable before = helmet.getData(DataComponentTypes.EQUIPPABLE);

        assertTrue(ItemFactory.applyEquipmentAsset(helmet, "infinity"));

        Equippable after = helmet.getData(DataComponentTypes.EQUIPPABLE);
        assertEquals("trinityforge:infinity", after.assetId().asString());
        // asset_id 以外は据え置き。丸ごと差し替えるとスロットが既定へ戻り「着られない防具」になる。
        assertEquals(before.slot(), after.slot());
        assertEquals(before.equipSound(), after.equipSound());
        assertEquals(before.dispensable(), after.dispensable());
        assertEquals(before.swappable(), after.swappable());
        assertEquals(before.damageOnHurt(), after.damageOnHurt());
    }

    @Test
    void aNonEquippableItemNeverGrowsAnEquippableComponent() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);

        assertFalse(ItemFactory.applyEquipmentAsset(sword, "infinity"));

        assertNull(sword.getData(DataComponentTypes.EQUIPPABLE),
                "装備できないアイテムに equippable を生やしてはいけない");
    }

    @Test
    void anItemNotListedInTheYmlIsNeverStamped() {
        EquipmentAssetsConfig config = configFrom("equipment-assets: {}\n");

        ItemStack stamped = factoryWith(config).create(template("infinity_helmet", Material.DIAMOND_HELMET), 1L, 0);

        assertNull(stamped.getData(DataComponentTypes.EQUIPPABLE),
                "未配線の防具に asset_id を書くと、パックに定義が無い場合その防具が透明になる");
        assertNull(config.assetFor("infinity_helmet"));
    }

    @Test
    void theSameItemListedInTwoSetsIsReportedAndResolvedByLastWins() {
        EquipmentAssetsConfig config = configFrom("""
                equipment-assets:
                  alpha:
                    items:
                      - shared_helmet
                  beta:
                    items:
                      - shared_helmet
                """);
        assertEquals("beta", config.assetFor("shared_helmet"));
    }

    @Test
    void anEntryWithoutItemsIsIgnoredRatherThanWiredEmpty() {
        EquipmentAssetsConfig config = configFrom("""
                equipment-assets:
                  broken:
                    layers:
                      - humanoid
                """);
        assertTrue(config.bindings().isEmpty());
    }
}
