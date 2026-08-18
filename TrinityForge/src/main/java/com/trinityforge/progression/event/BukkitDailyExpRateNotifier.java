package com.trinityforge.progression.event;

import com.trinityforge.progression.DailyExpDiminishing;
import com.trinityforge.progression.DailyExpRateText;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * {@link DailyExpRateChangeSink} をチャット通知へ変換する（2026-08-18）。
 *
 * <p><b>スレッド</b>: TF の EXP 付与は {@code NativeExperienceDispatcher} の<b>非同期</b>タスクから
 * 走るので、Bukkit を触る前に必ずメインスレッドへ寄せる（{@link BukkitSkillLevelUpDispatcher} と同じ）。
 *
 * <p><b>何を出すか</b>: 段が落ちたときは「今いくつになったか」だけでなく
 * <b>「何をすれば戻るのか」「あとどれくらいで戻るのか」</b>まで出す。倍率を離散の段にした設計意図が
 * まさにそこにあり、率だけ出しても「渋くなった」以上の情報にならないため。
 *
 * <p><b>⚠ 回復時間の見積りは「オンラインのまま、そのスキルを稼がずにいる」前提</b>。
 * 蓄積は永続化しておらず退出時に破棄されるので、再ログインすると表示より早く（即座に）戻る。
 * 表示が嘘になる向きは「実際より遅く出る」側だけなので、煽る表示にはならない。
 */
public final class BukkitDailyExpRateNotifier implements DailyExpRateChangeSink {

    private final Plugin plugin;
    /** スキルIDから日本語表示名へ。スキルツリー定義の display-name が唯一の出所。 */
    private final Function<String, String> skillDisplayName;
    /** プレイヤー×スキルの現在の状態（残りEXP・回復時間の出所）。 */
    private final BiFunction<UUID, String, DailyExpDiminishing.Status> statusLookup;

    public BukkitDailyExpRateNotifier(Plugin plugin, Function<String, String> skillDisplayName,
                                      BiFunction<UUID, String, DailyExpDiminishing.Status> statusLookup) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.skillDisplayName = Objects.requireNonNull(skillDisplayName, "skillDisplayName");
        this.statusLookup = Objects.requireNonNull(statusLookup, "statusLookup");
    }

    @Override
    public void onDailyExpRateChanged(UUID playerId, String skillId,
                                      DailyExpDiminishing.Applied applied) {
        if (playerId == null || skillId == null || applied == null) {
            return;
        }
        if (plugin.getServer().isPrimaryThread()) {
            send(playerId, skillId, applied);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () -> send(playerId, skillId, applied));
        }
    }

    private void send(UUID playerId, String skillId, DailyExpDiminishing.Applied applied) {
        try {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return;
            }
            String name = skillDisplayName.apply(skillId);
            if (applied.improved()) {
                player.sendMessage(recoveredMessage(name, applied.multiplier()));
                return;
            }
            player.sendMessage(loweredMessage(name, applied.multiplier(),
                    statusLookup.apply(playerId, skillId)));
            // 「気づかないうちに減っていた」を無くすのが目的なので、音も鳴らす。
            // 落ちるのは1段につき1回だけなので鳴りっぱなしにはならない。
            player.playSound(Sound.sound(Key.key("block.note_block.bass"),
                    Sound.Source.PLAYER, 0.7f, 0.8f));
        } catch (RuntimeException ex) {
            // 通知はおまけ。受け手の例外で EXP 付与経路そのものを壊さない。
            plugin.getLogger().log(Level.WARNING,
                    "EXP取得量の変化通知に失敗しました: " + playerId + " / " + skillId, ex);
        }
    }

    /** 段が落ちたときの本文（可視化のため package-private でテストから直接読む）。 */
    static Component loweredMessage(String skillName, double multiplier,
                                    DailyExpDiminishing.Status status) {
        Component message = Component.text("⚠ ", NamedTextColor.GOLD)
                .append(Component.text(skillName, NamedTextColor.YELLOW))
                .append(Component.text(" の経験値取得量が ", NamedTextColor.GRAY))
                .append(Component.text(DailyExpRateText.percent(multiplier), NamedTextColor.RED))
                .append(Component.text(" に下がりました。", NamedTextColor.GRAY));
        String untilImproved = status == null ? null : DailyExpRateText.duration(status.millisUntilImproved());
        String untilFull = status == null ? null : DailyExpRateText.duration(status.millisUntilFull());
        if (untilImproved == null && untilFull == null) {
            return message;
        }
        Component detail = Component.text("　このスキルを休むと戻ります（", NamedTextColor.DARK_GRAY);
        if (untilImproved != null) {
            detail = detail.append(Component.text("1段回復まで " + untilImproved, NamedTextColor.DARK_GRAY));
        }
        if (untilFull != null) {
            if (untilImproved != null) {
                detail = detail.append(Component.text(" / ", NamedTextColor.DARK_GRAY));
            }
            detail = detail.append(Component.text("等倍まで " + untilFull, NamedTextColor.DARK_GRAY));
        }
        return message.append(Component.newline())
                .append(detail).append(Component.text("）", NamedTextColor.DARK_GRAY));
    }

    /** 段が戻ったときの本文。 */
    static Component recoveredMessage(String skillName, double multiplier) {
        return Component.text("✔ ", NamedTextColor.GREEN)
                .append(Component.text(skillName, NamedTextColor.YELLOW))
                .append(Component.text(" の経験値取得量が ", NamedTextColor.GRAY))
                .append(Component.text(DailyExpRateText.percent(multiplier), NamedTextColor.GREEN))
                .append(Component.text(" に戻りました。", NamedTextColor.GRAY));
    }
}
