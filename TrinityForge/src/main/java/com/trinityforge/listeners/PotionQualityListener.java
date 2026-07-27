package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.AlchemyQualityConfig;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ポーション品質(stat: {@code potion_quality_bonus})と醸造速度(stat: {@code brew_speed_bonus})を
 * 醸造したブルワー本人にだけ反映する(横断制約: かまど/エンチャント/ポーションは実行者限定)。
 *
 * <p>所有者解決は {@link BrewOwnership}(= {@link NativeSkillExperienceListener} が最初に実装した
 * 「手で入れたときだけ刻む」PDCの共有読み取り)に一本化し、二重実装しない。
 *
 * <p><b>読み取り順序</b>: {@link #onBrew} は {@link EventPriority#HIGH} で {@link BrewEvent} を
 * 購読する。{@link NativeSkillExperienceListener#onBrew} は同じイベントを {@link EventPriority#MONITOR}
 * で購読し、EXP付与のあとに所有者PDCを消去する。BukkitのイベントディスパッチはLOWEST&lt;LOW&lt;
 * NORMAL&lt;HIGH&lt;HIGHEST&lt;MONITORの固定順で必ずこの順に呼ばれる(同一プラグイン内の登録順には
 * 依存しない)ため、このリスナーは消去より前に確実に所有者を読める。
 *
 * <p><b>ホッパー式自動醸造(BREW_MODE_AUTO)の扱い</b>: 品質・速度のどちらも、既存のEXP側と同じ
 * {@code alchemy.auto_mult}(既定0.25)で減衰させる。理由: 品質・速度のどちらか片方でも無減衰で
 * ホッパーへ乗せてしまうと放置周回が成立してしまうため、EXP側と同じポリシーへ揃えて一本化した
 * (新規の専用減衰係数を追加するのではなく、既存のalchemy.auto_multを流用)。
 */
public final class PotionQualityListener implements Listener {

    private static final String POTION_QUALITY_BONUS = StatKeys.canonical("potion_quality_bonus");
    private static final String BREW_SPEED_BONUS = StatKeys.canonical("brew_speed_bonus");
    /** Vanillaが醸造開始時にセットする満タンの醸造時間(tick)。この値そのものを「開始直後」の検出に使う。 */
    private static final int VANILLA_BREW_TIME_TICKS = 400;
    /** 速度短縮の下限(短縮しすぎて0/負のtickにならないための安全弁)。90%短縮まで。 */
    private static final double MAX_SPEED_REDUCTION = 0.9;

    private final Plugin plugin;
    private final PlayerStatAggregator aggregator;
    private final AlchemyQualityConfig alchemyQuality;
    private final NativeSkillCatalog progressionCatalog;
    private final BrewOwnership brewOwnership;

    public PotionQualityListener(Plugin plugin, PlayerStatAggregator aggregator,
                                 AlchemyQualityConfig alchemyQuality, NativeSkillCatalog progressionCatalog) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.alchemyQuality = Objects.requireNonNull(alchemyQuality, "alchemyQuality");
        this.progressionCatalog = Objects.requireNonNull(progressionCatalog, "progressionCatalog");
        this.brewOwnership = new BrewOwnership(plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        if (!(event.getBlock().getState() instanceof BrewingStand stand)) {
            return;
        }
        Optional<UUID> ownerId = brewOwnership.ownerOf(stand);
        if (ownerId.isEmpty()) {
            return;
        }
        Player owner = Bukkit.getPlayer(ownerId.get());
        if (owner == null) {
            return;
        }
        double damping = brewOwnership.isAutomated(stand) ? autoMult() : 1.0;
        double qualityPoints = Math.max(0.0, aggregator.aggregate(owner).totalOf(POTION_QUALITY_BONUS)) * damping;
        if (qualityPoints <= 0.0) {
            return;
        }

        double durationAdd = alchemyQuality.durationTicksPerQuality() * qualityPoints;
        int amplifierAdd = (int) Math.floor(alchemyQuality.amplifierPerQuality() * qualityPoints);

        List<ItemStack> results = event.getResults();
        for (int slot = 0; slot < results.size(); slot++) {
            applyQuality(results.get(slot), durationAdd, amplifierAdd, qualityPoints);
        }
    }

    private void applyQuality(ItemStack result, double durationAdd, int amplifierAdd, double qualityPoints) {
        if (result == null || !(result.getItemMeta() instanceof PotionMeta meta)) {
            return;
        }
        List<PotionEffect> effects = meta.getAllEffects();
        if (effects.isEmpty()) {
            return;
        }
        double lingerSplashAdd = isSplashOrLingering(result.getType())
                ? alchemyQuality.lingeringSplashDurationTicksPerQuality() * qualityPoints
                : 0.0;
        int durationDelta = (int) Math.round(durationAdd + lingerSplashAdd);

        List<PotionEffect> boosted = new ArrayList<>(effects.size());
        for (PotionEffect effect : effects) {
            PotionEffect updated = effect;
            if (!effect.getType().isInstant() && durationDelta != 0) {
                updated = updated.withDuration(Math.max(1, updated.getDuration() + durationDelta));
            }
            if (amplifierAdd > 0) {
                updated = updated.withAmplifier(updated.getAmplifier() + amplifierAdd);
            }
            boosted.add(updated);
        }
        // 段階の異なる効果を一意に確定させるため、baseはWATERへ倒して全てcustom effectsで表現する
        // (BrewUnlockListener#makeCustomPotion と同じ既存パターン)。
        meta.setBasePotionType(PotionType.WATER);
        meta.clearCustomEffects();
        for (PotionEffect effect : boosted) {
            meta.addCustomEffect(effect, true);
        }
        result.setItemMeta(meta);
    }

    private static boolean isSplashOrLingering(Material type) {
        return type == Material.SPLASH_POTION || type == Material.LINGERING_POTION;
    }

    private double autoMult() {
        SkillCatalogEntry alchemy = progressionCatalog.get(SkillId.ALCHEMY);
        double mult = alchemy.rate("alchemy.auto_mult", 0.25);
        return mult > 0.0 ? mult : 0.25;
    }

    // ---- 醸造速度(brew_speed_bonus): バニラが醸造を開始した直後(getBrewingTime()==満タン)を検出し、
    //      所有者のstatに応じて残り時間を短縮する。BrewUnlockListenerのカスタム強制開始と同じく、
    //      クリック/ドラッグ/ホッパー投入の直後を1tick遅延で確認する。 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrewerClick(InventoryClickEvent event) {
        if (event.getInventory() instanceof BrewerInventory brew) {
            scheduleSpeedCheck(brew);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrewerDrag(InventoryDragEvent event) {
        if (event.getInventory() instanceof BrewerInventory brew) {
            scheduleSpeedCheck(brew);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        Inventory dest = event.getDestination();
        if (dest instanceof BrewerInventory brew) {
            scheduleSpeedCheck(brew);
        }
    }

    private void scheduleSpeedCheck(BrewerInventory brew) {
        plugin.getServer().getScheduler().runTask(plugin, () -> applySpeed(brew));
    }

    private void applySpeed(BrewerInventory brew) {
        if (!(brew.getHolder() instanceof BrewingStand stand)) {
            return;
        }
        // ちょうど満タン=バニラが「今tick開始した」ときだけ短縮する。それ以外(進行中/未開始)は無視。
        if (stand.getBrewingTime() != VANILLA_BREW_TIME_TICKS) {
            return;
        }
        Optional<UUID> ownerId = brewOwnership.ownerOf(stand);
        if (ownerId.isEmpty()) {
            return;
        }
        Player owner = Bukkit.getPlayer(ownerId.get());
        if (owner == null) {
            return;
        }
        double damping = brewOwnership.isAutomated(stand) ? autoMult() : 1.0;
        double speedBonus = Math.max(0.0, aggregator.aggregate(owner).totalOf(BREW_SPEED_BONUS)) * damping;
        if (speedBonus <= 0.0) {
            return;
        }
        double reduction = Math.min(MAX_SPEED_REDUCTION, speedBonus);
        int reduced = (int) Math.max(1, Math.round(VANILLA_BREW_TIME_TICKS * (1.0 - reduction)));
        stand.setBrewingTime(reduced);
        stand.update();
    }
}
