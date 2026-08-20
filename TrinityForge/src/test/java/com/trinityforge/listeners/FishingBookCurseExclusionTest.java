package com.trinityforge.listeners;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 釣果のエンチャント本から呪いを外す判定 (2026-08-13 ユーザー指示)。
 *
 * <p>固定するのは「どう判定するか」ではなく「何が呪い集合に入るか」。実装はタグ
 * {@code #minecraft:curse} を第一経路にし、タグ API が無い環境では非推奨の
 * {@code Enchantment#isCursed()} へ落ちる二段構えなので、<b>どちらの経路を通っても
 * 同じ答えになること</b>をここで担保する(MockBukkit はタグ API 未実装なので、この
 * テストが実際に踏むのは第二経路)。
 *
 * <p>集合が空になると「呪いを弾かない」fail-open になり、症状は「たまに呪い本が釣れる」
 * だけでログには何も出ない。空でないことを明示的に落とすのはそのため。
 */
class FishingBookCurseExclusionTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Enchantment vanilla(String key) {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
    }

    @Test
    void cursedSetContainsBothVanillaCurses() {
        Enchantment binding = vanilla("binding_curse");
        Enchantment vanishing = vanilla("vanishing_curse");
        assertNotNull(binding, "binding_curse がレジストリに無い(前提が壊れている)");
        assertNotNull(vanishing, "vanishing_curse がレジストリに無い(前提が壊れている)");

        Set<Enchantment> curses = FishingGimmickListener.cursedEnchants();
        assertFalse(curses.isEmpty(),
                "呪い集合が空 = 呪いを弾かない fail-open。実機では『たまに呪い本が釣れる』"
                        + "という形でしか現れず、ログには何も出ない");
        assertTrue(curses.contains(binding), "束縛の呪いが除外対象に入っていない");
        assertTrue(curses.contains(vanishing), "消滅の呪いが除外対象に入っていない");
    }

    @Test
    void ordinaryEnchantsAreNotTreatedAsCurses() {
        Set<Enchantment> curses = FishingGimmickListener.cursedEnchants();
        // ここが落ちるということは「呪いの判定が実は全件 true」= 釣果の本が作られなくなる。
        for (String key : new String[]{"unbreaking", "mending", "sharpness", "luck_of_the_sea"}) {
            Enchantment ench = vanilla(key);
            assertNotNull(ench, key + " がレジストリに無い(前提が壊れている)");
            assertFalse(curses.contains(ench), key + " を呪い扱いしている");
        }
    }
}
