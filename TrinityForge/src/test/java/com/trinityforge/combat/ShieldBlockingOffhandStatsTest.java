package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.stats.ItemStatProfile;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W-163 (2026-08-20): 盾は<b>構えている間だけ</b>ステを乗せる。
 *
 * <p>それまで盾には TF のステが1つも無く、さらにバニラの盾軽減({@code BLOCKING} modifier)は
 * 「イベント生成時にバニラのダメージ規模で確定した絶対値」であるのに対し、TF は
 * {@code CombatListener} で BASE を自前の(桁違いに大きい)値へ書き換えるため、盾の寄与が
 * 相対的に誤差へ潰れていた ── 構えても構えなくても被ダメージが変わらなかった。
 *
 * <p>ここで固定するのは「門の挙動」そのもの:
 * {@code offhand-stats-require-blocking: true} の品は構えている間だけ寄与し、
 * 既定(false)の品は<b>従来どおり</b>持っているだけで寄与する(既存のオフハンド品の回帰防止)。
 */
class ShieldBlockingOffhandStatsTest {

    private static final String DAMAGE_REDUCTION = StatKeys.canonical("damage-reduction");

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** オフハンドの盾だけを持つ最小構成。{@code requireBlocking} だけを振る。 */
    private static void writeItemStats(File dir, boolean requireBlocking) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  SHIELD:
                    fixed: { damage-reduction: 0.20 }
                    offhand-stats-apply: true
                    offhand-stats-require-blocking: %s
                """.formatted(requireBlocking));
    }

    private static Map<String, Double> offhandShieldStats(File dir, boolean requireBlocking, boolean blocking)
            throws IOException {
        writeItemStats(dir, requireBlocking);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        return PlayerStatAggregator.equipmentDefenseItemStats(
                new ItemStack[0], null, new ItemStack(Material.SHIELD), blocking,
                cm.itemStats(), damage);
    }

    @Test
    void requireBlocking_構えていなければ盾のステは一切乗らない(@TempDir File dir) throws IOException {
        Map<String, Double> stats = offhandShieldStats(dir, true, false);

        assertFalse(stats.containsKey(DAMAGE_REDUCTION),
                "構えていないのに盾の damage-reduction が乗っている: " + stats);
    }

    @Test
    void requireBlocking_構えている間だけ盾のステが乗る(@TempDir File dir) throws IOException {
        Map<String, Double> stats = offhandShieldStats(dir, true, true);

        assertEquals(0.20, stats.getOrDefault(DAMAGE_REDUCTION, 0.0), 1e-9,
                "構えているのに盾の damage-reduction が乗っていない: " + stats);
    }

    /**
     * 既定(false)の回帰防止。{@code offhand-stats-apply: true} だけを書いた従来のオフハンド品は、
     * 構えているかどうかに関係なく<b>常に</b>寄与し続けること ── 新しい門を足したせいで
     * 既存のオフハンド品が黙って死ぬのを防ぐ。
     */
    @Test
    void 既定では構えの有無に関係なく従来どおり寄与する(@TempDir File notBlockingDir, @TempDir File blockingDir)
            throws IOException {
        assertEquals(0.20, offhandShieldStats(notBlockingDir, false, false)
                        .getOrDefault(DAMAGE_REDUCTION, 0.0), 1e-9,
                "require-blocking 未指定なのに、構えていないと寄与しなくなっている");
        assertEquals(0.20, offhandShieldStats(blockingDir, false, true)
                        .getOrDefault(DAMAGE_REDUCTION, 0.0), 1e-9,
                "require-blocking 未指定なのに、構えていると寄与しなくなっている");
    }

    /**
     * 出荷 {@code stats/item-stats.yml} の実バイト列を読み、盾が
     * 「構えている間だけ乗る」設定で<b>実際に配線されている</b>ことを固定する。
     * 機構だけ直して出荷値を入れ忘れると、盾は今までどおり何も起きないまま。
     */
    @Test
    void 出荷のitemStatsで盾が構え限定として配線されている(@TempDir File dir) throws IOException {
        File shipped = new File("src/main/resources/" + ItemStatsConfig.PATH);
        assertTrue(shipped.isFile(), "出荷 item-stats.yml が見つからない: " + shipped.getAbsolutePath());

        File copy = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(copy.getParentFile().toPath());
        Files.copy(shipped.toPath(), copy.toPath());

        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        Optional<ItemStatProfile> shield = cm.itemStats().profileFor(Material.SHIELD, null);

        assertTrue(shield.isPresent(), "出荷 item-stats.yml に SHIELD の定義が無い");
        assertTrue(shield.get().offhandApplies(),
                "SHIELD に offhand-stats-apply: true が無いのでオフハンドで一切寄与しない");
        assertTrue(shield.get().offhandRequiresBlocking(),
                "SHIELD に offhand-stats-require-blocking: true が無いので持っているだけで乗ってしまう");
        assertTrue(shield.get().fixed().getOrDefault(DAMAGE_REDUCTION, 0.0) > 0.0,
                "SHIELD の damage-reduction が 0 なので構えても被ダメージが変わらない: "
                        + shield.get().fixed());
    }
}
