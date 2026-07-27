package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Parity lock (#6 / verifier F3-F5): {@link PlayerStatAggregator#equipmentDefenseItemStats} が、同じ装備を
 * プレイヤーが着けた時に {@link PlayerDefenseResolver} が {@link DefenseStatBridge} へ渡す item 入力
 * ({@code agg.applyMultipliers(agg.item())}) と<b>完全一致</b>することを保証する。DPSChecker のダミーは
 * この公開APIでプレイヤーと同一の防御を得るため、両者の乖離は将来のリグレッションになる。
 *
 * <p>1つのローダウトで F3(オフハンドゲート {@code offhand-stats-apply})・F4(乗算レイヤ {@code multipliers})・
 * F5(メインハンド武器の防御ステ折込)を同時に検証する。
 */
class EquipmentDefenseItemStatsTest {

    private static final String FLAT_DEFENSE = StatKeys.canonical("flat-defense");
    private static final String DAMAGE_REDUCTION = StatKeys.canonical("damage-reduction");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * 胸当て=flat-defense(乗算レイヤ burst x1.5 付き=F4)、剣(メインハンド)=damage-reduction(=F5 武器防御ステ)、
     * 盾(オフハンド)=damage-reduction + {@code offhand-stats-apply}(=F3)。
     */
    private void writeItemStats(File dir, boolean shieldOffhandApplies) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  DIAMOND_CHESTPLATE:
                    fixed: { flat-defense: 3.0 }
                    multipliers:
                      burst:
                        fixed: { flat-defense: 1.5 }
                  DIAMOND_SWORD:
                    fixed: { damage-reduction: 0.05 }
                  SHIELD:
                    fixed: { damage-reduction: 0.20 }
                    offhand-stats-apply: %s
                """.formatted(shieldOffhandApplies));
    }

    private record Fixture(ConfigManager cm, CombatDamageConfig damage, PlayerStatAggregator aggregator) {}

    private Fixture fixture(File dir, boolean shieldOffhandApplies) throws IOException {
        writeItemStats(dir, shieldOffhandApplies);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> List.of());
        PlayerStatAggregator aggregator =
                new PlayerStatAggregator(cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        return new Fixture(cm, damage, aggregator);
    }

    private Player fullyEquipped() {
        Player player = server.addPlayer();
        PlayerInventory inv = player.getInventory();
        inv.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        inv.setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        inv.setItemInOffHand(new ItemStack(Material.SHIELD));
        return player;
    }

    private Map<String, Double> equipmentStats(Fixture f, Player player) {
        PlayerInventory inv = player.getInventory();
        return PlayerStatAggregator.equipmentDefenseItemStats(
                inv.getArmorContents(), inv.getItemInMainHand(), inv.getItemInOffHand(),
                f.cm().itemStats(), f.damage());
    }

    @Test
    void matchesPlayerDefenseInput_offhandExcluded(@TempDir File dir) throws IOException {
        Fixture f = fixture(dir, false);
        Player player = fullyEquipped();

        PlayerCombatAggregate agg = f.aggregator().aggregate(player);
        Map<String, Double> playerInput = agg.applyMultipliers(agg.item());
        Map<String, Double> equip = equipmentStats(f, player);

        // 完全一致: プレイヤーが着た時に bridge へ渡る item 入力と、ダミーの装備由来 item 入力が同一。
        assertEquals(playerInput, equip,
                "装備由来 item 防御はプレイヤー防御入力(進行系除く)と完全一致すること");
        // F4: 胸当て flat-defense 3.0 × 乗算 burst(x1.5) = 4.5。
        assertEquals(4.5, equip.getOrDefault(FLAT_DEFENSE, 0.0), 1e-9,
                "乗算レイヤ(F4)が適用され flat-defense=3.0×1.5=4.5");
        // F3: offhand-stats-apply=false のため盾(0.20)は含まず、剣(0.05, F5)のみ。
        assertEquals(0.05, equip.getOrDefault(DAMAGE_REDUCTION, 0.0), 1e-9,
                "offhand-stats-apply=false: damage-reductionはメインハンド剣(0.05)のみ、盾(0.20)を含まない");
    }

    @Test
    void matchesPlayerDefenseInput_offhandIncluded(@TempDir File dir) throws IOException {
        Fixture f = fixture(dir, true);
        Player player = fullyEquipped();

        PlayerCombatAggregate agg = f.aggregator().aggregate(player);
        Map<String, Double> playerInput = agg.applyMultipliers(agg.item());
        Map<String, Double> equip = equipmentStats(f, player);

        assertEquals(playerInput, equip,
                "オフハンド合算ONでもプレイヤー防御入力と完全一致すること");
        // F3+F5: offhand-stats-apply=true → 剣(0.05)+盾(0.20)=0.25。
        assertEquals(0.25, equip.getOrDefault(DAMAGE_REDUCTION, 0.0), 1e-9,
                "offhand-stats-apply=true: damage-reductionは剣(0.05)+盾(0.20)=0.25");
    }

    @Test
    void heldArmorInMainhand_doesNotContribute(@TempDir File dir) throws IOException {
        // 着用専用防具を手持ち(メインハンド)にしても寄与しない — プレイヤー防御の isWornOnlyArmor 規則と一致。
        Fixture f = fixture(dir, false);
        Player player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_CHESTPLATE));

        Map<String, Double> equip = equipmentStats(f, player);

        assertFalse(equip.containsKey(FLAT_DEFENSE),
                "手持ちの胸当ての flat-defense は装備由来 item に入らない(着用専用防具の折込除外)");
    }

    @Test
    void noEquipment_yieldsEmptyStats(@TempDir File dir) throws IOException {
        // 空装備(AIR)は寄与ゼロ = 手動プロファイルへ復帰する前提。
        Fixture f = fixture(dir, false);
        Player player = server.addPlayer();

        Map<String, Double> equip = equipmentStats(f, player);

        assertFalse(equip.containsKey(FLAT_DEFENSE), "無装備なら flat-defense は現れない");
        assertFalse(equip.containsKey(DAMAGE_REDUCTION), "無装備なら damage-reduction は現れない");
    }
}
