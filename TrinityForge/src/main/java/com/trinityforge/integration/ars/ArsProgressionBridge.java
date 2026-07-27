package com.trinityforge.integration.ars;

import com.trinityforge.TrinityForge;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Public integration facade used by TrinityForge and the ArsPaper fork to grant native EXP. */
public final class ArsProgressionBridge {

    public static final String ARS_MAGIC = "ARS_MAGIC";
    public static final String ARS_SMITHING = "ARS_SMITHING";

    private ArsProgressionBridge() {
    }

    public static void grantSkillExp(Plugin plugin, Player player, String skillType, double amount) {
        if (amount <= 0.0 || player == null || skillType == null || skillType.isBlank()) return;
        TrinityForge tf = TrinityForge.getInstance();
        if (tf == null || tf.experienceDispatcher() == null) return;
        try {
            tf.experienceDispatcher().grant(player.getUniqueId(), skillType, amount);
        } catch (RuntimeException ex) {
            // plugin は呼び出し元(ArsPaperフォーク等)から渡されるだけで未検証。null なら
            // plugin.getLogger() 自体がNPEになり境界を越えて例外が漏れるため、その場合は
            // Bukkitのグローバルロガーへフォールバックする(fail-open)。
            java.util.logging.Logger logger = plugin != null ? plugin.getLogger() : org.bukkit.Bukkit.getLogger();
            logger.warning("Failed to grant " + skillType + " EXP: " + ex.getMessage());
        }
    }

    public static void grantSmithingExp(Plugin plugin, Player player, double amount) {
        grantSkillExp(plugin, player, ARS_SMITHING, amount);
    }

    /**
     * 詠唱による ARS_MAGIC EXP。<b>ここでワールド倍率を掛ける</b>のがこのメソッドの存在意義で、
     * 素の {@link #grantSkillExp} を直接呼ぶのとは意味が違う。
     *
     * <p>{@code stats/skill-exp.yml} の記述は当初から「重武器/軽武器/弓術/重装甲/軽装甲/ARS_MAGIC を
     * ゲートする」だったが、実装は武器(命中)と防具(被弾)しか通しておらず、魔法だけ素通りしていた
     * (2026-07-26 のレビューで発覚)。オーバーワールドEXPを {@code outside-dungeon-exp-rate} で
     * 絞った以上、ここだけ無傷だと<b>魔法が他の戦闘スキルの4倍速で伸びる</b>ため、実装を記述側の
     * 意図へ合わせる。
     *
     * <p>倍率適用を {@link #grantSkillExp} 側に置かないのは、武器EXP({@code CombatListener})が
     * 既に自分で倍率を掛けてからそちらを呼んでいるため — 共通側に置くと二重に縮む。
     */
    public static void grantMagicExp(Plugin plugin, Player player, double amount) {
        grantSkillExp(plugin, player, ARS_MAGIC, amount * worldExpRate(player));
    }

    /**
     * 詠唱したワールドの戦闘スキルEXP倍率。プラグイン未起動やワールド不明のときは 1.0
     * (ゲートを掛けない) — 他のEXP経路が同じ状況で採る挙動と揃えてある。
     */
    private static double worldExpRate(Player player) {
        TrinityForge tf = TrinityForge.getInstance();
        if (tf == null || player == null || player.getWorld() == null) {
            return 1.0;
        }
        return tf.config().skillExp()
                .worldExpRate(tf.dungeonWorldRegistry().isDungeonWorld(player.getWorld().getUID()));
    }
}
