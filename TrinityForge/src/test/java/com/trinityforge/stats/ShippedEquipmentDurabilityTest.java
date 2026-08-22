package com.trinityforge.stats;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code item-stats.yml} の<b>耐久を持てる材質のエントリは、1件残らず
 * {@code durability} ステを持つ</b>ことを固定する（2026-08-22）。
 *
 * <p><b>なぜ全件で縛るのか。</b> {@code ItemAssembler} の
 *
 * <pre>{@code meta.setUnbreakable(effectiveDurability == null);}</pre>
 *
 * は「書き忘れ ＝ 壊れない品」という<b>逆向きの既定値</b>になっている。しかも壊れない品は
 * 耐久バーが出ないので、<b>実機では「減らない」としか見えず誰も気づけない</b>。
 * 同じ日に杖10種（詠唱で耐久が1も減らない）と SHIELD（永久に壊れない盾）の2件が
 * まったく同じ理由で見つかった。1件ずつ回帰テストを足しても次の書き忘れは止まらないので、
 * ここで全件を見る。
 *
 * <p><b>「耐久を持てるか」は {@link Material#getMaxDurability()} で決める</b> ── 材質名の
 * サフィックス列挙（{@code _SWORD} など）にすると<b>それ自体が許可リスト</b>になり、
 * 列挙から漏れた材質は検査ごと素通りする。
 *
 * <p>⚠️ <b>{@code getMaxDurability()} はレジストリを引くのでサーバが要る</b>
 * （素で呼ぶと {@code Bukkit.server is null} で落ちる）。MockBukkit を起動しているのはそのため。
 */
class ShippedEquipmentDurabilityTest {

    /**
     * バニラの {@link Material} に解決できない材質キー。<b>今は1件も無い</b>
     * （{@code COPPER_SWORD} も {@code *_SPEAR} も Paper 1.21.11 の enum に実在する）。
     *
     * <p>空のまま固定しておくのは、解決できない名前が増えたときに<b>黙って検査対象から
     * 外れる</b>のを防ぐため。{@code Material.getMaterial} が {@code null} を返す名前を
     * 素通りさせると、その材質だけ耐久の書き忘れを見逃す
     * （同じ罠で「杖0件」になり検査が空振りした前例がある）。
     */
    private static final Set<String> KNOWN_NON_VANILLA_MATERIALS = Set.of();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ConfigurationSection items() {
        try (InputStream in = ShippedEquipmentDurabilityTest.class
                .getResourceAsStream("/stats/item-stats.yml")) {
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

    /** {@code MATERIAL#cmd} の材質名。 */
    private static String materialOf(String key) {
        return key.contains("#") ? key.substring(0, key.indexOf('#')) : key;
    }

    private static boolean hasDurabilityStat(ConfigurationSection entry) {
        return entry.contains("fixed.durability")
                || entry.contains("per-quality.durability")
                || entry.contains("random.durability")
                || entry.contains("durability");
    }

    @Test
    @DisplayName("材質キーは全部バニラの Material に解決できる(解決できない名前は検査から静かに消える)")
    void everyUnresolvableMaterialIsAccountedFor() {
        Set<String> unresolved = new TreeSet<>();
        for (String key : items().getKeys(false)) {
            if (Material.getMaterial(materialOf(key)) == null) {
                unresolved.add(materialOf(key));
            }
        }
        assertEquals(new TreeSet<>(KNOWN_NON_VANILLA_MATERIALS), unresolved,
                "解決できない材質キーがある。耐久を持つ材質なら durability ステが要るので、"
                        + "確かめてから KNOWN_NON_VANILLA_MATERIALS へ足すこと");
    }

    @Test
    @DisplayName("耐久を持てる材質のエントリは全部 durability ステを持つ(無いと unbreakable になる)")
    void everyDamageableEntryCarriesADurabilityStat() {
        ConfigurationSection items = items();
        int checked = 0;
        List<String> missing = new ArrayList<>();
        for (String key : items.getKeys(false)) {
            Material material = Material.getMaterial(materialOf(key));
            ConfigurationSection entry = items.getConfigurationSection(key);
            if (material == null || entry == null || material.getMaxDurability() <= 0) {
                continue;
            }
            checked++;
            if (!hasDurabilityStat(entry)) {
                missing.add(key);
            }
        }
        assertTrue(checked > 300,
                "検査対象が " + checked + " 件しかない。材質の解決かレジストリが壊れている");
        assertEquals(List.of(), missing,
                "durability の無い装備は ItemAssembler が setUnbreakable(true) にする。"
                        + "耐久バーも出ないので実機では『減らない』としか見えない");
    }

    /**
     * 2026-08-22 に見つかった2件そのもの。上の全件検査が「対象0件」に化けても
     * この2件だけは必ず落ちるようにしておく（検査の空振りに対する保険）。
     */
    @Test
    @DisplayName("盾と杖は durability を持つ(2026-08-22 に実際に壊れないままだった2件)")
    void theTwoItemsFoundIn2026August22StayDamageable() {
        ConfigurationSection items = items();
        List<String> regressed = new ArrayList<>();
        for (String key : List.of("SHIELD", "NETHERITE_SWORD#400007", "WOODEN_SWORD#400008")) {
            ConfigurationSection entry = items.getConfigurationSection(key);
            assertNotNull(entry, key + " のエントリが消えている(キーの綴りが変わった?)");
            if (!hasDurabilityStat(entry)) {
                regressed.add(key);
            }
        }
        assertFalse(regressed.contains("SHIELD"), "盾がまた壊れない品に戻っている");
        assertEquals(List.of(), regressed);
    }
}
