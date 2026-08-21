package com.trinityforge.config.domains;

import com.trinityforge.combat.DamageType;
import com.trinityforge.combat.MobAbilityExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>2026-08-21 W-181「格下レベルのボスにワンパンされる」で入れた3つの是正を、出荷 yml の実データで
 * 固定する</b> drift 検出テスト。
 *
 * <h2>なぜ要るか — 3つとも「無言で元に戻る」種類の設定</h2>
 * <ul>
 *   <li><b>技の1発が最大HPを超えても例外は出ない。</b> 技のダメージは
 *       「そのモブの攻撃力 × {@code damage-percent}」なので、テンプレート1行を戻すだけで
 *       <b>難易度1のダンジョンのボスが即死技を撃つ</b>状態へ戻る。実際 2026-08-21 まで
 *       {@code piercing_beam}/{@code meteor_mark} が 2.0 倍・100% 魔法で、
 *       Lv100 帯最良装備でも最大HPの 73〜144% を1発で奪っていた。</li>
 *   <li><b>技が属性へ追従しているかは挙動を見ないと分からない。</b>
 *       {@code ability-element-bias} を 1.0 にすると 2026-08-21 以前の
 *       「技は damage-type へ100%」に戻り、<b>magic-ratio の上限 0.45 という安全弁を技だけが
 *       素通りする</b>状態が復活する。</li>
 *   <li><b>属性を選ぶ意味は「差」でしか測れない。</b> 個々の耐性値を眺めても
 *       「正しい属性を選ぶと何倍通るか」は見えない。2026-08-21 以前は中央値 1.29 倍しかなく、
 *       396 体中 43 体は物理と魔法が<b>完全に同値</b>だった(＝属性を選ぶ意味がゼロ)。</li>
 * </ul>
 */
class ShippedDungeonBurstAndElementTest {

    /**
     * 技1発が奪ってよい最大の倍率(モブの攻撃力に対する {@code damage-percent} の上限)。
     *
     * <p>1.4 の根拠: Lv100・品質5 の帯最良装備(最大HP 94・物理守備 8.2・魔法守備 3.9)に対して、
     * 最難関ダンジョンの踏破ボス(攻撃力 87)が撃っても<b>最大HPの 65% 前後</b>に収まる水準。
     * ここを 2.0 に戻すと難易度1のダンジョンでも1発が最大HPの7割を超え、
     * 「予兆を見て避ける」ではなく「引いたら死ぬ」ゲームになる。
     */
    private static final double MAX_ABILITY_DAMAGE_PERCENT = 1.4;

    /**
     * per-mob のボス係数(攻撃)として出荷 yml に存在してよい値の全体。
     *
     * <p>2026-08-20(W-179)の 1.3(BOSS)/1.2(MINIBOSS・EVENTBOSS)と、束縛者の段階表
     * 1.3/1.4/1.5/1.8 を、2026-08-21(W-181)で一律に下げたもの:
     * <b>1.3→1.15 / 1.2→1.10 / 1.4→1.20 / 1.5→1.25 / 1.8→1.40</b>。
     * 「攻撃力を下げたぶんをHPへ振り替える」のが W-181 の方針なので、
     * <b>ここを戻すなら HP 側の梯子({@link #everyDungeonScopeCarriesTheHpLadder})も一緒に戻すこと</b>。
     */
    private static final Set<Double> ALLOWED_BOSS_ATTACK_MULTIPLIERS = Set.of(1.1, 1.15, 1.2, 1.25, 1.4);

    /**
     * 属性の「尖り」の下限。物理と魔法で通りやすさが何倍違えば、属性を選ぶ意味があると見なすか。
     *
     * <p>1.35 は「正しい属性を選ぶと与ダメージが 35% 増える」という体感できる下限。
     * 意図的に弱点を持たない<b>物魔両方</b>のモブ(左右完全対称)はこの検査から外す ——
     * そこは「どちらでも同じだけ通る代わりに全体的に硬い」という別の設計だから。
     */
    private static final double MIN_ELEMENT_RATIO = 1.35;

