package com.trinityforge.config.domains;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.ComponentDamageCalculator;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.mobs.MobProfile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>踏破ボス(束縛者 第4段階)の通常攻撃に対する、重装1式(不滅) / 軽装1式(天陰)の
 * 耐久期待値の差を固定する</b>(2026-08-25、W-224 の追検証)。
 *
 * <h2>なぜ要るか — 台帳の「軽装 HP 30.3」は 2026-08-21(W-183a)より前の実測値</h2>
 * {@code reports/ACTIVE_RECORD.md} の W-224 は「軽装最終装備の耐久期待値をインフィニティ装備へ寄せる」
 * 決定を、"現状 Lv100 で最大HP 30.3 ＝踏破ボスの通常攻撃 0.9〜1.1 発" という前提つきで記録している。
 * この数字の出典は {@code reports/ACTIVE_RECORD_ARCHIVE.md}(W-183a、"着手前の実測")で、
 * <b>その同じ日に W-183a が防具を住み分け直し、以後は解決している</b>
 * ("重装 0.80 / 軽装 1.50" の HP 倍率で被ダメージ期待値を揃えた、と本文に明記)。
 * つまり W-224 はこの解決を踏まえずに古い数値をそのまま引き継いでいる。
 *
 * <p>このテストは出荷 {@code combat/damage.yml} / {@code combat/mob-import.yml} /
 * {@code combat/mob-overrides.yml} / {@code stats/item-stats.yml} を実際に読み、
 * 本番と同じ計算経路({@code MobOverridesConfig#resolve} → {@code ComponentDamageCalculator#compute}、
 * 分割は {@code SymmetricCombatService#physicalFinalDamageFromMobResult} と同じ
 * 「defaultDamage だけを magic-ratio で割り、fixedDamage は両成分にそのまま乗る」規則)で
 * <b>踏破ボスの通常攻撃1発に対する重装/軽装それぞれの耐えられる発数</b>を計算し、
 * その比が「壊れていない」帯に収まっていることを固定する。
 *
 * <p>前提: プレイヤー Lv100 / 品質は未考慮(この2部位セットは per-quality に durability しか
 * 無いので fixed 値だけで確定する、2026-08-25 実測で表と一致を確認済み) / スレッド未装着 /
 * バニラ素の最大HP 20。 dodge-chance は「攻撃を受ける確率」として被ダメージ期待値へ織り込む
 * (回避は攻撃全体を無効化する一様ロールなので、期待値としては 1/(1-dodge) 倍だけ発数が伸びる)。
 */
class BinderOfWorldsHeavyVersusLightSurvivabilityTest {

    private static final String BINDER_WORLD = "em_id_binder_of_worlds";
    private static final String FINAL_BOSS = "em_id_binder_of_worlds_phase_4";

    /**
     * 挑戦レベル。配備先 {@code custombosses/the_binder_of_worlds/*.yml} の {@code level: 100} が
     * 一次情報({@link ShippedBossStrengthDriftTest} と同じ根拠)。
     */
    private static final int DUNGEON_LEVEL = 100;

    private static final double VANILLA_BASE_HEALTH = 20.0;

    /** 重装1式(世界を繋ぐ・不滅、Lv100)。 */
    private static final List<String> HEAVY_PIECES = List.of(
            "NETHERITE_HELMET#148", "NETHERITE_CHESTPLATE#151",
            "NETHERITE_LEGGINGS#154", "NETHERITE_BOOTS#157");

    /** 軽装1式(エンダードラゴン・天陰、Lv100)。 */
    private static final List<String> LIGHT_PIECES = List.of(
            "NETHERITE_HELMET#149", "NETHERITE_CHESTPLATE#152",
            "NETHERITE_LEGGINGS#155", "NETHERITE_BOOTS#158");

    /**
     * 耐えられる発数(重装)の許容帯。2026-08-25 実測 ≈3.27 発。
     * 帯を持たせているのは、item-stats.yml の絶対値が動くたびに完全一致で落ちると
     * 「意図した数値の見直し」まで巻き込むため(このテストの主眼は比較の向きと大まかな水準)。
     */
    private static final double HEAVY_HITS_MIN = 2.6;
    private static final double HEAVY_HITS_MAX = 4.0;

    /** 耐えられる発数(軽装、dodge込みの期待値)の許容帯。2026-08-25 実測 ≈3.88 発。 */
    private static final double LIGHT_HITS_MIN = 3.0;
    private static final double LIGHT_HITS_MAX = 4.8;

    /**
     * 「軽装 ÷ 重装」の耐発数比の許容帯。
     *
     * <p><b>下限 1.00</b>: 軽装が重装より弱くなったら、W-183a が解決したはずの「軽装には取り柄が無い」
     * 状態への逆戻りなので、ここで検出する(W-224 の前提だった 30.3 ＝0.9〜1.1発はこの下限を大きく割る)。
     *
     * <p><b>上限 1.40</b>: 軽装はスレッド枠12(重装は0)という別枠の強みを持つので、被ダメージ期待値まで
     * 重装より大きく勝たせる必要は無い、というのが W-224 のユーザー決定("スレッド枠が多いぶんは
     * 少し弱めにする")の趣旨。2026-08-25 実測の比 ≈1.19 はこの帯の中に収まっている
     * ＝ 現状で既にスレッド枠を差し引いても軽装が不利になっていない。
     */
    private static final double RATIO_MIN = 1.00;
    private static final double RATIO_MAX = 1.40;

    // === 出荷リソースの読み込み(ShippedBossStrengthDriftTest と同じ手法) ===

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("BinderOfWorldsHeavyVersusLightSurvivabilityTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not be called when the file already exists on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static InputStream resource(String path) {
        return BinderOfWorldsHeavyVersusLightSurvivabilityTest.class.getClassLoader()
                .getResourceAsStream(path.replace('\\', '/'));
    }

    private static YamlConfiguration loadShippedYaml(String path) throws IOException {
        try (InputStream in = resource(path)) {
            assertNotNull(in, "出荷リソースが見つからない: " + path);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static MobOverridesConfig loadShippedOverrides(File tempDir) throws IOException {
        File file = new File(tempDir, MobOverridesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        try (InputStream in = resource(MobOverridesConfig.PATH)) {
            assertNotNull(in, "出荷リソースが見つからない: " + MobOverridesConfig.PATH);
            Files.write(file.toPath(), in.readAllBytes());
        }
        MobOverridesConfig config = new MobOverridesConfig();
        assertTrue(config.load(fakePlugin(tempDir)),
                "出荷 mob-overrides.yml のロードが false を返した(= 1件以上が skip された)");
        return config;
    }

    // === 束縛者(踏破ボス)の攻撃力を、共通ランプ + scope/mob 倍率で解決する ===

    private static double rampAttackPowerAt(int level) throws IOException {
        ConfigurationSection attack = loadShippedYaml("combat/mob-import.yml")
                .getConfigurationSection("attack.attack-power");
        assertNotNull(attack, "出荷 mob-import.yml に attack.attack-power が無い");
        double base = attack.getDouble("base");
        double growth = attack.getDouble("growth", 1.0);
        double interval = attack.getDouble("growth-interval", 1.0);
        // high-level-per-level は共通ランプの attack-power では 0(第2区間なし、combat.md 既知事実)なので
        // ここでは加算しない。0 でなくなったら「読んでいない値がある」ことを検出するため明示的に落とす。
        double highPerLevel = attack.getDouble("high-level-per-level", 0.0);
        assertTrue(highPerLevel == 0.0,
                "mob-import.yml の attack.attack-power.high-level-per-level が 0 でなくなった("
                        + highPerLevel + ")。このテストは第2区間を計算に入れていないので、値を追加すること");
        return base * Math.pow(growth, level / interval);
    }

    private static AttackStats resolvedFinalBossAttack(File tempDir) throws IOException {
        MobOverridesConfig config = loadShippedOverrides(tempDir);
        double rampAttack = rampAttackPowerAt(DUNGEON_LEVEL);
        MobProfile base = new MobProfile(FINAL_BOSS, DUNGEON_LEVEL, null,
                DefenseStats.NONE, DefenseStats.NONE, AttackStats.plain(rampAttack), 0.0, false);
        MobProfile resolved = config.resolve(BINDER_WORLD, FINAL_BOSS, base);
        assertTrue(resolved.attack().defaultDamage() > 0.0,
                FINAL_BOSS + " の解決後 attack-power が0以下。段階倍率が消えていないか確認すること");
        return resolved.attack();
    }

    /**
     * {@code physical.min-component-damage} / {@code magical.min-component-damage} を
     * 出荷 combat/damage.yml から読む(直書きするとここだけ乖離に気づけなくなる)。
     */
    private static double[] minComponentDamages() throws IOException {
        ConfigurationSection root = loadShippedYaml("combat/damage.yml");
        double physicalFloor = root.getDouble("physical.min-component-damage", 1.0);
        double magicalFloor = root.getDouble("magical.min-component-damage", 1.0);
        return new double[] {physicalFloor, magicalFloor};
    }

    // === プレイヤー側防具4部位の fixed 値合算(quality に依存しない部分。random ロールは対象外) ===

    private static Map<String, Double> fixedSumOf(List<String> pieceIds) throws IOException {
        ConfigurationSection items = loadShippedYaml(ItemStatsConfig.PATH).getConfigurationSection("items");
        assertNotNull(items, "出荷 item-stats.yml に items セクションが無い");
        Map<String, Double> sum = new java.util.LinkedHashMap<>();
        for (String pieceId : pieceIds) {
            ConfigurationSection piece = items.getConfigurationSection(pieceId);
            assertNotNull(piece, "出荷 item-stats.yml に " + pieceId + " が無い");
            ConfigurationSection fixed = piece.getConfigurationSection("fixed");
            assertNotNull(fixed, pieceId + " に fixed セクションが無い");
            for (String key : fixed.getKeys(false)) {
                if (!fixed.isDouble(key) && !fixed.isInt(key) && !fixed.isLong(key)) {
                    continue;
                }
                sum.merge(key, fixed.getDouble(key), Double::sum);
            }
        }
        return sum;
    }

    private static double get(Map<String, Double> stats, String key) {
        return stats.getOrDefault(key, 0.0);
    }

    /**
     * 踏破ボスの通常攻撃1発に対する期待被ダメージ({@code SymmetricCombatService#physicalFinalDamageFromMobResult}
     * のhybrid分岐と同じ規則: defaultDamageだけをmagic-ratioで分割し、fixedDamageは両成分にそのまま乗る)。
     * dodgeは織り込まない(呼び出し側で発数へ変換するときに 1/(1-dodge) として掛ける)。
     */
    private static double expectedDamagePerLandedHit(
            AttackStats bossAttack, Map<String, Double> defenderStats, double[] minComponentDamages) {
        double ratio = Math.max(0.0, Math.min(1.0, bossAttack.magicRatio()));
        AttackStats physicalAttack = bossAttack.withDefaultDamage(bossAttack.defaultDamage() * (1 - ratio));
        AttackStats magicalAttack = bossAttack.withDefaultDamage(bossAttack.defaultDamage() * ratio);

        DefenseStats physicalDefense = new DefenseStats(
                get(defenderStats, "defense-rate"),
                get(defenderStats, "phys-resistance"),
                get(defenderStats, "damage-reduction"),
                get(defenderStats, "phys-flat-defense"),
                get(defenderStats, "armor-strength"));
        DefenseStats magicalDefense = new DefenseStats(
                get(defenderStats, "defense-rate"),
                get(defenderStats, "magic-resistance"),
                get(defenderStats, "damage-reduction"),
                get(defenderStats, "magic-flat-defense"),
                get(defenderStats, "armor-strength"));

        // ボスの crit-chance は 0(束縛者スコープは crit-chance/crit-damage を一切上書きしない)なので
        // crit=false固定・unitRandomは damage-modifier が endpoint=1.0 の実測(常に ×1.0)なので任意の値でよい。
        double physicalDamage = ComponentDamageCalculator.compute(
                physicalAttack, physicalDefense, false, minComponentDamages[0], 0.5);
        double magicalDamage = ComponentDamageCalculator.compute(
                magicalAttack, magicalDefense, false, minComponentDamages[1], 0.5);
        return physicalDamage + magicalDamage;
    }

    private static double hitsWithstood(
            AttackStats bossAttack, List<String> pieceIds, double[] minComponentDamages) throws IOException {
        Map<String, Double> stats = fixedSumOf(pieceIds);
        double health = VANILLA_BASE_HEALTH + Math.max(0.0, get(stats, "max-health"));
        double dodge = Math.max(0.0, Math.min(0.9, get(stats, "dodge-chance")));
        double perLandedHit = expectedDamagePerLandedHit(bossAttack, stats, minComponentDamages);
        assertTrue(perLandedHit > 0.0, "1発の期待被ダメージが0以下(" + perLandedHit + ")。防具の守備値が過大");
        double expectedDamagePerAttack = perLandedHit * (1 - dodge);
        return health / expectedDamagePerAttack;
    }

    @Test
    @DisplayName("踏破ボス(束縛者)の通常攻撃に対する耐発数は、軽装(天陰)が重装(不滅)を下回らない"
            + "(W-224前提の「軽装30.3=0.9〜1.1発」への回帰防止。ただしスレッド枠12の分は勝ちすぎない)")
    void lightArmorSurvivesAtLeastAsWellAsHeavyArmor(@TempDir File tempDir) throws IOException {
        AttackStats bossAttack = resolvedFinalBossAttack(tempDir);
        double[] minComponentDamages = minComponentDamages();

        double heavyHits = hitsWithstood(bossAttack, HEAVY_PIECES, minComponentDamages);
        double lightHits = hitsWithstood(bossAttack, LIGHT_PIECES, minComponentDamages);

        assertTrue(heavyHits >= HEAVY_HITS_MIN && heavyHits <= HEAVY_HITS_MAX,
                String.format("重装(不滅)の耐発数が %.3f。想定帯 [%.1f, %.1f] の外。"
                                + "mob-import.yml のランプ、束縛者第4段階の倍率、または item-stats.yml の"
                                + "重装4部位の fixed 値が動いた", heavyHits, HEAVY_HITS_MIN, HEAVY_HITS_MAX));
        assertTrue(lightHits >= LIGHT_HITS_MIN && lightHits <= LIGHT_HITS_MAX,
                String.format("軽装(天陰)の耐発数が %.3f。想定帯 [%.1f, %.1f] の外。"
                                + "同上の原因を疑うこと", lightHits, LIGHT_HITS_MIN, LIGHT_HITS_MAX));

        double ratio = lightHits / heavyHits;
        assertTrue(ratio >= RATIO_MIN && ratio <= RATIO_MAX,
                String.format("軽装/重装の耐発数比が %.3f(軽装 %.3f 発 ÷ 重装 %.3f 発)。"
                                + "許容帯 [%.2f, %.2f] の外。下限を割ったら「軽装には取り柄が無い」"
                                + "(W-183a以前・W-224前提の状態)への回帰。上限を超えたら軽装のスレッド枠12"
                                + "(重装は0)ぶんの優位を割り引く前提が崩れている",
                        ratio, lightHits, heavyHits, RATIO_MIN, RATIO_MAX));
    }
}
