package com.trinityforge.testsupport;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code custom:<id>} として実行時に解決しうる id の全既知集合。
 *
 * <p>もともとは {@code ShippedBrewUnlocksPairDriftTest} だけが持っていたロジックだが、
 * 「出荷 yml のどこかに {@code custom:<id>} 参照があれば、それがどの登録先(素材/レシピ/
 * ドロップ表/醸造/図鑑/EXP表)であっても既知IDで解決できるはず」という不変条件を検査する
 * ガードが複数出てきたので共有ヘルパーへ切り出した。単一の情報源にしておかないと、
 * 片方だけ更新されて「既知IDのはずなのに片方のテストだけ落ちる」というズレが起きる。
 *
 * <h2>CMD 台帳を第一級の証拠にしてはいけない理由(2026-08-16 レビュー指摘で修正)</h2>
 * <p>{@code resourcepack/cmd-registry.json} は CMD 番号を<b>再利用しないための永続台帳</b>で、
 * 「その id が materials/catalog のどちらかに<b>かつて</b>実在した」ことしか意味しない。
 * カタログや materials.yml から id を削除しても、台帳のエントリは残り続ける。
 *
 * <p>そのため台帳を無条件に既知集合へ混ぜると、このガードが検出すべき<b>一番肝心なケース</b>
 * ——「{@code items/catalog.yml} からアイテムを消したのに、それを参照している側が残っている」——
 * を素通りしてしまう。台帳にだけ残っている削除済み id を「今も実在する」と誤認し、
 * 削除の検出そのものを無効化するため。実測(2026-08-16): catalog ∪ materials = 674 件に対し、
 * 台帳にしか無い id が 16 件存在する(= 現に台帳だけが「実在の証拠」だと言い張っている死角)。
 *
 * <h2>2段階の証拠モデル</h2>
 * <ul>
 *   <li><b>{@link Mode#STRICT}</b> — {@code materials.yml} が存在する(実際の作業ワークツリー)。
 *       既知集合は {@code items/catalog.yml} ∪ {@code materials.yml} の id <b>だけ</b>。
 *       これが「今この瞬間に実在する id」の唯一の正しい定義であり、台帳は使わない。</li>
 *   <li><b>{@link Mode#LEDGER_FALLBACK}</b> — {@code materials.yml} が無い(通常のクローン/CI/
 *       新しい worktree。ArsPaper フォークのソースは {@code .gitignore} 除外なので存在しない)。
 *       materials 側の id を直接検証できないため、やむを得ず台帳を混ぜて「かつて実在した」ことを
 *       弱い証拠として使う。<b>このモードでは削除検出が効かない</b>ことを呼び出し側が
 *       {@link Result#mode()} で読み取り、失敗メッセージに残せるようにしている。</li>
 * </ul>
 *
 * <h2>読む3本と、その理由</h2>
 * <ul>
 *   <li><b>{@code items/catalog.yml}</b> — TF カタログ(出荷 yml)。ここに書かれた id は
 *       {@code trinityforge:catalog_id} で一致する。両モード共通で読む。</li>
 *   <li><b>{@code fork-handoff/arspaper/fork/src/main/resources/materials.yml}</b> —
 *       ArsPaper 側の {@code arspaper:custom_item_id} 定義元。存在するときは
 *       {@link Mode#STRICT} の証拠源になる。</li>
 *   <li><b>{@code resourcepack/cmd-registry.json}</b> — CMD 割り当て台帳。{@code source} が
 *       {@code catalog} / {@code materials} のどちらのレジストリに属する id かまで記録している
 *       <b>唯一の tracked な一覧</b>だが、上記のとおり「今も実在する」ことの証拠にはならないため
 *       {@link Mode#LEDGER_FALLBACK} でのみ、綴り間違い・定義忘れを拾う目的で補助的に混ぜる。</li>
 * </ul>
 */
public final class KnownCustomItemIds {

    private static final File CATALOG = new File("src/main/resources/items/catalog.yml");
    private static final File CMD_REGISTRY = new File("../resourcepack/cmd-registry.json");
    private static final File ARS_MATERIALS =
            new File("../fork-handoff/arspaper/fork/src/main/resources/materials.yml");

    /** どちらの証拠モードで既知集合を組み立てたか。失敗メッセージで検査の強さを開示するために使う。 */
    public enum Mode {
        /** {@code materials.yml} あり。catalog ∪ materials のみ(削除検出が効く)。 */
        STRICT,
        /** {@code materials.yml} 無し。catalog ∪ ledger にフォールバック(削除検出が効かない)。 */
        LEDGER_FALLBACK
    }

    /** 既知集合とモードの組。{@code toString()} は失敗メッセージにそのまま埋め込める説明文になる。 */
    public record Result(Set<String> ids, Mode mode) {
        public boolean isStrict() {
            return mode == Mode.STRICT;
        }

        /** 失敗メッセージに添える1行。モードごとに検査の強さが違うことを明示する。 */
        public String describe() {
            return mode == Mode.STRICT
                    ? "STRICT(catalog ∪ materials, " + ids.size() + " 件) — カタログからの削除も検出対象"
                    : "LEDGER_FALLBACK(catalog ∪ ledger, " + ids.size() + " 件) — materials.yml が無いため"
                            + "台帳にフォールバック。カタログから削除済みの id も既知として通ってしまう";
        }
    }

    private KnownCustomItemIds() {
    }

    /** {@code custom:<id>} として解決しうる id の集合(モード込み)。実ファイルから組み立てる既定経路。 */
    public static Result load() {
        return loadFrom(CATALOG, ARS_MATERIALS, CMD_REGISTRY);
    }

    /**
     * {@link #load()} の本体。ファイルを引数で受け取れるようにしているのは、
     * 「{@code materials.yml} が無いときは台帳フォールバックで検査が弱くなる」という
     * 挙動そのものをテストで固定するため(2026-08-16 レビュー指摘の追加要望)。
     * {@code materials} に実在しないパスを渡せば {@link Mode#LEDGER_FALLBACK} を強制でき、
     * 台帳にしか無い(=カタログ/materials から削除済みの) id がそのモードでだけ通ることを示せる。
     */
    public static Result loadFrom(File catalog, File materials, File ledger) {
        Set<String> catalogIds = readCatalog(catalog);

        if (materials.isFile()) {
            Set<String> ids = new LinkedHashSet<>(catalogIds);
            ids.addAll(readMaterials(materials));
            return new Result(ids, Mode.STRICT);
        }

        Set<String> ids = new LinkedHashSet<>(catalogIds);
        ids.addAll(readLedger(ledger));
        return new Result(ids, Mode.LEDGER_FALLBACK);
    }

    private static Set<String> readCatalog(File catalog) {
        assertTrue(catalog.isFile(), "出荷 catalog.yml が見つからない: " + catalog.getAbsolutePath());
        ConfigurationSection items = YamlConfiguration.loadConfiguration(catalog)
                .getConfigurationSection("items");
        assertNotNull(items, "catalog.yml に items セクションが無い");
        return new LinkedHashSet<>(items.getKeys(false));
    }

    private static Set<String> readMaterials(File materials) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?m)^  ([A-Za-z0-9_]+):\\s*$").matcher(read(materials));
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private static Set<String> readLedger(File ledger) {
        assertTrue(ledger.isFile(), "CMD 台帳が見つからない: " + ledger.getAbsolutePath());
        Set<String> ids = new LinkedHashSet<>();
        // JSON パーサを持ち込まずに "id": "<value>" だけを拾う(台帳は生成物なので形が安定している)。
        Matcher matcher = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(read(ledger));
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private static String read(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AssertionError("読めない: " + file.getAbsolutePath(), ex);
        }
    }
}
