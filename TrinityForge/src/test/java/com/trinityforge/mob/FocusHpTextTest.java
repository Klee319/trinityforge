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
