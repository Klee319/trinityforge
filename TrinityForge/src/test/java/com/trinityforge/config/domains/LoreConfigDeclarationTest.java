package com.trinityforge.config.domains;

import com.trinityforge.stats.CapRefResolver;
import com.trinityforge.stats.StatAppliesTo;
import com.trinityforge.stats.StatBound;
import com.trinityforge.stats.StatDisplaySpec;
import com.trinityforge.stats.StatLimits;
import com.trinityforge.stats.StatSourceScope;
import com.trinityforge.stats.StatStacking;
import com.trinityforge.stats.StatTrigger;
import com.trinityforge.stats.StatTriggerWhen;
import org.bukkit.configuration.file.YamlConfiguration;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拘束テスト(段階2): {@code stats/lore.yml} の {@code trigger:}/{@code limits:} 宣言が実装から
 * ずれたら赤で落ちる。{@link AllSkillTreesLoadTest} と同じ「実クラスパスの本物のconfigをコピーして
 * 本物のローダーで読む」流儀。
 *
 * <p>3本柱:
 * <ol>
 *   <li>{@code -ref} 解決テスト — {@link StatLimits#declaredBounds()} が返す各境界({@code cap} /
 *       {@code floor} / {@code min-pieces} / {@code max-distance} / {@code max-duration-ticks})について、
 *       宣言した値と {@code <field>-ref} が指す実装値が一致すること(typo / ドリフトの両方を検知)。</li>
 *   <li>語彙テスト — {@code when}/{@code sources}/{@code applies-to}/{@code stacking} が閉じた語彙の
 *       メンバーであること。未知の値は{@link IllegalArgumentException}で、呼び出し元
 *       ({@link LoreConfig#load}) がそれを「skip + warning」に変換する。全120キー相当に対する
 *       「警告ゼロ要求」は {@link #shippedLoreLoadsWithZeroWarnings} が既に持っている。</li>
 *   <li>ラチェット(未宣言許可リスト) — 宣言の無いキーが {@link #UNDECLARED_ALLOW_LIST} と厳密一致
 *       すること。新しいstatキーを追加して宣言を忘れると、このリストに無い名前が実ファイル側に
 *       現れて即失敗する(=宣言必須のラチェット)。このリストは"減る一方"であるべき — 宣言を追加したら
 *       ここから該当キーを削除すること(逆(増やす)は原則しない)。
 * </ol>
 */
class LoreConfigDeclarationTest {

    // ---- 宣言済み10キー(2026-07-27 段階1/2実装、実測4件+実装から断定できた6件)。
    // 新しくキーを宣言したら、ここに足す代わりに UNDECLARED_ALLOW_LIST から取り除くこと。
    private static final Set<String> DECLARED_KEYS = Set.of(
            "dodge-chance", "armor-strength", "distance-damage-bonus",
            "phys-resistance", "magic-resistance", "damage-reduction", "armor-defense-rate",
            "stun-duration-bonus", "max-health", "move-speed");

    /**
     * 宣言(trigger/limits)の無いキーの許可リスト(2026-07-27時点)。このリストに無い名前が
     * 未宣言のまま出現したら {@link #undeclaredKeysMatchAllowList} が失敗する — 新規stat追加時の
     * 宣言忘れを検知するラチェット。既存キーを宣言したら、このSetから取り除いて縮めること
     * (逆に増やすのは、新規stat追加をこの許可リストへ逃がす行為であり原則禁止)。
     */
    private static final Set<String> UNDECLARED_ALLOW_LIST = Set.of(
            "bleed-chance", "bleed-damage", "durability",
            "bow-accuracy", "ammo-save-chance", "arrow-piercing",
            "arrow-velocity", "bow-cooldown-reduction",
            "haste-active-mining-cooldown-reduction",
            "health-regen-bonus", "coating-charges",
            // 段階4(2026-07-27)調査: 空腹減少(FoodLevelChangeEventのドレイン)に相当するtrigger.when
            // 語彙が無いため未宣言。
            "hunger-save-chance",
            // 段階4(2026-07-27)調査: 複数の異なる契機(釣り/アイテム入手時の汎用品質刻印スイープ)から
            // 発動し、単一のtrigger.whenで正確に表現できないため未宣言。
            "loot-luck",
            // 段階4(2026-07-27)調査: PlayerInteractEvent(ガチャ券の右クリック)に相当する「プレイヤーの
            // 任意のインタラクト時」のtrigger.when語彙が無いため未宣言。
            "gacha-rate-bonus",
            "hive-harvest-fortune",
            // 段階4(2026-07-27)調査: EntityBreedEvent(繁殖時)に相当するtrigger.when語彙が無いため
            // 未宣言(3キーとも同じ理由)。
            "breeding-vanilla-exp-bonus", "breeding-extra-child-chance", "bred-animal-growth-bonus",
            "enchant-cost-reduction",
            // 段階4(2026-07-27)調査: 公開APIはあるがフォーク側のどのクラスからも呼ばれておらず、
            // 現状は未消費(死んでいる)ため未宣言。
            "glyph-damage-multiplier-bonus",
            "armor-set-bonus",
            // 段階4(2026-07-27)調査: combat/base-stats.yml専用の「全プレイヤー共通の定数」であり
            // PlayerStatAggregatorのitem+perk合算チャネルを経由しないため、trigger.sourcesが前提とする
            // 「合算元」の概念が当てはまらず未宣言(10キーとも同じ理由、詳細はstats/lore.ymlの
            // mana-max-baseコメント参照)。
            "mana-max-base", "mana-regen-base", "mana-regen-interval-ticks",
            "mana-onhit-percent", "mana-onhit-flat", "mana-onattack-percent",
            "mana-onattack-flat", "mana-idle-seconds", "mana-idle-bonus-percent",
            "mana-idle-bonus-flat");

    // --- fixtures ------------------------------------------------------------------------------

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

    /** cap-ref が参照する combat/damage.yml も含め、本物のリソースを実ファイルとして dataFolder に置く。 */
    private static void copyRealResources(File dataFolder) throws IOException {
        copyClasspathFile("stats/lore.yml", new File(dataFolder, "stats/lore.yml"));
        copyClasspathFile("combat/damage.yml", new File(dataFolder, "combat/damage.yml"));
    }

    private static void copyClasspathFile(String classpathPath, File dest) throws IOException {
        Files.createDirectories(dest.getParentFile().toPath());
        try (InputStream in = LoreConfigDeclarationTest.class.getClassLoader()
                .getResourceAsStream(classpathPath)) {
            assertNotNull(in, "bundled " + classpathPath + " must be on the test classpath");
            Files.copy(in, dest.toPath());
        }
    }

    private static LoreConfig loadReal(File dataFolder) throws IOException {
        copyRealResources(dataFolder);
        LoreConfig config = new LoreConfig();
        config.load(fakePlugin(dataFolder, Logger.getLogger("LoreConfigDeclarationTest-" + System.nanoTime())));
        return config;
    }

    // --- 1) cap-ref 解決テスト -------------------------------------------------------------------

    @Test
    @DisplayName("-ref を持つ全宣言キー×全境界について、宣言値と実装解決値が一致する")
    void declaredBoundsMatchResolvedRefs(@TempDir File dataFolder) throws IOException {
        LoreConfig config = loadReal(dataFolder);
        List<String> mismatches = new ArrayList<>();
        int checked = 0;

        for (Map.Entry<String, StatDisplaySpec> e : config.displayTable().entrySet()) {
            StatLimits limits = e.getValue().limits();
            if (limits == null) {
                continue;
            }
            for (Map.Entry<String, StatBound> boundEntry : limits.declaredBounds().entrySet()) {
                StatBound bound = boundEntry.getValue();
                if (bound.ref() == null) {
                    continue;
                }
                checked++;
                double resolved = CapRefResolver.resolve(bound.ref(), dataFolder);
                if (Math.abs(bound.value() - resolved) > 1.0e-9) {
                    mismatches.add(e.getKey() + "." + boundEntry.getKey() + ": declared="
                            + bound.value() + " but ref '" + bound.ref() + "' resolves to " + resolved);
                }
            }
        }

        assertTrue(checked > 0, "no stat declared a bound with a -ref; the test would be vacuous");
        assertTrue(mismatches.isEmpty(), "bound declaration drifted from implementation:\n  "
                + String.join("\n  ", mismatches));
    }

    @Test
    @DisplayName("dodge-chance の cap-ref は combat/damage.yml の実値(0.9)へ解決される")
    void dodgeChanceCapRefResolvesToRealConfigValue(@TempDir File dataFolder) throws IOException {
        copyRealResources(dataFolder);
        double resolved = CapRefResolver.resolve("combat/damage.yml#defense.max-dodge-chance", dataFolder);
        assertEquals(0.9, resolved, 1.0e-9);
    }

    @Test
    @DisplayName("distance-damage-bonus の java: max-distance-ref は CombatListener の昇格済み定数(64)へ解決される")
    void distanceDamageBonusMaxDistanceRefResolvesToJavaConstant() {
        double resolved = CapRefResolver.resolve(
                "java:com.trinityforge.listeners.CombatListener#MAX_DISTANCE_DAMAGE_BLOCKS", null);
        assertEquals(64.0, resolved, 1.0e-9);
    }

    @Test
    @DisplayName("stun-duration-bonus の java: max-duration-ticks-ref は NativeCombatPerkListener の昇格済み定数(100)へ解決される")
    void stunDurationBonusMaxDurationTicksRefResolvesToJavaConstant() {
        double resolved = CapRefResolver.resolve(
                "java:com.trinityforge.skilltree.runtime.NativeCombatPerkListener#MAX_STUN_DURATION_TICKS",
                null);
        assertEquals(100.0, resolved, 1.0e-9);
    }

    @Test
    @DisplayName("distance-damage-bonus / stun-duration-bonus の宣言済み境界が実ファイルから正しく読める"
            + "(max-distance / max-duration-ticks へ語彙修正済み)")
    void distanceAndStunBoundsUseCorrectVocabulary(@TempDir File dataFolder) throws IOException {
        LoreConfig config = loadReal(dataFolder);

        StatLimits distance = config.displayTable().get("distance-damage-bonus").limits();
        assertNotNull(distance, "distance-damage-bonus must declare limits");
        assertEquals(Set.of("max-distance"), distance.declaredBounds().keySet());
        assertEquals(64.0, distance.maxDistance().value(), 1.0e-9);
        assertEquals("java:com.trinityforge.listeners.CombatListener#MAX_DISTANCE_DAMAGE_BLOCKS",
                distance.maxDistance().ref());

        StatLimits stun = config.displayTable().get("stun-duration-bonus").limits();
        assertNotNull(stun, "stun-duration-bonus must declare limits");
        assertEquals(Set.of("max-duration-ticks"), stun.declaredBounds().keySet());
        assertEquals(100.0, stun.maxDurationTicks().value(), 1.0e-9);
        assertEquals(
                "java:com.trinityforge.skilltree.runtime.NativeCombatPerkListener#MAX_STUN_DURATION_TICKS",
                stun.maxDurationTicks().ref());
    }

    @Test
    @DisplayName("cap-ref: 存在しないyml相対パスは例外で失敗する(typo検知)")
    void capRefMissingFileFails(@TempDir File dataFolder) {
        assertThrows(IllegalArgumentException.class,
                () -> CapRefResolver.resolve("combat/does-not-exist.yml#a.b", dataFolder));
    }

    @Test
    @DisplayName("cap-ref: 存在しないキーパスは例外で失敗する(typo検知)")
    void capRefMissingKeyFails(@TempDir File dataFolder) throws IOException {
        copyRealResources(dataFolder);
        assertThrows(IllegalArgumentException.class,
                () -> CapRefResolver.resolve("combat/damage.yml#defense.no-such-key", dataFolder));
    }

    @Test
    @DisplayName("cap-ref: java: 形式で存在しないクラス/フィールドは例外で失敗する(typo検知)")
    void capRefMissingJavaTargetFails() {
        assertThrows(IllegalArgumentException.class,
                () -> CapRefResolver.resolve("java:com.trinityforge.NoSuchClass#X", null));
        assertThrows(IllegalArgumentException.class,
                () -> CapRefResolver.resolve(
                        "java:com.trinityforge.listeners.CombatListener#NO_SUCH_FIELD", null));
    }

    @Test
    @DisplayName("cap-ref: java: 形式で public でないフィールドは例外で失敗する(可視性強制)")
    void capRefNonPublicJavaFieldFails() {
        // AOE_RADIUS_KEY は private static final String (数値でもpublicでもない) — 両方の理由で弾かれる。
        assertThrows(IllegalArgumentException.class,
                () -> CapRefResolver.resolve(
                        "java:com.trinityforge.listeners.CombatListener#AOE_RADIUS_KEY", null));
    }

    // --- 2) 語彙テスト ----------------------------------------------------------------------------

    @Test
    @DisplayName("trigger.when は閉じた語彙のみ受理する")
    void triggerWhenRejectsUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> StatTriggerWhen.parse("ON_BOGUS"));
        assertEquals(StatTriggerWhen.ON_DAMAGE_TAKEN, StatTriggerWhen.parse("on_damage_taken"));
    }

    @Test
    @DisplayName("trigger.sources は閉じた語彙のみ受理する")
    void sourcesRejectsUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> StatSourceScope.parse("EVERYWHERE"));
        assertEquals(StatSourceScope.MAINHAND_ONLY, StatSourceScope.parse("mainhand_only"));
    }

    @Test
    @DisplayName("trigger.applies-to は閉じた語彙のみ受理する")
    void appliesToRejectsUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> StatAppliesTo.parse("NPC"));
        assertEquals(StatAppliesTo.MOB, StatAppliesTo.parse("mob"));
    }

    @Test
    @DisplayName("limits.stacking は閉じた語彙のみ受理する")
    void stackingRejectsUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> StatStacking.parse("WEIRD"));
        assertEquals(StatStacking.MAX_ONLY, StatStacking.parse("max-only"));
    }

    @Test
    @DisplayName("limits.<X>-ref だけがあり limits.<X> が無いと、フィールド名込みの例外で失敗する")
    void refWithoutValueThrowsWithFieldName() {
        for (String field : List.of("cap", "floor", "min-pieces", "max-distance", "max-duration-ticks")) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("limits." + field + "-ref", "java:com.trinityforge.listeners.CombatListener#"
                    + "MAX_DISTANCE_DAMAGE_BLOCKS");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> LoreConfig.parseLimits(yaml.getConfigurationSection("limits")),
                    field + "-ref without " + field + " must throw");
            assertTrue(ex.getMessage().contains(field + "-ref"),
                    "exception for '" + field + "' must name the -ref field, was: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("limits." + field),
                    "exception for '" + field + "' must name the missing value field, was: " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("実ファイルの全宣言済みtriggerが閉じた語彙のみで構成されている(パース済みモデルの型で保証)")
    void allDeclaredTriggersUseClosedVocabulary(@TempDir File dataFolder) throws IOException {
        LoreConfig config = loadReal(dataFolder);
        for (Map.Entry<String, StatDisplaySpec> e : config.displayTable().entrySet()) {
            StatTrigger trigger = e.getValue().trigger();
            if (trigger == null) {
                continue;
            }
            assertNotNull(trigger.when(), e.getKey() + ": trigger.when");
            assertNotNull(trigger.sources(), e.getKey() + ": trigger.sources");
            assertFalse(trigger.appliesTo().isEmpty(), e.getKey() + ": trigger.applies-to must not be empty");
        }
    }

    @Test
    @DisplayName("出荷版stats/lore.ymlは宣言込みで警告ゼロでロードできる")
    void shippedLoreLoadsWithZeroWarnings(@TempDir File dataFolder) throws IOException {
        copyRealResources(dataFolder);
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger("LoreConfigDeclarationTest-clean-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        LoreConfig config = new LoreConfig();
        boolean ok = config.load(fakePlugin(dataFolder, logger));

        List<String> unexpected = warnings.stream()
                .filter(w -> !w.contains("loaded ") || !w.contains("stat display(s) OK"))
                .toList();
        assertTrue(unexpected.isEmpty(), "unexpected warning(s) loading shipped stats/lore.yml:\n  "
                + String.join("\n  ", unexpected));
        assertTrue(ok, "load() must report success (zero skipped stats) for the shipped file");
    }

    // --- 3) ラチェット(未宣言許可リスト) ------------------------------------------------------------

    @Test
    @DisplayName("未宣言キーの集合は許可リストと厳密一致する(新規statの宣言忘れをここで検知するラチェット)")
    void undeclaredKeysMatchAllowList(@TempDir File dataFolder) throws IOException {
        LoreConfig config = loadReal(dataFolder);
        Set<String> undeclaredActual = new TreeSet<>();
        for (Map.Entry<String, StatDisplaySpec> e : config.displayTable().entrySet()) {
            if (e.getValue().trigger() == null) {
                undeclaredActual.add(e.getKey());
            }
        }

        Set<String> allowList = new TreeSet<>(UNDECLARED_ALLOW_LIST);
        Set<String> missingFromAllowList = new LinkedHashSet<>(undeclaredActual);
        missingFromAllowList.removeAll(allowList);
        Set<String> staleInAllowList = new LinkedHashSet<>(allowList);
        staleInAllowList.removeAll(undeclaredActual);

        assertTrue(missingFromAllowList.isEmpty(),
                "new undeclared stat key(s) not covered by the allow-list (declare trigger/limits, or "
                        + "if genuinely out of scope add to UNDECLARED_ALLOW_LIST): " + missingFromAllowList);
        assertTrue(staleInAllowList.isEmpty(),
                "allow-list has key(s) that are either declared now or no longer exist "
                        + "(shrink UNDECLARED_ALLOW_LIST): " + staleInAllowList);
    }

    @Test
    @DisplayName("宣言済み10キーは全て実ファイルにtrigger宣言を持つ(DECLARED_KEYSの整合)")
    void declaredKeysActuallyHaveTriggers(@TempDir File dataFolder) throws IOException {
        LoreConfig config = loadReal(dataFolder);
        List<String> missing = new ArrayList<>();
        for (String key : DECLARED_KEYS) {
            StatDisplaySpec spec = config.displayTable().get(key);
            if (spec == null || spec.trigger() == null) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "DECLARED_KEYS entry has no trigger in the real file: " + missing);
    }
}
