package com.trinityforge.config.domains;

import com.trinityforge.listeners.CombatListener;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacySkillExpApiRemovalTest {

    private static final Path COMBAT_LISTENER_SOURCE =
            Path.of("src/main/java/com/trinityforge/listeners/CombatListener.java");

    @Test
    void removedCastAndPerHitConfigurationApisAreNotExposed() throws Exception {
        Set<String> configMethods = Stream.of(SkillExpConfig.class.getDeclaredMethods())
                .map(method -> method.getName())
                .collect(Collectors.toSet());
        for (String removed : Set.of(
                "arsMagicExpPerCast",
                "arsMagicExpPerMana",
                "combatExpPerHit",
                "combatExpForSkill",
                "combatSameTargetCooldownSeconds",
                "combatDamageScaledMode",
                "combatDamageScale",
                "combatMobLevelScale")) {
            assertFalse(configMethods.contains(removed), removed + " is a removed legacy EXP API");
        }

        Set<String> listenerMethods = Stream.of(CombatListener.class.getDeclaredMethods())
                .map(method -> method.getName())
                .collect(Collectors.toSet());
        // N5(2026-07-31): 「メソッド名の一致」だけで per-hit 経路の不在を宣言していたため、
        // 同じ性質の per-hit 計算 archeryExpAmount が名前が違うだけで素通りしていた。
        // 名前の列挙は残すが、これは補助。本体の縛りは下の
        // combatListenerGrantsSkillExpOnlyFromTheDeathHandler(性質で縛る)。
        for (String removed : Set.of("combatSkillExpAmount", "archeryExpAmount")) {
            assertFalse(listenerMethods.contains(removed),
                    removed + ": per-hit legacy EXP calculation must not remain reachable");
        }

        String configSource = Files.readString(Path.of(
                "src/main/java/com/trinityforge/config/domains/SkillExpConfig.java"));
        for (String removedKey : Set.of(
                "exp-per-cast",
                "exp-per-mana",
                "exp-per-hit",
                "same-target-cooldown-seconds",
                "combat.by-skill",
                "combat.mode",
                "combat.damage-scale",
                "combat.mob-level-scale")) {
            assertFalse(configSource.contains(removedKey),
                    removedKey + " is a removed legacy configuration key");
        }
    }

    /**
     * N5(2026-07-31) の本体: <b>スキルEXPの付与は討伐確定時(EntityDeathEvent)にしか起きない</b>という
     * 性質そのものを縛る。メソッド名ではなく「{@code CombatListener} 内の
     * {@code ArsProgressionBridge.grantSkillExp(} 呼び出しが {@code onCombatKill} の本体だけに存在する」
     * ことを見るので、per-hit 経路を新しい名前で復活させても落ちる。
     *
     * <p>これが無かったために、近接を討伐時ベースへ移した 2026-07-29 の変更で弓術だけが
     * {@code EntityDamageByEntityEvent}(命中)の中で即時付与を続けていたことを検出できなかった。
     */
    @Test
    void combatListenerGrantsSkillExpOnlyFromTheDeathHandler() throws Exception {
        String source = Files.readString(COMBAT_LISTENER_SOURCE);

        int killStart = source.indexOf("public void onCombatKill(");
        assertTrue(killStart > 0,
                "onCombatKill(EntityDeathEvent) が見つからない(改名したならこのテストも直す)");
        // onCombatKill の本体は「次の @EventHandler」までで終わる(入れ子のハンドラは無い)。
        int killEnd = source.indexOf("\n    @EventHandler", killStart);
        assertTrue(killEnd > killStart, "onCombatKill の次のイベントハンドラが見つからない");

        List<Integer> grantSites = new ArrayList<>();
        for (int at = source.indexOf("ArsProgressionBridge.grantSkillExp(");
                at >= 0;
                at = source.indexOf("ArsProgressionBridge.grantSkillExp(", at + 1)) {
            grantSites.add(at);
        }
        assertEquals(1, grantSites.size(),
                "CombatListener のスキルEXP付与は討伐時の1箇所だけであること(実際: "
                        + grantSites.size() + "箇所)。命中経路で付与を足してはならない");
        int site = grantSites.getFirst();
        assertTrue(site > killStart && site < killEnd,
                "唯一の grantSkillExp 呼び出しが onCombatKill の外にある(= per-hit 付与が復活している)");
    }

    /**
     * per-hit 式の実体({@code ArcheryExperiencePolicy})が「到達不能なまま残っている」状態も禁止する。
     * 残すと出荷 yml とリファレンスに効かない係数が居座り、次の担当者が「どちらが正か」を読めない。
     */
    @Test
    void perHitArcheryPolicyClassIsDeletedNotJustUnreferenced() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.trinityforge.listeners.ArcheryExperiencePolicy"),
                "ArcheryExperiencePolicy は削除済みであること(未参照で残すのは禁止)");
    }

    /** 命中経路のクラス参照も残っていないこと(import だけ残る等の中途半端な状態を防ぐ)。 */
    @Test
    void combatListenerNoLongerMentionsThePerHitArcheryPolicy() throws Exception {
        String source = Files.readString(COMBAT_LISTENER_SOURCE);
        assertFalse(source.contains("ArcheryExperiencePolicy"),
                "CombatListener が削除済みの ArcheryExperiencePolicy をまだ参照している");
    }
}
