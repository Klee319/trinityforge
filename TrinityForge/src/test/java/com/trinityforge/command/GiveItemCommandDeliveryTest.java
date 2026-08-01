package com.trinityforge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.pdc.BindType;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /tf give} の<b>実行</b>テスト(2026-08-01 追加)。
 *
 * <p>それまで {@code GiveItemCommandAmountTest} は Brigadier のパース結果と静的ヘルパしか見ておらず、
 * 「{@code amount <n>} と打ったら本当に n 個手元に来るのか」を通す実行経路が<b>1本も無かった</b> ——
 * 実際、本番の個数付与ロジック({@code splitIntoStacks} の呼び出し / スタック不可品のループ /
 * 受け渡し)をミューテーションで壊しても全テストが緑のままだった。
 *
 * <p>ここでは {@link CommandDispatcher#execute} でコマンド文字列から実際にインベントリまで通す。
 * {@code CommandSourceStack} は {@code getSender()} しか使われないので Mockito で足りる
 * (このツリーは {@code requires()} を宣言していないため {@code canUse} も参照されない)。
 */
class GiveItemCommandDeliveryTest {

    /** スタック可能(最大64)の検証用カタログ品。 */
    private static final String STACKABLE_ID = "tf_test_ingot";
    /** スタック不可(最大1)の検証用カタログ品。1個ずつ独立ロールで組む経路を通す。 */
    private static final String UNSTACKABLE_ID = "tf_test_sword";

    private ServerMock server;
    private PlayerMock admin;
    private PlayerMock steve;
    private ItemFactory factory;
    private QualityConfig qualityConfig;
    private GiveItemCommand command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        admin = server.addPlayer("Admin");
        steve = server.addPlayer("Steve");

        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("GiveItemCommandDeliveryTest"));

        ItemTemplate ingot = new ItemTemplate(STACKABLE_ID, Material.IRON_INGOT,
                "<white>テスト鉄インゴット</white>", 0, BindType.TRADEABLE, 0, null);
        ItemTemplate sword = new ItemTemplate(UNSTACKABLE_ID, Material.NETHERITE_SWORD,
                "<white>テスト剣</white>", 0, BindType.TRADEABLE, 0, null);

        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.template(STACKABLE_ID)).thenReturn(Optional.of(ingot));
        when(catalog.template(UNSTACKABLE_ID)).thenReturn(Optional.of(sword));
        when(catalog.all()).thenReturn(Map.of(STACKABLE_ID, ingot, UNSTACKABLE_ID, sword));

        factory = mock(ItemFactory.class);
        // 呼ばれるたびに別インスタンスを返す(スタック不可品は1個ずつ独立に組み立てられる契約)。
        when(factory.create(eq(ingot), anyLong(), anyInt()))
                .thenAnswer(invocation -> new ItemStack(Material.IRON_INGOT));
        when(factory.create(eq(sword), anyLong(), anyInt()))
                .thenAnswer(invocation -> new ItemStack(Material.NETHERITE_SWORD));

        qualityConfig = new QualityConfig(); // ティア未配線なので max-quality=9(スキーマ既定)
        command = new GiveItemCommand(plugin, factory, catalog, qualityConfig);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** コマンド文字列を実行して戻り値を返す。送信者は {@code Admin}。 */
    private int run(String command) throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(this.command.node());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(admin);
        return dispatcher.execute(command, source);
    }

    private static int totalOf(PlayerMock player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private static List<Integer> stackSizesOf(PlayerMock player, Material material) {
        List<Integer> sizes = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                sizes.add(stack.getAmount());
            }
        }
        return sizes;
    }

    private static List<String> drainMessages(PlayerMock player) {
        List<String> messages = new ArrayList<>();
        String message;
        while ((message = player.nextMessage()) != null) {
            messages.add(message);
        }
        return messages;
    }

    // ---- (A) 個数が実際に渡ることの担保 ----

    @Test
    @DisplayName("amount <n> で指定した個数がそのまま相手のインベントリに入る(スタック上限をまたぐ)")
    void amountDeliversExactlyThatManyItemsAcrossStacks() throws Exception {
        assertEquals(1, run("give " + STACKABLE_ID + " amount 130 Steve"));

        assertEquals(130, totalOf(steve, Material.IRON_INGOT), "指定した個数がそのまま届くこと");
        assertEquals(List.of(64, 64, 2), stackSizesOf(steve, Material.IRON_INGOT),
                "最大スタック単位に割って配ること");
        assertEquals(0, totalOf(admin, Material.IRON_INGOT), "対象を指定したら送信者には渡らない");
    }

    @Test
    @DisplayName("amount <n> でプレイヤーを省略すると送信者本人に n 個渡る")
    void amountWithoutTargetDeliversToTheSender() throws Exception {
        assertEquals(1, run("give " + STACKABLE_ID + " amount 5"));

        assertEquals(5, totalOf(admin, Material.IRON_INGOT));
        assertEquals(List.of(5), stackSizesOf(admin, Material.IRON_INGOT));
    }

    @Test
    @DisplayName("n=1(下限)でもちょうど1個(既定は個数なし=1個と同じ)")
    void amountOfOneDeliversExactlyOne() throws Exception {
        assertEquals(1, run("give " + STACKABLE_ID + " amount 1"));
        assertEquals(1, totalOf(admin, Material.IRON_INGOT));

        assertEquals(1, run("give " + STACKABLE_ID));
        assertEquals(2, totalOf(admin, Material.IRON_INGOT), "個数省略は1個 = 合計2個");
    }

    @Test
    @DisplayName("位置指定形 '<品質> <個数>' でも同じ個数が渡る(後方互換)")
    void positionalCountStillDelivers() throws Exception {
        assertEquals(1, run("give " + STACKABLE_ID + " 5 70 Steve"));

        assertEquals(70, totalOf(steve, Material.IRON_INGOT));
        assertEquals(List.of(64, 6), stackSizesOf(steve, Material.IRON_INGOT));
    }

    @Test
    @DisplayName("スタック不可の品は n 個ぶん独立に組み立てられ、n スタック届く")
    void unstackableItemsAreBuiltOneByOne() throws Exception {
        assertEquals(1, run("give " + UNSTACKABLE_ID + " amount 3 Steve"));

        assertEquals(3, totalOf(steve, Material.NETHERITE_SWORD));
        assertEquals(List.of(1, 1, 1), stackSizesOf(steve, Material.NETHERITE_SWORD),
                "setAmount で1スタックに詰めると同一ロールの複製になる");
        // 「1個ずつ独立ロール」の契約: 組み立ては個数ぶん呼ばれていなければならない。
        verify(factory, times(3)).create(org.mockito.ArgumentMatchers.any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("スタック不可の品は上限超過で1個も配らない(丸めない)")
    void unstackableOverTheCapDeliversNothing() throws Exception {
        assertEquals(0, run("give " + UNSTACKABLE_ID + " amount "
                + (GiveItemCommand.MAX_UNSTACKABLE_AMOUNT + 1) + " Steve"));

        assertEquals(0, totalOf(steve, Material.NETHERITE_SWORD), "全か無か: 1個も配らない");
        assertTrue(String.join("\n", drainMessages(admin))
                        .contains(Integer.toString(GiveItemCommand.MAX_UNSTACKABLE_AMOUNT)),
                "実際の上限を伝えること");
    }

    // ---- (C) 個数の範囲エラーが本番経路から到達する ----

    @Test
    @DisplayName("個数0は Brigadier ではなく自前の日本語エラーで弾かれ、1個も配らない(実経路)")
    void zeroAmountIsRejectedThroughTheRealCommandPath() throws Exception {
        assertEquals(0, run("give " + STACKABLE_ID + " amount 0 Steve"));

        assertEquals(0, totalOf(steve, Material.IRON_INGOT));
        String output = String.join("\n", drainMessages(admin));
        assertTrue(output.contains("個数"), "個数側のエラーが本番経路から出ること: " + output);
        assertTrue(output.contains(Integer.toString(GiveItemCommand.MIN_AMOUNT))
                && output.contains(Integer.toString(GiveItemCommand.MAX_AMOUNT)),
                "実際の範囲を伝えること: " + output);
    }

    @Test
    @DisplayName("個数が上限超過でも自前のエラーで弾かれる(実経路)")
    void amountAboveTheCapIsRejectedThroughTheRealCommandPath() throws Exception {
        assertEquals(0, run("give " + STACKABLE_ID + " amount " + (GiveItemCommand.MAX_AMOUNT + 1)));

        assertEquals(0, totalOf(admin, Material.IRON_INGOT));
        assertTrue(String.join("\n", drainMessages(admin)).contains("個数"));
    }

    @Test
    @DisplayName("負の個数も自前のエラーで弾かれる(パーサで縛っていないことの裏返し)")
    void negativeAmountIsRejectedThroughTheRealCommandPath() throws Exception {
        assertEquals(0, run("give " + STACKABLE_ID + " amount -5"));

        assertEquals(0, totalOf(admin, Material.IRON_INGOT));
        assertTrue(String.join("\n", drainMessages(admin)).contains("個数"));
    }

    // ---- (B) 品質の実効上限は config から引く ----

    @Test
    @DisplayName("品質の許容範囲は config の実効上限に追随する(定数を焼き込まない)")
    void qualityBoundFollowsTheConfiguredEffectiveMaximum() throws Exception {
        // ティア未配線: max-quality=9。品質9は通り10は弾かれる。
        assertEquals(1, run("give " + STACKABLE_ID + " 9 amount 1"));
        assertEquals(1, totalOf(admin, Material.IRON_INGOT));
        assertEquals(0, run("give " + STACKABLE_ID + " 10 amount 1"));
        assertTrue(String.join("\n", drainMessages(admin)).contains("9"));

        // ティアを配線すると実効上限がそちらへ移る。同じ品質10が今度は通る。
        qualityConfig.useEffectiveMaxOverride(() -> 12);
        assertEquals(1, run("give " + STACKABLE_ID + " 10 amount 1"));
        assertEquals(2, totalOf(admin, Material.IRON_INGOT));
        assertEquals(0, run("give " + STACKABLE_ID + " 13 amount 1"));
        assertTrue(String.join("\n", drainMessages(admin)).contains("12"),
                "エラー文にも config 由来の上限が出ること");
    }

    @Test
    @DisplayName("出荷 config の実効品質上限(ティア数-1)が give の許容範囲になる — 9ではない")
    void shippedTierCountDrivesTheAcceptedQualityRange() throws Exception {
        int shippedMax = shippedEffectiveMaxQuality();
        assertTrue(shippedMax > 9,
                "出荷 quality-tiers.yml は9より多い段数を持つ想定(現在" + shippedMax + ")。"
                        + "『既定9』という前提でコードやメッセージを書かないこと");

        qualityConfig.useEffectiveMaxOverride(() -> shippedMax);
        assertEquals(1, run("give " + STACKABLE_ID + " " + shippedMax + " amount 1"),
                "出荷 config の実効上限ちょうどは通ること");
        assertEquals(1, totalOf(admin, Material.IRON_INGOT));

        assertEquals(0, run("give " + STACKABLE_ID + " " + (shippedMax + 1) + " amount 1"));
        assertTrue(String.join("\n", drainMessages(admin)).contains(Integer.toString(shippedMax)));
    }

    /** 出荷 {@code stats/quality-tiers.yml} のティア数-1(= 実効品質上限)。 */
    private static int shippedEffectiveMaxQuality() throws Exception {
        try (InputStream in = GiveItemCommandDeliveryTest.class.getClassLoader()
                .getResourceAsStream("stats/quality-tiers.yml")) {
            assertNotNull(in, "出荷リソース stats/quality-tiers.yml が見つからない");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml.getMapList("tiers").size() - 1;
        }
    }

    // ---- (D) 救済ヒントは必ず「打てるコマンド」 ----

    @Test
    @DisplayName("個数のつもりの数字が範囲内なら、その値を埋めた実行可能なコマンドを案内する")
    void rescueHintEmbedsTheValueWhenItIsAValidAmount() throws Exception {
        assertEquals(0, run("give " + STACKABLE_ID + " 130"));

        String output = String.join("\n", drainMessages(admin));
        assertTrue(output.contains("amount 130"), "範囲内の値はそのまま案内してよい: " + output);
    }

    @Test
    @DisplayName("範囲外の数字は案内文に埋め込まない(打てないコマンドを案内しない)")
    void rescueHintOmitsAnOutOfRangeValue() throws Exception {
        assertEquals(0, run("give " + STACKABLE_ID + " 99999"));

        String output = String.join("\n", drainMessages(admin));
        assertFalse(output.contains("amount 99999"),
                "上限超過の値を埋めた案内はそのまま打っても失敗する: " + output);
        assertTrue(output.contains("amount <個数>"), "書き方は案内すること: " + output);
        assertTrue(output.contains(Integer.toString(GiveItemCommand.MAX_AMOUNT)),
                "上限を伝えること: " + output);
    }

    @Test
    @DisplayName("桁あふれする数字でも案内文が壊れない")
    void rescueHintSurvivesAnOverflowingNumber() {
        String hint = GiveItemCommand.amountRescueHint("999999999999999999999");
        assertFalse(hint.contains("999999999999999999999"));
        assertTrue(hint.contains("amount <個数>"));
    }

    // ---- (E) amount キーワードは大文字小文字を無視する ----

    @Test
    @DisplayName("'Amount' でも個数として通る(統合版/スマホの自動大文字化)")
    void capitalizedAmountKeywordStillGivesACount() throws Exception {
        assertEquals(1, run("give " + STACKABLE_ID + " Amount 7 Steve"));
        assertEquals(7, totalOf(steve, Material.IRON_INGOT));
    }

    @Test
    @DisplayName("'AMOUNT' / 'aMoUnT' でも通る、品質の後ろでも通る")
    void anyCasingOfTheAmountKeywordWorks() throws Exception {
        assertEquals(1, run("give " + STACKABLE_ID + " AMOUNT 3"));
        assertEquals(3, totalOf(admin, Material.IRON_INGOT));

        assertEquals(1, run("give " + STACKABLE_ID + " aMoUnT 4"));
        assertEquals(7, totalOf(admin, Material.IRON_INGOT));

        assertEquals(1, run("give " + STACKABLE_ID + " 5 Amount 2"));
        assertEquals(9, totalOf(admin, Material.IRON_INGOT));
    }

    @Test
    @DisplayName("個数を伴わない 'Amount' はプレイヤー名扱いせず、書き方を案内する")
    void bareMiscasedKeywordIsNotTreatedAsAPlayerName() throws Exception {
        assertEquals(0, run("give " + STACKABLE_ID + " Amount"));

        String output = String.join("\n", drainMessages(admin));
        assertFalse(output.contains("Player not online"),
                "キーワードをプレイヤー名として扱ってはいけない: " + output);
        assertTrue(output.contains("amount"), "個数の書き方を案内すること: " + output);
    }

    @Test
    @DisplayName("プレイヤー名の後ろに個数を書いたら、構文エラーでなく日本語で正しい形を案内する")
    void playerNameFollowedByACountIsExplained() throws Exception {
        assertEquals(0, run("give " + STACKABLE_ID + " Steve 12"));

        assertEquals(0, totalOf(steve, Material.IRON_INGOT));
        String output = String.join("\n", drainMessages(admin));
        assertTrue(output.contains("Steve") && output.contains("amount"),
                "何が悪くてどう書くのかを両方伝えること: " + output);
    }
}
