package com.trinityforge.config.domains;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-07-26 オーバーワールドEXP開放: {@code dungeon-only-exp} と {@code outside-dungeon-exp-rate} の
 * 合成規則({@link SkillExpConfig#worldExpRate(boolean)})。
 *
 * <p>この合成を呼び出し側で書くと、片方を見忘れた経路が静かに全額付与になる(オーバーワールドで
 * ダンジョンと同じ速度で育ってしまい、しかもエラーにならないので気付けない)。1箇所に閉じた以上、
 * その1箇所の規則をここで固定しておく。
 */
class SkillExpWorldRateTest {

    private static SkillExpConfig loaded(Path dir, String yaml) throws Exception {
        Path file = dir.resolve("skill-exp.yml");
        Files.writeString(file, yaml);
        SkillExpConfig config = new SkillExpConfig();
        config.applyFrom(
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile()),
                java.util.logging.Logger.getLogger("SkillExpWorldRateTest"));
        return config;
    }

    @Test
    void dungeonIsAlwaysFullRate(@TempDir Path dir) throws Exception {
        SkillExpConfig config = loaded(dir, "dungeon-only-exp: false\noutside-dungeon-exp-rate: 0.25\n");
        assertEquals(1.0, config.worldExpRate(true));
    }

    @Test
    void outsideDungeonUsesTheConfiguredRate(@TempDir Path dir) throws Exception {
        SkillExpConfig config = loaded(dir, "dungeon-only-exp: false\noutside-dungeon-exp-rate: 0.25\n");
        assertEquals(0.25, config.worldExpRate(false));
    }

    @Test
    void dungeonOnlyFlagStillWinsOverTheRate(@TempDir Path dir) throws Exception {
        // 後方互換の要: 既存サーバが dungeon-only-exp: true のままなら、rate が何であれ従来どおり遮断。
        SkillExpConfig config = loaded(dir, "dungeon-only-exp: true\noutside-dungeon-exp-rate: 1.0\n");
        assertEquals(0.0, config.worldExpRate(false));
        assertEquals(1.0, config.worldExpRate(true));
    }

    @Test
    void rateIsClampedToUnitRange(@TempDir Path dir) throws Exception {
        assertEquals(1.0, loaded(dir, "dungeon-only-exp: false\noutside-dungeon-exp-rate: 9.0\n")
                .worldExpRate(false));
        assertEquals(0.0, loaded(dir, "dungeon-only-exp: false\noutside-dungeon-exp-rate: -3.0\n")
                .worldExpRate(false));
    }

    @Test
    void shippedDefaultsOpenTheOverworldAtAQuarterRate(@TempDir Path dir) throws Exception {
        // キー未記載(=出荷前の古いymlをそのまま使っているサーバ)では dungeon-only-exp の既定 true が
        // 効き、挙動は従来のまま変わらない。開放は出荷yml側で false を書くことで行う。
        SkillExpConfig config = loaded(dir, "{}\n");
        assertEquals(0.0, config.worldExpRate(false));
    }
}
