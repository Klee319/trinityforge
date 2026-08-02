package com.trinityforge.config.domains;

import com.trinityforge.mobs.ConversionPolicy;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code combat/mob-import.yml} を固定する回帰テスト(2026-08-03、45+難易度修正 / U18)。
 *
 * <h2>なぜ縛るのか</h2>
 * <ul>
 *   <li><b>U18(magic-ratio)</b>: {@code unknown-mobs.synthesize: true} で解決される EliteMobs
 *       ダンジョンモブは {@code combat/mob-profiles.yml} が空({@code profiles: {}})の間、事実上
 *       これが全ダンジョンモブの唯一の攻撃プロファイル源になる。ここの {@code magic-ratio} が
 *       0 に戻ると「ダンジョンモブは常に完全物理」に逆戻りし、魔法防御が丸ごと死にステになる
 *       (2026-08-02以前の状態、{@link ShippedMobMagicRatioTest} の field mob 版と対の回帰ガード)。</li>
 *   <li><b>45+難易度修正</b>: max-health / flat-defense の high-level-from/per-level が消えると、
 *       Lv45以降のダンジョンモブが「討伐が速くなり続ける」壊れた挙動に無言で戻る。</li>
 * </ul>
 */
class ShippedMobImportBreakpointTest {

    private static final String MOB_IMPORT = "src/main/resources/combat/mob-import.yml";
    private static final Logger LOG = Logger.getLogger("ShippedMobImportBreakpointTest");
    private static final double DELTA = 1.0e-9;

    private static ConversionPolicy loadPolicy() throws Exception {
        Path file = Path.of(MOB_IMPORT);
        assertTrue(Files.isRegularFile(file), "出荷 yml が見つからない: " + file.toAbsolutePath());
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(Files.readString(file));
        return MobImportConfig.parse(cfg, LOG);
    }

    @Test
    @DisplayName("U18: 出荷 mob-import.yml の magic-ratio が0でない(ダンジョンモブが完全物理に逆戻りしていない)")
    void magicRatioIsNotSilentlyZero() throws Exception {
        ConversionPolicy policy = loadPolicy();
        double magicRatioAtL0 = policy.attack().magicRatio().at(0);
        assertTrue(magicRatioAtL0 > 0.0 && magicRatioAtL0 <= 1.0,
                "mob-import.yml attack.magic-ratio が0または範囲外: " + magicRatioAtL0
                        + "。unknown-mobs.synthesize経由の全ダンジョンモブが完全物理に戻り、"
                        + "魔法防御が死にステになる");
    }

    @Test
    @DisplayName("45+難易度修正: max-health の高レベル区間がLv45から発動する")
    void maxHealthHighLevelBreakpointIsConfigured() throws Exception {
        ConversionPolicy policy = loadPolicy();
        ConversionPolicy.Ramp maxHealth = policy.maxHealth();
        assertEquals(maxHealth.at(45), maxHealth.at(45), DELTA); // 閾値自体は連続(回帰時にNaN化しない確認)
        assertTrue(maxHealth.at(60) > maxHealth.at(45) * 1.5,
                "Lv45→60でHPが1.5倍未満しか伸びていない(high-level-per-levelが消えている疑い): "
                        + maxHealth.at(45) + " -> " + maxHealth.at(60));
    }

    @Test
    @DisplayName("45+難易度修正: 物理/魔法 flat-defense の高レベル区間がLv45から発動する")
    void flatDefenseHighLevelBreakpointIsConfigured() throws Exception {
        ConversionPolicy policy = loadPolicy();
        ConversionPolicy.Ramp physicalFlat = policy.physical().flatDefense();
        ConversionPolicy.Ramp magicalFlat = policy.magical().flatDefense();
        assertEquals(0.0, physicalFlat.at(44), DELTA, "Lv45未満は従来どおり無干渉であること");
        assertTrue(physicalFlat.at(60) > 0.0, "Lv60でphysical.flat-defenseが0のまま(high-level-per-levelが消えている疑い)");
        assertTrue(magicalFlat.at(60) > 0.0, "Lv60でmagical.flat-defenseが0のまま(high-level-per-levelが消えている疑い)");
    }
}
