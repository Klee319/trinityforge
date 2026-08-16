package com.trinityforge.config.domains;

import com.trinityforge.command.StatsCategory;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatVocabulary;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-16 の移設の拘束テスト: マナ基礎3キー({@code mana-max-base} / {@code mana-regen-base} /
 * {@code mana-regen-interval-ticks})は ArsPaper の {@code config.yml} の {@code mana.default-max} /
 * {@code mana.default-regen-rate} / {@code mana.regen-interval-ticks} へ移った。
 *
 * <p>この移設の危険は「無言移行」にある: {@link BaseStatsConfig#load} は既存ファイルを上書きしない
 * ({@code saveResource(PATH, false)})ので、稼働中サーバの
 * {@code plugins/TrinityForge/combat/base-stats.yml} には旧キーの行が残り続ける。TF が黙って無視すると
 * <b>「base-stats.yml に書いた値が効かない」のに何のログも出ない</b>状態になり、原因に辿り着けない。
 * したがってロード時に、旧キー1行につき移設先を名指しした WARNING が1件出ることを固定する。
 *
 * <p>あわせて「移設が本当に済んでいる」側(語彙 / StatsCategory / 出荷 yml から消えていること)も見る。
 * 出荷 yml と語彙は片方だけ消すと別のドリフトテストが落ちる関係にあるため、ここでは
 * 移設の成立条件をひとまとまりで固定する。
 */
class ManaBaseKeysMigrationTest {

    /** 旧キー(kebab) → ArsPaper config.yml の移設先キー。 */
    private static final List<String[]> MIGRATED = List.of(
            new String[] {"mana-max-base", "mana.default-max"},
            new String[] {"mana-regen-base", "mana.default-regen-rate"},
            new String[] {"mana-regen-interval-ticks", "mana.regen-interval-ticks"});

    // --- fixtures ------------------------------------------------------------------------------

    /** WARNING 以上のログ本文を溜めるだけのハンドラ。 */
    private static final class RecordingHandler extends Handler {
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(String.valueOf(record.getMessage()));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        List<String> containing(String needle) {
            return warnings.stream().filter(w -> w.contains(needle)).toList();
        }
    }

    private static Plugin fakePlugin(File dataFolder, Logger logger) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> logger;
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

    private static RecordingHandler loadCapturingWarnings(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, BaseStatsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml, StandardCharsets.UTF_8);

        RecordingHandler sink = new RecordingHandler();
        Logger logger = Logger.getLogger("ManaBaseKeysMigrationTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(sink);

        assertTrue(new BaseStatsConfig().load(fakePlugin(tempDir, logger)), "load() が失敗した");
        return sink;
    }

    private static ConfigurationSection shippedBaseStats() throws Exception {
        try (InputStream in = ManaBaseKeysMigrationTest.class.getClassLoader()
                .getResourceAsStream(BaseStatsConfig.PATH)) {
            assertNotNull(in, "出荷リソース " + BaseStatsConfig.PATH + " が見つからない");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            ConfigurationSection sec = yaml.getConfigurationSection("base-stats");
            assertNotNull(sec, BaseStatsConfig.PATH + " に base-stats: セクションが無い");
            return sec;
        }
    }

    // --- 1) 無言移行の防止(契約4) ------------------------------------------------------------

    @Test
    @DisplayName("旧3キーが残っている base-stats.yml をロードすると、キーごとに移設先つきの警告が1件ずつ出る")
    void legacyKeysStillPresentWarnOncePerKeyWithTheirNewHome(@TempDir File tempDir) throws IOException {
        RecordingHandler sink = loadCapturingWarnings(tempDir, """
                base-stats:
                  mana-max-base: 100
                  mana-regen-base: 5
                  mana-regen-interval-ticks: 20
                """);

        for (String[] pair : MIGRATED) {
            List<String> hits = sink.containing("'" + pair[0] + "'");
            assertEquals(1, hits.size(),
                    pair[0] + " の移設警告が1件ではない: " + hits);
            assertTrue(hits.get(0).contains(pair[1]),
                    pair[0] + " の警告が移設先 " + pair[1] + " を示していない: " + hits.get(0));
            // 移設キーは StatVocabulary から外れているので、対策しないと汎用の
            // 「綴り間違い、またはアイテム固有ステの可能性」警告が二重に出て移設先へ辿り着けなくなる。
            assertFalse(hits.get(0).contains("綴り間違い"),
                    pair[0] + " に綴り間違い扱いの警告が出ている: " + hits.get(0));
        }
        assertEquals(List.of(), sink.containing("綴り間違い"),
                "移設済みキーが汎用の未知キー警告にも乗っている(二重警告)");
    }

    @Test
    @DisplayName("旧キーを0に書き換えて無効化したつもりのファイルでも移設警告は出る")
    void legacyKeysWrittenAsZeroStillWarn(@TempDir File tempDir) throws IOException {
        // 0 値は stats() から落ちる(=「未記載」と同じ)ので、this.stats を見る実装だと検出できない。
        // 「0 にして無効化した」人にも移設は伝える必要があるため、生のキー集合で判定する。
        RecordingHandler sink = loadCapturingWarnings(tempDir, """
                base-stats:
                  mana-max-base: 0
                """);

        assertEquals(1, sink.containing("'mana-max-base'").size(),
                "0 で書かれた旧キーの移設警告が出ていない: " + sink.warnings);
    }

    @Test
    @DisplayName("旧キーが無いファイルでは移設警告は1件も出ない")
    void cleanFileEmitsNoMigrationWarning(@TempDir File tempDir) throws IOException {
        RecordingHandler sink = loadCapturingWarnings(tempDir, """
                base-stats:
                  crit-chance: 0.05
                  mana-idle-seconds: 5
                """);

        for (String[] pair : MIGRATED) {
            assertEquals(List.of(), sink.containing("'" + pair[0] + "'"),
                    pair[0] + " が無いのに移設警告が出ている");
            assertEquals(List.of(), sink.containing(pair[1]),
                    pair[1] + " への移設警告が誤って出ている");
        }
    }

    // --- 2) 移設が本当に済んでいること --------------------------------------------------------

    @Test
    @DisplayName("旧3キーは StatVocabulary と StatsCategory の双方から消えている")
    void migratedKeysAreGoneFromVocabularyAndCategories() {
        // 空振り防止のアンカー: 移設対象外のマナキーは残っていること(3キーだけを外したことの確認)。
        assertTrue(StatVocabulary.isKnown("mana-idle-seconds"),
                "移設対象外の mana-idle-seconds まで巻き込み削除している");
        assertTrue(StatVocabulary.isKnown("mana-bonus"), "mana-bonus まで巻き込み削除している");

        for (String[] pair : MIGRATED) {
            String canonical = StatKeys.canonical(pair[0]);
            assertFalse(StatVocabulary.isKnown(canonical),
                    pair[0] + " が StatVocabulary に残っている(移設が未完了)");
            for (StatsCategory category : EnumSet.complementOf(
                    EnumSet.of(StatsCategory.ALL, StatsCategory.OTHER))) {
                assertFalse(category.includes(canonical),
                        pair[0] + " が StatsCategory." + category + " に残っている");
            }
        }
    }

    @Test
    @DisplayName("出荷 combat/base-stats.yml に旧3キーの行が残っていない(移設対象外の5キーは残る)")
    void shippedBaseStatsNoLongerDeclaresTheMigratedKeys() throws Exception {
        ConfigurationSection sec = shippedBaseStats();
        for (String[] pair : MIGRATED) {
            assertFalse(sec.contains(pair[0]),
                    pair[0] + " が出荷 " + BaseStatsConfig.PATH + " に残っている(移設が未完了)");
        }
        for (String remaining : List.of("mana-onhit-percent", "mana-onattack-percent",
                "mana-idle-seconds", "mana-idle-bonus-percent", "mana-idle-bonus-flat")) {
            assertTrue(sec.contains(remaining),
                    remaining + " まで巻き込み削除している(移設対象は基礎3キーだけ)");
        }
    }
}