    /**
     * 難易度の梯子を持たないスコープ。
     *
     * <ul>
     *   <li>{@code default} — 全ダンジョン共通のフォールバック。特定の難易度に属さない。</li>
     *   <li>{@code em_adventurers_guild} — 戦闘のない拠点(NPC と計測用の的だけ)。
     *       ここのHPを動かしてもプレイ体験は1ミリも変わらない。</li>
     * </ul>
     */
    private static final Set<String> LADDERLESS_SCOPES = Set.of("default", "em_adventurers_guild");

    // ---------------------------------------------------------------- 技

    @Test
    @DisplayName("技の damage-percent が1発上限を超えない(ワンパンの再発防止)")
    void noAbilityExceedsTheBurstCap() throws IOException {
        ConfigurationSection abilities =
                loadShippedYaml(MobAbilitiesConfig.PATH).getConfigurationSection("abilities");
        assertNotNull(abilities, "combat/mob-abilities.yml の abilities: が読めていない");

        List<String> over = new ArrayList<>();
        for (String id : abilities.getKeys(false)) {
            double dp = abilities.getDouble(id + ".damage-percent", 0.0);
            if (dp > MAX_ABILITY_DAMAGE_PERCENT) {
                over.add(id + "=" + dp);
            }
        }
        assertEquals(List.of(), over,
                "技の damage-percent が上限 " + MAX_ABILITY_DAMAGE_PERCENT + " を超えている: " + over + "。"
                        + "技のダメージは【そのモブの攻撃力 × この倍率】なので、ここを上げると"
                        + "難易度に関係なく全ダンジョンで同時に即死級になる。"
                        + "2026-08-21 以前は 2.0 で、難易度1のボスでも1発が最大HPの7割を超えていた。"
                        + "特定の技だけ重くしたいなら、倍率ではなく cooldown-seconds / chance を触ること。");
    }

    @Test
    @DisplayName("ability-element-bias が 1.0 未満(技がモブの magic-ratio に追従している)")
    void abilitiesStayTiedToTheMobsElement() throws IOException {
        YamlConfiguration yaml = loadShippedYaml(MobAbilitiesConfig.PATH);
        // 「キーが無い＝Java の既定値で動く」ので挙動は正しいが、出荷 yml に無いと
        // 運用者がこの摘みの存在に気づけない(＝説明のコメントごと消えている)。明示を要求する。
        assertTrue(yaml.isSet("ability-element-bias"),
                "出荷 combat/mob-abilities.yml に ability-element-bias が無い。"
                        + "Java 側の既定 " + MobAbilitiesConfig.DEFAULT_ELEMENT_BIAS + " で動くので挙動は変わらないが、"
                        + "この摘みは【技が magic-ratio の安全弁を素通りしていた】という設計事故の再発防止そのもの。"
                        + "キーと一緒に説明のコメントが消えると、次に触る人が同じ穴を開け直す。");
        double bias = yaml.getDouble("ability-element-bias", MobAbilitiesConfig.DEFAULT_ELEMENT_BIAS);
        assertTrue(bias >= 0.0 && bias < 1.0,
                "ability-element-bias が " + bias + "。1.0 は 2026-08-21 以前の"
                        + "「技は damage-type へ100%」に戻す値で、"
                        + "【magic-ratio の上限 0.45 という安全弁を技だけが素通りする】状態が復活する。"
                        + "その結果『敵は物理型』のダンジョンで、正しく物理防御を積んだプレイヤーほど"
                        + "魔法技1発で理不尽に死ぬ。0 に近いほど技はモブの属性そのままになる。");
    }

