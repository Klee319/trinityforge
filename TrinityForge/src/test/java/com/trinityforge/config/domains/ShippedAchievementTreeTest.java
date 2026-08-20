package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code progression/achievements.yml}(指南ツリー)の整合性を固定する(2026-07-31)。
 *
 * <p><b>なぜ必要か</b>: このファイルの間違いはどれも<b>起動時の警告1行で済んでしまい、
 * ゲーム内では「そのアチーブメントが存在しないだけ」に見える</b>。具体的には:
 * <ul>
 *   <li>{@code statistic-qualifier} に非ブロックの Material を書く → そのノードだけ丸ごと skip</li>
 *   <li>{@code parent} のIDを打ち間違える → 条件を満たしても永久に達成にならない</li>
 *   <li>{@code rewards.special} に未定義IDを書く → 達成しても称号が<b>無言で</b>配られない</li>
 *   <li>{@code collection.scope: category} に無いカテゴリIDを書く → 分母0で永久に未達成</li>
 * </ul>
 * 35ノードを目視で確かめ続けるのは無理なので、機械で固定する。
 *
 * <p>{@code permanent-buffs} の本数まで見ているのは 2026-07-31 のユーザー確定
 * 「束縛者だけ縦強化、他は称号/コスメ」を config のドリフトから守るため。ここが緩むと、
 * 格差の吸収に選んだ3本(24時間EXP減衰 / 指数コスト / 横の選択肢)が全部意味を失う。
 */
class ShippedAchievementTreeTest {

    private static final String ACHIEVEMENTS = "src/main/resources/" + AchievementsConfig.PATH;
    private static final String SPECIAL_REWARDS = "src/main/resources/progression/special-rewards.yml";
    private static final String COLLECTION = "src/main/resources/progression/collection.yml";

    /**
     * 実装のある累計カウンタID。増やすときは加算側(ArsPaper フォーク)も必ず用意する。
     *
     * <p>2026-08-16 の再構築で source_spent 以外を追加した。加算箇所はいずれも ArsPaper 側:
     * グリフ解放(ScribingTable)/ 儀式成立・儀式エフェクト(RitualManager)/ 呪文詠唱(SpellCaster)/
     * 共有エンチャント(EnchantBook)。TF 本体は読むだけ。
     */
    private static final Set<String> IMPLEMENTED_COUNTERS = Set.of(
            "source_spent",
            "glyph_unlocked",
            "glyph_harm",
            "glyph_break",
            "glyph_exchange",
            "glyph_grow",
            "ritual_performed",
            "ritual_effect_used",
            "spell_augment_used",
            "catalyst_cast",
            "enchant_book_shared");

    private List<AchievementsConfig.Achievement> achievements;
    private List<String> warnings;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        Logger log = Logger.getLogger("ShippedAchievementTreeTest");
        log.setUseParentHandlers(false);
        List<String> captured = new ArrayList<>();
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    captured.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        this.warnings = captured;
        ConfigurationSection root = YamlConfiguration
                .loadConfiguration(new File(ACHIEVEMENTS))
                .getConfigurationSection("achievements");
        AchievementsConfig.ParseResult result = AchievementsConfig.parse(root, log);
        this.achievements = result.achievements();
        // parse は skip 件数を返すだけなので、テスト側で件数と警告本文の両方を見る。
        assertEquals(0, result.skipped(),
                "出荷 achievements.yml に読み込みでスキップされる定義がある: " + captured);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("読み込み時に警告が1件も出ない(=全ノードが有効)")
    void loadsWithoutAnyWarning() {
        assertTrue(warnings.isEmpty(), "警告が出ている: " + warnings);
        assertFalse(achievements.isEmpty(), "1件も読み込めていない");
    }

