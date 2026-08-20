package com.trinityforge.config.domains;

import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewUnlockGroup;
import com.trinityforge.config.domains.VillagerTradesConfig.ProfessionTrades;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.effects.DedicatedEffectGateIndex;
import org.bukkit.entity.Villager;
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
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「配置ゼロ = 恒久ロック」防止ガードテスト(2026-07-25 監査より)。
 *
 * <p>解放ゲートの未配置時の挙動はゲート種別で異なる:
 * <ul>
 *   <li>{@code recipe:} / {@code ritual:} / {@code glyph:} / {@code drop:} … <b>フェイルオープン</b>
 *       (未配置 = 解放)。この種別は本テストの対象<b>外</b> — 未配置が正常な状態であり、含めると
 *       「未配置を検出した」という誤検知になってしまう。</li>
 *   <li>{@code brew:} / {@code trade:} / {@code overenchant:} … <b>フェイルクローズ</b>(未配置 =
 *       未解放)。これは設計上意図的({@link com.trinityforge.listeners.BrewUnlockListener} /
 *       {@link com.trinityforge.listeners.VillagerTradeListener} のコメント参照)だが、副作用として
 *       「configに定義したのにスキルツリーへ配置し忘れる」と、そのコンテンツが恒久的に(パッチで
 *       スキルツリーに追加するまで)入手不可能になったまま誰にも気付かれない危険がある。</li>
 * </ul>
 *
 * <p>本テストは configファイル({@code progression/crafting-features.yml} の brew-unlocks / over-enchant、
 * {@code economy/villager-trades.yml} の professions)を実際にロードして対象idを<b>動的に</b>列挙し、
 * 16本のスキルツリーの {@code dedicated-effects} 配置から構築した {@link DedicatedEffectGateIndex} と
 * 突き合わせる。対象idをテスト内にハードコードしていないため、config側に新しい brew-unlocks グループ /
 * villager profession / over-enchant tier が追加されても、スキルツリーへの配置を忘れれば自動的に
 * 検出される。
 */
class FailCloseGateSkillTreePlacementTest {

    private static final String BREW_PREFIX = "brew:";
    private static final String TRADE_PREFIX = "trade:";
    private static final String OVERENCHANT_PREFIX = "overenchant:";

    private static final String[] SKILL_TREE_FILES = {
        "light_weapons.yml", "heavy_weapons.yml", "archery.yml", "light_armor.yml",
        "heavy_armor.yml", "ars_magic.yml", "mining.yml", "woodcutting.yml", "farming.yml",
        "enchanting.yml", "digging.yml", "smithing.yml", "alchemy.yml", "fishing.yml",
        "ars_smithing.yml", "power.yml"
    };

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

    private static void copyResource(File dataFolder, String relativePath) throws IOException {
        File dest = new File(dataFolder, relativePath);
        Files.createDirectories(dest.getParentFile().toPath());
        try (InputStream in = FailCloseGateSkillTreePlacementTest.class.getClassLoader()
                .getResourceAsStream(relativePath)) {
            if (in == null) {
                throw new AssertionError("bundled " + relativePath + " must be on the test classpath");
            }
            Files.copy(in, dest.toPath());
        }
    }

    private static void copyAllSkillTrees(File dataFolder) throws IOException {
        for (String fileName : SKILL_TREE_FILES) {
            copyResource(dataFolder, SkillTreeConfig.DIR + "/" + fileName);
        }
    }

