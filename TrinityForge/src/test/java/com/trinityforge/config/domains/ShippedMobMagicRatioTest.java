package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「魔法モブ4割・物理モブ6割」を出荷 yml で固定する(2026-08-02)。
 *
 * <h2>なぜ比率をテストで縛るのか</h2>
 * この比率は<b>プレイヤーの装備方針を直接決める</b>。魔法防御に振る価値があるかどうかが
 * 「魔法で殴ってくる敵が何割いるか」で決まるため、モブを1体足す/消すたびに黙って崩れると
 * 「魔法防御に振ったのに出番が無い」「物理防具だけで全部受けられる」という設計の破綻になる。
 * それでいて崩れたことは<b>実プレイを長時間しないと気づけない</b>ので、yml 側で固定する。
 *
 * <h2>数え方を2つ持つ理由</h2>
 * {@code magic-ratio} は真偽値ではなく比率なので「魔法モブの数」は一意に決まらない。
 * <ul>
 *   <li><b>頭数</b>: {@code magic-ratio >= 0.5} を「主に魔法で殴るモブ」として数える。
 *       プレイヤーの体感（この敵は魔法防御で受ける敵か）に近い。</li>
 *   <li><b>重み付け</b>: 全モブの {@code magic-ratio} の平均。実際に飛んでくるダメージのうち
 *       魔法が占めるシェアに近い。</li>
 * </ul>
 * 片方だけを縛ると、もう片方が極端に振れても検出できない（例: 全モブを 0.4 にすると
 * 頭数はゼロなのに重み付けは 40%）。両方に幅を持たせて縛る。
 *
 * <h2>母集団</h2>
 * 敵対モブ 41 種（{@code mob-types.yml} の先頭 41 エントリ）＋ {@code mob-overrides.yml} の
 * {@code overrides.default.mobs} に置いた派生カスタムボス。動物・村人などの非敵対モブは
 * そもそも攻撃してこないので母集団から外す（入れると分母が膨らんで比率が無意味になる）。
 */
class ShippedMobMagicRatioTest {

    private static final String MOB_TYPES = "src/main/resources/combat/mob-types.yml";
    private static final String MOB_OVERRIDES = "src/main/resources/combat/mob-overrides.yml";

    /** 攻撃してくるモブ。ここが母集団で、非敵対モブ(動物/村人/魚)は比率の分母に入れない。 */
    private static final Set<String> HOSTILE = Set.of(
            "BLAZE", "BOGGED", "BREEZE", "CAVE_SPIDER", "CREAKING", "CREEPER",
            "DROWNED", "ELDER_GUARDIAN", "ENDERMAN", "ENDERMITE", "ENDER_DRAGON", "EVOKER",
            "GHAST", "GIANT", "GUARDIAN", "HOGLIN", "HUSK", "ILLUSIONER",
            "MAGMA_CUBE", "PARCHED", "PHANTOM", "PIGLIN", "PIGLIN_BRUTE", "PILLAGER",
            "RAVAGER", "SHULKER", "SILVERFISH", "SKELETON", "SLIME", "SPIDER",
            "STRAY", "VEX", "VINDICATOR", "WARDEN", "WITCH", "WITHER",
            "WITHER_SKELETON", "ZOGLIN", "ZOMBIE", "ZOMBIE_VILLAGER", "ZOMBIFIED_PIGLIN");

    /**
     * 純粋な近接モブ。ここに魔法が付いたら「既存モブの性質を書き換えた」ことになるので落とす。
     * 割り当ての方針は「バニラで元から魔法的な手段(術/ビーム/火球/弾/呪い)で攻撃するモブにだけ付ける」で、
     * ゾンビが素手で殴って魔法ダメージになるのはその方針の破れ。
     */
    private static final Set<String> MUST_STAY_PHYSICAL = Set.of(
            "ZOMBIE", "SKELETON", "CREEPER", "SPIDER", "CAVE_SPIDER", "HUSK", "DROWNED",
            "PIGLIN", "PIGLIN_BRUTE", "ZOMBIFIED_PIGLIN", "HOGLIN", "ZOGLIN", "RAVAGER",
            "VINDICATOR", "PILLAGER", "SILVERFISH", "ENDERMITE", "SLIME", "MAGMA_CUBE",
            "GIANT", "ENDERMAN", "PHANTOM", "ZOMBIE_VILLAGER");

    /** 「主に魔法で殴る」と数える下限。 */
    private static final double PRIMARILY_MAGIC = 0.5;

    /** 頭数比率の許容幅。狙いは4割で、ここを外れたら意図的な設計変更として見直させる。 */
    private static final double HEADCOUNT_MIN = 0.35;
    private static final double HEADCOUNT_MAX = 0.50;

    /** 重み付け(魔法ダメージのシェア)の許容幅。頭数より低く出るのが正常。 */
    private static final double WEIGHTED_MIN = 0.28;
    private static final double WEIGHTED_MAX = 0.45;

    private static YamlConfiguration load(String path) throws Exception {
        Path file = Path.of(path);
        assertTrue(Files.isRegularFile(file), "出荷 yml が見つからない: " + file.toAbsolutePath());
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(Files.readString(file));
        return cfg;
    }

