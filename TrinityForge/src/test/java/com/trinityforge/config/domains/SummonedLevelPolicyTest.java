package com.trinityforge.config.domains;

import com.trinityforge.config.domains.MobTypesConfig.SummonedLevelPolicy;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 召喚モブのレベル決め（{@code combat/mob-types.yml} の {@code summoned:}）。
 *
 * <p>直している不具合: 召喚モブは特別扱いされておらず、周囲の野良モブと同じ
 * 「ワールドスポーンからの距離 × coordinate-coefficient」でレベルが決まっていた。
 * 召喚は拠点や戦闘中のその場で行うので実際には<b>ほぼ常に Lv0</b> になり、
 * 魔法の熟練度が使い魔にまったく反映されていなかった。
 */
class SummonedLevelPolicyTest {

    private static final File SHIPPED =
            new File("src/main/resources/combat/mob-types.yml");

    @Test
    @DisplayName("召喚者のスキルレベルが下駄付きでそのままモブのレベルになる")
    void theCastersSkillLevelBecomesTheMobsLevel() {
        SummonedLevelPolicy policy = new SummonedLevelPolicy(true, "ARS_MAGIC", 1.0, 1, 0);

        assertEquals(1, policy.levelFor(0, 100), "スキル0でも base-level は乗る（乗らないと修正前と同じ Lv0）");
        assertEquals(11, policy.levelFor(10, 100));
        assertEquals(61, policy.levelFor(60, 100));
    }

    @Test
    @DisplayName("係数が1未満でも切り捨てで単調に上がる")
    void aFractionalCoefficientFloorsButStillGrows() {
        SummonedLevelPolicy policy = new SummonedLevelPolicy(true, "ARS_MAGIC", 0.5, 0, 0);

        assertEquals(0, policy.levelFor(1, 100));
        assertEquals(1, policy.levelFor(2, 100));
        assertEquals(30, policy.levelFor(60, 100));
    }

    @Test
    @DisplayName("上限は summoned.max-level が優先、0 なら mob-types の max-level")
    void theCeilingPrefersTheSummonedOverrideAndFallsBackToTheGlobalMax() {
        assertEquals(20, new SummonedLevelPolicy(true, "ARS_MAGIC", 1.0, 0, 20).levelFor(999, 100));
        assertEquals(100, new SummonedLevelPolicy(true, "ARS_MAGIC", 1.0, 0, 0).levelFor(999, 100));
    }

    @Test
    @DisplayName("負のスキルレベルでもレベルは負にならない")
    void aNegativeSkillLevelNeverProducesANegativeMobLevel() {
        assertEquals(0, new SummonedLevelPolicy(true, "ARS_MAGIC", 1.0, 0, 0).levelFor(-5, 100));
    }

    @Test
    @DisplayName("enabled:false / skill 未指定は無効（＝従来どおり距離ベースへ落ちる）")
    void anIncompleteSectionIsTreatedAsDisabled() {
        assertFalse(loadSummoned("summoned:\n  enabled: false\n  skill: ARS_MAGIC\n").enabled());
        assertFalse(loadSummoned("summoned:\n  enabled: true\n").enabled(),
                "skill が無いのに有効化すると、どのスキルを見ればいいか決まらない");
    }

    @Test
    @DisplayName("出荷 mob-types.yml の summoned: が有効で ARS_MAGIC を見ている")
    void theShippedConfigWiresTheMagicSkill() {
        assertTrue(SHIPPED.isFile(), SHIPPED + " が見つからない");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(SHIPPED);
        assertTrue(yaml.getBoolean("summoned.enabled", false),
                "出荷設定で無効だと、召喚モブは Lv0 のままという元の不具合に戻る");
        assertEquals("ARS_MAGIC", yaml.getString("summoned.skill"));
        assertTrue(yaml.getInt("summoned.base-level", 0) >= 1,
                "base-level が0だと魔法レベル0の召喚モブが Lv0 になり、修正前と区別がつかない");
    }

    private static SummonedLevelPolicy loadSummoned(String yaml) {
        return MobTypesConfig.parseSummoned(
                YamlConfiguration.loadConfiguration(new StringReader(yaml))
                        .getConfigurationSection("summoned"),
                java.util.logging.Logger.getLogger("test"));
    }
}
