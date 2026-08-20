package com.trinityforge.listeners;

import com.trinityforge.combat.ProjectileWeapon;
import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code type: gear-use} のアチーブメント(2026-08-16 の再構築で追加)へ「実戦で使った装備」を記録する。
 *
 * <p><b>なぜクラフト統計ではないのか</b>: 武器/防具のティア到達を {@code CRAFT_ITEM} 統計で書くと、
 * <b>使用可能レベルに達していなくても</b>作っただけで解除できてしまう。ここでは
 * 「その武器でダメージを与えた」「その防具を着て被弾した」だけを数えるので、
 * 装備できない品を作っても進まない。
 *
 * <p><b>記録する値</b>: {@code weapon:<id>} / {@code armor:<id>}。{@code <id>} は TF/Ars の
 * カタログID({@link CrossPluginItemResolver#idOf})が刻まれていればそれ、無ければ {@code Material} 名。
 * どちらの書き方でも yml から指し示せるように<b>両方</b>を候補として突き合わせる。
 *
 * <p><b>PDC を膨らませない</b>: プレイヤーPDCへ書くのは
 * {@code progression/achievements.yml} の {@code gear-use.items} に<b>実際に書かれているトークンだけ</b>。
 * 素振り一回ごとに全 Material が溜まると PDC が青天井になるので、図鑑
 * ({@link CollectionListener} の監視集合)と同じ「設定から参照されているものだけ記録する」方式に揃えてある。
 * 監視集合は reload で差し替わるため、config スナップショットの同一性でキャッシュする。
 *
 * <p><b>クリエイティブ/スペクテイターは記録しない</b>。図鑑と同じ理由(アイテム欄から出した品で
 * ティアを無料で埋められる)。ただしここはアイテムの出自までは追わない — 装備は
 * 「使用可能レベルのゲート」を別途通るので、図鑑ほど厳密な出自追跡は要らない。
 */
public final class GearUseListener implements Listener {

    private final AchievementsConfig achievements;

    /**
     * 監視集合のキャッシュ。キーは {@code achievements.achievements()} が返すリストの<b>同一性</b>
     * ({@code /trinityforge reload} で新しいインスタンスに差し替わる)。{@link CollectionListener}
     * の {@code WatchedSnapshot} と同じ方式。
     */
    private volatile WatchedSnapshot watchedCache;

    /**
     * @param source  キャッシュ作成時点の {@code achievements.achievements()} インスタンス
     * @param watched そこから組んだ監視トークン集合(不変)
     */
    private record WatchedSnapshot(List<AchievementsConfig.Achievement> source, Set<String> watched) {
    }

    public GearUseListener(AchievementsConfig achievements) {
        this.achievements = Objects.requireNonNull(achievements, "achievements");
    }

    /**
     * MONITOR/ignoreCancelled: 実際に通ったダメージだけを数える。キャンセルされた攻撃
     * (PvP 抑止・無敵時間・ダンジョンのゲート)で進捗が付くと「殴れない相手を殴って解除」できてしまう。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Set<String> watched = watched();
        if (watched.isEmpty()) {
            return;
        }
        recordWeapon(event, watched);
        recordArmor(event, watched);
    }

    private void recordWeapon(EntityDamageByEntityEvent event, Set<String> watched) {
        Player attacker = attackerOf(event);
        if (excluded(attacker)) {
            return;
        }
        ItemStack weapon = weaponOf(event, attacker);
        record(attacker, "weapon:", weapon, watched);
    }

    private void recordArmor(EntityDamageByEntityEvent event, Set<String> watched) {
        if (!(event.getEntity() instanceof Player victim) || excluded(victim)) {
            return;
        }
        EntityEquipment equipment = victim.getEquipment();
        if (equipment == null) {
            return;
        }
        for (ItemStack piece : equipment.getArmorContents()) {
            record(victim, "armor:", piece, watched);
        }
    }

    /** 直接殴りとプレイヤーが撃った投射物の両方を拾う(弓術ティアは投射物経路しか来ない)。 */
    private static Player attackerOf(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    /**
     * 投射物では<b>発射時に刻んだ武器</b>({@link ProjectileWeapon#read})を優先する。
     * 着弾時のメインハンドを見ると、撃ってから持ち替えただけで別の武器が記録される。
     */
    private static ItemStack weaponOf(EntityDamageByEntityEvent event, Player attacker) {
        if (attacker == null) {
            return null;
        }
        if (event.getDamager() instanceof Projectile projectile) {
            ItemStack stored = ProjectileWeapon.read(projectile).orElse(null);
            if (stored != null) {
                return stored;
            }
        }
        return attacker.getInventory().getItemInMainHand();
    }

    private static boolean excluded(Player player) {
        if (player == null) {
            return true;
        }
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
    }

    private static void record(Player player, String prefix, ItemStack stack, Set<String> watched) {
        if (player == null || stack == null || stack.getType().isAir()) {
            return;
        }
        PlayerData data = null;
        // カタログID と Material 名の両方を候補にする(yml がどちらで書かれていても効くように)。
        for (String id : List.of(
                CrossPluginItemResolver.idOf(stack).orElse(""),
                stack.getType().name())) {
            if (id.isBlank()) {
                continue;
            }
            String token = prefix + id;
            if (!watched.contains(token)) {
                continue;
            }
            if (data == null) {
                data = PlayerData.of(player);
            }
            data.recordGearUsed(token);
        }
    }

    private Set<String> watched() {
        List<AchievementsConfig.Achievement> source = achievements.achievements();
        WatchedSnapshot cached = watchedCache;
        if (cached != null && cached.source() == source) {
            return cached.watched();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (AchievementsConfig.Achievement achievement : achievements.gearUseAchievements()) {
            AchievementsConfig.Trigger trigger = achievement.trigger();
            AchievementsConfig.GearSlot slot = trigger.gearSlot();
            if (slot == null) {
                continue;
            }
            for (String item : trigger.gearItems()) {
                tokens.add(slot.tokenPrefix() + item);
            }
        }
        Set<String> result = Set.copyOf(tokens);
        watchedCache = new WatchedSnapshot(source, result);
        return result;
    }
}
