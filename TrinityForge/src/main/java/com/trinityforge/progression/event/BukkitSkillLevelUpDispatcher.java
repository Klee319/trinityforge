package com.trinityforge.progression.event;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * {@link SkillLevelUpSink} を {@link TrinitySkillLevelUpEvent} の発火へ変換する。
 *
 * <p>ここが引き受けている非自明な事情は 2 つ。
 * <ol>
 *   <li><b>スレッド。</b> TF の EXP 付与は {@code NativeExperienceDispatcher} の
 *       <b>非同期</b>タスクから走る。Bukkit の同期イベントを非同期文脈で発火すると
 *       {@code IllegalStateException} になるので、非同期なら必ず
 *       {@code getScheduler().runTask} でメインスレッドへ寄せてから発火する。</li>
 *   <li><b>1 レベルずつ分解する。</b> 複数レベル同時上昇でも、到達したレベルごとに
 *       個別のイベントを発火する。受け手が「Lv10 の節目」を単純な等値比較で書けるようにするため。</li>
 * </ol>
 *
 * <p>プレイヤーがこのサーバにいない（オフライン／他バックエンドへ移動済み）場合は発火しない。
 * イベントが {@code Player} を必須で持つ設計のため。
 */
public final class BukkitSkillLevelUpDispatcher implements SkillLevelUpSink {

    /** 1 回の付与で発火するイベント数の上限。管理コマンドの一括レベル設定で数百発になるのを防ぐ。 */
    private static final int MAX_EVENTS_PER_GRANT = 100;

    private final Plugin plugin;

    public BukkitSkillLevelUpDispatcher(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void onSkillLevelUp(UUID playerId, String skillId, int oldLevel, int newLevel) {
        if (playerId == null || skillId == null || newLevel <= oldLevel) {
            return;
        }
        if (plugin.getServer().isPrimaryThread()) {
            fire(playerId, skillId, oldLevel, newLevel);
        } else {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> fire(playerId, skillId, oldLevel, newLevel));
        }
    }

    private void fire(UUID playerId, String skillId, int oldLevel, int newLevel) {
        try {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            int last = Math.min(newLevel, oldLevel + MAX_EVENTS_PER_GRANT);
            for (int level = oldLevel + 1; level <= last; level++) {
                plugin.getServer().getPluginManager().callEvent(
                        new TrinitySkillLevelUpEvent(player, skillId, level - 1, level));
            }
        } catch (RuntimeException ex) {
            // 通知はおまけ。受け手の例外で EXP 付与経路そのものを壊さない。
            plugin.getLogger().log(Level.WARNING,
                    "スキルレベルアップイベントの発火に失敗しました: " + playerId + " / " + skillId, ex);
        }
    }
}
