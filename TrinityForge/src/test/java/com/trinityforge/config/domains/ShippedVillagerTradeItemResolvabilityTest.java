package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code economy/villager-trades.yml} の {@code catalog:} 指定が
 * <b>実物へ解決できるIDだけ</b >であることを固定する（2026-08-02）。
 *
 * <p><b>実際に踏んだ穴</b>: {@code VillagerTradeListener#resolveStack} は TF の
 * {@code catalog.yml} しか引いていなかったが、出荷の {@code tf_scrap} / {@code tf_core_jewelry} は
 * <b>ArsPaper の {@code materials.yml} 由来のID</b>で TF カタログには存在しない。
 * その結果 WEAPONSMITH の追加取引が<b>2件とも解決に失敗して無言で捨てられ</b>、
 * さらに {@code block-vanilla-trades: true} なのでバニラ取引も消え、
 * {@code trade:WEAPONSMITH} を解放したプレイヤーには<b>空の商人</b>が出ていた。
 * リスナー側は Ars レジストリへフォールバックするように直してある。
 *
 * <p>このテストは<b>ArsPaper 由来のIDを明示的に列挙</b>する。フォークのソースは
 * {@code .gitignore} 除外でこのリポジトリのクローンには存在せず、テストから読めないため。
 * 新しいIDを取引に足すときは、TFカタログに入れるか、この一覧へ追記して
 * 「ArsPaper が無いと解決できない」ことを意識的に選ぶこと（打ち間違いはここで落ちる）。
 */
class ShippedVillagerTradeItemResolvabilityTest {

    private static final String TRADES = "src/main/resources/economy/villager-trades.yml";
    private static final String CATALOG = "src/main/resources/items/catalog.yml";

    /**
     * TFカタログに無く、ArsPaper の {@code materials.yml} が供給するID。
     * ここに載せる＝「ArsPaper が enable されていないと、この取引は出ない」ことを承知した、の意。
     */
    private static final Set<String> ARS_PROVIDED =
            Set.of("tf_scrap", "tf_core_jewelry", "iron_ingot_scrap");

    /** 柱4: スクラップの出口。無くなると tf_scrap が村人側で行き止まりに戻る。 */
    @Test
    @DisplayName("TOOLSMITH に tf_scrap の出口がある(柱4)")
    void toolsmithConsumesScrap() {
        ConfigurationSection professions = loadProfessions();
        List<?> trades = professions.getList("TOOLSMITH.trades");
        assertNotNull(trades, "TOOLSMITH に trades が無い");

        boolean hasScrapExit = trades.stream().anyMatch(raw ->
                raw instanceof java.util.Map<?, ?> trade
                        && trade.get("input") instanceof java.util.Map<?, ?> input
                        && "tf_scrap".equals(input.get("catalog")));

        assertTrue(hasScrapExit,
                "TOOLSMITH が tf_scrap を吸う取引を持っていない。"
                        + "tf_scrap は釣りのゴミとガチャから出るので、出口が無いと在庫が溜まるだけになる");
    }

    @Test
    @DisplayName("村人取引の catalog: は TFカタログか ArsPaper のどちらかで必ず解決できる")
    void everyTradeCatalogIdIsResolvable() {
        Set<String> catalogIds = loadCatalogIds();
        List<String> used = collectTradeCatalogIds();

        assertTrue(!used.isEmpty(),
                "出荷 villager-trades.yml から catalog: 指定が1件も読めていない。"
                        + "パースが壊れているか、節ごと消えている");

        Set<String> unresolvable = new TreeSet<>();
        for (String id : used) {
            if (!catalogIds.contains(id) && !ARS_PROVIDED.contains(id)) {
                unresolvable.add(id);
            }
        }

        if (!unresolvable.isEmpty()) {
            throw new AssertionError(
                    "村人取引が参照する catalog: ID のうち " + unresolvable.size() + " 件が解決できない: "
                            + String.join(", ", unresolvable)
                            + "\nVillagerTradeListener は解決に失敗した取引を捨てるので、"
                            + "block-vanilla-trades: true の職業では『解放したのに空の商人』になる。"
                            + "\nTF の items/catalog.yml に定義するか、ArsPaper の materials.yml にあるなら"
                            + "このテストの ARS_PROVIDED へ追記すること。");
        }
    }

    @Test
    @DisplayName("ARS_PROVIDED に書いたIDが TFカタログにも生えていないか(二重定義の検出)")
    void arsProvidedIdsAreNotAlsoInTheTrinityForgeCatalog() {
        Set<String> catalogIds = loadCatalogIds();
        Set<String> both = new TreeSet<>(ARS_PROVIDED);
        both.retainAll(catalogIds);

        assertTrue(both.isEmpty(),
                "TFカタログにも同名IDがある: " + both
                        + " — resolveStack はTFカタログを先に引くので Ars 版は使われない。"
                        + "意図した挙動なら、このテストの ARS_PROVIDED から外すこと");
    }

    private static ConfigurationSection loadProfessions() {
        File file = new File(TRADES);
        assertTrue(file.isFile(), "出荷 villager-trades.yml が見つからない: " + file.getAbsolutePath());
        ConfigurationSection professions =
                YamlConfiguration.loadConfiguration(file).getConfigurationSection("professions");
        assertNotNull(professions, TRADES + " に professions: 節が無い");
        return professions;
    }

    /** {@code professions.<職業>.trades[].input/output.catalog} を全部集める。 */
    private static List<String> collectTradeCatalogIds() {
        ConfigurationSection professions = loadProfessions();
        List<String> ids = new ArrayList<>();
        for (String profession : professions.getKeys(false)) {
            List<?> trades = professions.getList(profession + ".trades");
            if (trades == null) {
                continue;
            }
            for (Object raw : trades) {
                if (!(raw instanceof java.util.Map<?, ?> trade)) {
                    continue;
                }
                for (String side : List.of("input", "output")) {
                    if (trade.get(side) instanceof java.util.Map<?, ?> stack
                            && stack.get("catalog") instanceof String id
                            && !id.isBlank()) {
                        ids.add(id.trim());
                    }
                }
            }
        }
        return ids;
    }

    private static Set<String> loadCatalogIds() {
        File file = new File(CATALOG);
        assertTrue(file.isFile(), "出荷カタログが見つからない: " + file.getAbsolutePath());
        ConfigurationSection items =
                YamlConfiguration.loadConfiguration(file).getConfigurationSection("items");
        assertNotNull(items, CATALOG + " に items: 節が無い");
        return new LinkedHashSet<>(items.getKeys(false));
    }
}
