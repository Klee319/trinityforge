package com.trinityforge.config.domains;

import com.trinityforge.mobs.LevelTierDropEntry;
import com.trinityforge.mobs.MobTargetFilter;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code add-drops} の {@code environment:} 軸(2026-08-19 / W-128 ドラゴンの卵)。
 *
 * <h2>なぜこの軸が要るのか</h2>
 * 既存の {@code where:} は「討伐したワールドがダンジョンインスタンスか」しか見ない。
 * つまり<b>オーバーワールドもジ・エンドもどちらも {@code field}</b> になり、両方に出る
 * エンダードラゴンを片方だけに限定できない。依頼は「オーバーワールドのエンダードラゴン」
 * だったので、ここを絞らないとエンドクリスタルで復活させたエンドラからも卵が出る。
 *
 * <h2>この検査が無いと何が無言で壊れるか</h2>
 * <ul>
 *   <li>{@code environment:} を書き忘れる / タイプミスする → 空集合＝「問わない」に倒れ、
 *       ジ・エンドでも落ちるようになる。ログにも出ない。</li>
 *   <li>帯(tiers)は floor lookup で1つしか選ばれないので、6帯のどれかに書き漏らすと
 *       そのレベル帯でだけ落ちなくなる。</li>
 * </ul>
 */
class LevelTierDropEnvironmentFilterTest {

    private static final Logger LOG = Logger.getLogger("LevelTierDropEnvironmentFilterTest");

    private static final String MOB_LEVEL_TABLE = "src/main/resources/combat/mob-level-table.yml";

    // ------------------------------------------------------------------ 純関数

    @Test
    @DisplayName("environment: 未指定のエントリはどのディメンションでも落ちる(後方互換)")
    void emptyEnvironmentMatchesEverything() {
        LevelTierDropEntry entry = LevelTierDropEntry.ofMaterial(
                Material.BONE, 1.0, 1, 1, MobTargetFilter.EMPTY);
        assertTrue(entry.environments().isEmpty());
        for (World.Environment env : World.Environment.values()) {
            assertTrue(entry.appliesInEnvironment(env), env + " で落ちなくなっている");
        }
        assertTrue(entry.appliesInEnvironment(null),
                "ワールドが取れない場合でも、指定が無いエントリは従来どおり落ちる必要がある");
    }

    @Test
    @DisplayName("environment: [NORMAL] はオーバーワールドだけに一致する")
    void normalOnlyEntryExcludesTheEnd() {
        LevelTierDropEntry entry = LevelTierDropEntry.ofMaterial(
                Material.DRAGON_EGG, 1.0, 1, 1,
                MobTargetFilter.of(Set.of(EntityType.ENDER_DRAGON), null), Set.of(),
                null, LevelTierDropEntry.DropScope.FIELD, null,
                Set.of(World.Environment.NORMAL));

        assertTrue(entry.appliesInEnvironment(World.Environment.NORMAL));
        assertFalse(entry.appliesInEnvironment(World.Environment.THE_END),
                "ジ・エンドのエンダードラゴンからも落ちてしまう");
        assertFalse(entry.appliesInEnvironment(World.Environment.NETHER));
        assertFalse(entry.appliesInEnvironment(null),
                "判定できないときは落とさない(baby: と同じ、設定ミスに気づける方へ倒す)");
    }

    // ------------------------------------------------------------------ 解釈

    @Test
    @DisplayName("environment: は単一値でもリストでも読める / 不正値は読み飛ばしてエントリは生かす")
    void parsesEnvironmentKey() throws Exception {
        MobLevelTableConfig.ParseResult r = parse("""
                dungeon-only: false
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: DRAGON_EGG, chance: 1.0, min: 1, max: 1, environment: [NORMAL] }
                      - { material: BONE, chance: 1.0, min: 1, max: 1, environment: THE_END }
                      - { material: STICK, chance: 1.0, min: 1, max: 1, environment: [NOT_A_DIMENSION] }
                """);
        List<LevelTierDropEntry> drops = r.tiers().resolve(0).orElseThrow().addDrops();
        assertEquals(3, drops.size(), "不正な environment でエントリごと落としてはいけない");
        assertEquals(Set.of(World.Environment.NORMAL), drops.get(0).environments());
        assertEquals(Set.of(World.Environment.THE_END), drops.get(1).environments(),
                "単一値(リストでない)も受け付ける必要がある");
        assertTrue(drops.get(2).environments().isEmpty(),
                "不正値は読み飛ばして『問わない』へ倒す(警告はログに出る)");
    }

    // ------------------------------------------------------------------ 出荷設定

    @Test
    @DisplayName("出荷設定: ドラゴンの卵は全帯に 100%/1個・ENDER_DRAGON・field・NORMAL 限定で載っている")
    void shippedDragonEggEntryIsWiredInEveryBand() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(Files.readString(Path.of(MOB_LEVEL_TABLE)));
        List<?> tiers = cfg.getList("tiers");
        assertTrue(tiers != null && tiers.size() >= 2,
                "帯が読めない。このテストは帯ごとの配線を見るので前提が崩れている");

        List<String> problems = new ArrayList<>();
        int found = 0;
        for (Object tierRaw : tiers) {
            if (!(tierRaw instanceof Map<?, ?> tier)) continue;
            Object minLevel = tier.get("min-level");
            Map<?, ?> egg = null;
            if (tier.get("add-drops") instanceof List<?> addDrops) {
                for (Object dropRaw : addDrops) {
                    if (dropRaw instanceof Map<?, ?> drop
                            && "DRAGON_EGG".equals(String.valueOf(drop.get("material")))) {
                        egg = drop;
                        break;
                    }
                }
            }
            if (egg == null) {
                problems.add("min-level=" + minLevel + ": DRAGON_EGG のエントリが無い");
                continue;
            }
            found++;
            if (!(egg.get("chance") instanceof Number chance) || chance.doubleValue() != 1.0) {
                problems.add("min-level=" + minLevel + ": chance が 1.0 でない (" + egg.get("chance") + ")");
            }
            if (!Integer.valueOf(1).equals(egg.get("min")) || !Integer.valueOf(1).equals(egg.get("max"))) {
                problems.add("min-level=" + minLevel + ": 個数が 1 個固定でない ("
                        + egg.get("min") + "〜" + egg.get("max") + ")");
            }
            if (!List.of("ENDER_DRAGON").equals(egg.get("mobs"))) {
                problems.add("min-level=" + minLevel + ": mobs が [ENDER_DRAGON] でない (" + egg.get("mobs") + ")");
            }
            if (!"field".equals(String.valueOf(egg.get("where")))) {
                problems.add("min-level=" + minLevel + ": where が field でない (" + egg.get("where") + ")");
            }
            if (!List.of("NORMAL").equals(egg.get("environment"))) {
                problems.add("min-level=" + minLevel + ": environment が [NORMAL] でない ("
                        + egg.get("environment") + ") — ジ・エンドのエンドラからも落ちる");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertEquals(tiers.size(), found,
                "帯は floor lookup で1つしか選ばれないので、全帯に同じ内容で載っている必要がある");
    }

    private static MobLevelTableConfig.ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return MobLevelTableConfig.parse(cfg, LOG);
    }
}