    /** 敵対モブ名 -> magic-ratio(未設定は 0.0)。 */
    private static Map<String, Double> hostileRatios() throws Exception {
        Map<String, Double> ratios = new LinkedHashMap<>();

        ConfigurationSection types = load(MOB_TYPES).getConfigurationSection("mob-types");
        assertNotNull(types, MOB_TYPES + " に mob-types: 節が無い");
        List<String> unknownHostile = new ArrayList<>(HOSTILE);
        for (String id : types.getKeys(false)) {
            if (!HOSTILE.contains(id)) {
                continue;
            }
            unknownHostile.remove(id);
            ConfigurationSection attack = types.getConfigurationSection(id + ".attack");
            ratios.put(id, attack == null ? 0.0 : attack.getDouble("magic-ratio", 0.0));
        }
        assertTrue(unknownHostile.isEmpty(),
                "敵対モブとして数えている ID が mob-types.yml に無い(改名/削除されたか、"
                        + "このテストの母集団が古い)。該当: " + unknownHostile);

        // overrides.default.mobs の派生カスタムボスも同じ母集団に入れる。
        ConfigurationSection defaults = load(MOB_OVERRIDES).getConfigurationSection("overrides.default.mobs");
        if (defaults != null) {
            for (String id : defaults.getKeys(false)) {
                ConfigurationSection attack = defaults.getConfigurationSection(id + ".stats.attack");
                if (attack != null && attack.contains("magic-ratio")) {
                    ratios.put(id, attack.getDouble("magic-ratio", 0.0));
                }
            }
        }
        return ratios;
    }

    @Test
    @DisplayName("敵対モブのおよそ4割が魔法モブになっている(頭数・重み付けの両方で)")
    void magicMobShareIsAboutFortyPercent() throws Exception {
        Map<String, Double> ratios = hostileRatios();
        int total = ratios.size();
        assertTrue(total >= 41, "母集団が " + total + " 体しかない(敵対モブの節ごと消えていないか)");

        List<String> primarilyMagic = ratios.entrySet().stream()
                .filter(e -> e.getValue() >= PRIMARILY_MAGIC)
                .map(Map.Entry::getKey)
                .toList();
        double headcount = (double) primarilyMagic.size() / total;
        double weighted = ratios.values().stream().mapToDouble(Double::doubleValue).sum() / total;

        String detail = String.format(
                "頭数 %d/%d = %.1f%% / 重み付け %.1f%% / 主に魔法: %s",
                primarilyMagic.size(), total, headcount * 100, weighted * 100, primarilyMagic);

        assertTrue(headcount >= HEADCOUNT_MIN && headcount <= HEADCOUNT_MAX,
                "「主に魔法で殴るモブ」の割合が狙いの4割から外れた。魔法防御ステの価値が"
                        + "設計とずれるので、モブを足す/消すときはこの比率も合わせて調整すること。"
                        + detail);
        assertTrue(weighted >= WEIGHTED_MIN && weighted <= WEIGHTED_MAX,
                "飛んでくるダメージに占める魔法のシェアが狙いから外れた。"
                        + "(頭数だけ合わせて全員を 0.5 ちょうどにする、等でここが崩れる)。" + detail);
    }

    @Test
    @DisplayName("純粋な近接モブは完全物理のまま(既存モブの性質を書き換えていない)")
    void meleeMobsStayFullyPhysical() throws Exception {
        Map<String, Double> ratios = hostileRatios();
        List<String> drifted = MUST_STAY_PHYSICAL.stream()
                .filter(id -> ratios.getOrDefault(id, 0.0) > 0.0)
                .sorted()
                .toList();
        assertTrue(drifted.isEmpty(),
                "素手や剣で殴るだけのモブに magic-ratio が付いている。魔法は「バニラで元から"
                        + "魔法的な手段で攻撃するモブ」にだけ付ける方針。新しい魔法モブが要るなら"
                        + "既存モブを塗り替えるのではなく派生モブ(例: 術者のゾンビ)を作ること。該当: "
                        + drifted);
    }

    @Test
    @DisplayName("magic-ratio は [0.0, 1.0] の範囲に収まっている")
    void ratiosAreWithinRange() throws Exception {
        List<String> outOfRange = hostileRatios().entrySet().stream()
                .filter(e -> e.getValue() < 0.0 || e.getValue() > 1.0)
                .map(e -> e.getKey() + "=" + e.getValue())
                .sorted()
                .toList();
        assertTrue(outOfRange.isEmpty(),
                "magic-ratio が範囲外。読み込み時にクランプされるので実害は出ないが、"
                        + "yml の意図と実挙動が食い違う。該当: " + outOfRange);
    }

    @Test
    @DisplayName("魔法モブが1体も無い状態には戻っていない(機構ごと死んだことに気づくため)")
    void mechanismIsNotSilentlyDisabled() throws Exception {
        Map<String, Double> ratios = hostileRatios();
        assertFalse(ratios.values().stream().allMatch(r -> r == 0.0),
                "magic-ratio が全モブ 0.0 になっている。これは「モブが魔法ダメージを与える経路が"
                        + "1本も無い」状態(2026-08-02 以前と同じ)で、魔法防御ステが全て死に値になる");
    }
}
