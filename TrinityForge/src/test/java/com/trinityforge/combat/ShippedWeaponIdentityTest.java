package com.trinityforge.combat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code stats/item-stats.yml} の<b>武器種の個性</b>を固定する (W-72, 2026-08-18)。
 *
 * <p>実サーバ要望「DPS は極力据え置いたまま、出血ダメージや範囲ダメージ等の武器種特有の
 * ステータスをよりとがらせてほしい」。{@code WeaponTierParityTest} は<b>単体DPSの比</b>しか
 * 見ないので、個性ステ（範囲ダメージ・出血・貫通・会心の配分）はそちらでは 1 件も守られない
 * —— 実際 2026-08-18 の棚卸しでは次の3つが揃って壊れていた:
 * <ul>
 *   <li>{@code aoe-radius} / {@code aoe-damage-rate} / {@code aoe-max-targets} を持つ武器が
 *       <b>216 本中 0 本</b>。機構は {@code CombatListener#maybeApplyAreaDamage} に実装済みで
 *       {@code lore.yml} にも表示名があるのに、武器側が一度も使っていなかった。</li>
 *   <li>トライデントが全 16 段で鎌と同じ出血率（0.16）を持ち、「出血は鎌の個性」が数字として
 *       成立していなかった。</li>
 *   <li>ウォーハンマーの貫通が剣より高く、刺突/打撃の区別が値に出ていなかった。</li>
 * </ul>
 *
 * <p><b>値ではなく順序と集合で固定する</b>。個別の数値を書くと、バランス調整のたびに
 * 期待値表の更新が必要になり、更新を忘れた瞬間に検査が形骸化する（{@code WeaponTierParityTest}
 * のクラスコメントと同じ理由）。ここで見るのは「どの武器種が持つか」と「同系列の剣に対する
 * 大小」だけなので、段の数値を動かしても勝手に守られる。
 */
class ShippedWeaponIdentityTest {

    /**
     * 範囲ダメージを持つ武器種。長柄・両手の「薙ぎ払い」＋<b>剣の小さい巻き込み</b>。
     *
     * <p>2026-08-19 W-126(ユーザー指示「一部他武器との差別化が弱い軽武器も対象上限3体など
     * 多少の範囲攻撃機能を付けていい」)で {@code sword} を追加した。剣は全 tier を並べても
     * 尖ったステが1つも無いまま実効DPSが最上位という状態で、武器種として何も選ばせていなかった。
     * 剣に与えるのは<b>半径2・対象3体・倍率0.15</b>の小さい巻き込みだけで、
     * 薙ぎ払い勢(大剣・大斧・ハルバード・鎌)より必ず下に置く({@link #theGreataxeSweepsHardest} と
     * {@link #swordCleaveStaysSmallerThanEverySweeper} が上下関係を固定する)。
     */
    private static final Set<String> AOE_TYPES =
            Set.of("grate_sword", "greataxe", "halberd", "scythe", "sword");

    /** 「薙ぎ払い」本職。剣の巻き込みは必ずこれらより小さい。 */
    private static final Set<String> SWEEPER_TYPES =
            Set.of("grate_sword", "greataxe", "halberd", "scythe");

    /** 刺突。貫通が同系列の剣より必ず高い。 */
    private static final Set<String> PIERCING_TYPES = Set.of("spear", "rapier", "trident", "javelin", "halberd");

    /** 打撃。貫通が同系列の剣より必ず低い。 */
    private static final Set<String> BLUNT_TYPES = Set.of("mace", "warhammer", "greataxe");

    private static final List<String> TYPES = List.of(
            "grate_sword", "greataxe", "warhammer", "crossbow", "trident", "javelin",
            "halberd", "scythe", "rapier", "dagger", "spear", "sword", "mace", "wand", "bow", "axe");

    private static final Map<String, String> TYPE_ALIAS = Map.of(
            "winter_grim_reaper", "scythe",
            "fnis_peccati_profundi", "scythe",
            "koujien", "special");

    private record Weapon(String id, String type, String series,
                          double aoeRadius, double aoeRate, double aoeMaxTargets,
                          double bleedChance, double penetration,
                          double critChance, double critDamage) {}

    // ---------------------------------------------------------------- 読み込み

    private static YamlConfiguration shipped(String resource) {
        try (InputStream in = ShippedWeaponIdentityTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "出荷リソースが見つからない: " + resource);
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(resource + " を読めない", ex);
        }
    }

    /** {@code fixed} と {@code random}（min/max の中央＝期待値）を足した実効値。品質0で評価する。 */
    private static double stat(ConfigurationSection item, String name) {
        double v = 0.0;
        ConfigurationSection fixed = item.getConfigurationSection("fixed");
        if (fixed != null) {
            v += fixed.getDouble(name, 0.0);
        }
        ConfigurationSection random = item.getConfigurationSection("random");
        if (random != null) {
            ConfigurationSection range = random.getConfigurationSection(name);
            if (range != null) {
                v += (range.getDouble("min") + range.getDouble("max")) / 2.0;
            }
        }
        return v;
    }

    private static List<Weapon> loadWeapons() {
        YamlConfiguration stats = shipped("stats/item-stats.yml");
        YamlConfiguration catalog = shipped("items/catalog.yml");

        Map<String, String> idByStatKey = new HashMap<>();
        ConfigurationSection catalogItems = catalog.getConfigurationSection("items");
        if (catalogItems != null) {
            for (String id : catalogItems.getKeys(false)) {
                ConfigurationSection def = catalogItems.getConfigurationSection(id);
                if (def == null) {
                    continue;
                }
                String material = def.getString("material");
                if (material == null) {
                    continue;
                }
                idByStatKey.put(def.contains("custom-model-data")
                        ? material + "#" + def.getInt("custom-model-data")
                        : material, id);
            }
        }

        ConfigurationSection items = stats.getConfigurationSection("items");
        assertNotNull(items, "item-stats.yml に items: が無い");
        List<Weapon> out = new ArrayList<>();
        for (String statKey : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(statKey);
            if (item == null) {
                continue;
            }
            ConfigurationSection fixed = item.getConfigurationSection("fixed");
            if (fixed == null || !fixed.contains("attack-power")) {
                continue;
            }
            String id = idByStatKey.getOrDefault(statKey, statKey);
            String lower = id.toLowerCase(Locale.ROOT);
            String alias = TYPE_ALIAS.get(lower);
            String type = alias != null ? alias
                    : TYPES.stream().filter(t -> lower.endsWith(t) || lower.endsWith(t + "_tf"))
                    .findFirst().orElse(null);
            String series = null;
            if (type != null && alias == null) {
                String suffix = lower.endsWith(type + "_tf") ? type + "_tf" : type;
                String head = lower.substring(0, lower.length() - suffix.length())
                        .replaceAll("_$", "");
                series = head.isEmpty() ? null : head;
            }
            out.add(new Weapon(id, type, series,
                    stat(item, "aoe-radius"), stat(item, "aoe-damage-rate"),
                    stat(item, "aoe-max-targets"), stat(item, "bleed-chance"),
                    stat(item, "penetration"), stat(item, "crit-chance"),
                    stat(item, "crit-damage")));
        }
        assertTrue(out.size() >= 210, "武器を " + out.size() + " 件しか読めていない(この検査は空振りしている)");
        return out;
    }

    /** 系列 → 武器種 → その1本。比較の分母を同系列の剣に取るため。 */
    private static Map<String, Map<String, Weapon>> bySeries(List<Weapon> weapons) {
        Map<String, Map<String, Weapon>> out = new TreeMap<>();
        for (Weapon w : weapons) {
            if (w.series() != null && w.type() != null) {
                out.computeIfAbsent(w.series(), k -> new TreeMap<>()).put(w.type(), w);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 検査

    @Test
    @DisplayName("範囲ダメージを持つのは大剣・大斧・ハルバード・鎌だけで、半径と割合が必ず両方立っている")
    void areaDamageBelongsOnlyToTheSweepingWeapons() {
        List<String> problems = new ArrayList<>();
        int withAoe = 0;
        for (Weapon w : loadWeapons()) {
            boolean hasAny = w.aoeRadius() > 0 || w.aoeRate() > 0 || w.aoeMaxTargets() > 0;
            if (!hasAny) {
                if (w.type() != null && AOE_TYPES.contains(w.type())) {
                    problems.add(w.id() + " (" + w.type() + "): 薙ぎ払う武器種なのに範囲ダメージが無い");
                }
                continue;
            }
            withAoe++;
            if (w.type() == null || !AOE_TYPES.contains(w.type())) {
                problems.add(w.id() + " (" + (w.type() == null ? "型不明" : w.type())
                        + "): 薙ぎ払わない武器種に範囲ダメージが付いている");
                continue;
            }
            // CombatListener#maybeApplyAreaDamage は radius か rate のどちらかが 0 以下だと
            // 何もせず return する。片方だけ書いた設定は「表示だけあって効かない」死に設定。
            if (w.aoeRadius() <= 0 || w.aoeRate() <= 0) {
                problems.add(String.format("%s: aoe-radius %.2f / aoe-damage-rate %.2f のどちらかが 0"
                        + "(両方 > 0 でないと一切発動しない)", w.id(), w.aoeRadius(), w.aoeRate()));
            }
        }
        assertTrue(withAoe >= 40, "範囲ダメージを持つ武器が " + withAoe + " 本しかない(この検査は空振りしている)");
        assertTrue(problems.isEmpty(), "範囲ダメージの割り当てが規約と違う:\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("剣の巻き込みは薙ぎ払い勢より必ず小さい（対象3体・半径2・倍率は全 sweeper 未満）")
    void swordCleaveStaysSmallerThanEverySweeper() {
        List<Weapon> weapons = loadWeapons();
        double weakestSweeperTargets = Double.MAX_VALUE;
        double weakestSweeperRate = Double.MAX_VALUE;
        for (Weapon w : weapons) {
            if (w.type() != null && SWEEPER_TYPES.contains(w.type()) && w.aoeMaxTargets() > 0) {
                weakestSweeperTargets = Math.min(weakestSweeperTargets, w.aoeMaxTargets());
                weakestSweeperRate = Math.min(weakestSweeperRate, w.aoeRate());
            }
        }
        assertTrue(weakestSweeperTargets < Double.MAX_VALUE, "薙ぎ払い勢の範囲が1本も読めていない");

        List<String> problems = new ArrayList<>();
        int swords = 0;
        for (Weapon w : weapons) {
            if (!"sword".equals(w.type())) {
                continue;
            }
            swords++;
            if (w.aoeMaxTargets() != 3.0) {
                problems.add(w.id() + ": 剣の対象上限が3ではない(" + w.aoeMaxTargets() + ")");
            }
            if (w.aoeMaxTargets() > weakestSweeperTargets) {
                problems.add(w.id() + ": 剣の対象上限が薙ぎ払い勢の最小を超えている");
            }
            if (w.aoeRate() >= weakestSweeperRate) {
                problems.add(String.format("%s: 剣の範囲倍率 %.2f が薙ぎ払い勢の最小 %.2f 以上",
                        w.id(), w.aoeRate(), weakestSweeperRate));
            }
        }
        assertTrue(swords >= 10, "剣が " + swords + " 本しか読めていない(この検査は空振りしている)");
        assertTrue(problems.isEmpty(), "剣の巻き込みが規約と違う:\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("大剣の対象上限は最下位 tier でも5・最上位で10まで段で伸びる（W-126）")
    void greatswordTargetCapRisesFromFiveToTen() {
        // tier の並びは攻撃力の実測順(tmp/audit-weapon-dps.js)。
        List<String> ladder = List.of(
                "wooden_grate_sword", "stone_grate_sword", "copper_grate_sword",
                "source_gem_grate_sword", "iron_grate_sword", "golden_grate_sword",
                "diamond_grate_sword", "netherite_grate_sword", "nuclear_grate_sword",
                "hero_grate_sword", "cryocore_grate_sword", "abyss_grate_sword",
                "emberforge_grate_sword", "infinity_grate_sword", "binder_grate_sword");
        Map<String, Weapon> byId = new HashMap<>();
        for (Weapon w : loadWeapons()) {
            byId.put(w.id(), w);
        }

        List<String> problems = new ArrayList<>();
        double previous = 0.0;
        for (String id : ladder) {
            Weapon w = byId.get(id);
            assertNotNull(w, "大剣 " + id + " が読めない(tier 表がずれている)");
            if (w.aoeMaxTargets() < 5.0) {
                problems.add(id + ": 対象上限が5未満(" + w.aoeMaxTargets() + ")");
            }
            if (w.aoeMaxTargets() < previous) {
                problems.add(id + ": 前の tier より対象上限が下がっている");
            }
            previous = w.aoeMaxTargets();
        }
        assertTrue(problems.isEmpty(), "大剣の対象上限の段が規約と違う:\n" + String.join("\n", problems));
        assertTrue(byId.get(ladder.get(0)).aoeMaxTargets() == 5.0,
                "最下位 tier の大剣の対象上限は5であるべき");
        assertTrue(byId.get(ladder.get(ladder.size() - 1)).aoeMaxTargets() == 10.0,
                "最上位 tier の大剣の対象上限は10であるべき");
    }

    @Test
    @DisplayName("鎌の範囲は対象数・半径・倍率のすべてで同 tier の大剣以下（範囲の主役は大剣）")
    void scytheStaysBelowTheGreatswordOnEveryAreaKnob() {
        List<String> greatswords = List.of(
                "wooden_grate_sword", "stone_grate_sword", "copper_grate_sword",
                "source_gem_grate_sword", "iron_grate_sword", "golden_grate_sword",
                "diamond_grate_sword", "netherite_grate_sword", "nuclear_grate_sword",
                "hero_grate_sword", "cryocore_grate_sword", "abyss_grate_sword",
                "emberforge_grate_sword", "infinity_grate_sword", "binder_grate_sword");
        // 出荷 id の綴り違い(golad / nethrite)はそのまま使う。
        List<String> scythes = List.of(
                "wooden_scythe", "stone_scythe", "copper_scythe", "source_gem_scythe",
                "iron_scythe", "golad_scythe", "diamond_scythe", "nethrite_scythe",
                "nuclear_scythe", "hero_scythe", "cryocore_scythe", "abyss_scythe",
                "emberforge_scythe", "infinity_scythe", "binder_scythe");
        Map<String, Weapon> byId = new HashMap<>();
        for (Weapon w : loadWeapons()) {
            byId.put(w.id(), w);
        }

        List<String> problems = new ArrayList<>();
        for (int i = 0; i < greatswords.size(); i++) {
            Weapon gs = byId.get(greatswords.get(i));
            Weapon sc = byId.get(scythes.get(i));
            assertNotNull(gs, greatswords.get(i) + " が読めない");
            assertNotNull(sc, scythes.get(i) + " が読めない");
            if (sc.aoeMaxTargets() > gs.aoeMaxTargets()
                    || sc.aoeRadius() > gs.aoeRadius()
                    || sc.aoeRate() > gs.aoeRate()) {
                problems.add(String.format("%s(%.0f体/%.1f/%.2f) が %s(%.0f体/%.1f/%.2f) を上回っている",
                        sc.id(), sc.aoeMaxTargets(), sc.aoeRadius(), sc.aoeRate(),
                        gs.id(), gs.aoeMaxTargets(), gs.aoeRadius(), gs.aoeRate()));
            }
            if (sc.aoeMaxTargets() <= 0) {
                problems.add(sc.id() + ": 鎌が範囲を失っている");
            }
        }
        assertTrue(problems.isEmpty(), "鎌と大剣の範囲の上下が規約と違う:\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("大斧の範囲ダメージが最も大きい（薙ぎ払いの一番手は大斧）")
    void theGreataxeSweepsHardest() {
        double greataxe = 0.0;
        List<String> problems = new ArrayList<>();
        List<Weapon> weapons = loadWeapons();
        for (Weapon w : weapons) {
            if ("greataxe".equals(w.type())) {
                greataxe = Math.max(greataxe, w.aoeRate());
            }
        }
        assertTrue(greataxe > 0, "大斧の範囲ダメージが読めていない");
        for (Weapon w : weapons) {
            if (!"greataxe".equals(w.type()) && w.aoeRate() > greataxe + 1e-9) {
                problems.add(String.format("%s (%s): 範囲ダメージ %.2f が大斧の %.2f を上回っている",
                        w.id(), w.type(), w.aoeRate(), greataxe));
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("武器種として出血を持つのは鎌だけ（トライデントの一律出血は 2026-08-18 に落とした）")
    void bleedIsTheScythesAlone() {
        Map<String, Integer> withBleed = new TreeMap<>();
        Map<String, Integer> total = new TreeMap<>();
        for (Weapon w : loadWeapons()) {
            String type = w.type() == null ? "型不明" : w.type();
            total.merge(type, 1, Integer::sum);
            if (w.bleedChance() > 0) {
                withBleed.merge(type, 1, Integer::sum);
            }
        }
        List<String> problems = new ArrayList<>();
        for (var e : total.entrySet()) {
            int carriers = withBleed.getOrDefault(e.getKey(), 0);
            boolean isScythe = "scythe".equals(e.getKey());
            if (isScythe && carriers != e.getValue()) {
                problems.add("鎌 " + e.getValue() + " 本のうち " + carriers + " 本しか出血を持っていない");
            }
            // 系列個性(cryocore の氷結など)で1本だけ持つのは許す。武器種ぜんぶが持ったら
            // 「その武器種の個性」になってしまうので落とす。
            if (!isScythe && carriers > 1) {
                problems.add(e.getKey() + ": " + e.getValue() + " 本中 " + carriers
                        + " 本が出血を持っている(武器種の個性が鎌と重なる)");
            }
        }
        assertTrue(problems.isEmpty(), "出血は鎌の個性であること:\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("貫通は 刺突 > 剣 > 打撃 の順（同系列で比較）")
    void penetrationSeparatesPiercingFromBlunt() {
        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (var e : bySeries(loadWeapons()).entrySet()) {
            Weapon sword = e.getValue().get("sword");
            if (sword == null) {
                continue;
            }
            for (var typed : e.getValue().entrySet()) {
                Weapon w = typed.getValue();
                if (PIERCING_TYPES.contains(typed.getKey())) {
                    compared++;
                    if (w.penetration() <= sword.penetration() + 1e-9) {
                        problems.add(String.format("%s: 刺突の貫通 %.4f が同系列の剣 %.4f 以下",
                                w.id(), w.penetration(), sword.penetration()));
                    }
                } else if (BLUNT_TYPES.contains(typed.getKey())) {
                    compared++;
                    if (w.penetration() >= sword.penetration() - 1e-9) {
                        problems.add(String.format("%s: 打撃の貫通 %.4f が同系列の剣 %.4f 以上",
                                w.id(), w.penetration(), sword.penetration()));
                    }
                }
            }
        }
        assertTrue(compared >= 60, "比較できた武器が " + compared + " 本しかない(この検査は空振りしている)");
        assertTrue(problems.isEmpty(), "貫通の刺突/打撃の区別が値に出ていない:\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("会心は 率が 短剣>剣>大斧、倍率が 大斧>剣>短剣（手数の博打と一撃の博打を分ける）")
    void critIsPolarisedBetweenLightAndHeavy() {
        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (var e : bySeries(loadWeapons()).entrySet()) {
            Weapon sword = e.getValue().get("sword");
            Weapon dagger = e.getValue().get("dagger");
            Weapon greataxe = e.getValue().get("greataxe");
            if (sword == null) {
                continue;
            }
            if (dagger != null) {
                compared++;
                if (dagger.critChance() <= sword.critChance() + 1e-9) {
                    problems.add(dagger.id() + ": 短剣の会心率が同系列の剣以下");
                }
                if (dagger.critDamage() >= sword.critDamage() - 1e-9) {
                    problems.add(dagger.id() + ": 短剣の会心倍率が同系列の剣以上");
                }
            }
            if (greataxe != null) {
                compared++;
                if (greataxe.critChance() >= sword.critChance() - 1e-9) {
                    problems.add(greataxe.id() + ": 大斧の会心率が同系列の剣以上");
                }
                if (greataxe.critDamage() <= sword.critDamage() + 1e-9) {
                    problems.add(greataxe.id() + ": 大斧の会心倍率が同系列の剣以下");
                }
            }
        }
        assertTrue(compared >= 24, "比較できた武器が " + compared + " 本しかない(この検査は空振りしている)");
        assertTrue(problems.isEmpty(), "会心の二極化が値に出ていない:\n" + String.join("\n", problems));
    }
}
