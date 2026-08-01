package com.trinityforge.config.domains;

import com.trinityforge.stats.DropTableConfig;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FarmingGimmickConfig} のデフォルト/明示上書き/無効値ガードを検証する。他のconfigローダーテストと
 * 同じ、リフレクションで作る偽{@link Plugin}パターン({@link MiningGimmickConfigTest}参照)。
 */
class FarmingGimmickConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("FarmingGimmickConfigTest");
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

    private static FarmingGimmickConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, FarmingGimmickConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        FarmingGimmickConfig config = new FarmingGimmickConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void defaultsWhenSectionsAbsent(@TempDir File tempDir) throws IOException {
        FarmingGimmickConfig config = loaded(tempDir, "area-harvest:\n  radius: 1\n");
        assertEquals(1, config.areaHarvestRadius());
        assertEquals(4.0, config.animalDamageMultiplier(), 0.0);
        assertEquals(8.0, config.beeCalmRadius(), 0.0);
    }

    @Test
    void honorsExplicitOverrides(@TempDir File tempDir) throws IOException {
        FarmingGimmickConfig config = loaded(tempDir, """
                area-harvest:
                  radius: 2
                animal-damage-4x:
                  multiplier: 6.0
                bee-no-aggro:
                  calm-radius: 12.5
                """);
        assertEquals(2, config.areaHarvestRadius());
        assertEquals(6.0, config.animalDamageMultiplier(), 0.0);
        assertEquals(12.5, config.beeCalmRadius(), 0.0);
    }

    @Test
    void nonPositiveValuesFallBackToDefault(@TempDir File tempDir) throws IOException {
        FarmingGimmickConfig config = loaded(tempDir, """
                area-harvest:
                  radius: -1
                animal-damage-4x:
                  multiplier: 0.0
                bee-no-aggro:
                  calm-radius: -5.0
                """);
        assertEquals(1, config.areaHarvestRadius());
        assertEquals(4.0, config.animalDamageMultiplier(), 0.0);
        assertEquals(8.0, config.beeCalmRadius(), 0.0);
    }

    @Test
    void nonFiniteMultiplierFallsBackToDefault(@TempDir File tempDir) throws IOException {
        FarmingGimmickConfig config = loaded(tempDir, """
                animal-damage-4x:
                  multiplier: .NaN
                """);
        assertEquals(4.0, config.animalDamageMultiplier(), 0.0);
    }

    @Test
    void areaHarvestTierFallsBackToGlobalScalarWhenTiersUndefined(@TempDir File tempDir) throws IOException {
        // 2026-07-25 gather-rework-active-framework §1 item 2: tiers 未定義なら完全後方互換。
        FarmingGimmickConfig config = loaded(tempDir, "area-harvest:\n  radius: 1\n");
        assertEquals(1, config.areaHarvestRadius(1));
        assertEquals(1, config.areaHarvestRadius(99));
    }

    // --- 2026-08-01: drop-tables(採取トリガー型の追加ドロップ、§4)を農業にも通した ---

    @Test
    void dropTablesAreEmptyWhenSectionAbsent(@TempDir File tempDir) throws IOException {
        // 農業に drop-tables を足す前と完全後方互換であること(未記載=追加ドロップ無し)。
        FarmingGimmickConfig config = loaded(tempDir, "area-harvest:\n  radius: 1\n");
        assertTrue(config.dropTables().isEmpty());
    }

    @Test
    void dropTableCategoriesAreParsedLikeTheOtherThreeProfessions(@TempDir File tempDir) throws IOException {
        FarmingGimmickConfig config = loaded(tempDir, """
                drop-tables:
                  categories:
                    gacha_tier1:
                      display-name: ガチャ券(初級)
                      trigger-chance-percent: 1.5
                      entries:
                        - item: tf_gacha_ticket_1
                          weight: 1
                          amount: 1
                """);
        Map<String, DropTableConfig.Category> tables = config.dropTables();
        assertEquals(1, tables.size());
        DropTableConfig.Category category = tables.get("gacha_tier1");
        assertNotNull(category, "カテゴリidがキーとして引けること: " + tables.keySet());
        assertEquals("gacha_tier1", category.id());
        assertEquals(1.5, category.triggerChancePercent(), 1e-9);
        assertEquals(1, category.entries().size());
        assertEquals("tf_gacha_ticket_1", category.entries().get(0).item());
    }

    /**
     * 出荷 yml が実際に drop-tables を持っていることの回帰ガード。
     * エディタで農業タブを保存した際にセクションごと落ちると、追加ドロップは
     * 例外もログも出さずに<b>ただ何も起きなくなる</b>(カテゴリ0件は正当な設定なので警告も出ない)。
     */
    @Test
    void shippedFarmingGimmickShipsAtLeastOneDropTableCategory(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, FarmingGimmickConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        try (var in = FarmingGimmickConfigTest.class.getClassLoader()
                .getResourceAsStream(FarmingGimmickConfig.PATH.replace('\\', '/'))) {
            assertNotNull(in, "出荷リソースが見つからない: " + FarmingGimmickConfig.PATH);
            Files.write(file.toPath(), in.readAllBytes());
        }
        FarmingGimmickConfig config = new FarmingGimmickConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "出荷 farming-gimmick.yml がパースできない");
        assertFalse(config.dropTables().isEmpty(),
                "出荷 farming-gimmick.yml から drop-tables.categories が消えている。"
                        + "農業は4職で唯一この機構が丸ごと無かった経路(2026-08-01 に新設)なので、"
                        + "0件に戻っているなら意図的な差し戻しかエディタ保存での欠落を疑うこと。");
        config.dropTables().values().forEach(category ->
                assertFalse(category.entries().isEmpty(),
                        "カテゴリ '" + category.id() + "' が entries 0件。"
                                + "DropTablePolicy は開いたエントリが無ければ何も引かないので、"
                                + "発動率だけ書いても無言で何も落ちない。"));
    }

    @Test
    void areaHarvestTierResolvesFloorEntryFromTiersTable(@TempDir File tempDir) throws IOException {
        FarmingGimmickConfig config = loaded(tempDir, """
                area-harvest:
                  radius: 1
                  tiers:
                    1: { radius: 1 }
                    3: { radius: 2 }
                """);
        assertEquals(1, config.areaHarvestRadius(1));
        assertEquals(1, config.areaHarvestRadius(2));
        assertEquals(2, config.areaHarvestRadius(3));
        assertEquals(2, config.areaHarvestRadius(99));
    }
}
