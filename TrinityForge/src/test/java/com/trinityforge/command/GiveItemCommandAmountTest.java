package com.trinityforge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.stats.ItemFactory;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /tf give} の個数指定(2026-08-01)。
 *
 * <p>症状: 「個数を引数に取れない」。実際には {@code amount} は
 * {@code /tf give <item> <quality> <amount>} の第3位置に存在していたが、
 * <b>第2位置が {@code quality} に占められている</b>ため、素直に
 * {@code /tf give iron_ingot 64} と打つと 64 が品質として解釈され、
 * さらに {@code quality.maxQuality()}(既定9)へ<b>無言でクランプ</b>されて
 * 「品質9のアイテムが1個」出てくる。数量指定の手段が事実上存在しなかった。
 *
 * <p>ここで担保するのは4本:
 * <ol>
 *   <li>{@code amount} リテラル経由で個数を単独指定できる(新規)</li>
 *   <li>既存の呼び出し形(個数なし/品質のみ/位置指定 amount/プレイヤー指定)が全て無傷</li>
 *   <li>範囲外の品質・個数は<b>エラーで弾かれる</b>(無言クランプしない)</li>
 *   <li>スタック上限を超える個数は複数スタックへ分割される</li>
 * </ol>
 */
class GiveItemCommandAmountTest {

