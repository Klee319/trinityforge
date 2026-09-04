package com.trinityforge.combat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ActionBarRouter} の調停ロジック（機構4）の回帰テスト。
 *
 * <p>Bukkit の {@code sendActionBar} を挟まず、sink を直接検証する（{@link Player} は Mockito モック、
 * {@code nowMillis} は {@code long[]} で手動進行）。
 */
class ActionBarRouterTest {

    private long[] clock;
    private List<Component> sent;
    private ActionBarRouter router;
    private Player viewer;

    @BeforeEach
    void setUp() {
        clock = new long[] {0L};
        sent = new ArrayList<>();
        router = new ActionBarRouter(() -> clock[0], (player, component) -> sent.add(component));
        viewer = mock(Player.class);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
    }

    private String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("予告中に SKILL_EXP を送っても sink へ届かず false")
    void telegraphBlocksSkillExp() {
        router.telegraphUpdate(viewer, "t1", false, 1000L, Component.text("予告"));
        sent.clear();

        boolean result = router.send(viewer, ActionBarRouter.Priority.SKILL_EXP, Component.text("+1 EXP"));

        assertFalse(result);
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("致命と通常が同時なら致命の行が出て、末尾に +1 が付く")
    void lethalWinsOverNormalWithSuffix() {
        router.telegraphUpdate(viewer, "normal", false, 500L, Component.text("通常技"));
        router.telegraphUpdate(viewer, "lethal", true, 2000L, Component.text("致命技"));

        String last = plain(sent.get(sent.size() - 1));
        assertTrue(last.contains("致命技"), last);
        assertTrue(last.contains("+1"), last);
    }

    @Test
    @DisplayName("通常2本なら着弾が近い方が出る")
    void closerNormalTelegraphWinsAmongEqualWeight() {
        router.telegraphUpdate(viewer, "far", false, 3000L, Component.text("遠い技"));
        router.telegraphUpdate(viewer, "near", false, 1000L, Component.text("近い技"));

        String last = plain(sent.get(sent.size() - 1));
        assertTrue(last.contains("近い技"), last);
        assertTrue(last.contains("+1"), last);
    }

    @Test
    @DisplayName("telegraphEnd 後は send(SKILL_EXP) が届く（復帰）")
    void endingTelegraphRestoresLowerPriority() {
        router.telegraphUpdate(viewer, "t1", false, 1000L, Component.text("予告"));
        router.telegraphEnd(viewer, "t1");
        sent.clear();

        boolean result = router.send(viewer, ActionBarRouter.Priority.SKILL_EXP, Component.text("+1 EXP"));

        assertTrue(result);
        assertEquals(1, sent.size());
        assertEquals("+1 EXP", plain(sent.get(0)));
    }

    @Test
    @DisplayName("notice 後 800ms 未満は send(WARNING) が捨てられ、800ms 経過後は届く")
    void noticeBlocksLowerPriorityFor800ms() {
        router.notice(viewer, Component.text("詠唱不発"));
        sent.clear();

        clock[0] = 799L;
        boolean tooEarly = router.send(viewer, ActionBarRouter.Priority.WARNING, Component.text("警告"));
        assertFalse(tooEarly);
        assertTrue(sent.isEmpty());

        clock[0] = 800L;
        boolean afterWindow = router.send(viewer, ActionBarRouter.Priority.WARNING, Component.text("警告"));
        assertTrue(afterWindow);
        assertEquals(1, sent.size());
    }

    @Test
    @DisplayName("期限切れ（resolveAt+1000ms超）の予告は自動で消え、EXPが届く")
    void expiredTelegraphIsPurgedAutomatically() {
        router.telegraphUpdate(viewer, "t1", false, 1000L, Component.text("予告"));

        clock[0] = 1000L + 1000L + 1L; // resolveAt + 猶予を超える
        sent.clear();

        boolean result = router.send(viewer, ActionBarRouter.Priority.SKILL_EXP, Component.text("+1 EXP"));

        assertTrue(result);
        assertEquals(1, sent.size());
    }

    @Test
    @DisplayName("telegraphLine: バー段数が remaining/total に応じて決まる")
    void telegraphLineSegmentCounts() {
        assertEquals(5, countFilled(ActionBarRouter.telegraphLine("<red>震脚</red>", "shin", 1000L, 1000L)));
        assertEquals(3, countFilled(ActionBarRouter.telegraphLine("<red>震脚</red>", "shin", 500L, 1000L)));
        assertEquals(1, countFilled(ActionBarRouter.telegraphLine("<red>震脚</red>", "shin", 1L, 1000L)));
    }

    @Test
    @DisplayName("telegraphLine: 色が display-name の色を継承する")
    void telegraphLineInheritsDisplayNameColor() {
        Component line = ActionBarRouter.telegraphLine("<red>震脚</red>", "shin", 1000L, 1000L);
        assertEquals(NamedTextColor.RED, line.color());
    }

    @Test
    @DisplayName("telegraphEnd: 予告が0本になったらComponent.emptyを1回送って表示を消す(追加指示8)")
    void telegraphEndSendsEmptyComponentWhenTheLastTelegraphEnds() {
        router.telegraphUpdate(viewer, "t1", false, 1000L, Component.text("予告"));
        sent.clear();

        router.telegraphEnd(viewer, "t1");

        assertEquals(1, sent.size(), "最終フレームを消すための空コンポーネントが送られていない");
        assertEquals("", plain(sent.get(0)));
    }

    @Test
    @DisplayName("telegraphEnd: 他の予告がまだ残っていれば空コンポーネントを送らない")
    void telegraphEndDoesNotClearWhenAnotherTelegraphRemains() {
        router.telegraphUpdate(viewer, "t1", false, 1000L, Component.text("予告1"));
        router.telegraphUpdate(viewer, "t2", false, 2000L, Component.text("予告2"));
        sent.clear();

        router.telegraphEnd(viewer, "t1");

        assertTrue(sent.stream().noneMatch(c -> plain(c).isEmpty()),
                "他の予告が残っているのに空コンポーネントで消してしまった");
    }

    @Test
    @DisplayName("telegraphEnd: 登録の無いキーを外しても何も送らない(多重解放の許容)")
    void telegraphEndOnUnknownKeyIsANoOp() {
        router.telegraphUpdate(viewer, "t1", false, 1000L, Component.text("予告"));
        sent.clear();

        router.telegraphEnd(viewer, "unknown-key");

        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("telegraphLine(行動語付き): [語] 名前 バー 残り秒 の形になる(追加指示5)")
    void telegraphLineWithResponseWordFormatsBracketAndSeconds() {
        Component line = ActionBarRouter.telegraphLine("横へ", "<red>震脚</red>", "shin", 1300L, 2000L);
        String text = plain(line);
        assertTrue(text.startsWith("[横へ] "), text);
        assertTrue(text.contains("震脚"), text);
        assertTrue(text.endsWith("1.3s"), text);
    }

    @Test
    @DisplayName("send(TELEGRAPH, ...) は IllegalArgumentException")
    void sendRejectsTelegraphPriorities() {
        assertThrows(IllegalArgumentException.class,
                () -> router.send(viewer, ActionBarRouter.Priority.TELEGRAPH, Component.text("x")));
        assertThrows(IllegalArgumentException.class,
                () -> router.send(viewer, ActionBarRouter.Priority.TELEGRAPH_LETHAL, Component.text("x")));
    }

    private static int countFilled(Component line) {
        String plain = PlainTextComponentSerializer.plainText().serialize(line);
        int count = 0;
        for (char c : plain.toCharArray()) {
            if (c == '▮') {
                count++;
            }
        }
        return count;
    }
}
