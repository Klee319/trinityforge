package com.trinityforge.config.domains;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GatheringEfficiencyConfig}: {@code stats/gathering-efficiency.yml} schema — the
 * {@code max-enchant-level} ceiling consumed by {@code GatheringEfficiencyMath#resolveLevel}.
 *
 * <p>2026-07-26 ユーザー決定(上限撤廃)固定テスト: 既定値が0(=無制限)へ変わったこと、0/負値が
 * 「無制限」を意味すること、正の値は従来どおり上限として機能すること(後方互換)を検証する。
 */
class GatheringEfficiencyConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("GatheringEfficiencyConfigTest");
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

    private static void write(File file, String content) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }

    @Test
    void schemaDefaultIsUnlimited() {
        // コンストラクタ直後(load()未呼び出し)の既定値も0(無制限)であること。
        GatheringEfficiencyConfig config = new GatheringEfficiencyConfig();
        assertEquals(0, config.maxEnchantLevel());
    }

    @Test
    void bundledYamlResourceDefaultsToUnlimited(@TempDir File tempDir) throws IOException {
        // 出荷ymlの実バイトをコピーして読み込み、既定値が実際に0(無制限)として出荷されていることを確認する。
        File source = new File("src/main/resources/" + GatheringEfficiencyConfig.PATH);
        File dest = new File(tempDir, GatheringEfficiencyConfig.PATH);
        Files.createDirectories(dest.getParentFile().toPath());
        Files.copy(source.toPath(), dest.toPath());

        GatheringEfficiencyConfig config = new GatheringEfficiencyConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "bundled gathering-efficiency.yml must parse without issues");
        assertEquals(0, config.maxEnchantLevel());
    }

    @Test
    void explicitZeroMeansUnlimited(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, GatheringEfficiencyConfig.PATH);
        write(file, "max-enchant-level: 0\n");

        GatheringEfficiencyConfig config = new GatheringEfficiencyConfig();
        assertTrue(config.load(fakePlugin(tempDir)));
        assertEquals(0, config.maxEnchantLevel());
    }

    @Test
    void negativeValueAlsoMeansUnlimited(@TempDir File tempDir) throws IOException {
        // 上限撤廃前は負値がエラー扱いで既定(旧5)へ差し戻されていたが、撤廃後は0と同じ「無制限」を
        // 意味するのでそのまま素通しする(呼び出し側 GatheringEfficiencyMath が <=0 を無制限と解釈する)。
        File file = new File(tempDir, GatheringEfficiencyConfig.PATH);
        write(file, "max-enchant-level: -3\n");

        GatheringEfficiencyConfig config = new GatheringEfficiencyConfig();
        assertTrue(config.load(fakePlugin(tempDir)));
        assertEquals(-3, config.maxEnchantLevel());
    }

    @Test
    void positiveValueStillActsAsATraditionalCeiling(@TempDir File tempDir) throws IOException {
        // 後方互換: 既存ymlに書かれた正の上限値はそのまま尊重される。
        File file = new File(tempDir, GatheringEfficiencyConfig.PATH);
        write(file, "max-enchant-level: 3\n");

        GatheringEfficiencyConfig config = new GatheringEfficiencyConfig();
        assertTrue(config.load(fakePlugin(tempDir)));
        assertEquals(3, config.maxEnchantLevel());
    }
}
