package com.trinityforge.progression;

import com.trinityforge.config.domains.LevelBroadcastConfig;
import com.trinityforge.progression.event.TrinitySkillLevelUpEvent;
import com.trinityforge.text.MiniText;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * 節目レベルアップの全体アナウンス (2026-08-16, {@code progression/level-broadcast.yml})。
 *
 * <p>元は ValhallaMMO アドオン {@code ValTopBoard} の {@code level-up-broadcast} 機能。
 * TF は自分のスキルレベルアップを {@link TrinitySkillLevelUpEvent} で知っているので本体側へ移した。
 *
 * <h2>なぜ即座に流さず 1 tick 溜めるのか（アナウンス氾濫の抑止）</h2>
 * {@link TrinitySkillLevelUpEvent} は<b>到達レベルごとに 1 回</b>発火する（Lv9→Lv31 なら 22 回）。
 * そのまま素直に節目判定すると Lv10/20/30 の 3 行が一度に流れる。さらに管理コマンドで
 * 全スキルを一括で上げると、スキル数 × 節目数だけ行が出てチャットが埋まる。
 * そこで次の 2 段で抑える。
 * <ol>
 *   <li><b>(プレイヤー, スキル) ごとに最高到達レベルだけへ畳む。</b> 発火は同一 tick 内で
 *       連続する（{@code BukkitSkillLevelUpDispatcher#fire} が 1 ループで回す）ので、
 *       次の tick に 1 回だけフラッシュすれば「1 回の付与＝1 行」に落ちる。
 *       最初の節目ではなく<b>最高</b>を採るのは、Lv31 に到達した人へ「Lv10 到達」と
 *       流れるのが実態とずれるため。</li>
 *   <li><b>1 フラッシュあたりの行数に上限</b>（{@code max-announcements-per-batch}）。
 *       超過分は黙って捨てる（件数だけ FINE ログへ）。</li>
 * </ol>
 *
 * <p><b>本人向けのレベルアップ通知とは別経路。</b> 音/チャット/タイトルを本人にだけ出すのは
 * {@link SkillExpFeedbackService}（{@code stats/skill-exp.yml} の {@code level-up.*}）で、
 * こちらは全体行のみ。設定も別ファイルに分けてあるので片方だけ有効にできる。
 *
 * <p>状態（{@link #pending}）はメインスレッドからのみ触る。{@link TrinitySkillLevelUpEvent} は
 * ディスパッチャがメインスレッドへ寄せてから発火する契約なので、この前提は満たされる。
 */
public final class SkillLevelBroadcastListener implements Listener {

    private final Plugin plugin;
    private final LevelBroadcastConfig config;
    /** スキルID → 表示名（{@code skilltree/*.yml} の display_name）。解決できなければ ID をそのまま返す。 */
    private final Function<String, String> skillDisplayName;

    /** (プレイヤー, スキル) → その tick で到達した最高の節目レベル。挿入順＝最初に節目へ届いた順。 */
    private final Map<PendingKey, Integer> pending = new LinkedHashMap<>();
    private boolean flushScheduled;

    /** 効果音の解決結果キャッシュ。{@code null} = 未解決 or 解決失敗。 */
    private String resolvedSoundSource;
    private Key resolvedSoundKey;
    private boolean soundWarned;

    public SkillLevelBroadcastListener(Plugin plugin, LevelBroadcastConfig config,
                                       Function<String, String> skillDisplayName) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.skillDisplayName = Objects.requireNonNull(skillDisplayName, "skillDisplayName");
    }

    /**
     * {@code MONITOR} で購読するのは、このリスナーが何も変更しない純粋な通知だから
     * （他プラグインの購読者より後で構わない）。イベントはキャンセル不可なので
     * {@code ignoreCancelled} は意味を持たない。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSkillLevelUp(TrinitySkillLevelUpEvent event) {
        String skillId = event.getSkillId() == null
                ? ""
                : event.getSkillId().trim().toUpperCase(Locale.ROOT);
        if (!config.shouldAnnounce(skillId, event.getNewLevel())) {
            return;
        }
        pending.merge(new PendingKey(event.getPlayer().getUniqueId(), skillId),
                event.getNewLevel(), Math::max);
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (flushScheduled) {
            return;
        }
        flushScheduled = true;
        try {
            // runTask ではなく 1 tick 後。runTask は実装によっては呼び出し tick 内で走りうるので、
            // 「同一 tick に連続発火する分をまとめる」という前提が壊れる（＝畳み込みが効かず
            // Lv10/20/30 が個別に流れる）。1 tick の遅延はこの機構の要件そのもの。
            plugin.getServer().getScheduler().runTaskLater(plugin, this::flush, 1L);
        } catch (RuntimeException ex) {
            // プラグイン無効化中などでスケジュールできない場合は、溜め込まずその場で流す
            // （祝う行が消えるより 1 tick 早く出るほうが害が無い）。
            flushScheduled = false;
            flush();
        }
    }

    /**
     * 溜まっている節目をまとめて流す（{@link #scheduleFlush()} が 1 tick 後に呼ぶ）。
     * テストから直接呼べるよう package-private。
     */
    void flush() {
        flushScheduled = false;
        if (pending.isEmpty()) {
            return;
        }
        List<Map.Entry<PendingKey, Integer>> batch = new ArrayList<>(pending.entrySet());
        pending.clear();

        int cap = Math.max(1, config.maxAnnouncementsPerBatch());
        int emitted = 0;
        int dropped = 0;
        for (Map.Entry<PendingKey, Integer> entry : batch) {
            if (emitted >= cap) {
                dropped++;
                continue;
            }
            Player player = plugin.getServer().getPlayer(entry.getKey().playerId());
            if (player == null) {
                continue; // 到達直後にログアウト/サーバ移動した場合は流さない
            }
            announce(player, entry.getKey().skillId(), entry.getValue());
            emitted++;
        }
        if (emitted > 0) {
            // 効果音は 1 フラッシュにつき 1 回。行ごとに鳴らすと同時到達で音が重なって鳴る。
            playSound();
        }
        if (dropped > 0) {
            plugin.getLogger().fine("[" + LevelBroadcastConfig.PATH + "] 節目アナウンスが上限("
                    + cap + ")を超えたため " + dropped + " 件を省略しました。");
        }
    }

    /**
     * 1 件を全体へ流す。
     *
     * <p><b>{@code Bukkit.getServer().sendMessage(Component)} は使わない。</b> MockBukkit では
     * この API が未実装で {@code UnimplementedOperationException}（＝ JUnit では SKIPPED）になるため、
     * それを使うと「全体告知が実際に届くこと」を固定する回帰テストが原理的に書けない
     * （{@code CollectionServiceTest#retroactiveRecordSuppressesEntryChatAndTierBroadcast} の
     * javadoc に実測記録あり）。オンライン全員へ個別に送れば同じ結果になり、しかもテストできる。
     * コンソールにも残したいので、素のテキストだけ INFO ログへ出す。
     */
    private void announce(Player player, String skillId, int level) {
        Component skillDisplay = MiniText.render(skillDisplayName.apply(skillId), null);
        Component line = LevelBroadcastFormat.render(config.message(), player.getName(),
                skillDisplay, level, this::warnOnce);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            online.sendMessage(line);
        }
        plugin.getLogger().info(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(line));
    }

    /**
     * アナウンス音を全員へ鳴らす。無効・解決不能なら何もしない。
     *
     * <p>解決できない音名で例外を投げたり通知そのものを落としたりしない
     * （祝う行は出したいので、音だけ黙って無くす）。警告は設定値ごとに 1 回だけ。
     */
    private void playSound() {
        if (!config.soundEnabled()) {
            return;
        }
        Key key = soundKeyForCurrentConfig();
        if (key == null) {
            return;
        }
        Sound sound = Sound.sound(key, Sound.Source.PLAYER, config.soundVolume(), config.soundPitch());
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            try {
                online.playSound(sound);
            } catch (RuntimeException ex) {
                // 再生できない環境（API 未実装のテストサーバ等）でも通知本体は止めない。
                return;
            }
        }
    }

    /** 設定値が変わったら（reload 含む）解決し直す。 */
    private Key soundKeyForCurrentConfig() {
        String raw = config.sound();
        if (!Objects.equals(raw, resolvedSoundSource)) {
            resolvedSoundSource = raw;
            resolvedSoundKey = soundKey(raw);
            soundWarned = false;
        }
        if (resolvedSoundKey == null && !soundWarned) {
            soundWarned = true;
            plugin.getLogger().warning("[" + LevelBroadcastConfig.PATH + "] sound.key を解決できません: "
                    + raw + " — このアナウンスは無音で流します。");
        }
        return resolvedSoundKey;
    }

    /**
     * 設定の音名を Adventure の {@link Key} へ。{@code null} = 解決不能。
     *
     * <p><b>enum 定数名とレジストリキーは機械的に変換できない。</b>
     * {@code ENTITY_GENERIC_EXPLODE} ⇔ {@code entity.generic.explode} は素朴な {@code _}→{@code .}
     * で解けるが、{@code ENTITY_IRON_GOLEM_ATTACK} ⇔ {@code entity.iron_golem.attack} は解けない
     * （モブ名の中の {@code _} は残る）。そこでレジストリを走査して
     * 「キーの {@code .} を {@code _} にして大文字化」した索引を作り、変換規則を推測せずに引く
     * （{@code combat.MobAbilityExecutor#soundIndex} と同じ手口）。
     *
     * <p>レジストリを引けない/空の環境では索引を使わず、綴りから直接 {@link Key} を組む
     * （fail-open。ここで null に倒すと、テスト用サーバ実装でだけ「無音＋警告」になる）。
     */
    static Key soundKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        Map<String, Key> index = soundIndex();
        if (!index.isEmpty()) {
            Key hit = index.get(s.toUpperCase(Locale.ROOT));
            if (hit != null) {
                return hit;
            }
            // レジストリが引ける環境で見つからない＝実在しない音名。警告して無音に倒す。
            return null;
        }
        try {
            if (s.contains(":")) {
                return Key.key(s);
            }
            if (s.indexOf('_') >= 0 && s.equals(s.toUpperCase(Locale.ROOT))) {
                s = s.toLowerCase(Locale.ROOT).replace('_', '.');
            }
            return Key.key(s);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static volatile Map<String, Key> soundIndex;

    private static Map<String, Key> soundIndex() {
        Map<String, Key> cached = soundIndex;
        if (cached != null) {
            return cached;
        }
        Map<String, Key> built = new java.util.HashMap<>();
        try {
            for (org.bukkit.Sound sound : org.bukkit.Registry.SOUNDS) {
                // Sound#getKey() は削除予定なのでレジストリ側から引く。
                org.bukkit.NamespacedKey namespaced = org.bukkit.Registry.SOUNDS.getKey(sound);
                if (namespaced == null) {
                    continue;
                }
                Key key = Key.key(namespaced.getNamespace(), namespaced.getKey());
                String path = namespaced.getKey();
                built.put(path.toUpperCase(Locale.ROOT).replace('.', '_'), key);
                built.put(path.toUpperCase(Locale.ROOT), key);
                built.put(namespaced.toString().toUpperCase(Locale.ROOT), key);
            }
        } catch (RuntimeException | LinkageError ex) {
            // レジストリを走査できない環境。索引無し(空)として綴り解決へ落とす。
            built.clear();
        }
        Map<String, Key> immutable = Map.copyOf(built);
        // 空の索引はキャッシュしない（サーバ起動途中に呼ばれた場合、後から引けるようにするため）。
        if (!immutable.isEmpty()) {
            soundIndex = immutable;
        }
        return immutable;
    }

    private void warnOnce(String message) {
        plugin.getLogger().warning("[" + LevelBroadcastConfig.PATH + "] " + message);
    }

    private record PendingKey(UUID playerId, String skillId) {
    }
}
