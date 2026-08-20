package com.trinityforge.config.domains;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U10: <b>防具立て・マネキンを殴ってレベリングできる</b>穴を構造的に塞いだことの回帰テスト
 * （2026-08-02）。
 *
 * <p><b>なぜ設定ではなくコードで塞ぐか</b>: 防具立ては棒6＋石ブロック1で無限に量産でき、
 * 動かず反撃もしないので「無限EXP装置」そのものになる。既存の抑止は2つとも設定値で、
 * どちらも戻せば復活した ──
 * {@code combat.kill-exp.unlisted-entity-multiplier}(既定0)を 1.0 にすれば元通りで、
 * {@code mob-level-table.yml} の {@code no-skill-exp-mobs} は行を消せば復活する。
 * よって<b>設定を最大限ゆるめても 0 のまま</b>であることをここで固定する。
 *
 * <p><b>経路は2本ある</b>: 討伐EXPは {@code CombatListener#onCombatKill}(武器スキル)と
 * {@code ArsMagicExperienceListener#onMagicKill}(魔法)の両方から入る。片方だけ塞ぐと
 * 「杖で叩けば通る」ので、両方が通る {@link SkillExpConfig} の kill-exp 計算に置いてある。
 *
 * <p><b>前提の訂正</b>: 「{@code ArmorStand} は {@code kill()} を override するので
 * {@code EntityDeathEvent} は発火しない」という調査時の推測は<b>誤り</b>だった。
 * Paper のサーバ実装では {@code ArmorStand#brokenByPlayer} →
 * {@code LivingEntity#dropAllDeathLoot} → {@code CraftEventFactory.callEntityDeathEvent}
 * と辿って確実に発火する。この推測のまま「U10 は非バグ」と閉じると穴が残る。
 */
class SkillExpPlaceableTargetExclusionTest {

    /** 全ての倍率を 1.0 に開けた、EXP が最大限入る設定。 */
    private static SkillExpConfig wideOpenConfig() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("combat.kill-exp.enabled", true);
        yaml.set("combat.kill-exp.base.HEAVY_WEAPONS", 100.0);
        yaml.set("combat.kill-exp.base.LIGHT_WEAPONS", 100.0);
        yaml.set("combat.kill-exp.base.ARCHERY", 100.0);
        yaml.set("combat.kill-exp.unlisted-entity-multiplier", 1.0);
        yaml.set("ars-magic.kill-exp.enabled", true);
        yaml.set("ars-magic.kill-exp.base", 100.0);
        yaml.set("ars-magic.kill-exp.unlisted-entity-multiplier", 1.0);

        SkillExpConfig config = new SkillExpConfig();
        config.applyFrom(yaml, Logger.getLogger(SkillExpPlaceableTargetExclusionTest.class.getName()));
        return config;
    }

    @Test
    @DisplayName("防具立ては unlisted-entity-multiplier を 1.0 に戻しても武器スキルEXPが0")
    void armorStandGivesNoCombatExpEvenWithEveryMultiplierOpened() {
        SkillExpConfig config = wideOpenConfig();

        assertEquals(0.0, config.combatKillExp("HEAVY_WEAPONS", "ARMOR_STAND", 100, 20.0),
                "防具立ては棒6＋石1で無限に量産できるので、設定で開けられてはいけない");
        assertEquals(0.0, config.combatKillExp("LIGHT_WEAPONS", "ARMOR_STAND", 100, 20.0));
        assertEquals(0.0, config.combatKillExp("ARCHERY", "ARMOR_STAND", 100, 20.0));
    }

    @Test
    @DisplayName("防具立ては魔法経路(ars-magic)でも0 ── 片方だけ塞ぐと杖で通る")
    void armorStandGivesNoArsMagicExpEither() {
        SkillExpConfig config = wideOpenConfig();

        assertEquals(0.0, config.arsMagicKillExp("ARMOR_STAND", 100, 20.0),
                "討伐EXPの経路は CombatListener と ArsMagicExperienceListener の2本ある");
    }

    @Test
    @DisplayName("マネキン(1.21.9 追加)も同じ扱い")
    void mannequinIsExcludedToo() {
        SkillExpConfig config = wideOpenConfig();

        assertEquals(0.0, config.combatKillExp("HEAVY_WEAPONS", "MANNEQUIN", 100, 20.0));
        assertEquals(0.0, config.arsMagicKillExp("MANNEQUIN", 100, 20.0));
    }

    @Test
    @DisplayName("大文字小文字と前後空白を問わず除外する(呼び出し元の表記ゆれで穴が開かない)")
    void exclusionIsCaseAndWhitespaceInsensitive() {
        SkillExpConfig config = wideOpenConfig();

        assertEquals(0.0, config.combatKillExp("HEAVY_WEAPONS", "armor_stand", 100, 20.0));
        assertEquals(0.0, config.combatKillExp("HEAVY_WEAPONS", " ARMOR_STAND ", 100, 20.0));
    }

    @Test
    @DisplayName("通常のモブは巻き添えにならない(除外が広すぎないことの確認)")
    void ordinaryMobsStillEarnExp() {
        SkillExpConfig config = wideOpenConfig();

        assertTrue(config.combatKillExp("HEAVY_WEAPONS", "ZOMBIE", 100, 20.0) > 0.0,
                "この行が落ちたら除外集合が広すぎる(討伐EXPが丸ごと死ぬ)");
        assertTrue(config.arsMagicKillExp("ZOMBIE", 100, 20.0) > 0.0);
        assertTrue(config.combatKillExp("HEAVY_WEAPONS", null, 100, 20.0) >= 0.0,
                "entityType が null でも例外にならないこと");
    }
}
