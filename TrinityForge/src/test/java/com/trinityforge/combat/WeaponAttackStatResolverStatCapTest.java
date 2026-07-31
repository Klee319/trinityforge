package com.trinityforge.combat;

import com.trinityforge.TrinityForge;
import com.trinityforge.TrinityForgeSingletonTestSupport;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.StatCapsConfig;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-07-31 (F4 指摘4) の回帰テスト: <b>魔法経路の {@code attack-power} も
 * {@code combat/stat-caps.yml} の上限に服する</b>こと。
 *
 * <p>近接は {@code CombatListener} が {@code PlayerCombatAggregate#clamp} を通す
 * ({@link CombatListenerAttackStatCapTest} が固定済み)一方、魔法は ArsPaper フォークが
 * {@link WeaponAttackStatResolver#attackPowerOf} を直読みするため上限を一切通っていなかった。
 * 出荷は {@code stat-caps: {}} なので現状 no-op だが、運用者が上限を入れたときに
 * 「近接だけ従い魔法だけ素通り」という無言のドリフトになるため、ここで両方向を固定する。
 */
class WeaponAttackStatResolverStatCapTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        TrinityForgeSingletonTestSupport.clear();
        MockBukkit.unmock();
    }

    /** attack-power 1000 の武器を1本だけ持つ item-stats と、任意の stat-caps を書き出して読み込む。 */
    private ConfigManager configs(File dir, String statCapsYaml) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  BLAZE_ROD:
                    fixed: { attack-power: 1000.0 }
                """);
        if (statCapsYaml != null) {
            File statCaps = new File(dir, StatCapsConfig.PATH);
            Files.createDirectories(statCaps.getParentFile().toPath());
            Files.writeString(statCaps.toPath(), statCapsYaml);
        }
        return CombatWiringSupport.loadedConfigManager(dir);
    }

    private static WeaponAttackStatResolver injected(ConfigManager cm, StatCapsConfig caps) {
        return new WeaponAttackStatResolver(cm.itemStats(), cm.combatDamage(),
                cm.combatDamage().attackStatKeys(), cm.craftingFeatures(), caps);
    }

    /** 本番と同じ4引数配線(TrinityForge.java 側は変更していない)。 */
    private static WeaponAttackStatResolver productionWired(ConfigManager cm) {
        return new WeaponAttackStatResolver(cm.itemStats(), cm.combatDamage(),
                cm.combatDamage().attackStatKeys(), cm.craftingFeatures());
    }

    private static final String CAP_100 = """
            stat-caps:
              attack-power: 100.0
            """;

    @Test
    @DisplayName("注入した stat-caps の attack-power 上限が魔法経路にも効く")
    void injectedStatCapsClampAttackPower(@TempDir File dir) throws IOException {
        ConfigManager cm = configs(dir, CAP_100);
        double power = injected(cm, cm.statCaps()).attackPowerOf(new ItemStack(Material.BLAZE_ROD));
        assertEquals(100.0, power, 1e-9,
                "杖の attack-power(1000) は stat-caps の上限(100)まで切り詰められる必要がある");
    }

    @Test
    @DisplayName("本番配線(4引数)でも実行中プラグインの stat-caps.yml が効く")
    void productionWiringResolvesStatCapsFromRunningPlugin(@TempDir File dir) throws IOException {
        ConfigManager cm = configs(dir, CAP_100);
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.config()).thenReturn(cm);
        TrinityForgeSingletonTestSupport.set(tf);

        double power = productionWired(cm).attackPowerOf(new ItemStack(Material.BLAZE_ROD));

        assertEquals(100.0, power, 1e-9,
                "配線を変えずに上限を効かせるのが今回の修正の要点"
                        + "(注入版だけ効いて本番配線が素通りだと、テストが緑でも実機で直っていない)");
    }

    @Test
    @DisplayName("上限未設定なら1ビットも変わらない(既定の挙動)")
    void noCapsLeavesAttackPowerUnchanged(@TempDir File dir) throws IOException {
        ConfigManager cm = configs(dir, null);
        assertEquals(1000.0, injected(cm, null).attackPowerOf(new ItemStack(Material.BLAZE_ROD)), 1e-9);
        assertEquals(1000.0, productionWired(cm).attackPowerOf(new ItemStack(Material.BLAZE_ROD)), 1e-9,
                "プラグイン未起動(getInstance()==null)でも例外にせず素通しする");
    }

    @Test
    @DisplayName("上限は他ステと独立(attack-power 以外のキーを書いても影響しない)")
    void unrelatedCapDoesNotAffectAttackPower(@TempDir File dir) throws IOException {
        ConfigManager cm = configs(dir, """
                stat-caps:
                  crit-chance: 0.5
                """);
        assertEquals(1000.0, injected(cm, cm.statCaps()).attackPowerOf(new ItemStack(Material.BLAZE_ROD)), 1e-9);
    }
}
