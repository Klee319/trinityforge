package com.trinityforge.mob;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