    @Test
    @DisplayName("実効魔法割合の式: 逆属性のダンジョンでも片側へ振り切らない")
    void effectiveMagicRatioNeverSaturates() {
        double bias = MobAbilitiesConfig.DEFAULT_ELEMENT_BIAS;
        // 敵は物理型(magic-ratio 0.10)のダンジョンの魔法技 —— ここが 1.0 だと物理防御が無意味になる
        double magicalInPhysicalDungeon =
                MobAbilityExecutor.effectiveMagicRatio(0.10, DamageType.MAGICAL, bias);
        assertTrue(magicalInPhysicalDungeon < 0.5,
                "物理型ダンジョンの魔法技が魔法 " + magicalInPhysicalDungeon + " 割。"
                        + "半分を超えると『物理防御を積んだプレイヤーが魔法技だけで溶ける』側へ戻る。");
        // 敵は魔法型(0.45)のダンジョンの物理技 —— 逆向きにも振り切らないこと
        double physicalInMagicDungeon =
                MobAbilityExecutor.effectiveMagicRatio(0.45, DamageType.PHYSICAL, bias);
        assertTrue(physicalInMagicDungeon > 0.0 && physicalInMagicDungeon < 0.45,
                "魔法型ダンジョンの物理技の魔法割合が " + physicalInMagicDungeon + "。"
                        + "0 だと『魔法防御を積んだプレイヤーが物理技だけで溶ける』という対称の穴が開く。");
        // 刻印の無いバニラモブ(magic-ratio 0)でも、魔法技は魔法として入ること
        assertTrue(MobAbilityExecutor.effectiveMagicRatio(0.0, DamageType.MAGICAL, bias) > 0.0,
                "magic-ratio を持たないバニラモブの魔法技が完全物理に化けている"
                        + "(ウィザーのビームが物理になる)。");
    }

    // ---------------------------------------------------------------- 攻撃/HP

    @Test
    @DisplayName("per-mob のボス係数(攻撃)が W-181 で下げた値の範囲に収まっている")
    void perMobBossAttackMultipliersStayOnTheLoweredTier() throws IOException {
        Set<Double> found = new TreeSet<>();
        forEachMob((world, mobId, stats) -> {
            if (stats.isSet("attack-power-multiplier")) {
                found.add(stats.getDouble("attack-power-multiplier"));
            }
        });
        List<Double> stray = found.stream().filter((v) -> !ALLOWED_BOSS_ATTACK_MULTIPLIERS.contains(v)).toList();
        assertEquals(List.of(), stray,
                "per-mob のボス係数(攻撃)に想定外の値がある: " + stray
                        + " (許可されているのは " + new TreeSet<>(ALLOWED_BOSS_ATTACK_MULTIPLIERS) + ")。"
                        + "2026-08-21(W-181)で 1.3→1.15 / 1.2→1.10 / 1.4→1.20 / 1.5→1.25 / 1.8→1.40 へ"
                        + "下げ、そのぶんを HP 側(スコープ直下の max-health-multiplier)へ振り替えた。"
                        + "片方だけ戻すと『攻撃も硬さも上がる』ことになるので、必ず2つセットで動かすこと。");
    }

    @Test
    @DisplayName("全ダンジョンのスコープに難易度別のHP梯子がある(難易度が高いほど控えめ)")
    void everyDungeonScopeCarriesTheHpLadder() throws IOException {
        ConfigurationSection overrides = overridesSection();
        Map<String, Double> ladder = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String world : overrides.getKeys(false)) {
            if (LADDERLESS_SCOPES.contains(world)) {
                continue;
            }
            ConfigurationSection stats = overrides.getConfigurationSection(world + ".stats");
            if (stats == null || !stats.isSet("max-health-multiplier")) {
                missing.add(world);
                continue;
            }
            ladder.put(world, stats.getDouble("max-health-multiplier"));
        }
        assertEquals(List.of(), missing,
                "スコープ直下の max-health-multiplier が無いダンジョンがある: " + missing + "。"
                        + "この層は【雑魚も含めそのダンジョンの全モブ】へ効く底上げで、"
                        + "『ボスの攻撃力を下げたぶんをHPへ振り替える』という W-181 の方針の片翼。"
                        + "スコープに書かないとそのダンジョンだけ攻撃力だけが下がって薄くなる。");

