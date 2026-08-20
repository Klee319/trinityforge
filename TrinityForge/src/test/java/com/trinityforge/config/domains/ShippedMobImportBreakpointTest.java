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
 *   <li><b>W-176(2026-08-20)</b>: max-health / flat-defense の「Lv45以降だけ効く加算専用の第2区間」は
 *       撤去済み。復活すると踏破ボスの撃破秒数が中レベル帯だけ跳ね上がる(実測で難易度1のボスが
 *       Lv45 12秒 / Lv55 189秒)。ここはその復活を落とすための固定。</li>
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

    /** 出荷 mob-import.yml の max-health カーブ(W-176 で加算区間を撤去した後の値)。 */
    private static final double DUNGEON_HEALTH_BASE = 150.0;
    private static final double DUNGEON_HEALTH_GROWTH = 1.072;

    @Test
    @DisplayName("W-176: ダンジョンの max-health は加算区間を持たない純粋な指数である")
    void maxHealthIsAPureExponentialWithoutTheHighLevelPhase() throws Exception {
        ConversionPolicy policy = loadPolicy();
        ConversionPolicy.Ramp maxHealth = policy.maxHealth();

        // 【契約の変更】2026-08-03(45+難易度修正)は「Lv45以降だけ +1800/Lv を加算する」第2区間を
        // 置いていた。2026-08-20(W-176)で撤去した。理由は2つ。
        //  (1) このランプを食うのは踏破ボス/中ボスだけ(EliteMobs フォークの HP 委譲が
        //      CustomBossEntity ゲート)。雑魚のHPはここに乗らないので、加算は「ボスだけを
        //      中レベル帯で厚くする」効果しか持たない。
        //  (2) ボスの実HPは このランプ × mob-overrides の倍率 × EM の healthMultiplier の3段の積で、
        //      後段は最大 2500 倍。加算がその内側にあると +1800/Lv が最大 450万HP/Lv に化け、
        //      指数で組んだ難易度ラダーの形を中レベル帯だけ壊す(スパーキーが Lv55 で 623 秒)。
        // ここが再び 0 でなくなると撃破秒数のぶれが 2.9倍 → 15.2倍 へ戻る。
        for (int level : new int[] {0, 44, 45, 46, 60, 80, 100}) {
            assertEquals(DUNGEON_HEALTH_BASE * Math.pow(DUNGEON_HEALTH_GROWTH, level),
                    maxHealth.at(level), maxHealth.at(level) * 1.0e-9,
                    "Lv" + level + " のHPが純粋な指数からずれている"
                            + "(high-level-per-level が 0 でなくなった疑い)");
        }
    }

    /** 出荷 mob-import.yml の attack-power カーブ(2026-08-12 の火力/防御再較正で置いた値)。 */
    private static final double DUNGEON_ATTACK_BASE = 10.2;
    private static final double DUNGEON_ATTACK_GROWTH = 1.0148;

    @Test
    @DisplayName("2026-08-12: ダンジョンの attack-power は加算区間を持たない純粋な指数である")
    void attackPowerIsAPureExponentialWithoutTheHighLevelPhase() throws Exception {
        ConversionPolicy policy = loadPolicy();
        ConversionPolicy.Ramp attackPower = policy.attack().attackPower();

        // 【契約の変更】2026-08-03(#63)は「Lv45以降だけ +0.25/Lv を加算する」第2区間を置いていた。
        // 2026-08-12 の再較正でフィールド側(combat/mob-types.yml)の同じ加算を撤去したのに合わせ、
        // ダンジョン側もここで撤去した。指数を寝かせた(1.03 -> 1.0148)ぶん、加算で高帯を持ち上げると
        // 二重計上になり、Lv100 で「ダンジョンだけ攻撃力が約4.3倍」という即死状態へ戻る。
        for (int level : new int[] {0, 44, 45, 46, 60, 80, 100}) {
            assertEquals(DUNGEON_ATTACK_BASE * Math.pow(DUNGEON_ATTACK_GROWTH, level),
                    attackPower.at(level), DELTA,
                    "Lv" + level + " の攻撃力が純粋な指数からずれている"
                            + "(high-level-per-level が 0 でなくなった疑い)");
        }
    }

    @Test
    @DisplayName("2026-08-12: ダンジョンとフィールドの attack-power の伸びが同じ勾配である")
    void dungeonAndFieldAttackCurvesShareTheSameGrowth() throws Exception {
        Path fieldFile = Path.of("src/main/resources/combat/mob-types.yml");
        assertTrue(Files.isRegularFile(fieldFile), "出荷 yml が見つからない: " + fieldFile.toAbsolutePath());
        YamlConfiguration field = new YamlConfiguration();
        field.loadFromString(Files.readString(fieldFile));

        // フィールドの基準モブ(ゾンビ)の伸び。ここが動いたらダンジョン側も一緒に動かすこと ──
        // 勾配が割れると、割れた分がそのままレベル差の指数として効いて片側だけ即死になる。
        double fieldGrowth = field.getDouble(
                "mob-types.ZOMBIE.level-coefficients.attack.attack-power-growth", -1.0);
        assertEquals(DUNGEON_ATTACK_GROWTH, fieldGrowth, DELTA,
                "フィールド(mob-types.yml の ZOMBIE)とダンジョン(mob-import.yml)で"
                        + " attack-power の伸びが割れている。Lv100 では (fieldGrowth/dungeonGrowth)^100 倍の"
                        + "差になるので、片方だけ触ると必ず片側が事故る");
    }

    @Test
    @DisplayName("W-176: ダンジョンの物理/魔法 flat-defense はどのレベルでも0(帯の中で実効DPSが下がらない)")
    void flatDefenseHasNoHighLevelPhase() throws Exception {
        ConversionPolicy policy = loadPolicy();
        ConversionPolicy.Ramp physicalFlat = policy.physical().flatDefense();
        ConversionPolicy.Ramp magicalFlat = policy.magical().flatDefense();

        // 【なぜ0で固定するのか】flat-defense はクリット前に減算される純粋な固定値
        // (ComponentDamageCalculator step2)。プレイヤーの装備更新は Lv45/60/80/100 の飛び石で、
        // その間は1発の威力が変わらない。そこへ守備力だけ +150/Lv で伸びると
        // 「同じ装備のままレベルを上げるほど弱くなる」逆転が起き、帯の終わり(Lv55/75/95)で
        // 撃破秒数が跳ね上がる。2026-08-03 に置いた第2区間を 2026-08-20(W-176)で撤去した。
        for (int level : new int[] {0, 44, 45, 46, 60, 80, 100}) {
            assertEquals(0.0, physicalFlat.at(level), DELTA,
                    "Lv" + level + " の physical.flat-defense が0でない"
                            + "(Lv45以降の加算専用の第2区間が復活した疑い)");
            assertEquals(0.0, magicalFlat.at(level), DELTA,
                    "Lv" + level + " の magical.flat-defense が0でない"
                            + "(Lv45以降の加算専用の第2区間が復活した疑い)");
        }
    }
}
