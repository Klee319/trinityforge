package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ガチャ券の<b>恒常入手経路</b>を守る。
 *
 * <h2>なぜ必要か（2026-08-14 に実際に起きた事故）</h2>
 * フィールドドロップ配線の作業で、{@code combat/mob-level-table.yml} の6帯すべてから
 * {@code custom:gacha_ticket_0}（素の券 / {@code standard} プール）のエントリが指示外で削除された。
 * 削除後に残っていた「経路」は次の2つだけで、どちらも<b>供給にならない</b>:
 * <ul>
 *   <li>{@code progression/achievements.yml} の進捗 {@code first_step} の報酬 —— <b>一回きり</b>。</li>
 *   <li>{@code gacha.yml} の {@code standard} プールの景品 —— <b>券を消費して券を引く自己循環</b>。
 *       期待値が 1 未満である以上、供給源にはなりえない。</li>
 * </ul>
 * つまりガチャの周回ループが恒久的に止まっていた。yml は例外もログも出さないので、
 * 「ガチャ券が出ない」という体感が積み上がるまで誰も気づけない。
 *
 * <h2>ここで言う「恒常経路」</h2>
 * <b>モブ討伐ドロップだけ</b>を数える —— {@code combat/mob-level-table.yml} の {@code add-drops}
 * と {@code combat/mob-overrides.yml} の {@code drops}。進捗報酬（一回きり）とガチャ景品（自己循環）は
 * 意図的に数えない。この2つを数えてしまうと、上の事故がそのまま素通りする。
 *
 * <h2>許可リストにしない工夫</h2>
 * 検査対象の券を手書きせず、{@code gacha.yml} の {@code tickets:} キーから毎回導出する。
 * 券を増やして経路を配線し忘れれば、そのまま落ちる。
 */
class ShippedGachaTicketRouteTest {

    private static final String MOB_LEVEL_TABLE = "src/main/resources/combat/mob-level-table.yml";
    private static final String MOB_OVERRIDES = "src/main/resources/combat/mob-overrides.yml";
    private static final String GACHA = "src/main/resources/gacha.yml";

    private static final String CUSTOM_PREFIX = "custom:";

    /** 券の下限件数（{@code tickets:} 節ごと消えて検査が空回りするのを防ぐ）。 */
    private static final int MIN_EXPECTED_TICKETS = 5;

    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("gacha.yml が宣言する券は全件がモブ討伐ドロップの経路を持つ — 進捗報酬(一回きり)とガチャ景品(自己循環)は供給にならない")
    void everyDeclaredTicketHasARepeatableMobDropRoute() throws Exception {
        Set<String> tickets = declaredTicketIds();
        Set<String> fromBands = ticketIdsInLevelTable();
        Set<String> fromBosses = ticketIdsInMobOverrides();

        assertTrue(!fromBands.isEmpty(),
                MOB_LEVEL_TABLE + " の add-drops にガチャ券が1件も無い。抽出側が壊れていると"
                        + "この検査が丸ごと無効化されるので、まず抽出できていることを確かめる");
        assertTrue(!fromBosses.isEmpty(),
                MOB_OVERRIDES + " の drops にガチャ券が1件も無い。同上");

        List<String> unobtainable = new ArrayList<>();
        for (String ticket : tickets) {
            if (!fromBands.contains(ticket) && !fromBosses.contains(ticket)) {
                unobtainable.add(ticket);
            }
        }
        assertTrue(unobtainable.isEmpty(),
                "gacha.yml の tickets: に宣言されているのに、モブ討伐ドロップの経路が1本も無い券がある。"
                        + "進捗報酬は一回きり、ガチャ景品は『券を消費して券を引く』自己循環なので、"
                        + "どちらも恒常供給にはならない —— この状態はガチャの周回ループが恒久的に"
                        + "止まっていることを意味する(2026-08-14 に gacha_ticket_0 で実際に起きた)。"
                        + "帯ドロップ(combat/mob-level-table.yml の add-drops)か"
                        + "踏破ボスドロップ(combat/mob-overrides.yml の drops)のどちらかへ配線すること: "
                        + unobtainable
                        + " / 帯にある券=" + fromBands + " / ボスにある券=" + fromBosses);
    }

