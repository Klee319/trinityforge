package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.AlchemyQualityConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.BrewRecipeSupport;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.VanillaLuckEffect;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.persistence.PersistentDataType;
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
    /**
     * 速度短縮の下限(短縮しすぎて0/負のtickにならないための安全弁)。90%短縮まで。
     * 段階1宣言(2026-07-27): {@code stats/lore.yml} の {@code brew-speed-bonus.limits.cap-ref} が
     * 参照する昇格済み定数(可視性のみpublicへ変更、値・挙動は不変)。
     */
    public static final double MAX_SPEED_REDUCTION = 0.9;

    private final Plugin plugin;
    private final PlayerStatAggregator aggregator;
    private final AlchemyQualityConfig alchemyQuality;
    private final NativeSkillCatalog progressionCatalog;
    private final BrewOwnership brewOwnership;
    /** 幸運のポーションぶんの換算レート({@code luck-potion-quality-per-level})の参照元。null可=幸運を加算しない。 */
    private final QualityConfig quality;

    public PotionQualityListener(Plugin plugin, PlayerStatAggregator aggregator,
                                 AlchemyQualityConfig alchemyQuality, NativeSkillCatalog progressionCatalog) {
        this(plugin, aggregator, alchemyQuality, progressionCatalog, null);
    }

    public PotionQualityListener(Plugin plugin, PlayerStatAggregator aggregator,
                                 AlchemyQualityConfig alchemyQuality, NativeSkillCatalog progressionCatalog,
                                 QualityConfig quality) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.alchemyQuality = Objects.requireNonNull(alchemyQuality, "alchemyQuality");
        this.progressionCatalog = Objects.requireNonNull(progressionCatalog, "progressionCatalog");
        this.brewOwnership = new BrewOwnership(plugin);
        this.quality = quality;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        // 品質より先に「延長/強化」を見る。品質はここで二重に乗せない(下の return)。
        if (rewriteCustomEffectUpgrade(event)) {
            return;
        }
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
        // 幸運のポーションぶんはステと同じ「品質ポイント」なので、自動醸造の減衰も同じく掛ける
        // (片方だけ無減衰にするとホッパー放置が成立してしまう — このリスナの既存ポリシー)。
        double statPoints = Math.max(0.0, aggregator.aggregate(owner).totalOf(POTION_QUALITY_BONUS));
        double qualityPoints = (statPoints + luckPotionQualityBonus(owner)) * damping;
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

    /**
     * 幸運のポーションぶんの品質ポイント(2026-08-20 ユーザー要望「醸造・作業台・儀式の各品質ptも
     * 幸運のポーションレベルに応じて上がるように」)。効果レベル ×
     * {@code stats/quality.yml} の {@code luck-potion-quality-per-level}。
     * config 未配線(テスト等)・0 設定・未付与なら 0。
     */
    private double luckPotionQualityBonus(Player brewer) {
        if (quality == null) {
            return 0.0;
        }
        double perLevel = quality.luckPotionQualityPerLevel();
        return perLevel <= 0.0 ? 0.0 : VanillaLuckEffect.levelOf(brewer) * perLevel;
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
        // (BrewRecipeSupport#customPotion と同じ既存パターン。2026-07-31 に
        //  BrewUnlockListener#makeCustomPotion からそちらへ移設された)。
        //
        // ⚠️ 倒す【前】の種類を焼き付ける (2026-08-20 / W-170)。EXP を出す
        //   NativeSkillExperienceListener#onBrew は MONITOR = このリスナ(HIGH)の【後】に走り、
        //   完成品の base から alchemy_progression.yml の brew_result を引く。倒した後は常に WATER で
        //   表に無いため、どの効果ポーションを作っても alchemy_brew_exp の定額へ落ちて【EXPが一律】に
        //   なっていた。品質0のプレイヤーはここを通らないので、スキルツリーで品質を取った人だけ壊れる。
        stampBrewSourcePotion(meta);
        meta.setBasePotionType(PotionType.WATER);
        meta.clearCustomEffects();
        for (PotionEffect effect : boosted) {
            meta.addCustomEffect(effect, true);
        }
        // baseをWATERへ倒すと【名前も「水入り瓶」に化ける】。ポーション名はベースの種類からしか
        // 引かれないので(PotionContents#getName)、効果を足しても名前は戻らない。
        // 2026-08-18 実サーバ報告「進捗バーも動いて完了音も鳴るのに水入り瓶が完成する」の真因がこれ。
        // 既に名前が付いている(ゲート付き醸造の完成品など)ならそちらを尊重する。
        if (!meta.hasDisplayName()) {
            meta.displayName(BrewRecipeSupport.potionDisplayName(result.getType(), boosted));
        }
        result.setItemMeta(meta);
    }

    /**
     * ベースを {@code WATER} へ倒す直前の {@link PotionType} を
     * {@link PdcKeys#ITEM_BREW_SOURCE_POTION} へ焼き付ける (2026-08-20 / W-170)。
     *
     * <p>倒した後は {@code getBasePotionType()} が常に {@code WATER} を返すので、EXP 側
     * ({@code NativeSkillExperienceListener#onBrew}) が {@code brew_result} を引けなくなる。
     * <b>既に倒れている（＝WATER）ものには書かない</b> — 延長/強化のように「一度品質を乗せた
     * ポーションをもう一度醸造する」経路で、上書きすると前の記録を消してしまうため。
     */
    private static void stampBrewSourcePotion(PotionMeta meta) {
        if (!meta.hasBasePotionType()) {
            return;
        }
        PotionType original = meta.getBasePotionType();
        if (original == null || original == PotionType.WATER) {
            return;
        }
        meta.getPersistentDataContainer()
                .set(PdcKeys.ITEM_BREW_SOURCE_POTION, PersistentDataType.STRING, original.name());
    }

    private static boolean isSplashOrLingering(Material type) {
        return type == Material.SPLASH_POTION || type == Material.LINGERING_POTION;
    }

    // ---- 延長(レッドストーン) / 強化(グロウストーンダスト) の救済 -------------------------
    //
    // なぜ必要か(2026-08-18 / W-108):
    //   TF は品質を乗せるときもゲート付き醸造の完成品を作るときも、段階の違う効果を一意に
    //   確定させるためにベースを WATER へ倒して全部カスタム効果で表現する
    //   (#applyQuality / BrewRecipeSupport#customPotion)。ところがバニラの醸造表は
    //   「(ベースの PotionType, 素材) → PotionType」でしか引かないので、
    //   WATER ベースのポーションにレッドストーンを入れると **ありふれたポーション(MUNDANE)**、
    //   グロウストーンダストなら **濃厚なポーション(THICK)** になる。
    //   バニラの mix はカスタム効果を引き継がないので、**効果が丸ごと消える**。
    //   ＝ 錬金術ステを持っているプレイヤーほど、自分で作ったポーションを延長・強化できず、
    //     しかも試すと中身を失う。0 のプレイヤーは WATER へ倒されないのでこの症状も出ない
    //     (「一部の人だけ壊れる」に見える。W-83 と同じ現れ方)。
    //
    // なぜ PotionMix を登録しないのか:
    //   WATER + レッドストーン / グロウストーンダストは **バニラに実在する組**なので、
    //   醸造そのものは何もしなくても始まり BrewEvent も飛ぶ。必要なのは「結果の差し替え」だけで、
    //   mix を足すと素の水入り瓶の挙動まで奪う危険が増えるだけ
    //   (BrewPotionMixRegistrar#vanillaCollision がこの2素材を弾いているのと同じ理由)。
    //
    // 倍率はバニラに合わせる: 延長 3:00→8:00 (8/3倍)、強化 3:00→1:30 (1/2倍) + 効力+1。
    // 即時効果(回復/ダメージ)には持続時間が無いので延長側は掛けない。

    /** 延長 3:00 → 8:00 のバニラ比。 */
    private static final double EXTEND_DURATION_RATIO = 8.0 / 3.0;
    /** 強化 3:00 → 1:30 のバニラ比。 */
    private static final double AMPLIFY_DURATION_RATIO = 0.5;

    /** どちらの加工を受けたか。バニラ同様「延長と強化は排他・各1回まで」。 */
    private enum BrewUpgrade { EXTENDED, AMPLIFIED }

    private NamespacedKey upgradeKey() {
        return new NamespacedKey(plugin, "brew_upgrade");
    }

    /**
     * WATER ベース＋カスタム効果のポーションに対する延長/強化を、効果を保ったまま書き戻す。
     *
     * @return この醸造を延長/強化として扱ったなら {@code true}(呼び出し側は品質適用へ進まない)
     */
    private boolean rewriteCustomEffectUpgrade(BrewEvent event) {
        BrewerInventory inv = event.getContents();
        // ⚠️ getIngredient() ではなく getItem(3) で読む。実サーバでは等価(醸造台のスロット配置は
        // 0..2=ビン / 3=素材 / 4=燃料 で固定)だが、MockBukkit の BrewerInventoryMock は
        // 素材が未設定のとき getIngredient() が IllegalStateException を投げるため、
        // 素材を置かない既存テスト(品質・速度側)を巻き添えで落としてしまう。
        ItemStack ingredient = inv.getItem(3);
        if (ingredient == null) {
            return false;
        }
        BrewUpgrade upgrade = switch (ingredient.getType()) {
            case REDSTONE -> BrewUpgrade.EXTENDED;
            case GLOWSTONE_DUST -> BrewUpgrade.AMPLIFIED;
            default -> null;
        };
        if (upgrade == null) {
            return false;
        }

        List<ItemStack> results = event.getResults();
        boolean handled = false;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = inv.getItem(slot);
            if (bottle == null || !BrewRecipeSupport.isPotionContainer(bottle.getType())
                    || !(bottle.getItemMeta() instanceof PotionMeta meta)
                    || meta.getBasePotionType() != PotionType.WATER
                    || !meta.hasCustomEffects()) {
                continue; // 素の水入り瓶やバニラのポーションはバニラの結果に任せる
            }
            ItemStack rebuilt = bottle.clone();
            rebuilt.setAmount(1);
            applyBrewUpgrade(rebuilt, upgrade);
            while (results.size() <= slot) {
                results.add(null);
            }
            results.set(slot, rebuilt);
            handled = true;
        }
        return handled;
    }

    /**
     * 1本ぶんの延長/強化。<b>既に加工済みなら中身を変えずにそのまま返す</b> —— バニラに任せると
     * ありふれた/濃厚なポーションへ化けて効果が消えるので、「何も起きない」で止めるのが最小の被害。
     */
    private void applyBrewUpgrade(ItemStack stack, BrewUpgrade upgrade) {
        if (!(stack.getItemMeta() instanceof PotionMeta meta)) {
            return;
        }
        if (meta.getPersistentDataContainer().has(upgradeKey(), PersistentDataType.STRING)) {
            return;
        }
        List<PotionEffect> upgraded = new ArrayList<>();
        for (PotionEffect effect : meta.getCustomEffects()) {
            PotionEffect updated = effect;
            if (!effect.getType().isInstant()) {
                double ratio = upgrade == BrewUpgrade.EXTENDED
                        ? EXTEND_DURATION_RATIO : AMPLIFY_DURATION_RATIO;
                updated = updated.withDuration(Math.max(1, (int) Math.round(updated.getDuration() * ratio)));
            }
            if (upgrade == BrewUpgrade.AMPLIFIED) {
                updated = updated.withAmplifier(updated.getAmplifier() + 1);
            }
            upgraded.add(updated);
        }
        meta.clearCustomEffects();
        for (PotionEffect effect : upgraded) {
            meta.addCustomEffect(effect, true);
        }
        meta.getPersistentDataContainer().set(upgradeKey(), PersistentDataType.STRING, upgrade.name());
        if (!meta.hasDisplayName()) {
            meta.displayName(BrewRecipeSupport.potionDisplayName(stack.getType(), upgraded));
        }
        stack.setItemMeta(meta);
    }

    private double autoMult() {
        SkillCatalogEntry alchemy = progressionCatalog.get(SkillId.ALCHEMY);
        double mult = alchemy.rate("alchemy.auto_mult", 0.25);
        return mult > 0.0 ? mult : 0.25;
    }

    // ---- 醸造速度(brew_speed_bonus): バニラが醸造を開始した直後(getBrewingTime()==満タン)を検出し、
    //      所有者のstatに応じて残り時間を短縮する。クリック/ドラッグ/ホッパー投入の直後を
    //      1tick遅延で確認する(2026-07-31: かつてここにあった「BrewUnlockListenerのカスタム強制開始と
    //      同じ方式」という記述は、その強制開始が PotionMix 登録方式へ置き換わって消えたため削除)。 ----

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