    @Test
    @DisplayName("brew-unlocks / villager-trades / over-enchant: 全対象configエントリがスキルツリーに"
            + "最低1箇所配置されている(未配置=恒久ロックを防ぐ)")
    void allFailCloseGateTargetsHaveAtLeastOneSkillTreePlacement(@TempDir File dataFolder) throws IOException {
        Logger logger = Logger.getLogger("FailCloseGateSkillTreePlacementTest-" + System.nanoTime());

        // 1. config側から対象idを動的に列挙する(ハードコード禁止 — 新規追加項目も自動検出させるため)。
        copyResource(dataFolder, CraftingFeaturesConfig.PATH);
        CraftingFeaturesConfig features = new CraftingFeaturesConfig();
        assertTrue(features.load(fakePlugin(dataFolder, logger)), "crafting-features.yml must load OK");

        copyResource(dataFolder, VillagerTradesConfig.PATH);
        VillagerTradesConfig trades = new VillagerTradesConfig();
        assertTrue(trades.load(fakePlugin(dataFolder, logger)), "villager-trades.yml must load OK");

        List<Target> targets = new ArrayList<>();
        for (Map.Entry<String, BrewUnlockGroup> entry : features.brewUnlocks().entrySet()) {
            targets.add(new Target(BREW_PREFIX + entry.getKey(), CraftingFeaturesConfig.PATH,
                    "brew-unlocks." + entry.getKey()));
        }
        for (Map.Entry<Villager.Profession, ProfessionTrades> entry : trades.byProfession().entrySet()) {
            targets.add(new Target(TRADE_PREFIX + entry.getKey().name(), VillagerTradesConfig.PATH,
                    "professions." + entry.getKey().name()));
        }
        for (String profileId : features.overEnchantProfiles().keySet()) {
            targets.add(new Target(OVERENCHANT_PREFIX + profileId, CraftingFeaturesConfig.PATH,
                    "over-enchant." + profileId));
        }
        assertTrue(!targets.isEmpty(),
                "test setup sanity: expected at least one brew-unlocks/trade/over-enchant target from config, "
                        + "found none — the config fixtures or the loader may have regressed");

        // 2. 16本のスキルツリーをロードし、dedicated-effects配置からゲートインデックスを構築する。
        copyAllSkillTrees(dataFolder);
        SkillTreeConfig skillTreeConfig = new SkillTreeConfig();
        // 戻り値は見ない。load() は「警告が1件でもあれば false」なので、無関係な警告
        // (排他グループのメンバー不足など)でこのテストが【本来の配置漏れ検査に到達する前に】落ちる。
        // ツリーが本当に読めているかどうかは直後の件数チェックで見る(2026-08-16)。
        skillTreeConfig.load(fakePlugin(dataFolder, logger));
        assertTrue(skillTreeConfig.all().size() == SKILL_TREE_FILES.length,
                "16本のツリーが読めていない: " + skillTreeConfig.all().keySet());
        DedicatedEffectGateIndex index = DedicatedEffectGateIndex.build(skillTreeConfig.all().values());

        // 3. 突き合わせ: brew:/trade:/overenchant: はいずれも GateEffectId により FLAG チャンネルへ
        //    ルーティングされ、target = 完全な元id(プレフィックス込み)になる(GateEffectId.java参照)。
        //    flagPerks() にキーが無い、またはパーク集合が空 = どのスキルツリーノードにも配置されて
        //    いない = フェイルクローズ側なので恒久的に入手不可能。
        List<String> violations = new ArrayList<>();
        for (Target target : targets) {
            Set<String> perks = index.flagPerks().get(target.gateId());
            if (perks == null || perks.isEmpty()) {
                violations.add("id='" + target.gateId() + "' (config: " + target.configFile()
                        + " の " + target.configKey() + ") はどのスキルツリーの dedicated-effects にも"
                        + "配置されていません。このゲート種別はフェイルクローズ(未配置=未解放)のため、"
                        + "このまま放置するとこのコンテンツは恒久的に入手不可能になります。"
                        + "いずれかのスキルツリーノードに dedicated-effects として配置してください。");
            }
        }
        assertTrue(violations.isEmpty(),
                "フェイルクローズなゲートの未配置(恒久ロック化)を検出:\n  "
                        + String.join("\n  ", violations));
    }

    private record Target(String gateId, String configFile, String configKey) {}
}
