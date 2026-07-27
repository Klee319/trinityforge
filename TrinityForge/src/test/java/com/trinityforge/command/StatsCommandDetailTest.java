package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.LoreConfig;
import com.trinityforge.progression.SkillLevelSource;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /tf stats detail <key>} (K-5段階3): {@code stats/lore.yml} の {@code trigger:}/{@code limits:}
 * 宣言をプレイヤー向けにゲーム内で読めるようにするサブコマンドのテスト。
 *
 * <p>3本柱:
 * <ol>
 *   <li>宣言済みキー({@code dodge-chance})で発動条件と上限の両方が出力に含まれる</li>
 *   <li>未宣言キー({@code attack-power})で「未宣言」と明示され、でっち上げの説明が出ない</li>
 *   <li>存在しないキーでエラー(+近いキー候補)になる</li>
 * </ol>
 * これに加えて、{@code /tf stats <category>} の既存挙動(回帰)と、Brigadier上で {@code detail}
 * リテラルと {@code category} 引数が曖昧に衝突しないことを確認する。
 */
class StatsCommandDetailTest {

    private static final String LORE_YAML = """
            stats:
              dodge-chance:
                name: 回避率
                icon: ""
                format: PERCENT
                decimals: 0
                order: 66
                show-sign: true
                hide-when-zero: true
                category: defense
                trigger:
                  when: ON_DAMAGE_TAKEN
                  sources: ALL
                  applies-to: [PLAYER, MOB]
                limits:
                  cap: 0.9
                  cap-ref: "combat/damage.yml#defense.max-dodge-chance"
              attack-power:
                name: 攻撃力
                icon: ""
                format: FLAT
                decimals: 1
                order: 1
                show-sign: true
                hide-when-zero: true
                category: attack
            """;

    private ServerMock server;
    private PlayerMock player;
    private StatsCommand command;

