package com.trinityforge.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 出荷 {@code combat/mob-abilities.yml} に書いた効果音が実際に解決できることを固定する(2026-07-31)。
 *
 * <p><b>なぜ必要か</b>: Bukkit の {@code Sound} は<b>enum 定数名（アンダースコア区切り）</b>と
 * <b>レジストリキー（ドット区切り）</b>で綴りが違う。
 * {@code ENTITY_GENERIC_EXPLODE} ⇔ {@code minecraft:entity.generic.explode}。
 * yml には読みやすさのため enum 定数名を書いているので、レジストリを引くときに
 * {@code _} を {@code .} へ直さないと <b>null が返るだけで例外は飛ばない</b> ──
 * つまり「技は出るのに音だけ一切鳴らない」状態が、警告もログも無しに成立する。
 *
 * <p>演出は本要件（敵の攻撃手法と演出のバリエーション）の半分を占めるので、
 * 綴りの取り違えをここで落とす。
 */
class MobAbilitySoundResolutionTest {

    private static final String ABILITIES = "src/main/resources/combat/mob-abilities.yml";

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 出荷 yml に書かれている sound 値を全部集める。 */
    private static List<String> shippedSoundNames() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(ABILITIES));
        ConfigurationSection templates = yaml.getConfigurationSection("abilities");
        List<String> names = new ArrayList<>();
        if (templates != null) {
            for (String id : templates.getKeys(false)) {
                String sound = templates.getString(id + ".sound", "");
                if (sound != null && !sound.isBlank()) {
                    names.add(sound.trim());
                }
            }
        }
        return names;
    }

    @Test
    @DisplayName("enum 定数名をそのままレジストリキーにすると解決できない（この綴り違いが本体）")
    void underscoreSpellingDoesNotResolveInTheRegistry() {
        // 「アンダースコアのままレジストリを引く」= MobAbilityExecutor が 2026-07-31 まで
        // やっていたこと。これが null になることを固定しておく（なぜ変換が要るかの証拠）。
        Sound direct = Registry.SOUNDS.get(NamespacedKey.minecraft("entity_generic_explode"));
        assertEquals(null, direct,
                "アンダースコア綴りが解決できてしまうなら、この変換自体が不要になる。"
                        + "その場合は MobAbilityExecutor.sound() の実装を見直すこと");
    }

    @Test
    @DisplayName("出荷 yml の効果音は全件解決できる")
    void everyShippedSoundResolves() {
        List<String> names = shippedSoundNames();
        assertFalse(names.isEmpty(), "出荷 mob-abilities.yml から sound を1件も読めていない");

        List<String> unresolved = new ArrayList<>();
        for (String name : names) {
            if (resolve(name) == null) {
                unresolved.add(name);
            }
        }
        assertEquals(List.of(), unresolved,
                "解決できない効果音がある(技は出るのに無音になる。例外もログも出ない): " + unresolved);
    }

    @Test
    @DisplayName("素朴な _→. 置換では解決できない名前がある（だから索引方式にしてある）")
    void naiveUnderscoreToDotReplacementIsNotEnough() {
        // モブ名の中のアンダースコアは残る綴りなので、全置換すると壊れる:
        // ENTITY_IRON_GOLEM_ATTACK → entity.iron.golem.attack (存在しない)
        //                    正解 → entity.iron_golem.attack
        List<String> brokenByNaiveRule = new ArrayList<>();
        for (String name : List.of("ENTITY_IRON_GOLEM_ATTACK", "ENTITY_ZOMBIE_VILLAGER_CONVERTED")) {
            String naive = name.toLowerCase(Locale.ROOT).replace('_', '.');
            if (Registry.SOUNDS.get(NamespacedKey.minecraft(naive)) == null) {
                brokenByNaiveRule.add(name);
            }
            // 索引方式なら解ける。
            assertFalse(resolve(name) == null, name + " は索引方式で解決できるべき");
        }
        assertEquals(2, brokenByNaiveRule.size(),
                "素朴置換で壊れる例が消えたなら、実装を単純化してよい: " + brokenByNaiveRule);
    }

    /**
     * {@code MobAbilityExecutor#sound} と同じ索引方式。
     * レジストリキーの {@code .} を {@code _} に直して大文字化すると enum 定数名の綴りに一致する。
     * 実装を変えたらここも合わせる（あえて重複させてある: 実装側は private のため）。
     */
    private static Sound resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.trim().toUpperCase(Locale.ROOT);
        for (Sound sound : Registry.SOUNDS) {
            String key = sound.getKey().getKey().toUpperCase(Locale.ROOT);
            if (wanted.equals(key) || wanted.equals(key.replace('.', '_'))) {
                return sound;
            }
        }
        return null;
    }
}
