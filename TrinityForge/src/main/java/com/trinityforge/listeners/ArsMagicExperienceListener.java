package com.trinityforge.listeners;

import com.trinityforge.TrinityForge;
import com.trinityforge.combat.MagicPipelineDamage;
import com.trinityforge.config.domains.MobLevelTableConfig;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.integration.ars.ArsMagicExperiencePolicy;
import com.trinityforge.integration.ars.ArsProgressionBridge;
import com.trinityforge.pdc.MobData;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;

/**
 * Composite ARS_MAGIC EXP producer: marked Ars damage kills plus marked Ars block breaks.
 *
 * <p>The dedicated {@link MagicPipelineDamage} marker and MAGIC damage cause are both required, so
 * splash potions, commands, and unrelated Bukkit magic cannot impersonate an Ars kill. Block awards
 * require {@link SpellBreakGuard}'s short-lived marker and reuse the gathering progression tables.
 */
public final class ArsMagicExperienceListener implements Listener {

    private final Plugin plugin;
    private final SkillExpConfig skillExp;
    private final NativeSkillCatalog catalog;
    private final PlacedBlockTracker placedBlocks;
    private final MobLevelTableConfig mobLevelTable;

    public ArsMagicExperienceListener(Plugin plugin, SkillExpConfig skillExp,
                                      NativeSkillCatalog catalog, PlacedBlockTracker placedBlocks,
                                      MobLevelTableConfig mobLevelTable) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.skillExp = Objects.requireNonNull(skillExp, "skillExp");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.placedBlocks = Objects.requireNonNull(placedBlocks, "placedBlocks");
        this.mobLevelTable = mobLevelTable;
    }

    /**
     * 魔法での討伐に ARS_MAGIC EXP を付与する。
     *
     * <p><b>2026-08-18 修正(ユーザー報告「魔法で敵を倒しても Ars 魔法の経験値が手に入らなかった」)</b>:
     * ここは以前 {@link #excluded(Player)}(クリエイティブ／スペクテイターを除外)を通していた。
     * ところが<b>武器・弓術の討伐EXP({@code CombatListener#onCombatKill})はゲームモードを一切見ない</b>ので、
     * クリエイティブでは「剣で倒すと EXP が入るのに魔法で倒すと入らない」という非対称になり、
     * 魔法だけが壊れているように見えていた(実サーバのログでも報告時刻の前後で {@code /gmc} が連発されている)。
     * 討伐側は武器と同じ規約(<b>倒したプレイヤーが居ればゲームモードを問わない</b>)へ揃える。
     * ブロック破壊側({@link #onMagicBlockBreak})は採取EXPと同じ規約なので除外を維持する
     * ── クリエイティブの破壊はコストが無く、そちらは {@code NativeSkillExperienceListener} も除外している。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMagicKill(EntityDeathEvent event) {
        if (!skillExp.arsMagicKillExpEnabled()) return;
        var dead = event.getEntity();
        if (mobLevelTable != null && mobLevelTable.suppressesSkillExp(dead.getType())) return;
        Player killer = dead.getKiller();
        if (killer == null) return;
        EntityDamageEvent last = dead.getLastDamageCause();
        if (!(last instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Entity causing = byEntity.getDamageSource().getCausingEntity();
        if (!killGrantsExp(MagicPipelineDamage.isActive(), last.getCause(), killer.getUniqueId(),
                causing == null ? null : causing.getUniqueId(), killer.getGameMode())) {
            return;
        }
        double amount = skillExp.arsMagicKillExp(
                dead.getType().name(), Math.max(0, MobData.of(dead).level()), maxHealth(dead));
        if (amount <= 0.0) return;
        TrinityForge tf = TrinityForge.getInstance();
        double spot = tf == null ? 1.0
                : tf.locationExpDiminishing().multiplierForKillSpot(killer, dead, skillExp,
                        tf.dungeonWorldRegistry().isDungeonWorld(dead.getWorld().getUID()));
        ArsProgressionBridge.grantMagicExp(plugin, killer, amount * spot);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMagicBlockBreak(BlockBreakEvent event) {
        if (!skillExp.arsMagicBlockBreakExpEnabled()
                || !SpellBreakGuard.isSpellBreak(event.getBlock())
                || excluded(event.getPlayer())) {
            return;
        }
        if (placedBlocks.clearIfPlaced(event.getBlock())) return;
        double source = ArsMagicExperiencePolicy.gatheringSourceExp(
                event.getBlock().getType().name(), catalog::get);
        double amount = source * skillExp.arsMagicBlockBreakSourceMultiplier();
        ArsProgressionBridge.grantMagicExp(plugin, event.getPlayer(), amount);
    }

    private static double maxHealth(org.bukkit.entity.LivingEntity entity) {
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? Math.max(0.0, entity.getHealth()) : Math.max(0.0, attribute.getValue());
    }

    private static boolean excluded(Player player) {
        return player == null || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR;
    }

    static boolean isMarkedArsKill(boolean markerActive, EntityDamageEvent.DamageCause cause,
                                   UUID killerId, UUID causingEntityId) {
        return markerActive && cause == EntityDamageEvent.DamageCause.MAGIC
                && killerId != null && killerId.equals(causingEntityId);
    }

    /**
     * 討伐EXPを付与してよいか。{@code killerMode} は<b>意図的に一切見ない</b>
     * (2026-08-18、ユーザー報告「魔法で敵を倒しても Ars 魔法の経験値が手に入らなかった」の修正)。
     *
     * <p>引数に残してあるのは<b>「ゲームモードで弾かない」という決定をテストで固定するため</b>。
     * 武器・弓術の討伐EXP({@code CombatListener#onCombatKill})はゲームモードを見ないので、
     * ここだけクリエイティブを除外すると「剣なら入るのに魔法だと入らない」非対称になる
     * ── それが実際の報告内容だった。ここで再びモードを見る実装に戻すと
     * {@code ArsMagicExperienceListenerAttributionTest} が落ちる。
     */
    static boolean killGrantsExp(boolean markerActive, EntityDamageEvent.DamageCause cause,
                                  UUID killerId, UUID causingEntityId, GameMode killerMode) {
        return isMarkedArsKill(markerActive, cause, killerId, causingEntityId);
    }
}
