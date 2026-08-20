package com.trinityforge.config.domains;

import com.trinityforge.config.domains.MobTypesConfig.TamedLevelPolicy;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手懐けた友好モブのレベル決め（{@code combat/mob-types.yml} の {@code tamed:}、M-2）。
 *
 * <p>直している不具合: 手懐けた狼/猫/馬等は特別扱いされておらず、周囲の野良モブと同じ
 * 「ワールドスポーンからの距離 × coordinate-coefficient」でレベルが決まっていた
 * (拠点で懐けるので実質常にLv0)。飼い主がどれだけ熟練しても手懐けモブが育たなかった。
 *
 * <p>{@code summoned:}({@link SummonedLevelPolicyTest}) と違い、{@link TamedLevelPolicy} は
 * 単一スキルを参照しない（テイム専用スキルが存在しないため）。飼い主の総合戦闘レベルを
 * そのまま {@link TamedLevelPolicy#levelFor} の引数として渡す設計。
 */
class TamedLevelPolicyTest {

    private static final File SHIPPED =
            new File("src/main/resources/combat/mob-types.yml");

    @Test
    @DisplayName("飼い主の総合戦闘レベルが下駄付きでそのまま手懐けモブのレベルになる")
    void theOwnersCombatLevelBecomesTheMobsLevel() {
        TamedLevelPolicy policy = new TamedLevelPolicy(true, 1.0, 1, 0);

        assertEquals(1, policy.levelFor(0, 100), "総合戦闘レベル0でも base-level は乗る（乗らないと修正前と同じ Lv0）");
        assertEquals(11, policy.levelFor(10, 100));
        assertEquals(61, policy.levelFor(60, 100));
    }

    @Test
    @DisplayName("係数が1未満でも切り捨てで単調に上がる")
    void aFractionalCoefficientFloorsButStillGrows() {
        TamedLevelPolicy policy = new TamedLevelPolicy(true, 0.5, 0, 0);

        assertEquals(0, policy.levelFor(1, 100));
        assertEquals(1, policy.levelFor(2, 100));
        assertEquals(30, policy.levelFor(60, 100));
    }

    @Test
    @DisplayName("上限は tamed.max-level が優先、0 なら mob-types の max-level")
    void theCeilingPrefersTheTamedOverrideAndFallsBackToTheGlobalMax() {
        assertEquals(20, new TamedLevelPolicy(true, 1.0, 0, 20).levelFor(999, 100));
        assertEquals(100, new TamedLevelPolicy(true, 1.0, 0, 0).levelFor(999, 100));
    }

    @Test
    @DisplayName("負の総合戦闘レベルでもモブのレベルは負にならない")
    void aNegativeCombatLevelNeverProducesANegativeMobLevel() {
        assertEquals(0, new TamedLevelPolicy(true, 1.0, 0, 0).levelFor(-5, 100));
    }

    @Test
    @DisplayName("enabled:false は無効（＝従来どおり EntityType の通常値へ落ちる）")
    void aDisabledSectionIsTreatedAsDisabled() {
        assertFalse(loadTamed("tamed:\n  enabled: false\n").enabled());
    }

    @Test
    @DisplayName("tamed: セクション自体が無い場合も無効")
    void aMissingSectionIsTreatedAsDisabled() {
        assertFalse(MobTypesConfig.parseTamed(null, java.util.logging.Logger.getLogger("test")).enabled());
    }

    @Test
    @DisplayName("summoned: と違い skill キーを要求しない（enabled:true だけで有効化できる）")
    void enablingDoesNotRequireASkillKey() {
        assertTrue(loadTamed("tamed:\n  enabled: true\n").enabled(),
                "テイム専用スキルは存在しないため、skill キーが無くても有効化できて良い");
    }

    @Test
    @DisplayName("出荷 mob-types.yml の tamed: が有効")
    void theShippedConfigEnablesTamedLeveling() {
        assertTrue(SHIPPED.isFile(), SHIPPED + " が見つからない");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(SHIPPED);
        assertTrue(yaml.getBoolean("tamed.enabled", false),
                "出荷設定で無効だと、手懐けモブは Lv0 のままという元の不具合に戻る");
        assertTrue(yaml.getInt("tamed.base-level", 0) >= 1,
                "base-level が0だと総合戦闘レベル0の飼い主の手懐けモブがLv0になり、修正前と区別がつかない");
        assertFalse(yaml.contains("tamed.skill"),
                "tamed: は単一スキルを参照しない設計。skill キーが出荷configに残っていると設計判断と矛盾する");
    }

    private static TamedLevelPolicy loadTamed(String yaml) {
        return MobTypesConfig.parseTamed(
                YamlConfiguration.loadConfiguration(new StringReader(yaml))
                        .getConfigurationSection("tamed"),
                java.util.logging.Logger.getLogger("test"));
    }
}
