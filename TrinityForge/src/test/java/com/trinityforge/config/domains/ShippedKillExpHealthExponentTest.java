package com.trinityforge.config.domains;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code stats/skill-exp.yml} が討伐EXPの<b>最大HP項の指数</b>を持っていることを固定する
 * (2026-08-19 ユーザー要望「80lvエンダーマンで約4000へ。ただし序盤の上がり方は変えたくないので
 * HPに応じた経験値指数だけ調整して」)。
 *
 * <p><b>なぜ出荷ymlを直接見るのか</b>: {@code per-max-health-anchor} / {@code -exponent} は
 * <b>キーが無いと既定値(=無効)へ落ちて黙って旧挙動へ戻る</b>。エディタでの保存や別セッションの
 * 編集で1行消えるだけで、エラーもログも出さずに高レベル帯のEXPが2倍に戻る。
 *
 * <p>固定するのは「値がいくつか」ではなく<b>挙動</b>: 序盤帯のHPでは倍率1.0、Lv80 エンダーマン
 * 相当のHPでは約0.44倍。数値そのものを直接 assert すると、同じ挙動を別の (anchor, exponent) の
 * 組で満たしたときに誤検知する。
 */
class ShippedKillExpHealthExponentTest {

    private static final String SKILL_EXP = "src/main/resources/stats/skill-exp.yml";

    /** 討伐EXPを持つ2系統。片方だけ設定されていると「魔法だけ緩い/渋い」になる。 */
    private static final List<String> KILL_EXP_SECTIONS = List.of("combat.kill-exp", "ars-magic.kill-exp");

    /** combat/mob-types.yml の ENDERMAN(base 1120 / growth 1.053 / Lv45+ に +3233/Lv)。 */
    private static double endermanHealth(int level) {
        double value = 1120.0 * Math.pow(1.053, level);
        if (level >= 45) {
            value += 3233.0 * (level - 45);
        }
        return value;
    }

    private static YamlConfiguration shippedSkillExp() throws IOException {
        File file = new File(SKILL_EXP);
        assertTrue(file.isFile(), "出荷 skill-exp.yml が見つからない: " + file.getAbsolutePath());
        return YamlConfiguration.loadConfiguration(
                Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("出荷ymlの指数設定は、序盤帯を1.0倍で通しつつLv80帯を約0.44倍へ圧縮する")
    void shippedExponentKeepsEarlyBandAndHalvesTheHighBand() throws IOException {
        YamlConfiguration yaml = shippedSkillExp();
        for (String section : KILL_EXP_SECTIONS) {
            double perMaxHealth = yaml.getDouble(section + ".per-max-health", -1.0);
            assertTrue(perMaxHealth > 0.0, section + ".per-max-health が読めない");
            double anchor = yaml.getDouble(section + ".per-max-health-anchor", 0.0);
            double exponent = yaml.getDouble(section + ".per-max-health-exponent", 1.0);
            assertTrue(anchor > 0.0 && exponent > 0.0 && exponent < 1.0,
                    section + " の最大HP項の指数が無効化されている(キー消失で旧挙動へ戻っている): "
                            + "anchor=" + anchor + " exponent=" + exponent);

            // 序盤(anchor 以下のHP)は完全に線形のまま。
            double earlyHealth = endermanHealth(20);
            assertTrue(earlyHealth < anchor,
                    "Lv20 のHPが anchor を超えている(圧縮が序盤へ食い込んでいる): " + earlyHealth);
            assertEquals(perMaxHealth * earlyHealth,
                    com.trinityforge.stats.KillExpHealthTerm.healthTerm(
                            perMaxHealth, earlyHealth, anchor, exponent),
                    1e-9, section + ": 序盤のHP項が変わってしまっている");

            // Lv80 帯は約0.44倍(報告値 8000〜10000 → 約4000)。
            double lateHealth = endermanHealth(80);
            double ratio = com.trinityforge.stats.KillExpHealthTerm.healthTerm(
                    perMaxHealth, lateHealth, anchor, exponent) / (perMaxHealth * lateHealth);
            assertTrue(ratio > 0.40 && ratio < 0.48,
                    section + ": Lv80 の倍率が狙い(約0.44)から外れている: " + ratio);
        }
    }

    @Test
    @DisplayName("2系統(武器/Ars魔法)の指数設定は一致している")
    void bothKillExpSectionsShareTheSameCurve() throws IOException {
        YamlConfiguration yaml = shippedSkillExp();
        assertEquals(yaml.getDouble("combat.kill-exp.per-max-health-anchor", 0.0),
                yaml.getDouble("ars-magic.kill-exp.per-max-health-anchor", 0.0), 1e-9,
                "anchor が武器とArs魔法でずれている(どちらかだけ育ちやすくなる)");
        assertEquals(yaml.getDouble("combat.kill-exp.per-max-health-exponent", 1.0),
                yaml.getDouble("ars-magic.kill-exp.per-max-health-exponent", 1.0), 1e-9,
                "exponent が武器とArs魔法でずれている(どちらかだけ育ちやすくなる)");
    }
}
