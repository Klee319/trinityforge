package com.trinityforge.config.domains;

import com.trinityforge.stats.DropTableConfig;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MiningGimmickConfig} のデフォルト/明示上書き/無効値ガードを検証する。他のconfigローダーテストと
 * 同じ、リフレクションで作る偽{@link Plugin}パターン。
 */
class MiningGimmickConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("MiningGimmickConfigTest");
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

    private static MiningGimmickConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, MiningGimmickConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        MiningGimmickConfig config = new MiningGimmickConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void defaultsWhenSectionsAbsent(@TempDir File tempDir) throws IOException {
        MiningGimmickConfig config = loaded(tempDir, "vein-mining:\n  max-extra-blocks: 32\n");
        assertTrue(config.oreBlocks().isEmpty());
        assertEquals(32, config.veinMiningMaxExtraBlocks());
        assertEquals(1, config.hasteAmplifier());
        assertEquals(200, config.hasteDurationTicks());
        assertEquals(600, config.hasteCooldownTicks());
        assertTrue(config.dropTables().isEmpty());
        assertEquals("MINING", config.fortuneSkillId());
        assertEquals(0.006, config.fortunePerLevel(), 0.0);
        assertTrue(config.fortuneBlocks().isEmpty());
    }

    // --- 2026-08-24 「一括破壊の連鎖分でも追加ドロップを抽選する」 ---

    @Test
    void chainDropRollsMaxDefaultsAndKeepsZeroAsAnOptOut(@TempDir File tempDir) throws IOException {
        // キーが1つも無い古い配備ymlでも Java 既定で抽選が走ること(=yml を配備しないと直らない
        // 種類の修正にしない)。
        assertEquals(MiningGimmickConfig.DEFAULT_CHAIN_DROP_ROLLS_MAX,
                loaded(tempDir, "vein-mining:\n  max-extra-blocks: 32\n").veinMiningChainDropRollsMax());

        assertEquals(3, loaded(tempDir, """
                vein-mining:
                  max-extra-blocks: 32
                  chain-drop-rolls-max: 3
                """).veinMiningChainDropRollsMax(), "明示値がそのまま効くこと");

        // 他の数値キーと違い 0 は「この経路の抽選を行わない」という意味を持つので、
        // 既定へ戻してはいけない(戻すと無効化できなくなる)。
        assertEquals(0, loaded(tempDir, """
                vein-mining:
                  max-extra-blocks: 32
                  chain-drop-rolls-max: 0
                """).veinMiningChainDropRollsMax(), "0は「抽選しない」の意味なので保つこと");
    }

    @Test
    void shippedYamlCarriesTheChainDropRollCap(@TempDir File tempDir) throws IOException {
        // ハードコードした既定値ではなく出荷ymlの実バイトを読む(既定値を検証するテストは
        // 出荷値のドリフトを捕まえられない)。
        File source = new File("src/main/resources/" + MiningGimmickConfig.PATH);
        File dest = new File(tempDir, MiningGimmickConfig.PATH);
        Files.createDirectories(dest.getParentFile().toPath());
        Files.copy(source.toPath(), dest.toPath());
        MiningGimmickConfig config = new MiningGimmickConfig();
        config.load(fakePlugin(tempDir));

        assertEquals(MiningGimmickConfig.DEFAULT_CHAIN_DROP_ROLLS_MAX,
                config.veinMiningChainDropRollsMax(),
                "出荷値の chain-drop-rolls-max(0だと連鎖分の追加ドロップが元の取りこぼしへ戻る)");
    }

    @Test
    void honorsExplicitOverrides(@TempDir File tempDir) throws IOException {
        MiningGimmickConfig config = loaded(tempDir, """
                vein-mining:
                  ore-blocks:
                    - COAL_ORE
                    - DIAMOND_ORE
                  max-extra-blocks: 10
                haste-active-mining:
                  amplifier: 2
                  duration-ticks: 100
                  cooldown-ticks: 300
                drop-tables:
                  categories:
                    gacha_tier1:
                      display-name: "Tier1"
                      trigger-chance-percent: 3.0
                      entries:
                        - item: tf_gacha_ticket_1
                          weight: 10
                          amount: 1
                fortune:
                  fortune-per-level: 0.1
                  fortune-blocks:
                    - COAL_ORE
                    - IRON_ORE
                """);
        assertEquals(Set.of(Material.COAL_ORE, Material.DIAMOND_ORE), config.oreBlocks());
        assertEquals(10, config.veinMiningMaxExtraBlocks());
        assertEquals(2, config.hasteAmplifier());
        assertEquals(100, config.hasteDurationTicks());
        assertEquals(300, config.hasteCooldownTicks());
        assertEquals(1, config.dropTables().size());
        DropTableConfig.Category tier1 = config.dropTables().get("gacha_tier1");
        assertEquals(3.0, tier1.triggerChancePercent(), 0.0);
        assertEquals("tf_gacha_ticket_1", tier1.entries().get(0).item());
        assertEquals("MINING", config.fortuneSkillId());
        assertEquals(0.1, config.fortunePerLevel(), 0.0);
        assertEquals(Set.of(Material.COAL_ORE, Material.IRON_ORE), config.fortuneBlocks());
    }

    @Test
    void invalidMaterialNamesAreExcluded(@TempDir File tempDir) throws IOException {
        MiningGimmickConfig config = loaded(tempDir, """
                vein-mining:
                  ore-blocks:
                    - COAL_ORE
                    - NOT_A_REAL_MATERIAL
                """);
        assertEquals(Set.of(Material.COAL_ORE), config.oreBlocks());
    }

    @Test
    void nonPositiveTickValuesFallBackToDefault(@TempDir File tempDir) throws IOException {
        MiningGimmickConfig config = loaded(tempDir, """
                vein-mining:
                  max-extra-blocks: -5
                haste-active-mining:
                  duration-ticks: 0
                  cooldown-ticks: -1
                """);
        assertEquals(32, config.veinMiningMaxExtraBlocks());
        assertEquals(200, config.hasteDurationTicks());
        assertEquals(600, config.hasteCooldownTicks());
    }

    @Test
    void nonFiniteFortunePerLevelFallsBackToDefault(@TempDir File tempDir) throws IOException {
        MiningGimmickConfig config = loaded(tempDir, """
                fortune:
                  fortune-per-level: .nan
                """);
        assertEquals(0.006, config.fortunePerLevel(), 0.0);
    }

    @Test
    void negativeFortunePerLevelIsClampedToZero(@TempDir File tempDir) throws IOException {
        MiningGimmickConfig config = loaded(tempDir, """
                fortune:
                  fortune-per-level: -1.0
                """);
        assertEquals(0.0, config.fortunePerLevel(), 0.0);
    }

    @Test
    void tieredAccessorsFallBackToGlobalScalarWhenTiersUndefined(@TempDir File tempDir) throws IOException {
        MiningGimmickConfig config = loaded(tempDir, """
                vein-mining:
                  max-extra-blocks: 32
                haste-active-mining:
                  amplifier: 1
                  duration-ticks: 200
                  cooldown-ticks: 600
                """);
        // 2026-07-25 gather-rework-active-framework §1 item 2: tiers 未定義なら完全後方互換。
        assertEquals(32, config.veinMiningMaxExtraBlocks(1));
        assertEquals(32, config.veinMiningMaxExtraBlocks(99));
        assertEquals(1, config.hasteAmplifier(1));
        assertEquals(200, config.hasteDurationTicks(1));
        // 2026-07-25 CT設計一本化 §1: hasteCooldownTicks(int) は撤去済み — CTは常に
        // hasteCooldownTicks()(グローバルscalar)の1値のみ、tierに関わらず不変。
        assertEquals(600, config.hasteCooldownTicks());
    }

    @Test
    void tieredAccessorsResolveFloorEntryFromTiersTable(@TempDir File tempDir) throws IOException {
        MiningGimmickConfig config = loaded(tempDir, """
                vein-mining:
                  max-extra-blocks: 32
                  tiers:
                    1: { max-extra-blocks: 16 }
                    3: { max-extra-blocks: 32 }
                    5: { max-extra-blocks: 48 }
                haste-active-mining:
                  cooldown-ticks: 600
                  tiers:
                    1: { amplifier: 3, duration-ticks: 160 }
                    3: { amplifier: 4, duration-ticks: 200 }
                    5: { amplifier: 5, duration-ticks: 240 }
                """);
        assertEquals(16, config.veinMiningMaxExtraBlocks(1));
        assertEquals(16, config.veinMiningMaxExtraBlocks(2));
        assertEquals(32, config.veinMiningMaxExtraBlocks(3));
        assertEquals(48, config.veinMiningMaxExtraBlocks(5));
        assertEquals(48, config.veinMiningMaxExtraBlocks(99));

        assertEquals(3, config.hasteAmplifier(1));
        assertEquals(160, config.hasteDurationTicks(1));
        assertEquals(4, config.hasteAmplifier(3));
        assertEquals(5, config.hasteAmplifier(5));
        assertEquals(240, config.hasteDurationTicks(5));
        // 2026-07-25 CT設計一本化 §1: tierが3/5でもCTは変わらず、グローバルscalarの1値のまま。
        assertEquals(600, config.hasteCooldownTicks());
    }

    @Test
    void hasteCooldownTicksIsTierInvariantEvenWhenTierRowsAreDefined(@TempDir File tempDir) throws IOException {
        // 2026-07-25 CT設計一本化 §1: 段階(tier)はCTに一切影響させない。この設計を明示的に固定するための
        // 純粋関数テスト(Bukkit非依存) — たとえ将来だれかが tiers 行に cooldown-ticks を書き足しても、
        // MiningGimmickConfig にはそれを読むアクセサがもう存在しない(hasteCooldownTicks(int) は撤去済み)。
        MiningGimmickConfig config = loaded(tempDir, """
                haste-active-mining:
                  cooldown-ticks: 450
                  tiers:
                    1: { amplifier: 1, duration-ticks: 100 }
                    5: { amplifier: 5, duration-ticks: 300 }
                """);
        assertEquals(450, config.hasteCooldownTicks());
    }

    @Test
    void malformedTierRowIsSkippedWithoutThrowing(@TempDir File tempDir) throws IOException {
        MiningGimmickConfig config = loaded(tempDir, """
                vein-mining:
                  max-extra-blocks: 32
                  tiers:
                    1: { max-extra-blocks: -5 }
                    notanumber: { max-extra-blocks: 10 }
                """);
        // Both rows invalid -> table stays empty -> falls back to the global scalar.
        assertEquals(32, config.veinMiningMaxExtraBlocks(1));
    }
}
