package com.trinityforge.config.domains;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code progression/special-rewards.yml} のパーティクルシードが
 * <b>実際のローダを通って</b>読めることを固定する (2026-08-25 / W-221)。
 *
 * <p><b>yml を直接読む検査では足りない</b> ── {@code clears: true} のシードは粒子を持たないので、
 * ローダ側の「particle が無ければ捨てる」判定を通り抜けられているかどうかがここの本題。
 * ここを素通しにすると、消すシードは<b>config に書いてあるのに存在しない</b>（起動時の警告1行だけ）
 * という、金床に何も出ない形で壊れる。
 */
class ShippedParticleSeedClearAndDisplayTest {

    private static final String PATH = "src/main/resources/" + SpecialRewardsConfig.PATH;
    private static final Logger LOG = Logger.getLogger("ShippedParticleSeedClearAndDisplayTest");

    private static Map<String, SpecialRewardsConfig.ParticleSeed> shippedSeeds() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(PATH));
        SpecialRewardsConfig.ParseResult result = SpecialRewardsConfig.parse(yaml, LOG);
        return result.particleSeeds();
    }

    @Test
    @DisplayName("刻印を消すシードが1つ以上あり、粒子が無くても捨てられない")
    void aClearingSeedShipsAndSurvivesTheLoader() {
        Map<String, SpecialRewardsConfig.ParticleSeed> seeds = shippedSeeds();
        assertFalse(seeds.isEmpty(), "出荷シードが1件も読めていない(節の構造が変わった?)");

        List<String> clearing = seeds.values().stream()
                .filter(SpecialRewardsConfig.ParticleSeed::clears)
                .map(SpecialRewardsConfig.ParticleSeed::id)
                .toList();
        assertEquals(1, clearing.size(),
                "刻印を消すシードがちょうど1件でない(0件なら消す手段が無い): " + clearing);

        SpecialRewardsConfig.ParticleSeed clear = seeds.get(clearing.get(0));
        assertNotNull(clear.seedItem(), "消すシードに素材が無い");
        assertFalse(clear.seedItem().isBlank(), "消すシードの素材が空");
    }

    @Test
    @DisplayName("すべてのシードに日本語の表示名が付いている(lore に ID が出ないこと)")
    void everyShippedSeedHasADisplayName() {
        List<String> missing = new ArrayList<>();
        for (SpecialRewardsConfig.ParticleSeed seed : shippedSeeds().values()) {
            // displayName() は未設定のとき ID を返す。ID がそのまま lore に出るのは
            // 「日本語で表記」の要望を満たしていないので、ここで落とす。
            if (seed.displayName().equals(seed.id())) {
                missing.add(seed.id());
            }
        }
        assertEquals(List.of(), missing, "display 未設定のシード(lore に ID がそのまま出る): " + missing);
    }

    @Test
    @DisplayName("消さないシードは従来どおり粒子を持つ")
    void nonClearingSeedsStillCarryAParticle() {
        List<String> broken = new ArrayList<>();
        for (SpecialRewardsConfig.ParticleSeed seed : shippedSeeds().values()) {
            if (!seed.clears() && seed.particle() == null) {
                broken.add(seed.id());
            }
        }
        assertTrue(broken.isEmpty(), "粒子の無い通常シードがある(発動時に何も出ない): " + broken);
    }
}
