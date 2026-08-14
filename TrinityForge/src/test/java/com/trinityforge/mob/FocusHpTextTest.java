package com.trinityforge.mob;

import com.trinityforge.combat.DefenseStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusHpTextTest {

    @BeforeEach
    void setUp() {
        // EntityType#translationKey() (used by componentOverloadUsesTranslatableForSpeciesNameFallback)
        // resolves through Bukkit.getServer().getUnsafe(), which needs a mocked server to be non-null.
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void formatsPlainLayout() {
        assertEquals("Lv.5 Zombie\n12 / 20", FocusHpText.formatPlain(5, "Zombie", 12, 20));
    }

    @Test
    void formatsLevelZero() {
        assertEquals("Lv.0 ZOMBIE\n1 / 1", FocusHpText.formatPlain(0, "ZOMBIE", 1, 1));
    }

    @Test
    void componentContainsNameAndHp() {
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(FocusHpText.format(3, "Skeleton", 8, 20));
        assertTrue(plain.contains("Lv.3"));
        assertTrue(plain.contains("Skeleton"));
        assertTrue(plain.contains("8"));
        assertTrue(plain.contains("20"));
    }

    // --- 2026-07-25バグ修正: 種族名フォールバックが内部ID("ZOMBIE")でなく翻訳可能Componentになること ---
    @Test
    void componentOverloadUsesTranslatableForSpeciesNameFallback() {
        Component nameComponent = Component.translatable(EntityType.ZOMBIE);
        Component result = FocusHpText.format(3, nameComponent, 8, 20);
        // ネストされたtranslatableは平文シリアライザでは空文字になるため、直接componentツリーを検証する。
        assertTrue(containsTranslatable(result, "entity.minecraft.zombie"),
                "must embed the EntityType's own translation key, not a hardcoded English/ID string");
    }

    // --- カスタム名(ネームタグ/EliteMobsボス等)は種族名へフォールバックせずそのまま優先されること ---
    @Test
    void componentOverloadPreservesCustomNameOverSpeciesFallback() {
        Component customName = Component.text("闇の帝王ゾグラス", NamedTextColor.LIGHT_PURPLE);
        Component result = FocusHpText.format(10, customName, 50, 500);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(result);
        assertTrue(plain.contains("闇の帝王ゾグラス"), "custom name text must appear verbatim");
        assertTrue(!containsTranslatable(result, "entity.minecraft."),
                "must NOT fall back to a species translation key when a custom name is supplied");
    }

    // --- 依頼2 (2026-08-02): 耐性寄りタグの導出とレイアウト ---
    @Test
    void leanFromReturnsNoneForUnconfiguredMob() {
        assertEquals(FocusHpText.ResistanceLean.NONE,
                FocusHpText.leanFrom(DefenseStats.NONE, DefenseStats.NONE),
                "a mob with no configured defense profile (all-zero, e.g. no PDC stamp) must not show a tag");
    }

    @Test
    void leanFromReturnsNoneWhenBothComponentsAreBalanced() {
        DefenseStats balanced = new DefenseStats(0.3, 0.2, 0.1, 5.0, 0.0);
        assertEquals(FocusHpText.ResistanceLean.NONE, FocusHpText.leanFrom(balanced, balanced),
                "identical physical/magical profiles must not show a misleading skew tag");
    }

    @Test
    void leanFromPrefersThePhysicalComponentWhenItsScoreIsHigher() {
        DefenseStats physical = new DefenseStats(0.6, 0.5, 0.4, 0.0, 0.0);
        DefenseStats magical = DefenseStats.NONE;
        assertEquals(FocusHpText.ResistanceLean.PHYSICAL, FocusHpText.leanFrom(physical, magical));
    }

    @Test
    void leanFromPrefersTheMagicalComponentWhenItsScoreIsHigher() {
        DefenseStats physical = DefenseStats.NONE;
        DefenseStats magical = new DefenseStats(0.6, 0.5, 0.4, 0.0, 0.0);
        assertEquals(FocusHpText.ResistanceLean.MAGICAL, FocusHpText.leanFrom(physical, magical));
    }

    @Test
    void formatWithLeanKeepsTheDisplayAtTwoLines() {
        Component result = FocusHpText.format(10, Component.text("Boss"), 50, 100,
                FocusHpText.ResistanceLean.MAGICAL);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(result);
        assertEquals(1, plain.chars().filter(c -> c == '\n').count(),
                "the resistance tag must be appended to the name line, not add a third line");
        assertTrue(plain.contains("耐:魔"), "the magical-lean tag must render on the name line");
    }

    @Test
    void formatWithNoneLeanOmitsTheTag() {
        Component result = FocusHpText.format(10, Component.text("Boss"), 50, 100,
                FocusHpText.ResistanceLean.NONE);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(result);
        assertFalse(plain.contains("耐:"), "an unconfigured/balanced mob must not show any resistance tag");
    }

    // --- 依頼2 (2026-08-02): 攻撃タイプタグ(attack.magic-ratio)の導出とレイアウト ---
    @Test
    void attackLeanFromReturnsNoneForZeroMagicRatio() {
        assertEquals(FocusHpText.AttackLean.NONE, FocusHpText.attackLeanFrom(0.0),
                "magic-ratio=0(既定・大多数のモブ)はタグなし");
    }

    @Test
    void attackLeanFromReturnsHybridForMidRatio() {
        assertEquals(FocusHpText.AttackLean.HYBRID, FocusHpText.attackLeanFrom(0.5));
    }

    @Test
    void attackLeanFromReturnsMagicalForFullRatio() {
        assertEquals(FocusHpText.AttackLean.MAGICAL, FocusHpText.attackLeanFrom(1.0));
    }

    @Test
    void formatWithAttackLeanKeepsTheDisplayAtTwoLines() {
        Component result = FocusHpText.format(10, Component.text("Boss"), 50, 100,
                FocusHpText.ResistanceLean.NONE, FocusHpText.AttackLean.MAGICAL);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(result);
        assertEquals(1, plain.chars().filter(c -> c == '\n').count(),
                "the attack-type tag must be appended to the name line, not add a third line");
        assertTrue(plain.contains("攻:魔"), "the magical attack-type tag must render on the name line");
    }

    @Test
    void formatWithAttackLeanNoneOmitsTheTag() {
        Component result = FocusHpText.format(10, Component.text("Boss"), 50, 100,
                FocusHpText.ResistanceLean.NONE, FocusHpText.AttackLean.NONE);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(result);
        assertFalse(plain.contains("攻:"), "magic-ratio=0のモブは攻撃タイプタグを一切出さないこと");
    }

    @Test
    void resistanceAndAttackTagsCoexistWithoutMerging() {
        // 耐性タグ([耐:魔])と攻撃タイプタグ([攻:魔])は別軸の情報 — 同時に出ても混ざらず両方読み取れること。
        Component result = FocusHpText.format(10, Component.text("Boss"), 50, 100,
                FocusHpText.ResistanceLean.MAGICAL, FocusHpText.AttackLean.MAGICAL);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(result);
        assertTrue(plain.contains("耐:魔"), "resistance tag must still render");
        assertTrue(plain.contains("攻:魔"), "attack-type tag must still render");
        assertEquals(1, plain.chars().filter(c -> c == '\n').count(),
                "both tags together must still keep the display at 2 lines total");
    }

    // --- 指摘10 (2026-08-02): formatPlain がlean引数を持たず実表示と食い違っていたバグの回帰 ---
    @Test
    void formatPlainBackwardCompatibleOverloadOmitsTagsLikeUnconfiguredMob() {
        assertEquals("Lv.5 Zombie\n12 / 20", FocusHpText.formatPlain(5, "Zombie", 12, 20),
                "4引数版は従来どおりタグなし(NONE/NONE相当)であること");
    }

    @Test
    void formatPlainWithResistanceLeanMatchesColoredFormTag() {
        assertEquals("Lv.10 Boss [耐:魔]",
                FocusHpText.formatPlain(10, "Boss", 50, 100,
                        FocusHpText.ResistanceLean.MAGICAL, FocusHpText.AttackLean.NONE)
                        .split("\n")[0],
                "耐性タグは色付き表示と同じ文字列で名前行に付くこと");
    }

    @Test
    void formatPlainWithAttackLeanMatchesColoredFormTag() {
        assertEquals("Lv.10 Boss [攻:魔]",
                FocusHpText.formatPlain(10, "Boss", 50, 100,
                        FocusHpText.ResistanceLean.NONE, FocusHpText.AttackLean.MAGICAL)
                        .split("\n")[0],
                "攻撃タイプタグは色付き表示と同じ文字列で名前行に付くこと");
    }

    @Test
    void formatPlainWithBothLeansKeepsThemDistinctAndOnTheNameLine() {
        String plain = FocusHpText.formatPlain(10, "Boss", 50, 100,
                FocusHpText.ResistanceLean.PHYSICAL, FocusHpText.AttackLean.HYBRID);
        assertEquals("Lv.10 Boss [耐:物] [攻:混]\n50 / 100", plain,
                "耐性/攻撃の両タグが混ざらず1行のまま name line に並ぶこと(色付き表示のformatと同じレイアウト)");
    }

    @Test
    void formatPlainWithNoneLeansOmitsBothTags() {
        String plain = FocusHpText.formatPlain(10, "Boss", 50, 100,
                FocusHpText.ResistanceLean.NONE, FocusHpText.AttackLean.NONE);
        assertEquals("Lv.10 Boss\n50 / 100", plain);
    }

    // --- 2026-08-14修正: ボスHPが生の整数で桁あふれしていたバグ ---
    // 表記規則はEliteMobsの BossHealthDisplay#formatNumber (fork-handoff/elitemobs/elitemobs-fork/
    // src/main/java/com/magmaguy/elitemobs/combatsystem/displays/BossHealthDisplay.java:193-210) に
    // 揃える。同じサーバでプレイヤーが2種類の表記を見ないようにするため、閾値・小数桁・丸め方向まで
    // EM と一致させる(唯一の意図的な差異は1000未満 — 下の hpBelowOneThousandIsNotAbbreviated 参照)。

    @Test
    void hpBelowOneThousandIsNotAbbreviated() {
        // EMは1000未満で "999.0" と小数を付けるが(String.valueOf(Round.twoDecimalPlaces(999)))、
        // TFのHPは整数なので全通常モブが "20.0 / 20.0" に化ける。ここだけEMと意図的に変える。
        assertEquals("999 / 999", FocusHpText.formatPlain(1, "Boss", 999, 999).split("\n")[1]);
        assertEquals("20 / 20", FocusHpText.formatPlain(1, "Zombie", 20, 20).split("\n")[1]);
    }

    @Test
    void darkCathedralBossHpUsesEliteMobsMillionAbbreviation() {
        // 闇の大聖堂のボス: 修正前は "7683000 / 7683000" と7桁で出ていた。
        assertEquals("7.68M / 7.68M",
                FocusHpText.formatPlain(80, "Boss", 7_683_000, 7_683_000).split("\n")[1],
                "EMのformatNumber(7683000)と同じ '7.68M' になること");
    }

    @Test
    void hardestBossHpStaysReadableAtEightDigits() {
        // 難易度調整後の最大値: 修正前は "69371755 / 69371755" と8桁で完全に読めなかった。
        assertEquals("69.37M / 69.37M",
                FocusHpText.formatPlain(100, "Boss", 69_371_755, 69_371_755).split("\n")[1],
                "EMのformatNumber(69371755)と同じ '69.37M' になること");
    }

    @Test
    void abbreviationBoundariesMatchEliteMobsExactly() {
        // 実測値はEMのformatNumber+MagmaCore Round(Math.round(v*100)/100.0)を原文どおり再現して確認済み。
        assertEquals("999", FocusHpText.formatHp(999), "1000未満は略記しない");
        assertEquals("1.0K", FocusHpText.formatHp(1_000), "1000ちょうどからK表記に入る");
        // EMの既知の癖: 999999/1000=999.999 を2桁丸めすると1000.0になるがK枝のまま。
        // TF側で "1.0M" に直すとEMと表示が食い違うので、あえて同じ癖を再現する。
        assertEquals("1000.0K", FocusHpText.formatHp(999_999));
        assertEquals("1.0M", FocusHpText.formatHp(1_000_000), "1000000ちょうどからM表記に入る");
    }

    @Test
    void abbreviationCoversKiloMegaGigaTeraLikeEliteMobs() {
        assertEquals("1.5K", FocusHpText.formatHp(1_500));
        assertEquals("7.68M", FocusHpText.formatHp(7_683_000));
        assertEquals("1.23B", FocusHpText.formatHp(1_234_500_000L));
        assertEquals("1.0T", FocusHpText.formatHp(1_000_000_000_000L));
    }

    @Test
    void abbreviationRoundsHalfUpToTwoDecimalsLikeMagmaCoreRound() {
        // MagmaCore Round#decimalPlaces は Math.round(v*10^p)/10^p = 四捨五入(切り捨てではない)。
        // 切り捨て実装なら 1.235M -> "1.23M" になり、ここで落ちる。
        assertEquals("1.24M", FocusHpText.formatHp(1_235_000), "四捨五入されること(切り捨てなら1.23M)");
        assertEquals("1.23M", FocusHpText.formatHp(1_234_000), "繰り上がらない側も2桁で止まること");
    }

    @Test
    void negativeValuesKeepTheEliteMobsSignPrefix() {
        assertEquals("-1.5K", FocusHpText.formatHp(-1_500),
                "EMのformatNumberと同じく符号を前置して絶対値を略記すること");
    }

    @Test
    void hpBeyondIntegerMaxIsNotSaturated() {
        // 修正前は curHp/maxHp が int だったため、double→int の narrowing が Integer.MAX_VALUE で
        // 飽和し、30億HPが "2.15B"(=2,147,483,647)という嘘の値に化けた。long 化の回帰。
        // ※このテストは修正前のシグネチャではそもそもコンパイルできない(long→int の縮小変換)。
        assertEquals("3.0B / 3.0B",
                FocusHpText.formatPlain(120, "Boss", 3_000_000_000L, 3_000_000_000L).split("\n")[1]);
        assertEquals("2.15B", FocusHpText.formatHp(Integer.MAX_VALUE),
                "Integer.MAX_VALUE 自体は飽和値と同じ表記になるので、これと区別できることが要点");
    }

    @Test
    void coloredComponentPathAbbreviatesToo() {
        // formatPlain と format は別コードパス(過去に指摘10でタグが片方だけ落ちた実績がある)。
        // 実表示側(Component)でも略記されることを独立に固定する。
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(FocusHpText.format(80, Component.text("Boss"), 7_683_000, 69_371_755));
        assertTrue(plain.contains("7.68M"), "現在HPが色付き表示でも略記されること: " + plain);
        assertTrue(plain.contains("69.37M"), "最大HPが色付き表示でも略記されること: " + plain);
        assertFalse(plain.contains("7683000"), "生の整数が残っていないこと: " + plain);
    }

    private static boolean containsTranslatable(Component component, String keyPrefix) {
        if (component instanceof net.kyori.adventure.text.TranslatableComponent translatable
                && translatable.key().startsWith(keyPrefix)) {
            return true;
        }
        for (Component child : component.children()) {
            if (containsTranslatable(child, keyPrefix)) {
                return true;
            }
        }
        return false;
    }
}