    private ServerMock server;
    private GiveItemCommand command;
    private QualityConfig qualityConfig;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addPlayer("Steve");

        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("GiveItemCommandAmountTest"));

        qualityConfig = new QualityConfig(); // スキーマ既定(max-quality=9 / give-default-quality=3)
        command = new GiveItemCommand(plugin, mock(ItemFactory.class), new ItemCatalogConfig(), qualityConfig);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(command.node());
        return dispatcher;
    }

    // requires() 述語をこのツリーのどのノードも宣言していないため parse() は source を参照しない
    // (StatsCommandDetailTest と同じ idiom)。CommandSourceStack を組み立てずに済む。
    private static List<String> nodeNames(ParseResults<CommandSourceStack> parsed) {
        List<String> names = new ArrayList<>();
        for (ParsedCommandNode<CommandSourceStack> node : parsed.getContext().getNodes()) {
            names.add(node.getNode().getName());
        }
        return names;
    }

    @Test
    @DisplayName("新規: '/tf give <item> amount <n>' で個数だけを指定できる")
    void amountLiteralGivesACountWithoutTouchingQuality() {
        ParseResults<CommandSourceStack> parsed = dispatcher().parse("give iron_ingot amount 64", null);

        assertTrue(parsed.getExceptions().isEmpty(),
                "'give <item> amount <n>' must parse cleanly: " + parsed.getExceptions());
        assertEquals(List.of("give", "item", "amount", "count"), nodeNames(parsed));
        assertInstanceOf(LiteralCommandNode.class, parsed.getContext().getNodes().get(2).getNode(),
                "the 3rd path segment must be the 'amount' LITERAL, not an argument");
        assertEquals(64, parsed.getContext().build("give iron_ingot amount 64")
                .getArgument("count", Integer.class));
    }

    @Test
    @DisplayName("新規: '/tf give <item> amount <n> <player>' で対象プレイヤーも指定できる")
    void amountLiteralAcceptsATargetPlayer() {
        ParseResults<CommandSourceStack> parsed = dispatcher().parse("give iron_ingot amount 64 Steve", null);

        assertTrue(parsed.getExceptions().isEmpty(), "" + parsed.getExceptions());
        assertEquals(List.of("give", "item", "amount", "count", "player"), nodeNames(parsed));
    }

    @Test
    @DisplayName("Brigadier配線: 'amount' リテラルと位置指定の個数引数が同じ親で共存する(名前衝突の罠)")
    void amountLiteralAndPositionalCountCoexistUnderQuality() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();

        // Brigadier の CommandNode#addChild は子を「名前をキーにした1本のマップ」で持つため、
        // リテラルと引数を同名(amount)で同じ親に並べると後から足したほうが無言でマージされて消える。
        // 個数引数を count に改名したことで、キーワード形と位置指定形が両立していることを固定する。
        assertEquals(List.of("give", "item", "quality", "amount", "count"),
                nodeNames(dispatcher.parse("give iron_ingot 5 amount 64", null)));
        assertEquals(List.of("give", "item", "quality", "count"),
                nodeNames(dispatcher.parse("give iron_ingot 5 64", null)));
    }

    @Test
    @DisplayName("回帰: 既存の呼び出し形(個数なし/品質のみ/位置指定amount/プレイヤー)が全て無傷")
    void legacyCallShapesStillResolve() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();

        assertEquals(List.of("give", "item"), nodeNames(dispatcher.parse("give iron_ingot", null)));
        assertEquals(List.of("give", "item", "quality"), nodeNames(dispatcher.parse("give iron_ingot 5", null)));
        assertEquals(List.of("give", "item", "quality", "count"),
                nodeNames(dispatcher.parse("give iron_ingot 5 64", null)));
        assertEquals(List.of("give", "item", "quality", "count", "player"),
                nodeNames(dispatcher.parse("give iron_ingot 5 64 Steve", null)));
        assertEquals(List.of("give", "item", "player"),
                nodeNames(dispatcher.parse("give iron_ingot Steve", null)));
    }

    @Test
    @DisplayName("サジェスト: アイテム名の次に 'amount' が候補として出る(発見可能性)")
    void amountLiteralIsSuggestedAfterTheItemId() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        ParseResults<CommandSourceStack> parsed = dispatcher.parse("give iron_ingot ", null);

        List<String> suggestions = dispatcher.getCompletionSuggestions(parsed).get().getList().stream()
                .map(s -> s.getText())
                .toList();

        assertTrue(suggestions.contains("amount"),
                "the amount keyword must be tab-completable, otherwise it is undiscoverable: " + suggestions);
    }

    @Test
    @DisplayName("無言クランプ禁止: 設定上限を超える品質はエラーで弾かれ、個数指定の書き方を案内する")
    void qualityAboveConfiguredMaxIsRejectedNotClamped() {
        PlayerMock sender = server.addPlayer("Admin");

        int result = command.giveTo(sender, null, "iron_ingot", 64, 1);

        assertEquals(0, result, "an out-of-range quality must fail, not silently clamp");
        String output = String.join("\n", drainMessages(sender));
        assertTrue(output.contains("品質"), "must name the offending argument: " + output);
        assertTrue(output.contains(Integer.toString(qualityConfig.maxQuality())),
                "must state the real configured maximum: " + output);
        assertTrue(output.contains("64"), "must echo the rejected value: " + output);
        assertTrue(output.contains("amount"),
                "must point at the amount syntax — this is exactly the case a user typing a COUNT hits: " + output);
    }

    @Test
    @DisplayName("無言クランプ禁止: 範囲外の個数はエラーで弾かれる(0個・上限超過とも)")
    void amountOutOfRangeIsRejectedNotClamped() {
        PlayerMock sender = server.addPlayer("Admin2");

        assertEquals(0, command.giveTo(sender, null, "iron_ingot", 3, 0));
        String tooFew = String.join("\n", drainMessages(sender));
        assertTrue(tooFew.contains("個数"), "must name the offending argument: " + tooFew);

        assertEquals(0, command.giveTo(sender, null, "iron_ingot", 3, GiveItemCommand.MAX_AMOUNT + 1));
        String tooMany = String.join("\n", drainMessages(sender));
        assertTrue(tooMany.contains("個数"), "must name the offending argument: " + tooMany);
        assertTrue(tooMany.contains(Integer.toString(GiveItemCommand.MAX_AMOUNT)),
                "must state the real maximum: " + tooMany);
    }

    @Test
    @DisplayName("スタック上限超過は複数スタックへ分割される(64+64+2)")
    void amountAboveMaxStackSizeIsSplitAcrossStacks() {
        List<ItemStack> split = GiveItemCommand.splitIntoStacks(new ItemStack(Material.IRON_INGOT), 130);

        assertEquals(List.of(64, 64, 2), split.stream().map(ItemStack::getAmount).toList());
        assertTrue(split.stream().allMatch(s -> s.getType() == Material.IRON_INGOT));
    }

    @Test
    @DisplayName("スタック不可の品は上限を超えたらエラー(丸めない)")
    void unstackableAmountAboveCapIsRejected() {
        assertEquals(null, GiveItemCommand.unstackableRejection(GiveItemCommand.MAX_UNSTACKABLE_AMOUNT),
                "the cap itself must be allowed");
        String rejection = GiveItemCommand.unstackableRejection(GiveItemCommand.MAX_UNSTACKABLE_AMOUNT + 1);
        assertFalse(rejection == null, "over the cap must be rejected, not rounded down");
        assertTrue(rejection.contains(Integer.toString(GiveItemCommand.MAX_UNSTACKABLE_AMOUNT)),
                "must state the real cap: " + rejection);
    }

    private static List<String> drainMessages(PlayerMock player) {
        List<String> messages = new ArrayList<>();
        String msg;
        while ((msg = player.nextMessage()) != null) {
            messages.add(msg);
        }
        return messages;
    }
}
