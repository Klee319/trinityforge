package com.trinityforge.combat;

import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.UseRequirementsConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.UseRequirementResolver;
import com.trinityforge.progression.UseRequirementService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code use-role}（ロール専用装備、2026-08-02 柱7）のゲート検証。
 *

 * <p>{@code CombatWiringSupport} が package-private なのでこのテストは {@code com.trinityforge.combat}
 * に置く（呼べるようにするためだけに可視性を広げない）。
 *
 * <p>要件が1箇所（{@link UseRequirementService#denialFor}）に集まっているので、
 * 近接/弓/ツール/防具装備/Ars触媒詠唱の全経路がここを通る。逆に言うとここが緩むと全部緩む。
 */
class UseRoleGateTest {

    private static final String YAML = """
            items:
              LEATHER_CHESTPLATE#5760:
                use-role: swordfighter
                fixed: { phys-flat-defense: 1 }
              LEATHER_HELMET#5760:
                use-skill: LIGHT_ARMOR
                use-level-requirement: 30
                use-role: fisher
                fixed: { phys-flat-defense: 1 }
              LEATHER_BOOTS:
                use-skill: LIGHT_ARMOR
                use-level-requirement: 30
                fixed: { phys-flat-defense: 1 }
            """;

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("use-role だけの装備は、その職業に就いていないと使えない")
    void roleOnlyItemIsGatedByRole(@TempDir File dir) throws IOException {
        UseRequirementService gate = gate(dir, Map.of());
        ItemStack item = stack(Material.LEATHER_CHESTPLATE, 5760);

        Player wrong = server.addPlayer();
        PlayerData.of(wrong).setRolePrimary("tank");
        assertTrue(gate.denialFor(wrong, item).isPresent(), "職業が違えば使えないはず");

        Player right = server.addPlayer();
        PlayerData.of(right).setRolePrimary("swordfighter");
        assertFalse(gate.denialFor(right, item).isPresent(), "職業が一致すれば使えるはず");

        Player none = server.addPlayer();
        assertTrue(gate.denialFor(none, item).isPresent(), "職業未選択なら使えないはず");
    }

    @Test
    @DisplayName("補助職の枠で一致しても使える(戦闘職枠だけを見ない)")
    void supportSlotAlsoSatisfiesTheRole(@TempDir File dir) throws IOException {
        UseRequirementService gate = gate(dir, Map.of("LIGHT_ARMOR", 99));
        ItemStack item = stack(Material.LEATHER_HELMET, 5760);

        Player player = server.addPlayer();
        PlayerData.of(player).setRolePrimary("tank");
        PlayerData.of(player).setRoleSupport("fisher");
        assertFalse(gate.denialFor(player, item).isPresent());
    }

    @Test
    @DisplayName("レベルを満たしていても職業が違えば拒否され、文言も職業のものになる")
    void levelMetButRoleMismatchStillDenies(@TempDir File dir) throws IOException {
        UseRequirementService gate = gate(dir, Map.of("LIGHT_ARMOR", 99));
        ItemStack item = stack(Material.LEATHER_HELMET, 5760);

        Player player = server.addPlayer();
        PlayerData.of(player).setRolePrimary("miner");
        UseRequirementResolver.Resolved denial = gate.denialFor(player, item).orElseThrow();
        assertEquals("fisher", denial.role());
        assertTrue(UseRequirementService.roleDenialMessage(denial).toString().contains("fisher"));
    }

    @Test
    @DisplayName("use-role が無い装備は職業を問わない(後方互換)")
    void itemsWithoutRoleAreUnaffected(@TempDir File dir) throws IOException {
        UseRequirementService gate = gate(dir, Map.of("LIGHT_ARMOR", 99));
        ItemStack item = new ItemStack(Material.LEATHER_BOOTS);

        Player player = server.addPlayer();
        assertFalse(gate.denialFor(player, item).isPresent(),
                "職業未選択でも use-role の無い装備は使えるはず");
    }

    private UseRequirementService gate(File dir, Map<String, Integer> levels) throws IOException {
        File file = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), YAML);
        // enforce のコード既定は true(出荷 yml と同じ)。明示しておくのは、このテストが
        // 「ゲートが掛かること」を検証していて、キー欠落で素通りしたら検査が消えるため。
        File gateFile = new File(dir, UseRequirementsConfig.PATH);
        Files.createDirectories(gateFile.getParentFile().toPath());
        Files.writeString(gateFile.toPath(), "enforce: true\n");
        var configs = CombatWiringSupport.loadedConfigManager(dir);
        ItemStatsConfig itemStats = configs.itemStats();
        UseRequirementsConfig enforced = configs.useRequirements();
        assertTrue(enforced.enforce(), "enforce=true で読めていない(テストが素通りする)");
        return new UseRequirementService(enforced, itemStats, playerId -> levels);
    }

    private static ItemStack stack(Material material, int cmd) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> meta.setCustomModelData(cmd));
        return stack;
    }
}