    @Test
    @DisplayName("前提(parent / parents-any)は全て存在し、循環しない")
    void prerequisitesResolve() {
        Set<String> known = new LinkedHashSet<>();
        achievements.forEach(a -> known.add(a.id()));
        for (AchievementsConfig.Achievement achievement : achievements) {
            if (achievement.parent() != null) {
                assertTrue(known.contains(achievement.parent()),
                        achievement.id() + " の parent '" + achievement.parent() + "' が存在しない"
                                + "(条件を満たしても永久に達成にならない)");
            }
            for (String any : achievement.parentsAny()) {
                assertTrue(known.contains(any),
                        achievement.id() + " の parents-any '" + any + "' が存在しない");
            }
            // parent 鎖をたどって自分へ戻らないこと。
            Set<String> seen = new HashSet<>();
            seen.add(achievement.id());
            String cursor = achievement.parent();
            while (cursor != null) {
                assertTrue(seen.add(cursor), achievement.id() + " の parent 鎖が循環している");
                String next = null;
                for (AchievementsConfig.Achievement candidate : achievements) {
                    if (candidate.id().equals(cursor)) {
                        next = candidate.parent();
                        break;
                    }
                }
                cursor = next;
            }
        }
    }

    /**
     * 2026-08-16 の再構築で「1起点5章」から「3つの道 + 秘された道」へ変えた。
     * 起点はそのまま GUI の系統バーの見出しになるので、意図しない起点が増えると
     * <b>タブが1つ勝手に生える</b>(= どこかのノードの parent を打ち間違えたサイン)。
     */
    @Test
    @DisplayName("起点はちょうど4つ(3つの道 + 秘された道)")
    void rootsAreTheFourPaths() {
        List<String> roots = achievements.stream()
                .filter(AchievementsConfig.Achievement::isRoot)
                .map(AchievementsConfig.Achievement::id)
                .toList();
        assertEquals(List.of("warrior_root", "mage_root", "adventurer_root", "secrets_root"), roots,
                "起点が想定と違う(parent の打ち間違いで系統タブが増減する): " + roots);
    }

    /**
     * 裏アチーブメントは「達成するまで完全非表示」(2026-08-16 ユーザー確定)。
     * <b>起点にしてはいけない</b> ── 起点は系統バーの見出しなので、隠しノードを起点にすると
     * 達成した瞬間にタブが増え、逆に未達成のうちは系統ごと消えて座標計算が飛ぶ。
     */
    @Test
    @DisplayName("hidden なノードは起点にしない(必ず secrets_root の下に置く)")
    void hiddenAchievementsAreNeverRoots() {
        List<String> offenders = achievements.stream()
                .filter(AchievementsConfig.Achievement::hidden)
                .filter(AchievementsConfig.Achievement::isRoot)
                .map(AchievementsConfig.Achievement::id)
                .toList();
        assertEquals(List.of(), offenders, "hidden なのに起点になっている: " + offenders);
        assertFalse(achievements.stream().noneMatch(AchievementsConfig.Achievement::hidden),
                "裏アチーブメントが1件も無い(hidden: true の指定が全部落ちている)");
    }

