package com.trinityforge.config.domains;

import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code progression/special-rewards.yml} が書いている粒子名が、<b>追加データ無しで撒ける粒子</b>
 * であることを固定する(2026-08-16)。
 *
 * <p><b>なぜ要るか</b>: {@link SpecialRewardsConfig} の {@code parseParticle} は
 * {@code Particle#getDataType() == Void.class} のものしか通さない。追加データが要る粒子
 * ({@code DUST} は色、{@code FLASH} は Color、{@code DRAGON_BREATH} は Float …)を書くと、
 * <b>その特殊報酬が丸ごと読み込まれず、起動時に警告1行が出るだけ</b>で終わる。
 * 報酬は「付与はされるが何も出ない」ではなく<b>存在しないIDになる</b>ので、
 * それを参照しているアチーブメント側も静かに空振りする。
 *
 * <p>実際 2026-08-16 の実サーバ起動ログで {@code particle_dragon_aura} がこれで落ちていた
 * ({@code DRAGON_BREATH} は 1.21.11 で Float 必須)。粒子はバージョン更新で
 * <b>データ必須側へ移ることがある</b>ので、名前の許可リストではなく
 * {@code getDataType()} を実際に引いて判定する。
 *
 * <p>この検査は Bukkit サーバを立てない: {@code Particle} の {@code dataType} は paper-api の
 * クラス初期化時に確定していて、MockBukkit も CraftBukkit も要らない。
 */
class ShippedSpecialRewardParticleDataTest {

    private static final String PATH = "src/main/resources/" + SpecialRewardsConfig.PATH;

    @Test
    @DisplayName("出荷 special-rewards.yml の粒子はすべて追加データ不要(Void)")
    void everyShippedParticleNeedsNoExtraData() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(PATH));

        List<String> named = new ArrayList<>();
        for (String section : List.of("particles", "particle-seeds")) {
            ConfigurationSection root = yaml.getConfigurationSection(section);
            if (root == null) {
                continue;
            }
            for (String id : root.getKeys(false)) {
                ConfigurationSection entry = root.getConfigurationSection(id);
                String raw = entry == null ? null : entry.getString("particle");
                if (raw != null && !raw.isBlank()) {
                    named.add(section + "." + id + " = " + raw.trim());
                }
            }
        }
        assertTrue(named.size() >= 2,
                "出荷 yml から粒子名が " + named.size() + " 件しか読めていない。"
                        + "節の構造が変わって抽出が壊れていると、この検査は「全部OK」に化ける: " + named);

        List<String> broken = new ArrayList<>();
        for (String line : named) {
            String raw = line.substring(line.indexOf(" = ") + 3);
            Particle particle;
            try {
                particle = Particle.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                broken.add(line + " (この名前の粒子は存在しない)");
                continue;
            }
            if (particle.getDataType() != Void.class) {
                broken.add(line + " (追加データ " + particle.getDataType().getSimpleName() + " が必須)");
            }
        }
        assertEquals(List.of(), broken,
                "追加データが必要な粒子を書いている。SpecialRewardsConfig#parseParticle が弾くので、"
                        + "この特殊報酬は起動時に警告1行だけ残して丸ごと消える(参照しているアチーブメントも空振りする): "
                        + broken);
    }

    @Test
    @DisplayName("この検査の前提: 追加データが必須の粒子が実際に存在する")
    void theGuardIsNotVacuous() {
        // Void しか無い環境(API の差し替え等)では上の検査が何も守らなくなるので、
        // 「弾かれる側が実在する」ことをここで確かめておく。
        assertNotNull(Particle.DUST.getDataType());
        assertTrue(Particle.DUST.getDataType() != Void.class,
                "DUST に追加データが要らなくなった。粒子APIが変わったので上の検査の前提を見直すこと");
    }
}