    @Test
    @DisplayName("帯を問わない券(素の券・【I】)は6帯すべてに載っている — 1帯でも欠けるとそのレベル帯では0枚")
    void ticketsSpanningMultipleBandsAppearInEveryBand() throws Exception {
        List<Map<?, ?>> tiers = tiers();
        assertTrue(tiers.size() >= 2,
                "帯が " + tiers.size() + " 個しかない。このテストは『全帯に載っているか』を見るので"
                        + "帯が1つだと素通りする");

        Map<String, Set<String>> bandsByTicket = bandsByTicket();
        Set<String> allBands = allBands();
        assertTrue(!bandsByTicket.isEmpty(),
                MOB_LEVEL_TABLE + " の add-drops にガチャ券が1件も無い(配線ごと消えている)");

        // 「帯を問わない券」かどうかは券IDのハードコードではなく、2帯以上に書かれているかで判定する。
        // ちょうど1帯にしか無い券は【帯に対応づけた上位券】なので、こちらの検査の対象外
        // (そちらは bandExclusiveTicketsFormAnUninterruptedLadder が固定する)。
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : bandsByTicket.entrySet()) {
            if (entry.getValue().size() < 2 || entry.getValue().equals(allBands)) {
                continue;
            }
            Set<String> missing = new TreeSet<>(allBands);
            missing.removeAll(entry.getValue());
            problems.add(entry.getKey() + " が " + missing + " に無い(載っているのは " + entry.getValue() + ")");
        }
        assertTrue(problems.isEmpty(),
                "複数の帯に書かれているガチャ券が、一部の帯にだけ書かれていない。帯の解決は"
                        + " floor lookup(min-level <= L を満たす最大の帯が【1つだけ】選ばれる)で、"
                        + "上の帯から下の帯へ継承されない。つまり欠けている帯のレベルのモブからは"
                        + "その券が1枚も落ちない(例外もログも出ない)。"
                        + "帯を問わない供給源(素の券・ガチャ券【I】)は必ず全帯へ複製すること: " + problems);
    }

    @Test
    @DisplayName("1帯だけの上位券は、番号の小さい方から順に上位帯へ並ぶ連続した階段になっている — 途中を消すと階段に穴が空く")
    void bandExclusiveTicketsFormAnUninterruptedLadder() throws Exception {
        // yml の記述順ではなく min-level の昇順で並べる(並べ替えられても壊れないように)。
        TreeMap<Double, String> byMinLevel = new TreeMap<>();
        for (Map<?, ?> tier : tiers()) {
            Object raw = tier.get("min-level");
            byMinLevel.put(raw instanceof Number n ? n.doubleValue() : Double.NaN, bandKey(tier));
        }
        List<String> bandsAscending = new ArrayList<>(byMinLevel.values());

        Map<String, Set<String>> bandsByTicket = bandsByTicket();

        // 券IDの配列は書かない。宣言(gacha.yml の tickets:)と、実際に何帯へ書かれているかだけから導く。
        Set<String> multiBand = new TreeSet<>();
        Map<String, String> exclusiveBandByTicket = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : bandsByTicket.entrySet()) {
            if (entry.getValue().size() == 1) {
                exclusiveBandByTicket.put(entry.getKey(), entry.getValue().iterator().next());
            } else {
                multiBand.add(entry.getKey());
            }
        }

        assertTrue(!exclusiveBandByTicket.isEmpty(),
                "帯ドロップに『その帯だけの上位券』が1件も無い。2026-08-14 に gacha_ticket_2/3/4 が"
                        + "3帯からまとめて消えたのがこの状態で、上位プールの非ダンジョン経路が全滅する。"
                        + "帯を問わない券だけが残っている: " + multiBand);

        // 期待される階段 = gacha.yml の宣言順(番号順)のうち、全帯券でないものの【先頭から連続】。
        // 番号の低い券ほど入手しやすいのが券の設計なので、途中を飛ばした階段は許さない。
        List<String> ladderCandidates = new ArrayList<>();
        for (String ticket : declaredTicketIdsInDeclaredOrder()) {
            if (!multiBand.contains(ticket)) {
                ladderCandidates.add(ticket);
            }
        }

        // 実際の階段を、載っている帯の低い順に並べる。
        List<String> actualLadder = new ArrayList<>();
        for (String band : bandsAscending) {
            for (Map.Entry<String, String> entry : exclusiveBandByTicket.entrySet()) {
                if (entry.getValue().equals(band)) {
                    actualLadder.add(entry.getKey());
                }
            }
        }

        List<String> expectedLadder = ladderCandidates.subList(
                0, Math.min(actualLadder.size(), ladderCandidates.size()));
        assertEquals(expectedLadder, actualLadder,
                "『その帯だけの上位券』の並びが、宣言順の先頭からの連続になっていない。"
                        + "券は番号が小さいほど入手しやすいのが設計なので、番号を飛ばすと"
                        + "『下位券だけ踏破ボス限定・上位券は帯周回で出る』という逆転になる。"
                        + "また帯の低い順＝券の番号順でないと『弱い敵から上位券』になる。"
                        + "宣言順(全帯券を除く)=" + ladderCandidates
                        + " / 実際(帯の低い順)=" + actualLadder
                        + " / 帯の割り当て=" + exclusiveBandByTicket);

        // 階段が占める帯は【最上位から連続した K 帯】でなければならない。
        // 一番強い敵を狩る帯に上位券が無いと、最上位帯を狩る動機がひとつ消える。
        List<String> expectedBands = bandsAscending.subList(
                Math.max(0, bandsAscending.size() - actualLadder.size()), bandsAscending.size());
        List<String> actualBands = new ArrayList<>();
        for (String ticket : actualLadder) {
            actualBands.add(exclusiveBandByTicket.get(ticket));
        }
        assertEquals(expectedBands, actualBands,
                "『その帯だけの上位券』が最上位帯から連続して並んでいない。上位券は"
                        + "「強い敵ほど上位の券」という対応で帯へ割り当てているので、"
                        + "最上位帯や途中の帯が抜けると割り当ての意味が崩れる。"
                        + "期待(最上位から " + actualLadder.size() + " 帯)=" + expectedBands
                        + " / 実際=" + actualBands);
    }

    @Test
    @DisplayName("自分自身のプールの景品にもなっている券(自己循環)は、必ずモブドロップ経路も持つ — 循環だけでは供給ゼロ")
    void selfCirculatingTicketsAlsoHaveAnExternalRoute() throws Exception {
        YamlConfiguration gacha = load(GACHA);
        ConfigurationSection tickets = gacha.getConfigurationSection("tickets");
        assertNotNull(tickets, GACHA + " に tickets: 節が無い");

        Set<String> external = new TreeSet<>(ticketIdsInLevelTable());
        external.addAll(ticketIdsInMobOverrides());

        List<String> selfCirculating = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String ticket : tickets.getKeys(false)) {
            String pool = tickets.getString(ticket + ".pool");
            if (pool == null) {
                continue;
            }
            boolean circular = false;
            for (Map<?, ?> entry : gacha.getMapList("pools." + pool + ".entries")) {
                if (normalize(ticket).equals(normalize(text(entry.get("item"))))) {
                    circular = true;
                    break;
                }
            }
            if (!circular) {
                continue;
            }
            selfCirculating.add(ticket);
            if (!external.contains(normalize(ticket))) {
                problems.add(ticket + "(pool: " + pool + ")");
            }
        }
        assertTrue(!selfCirculating.isEmpty(),
                "自己循環している券が1件も検出できていない。gacha.yml の pools/entries の構造が"
                        + "変わってこの検査が空回りしている可能性がある");
        assertTrue(problems.isEmpty(),
                "『そのプールを引くのに必要な券が、そのプールの景品にも入っている』(=自己循環)のに、"
                        + "外部からの恒常入手経路が無い券がある。券を消費して券を引く以上、"
                        + "期待値が1未満なら残高は必ず減る —— 供給源にはなりえない。"
                        + "モブ討伐ドロップ側へ配線すること: " + problems
                        + " / 自己循環している券=" + selfCirculating);
    }

    // ------------------------------------------------------------------------------------------
    // 出荷 yml の読み取り
    // ------------------------------------------------------------------------------------------

    private static YamlConfiguration load(String relativePath) throws Exception {
        Path path = Path.of(relativePath);
        assertTrue(Files.isRegularFile(path), "出荷 yml が見つからない: " + path.toAbsolutePath());
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(Files.readString(path));
        return cfg;
    }

    private static Set<String> declaredTicketIds() throws Exception {
        ConfigurationSection tickets = load(GACHA).getConfigurationSection("tickets");
        assertNotNull(tickets, GACHA + " に tickets: 節が無い");
        Set<String> ids = new TreeSet<>();
        for (String key : tickets.getKeys(false)) {
            ids.add(normalize(key));
        }
        assertTrue(ids.size() >= MIN_EXPECTED_TICKETS,
                "券が " + ids.size() + " 種しか読めていない(期待: " + MIN_EXPECTED_TICKETS + " 種以上)。"
                        + "tickets: 節ごと消えるとこの検査が素通りするので下限で縛っている: " + ids);
        return ids;
    }

    private static List<Map<?, ?>> tiers() throws Exception {
        List<Map<?, ?>> tiers = load(MOB_LEVEL_TABLE).getMapList("tiers");
        assertTrue(!tiers.isEmpty(), MOB_LEVEL_TABLE + " に tiers: が無い");
        return tiers;
    }

    /**
     * {@code gacha.yml} が宣言する券を<b>階級順</b>(IDの末尾の数値の昇順)で返す。
     * 券IDの配列はテスト側に書かない —— 券を増やしたら自動的にこの一覧へ入る。
     */
    private static List<String> declaredTicketIdsInDeclaredOrder() throws Exception {
        ConfigurationSection tickets = load(GACHA).getConfigurationSection("tickets");
        assertNotNull(tickets, GACHA + " に tickets: 節が無い");
        List<String> ids = new ArrayList<>();
        for (String key : tickets.getKeys(false)) {
            ids.add(normalize(key));
        }
        ids.sort((left, right) -> {
            int bySuffix = Integer.compare(trailingNumber(left), trailingNumber(right));
            return bySuffix != 0 ? bySuffix : left.compareTo(right);
        });
        return ids;
    }

    /** {@code "gacha_ticket_12"} -> 12。末尾が数値でなければ {@link Integer#MAX_VALUE}。 */
    private static int trailingNumber(String id) {
        int index = id.length();
        while (index > 0 && Character.isDigit(id.charAt(index - 1))) {
            index--;
        }
        if (index == id.length()) {
            return Integer.MAX_VALUE;
        }
        return Integer.parseInt(id.substring(index));
    }

    /** 帯キーの一覧({@code min-level} の昇順)。 */
    private static Set<String> allBands() throws Exception {
        Set<String> bands = new TreeSet<>();
        for (Map<?, ?> tier : tiers()) {
            bands.add(bandKey(tier));
        }
        return bands;
    }

    /** 券ID -> その券が載っている帯キーの集合。 */
    private static Map<String, Set<String>> bandsByTicket() throws Exception {
        Map<String, Set<String>> out = new TreeMap<>();
        for (Map<?, ?> tier : tiers()) {
            String band = bandKey(tier);
            for (Map<?, ?> drop : maps(tier.get("add-drops"))) {
                String id = customId(text(drop.get("material")));
                if (id != null && id.startsWith("gacha_ticket_")) {
                    out.computeIfAbsent(id, key -> new TreeSet<>()).add(band);
                }
            }
        }
        return out;
    }

    private static Set<String> ticketIdsInLevelTable() throws Exception {
        Set<String> ids = new TreeSet<>();
        for (Map<?, ?> tier : tiers()) {
            for (Map<?, ?> drop : maps(tier.get("add-drops"))) {
                String id = customId(text(drop.get("material")));
                if (id != null && id.startsWith("gacha_ticket_")) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /** {@code mob-overrides.yml} は入れ子が深いので、{@code drops} という名前の節を全部拾う。 */
    private static Set<String> ticketIdsInMobOverrides() throws Exception {
        Set<String> ids = new TreeSet<>();
        YamlConfiguration cfg = load(MOB_OVERRIDES);
        for (String key : cfg.getKeys(true)) {
            if (!key.equals("drops") && !key.endsWith(".drops")) {
                continue;
            }
            for (Map<?, ?> drop : cfg.getMapList(key)) {
                String id = customId(text(drop.get("item")));
                if (id != null && id.startsWith("gacha_ticket_")) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------------------------------
    // 小道具
    // ------------------------------------------------------------------------------------------

    private static String bandKey(Map<?, ?> tier) {
        return "min-level=" + text(tier.get("min-level"));
    }

    private static String normalize(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** {@code "custom:gacha_ticket_0"} -> {@code "gacha_ticket_0"}。custom: でなければ {@code null}。 */
    private static String customId(String raw) {
        String normalized = normalize(raw);
        if (normalized == null || !normalized.startsWith(CUSTOM_PREFIX)) {
            return null;
        }
        return normalized.substring(CUSTOM_PREFIX.length());
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static List<Map<?, ?>> maps(Object raw) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    out.add(map);
                }
            }
        }
        return out;
    }
}
