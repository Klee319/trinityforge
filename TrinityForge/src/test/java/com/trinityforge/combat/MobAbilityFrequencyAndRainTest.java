package com.trinityforge.combat;

import com.trinityforge.config.domains.MobAbilitiesConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-17 回帰。ユーザー報告2件を固定する。
 *
 * <ul>
 *   <li>「矢の雨のダメージが少ない上に、モブ本体から平行に拡散射出されるためプレイヤーに当たらない」</li>
 *   <li>「スキルが常時発動している（バニラの通常行動に1〜3の攻撃スキルを織り交ぜる想定）」</li>
 * </ul>
 */
class MobAbilityFrequencyAndRainTest {

    private static final String ABILITIES = "src/main/resources/combat/mob-abilities.yml";
    private static final Logger LOG = Logger.getLogger("MobAbilityFrequencyAndRainTest");

    private static YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new File(ABILITIES));
    }

    // ------------------------------------------------------------------
    // 矢の雨（頭上から降らせる）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("projectile_rain 型がパースできる")
    void projectileRainTypeParses() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                abilities:
                  rain:
                    type: projectile_rain
                    projectile: ARROW
                    count: 7
                    radius: 1.6
                """);
        MobAbilitiesConfig.ParseResult result =
                MobAbilitiesConfig.parse(cfg.getConfigurationSection("abilities"), LOG);
        assertEquals(0, result.skipped());
        MobAbility rain = result.abilities().get("rain");
        assertNotNull(rain);
        assertEquals(MobAbility.Type.PROJECTILE_RAIN, rain.type());
    }

    @Test
    @DisplayName("出荷の矢の雨は頭上から降る型である（水平の扇に戻すと当たらなくなる）")
    void shippedArrowRainIsNotAHorizontalFan() {
        ConfigurationSection arrowFan = shipped().getConfigurationSection("abilities.arrow_fan");
        assertNotNull(arrowFan, "arrow_fan テンプレートが出荷 yml から消えている");
        assertEquals("projectile_rain", arrowFan.getString("type"),
                "水平の扇に戻すと、開き角のぶんだけ中央以外の矢が最初から相手を向かない");
        assertTrue(arrowFan.getInt("count") >= 3,
                "頭上から降らせる技は本数がそのまま威力なので、少なくすると威力不足の報告に戻る");
        assertTrue(arrowFan.getDouble("radius") > 0.0 && arrowFan.getDouble("radius") <= 3.0,
                "ばら撒く半径を広げるほど当たらなくなる。1〜3 の範囲で調整すること");
    }

    @Test
    @DisplayName("水平の扇（projectile_volley）は開き角 20 度までに抑える")
    void shippedVolleyFansStayNarrowEnoughToHit() {
        ConfigurationSection templates = shipped().getConfigurationSection("abilities");
        assertNotNull(templates);
        for (String id : templates.getKeys(false)) {
            ConfigurationSection entry = templates.getConfigurationSection(id);
            if (entry == null || !"projectile_volley".equals(entry.getString("type"))) {
                continue;
            }
            double spread = entry.getDouble("spread-degrees", 45.0);
            assertTrue(spread <= 20.0,
                    id + ": 開き角 " + spread + " 度は広すぎる。射程 20m なら端の投射物が "
                            + Math.round(20.0 * Math.sin(Math.toRadians(spread / 2.0)))
                            + "m 横を通り過ぎて当たらない");
        }
    }

    // ------------------------------------------------------------------
    // 発動頻度（モブ単位の共通クールダウン）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("global-cooldown-seconds を読む（既定 12 秒）")
    void globalCooldownIsConfigurable() {
        assertEquals(12.0, MobAbilitiesConfig.DEFAULT_GLOBAL_COOLDOWN_SECONDS);
        assertTrue(shipped().getDouble("global-cooldown-seconds", 0.0) > 0.0,
                "0 にすると技を複数持つモブが判定のたびに抽選を引き、常時発動へ戻る");
    }

    @Test
    @DisplayName("共通クールダウンの予約キーは yml の技IDと衝突しえない")
    void globalGapKeyCannotCollideWithAnAbilityId() throws Exception {
        // 予約キーは先頭が空白。パーサは技IDを trim() するので、
        // yml にどう書いても先頭空白のIDは生成されない = 技を1つ潰す事故が起きない。
        assertTrue(MobAbilityTask.GLOBAL_GAP_KEY.startsWith(" "));

        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                abilities:
                  "%s":
                    type: ground_slam
                """.formatted(MobAbilityTask.GLOBAL_GAP_KEY));
        MobAbilitiesConfig.ParseResult result =
                MobAbilitiesConfig.parse(cfg.getConfigurationSection("abilities"), LOG);
        assertNull(result.abilities().get(MobAbilityTask.GLOBAL_GAP_KEY),
                "予約キーそのものの技が作れてしまうと、共通クールダウンがその技を封じてしまう");
        assertNotNull(result.abilities().get(MobAbilityTask.GLOBAL_GAP_KEY.trim()));
    }

    @Test
    @DisplayName("共通クールダウン中は台帳が「撃てない」と答える（発動間隔の実体）")
    void globalGapSuppressesTheNextAbility() {
        long[] now = {0L};
        MobAbilityCooldowns cooldowns = new MobAbilityCooldowns(() -> now[0]);
        java.util.UUID mob = java.util.UUID.randomUUID();

        // 技Aを撃った直後: 技A自身のクールダウン(8秒)も共通クールダウン(12秒)も明けていない。
        cooldowns.arm(mob, "arrow_fan", 8_000L);
        cooldowns.arm(mob, MobAbilityTask.GLOBAL_GAP_KEY, 12_000L);

        // 10秒後 — 技Bは自分のクールダウンが無いので「撃てる」。共通クールダウンが無ければ
        // ここで撃ててしまい、これが「技を足すほど発動間隔が短くなる」の正体だった。
        now[0] = 10_000L;
        assertTrue(cooldowns.ready(mob, "shockwave"), "技B自身のクールダウンは明けている");
        assertTrue(!cooldowns.ready(mob, MobAbilityTask.GLOBAL_GAP_KEY),
                "共通クールダウンが 10 秒で明けてしまうと、技の間合いが作れない");

        now[0] = 12_000L;
        assertTrue(cooldowns.ready(mob, MobAbilityTask.GLOBAL_GAP_KEY));
    }
}
