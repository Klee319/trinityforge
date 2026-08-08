package com.trinityforge.listeners;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 召喚モブの判別に使う PDC キーが、TF と ArsPaper フォークで一致していることの契約テスト。
 *
 * <p><b>なぜ要るか</b>: TF は召喚モブを「{@code arspaper:summoned} が刻まれているか」だけで判別する。
 * フォーク側でキー名を変えても<b>コンパイルは通り、例外も出ず、ただ召喚モブのレベルが
 * 元の Lv0 に戻る</b>だけなので、実機で気づくまで誰も分からない。文字列で繋がっている以上
 * 実行時一致しか検証できないため、ここでソースを突き合わせる。
 *
 * <p><b>フォーク不在時の扱い</b>: {@code fork-handoff/arspaper/fork} は {@code .gitignore} 対象で、
 * クリーンなクローンや新しい worktree には<b>存在しない</b>。そこで落とすと「フォークを持っていない」
 * だけで赤くなるので、突き合わせはフォークがある環境でのみ行う。
 * TF 側の定数そのものは常に検証する（片側だけでも固定されていれば、
 * 変更したときに必ずこのテストの明示的な更新を伴う）。
 */
class SummonedMobPdcKeyContractTest {

    /** フォークの {@code SummonedMobListener} が {@code new NamespacedKey(plugin, ...)} で作るキー名。 */
    private static final String SUMMONED = "summoned";
    private static final String SUMMONER_UUID = "summoner_uuid";

    private static final Path FORK_LISTENER = Path.of("..", "fork-handoff", "arspaper", "fork",
            "src", "main", "java", "com", "arspaper", "spell", "SummonedMobListener.java");

    @Test
    @DisplayName("TF 側の PDC キーは arspaper 名前空間の summoned / summoner_uuid")
    void trinityForgeLooksUpTheArsPaperNamespace() {
        assertEquals("arspaper", MobTypeSpawnListener.ARS_SUMMONED_KEY.namespace());
        assertEquals(SUMMONED, MobTypeSpawnListener.ARS_SUMMONED_KEY.value());
        assertEquals("arspaper", MobTypeSpawnListener.ARS_SUMMONER_UUID_KEY.namespace());
        assertEquals(SUMMONER_UUID, MobTypeSpawnListener.ARS_SUMMONER_UUID_KEY.value());
    }

    @Test
    @DisplayName("フォークがある環境では、フォーク側も同じキー名を作っている")
    void theForkStillStampsTheSameKeys() throws IOException {
        if (!Files.isRegularFile(FORK_LISTENER)) {
            // フォークは .gitignore 対象。持っていない環境では突き合わせる相手がいない。
            return;
        }
        String source = Files.readString(FORK_LISTENER, StandardCharsets.UTF_8);
        assertTrue(source.contains("\"" + SUMMONED + "\""),
                "フォークが '" + SUMMONED + "' を刻まなくなった。TF 側の召喚モブ判別が"
                        + "無言で効かなくなるので、MobTypeSpawnListener.ARS_SUMMONED_KEY も直すこと");
        assertTrue(source.contains("\"" + SUMMONER_UUID + "\""),
                "フォークが '" + SUMMONER_UUID + "' を刻まなくなった。召喚者が引けなくなるので"
                        + "召喚モブのレベルは距離ベースへ落ちる（＝修正前の Lv0 に戻る）");
    }
}
