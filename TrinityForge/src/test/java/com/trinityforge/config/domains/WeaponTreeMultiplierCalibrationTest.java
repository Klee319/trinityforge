package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>武器3ツリー(軽量武器 / 重量武器 / 弓術)が配る「倍率系」の較正を固定する</b>
 * (2026-08-15, W-31)。
 *
 * <h2>何が問題だったか</h2>
 * 武器ツリーは {@code attack-power} を1も配らず「主火力は装備・ツリーは倍率」という分担で
 * 設計されている。ところが倍率側が装備の土俵から大きく外れていた:
 * <ul>
 *   <li>会心率: ツリー 0.60(+プレステージ 0.10/回) に対し武器は 0.07〜0.19</li>
 *   <li>会心ダメージ: 重量武器ツリー 1.20 に対し武器は 0.75</li>
 *   <li>追加ダメージ率: ツリー 0.45 に対し装備側は武器が1件も持たず、スレッド由来だけ</li>
 * </ul>
 * ユーザー判断(2026-08-15)で「ツリー ≒ 装備の1.5倍まで」へ引き下げた。
 *
 * <h2>ギリシャ路線は排他なので単純合計してはいけない</h2>
 * {@code group} が同じ greek ノードは相互排他(各段で α/β/γ のどれか1つ)。したがって
 * 「あるステの到達可能な最大」は<b>group ごとに最大の1本だけ</b>を足した値になる。
 * 単純合計すると、実際には両立しない α と β を同時に数えてしまう。
 *
 * <h2>2本立てにしている理由</h2>
 * <ul>
 *   <li>{@link #treeTotals_matchTheCalibratedValues()} は較正値そのものの固定。
 *       yml を触った瞬間に落ちるので「気づかないうちに戻っていた」を防ぐ。</li>
 *   <li>{@link #treeTotals_stayWithinOneAndAHalfTimesTheGear()} は装備側の実データから
 *       毎回計算する。<b>装備を下げてツリーを据え置いた</b>ときにも落ちるので、
 *       固定値だけでは検出できないドリフトを拾う。</li>
 * </ul>
 */
class WeaponTreeMultiplierCalibrationTest {

    private static final String TREE_DIR = "src/main/resources/skilltree";
    private static final String ITEM_STATS = "src/main/resources/stats/item-stats.yml";

    /** ツリーが装備の土俵に対して許される倍率(ユーザー確定: 2026-08-15)。 */
    private static final double MAX_RATIO_TO_GEAR = 1.5;

    private static final String LIGHT = "light_weapons";
    private static final String HEAVY = "heavy_weapons";
    private static final String ARCHERY = "archery";

    /** ツリー名 -> その武器の {@code use-skill}(装備側の土俵を引くのに使う)。 */
    private static final Map<String, String> WEAPON_SKILL = Map.of(
            LIGHT, "LIGHT_WEAPONS", HEAVY, "HEAVY_WEAPONS", ARCHERY, "ARCHERY");

    private static final List<String> MULTIPLIER_KEYS =
            List.of("crit-chance", "crit-damage", "percent-bonus-damage");

    @Test
    @DisplayName("武器3ツリーの倍率系合計は 2026-08-15 の較正値どおり(ギリシャ路線の排他を考慮)")
    void treeTotals_matchTheCalibratedValues() {
        // 会心率は主軸2件(0.03 + 0.05)+ β路線(0.02/0.03/0.04/0.05/0.06)= 0.28。
        // 追加ダメージ率は α路線(0.01/0.02/0.03/0.04/0.05)= 0.15。
        // 重量武器の会心ダメージは主軸(0.10 + 0.15)+ γ路線(0.06/0.10/0.14/0.18/0.22)= 0.95。
        assertEquals(0.28, treeTotal(LIGHT, "crit-chance"), 1e-9, "軽量武器の会心率");
        assertEquals(0.30, treeTotal(LIGHT, "crit-damage"), 1e-9, "軽量武器の会心ダメージ(元から装備以下なので据え置き)");
        assertEquals(0.15, treeTotal(LIGHT, "percent-bonus-damage"), 1e-9, "軽量武器の追加ダメージ率");

        assertEquals(0.28, treeTotal(HEAVY, "crit-chance"), 1e-9, "重量武器の会心率");
        assertEquals(0.95, treeTotal(HEAVY, "crit-damage"), 1e-9, "重量武器の会心ダメージ");
        assertEquals(0.15, treeTotal(HEAVY, "percent-bonus-damage"), 1e-9, "重量武器の追加ダメージ率");

        // 弓術は元から装備の土俵の内側(会心率 0.10 / 追加ダメージ率 0.08)なので触っていない。
        assertEquals(0.10, treeTotal(ARCHERY, "crit-chance"), 1e-9, "弓術の会心率");
        assertEquals(0.08, treeTotal(ARCHERY, "percent-bonus-damage"), 1e-9, "弓術の追加ダメージ率");

        // プレステージは3回積めるので、1回ぶんの値がノード合計を上回ってはいけない。
        for (String tree : List.of(LIGHT, HEAVY)) {
            assertEquals(0.05, prestigeBuff(tree, "crit-chance"), 1e-9, tree + " のプレステージ会心率");
            assertEquals(0.15, prestigeBuff(tree, "crit-damage"), 1e-9, tree + " のプレステージ会心ダメージ");
            assertTrue(prestigeBuff(tree, "crit-chance") < treeTotal(tree, "crit-chance"),
                    tree + " のプレステージ1回ぶんの会心率がツリー全取りを上回っている。"
                            + "プレステージは5回まで積めるので、ここが逆転すると較正が意味を失う");
        }
    }

    @Test
    @DisplayName("武器ツリーの倍率系は装備(武器+防具+スレッド)の1.5倍を超えない")
    void treeTotals_stayWithinOneAndAHalfTimesTheGear() {
        List<String> offenders = new ArrayList<>();
        int compared = 0;
        for (Map.Entry<String, String> entry : WEAPON_SKILL.entrySet()) {
            for (String key : MULTIPLIER_KEYS) {
                double tree = treeTotal(entry.getKey(), key);
                if (tree <= 0) {
                    continue;
                }
                double gear = gearCeiling(entry.getValue(), key);
                assertTrue(gear > 0, "装備側から " + key + " を1件も拾えていない(この比較は空振りしている)");
                compared++;
                if (tree > gear * MAX_RATIO_TO_GEAR) {
                    offenders.add(entry.getKey() + "." + key + " ツリー=" + tree + " 装備=" + gear
                            + " (比 " + String.format("%.2f", tree / gear) + ")");
                }
            }
        }
        assertTrue(compared >= 6, "比較できた組が " + compared + " 件しかない。検査対象が消えているので空振りしている");
        assertTrue(offenders.isEmpty(),
                "武器ツリーの倍率が装備の土俵の1.5倍を超えている: " + offenders
                        + " —— 武器ツリーは attack-power を配らず「主火力は装備・ツリーは倍率」で分担している。"
                        + "倍率までツリー主導になると装備を更新する意味が消える");
    }

    @Test
    @DisplayName("軽量武器の出血は割合(bleed-damage-rate)で配られ、実数(bleed-damage)は1つも残っていない")
    void lightWeapons_bleedIsGrantedAsARate() {
        // W-30: 実数のままだと Lv0 で武器の +440%、Lv100 で +3.3% になる(帯に追随しない)。
        assertEquals(0.0, treeTotal(LIGHT, "bleed-damage"), 1e-9,
                "軽量武器ツリーに実数の bleed-damage が戻っている。帯に追随しないので率へ振り替えること");
        assertEquals(0.37, treeTotal(LIGHT, "bleed-damage-rate"), 1e-9,
                "軽量武器ツリーの出血ダメージ率の合計(主軸0.05 + 派生0.02 + γ路線0.02〜0.10 の 0.30)");

        // 乗算レイヤは「1レイヤ=1ステ」。基準ステと違うキーを書いても yml は通るが、実行時に
        // PerkBuffResolver#withValidMultiplierLayers が無言で捨てる ── 定義とセットで固定する。
        YamlConfiguration yaml = tree(LIGHT);
        ConfigurationSection multipliers = yaml.getConfigurationSection("nodes.C-1-2.mainhand-multipliers");
        assertTrue(multipliers != null, "C-1-2 の mainhand-multipliers が消えている");
        for (String layerId : multipliers.getKeys(false)) {
            ConfigurationSection stats = multipliers.getConfigurationSection(layerId);
            for (String stat : stats.getKeys(false)) {
                assertEquals(stat, layerStat(layerId),
                        "C-1-2 の " + layerId + " に基準ステ以外(" + stat + ")が書かれている。"
                                + "実行時に無言で捨てられるので、stats/lore.yml の multiplier-layers に"
                                + "そのステ用のレイヤを足すこと");
                assertEquals(1.2, stats.getDouble(stat), 1e-9, "C-1-2 の " + stat + " 倍率");
            }
        }
        assertEquals(2, multipliers.getKeys(false).size(),
                "C-1-2 は実数(アイテム由来の出血)と率(ツリー由来の出血)の両方を1.2倍する");
    }

    /** {@code stats/lore.yml multiplier-layers} が宣言する、そのレイヤの基準ステ。 */
    private static String layerStat(String layerId) {
        YamlConfiguration lore = YamlConfiguration.loadConfiguration(new File("src/main/resources/stats/lore.yml"));
        for (Map<?, ?> layer : lore.getMapList("multiplier-layers")) {
            if (layerId.equals(String.valueOf(layer.get("id")))) {
                return String.valueOf(layer.get("stat"));
            }
        }
        throw new AssertionError("stats/lore.yml の multiplier-layers に " + layerId + " が無い");
    }

    // ---- 出荷 yml の読み出し -------------------------------------------------

    private static YamlConfiguration tree(String name) {
        File file = new File(TREE_DIR, name + ".yml");
        if (!file.isFile()) {
            throw new AssertionError("出荷スキルツリーが見つからない: " + file.getAbsolutePath());
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    /**
     * 1ツリーで到達可能な {@code key} の合計。{@code group} 付き(greek)は排他なので
     * group ごとに最大の1本だけを足す。プレステージは含めない。
     */
    private static double treeTotal(String treeName, String key) {
        YamlConfiguration yaml = tree(treeName);
        ConfigurationSection nodes = yaml.getConfigurationSection("nodes");
        if (nodes == null) {
            throw new AssertionError(treeName + ".yml に nodes が無い");
        }
        double plain = 0;
        Map<String, Double> byGroup = new LinkedHashMap<>();
        for (String id : nodes.getKeys(false)) {
            double value = nodeBuff(nodes, id, key);
            if (value == 0) {
                continue;
            }
            String group = nodes.getString(id + ".group");
            if (group == null || group.isBlank()) {
                plain += value;
            } else {
                byGroup.merge(group, value, Math::max);
            }
        }
        double total = plain;
        for (double best : byGroup.values()) {
            total += best;
        }
        return total;
    }

    /** {@code buffs}(弓術) と {@code mainhand-buffs}(軽量/重量武器) の両方を見る。 */
    private static double nodeBuff(ConfigurationSection nodes, String id, String key) {
        double value = 0;
        for (String block : List.of("buffs", "mainhand-buffs")) {
            ConfigurationSection buffs = nodes.getConfigurationSection(id + "." + block);
            if (buffs != null && buffs.isSet(key)) {
                value += buffs.getDouble(key);
            }
        }
        return value;
    }

    private static double prestigeBuff(String treeName, String key) {
        YamlConfiguration yaml = tree(treeName);
        double value = 0;
        for (String block : List.of("buffs", "mainhand-buffs")) {
            ConfigurationSection buffs = yaml.getConfigurationSection("prestige." + block);
            if (buffs != null && buffs.isSet(key)) {
                value += buffs.getDouble(key);
            }
        }
        return value;
    }

    /**
     * 装備側の土俵: その武器スキルの武器1本 + 防具4部位 + スレッド(最良1種 × 防具の枠合計)の最大値。
     * 出荷 {@code item-stats.yml} から毎回計算するので、装備側を触れば基準も動く。
     */
    private static double gearCeiling(String weaponSkill, String key) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(ITEM_STATS));
        ConfigurationSection items = yaml.getConfigurationSection("items");
        if (items == null) {
            throw new AssertionError("item-stats.yml に items が無い: " + new File(ITEM_STATS).getAbsolutePath());
        }
        Set<String> slots = Set.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");
        Map<String, Double> bestPerSlot = new LinkedHashMap<>();
        Map<String, Double> bestSlotsPerSlot = new LinkedHashMap<>();
        double bestWeapon = 0;
        double bestThread = 0;
        for (String itemKey : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(itemKey);
            if (item == null) {
                continue;
            }
            double value = deepMax(item, key);
            String slot = slotOf(itemKey, slots);
            String skill = item.getString("use-skill");
            if (slot != null && ("HEAVY_ARMOR".equals(skill) || "LIGHT_ARMOR".equals(skill))) {
                bestPerSlot.merge(slot, value, Math::max);
                bestSlotsPerSlot.merge(slot, deepMax(item, "thread-slots"), Math::max);
            } else if (weaponSkill.equals(skill)) {
                bestWeapon = Math.max(bestWeapon, value);
            } else if (item.getBoolean("socketed-only-stats")) {
                bestThread = Math.max(bestThread, value);
            }
        }
        double armour = bestPerSlot.values().stream().mapToDouble(Double::doubleValue).sum();
        double threadSlots = bestSlotsPerSlot.values().stream().mapToDouble(Double::doubleValue).sum();
        return armour + bestWeapon + bestThread * threadSlots;
    }

    private static String slotOf(String itemKey, Set<String> slots) {
        String material = itemKey.contains("#") ? itemKey.substring(0, itemKey.indexOf('#')) : itemKey;
        int underscore = material.lastIndexOf('_');
        if (underscore < 0) {
            return null;
        }
        String suffix = material.substring(underscore + 1).toUpperCase();
        return slots.contains(suffix) ? suffix : null;
    }

    private static double deepMax(ConfigurationSection section, String key) {
        double best = 0;
        for (String child : section.getKeys(false)) {
            if (child.equals(key)) {
                best = Math.max(best, section.isConfigurationSection(child)
                        ? section.getDouble(child + ".max")
                        : section.getDouble(child));
            } else if (section.isConfigurationSection(child)) {
                best = Math.max(best, deepMax(section.getConfigurationSection(child), key));
            }
        }
        return best;
    }
}