    @Test
    @DisplayName("rewards.special のIDは special-rewards.yml に定義されている")
    void specialRewardIdsExist() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(SPECIAL_REWARDS));
        Set<String> defined = new LinkedHashSet<>();
        for (String group : List.of("titles", "particles", "particle-seeds")) {
            ConfigurationSection section = yaml.getConfigurationSection(group);
            if (section != null) {
                defined.addAll(section.getKeys(false));
            }
        }
        assertFalse(defined.isEmpty(), "special-rewards.yml から1件も読めていない");
        for (AchievementsConfig.Achievement achievement : achievements) {
            for (String id : achievement.rewards().special()) {
                assertTrue(defined.contains(id),
                        achievement.id() + " の rewards.special '" + id + "' が special-rewards.yml に無い"
                                + "(達成しても無言で何も配られない)");
            }
        }
    }

    @Test
    @DisplayName("collection.scope: category の対象は collection.yml のカテゴリIDである")
    void collectionCategoryTargetsExist() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(COLLECTION));
        Set<String> categories = new LinkedHashSet<>();
        for (String kind : List.of("items", "mobs")) {
            ConfigurationSection section = yaml.getConfigurationSection("categories." + kind);
            if (section != null) {
                categories.addAll(section.getKeys(false));
            }
        }
        assertFalse(categories.isEmpty(), "collection.yml からカテゴリを1件も読めていない");
        for (AchievementsConfig.Achievement achievement : achievements) {
            AchievementsConfig.Trigger trigger = achievement.trigger();
            if (!"category".equals(trigger.collectionScope())) {
                continue;
            }
            for (String target : trigger.collectionTargets()) {
                assertTrue(categories.contains(target),
                        achievement.id() + " の collection.targets '" + target
                                + "' は collection.yml のカテゴリIDに無い(分母0で永久に未達成)");
            }
        }
    }

    @Test
    @DisplayName("category スコープのしきい値はそのカテゴリの件数以下")
    void collectionCategoryThresholdIsReachable() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(COLLECTION));
        for (AchievementsConfig.Achievement achievement : achievements) {
            AchievementsConfig.Trigger trigger = achievement.trigger();
            if (!"category".equals(trigger.collectionScope()) || trigger.collectionPercent()) {
                continue;
            }
            int available = 0;
            for (String target : trigger.collectionTargets()) {
                for (String kind : List.of("items", "mobs")) {
                    List<String> entries = yaml.getStringList("categories." + kind + "." + target + ".entries");
                    available += entries.size();
                }
            }
            assertTrue(trigger.threshold() <= available,
                    achievement.id() + " のしきい値 " + trigger.threshold()
                            + " が候補数 " + available + " を超えている(永久に未達成)");
        }
    }

    /**
     * {@code scope: item} の対象IDのタイプミスを拾う。
     *
     * <p>照合先は「collection.yml のカテゴリに載っているID」＋「thread_all ノードが列挙したスレッド45種」。
     * ArsPaper 側の登録一覧そのもの({@code materials.yml} 等)とは突き合わせられない ──
     * フォークのソースは {@code .gitignore} で除外されておりクローンには存在しないため、
     * そこへ依存させるとクローン先でこのテストが落ちる(または無言でスキップされる)。
     *
     * <p>それでも十分に効く: {@code thread_variety} だけ {@code thread_manaregen} と綴ってしまう類の
     * 「1ノードだけ永久に未達成」が、他ノードとの綴り不一致として現れる。
     */
    @Test
    @DisplayName("collection.scope: item の対象IDは図鑑エントリかスレッド45種のいずれか")
    void collectionItemTargetsAreKnownIds() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(COLLECTION));
        Set<String> known = new LinkedHashSet<>();
        for (String kind : List.of("items", "mobs")) {
            ConfigurationSection section = yaml.getConfigurationSection("categories." + kind);
            if (section == null) {
                continue;
            }
            for (String category : section.getKeys(false)) {
                known.addAll(yaml.getStringList("categories." + kind + "." + category + ".entries"));
            }
        }
        AchievementsConfig.Achievement threadAll = achievements.stream()
                .filter(a -> a.id().equals("thread_all")).findFirst().orElseThrow(
                        () -> new AssertionError("thread_all ノードが無い(スレッドIDの基準表が失われている)"));
        assertEquals(45, threadAll.trigger().collectionTargets().size(),
                "スレッドは ArsPaper の ThreadType に45種(2026-08-02 に16→40種、2026-08-03 に"
                        + "レシピを持たないガチャ専用5種を追加)。増減したらこのノードも合わせる");
        known.addAll(threadAll.trigger().collectionTargets());

        for (AchievementsConfig.Achievement achievement : achievements) {
            if (!"item".equals(achievement.trigger().collectionScope())) {
                continue;
            }
            for (String target : achievement.trigger().collectionTargets()) {
                assertTrue(known.contains(target),
                        achievement.id() + " の collection.targets '" + target
                                + "' は図鑑エントリにもスレッド45種にも無い(綴り違いなら永久に未達成)");
            }
        }
    }

    @Test
    @DisplayName("counter トリガは加算実装のあるIDだけを参照する")
    void counterIdsAreImplemented() {
        for (AchievementsConfig.Achievement achievement : achievements) {
            AchievementsConfig.Trigger trigger = achievement.trigger();
            if (trigger.type() != AchievementsConfig.TriggerType.COUNTER) {
                continue;
            }
            assertTrue(IMPLEMENTED_COUNTERS.contains(trigger.counter()),
                    achievement.id() + " の counter '" + trigger.counter()
                            + "' に加算実装が無い(条件を満たしようがない)");
        }
    }

    @Test
    @DisplayName("type: advancement は使わない(バニラ進捗の抑止と噛み合って達成不能になる)")
    void noAdvancementTriggers() {
        List<String> offenders = achievements.stream()
                .filter(a -> a.trigger().type() == AchievementsConfig.TriggerType.ADVANCEMENT)
                .map(AchievementsConfig.Achievement::id)
                .toList();
        assertEquals(List.of(), offenders,
                "vanilla-advancements.disabled: true のため、これらは永久に達成できない: " + offenders);
    }

    @Test
    @DisplayName("縦強化(permanent-buffs)を配るのは束縛者討伐だけ")
    void onlyTheWorldbinderGrantsPermanentBuffs() {
        List<String> withBuffs = achievements.stream()
                .filter(a -> !a.rewards().permanentBuffs().isEmpty())
                .map(AchievementsConfig.Achievement::id)
                .toList();
        assertEquals(List.of("goal_worldbinder"), withBuffs,
                "2026-07-31 ユーザー確定「束縛者だけ縦強化、他は称号/コスメ」に反している: " + withBuffs);
    }

    @Test
    @DisplayName("職業EXP(job-exp)を配るアチーブメントは1件も無い")
    void noAchievementGrantsJobExperience() {
        // 2026-08-16 ユーザー確定「アチーブメントの報酬から職業経験値を除外」。
        // アチーブメントは「その職業を進めた結果」なので、職業EXPを返すと進行が自己加速し、
        // 格差吸収に選んだ24時間EXP減衰を素通りする。代わりに道の節目へ特殊報酬を置く。
        List<String> offenders = achievements.stream()
                .filter(a -> !a.rewards().jobExp().isEmpty())
                .map(AchievementsConfig.Achievement::id)
                .toList();
        assertEquals(List.of(), offenders, "報酬に職業EXPが戻っている: " + offenders);
    }

    @Test
    @DisplayName("特殊報酬は「節目」だけに置かれている(全ノードには配らない)")
    void specialRewardsAreReservedForMilestones() {
        long withSpecial = achievements.stream()
                .filter(a -> !a.rewards().special().isEmpty())
                .count();
        long withoutSpecial = achievements.size() - withSpecial;
        assertTrue(withSpecial >= 6, "特殊報酬を持つノードが " + withSpecial + " 件しかない"
                + "(職業EXPを外した代わりの受け皿が消えている)");
        assertTrue(withoutSpecial >= 10,
                "特殊報酬の無いノードが " + withoutSpecial + " 件しか残っていない。"
                        + "ユーザー指示は「キリの良いところ(すべてではない)」なので、"
                        + "節目でないノードの報酬はガチャ券のみに保つこと");
    }

    @Test
    @DisplayName("最終目標3種が揃っている")
    void allThreeGoalsPresent() {
        Set<String> ids = new LinkedHashSet<>();
        achievements.forEach(a -> ids.add(a.id()));
        for (String goal : List.of("goal_worldbinder", "goal_infinite_source", "goal_completionist")) {
            assertTrue(ids.contains(goal), goal + " が無い");
        }
        AchievementsConfig.Achievement source = achievements.stream()
                .filter(a -> a.id().equals("goal_infinite_source")).findFirst().orElseThrow();
        assertEquals(100_000_000L, source.trigger().threshold(), "第2目標は累計1億ソース");
    }
}
