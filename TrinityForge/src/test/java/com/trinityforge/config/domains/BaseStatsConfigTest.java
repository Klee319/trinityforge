package com.trinityforge.config.domains;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BaseStatsConfig} load coverage: keys are canonicalised, PERCENT-family values are coerced to
 * fractions exactly like item stats, 0 / non-numeric / absent entries drop out (= vanilla).
 * Uses the same reflective fake {@link Plugin} pattern as {@code RoleBuffsConfigTest}.
 */
class BaseStatsConfigTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("BaseStatsConfigTest");
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

    private static BaseStatsConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, BaseStatsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        BaseStatsConfig config = new BaseStatsConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void percentFamilyWholeNumberIsCoercedToFractionLikeItemStats(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  crit-chance: 5
                """);
        // crit-chance は RATE_KEYS 収録 → 5 は 5% とみなし 0.05 へ正規化(item-stats と同一意味)。
        assertEquals(0.05, config.stats().get("crit_chance"), 1e-9);
    }

    @Test
    void alreadyFractionalPercentValuePassesThrough(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  crit-chance: 0.05
                """);
        assertEquals(0.05, config.stats().get("crit_chance"), 1e-9);
    }

    @Test
    void flatStatIsNotCoerced(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  phys-flat-defense: 3
                """);
        assertEquals(3.0, config.stats().get("phys_flat_defense"), 1e-9);
    }

    @Test
    void zeroValuesAreDroppedAsVanilla(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  crit-chance: 0
                  phys-flat-defense: 2
                """);
        assertFalse(config.stats().containsKey("crit_chance"), "0 は加算なし = 保持しない");
        assertEquals(2.0, config.stats().get("phys_flat_defense"), 1e-9);
    }

    @Test
    void nonNumericEntryIsSkipped(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  crit-chance: not-a-number
                  phys-flat-defense: 4
                """);
        assertFalse(config.stats().containsKey("crit_chance"));
        assertEquals(4.0, config.stats().get("phys_flat_defense"), 1e-9);
    }

    @Test
    void missingSectionYieldsEmptyMap(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, "base-stats: {}\n");
        assertTrue(config.stats().isEmpty());
        assertEquals(Map.of(), config.stats());
    }

    // 2026-07-25 (config editor T2): ArsPaper mana.default-max 等の移設先。
    // TrinityForgeBridge.manaBaseStat はこの statOrDefault (もしくは stats()直読み)経由でフォークへ届く。

    @Test
    void statOrDefaultReturnsFallbackWhenKeyAbsent(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, "base-stats: {}\n");
        assertEquals(100.0, config.statOrDefault("mana-max-base", 100.0), 1e-9);
    }

    @Test
    void statOrDefaultReturnsFallbackWhenValueIsZero(@TempDir File tempDir) throws IOException {
        // 0 は「加算なし」として保持されない(= 空欄と同じ)ので fallback が返る。「空欄=バニラ」の
        // 意味論が新規マナキーでも維持されていることの回帰ガード。
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  mana-max-base: 0
                """);
        assertEquals(100.0, config.statOrDefault("mana-max-base", 100.0), 1e-9);
    }

    @Test
    void manaMaxBaseIsReadableAfterMigrationFromArsPaperConfig(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  mana-max-base: 150
                  mana-regen-base: 8
                  mana-regen-interval-ticks: 15
                """);
        assertEquals(150.0, config.statOrDefault("mana-max-base", 100.0), 1e-9);
        assertEquals(8.0, config.statOrDefault("mana-regen-base", 5.0), 1e-9);
        assertEquals(15.0, config.statOrDefault("mana-regen-interval-ticks", 20.0), 1e-9);
    }

    @Test
    void manaPercentKeysAreCoercedFromPercentPointsLikeCritChance(@TempDir File tempDir) throws IOException {
        // config.yml の recovery.on-hit-percent: 3 (3%) と同じ書き方をそのまま base-stats.yml に書けること。
        // (2..100の整数のみ percent-point とみなされる。1は下の境界ケーステスト参照。)
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  mana-onhit-percent: 3
                  mana-onattack-percent: 3
                """);
        assertEquals(0.03, config.stats().get("mana_onhit_percent"), 1e-9);
        assertEquals(0.03, config.stats().get("mana_onattack_percent"), 1e-9);
    }

    @Test
    void percentPointOneIsAmbiguousWithAlreadyFractionalOne(@TempDir File tempDir) throws IOException {
        // PercentStatNormalize.coerce は |v|>1 の整数だけを percent-point とみなす。1 は "既に fraction
        // (100%)" と区別できないため coerce されない — base-stats.yml で1%を書きたい場合は 0.01 と直書き
        // する必要がある(2026-07-25 T2実装時に発見。base-stats.yml の mana-idle-bonus-percent は
        // このため 1 ではなく 0.01 と書いている)。この回帰ガードは境界挙動を明文化するためのもの。
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  mana-idle-bonus-percent: 1
                """);
        assertEquals(1.0, config.stats().get("mana_idle_bonus_percent"), 1e-9,
                "percent-point '1' is NOT coerced to 0.01 (documented boundary quirk, not a bug)");
    }

    @Test
    void manaIdleBonusPercentWrittenAsFractionIsReadCorrectly(@TempDir File tempDir) throws IOException {
        // base-stats.yml の実際の書き方(0.01直書き)がそのまま1%として読めることの回帰ガード。
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  mana-idle-bonus-percent: 0.01
                """);
        assertEquals(0.01, config.stats().get("mana_idle_bonus_percent"), 1e-9);
    }

    // ---- T1(2026-07-25): ATTRIBUTE チャネル5キーの絶対値方式 ----
    // written - vanillaDefault へ変換して内部保持する。境界: 未記載/0/バニラ既定値と同値 → 0(=保持しない)、
    // バニラ超 → 正の加算量、バニラ未満 → 負の加算量(クランプしない)。

    @Test
    void attributeChannelAboveVanillaDefaultIsConvertedToPositiveAddend(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  attack-reach: 6
                """);
        // バニラ既定 3.0 → 書いた6は "+3" として内部保持される(下流は従来通り加算量として扱う)。
        assertEquals(3.0, config.stats().get("attack_reach"), 1e-9);
    }

    @Test
    void attributeChannelEqualToVanillaDefaultIsDroppedAsVanilla(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  attack-reach: 3
                """);
        // バニラ既定と同値 = 加算量0 = "未記載"と同じ意味論で保持しない。
        assertFalse(config.stats().containsKey("attack_reach"));
    }

    @Test
    void attributeChannelBelowVanillaDefaultIsConvertedToNegativeAddend(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  attack-reach: 1
                """);
        // バニラ既定 3.0 未満(=1) は負の加算量(-2)としてそのまま表現する(0へクランプしない)。
        assertEquals(-2.0, config.stats().get("attack_reach"), 1e-9);
    }

    @Test
    void attributeChannelZeroIsStillTreatedAsUnwritten(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  attack-reach: 0
                """);
        assertFalse(config.stats().containsKey("attack_reach"), "0 は絶対値方式でも従来通り「未記載=バニラ」");
    }

    @Test
    void maxHealthAbsoluteValueConvertsAgainstVanillaTwenty(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  max-health: 40
                """);
        // バニラ既定 20.0 → 書いた40は "+20" として内部保持される。
        assertEquals(20.0, config.stats().get("max_health"), 1e-9);
    }

    @Test
    void moveSpeedAbsoluteValueConvertsAgainstVanillaPointOne(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  move-speed: 0.2
                """);
        // バニラ既定 0.1 → 書いた0.2は "+0.1" として内部保持される。
        assertEquals(0.1, config.stats().get("move_speed"), 1e-9);
    }

    @Test
    void knockbackResistanceVanillaDefaultIsZeroSoWrittenValueIsAddendAsIs(@TempDir File tempDir)
            throws IOException {
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  knockback-resistance: 0.3
                """);
        // バニラ既定 0.0 なので絶対値=加算量で従来と数値上は変わらない(意味論だけ「絶対値」に統一)。
        assertEquals(0.3, config.stats().get("knockback_resistance"), 1e-9);
    }

    /**
     * 2026-07-26 stat-scope 境界引き直し §2 (C→A 降格): {@code attack-speed}(絶対値・メインハンド専用)
     * は総合ステ(StatVocabulary/base-stats.yml)から完全に外れ、{@code stats/item-stats.yml}(武器個別ステ)
     * にのみ存在する真にアイテム固有のステとなった。base-stats.yml に(意図せず/手動で)
     * {@code attack-speed} を書いても、もはや {@link StatVocabulary.Channel#ATTRIBUTE} ではないため
     * written-vanillaDefault の絶対値変換は適用されず、他の未知キー同様の「普通の加算量としてそのまま
     * 保持される」扱いになる(かつロード時に「既知のステータスではない」警告が出る — プレイヤーへは
     * 一切反映されない、{@code PerkAttributeApplier} はこのキーを読まないため)。
     */
    @Test
    void attackSpeedIsNoLongerAttributeChannelInBaseStats(@TempDir File tempDir) throws IOException {
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  attack-speed: -1
                """);
        // 絶対値変換(written - vanillaDefault)はもう適用されない: -1 が「普通の加算量」としてそのまま入る
        // (かつては ATTRIBUTE チャネルとして -5.0 = -1 - 4.0 へ変換されていた)。
        assertEquals(-1.0, config.stats().get("attack_speed"), 1e-9);
        assertFalse(com.trinityforge.stats.StatVocabulary.isAttribute("attack-speed"));
        assertFalse(com.trinityforge.stats.StatVocabulary.isKnown("attack-speed"));
    }

    @Test
    void nonAttributeChannelKeyIsNotConvertedByVanillaDefault(@TempDir File tempDir) throws IOException {
        // crit-chance は ATTRIBUTE チャネルではないので written-vanillaDefault 変換の対象外
        // (PercentStatNormalize の通常のパーセント正規化のみが適用される)。
        BaseStatsConfig config = loaded(tempDir, """
                base-stats:
                  crit-chance: 0.05
                """);
        assertEquals(0.05, config.stats().get("crit_chance"), 1e-9);
    }

    // ------------------------------------------------------------------------------------------
    // 2026-07-26 stat-scope 境界引き直し §4: StatVocabulary ⇔ base-stats.yml のドリフト防止テスト
    // ------------------------------------------------------------------------------------------

    /**
     * {@link BaseStatsConfig#missingVocabularyKeys} の純粋関数としての振る舞い: 与えたキー集合に無い
     * vocabulary キーだけが返る(0値/未記載どちらでも「キーとして存在する」とみなすfixtureを使う)。
     */
    @Test
    void missingVocabularyKeysReturnsOnlyKeysAbsentFromRawFileKeySet() {
        java.util.Set<String> allButOne = new java.util.HashSet<>(
                com.trinityforge.stats.StatVocabulary.allKeys());
        String removed = allButOne.iterator().next();
        allButOne.remove(removed);

        java.util.Set<String> missing = BaseStatsConfig.missingVocabularyKeys(allButOne);

        assertEquals(java.util.Set.of(removed), missing);
    }

    @Test
    void missingVocabularyKeysIsEmptyWhenEverythingPresent() {
        java.util.Set<String> missing = BaseStatsConfig.missingVocabularyKeys(
                com.trinityforge.stats.StatVocabulary.allKeys());
        assertTrue(missing.isEmpty());
    }

    /**
     * ドリフト防止の本命: 実際に配布される {@code combat/base-stats.yml}(タスク4で欠落21キーを追加済み)
     * が、最終的な {@link StatVocabulary} の全キーをキーとして(値0でも)網羅していることを固定する。
     * このテストが落ちたら、新しい StatVocabulary キーを追加したのに base-stats.yml への追記を
     * 忘れている(editorで初期値を設定できないままになる)ことを意味する — 「静かに壊れるより騒がしく
     * 落ちる」({@code ActiveSkillCooldownKeys.verifyRegistered} と同種の設計)。
     */
    @Test
    void shippedBaseStatsYamlCoversEveryVocabularyKey() throws IOException {
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        try (java.io.InputStream in = BaseStatsConfigTest.class.getClassLoader()
                .getResourceAsStream(BaseStatsConfig.PATH)) {
            assertTrue(in != null, "bundled " + BaseStatsConfig.PATH + " must be on the test classpath");
            String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            yaml.loadFromString(content);
        } catch (org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new AssertionError("shipped base-stats.yml failed to parse", ex);
        }
        org.bukkit.configuration.ConfigurationSection sec = yaml.getConfigurationSection("base-stats");
        assertTrue(sec != null, "shipped base-stats.yml must have a 'base-stats:' section");
        java.util.Set<String> rawKeysCanonical = new java.util.HashSet<>();
        for (String key : sec.getKeys(false)) {
            rawKeysCanonical.add(com.trinityforge.stats.StatKeys.canonical(key));
        }

        java.util.Set<String> missing = BaseStatsConfig.missingVocabularyKeys(rawKeysCanonical);

        assertTrue(missing.isEmpty(),
                "combat/base-stats.yml is missing StatVocabulary key(s) (add them with value 0): " + missing);
    }

    /**
     * 上のテストの裏返し。{@code base-stats.yml} の生キー2つが {@link com.trinityforge.stats.StatKeys#canonical}
     * で同じキーに畳まれていないことを固定する。
     *
     * <p>実際に踏んだ事故: 2026-07-26 に {@code tool-enchant-efficiency} → {@code gathering-efficiency}
     * のエイリアスを入れた際、base-stats.yml に両方の行が残り、**同じステータスが2行**になっていた。
     * 値はどちらも0だったので誰も気付かなかったが、片方だけ editor で編集すると
     * 読み込み順によってもう片方の0に上書きされ、**設定したはずの値が無言で消える**。
     *
     * <p>網羅テスト(上)は「不足」しか見ないのでこれを検出できない。エイリアスを1件足すたびに
     * 起こり得るので、機械的に落とす。
     */
    @Test
    void shippedBaseStatsYamlHasNoKeysThatCollideAfterCanonicalization() throws IOException {
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        try (java.io.InputStream in = BaseStatsConfigTest.class.getClassLoader()
                .getResourceAsStream(BaseStatsConfig.PATH)) {
            assertTrue(in != null, "bundled " + BaseStatsConfig.PATH + " must be on the test classpath");
            String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            yaml.loadFromString(content);
        } catch (org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new AssertionError("shipped base-stats.yml failed to parse", ex);
        }
        org.bukkit.configuration.ConfigurationSection sec = yaml.getConfigurationSection("base-stats");
        assertTrue(sec != null, "shipped base-stats.yml must have a 'base-stats:' section");

        java.util.Map<String, String> canonicalToRaw = new java.util.HashMap<>();
        java.util.List<String> collisions = new java.util.ArrayList<>();
        for (String key : sec.getKeys(false)) {
            String canonical = com.trinityforge.stats.StatKeys.canonical(key);
            String previous = canonicalToRaw.put(canonical, key);
            if (previous != null) {
                collisions.add(previous + " + " + key + " -> " + canonical);
            }
        }

        assertTrue(collisions.isEmpty(),
                "combat/base-stats.yml has key(s) that fold to the same canonical stat "
                        + "(one silently overwrites the other): " + collisions);
    }
}
