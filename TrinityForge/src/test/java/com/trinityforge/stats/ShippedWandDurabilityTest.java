package com.trinityforge.stats;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 杖(魔法の詠唱に使うアイテム)が<b>耐久を持つ</b>ことを固定する
 * (2026-08-22 ユーザー報告「杖を使用時に耐久値が減らない」)。
 *
 * <p><b>真因は fork ではなく、ここで検査する出荷 yml のほうだった。</b>
 * ArsPaper の {@code SpellCaster#consumeCastDurability}(M-5)は実装済みで、配備先の
 * {@code plugins/ArsPaper/config.yml} も {@code cast-durability.enabled: true} だった。
 * 落ちていたのは {@code ItemAssembler}:
 *
 * <pre>{@code meta.setUnbreakable(effectiveDurability == null);}</pre>
 *
 * つまり <b>{@code durability} ステを書かなかったアイテムは「壊れない品」になる</b>。
 * 出荷 {@code item-stats.yml} の武器/防具エントリのうち {@code durability} を持たないのは
 * 杖10種と SHIELD だけで、杖はまるごと {@code isUnbreakable() == true} になっていた。
 * fork 側は W-173 と同じ理由で {@code isUnbreakable()} をきちんと見るので、
 * <b>耐久を減らす処理に到達する前に必ず return していた</b>。
 *
 * <p>だからこのテストが見るのは「fork が耐久を減らすか」ではなく
 * <b>「杖が壊れうる品として出荷されているか」</b>。config を1行消すだけで
 * 同じ症状が音もなく戻るので、コード側ではなくここで縛る。
 */
class ShippedWandDurabilityTest {

    /** 杖の判別: 詠唱に使うアイテム = {@code use-skill: ARS_MAGIC}。 */
    private static final String WAND_SKILL = "ARS_MAGIC";

    private static ConfigurationSection items() {
        try (InputStream in = ShippedWandDurabilityTest.class.getResourceAsStream("/stats/item-stats.yml")) {
            assertNotNull(in, "出荷 stats/item-stats.yml がクラスパスに無い");
            ConfigurationSection section = YamlConfiguration
                    .loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getConfigurationSection("items");
            assertNotNull(section, "items ブロックが無い");
            return section;
        } catch (java.io.IOException e) {
            throw new AssertionError("出荷 stats/item-stats.yml を読めない", e);
        }
    }

    /**
     * {@code MATERIAL#cmd} の材質名。
     *
     * <p><b>{@link Material} へ解決しない</b>のは意図的 — {@code COPPER_SWORD} のように
     * バニラの enum に存在しない材質キーが出荷 yml に実在し、{@code Material.getMaterial} が
     * {@code null} を返して<b>検査対象が静かに空になる</b>(初版がこれで「杖0件」になった)。
     * 名前で比べるだけならその穴が無い。
     */
    private static String materialOf(String key) {
        return key.contains("#") ? key.substring(0, key.indexOf('#')) : key;
    }

    /** 耐久を持てる材質か(名前で判定。理由は {@link #materialOf} の javadoc)。 */
    private static boolean isDamageable(String material) {
        return material.endsWith("_SWORD") || material.endsWith("_AXE") || material.endsWith("_PICKAXE")
                || material.endsWith("_SHOVEL") || material.endsWith("_HOE")
                || material.equals("BOW") || material.equals("CROSSBOW") || material.equals("TRIDENT");
    }

    private static List<String> wandKeys() {
        ConfigurationSection items = items();
        List<String> wands = new ArrayList<>();
        for (String key : items.getKeys(false)) {
            ConfigurationSection entry = items.getConfigurationSection(key);
            if (entry == null || !WAND_SKILL.equals(entry.getString("use-skill"))) {
                continue;
            }
            if (!isDamageable(materialOf(key))) {
                // 魔導書の BOOK のように耐久を持てない材質は対象外(バニラ側に上限が無い)。
                continue;
            }
            wands.add(key);
        }
        return wands;
    }

    @Test
    @DisplayName("杖は durability ステを持つ(無いと unbreakable になり、詠唱で耐久が1も減らない)")
    void everyWandCarriesADurabilityStat() {
        List<String> wands = wandKeys();
        assertFalse(wands.isEmpty(), "杖(use-skill: ARS_MAGIC で耐久を持てる材質)が1件も無い。判別条件が腐っている");

        ConfigurationSection items = items();
        List<String> unbreakable = new ArrayList<>();
        for (String key : wands) {
            ConfigurationSection entry = items.getConfigurationSection(key);
            boolean hasDurability = entry.contains("fixed.durability")
                    || entry.contains("per-quality.durability")
                    || entry.contains("random.durability")
                    || entry.contains("durability");
            if (!hasDurability) {
                unbreakable.add(key);
            }
        }
        assertEquals(List.of(), unbreakable,
                "durability の無い杖は ItemAssembler が setUnbreakable(true) にするので、"
                        + "SpellCaster#consumeCastDurability が isUnbreakable() で必ず抜ける");
    }

    @Test
    @DisplayName("杖の耐久は同じ材質の武器が使っている値のどれか(独自の数を発明しない)")
    void wandDurabilityReusesAValueFromTheSameMaterial() {
        ConfigurationSection items = items();

        // 「同じ材質の1件目と一致」までは縛れない ── NETHERITE_SWORD には通常帯(1218)と
        // 儀式製の最上位帯(3654)の2つがあり、杖も上位3種(魔源/黒淵/冥境)は上位帯に属する。
        // 縛るのは【同じ材質の武器が実際に使っている値のどれか】であること。
        // これで「杖だけ独自の数」を防ぎつつ、帯が複数ある材質でも誤検知しない。
        List<String> invented = new ArrayList<>();
        for (String wand : wandKeys()) {
            String material = materialOf(wand);
            java.util.Set<Integer> allowed = new java.util.LinkedHashSet<>();
            for (String key : items.getKeys(false)) {
                if (key.equals(wand) || !materialOf(key).equals(material)) {
                    continue;
                }
                ConfigurationSection entry = items.getConfigurationSection(key);
                if (entry == null || WAND_SKILL.equals(entry.getString("use-skill"))
                        || !entry.contains("fixed.durability")) {
                    continue;
                }
                allowed.add(entry.getInt("fixed.durability"));
            }
            if (allowed.isEmpty()) {
                // 同じ材質の比較対象が無い杖(将来の専用材質)は縛らない。
                continue;
            }
            int actual = items.getInt(wand + ".fixed.durability", -1);
            if (!allowed.contains(actual)) {
                invented.add(wand + "=" + actual + " (" + material + " が使う値: " + allowed + ")");
            }
        }
        assertEquals(List.of(), invented, "杖の耐久が同じ材質の武器のどの帯とも一致しない");
    }
}
