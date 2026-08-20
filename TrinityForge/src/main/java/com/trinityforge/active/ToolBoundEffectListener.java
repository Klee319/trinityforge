package com.trinityforge.active;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link ActiveSkill#toolBound()} な効果を「発動に使ったツールを手放した瞬間」に強制終了する
 * (2026-08-18 ユーザー確定要件: 「共有ではなく該当のツールから持ち変えると効果が強制終了する方針。
 * つまり強制的に終了しCTに入るので持ち替えても効果が残らずずるできない仕様」)。
 *
 * <h2>これが無いと何が起きていたか</h2>
 * {@code haste-active-mining}(ツルハシ)と {@code haste-active-digging}(シャベル)は
 * 2026-08-18 の初版では {@link ActiveSkill#cooldownGroup()} を共有して「持ち替えて連発できない」だけを
 * 担保していた。しかし<b>効果自体は持ち替えても残る</b>ので、ツルハシで発動してシャベルへ持ち替えると
 * 「シャベル側のノードを解放していないのに掘削がヘイストで速い」状態が作れた。
 * CTを共有しても<b>1回ぶんの効果を別ツールへ横流しする</b>のは止められない。
 *
 * <h2>設計上の選択</h2>
 * <ul>
 *   <li><b>CTは巻き戻さない/延長もしない。</b>CTは発動時から走っているので、持ち替えは
 *       「効果を捨ててCTだけ払う」= 常に損。ここでCTを再スタートさせると、
 *       持ち替えただけで元より重い罰になり「持ち替え禁止」に近い体験になる。</li>
 *   <li><b>判定は必ず次tickに回す。</b>{@code PlayerItemHeldEvent} はスロットが切り替わる<b>前</b>に
 *       飛ぶので、イベント中に {@code getItemInMainHand()} を読むと<b>持ち替え前</b>の道具が返る。
 *       {@code PlayerDropItemEvent} も「スタックが引かれ済みか」が経路依存
 *       (docs/agent-context の playerdropitem 罠)。したがってイベントの種類ごとに
 *       スナップショットを解釈するのではなく、<b>次tickの実際のメインハンド</b>だけを見る。</li>
 *   <li><b>効果の取り消しはスキルに委譲する</b>({@link ActiveSkill#cancelEffect})。
 *       ビーコン/ポーション由来の同型効果を剥がさないための照合は実装側の責務。</li>
 * </ul>
 */
public final class ToolBoundEffectListener implements Listener {

    private final Plugin plugin;
    private final ActiveSkillRegistry registry;
    private final ActiveEffectSessions sessions;
    private final FeedbackLayer feedback;

    public ToolBoundEffectListener(Plugin plugin, ActiveSkillRegistry registry,
                                    ActiveEffectSessions sessions, FeedbackLayer feedback) {
        this.plugin = plugin; // null 可(テスト): その場合は即時判定へ縮退する
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    /** ホットバーのスロット切り替え(スクロール/数字キー)。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        scheduleCheck(event.getPlayer());
    }

    /** メイン/オフハンドの入れ替え(F キー)。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        scheduleCheck(event.getPlayer());
    }

    /** 対象ツールを投げ捨てた場合。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        scheduleCheck(event.getPlayer());
    }

    /** 対象ツールが壊れた場合(手が空になる)。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent event) {
        scheduleCheck(event.getPlayer());
    }

    /** インベントリ操作でメインハンドのスロットの中身が入れ替わった場合。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSlotChange(io.papermc.paper.event.player.PlayerInventorySlotChangeEvent event) {
        if (event.getSlot() == event.getPlayer().getInventory().getHeldItemSlot()) {
            scheduleCheck(event.getPlayer());
        }
    }

    /** 死亡すると効果はバニラ側で落ちるので、台帳だけ閉じる。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        sessions.close(event.getEntity().getUniqueId());
    }

    /** 退出時に台帳を閉じる(Mapがサーバ稼働時間ぶん伸びないように)。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sessions.close(event.getPlayer().getUniqueId());
    }

    /**
     * 次tickで「いま実際に持っているもの」を見て判定する。プラグイン未注入(テスト)では即時判定。
     * セッションが開いていないプレイヤーではタスクを積まない(常時走るイベントなので空振りを避ける)。
     */
    private void scheduleCheck(Player player) {
        if (player == null || sessions.active(player.getUniqueId(), System.currentTimeMillis()).isEmpty()) {
            return;
        }
        if (plugin == null) {
            endIfToolLeft(player);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                endIfToolLeft(player);
            }
        });
    }

    /**
     * メインハンドが発動元スキルの {@link ActiveSkill#targetSkills()} から外れていたら効果を終了する。
     * パッケージ可視にしてあるのは、テストがスケジューラ無しでこの判定だけを叩けるようにするため。
     */
    void endIfToolLeft(Player player) {
        long now = System.currentTimeMillis();
        Optional<ActiveEffectSessions.Session> open = sessions.active(player.getUniqueId(), now);
        if (open.isEmpty()) {
            return;
        }
        ActiveEffectSessions.Session session = open.get();
        ActiveSkill skill = registry.get(session.skillId()).orElse(null);
        if (skill == null || !skill.toolBound()) {
            sessions.close(player.getUniqueId());
            return;
        }
        String useSkill = ActivationDispatcher.mainHandUseSkill(player.getInventory().getItemInMainHand());
        if (useSkill != null && containsIgnoreCase(skill, useSkill)) {
            return; // まだ対象ツールを握っている。
        }
        skill.cancelEffect(player, session.tier());
        sessions.close(player.getUniqueId());
        feedback.failure(player, skill.displayName() + " は対象の道具を手放したため終了しました");
    }

    /** {@link ActiveSkillRegistry#forTargetSkill} と同じ大文字小文字を無視する比較。 */
    private static boolean containsIgnoreCase(ActiveSkill skill, String useSkill) {
        for (String target : skill.targetSkills()) {
            if (useSkill.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }
}