        List<String> outOfRange = ladder.entrySet().stream()
                .filter((e) -> e.getValue() < 1.0 || e.getValue() > 1.45)
                .map((e) -> e.getKey() + "=" + e.getValue())
                .toList();
        assertEquals(List.of(), outOfRange,
                "HP梯子が想定の範囲 [1.0, 1.45] を外れている: " + outOfRange + "。"
                        + "1.0 未満は『HPを下げる』方向で、攻撃力も下げた今それをやると"
                        + "ボスが一瞬で溶ける。上限側は難易度1の 1.376 が最大で、"
                        + "そこを超えると設計目標(難易度1の踏破ボスで撃破 20 秒前後)から外れる。");
    }

    // ---------------------------------------------------------------- 属性

    @Test
    @DisplayName("左右非対称のモブは、正しい属性を選ぶと体感できるだけ通る")
    void typedMobsHaveARealElementalWeakness() throws IOException {
        List<String> flat = new ArrayList<>();
        forEachMob((world, mobId, stats) -> {
            double pRate = stats.getDouble("physical.defense-rate", 0.0);
            double pRes = stats.getDouble("physical.resistance", 0.0);
            double mRate = stats.getDouble("magical.defense-rate", 0.0);
            double mRes = stats.getDouble("magical.resistance", 0.0);
            if (!stats.isSet("physical.resistance") && !stats.isSet("magical.resistance")) {
                return;     // 耐性を書いていないモブ(下位層の既定値のまま)は対象外
            }
            double physPass = (1.0 - pRate) * (1.0 - pRes);
            double magPass = (1.0 - mRate) * (1.0 - mRes);
            if (Math.abs(physPass - magPass) < 1.0e-9) {
                return;     // 意図的に弱点を持たない「物魔両方」のモブ
            }
            double ratio = Math.max(physPass, magPass) / Math.min(physPass, magPass);
            if (ratio < MIN_ELEMENT_RATIO) {
                flat.add(world + "/" + mobId + "=" + String.format("%.2f", ratio) + "倍");
            }
        });
        assertEquals(List.of(), flat,
                "属性を選んでも " + MIN_ELEMENT_RATIO + " 倍に届かないモブがある: " + flat + "。"
                        + "『物理推奨/魔法推奨』というダンジョンのコンセプトは、"
                        + "【正しい属性を選ぶと目に見えて速く倒せる】ことでしか伝わらない。"
                        + "2026-08-21 以前は中央値 1.29 倍しかなく、属性を揃える動機が実質ゼロだった。"
                        + "弱点を持たせたくないモブは physical と magical を【完全に同値】にすること"
                        + "(この検査は同値のモブを意図的な設計として除外する)。");
    }

    // ---------------------------------------------------------------- helpers

    private interface MobVisitor {
        void visit(String world, String mobId, ConfigurationSection stats);
    }

    private static void forEachMob(MobVisitor visitor) throws IOException {
        ConfigurationSection overrides = overridesSection();
        for (String world : overrides.getKeys(false)) {
            ConfigurationSection mobs = overrides.getConfigurationSection(world + ".mobs");
            if (mobs == null) {
                continue;
            }
            for (String mobId : mobs.getKeys(false)) {
                ConfigurationSection stats = mobs.getConfigurationSection(mobId + ".stats");
                if (stats != null) {
                    visitor.visit(world, mobId, stats);
                }
            }
        }
    }

    private static ConfigurationSection overridesSection() throws IOException {
        ConfigurationSection overrides =
                loadShippedYaml(MobOverridesConfig.PATH).getConfigurationSection("overrides");
        assertNotNull(overrides, "combat/mob-overrides.yml の overrides: が読めていない");
        return overrides;
    }

    private static YamlConfiguration loadShippedYaml(String path) throws IOException {
        try (InputStream in = ShippedDungeonBurstAndElementTest.class.getClassLoader()
                .getResourceAsStream(path.replace('\\', '/'))) {
            assertNotNull(in, "出荷リソースが見つからない: " + path);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }
}
