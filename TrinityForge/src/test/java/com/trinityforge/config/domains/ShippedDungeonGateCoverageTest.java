package com.trinityforge.config.domains;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code dungeon/gates.yml} が EliteMobs 同梱ダンジョンを1件残らずカバーしていることを固定する
 * (2026-08-01, 追加コンテンツ詳細プラン §1「柱1 — ダンジョンの鍵」)。
 *
 * <p><b>なぜ必要か</b>: {@link com.trinityforge.mobs.DungeonGateService#checkRequiredEntry} は
 * 「ゲートが1本も無い」ときだけ入口ごと無効化し、<b>1本でも定義された瞬間に fail-close が復活する</b>。
 * つまりこのファイルにダンジョンを1件書き漏らすと、そのダンジョンは
 * {@code trinityforge.admin} を持たない一般プレイヤーにとって<b>入場不能</b>になる。
 * しかも症状は「入ろうとすると『入場ゲートが設定されていない』と言われる」だけで、
 * ログにも警告が出ないため、実際に誰かがそのダンジョンへ行こうとするまで気づけない。
 * 出荷時のゲートは長らく0本(=入口ごと無効)だったので、この経路は一度も実走していない。
 *
 * <p>網羅の基準は EliteMobs 同梱ダンジョン台帳
 * {@code tools/config-editor/public/data/elitemobs-dungeons.json}(生成物・手編集禁止)。
 * EliteMobs 側へダンジョンを追加DLしたときは、台帳を再生成してからここにもゲートを1本足すこと。
 */
class ShippedDungeonGateCoverageTest {

    private static final String GATES = "src/main/resources/dungeon/gates.yml";
    /** テストの作業ディレクトリは TrinityForge モジュール直下なので、台帳はリポジトリルート経由で引く。 */
    private static final String LEDGER = "../tools/config-editor/public/data/elitemobs-dungeons.json";

    /**
     * 設計書 §1-1 の鍵配分表(ダンジョン→鍵アイテムID)。
     *
     * <p>ここに列挙したIDは <b>ArsPaper {@code materials.yml} 側へ別レーンが追加する前提</b>で、
     * 追加されるまでは {@code CrossPluginItemResolver} が解決できず
     * {@code DungeonGateService} の fail-open でその鍵ゲートだけが無効化される(素通り＋警告1回)。
     * 永久ロックにはならないので、鍵アイテムの実在はこのテストでは検査しない
     * (実在検査は鍵アイテムを足すレーン側の担当)。ここで固定するのは
     * 「どのダンジョンにどのIDの鍵を要求すると決めたか」という配分そのもの。
     */
    private static final Map<String, String> EXPECTED_KEYS = new LinkedHashMap<>();

    static {
        EXPECTED_KEYS.put("em_id_the_mines", "key_mines");
        EXPECTED_KEYS.put("em_id_the_deep_mines", "key_deep_mines");
        EXPECTED_KEYS.put("em_id_the_quarry", "key_quarry");
        EXPECTED_KEYS.put("em_id_the_cave", "key_cave");
        EXPECTED_KEYS.put("em_id_enchantment_challenge_10", "key_enchant_trial");
        EXPECTED_KEYS.put("em_id_the_bridge", "key_bridge");
        EXPECTED_KEYS.put("em_id_the_city", "key_city");
        EXPECTED_KEYS.put("em_steamworks_lair", "key_steamworks");
        EXPECTED_KEYS.put("em_id_the_climb", "key_climb");
        EXPECTED_KEYS.put("em_id_the_palace", "key_palace");
        EXPECTED_KEYS.put("em_sewer_maze", "key_sewer_maze");
        EXPECTED_KEYS.put("em_knight_castle", "key_knight_castle");
        EXPECTED_KEYS.put("em_the_dark_cathedral", "key_dark_cathedral");
        EXPECTED_KEYS.put("em_id_the_nether_bell", "key_nether_bell");
        EXPECTED_KEYS.put("em_id_the_nether_wastes", "key_nether_wastes");
        EXPECTED_KEYS.put("em_fireworks", "key_fireworks");
        EXPECTED_KEYS.put("em_hallosseum", "key_hallosseum");
        EXPECTED_KEYS.put("em_north_pole", "key_north_pole");
        EXPECTED_KEYS.put("em_id_binder_of_worlds", "key_binder");
    }

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("ゲートは1本以上ある(0本だと EliteMobs 入場ゲートが機能ごと無効になる)")
    void shippedGatesAreNotEmpty() {
        assertFalse(gates().getKeys(false).isEmpty(),
                "gates.yml が空。0本だと checkRequiredEntry が入口ごと無効化され、"
                        + "鍵も戦闘レベルも一切効かなくなる");
    }

    @Test
    @DisplayName("EliteMobs 同梱ダンジョンは1件残らずゲートを持つ(書き漏らし＝一般プレイヤー入場不能)")
    void everyShippedEliteMobsDungeonHasAGate() {
        Map<String, String> ledger = ledgerWorldToPackage();
        assertFalse(ledger.isEmpty(), "台帳を1件も読めていない(パスか構造が変わった)");
        ConfigurationSection gates = gates();
        List<String> missing = new ArrayList<>();
        for (String world : ledger.keySet()) {
            if (!gates.isConfigurationSection(world)) {
                missing.add(world);
            }
        }
        assertTrue(missing.isEmpty(),
                "gates.yml に無い EliteMobs ダンジョン: " + missing
                        + " — 1本でもゲートがあると fail-close が働くので、"
                        + "ここに無いダンジョンは一般プレイヤーが入場できない");
    }

    @Test
    @DisplayName("em_ で始まるゲートは全部が台帳に実在する(綴り間違いのゲートは誰にも当たらない)")
    void everyEliteMobsGateExistsInTheLedger() {
        Map<String, String> ledger = ledgerWorldToPackage();
        List<String> unknown = new ArrayList<>();
        for (String gateId : gates().getKeys(false)) {
            if (gateId.startsWith("em_") && !ledger.containsKey(gateId)) {
                unknown.add(gateId);
            }
        }
        assertTrue(unknown.isEmpty(),
                "台帳に無い em_ ゲート: " + unknown
                        + " — 綴り間違いなら誰にも当たらず、その実在ダンジョン側は"
                        + "ゲート無しとして入場拒否される");
    }

    @Test
    @DisplayName("content-package は台帳のパッケージ名と一致し、.yml 付きの別名も併記されている")
    void contentPackageMatchesLedgerAndCarriesYamlAlias() {
        Map<String, String> ledger = ledgerWorldToPackage();
        ConfigurationSection gates = gates();
        for (Map.Entry<String, String> entry : ledger.entrySet()) {
            ConfigurationSection gate = gates.getConfigurationSection(entry.getKey());
            assertNotNull(gate, entry.getKey() + " のゲートが無い");
            assertEquals(entry.getValue(), gate.getString("content-package"),
                    entry.getKey() + " の content-package が台帳のパッケージ名と違う"
                            + "(EliteMobs 索引に当たらず入場が無言で失敗する)");
            List<String> aliases = gate.getStringList("aliases");
            assertTrue(aliases.contains(entry.getValue() + ".yml"),
                    entry.getKey() + " に .yml 付きの別名が無い。EliteMobs 側の索引キーは"
                            + "常に .yml 付きファイル名で、フォークがどちらの綴りで"
                            + "checkRequiredEntry を呼ぶかは確認できていないため両方登録しておく");
        }
    }

    @Test
    @DisplayName("key-item に custom: 接頭辞を書かない(所持判定が永久に0個になり鍵を持っていても入れない)")
    void keyItemsNeverUseTheCustomPrefix() {
        ConfigurationSection gates = gates();
        List<String> offenders = new ArrayList<>();
        for (String gateId : gates.getKeys(false)) {
            ConfigurationSection gate = gates.getConfigurationSection(gateId);
            if (gate == null) {
                continue;
            }
            for (String field : List.of("key-item", "key-material")) {
                String raw = gate.getString(field);
                if (raw != null && raw.trim().toLowerCase(Locale.ROOT).startsWith("custom:")) {
                    offenders.add(gateId + "." + field + "=" + raw);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "custom: 付きの鍵ID: " + offenders
                        + " — GateKeyMatcher#matches は所持品PDCの素のIDと完全一致で比べるだけで"
                        + "custom: を剥がさない。一方 CrossPluginItemResolver#exists は解決できてしまうので"
                        + "鍵ゲートは有効のまま所持数が常に0になり、鍵を持っていても永久に入れなくなる");
    }

    @Test
    @DisplayName("設計書 §1-1 の鍵配分どおりに鍵が置かれている(19種・重複なし)")
    void designatedDungeonsCarryTheirDesignatedKey() {
        ConfigurationSection gates = gates();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, String> expected : EXPECTED_KEYS.entrySet()) {
            ConfigurationSection gate = gates.getConfigurationSection(expected.getKey());
            assertNotNull(gate, expected.getKey() + " のゲートが無い");
            assertEquals(expected.getValue(), gate.getString("key-item"),
                    expected.getKey() + " の鍵が配分表と違う");
            assertTrue(gate.getInt("key-amount", 1) >= 1,
                    expected.getKey() + " の key-amount が不正");
            assertTrue(seen.add(expected.getValue()),
                    "鍵ID " + expected.getValue() + " が2つのダンジョンに割り当てられている");
        }
        assertEquals(EXPECTED_KEYS.size(), seen.size(), "鍵の総数が配分表と違う");
    }

    @Test
    @DisplayName("鍵を持つゲートは配分表の19件だけ(勝手に増えると入場経路が閉じる)")
    void onlyDesignatedDungeonsRequireAKey() {
        ConfigurationSection gates = gates();
        List<String> keyed = new ArrayList<>();
        for (String gateId : gates.getKeys(false)) {
            ConfigurationSection gate = gates.getConfigurationSection(gateId);
            if (gate == null) {
                continue;
            }
            String keyItem = gate.getString("key-item", gate.getString("key-material"));
            if (keyItem != null && !keyItem.isBlank()) {
                keyed.add(gateId);
            }
        }
        assertEquals(new ArrayList<>(EXPECTED_KEYS.keySet()).stream().sorted().toList(),
                keyed.stream().sorted().toList(),
                "鍵付きゲートの集合が配分表と違う");
    }

    @Test
    @DisplayName("戦闘レベル制限は世界を繋ぐ者の聖所(40)だけ — 他は level-sync に任せて0")
    void onlyTheBinderSanctumUsesALevelGate() {
        ConfigurationSection gates = gates();
        Map<String, Integer> levelGated = new TreeMap<>();
        for (String gateId : gates.getKeys(false)) {
            ConfigurationSection gate = gates.getConfigurationSection(gateId);
            if (gate == null) {
                continue;
            }
            int level = gate.getInt("required-combat-level", 0);
            if (level > 0) {
                levelGated.put(gateId, level);
            }
        }
        assertEquals(Map.of("em_id_binder_of_worlds", 40), levelGated,
                "EliteMobs 側に最低レベル拒否が無く contentLevel: -1 の level-sync が"
                        + "入場後の強さを合わせるため、レベルで弾く必要があるのは"
                        + "level-sync を持たない束縛者(contentLevel: 50)だけ");
    }

    // ------------------------------------------------------------------

    private static ConfigurationSection gates() {
        File file = new File(GATES);
        assertTrue(file.isFile(), GATES + " が無い(作業ディレクトリは TrinityForge モジュール直下のはず)");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection gates = yaml.getConfigurationSection("gates");
        assertNotNull(gates, "gates.yml の gates: セクションを読めない(YAML構文が壊れている可能性)");
        return gates;
    }

    /** 台帳(生成物)から world -> package を読む。挿入順を保つので差分メッセージが読みやすい。 */
    private static Map<String, String> ledgerWorldToPackage() {
        File file = new File(LEDGER);
        assertTrue(file.isFile(), LEDGER + " が無い(台帳の場所が変わったならこのテストも直すこと)");
        String json;
        try {
            json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AssertionError("台帳を読めない: " + LEDGER, ex);
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray dungeons = root.getAsJsonArray("dungeons");
        assertNotNull(dungeons, "台帳に dungeons 配列が無い");
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonElement element : dungeons) {
            JsonObject dungeon = element.getAsJsonObject();
            String world = dungeon.get("world").getAsString();
            JsonElement pkg = dungeon.get("package");
            if (pkg == null || pkg.isJsonNull()) {
                continue;
            }
            out.put(world, pkg.getAsString());
        }
        return out;
    }
}
