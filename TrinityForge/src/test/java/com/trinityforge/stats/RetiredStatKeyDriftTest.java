package com.trinityforge.stats;

import com.trinityforge.command.StatsCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ドリフト検知(2026-07-31 ステ語彙の間引き / L7): 3種の廃止・分割を機械的に固定する。
 *
 * <ul>
 *   <li><b>弓CT短縮 {@code bow-cooldown-reduction} の廃止</b>: アイテムCT短縮
 *       ({@code cooldown-reduction}) と同じ {@code Player#setCooldown} を二重に掛ける実装で、
 *       しかも BOW/CROSSBOW に {@code item-cooldown} が無いため残CTが常に0で完全な no-op だった。</li>
 *   <li><b>軽装/重装部位速度の廃止</b>: {@code light-/heavy-armor-move-speed-per-piece} は
 *       スキルツリー由来分しか読まれない専用経路だった。装備部位条件の {@code set-buffs}
 *       ({@code move-speed}) で表現できるため語彙ごと撤去した。</li>
 *   <li><b>品質上振れ/下振れの作業台・儀式分割</b>: {@code craft-upswing-bonus} /
 *       {@code craft-downswing-reduction} は作業台経路と儀式経路の両方に効いていたため、
 *       {@code workbench-*} / {@code ritual-*} の2組へ分けた
 *       ({@code workbench-quality-bonus} / {@code ritual-quality-bonus} と同じ命名)。
 *       ロール3キー({@code craft-roll-*})は分割していない — アイテム PDC
 *       ({@code PdcKeys}) に焼かれており、キー名を変えると流通済みアイテムのロール補正が読めなくなる。</li>
 *   <li><b>マナ基礎3キーの ArsPaper 移設(2026-08-16)</b>: {@code mana-max-base} /
 *       {@code mana-regen-base} / {@code mana-regen-interval-ticks} は ArsPaper の
 *       {@code config.yml} の {@code mana.default-max} / {@code mana.default-regen-rate} /
 *       {@code mana.regen-interval-ticks} へ移設した。TF 側から見ると「語彙・出荷 yml の双方から
 *       消えた」ので、他の廃止キーと同じ枠で固定する(旧キーが base-stats.yml へ戻ると
 *       設定エディタに出ないキーが復活し、また手編集専用に逆戻りする)。
 *       残りのマナ5キー({@code mana-onhit-percent} / {@code mana-onattack-percent} /
 *       {@code mana-idle-seconds} / {@code mana-idle-bonus-percent} / {@code mana-idle-bonus-flat})は
 *       移設対象外で TF 側に残る。</li>
 * </ul>
 *
 * <p>あわせて、近接/矢ノックバックの単位統一(FLAT + {@code m})と
 * {@code melee-knockback} を {@link PercentStatNormalize} の rate キーから外したことも固定する。
 */
class RetiredStatKeyDriftTest {

    /** 語彙・出荷ymlの双方から消えていること(canonical=snake_case)。 */
    private static final List<String> RETIRED_KEYS = List.of(
            "bow_cooldown_reduction",
            "light_armor_move_speed_per_piece",
            "heavy_armor_move_speed_per_piece",
            "craft_upswing_bonus",
            "craft_downswing_reduction",
            // 2026-08-16: ArsPaper の config.yml (mana.*) へ移設。TF 側では廃止キーと同じ扱い。
            "mana_max_base",
            "mana_regen_base",
            "mana_regen_interval_ticks");

    /** 分割後の新キー(GENERAL / StatsCategory.CRAFT)。 */
    private static final List<String> SPLIT_KEYS = List.of(
            "workbench_upswing_bonus", "ritual_upswing_bonus",
            "workbench_downswing_reduction", "ritual_downswing_reduction");

    private static ConfigurationSection section(String resource, String path) throws Exception {
        try (InputStream in = RetiredStatKeyDriftTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "出荷リソース " + resource + " が見つからない");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            ConfigurationSection s = yaml.getConfigurationSection(path);
            assertNotNull(s, resource + " に " + path + ": セクションが無い");
            return s;
        }
    }

    @Test
    @DisplayName("廃止キーは StatVocabulary から消えており、分割後の4キーが GENERAL に居る")
    void retiredKeysAreGoneFromVocabulary() {
        Set<String> known = StatVocabulary.allKeys();
        for (String key : RETIRED_KEYS) {
            assertFalse(known.contains(key), key + " が StatVocabulary に残っている");
        }
        for (String key : SPLIT_KEYS) {
            assertEquals(StatVocabulary.Channel.GENERAL, StatVocabulary.channelOf(key),
                    key + " が GENERAL チャネルに登録されていない");
        }
        // 分割していない側(PDC焼込みのため据え置き)は残っていること。
        assertTrue(known.contains("craft_roll_up_bonus"), "craft_roll_up_bonus まで巻き込み削除している");
        assertTrue(known.contains("craft_roll_down_reduction"), "craft_roll_down_reduction まで巻き込み削除している");
        assertTrue(known.contains("craft_roll_inset"), "craft_roll_inset まで巻き込み削除している");
    }

    @Test
    @DisplayName("廃止キーは StatsCategory のどのカテゴリにも残っていない / 新4キーは CRAFT")
    void retiredKeysAreGoneFromStatsCategory() {
        for (StatsCategory category : EnumSet.complementOf(
                EnumSet.of(StatsCategory.ALL, StatsCategory.OTHER))) {
            for (String key : RETIRED_KEYS) {
                assertFalse(category.includes(key), key + " が StatsCategory." + category + " に残っている");
            }
        }
        for (String key : SPLIT_KEYS) {
            assertTrue(StatsCategory.CRAFT.includes(key), key + " が StatsCategory.CRAFT に無い");
        }
    }

    /**
     * yml のコメントを落とす。「旧 X を廃止した」という経緯コメントは残す方針
     * (docs/agent-context の「誤診・廃止の記録は消さない」ルール)なので、
     * 実データ側にキーが復活していないことだけを見る。
     * {@code MATERIAL#CMD} のようなキー中の {@code #} を壊さないよう、
     * 行頭コメントと「空白+#」以降だけを落とす。
     */
    private static String stripYamlComments(String body) {
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            int idx = line.indexOf(" #");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("廃止キーは出荷リソースの全 yml の実データ(コメント以外)から消えている")
    void retiredKeysAreGoneFromEveryShippedYaml() throws Exception {
        URL anchor = RetiredStatKeyDriftTest.class.getClassLoader().getResource("combat/base-stats.yml");
        assertNotNull(anchor, "combat/base-stats.yml をクラスパスから解決できない");
        Path resourcesRoot = Path.of(anchor.toURI()).getParent().getParent();
        assertTrue(Files.isDirectory(resourcesRoot), "リソースルートの解決に失敗: " + resourcesRoot);

        // 空振り防止のアンカー: コメント除去後も実データが残っていること(現役キーで確認する)。
        String baseStats = stripYamlComments(Files.readString(
                resourcesRoot.resolve("combat/base-stats.yml"), StandardCharsets.UTF_8));
        assertTrue(baseStats.contains("arrow-knockback"),
                "コメント除去が効きすぎて実データまで消えている(この走査は空振りしている)");

        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(resourcesRoot)) {
            for (Path p : walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yml")).toList()) {
                String body = stripYamlComments(Files.readString(p, StandardCharsets.UTF_8));
                for (String key : RETIRED_KEYS) {
                    if (body.contains(key) || body.contains(key.replace('_', '-'))) {
                        hits.add(resourcesRoot.relativize(p) + " -> " + key);
                    }
                }
            }
        }
        assertEquals(List.of(), hits, "廃止したステキーが出荷 yml の実データに残っている");
    }

    @Test
    @DisplayName("分割後の4キーは base-stats.yml と lore.yml の両方にある")
    void splitKeysArePresentInShippedConfigs() throws Exception {
        ConfigurationSection baseStats = section("combat/base-stats.yml", "base-stats");
        ConfigurationSection lore = section("stats/lore.yml", "stats");
        for (String key : SPLIT_KEYS) {
            String kebab = key.replace('_', '-');
            assertTrue(baseStats.contains(kebab), kebab + " が combat/base-stats.yml に無い");
            assertTrue(lore.contains(kebab), kebab + " が stats/lore.yml に無い");
        }
    }

    @Test
    @DisplayName("近接/矢ノックバックは FLAT + 単位 m で揃い、melee-knockback は rate キーではない")
    void knockbackKeysShareFlatMetreUnits() throws Exception {
        ConfigurationSection lore = section("stats/lore.yml", "stats");
        for (String key : List.of("melee-knockback", "arrow-knockback")) {
            ConfigurationSection entry = lore.getConfigurationSection(key);
            assertNotNull(entry, key + " が stats/lore.yml に無い");
            assertEquals("FLAT", entry.getString("format"), key + " の format が FLAT でない");
            assertEquals("m", entry.getString("unit"), key + " の unit が m でない");
        }
        // FLAT にしたまま RATE_KEYS に残すと「2m と書いたら 0.02 に化ける」無言バグになる。
        assertFalse(PercentStatNormalize.isRateKey("melee-knockback"),
                "melee-knockback が RATE_KEYS に残っている(FLAT との組み合わせで値が1/100になる)");
        assertFalse(PercentStatNormalize.isRateKey("arrow-knockback"),
                "arrow-knockback が RATE_KEYS に入っている");
        assertEquals(2.0, PercentStatNormalize.coerce("melee-knockback", 2.0), 1e-9,
                "2(=2m)がそのまま2で通ること");
    }
}
