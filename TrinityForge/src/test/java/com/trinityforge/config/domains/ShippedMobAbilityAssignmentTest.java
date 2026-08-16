package com.trinityforge.config.domains;

import com.trinityforge.combat.MobAbility;
import com.trinityforge.mobs.MobOverrideEntry;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code combat/mob-overrides.yml} の {@code abilities:} と
 * {@code combat/mob-abilities.yml} のテンプレートの<b>突き合わせ</b>を固定する（2026-08-16）。
 *
 * <p><b>なぜ要るか</b>: Java 側は未定義のテンプレートIDを<b>黙って読み飛ばす</b>
 * （{@code MobAbilityTask#candidatesFor}。ロード順に依存させないための意図的な設計）。
 * つまり綴りを1文字間違えたモブは、エラーもログも無く「技を1つも撃たないボス」になる。
 * 400 体のモブへ手で技を貼る運用では必ず起きるので、機械で落とす。
 */
class ShippedMobAbilityAssignmentTest {

    private static final Logger LOG = Logger.getLogger("ShippedMobAbilityAssignmentTest");
    private static final String OVERRIDES = "src/main/resources/" + MobOverridesConfig.PATH;
    private static final String ABILITIES = "src/main/resources/" + MobAbilitiesConfig.PATH;

    private static Map<String, MobAbility> templates() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(ABILITIES));
        MobAbilitiesConfig.ParseResult result =
                MobAbilitiesConfig.parse(yaml.getConfigurationSection("abilities"), LOG);
        assertEquals(0, result.skipped(), "出荷 mob-abilities.yml に壊れたテンプレートがある");
        return result.abilities();
    }

    private static Map<String, Map<String, MobOverrideEntry>> overrides() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.options().pathSeparator(MobOverridesConfig.MOB_ID_SAFE_PATH_SEPARATOR);
        cfg.load(new File(OVERRIDES));
        MobOverridesConfig.ParseResult result = MobOverridesConfig.parse(cfg, LOG);
        assertEquals(0, result.skipped(), "出荷 mob-overrides.yml に壊れたエントリがある");
        return result.scopes();
    }

    @Test
    @DisplayName("モブに貼った技IDは全件テンプレートに実在する（未定義IDは無言で無技になる）")
    void everyReferencedAbilityExists() throws Exception {
        Map<String, MobAbility> defined = templates();
        List<String> dangling = new ArrayList<>();
        int references = 0;
        for (Map.Entry<String, Map<String, MobOverrideEntry>> scope : overrides().entrySet()) {
            for (Map.Entry<String, MobOverrideEntry> mob : scope.getValue().entrySet()) {
                for (String id : mob.getValue().abilities()) {
                    references++;
                    if (!defined.containsKey(id)) {
                        dangling.add(scope.getKey() + "." + mob.getKey() + " -> " + id);
                    }
                }
            }
        }
        assertEquals(List.of(), dangling, "未定義の技IDを参照しているモブがある: " + dangling);
        assertTrue(references >= 60,
                "技の割り当てが想定より少ない(貼り付けが巻き戻っていないか): " + references);
    }

    @Test
    @DisplayName("定義したテンプレートは1つ以上のモブから使われている（死にテンプレートを作らない）")
    void everyTemplateIsUsed() throws Exception {
        Map<String, MobAbility> defined = templates();
        Map<String, Integer> usage = new LinkedHashMap<>();
        for (String id : defined.keySet()) {
            usage.put(id, 0);
        }
        for (Map<String, MobOverrideEntry> scope : overrides().values()) {
            for (MobOverrideEntry mob : scope.values()) {
                for (String id : mob.abilities()) {
                    usage.computeIfPresent(id, (key, count) -> count + 1);
                }
            }
        }
        List<String> unused = usage.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey)
                .toList();
        assertEquals(List.of(), unused,
                "どのモブにも貼られていないテンプレートがある(作っただけで誰も撃たない): " + unused);
    }

    @Test
    @DisplayName("エンチャント試練の技の数は 1→10 で減らない（難易度の梯子を壊さない）")
    void enchantmentTrialAbilityCountIsMonotonic() throws Exception {
        Map<String, Map<String, MobOverrideEntry>> scopes = overrides();
        int previous = 0;
        for (int trial = 1; trial <= 10; trial++) {
            String scopeKey = "em_id_enchantment_challenge_" + trial;
            Map<String, MobOverrideEntry> mobs = scopes.get(scopeKey);
            assertFalse(mobs == null || mobs.isEmpty(), scopeKey + " が出荷 yml に無い");
            int best = 0;
            for (MobOverrideEntry mob : mobs.values()) {
                best = Math.max(best, mob.abilities().size());
            }
            assertTrue(best >= 1, scopeKey + " のボスが技を1つも持っていない");
            assertTrue(best >= previous,
                    scopeKey + " の技数が前の試練より減っている(" + previous + " -> " + best + ")");
            previous = best;
        }
        assertTrue(previous >= 3, "最終試練(10)の技数が " + previous + " 個しかない");
    }

    @Test
    @DisplayName("テンプレートのパーティクルはデータ不要の種類だけ（データ必須は無言で演出が消える）")
    void everyParticleIsDataFree() {
        List<String> rejected = new ArrayList<>();
        for (MobAbility ability : templates().values()) {
            String name = ability.particle();
            if (name.isEmpty()) {
                continue;
            }
            try {
                Particle particle = Particle.valueOf(name.toUpperCase(Locale.ROOT));
                if (particle.getDataType() != Void.class) {
                    rejected.add(ability.id() + " -> " + name + " (データ必須)");
                }
            } catch (IllegalArgumentException ex) {
                rejected.add(ability.id() + " -> " + name + " (そんな Particle は無い)");
            }
        }
        assertEquals(List.of(), rejected,
                "MobAbilityExecutor#particle が弾いて演出が出なくなるパーティクルがある: " + rejected);
    }

    @Test
    @DisplayName("予告設置型(delayed_zone)は予告時間を持つ（0 は既定へ落ちるが、明示してあること）")
    void delayedZoneTemplatesDeclareTheirTelegraph() {
        for (MobAbility ability : templates().values()) {
            if (ability.type() != MobAbility.Type.DELAYED_ZONE) {
                continue;
            }
            assertTrue(ability.durationSeconds() > 0.0,
                    ability.id() + ": delayed_zone は duration-seconds(予告秒)を明示すること");
            assertTrue(ability.delayTicks() >= MobAbility.MIN_DELAY_TICKS
                            && ability.delayTicks() <= MobAbility.MAX_DELAY_TICKS,
                    ability.id() + ": 予告が範囲外");
        }
    }
}