    @BeforeEach
    void setUp(@TempDir File dataFolder) throws Exception {
        server = MockBukkit.mock();
        player = server.addPlayer("Steve");

        File loreFile = new File(dataFolder, LoreConfig.PATH);
        Files.createDirectories(loreFile.getParentFile().toPath());
        Files.writeString(loreFile.toPath(), LORE_YAML);

        LoreConfig loreConfig = new LoreConfig();
        boolean loaded = loreConfig.load(fakePlugin(dataFolder));
        assertTrue(loaded, "test fixture lore.yml must load without warnings");

        SymmetricCombatService combatService = mock(SymmetricCombatService.class);
        when(combatService.combatLevelOf(any())).thenReturn(3);

        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        Map<String, Double> item = Map.of("dodge_chance", 0.15, "attack_power", 12.5);
        PlayerCombatAggregate agg = new PlayerCombatAggregate(item, Map.of(), Map.of(), Map.of(), Map.of());
        when(aggregator.aggregate(any())).thenReturn(agg);

        command = new StatsCommand(combatService, aggregator, loreConfig, SkillLevelSource.EMPTY);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("宣言済みキー(dodge-chance): 発動条件と上限の両方が出力に含まれる")
    void declaredKeyShowsTriggerAndLimits() {
        int result = command.detail(player, "dodge-chance");

        assertEquals(Command.SINGLE_SUCCESS, result);
        String output = String.join("\n", drainMessages(player));

        assertTrue(output.contains("回避率"), "displayName must appear: " + output);
        assertTrue(output.contains("dodge-chance"), "raw key must appear: " + output);
        assertTrue(output.contains("現在値"), "current value line must appear: " + output);
        assertTrue(output.contains("+15%"), "current value must be rendered via renderForStats: " + output);

        assertTrue(output.contains("被弾時"), "trigger.when label must appear: " + output);
        assertTrue(output.contains("装備・パーク・アドオン等すべての合算元が対象"), "trigger.sources label must appear: " + output);
        assertTrue(output.contains("プレイヤー") && output.contains("モブ"), "trigger.applies-to labels must appear: " + output);

        assertTrue(output.contains("上限値"), "limits.cap label must appear: " + output);
        assertTrue(output.contains("+90%"), "cap value must be rendered in the stat's own format: " + output);
        assertTrue(output.contains("この数字は実装から取得"), "a cap-ref must be surfaced as implementation-backed: " + output);

        assertFalse(output.contains("まだ登録されていません"), "a declared stat must never show the undeclared message");
    }

    @Test
    @DisplayName("未宣言キー(attack-power): 「未宣言」と明示され、でっち上げの説明が出ない")
    void undeclaredKeyIsHonestAboutMissingDeclaration() {
        int result = command.detail(player, "attack-power");

        assertEquals(Command.SINGLE_SUCCESS, result);
        String output = String.join("\n", drainMessages(player));

        assertTrue(output.contains("攻撃力"), "displayName must still appear: " + output);
        assertTrue(output.contains("このステータスの発動条件はまだ登録されていません。"),
                "must explicitly say the trigger is undeclared: " + output);
        assertTrue(output.contains("このステータスの上限はまだ登録されていません。"),
                "must explicitly say the limits are undeclared: " + output);

        // でっち上げ防止: 宣言済みキー側でしか出ないはずの語彙が紛れ込んでいないこと。
        assertFalse(output.contains("被弾時"), "must not fabricate a trigger.when for an undeclared stat: " + output);
        assertFalse(output.contains("上限値"), "must not fabricate a cap for an undeclared stat: " + output);
    }

    @Test
    @DisplayName("存在しないキー: エラーになり、部分一致する近いキー候補が出る")
    void unknownKeyErrorsWithCandidateSuggestion() {
        int result = command.detail(player, "dodge-chanc"); // typo: missing trailing 'e'

        assertEquals(0, result, "an unknown key must fail (non-SINGLE_SUCCESS)");
        String output = String.join("\n", drainMessages(player));

        assertTrue(output.contains("そのようなステータスキーはありません"), "must report the key as unknown: " + output);
        assertTrue(output.contains("近いキー候補"), "must offer a nearby-key hint: " + output);
        assertTrue(output.contains("dodge-chance"), "the real key must be among the suggested candidates: " + output);
    }

    @Test
    @DisplayName("存在しないキーかつ近い候補も無い場合: エラーのみで候補行は出ない")
    void unknownKeyWithNoCandidatesOmitsSuggestionLine() {
        int result = command.detail(player, "totally-unrelated-xyz");

        assertEquals(0, result);
        String output = String.join("\n", drainMessages(player));

        assertTrue(output.contains("そのようなステータスキーはありません"));
        assertFalse(output.contains("近いキー候補"), "no candidate should be suggested when nothing is close: " + output);
    }

    @Test
    @DisplayName("回帰: /tf stats <category> は detail 追加後も従来どおり動く")
    void categoryFilteredShowStillWorks() {
        int result = command.show(player, StatsCategory.ARMOR);

        assertEquals(Command.SINGLE_SUCCESS, result);
        String output = String.join("\n", drainMessages(player));

        assertTrue(output.contains("TrinityForge Stats"), "header must still be printed: " + output);
        assertTrue(output.contains("[armor]") || output.contains("armor"), "category id must still be echoed: " + output);
        assertTrue(output.contains("回避率"), "armor-category stat must still be listed: " + output);
        assertTrue(output.contains("+15%"), "current value must still be rendered through the same path: " + output);
    }

    @Test
    @DisplayName("Brigadier配線: '/tf stats detail <key>' は category 引数へ吸われず detail リテラルへ解決する")
    void detailLiteralDoesNotCollideWithCategoryArgument() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(command.node());

        // requires() 述語をこのツリーのどのノードも宣言していないため、parse() は source を参照しない
        // (canUse は既定で true を返す no-op) — CommandSourceStack を実際に組み立てずに済む。
        ParseResults<CommandSourceStack> detailParse = dispatcher.parse("stats detail dodge-chance", null);
        assertTrue(detailParse.getExceptions().isEmpty(),
                "'stats detail dodge-chance' must parse cleanly: " + detailParse.getExceptions());
        List<ParsedCommandNode<CommandSourceStack>> detailNodes = detailParse.getContext().getNodes();
        assertEquals("stats", detailNodes.get(0).getNode().getName());
        assertEquals("detail", detailNodes.get(1).getNode().getName(),
                "the second path segment must be the 'detail' literal, not the 'category' argument");
        assertEquals("key", detailNodes.get(2).getNode().getName());

        ParseResults<CommandSourceStack> categoryParse = dispatcher.parse("stats armor", null);
        assertTrue(categoryParse.getExceptions().isEmpty());
        List<ParsedCommandNode<CommandSourceStack>> categoryNodes = categoryParse.getContext().getNodes();
        assertEquals("stats", categoryNodes.get(0).getNode().getName());
        assertEquals("category", categoryNodes.get(1).getNode().getName(),
                "'stats armor' must still resolve through the 'category' argument node");
    }

    @Test
    @DisplayName("サジェスト: detail の key 引数は displayTable の実在キーだけを返す")
    void detailKeySuggestionsAreRealKeysOnly() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(command.node());

        ParseResults<CommandSourceStack> parsed = dispatcher.parse("stats detail ", null);
        List<String> suggestions = dispatcher.getCompletionSuggestions(parsed).get().getList().stream()
                .map(s -> s.getText())
                .toList();

        assertTrue(suggestions.contains("dodge-chance"), "real key must be suggested: " + suggestions);
        assertTrue(suggestions.contains("attack-power"), "real key must be suggested: " + suggestions);
        assertEquals(2, suggestions.size(), "only real displayTable keys must be suggested: " + suggestions);
    }

    private static List<String> drainMessages(PlayerMock player) {
        List<String> messages = new ArrayList<>();
        String msg;
        while ((msg = player.nextMessage()) != null) {
            messages.add(msg);
        }
        return messages;
    }

    /** Reflective fake {@link Plugin}: data folder only, the fixture lore.yml is written before load(). */
    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("StatsCommandDetailTest");
            case "saveResource" -> null; // never triggered: the fixture file already exists on disk
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }
}
