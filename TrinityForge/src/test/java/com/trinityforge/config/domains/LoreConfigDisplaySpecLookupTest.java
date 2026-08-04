package com.trinityforge.config.domains;

import com.trinityforge.stats.StatDisplaySpec;
import com.trinityforge.stats.StatKeys;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoreConfig#displaySpecFor} が<b>綴りの違いを吸収して必ず引ける</b>ことの回帰ガード。
 *
 * <h2>なぜ要るのか(2026-08-04 の実サーバ報告の真因)</h2>
 * {@code stats/lore.yml} の表は <b>yml に著者が書いた綴りそのまま</b>
 * ({@code attack-power} = ハイフン)でキーになっている。一方 TF 内部でステキーを持ち回る形は
 * {@link StatKeys#canonical} のスネークケース({@code attack_power})なので、
 * <b>canonical 化した値で生の {@code displayTable()} を引くと 1 件も一致しない</b>。
 *
 * <p>ArsPaper フォークのスレッド lore がまさにこれを踏んでいた ── 引く側だけを canonical 化しており
 * 表示名の解決が常に失敗、フォールバックが働いて<b>実機ではステータスidが素で並んでいた</b>
 * (「スレッドの表記がステータスidのまま」の報告)。{@code LoreComposer} は表と値の両方を
 * canonical 化しているので無事だったため、TF 本体のテストは一件も落ちていなかった。
 *
 * <p>そこで突き合わせを {@code displaySpecFor} 一箇所に閉じた。このテストは
 * <b>ハイフン綴り・スネーク綴り・大文字混じりのどれでも同じ定義に着地する</b>ことを
 * 出荷 yml の実物に対して確かめる ── {@code displaySpecFor} が単なる
 * {@code table.get(key)} に退化したら即座に落ちる。
 */
class LoreConfigDisplaySpecLookupTest {

    /** 綴り耐性が壊れると実機の表示が壊れる代表キー(全部ハイフン綴りで yml に載っている)。 */
    private static final String[] SAMPLE_KEYS = {
            "attack-power", "bleed-damage", "bleed-chance", "crit-chance", "damage-reduction",
            "armor-strength", "penetration", "stun-chance", "reflect-percent", "ammo-save-chance"};

    @Test
    @DisplayName("スネークケースでもハイフンでも大文字混じりでも同じ表示定義に着地する")
    void lookupIsSpellingInsensitive(@TempDir File dataFolder) throws IOException {
        LoreConfig config = loadReal(dataFolder);

        for (String authored : SAMPLE_KEYS) {
            StatDisplaySpec direct = config.displaySpecFor(authored);
            assertNotNull(direct, authored + " の表示定義が stats/lore.yml に無い(テストの前提が変わった)");

            String canonical = StatKeys.canonical(authored);
            assertSame(direct, config.displaySpecFor(canonical),
                    "canonical 綴り '" + canonical + "' で引けない。TF 内部はこの綴りでステキーを"
                            + "持ち回るので、ここが引けないと呼び出し側のフォールバックが働き"
                            + "実機にステータスidが素で表示される(2026-08-04 のスレッド lore の真因)。");
            assertSame(direct, config.displaySpecFor(authored.toUpperCase(java.util.Locale.ROOT)),
                    "大文字綴りで引けない(canonical 化は小文字化も含む)");
        }
    }

    @Test
    @DisplayName("表に載っている全キーが、その canonical 綴りでも引ける")
    void everyAuthoredKeyResolvesFromItsCanonicalForm(@TempDir File dataFolder) throws IOException {
        LoreConfig config = loadReal(dataFolder);
        Map<String, StatDisplaySpec> table = config.displayTable();
        assertTrue(table.size() > 50, "出荷 lore.yml のステ定義が少なすぎる: " + table.size());

        List<String> unresolvable = new ArrayList<>();
        for (String authored : table.keySet()) {
            if (config.displaySpecFor(StatKeys.canonical(authored)) == null) {
                unresolvable.add(authored);
            }
        }
        assertTrue(unresolvable.isEmpty(),
                "canonical 綴りで引けないキーがある(この形が実機の『ステータスidが素で出る』を生む): "
                        + unresolvable);
    }

    @Test
    @DisplayName("未定義キー・null・空文字は null を返す(呼び出し側が『定義が無い』と判断できる)")
    void unknownKeysReturnNull(@TempDir File dataFolder) throws IOException {
        LoreConfig config = loadReal(dataFolder);

        assertNull(config.displaySpecFor(null));
        assertNull(config.displaySpecFor("   "));
        assertNull(config.displaySpecFor("this-stat-does-not-exist"));
    }

    // --- ヘルパ(LoreConfigDeclarationTest と同じ流儀: 出荷リソースを実ファイルとして置いて load する) ---

    private static LoreConfig loadReal(File dataFolder) throws IOException {
        copyClasspathFile("stats/lore.yml", new File(dataFolder, "stats/lore.yml"));
        // cap-ref の解決先。無いと参照付きの境界が読めない。
        copyClasspathFile("combat/damage.yml", new File(dataFolder, "combat/damage.yml"));
        LoreConfig config = new LoreConfig();
        config.load(fakePlugin(dataFolder,
                Logger.getLogger("LoreConfigDisplaySpecLookupTest-" + System.nanoTime())));
        return config;
    }

    private static void copyClasspathFile(String classpathPath, File dest) throws IOException {
        Files.createDirectories(dest.getParentFile().toPath());
        try (InputStream in = LoreConfigDisplaySpecLookupTest.class.getClassLoader()
                .getResourceAsStream(classpathPath)) {
            assertNotNull(in, "bundled " + classpathPath + " must be on the test classpath");
            Files.copy(in, dest.toPath());
        }
    }

    private static Plugin fakePlugin(File dataFolder, Logger logger) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> logger;
            case "saveResource" -> throw new AssertionError("saveResource() must not run headlessly");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }
}
