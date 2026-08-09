package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.MobTypesConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.mobs.MobDropEntry;
import com.trinityforge.mobs.MobDropRoller;
import com.trinityforge.mobs.MobTypeDefinition;
import com.trinityforge.pdc.MobData;
import com.trinityforge.stats.CraftQualityPolicy;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.MaterialTier;
import com.trinityforge.stats.PlayerMobDropBonusSource;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * combat/mob-types.yml で定義されたEntityTypeのバニラモブが死亡した際、その{@code drops:}一覧に従って
 * 追加ドロップを生成するリスナー。対象条件は「死亡entityのEntityTypeがmob-typesに定義済み」であること
 * (PDCのMOB_LEVEL有無は問わない)。装備品には {@link ItemFactory#stamp} でrollSeed+品質を刻印する。
 * 既存ドロップは一切削除せず、{@code event.getDrops()}へ追加するのみ。
 *
 * <p>追加テーブルはプレイヤーが倒した場合のみ抽選する。溶岩・落下・モブ同士の戦闘など
 * {@link LivingEntity#getKiller()} がnullの死亡では何も追加しない。また、AFK対策の
 * {@link #setDropGate(java.util.function.Predicate)} がkillerを抑止した場合も、バニラドロップには
 * 触れず追加分だけを止める。
 *
 * <p><b>カスタムアイテム(2026-08-01 U13):</b> {@code drops[].material} が {@code custom:<id>} の場合は
 * {@link CrossPluginItemResolver#create(String)} で組み立てる({@code MobOverrideDropListener} /
 * {@code MobLevelTableListener} と同じ形)。解決できなければ WARNING を1回出してその抽選だけ捨てる
 * (fail-open — 他のドロップやバニラドロップは巻き込まない)。品質の刻印はバニラ Material のときだけ
 * 行う: カタログ品は resolver 内の {@code ItemFactory#create} が既に rollSeed と品質を打っているので、
 * ここで {@code stamp} すると上書きになる。
 */
public final class MobTypeDropListener implements Listener {

    private static final Logger LOG = Logger.getLogger(MobTypeDropListener.class.getName());

    private final MobTypesConfig mobTypesConfig;
    private final CraftQualityConfig craftQuality;
    private final QualityConfig quality;
    private final ItemFactory itemFactory;
    private final PlayerMobDropBonusSource mobDropBonus;
    // 品質基準値 (item-stats quality-mode-offset) の参照元。null可 (テスト/未配線時はオフセット0)。
    private final ItemStatsConfig itemStats;
    /**
     * {@code custom:<id>} ドロップの解決先。null可 = 未配線。null のまま custom: ドロップを引くと
     * 「設定できるのに永久にドロップしない」無言失敗になるので、そのときは専用の WARNING を出す
     * (配線は {@code TrinityForge.java} の {@code new MobTypeDropListener(...)} に
     * {@code crossPluginItemResolver} を渡すこと)。
     */
    private final CrossPluginItemResolver itemResolver;
    private final SplittableRandom random;
    private volatile java.util.function.Predicate<Player> dropGate;
    /** レベル差の足きり + ドロップ増加ステ(2026-08-09)。null可 = 未配線なら素の抽選結果のまま。 */
    private volatile KillRewardAdjuster killRewardAdjuster;
    /** 未配線 WARNING を毎キル出さないためのラッチ。 */
    private volatile boolean unwiredResolverWarned;

    public MobTypeDropListener(MobTypesConfig mobTypesConfig, CraftQualityConfig craftQuality,
                                QualityConfig quality, ItemFactory itemFactory) {
        this(mobTypesConfig, craftQuality, quality, itemFactory, null, null, null, new SplittableRandom());
    }

    public MobTypeDropListener(MobTypesConfig mobTypesConfig, CraftQualityConfig craftQuality,
                                QualityConfig quality, ItemFactory itemFactory,
                                PlayerMobDropBonusSource mobDropBonus) {
        this(mobTypesConfig, craftQuality, quality, itemFactory, mobDropBonus, null, null, new SplittableRandom());
    }

    public MobTypeDropListener(MobTypesConfig mobTypesConfig, CraftQualityConfig craftQuality,
                                QualityConfig quality, ItemFactory itemFactory,
                                PlayerMobDropBonusSource mobDropBonus, ItemStatsConfig itemStats) {
        this(mobTypesConfig, craftQuality, quality, itemFactory, mobDropBonus, itemStats, null,
                new SplittableRandom());
    }

    /**
     * 2026-08-01 U13: {@code drops[].material} の {@code custom:<id>} を解決できる本番用コンストラクタ。
     *
     * @param itemResolver {@code custom:<id>} の解決先。<b>これを渡さないと custom: ドロップは
     *                     1件も出ない</b>(WARNING は出る)。
     */
    public MobTypeDropListener(MobTypesConfig mobTypesConfig, CraftQualityConfig craftQuality,
                                QualityConfig quality, ItemFactory itemFactory,
                                PlayerMobDropBonusSource mobDropBonus, ItemStatsConfig itemStats,
                                CrossPluginItemResolver itemResolver) {
        this(mobTypesConfig, craftQuality, quality, itemFactory, mobDropBonus, itemStats, itemResolver,
                new SplittableRandom());
    }

    /** Package-visible ctor for tests that need a deterministic random source. */
    MobTypeDropListener(MobTypesConfig mobTypesConfig, CraftQualityConfig craftQuality,
                         QualityConfig quality, ItemFactory itemFactory,
                         PlayerMobDropBonusSource mobDropBonus, ItemStatsConfig itemStats,
                         CrossPluginItemResolver itemResolver, SplittableRandom random) {
        this.mobTypesConfig = Objects.requireNonNull(mobTypesConfig, "mobTypesConfig");
        this.craftQuality = Objects.requireNonNull(craftQuality, "craftQuality");
        this.quality = Objects.requireNonNull(quality, "quality");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
        this.mobDropBonus = mobDropBonus; // nullable: 未配線時はボーナス0
        this.itemStats = itemStats; // nullable: 未配線時は品質基準値オフセット0
        this.itemResolver = itemResolver; // nullable: 未配線時は custom: ドロップを WARNING 付きで捨てる
        this.random = Objects.requireNonNull(random, "random");
    }

    /** TF追加ドロップの抑止述語を設定する。nullで無効化。 */
    public void setDropGate(java.util.function.Predicate<Player> gate) {
        this.dropGate = gate;
    }

    /**
     * レベル差の足きり(combat/damage.yml の level-cutoff)とドロップ増加ステ(mob_drop_bonus)を
     * このドロップ源へも掛ける(2026-08-09)。null = 未配線で、そのときは従来どおり素の抽選結果が出る。
     *
     * <p>コンストラクタ引数ではなくセッターにしているのは、このクラスが既に4本の公開
     * コンストラクタを持っており、全部に1引数足すと呼び出し側(テスト含む)が一斉に壊れるため。
     * {@link #setDropGate} と同じ「後から挿す任意の抑止層」という位置づけ。
     */
    public void setKillRewardAdjuster(KillRewardAdjuster adjuster) {
        this.killRewardAdjuster = adjuster;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null || isGated(killer)) {
            return;
        }
        MobData mobData = MobData.of(entity);
        if (!mobData.isMobTypeStamped()) {
            // Not a mob-types spawn: either an EliteMobs/dungeon mob (shares MOB_LEVEL but never
            // this marker) or a vanilla mob that existed before the plugin/config loaded. Neither
            // should receive mob-types drop tables or the equipment-quality stamp.
            return;
        }
        Optional<MobTypeDefinition> maybeDef = mobTypesConfig.definition(entity.getType());
        if (maybeDef.isEmpty()) {
            return;
        }
        MobTypeDefinition def = maybeDef.get();
        int mobLevel = mobData.level();
        // mobドロップボーナス(power_mobdropbonus_add): killerの合計値ぶん品質modeを底上げする。
        // 幸運と同機構(整数分+1、端数は確率的)だが、幸運は意図的にmobドロップへ効かないため別stat。
        int bonusMode = mobDropBonus == null ? 0
                : mobDropBonus.qualityModeBonus(killer, random.nextDouble());

        // 2026-08-09: レベル差の足きり + ドロップ増加ステ。未配線(null)なら従来どおり素通し。
        KillRewardAdjuster adjuster = this.killRewardAdjuster;
        double chanceMultiplier = 1.0;
        double countFactor = 1.0;
        if (adjuster != null) {
            if (adjuster.blocksItems(killer, entity)) {
                return;
            }
            chanceMultiplier = adjuster.chanceMultiplier(killer, entity);
            countFactor = adjuster.countFactor(killer);
        }

        for (MobDropEntry drop : def.drops()) {
            if (!MobDropRoller.rolls(drop.chance() * chanceMultiplier, random.nextDouble())) {
                continue;
            }
            int count = MobDropRoller.rollCount(drop.min(), drop.max(), random.nextInt());
            if (countFactor > 1.0) {
                count = MobDropRoller.scaleCount(count, countFactor,
                        drop.isCustom() ? 64 : drop.material().getMaxStackSize(), random.nextDouble());
            }
            if (drop.isCustom()) {
                // 2026-08-01 U13: カタログ/Ars のカスタムアイテム。解決失敗はこの1件だけ捨てる
                // (fail-open。MobOverrideDropListener / MobLevelTableListener と同じ契約)。
                ItemStack custom = buildCustomStack(drop, count, entity.getType().name());
                if (custom != null) {
                    event.getDrops().add(custom);
                }
                continue;
            }
            ItemStack stack = new ItemStack(drop.material(), count);
            if (MaterialTier.of(drop.material()).isEquipment()) {
                int qualityValue = drop.quality() != null ? drop.quality()
                        : resolveQuality(mobLevel, bonusMode, drop.material());
                itemFactory.stamp(stack, random.nextLong(), qualityValue);
            }
            event.getDrops().add(stack);
        }
    }

    /**
     * {@code custom:<id>} ドロップの組み立て。解決できない(未知IDや resolver 未配線)ときは WARNING を
     * 出して {@code null} を返す(この抽選だけ捨てる)。
     *
     * <p>品質は {@code CrossPluginItemResolver#create} 側の {@code ItemFactory#create} が既に打つので、
     * ここで {@code itemFactory.stamp} を重ねない。個数0の抽選も捨てる({@code ItemStack#setAmount(0)}
     * のスタックを drops へ積まない — 兄弟2リスナーと同じ)。
     */
    private ItemStack buildCustomStack(MobDropEntry drop, int count, String mobLabel) {
        if (count <= 0) {
            return null;
        }
        if (itemResolver == null) {
            if (!unwiredResolverWarned) {
                unwiredResolverWarned = true;
                LOG.log(Level.WARNING, "[mob-types] " + mobLabel + " の drops に custom:"
                        + drop.catalogId() + " が設定されているが CrossPluginItemResolver が未配線のため"
                        + "カスタムアイテムのドロップは一切行われない"
                        + " (TrinityForge.java の new MobTypeDropListener(...) へ crossPluginItemResolver を渡すこと)");
            }
            return null;
        }
        Optional<ItemStack> resolved = itemResolver.create(drop.catalogId());
        if (resolved.isEmpty()) {
            LOG.log(Level.WARNING, "[mob-types] " + mobLabel + " の drops custom item '"
                    + drop.catalogId() + "' could not be resolved (unknown catalog/Ars id?);"
                    + " this roll was skipped");
            return null;
        }
        ItemStack stack = resolved.get();
        stack.setAmount(count);
        return stack;
    }

    /** AFK判定側の一時障害で通常プレイのドロップまで失わないよう、述語失敗時は付与を継続する。 */
    private boolean isGated(Player killer) {
        java.util.function.Predicate<Player> gate = this.dropGate;
        if (gate == null) {
            return false;
        }
        try {
            return gate.test(killer);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Mob-level-driven quality: same split-normal draw as crafted/fished equipment (5.2d), plus the
     * dropped material's 品質基準値 (item-stats {@code quality-mode-offset}, 2026-07-23
     * stat-gate-overhaul §6.6 適用拡大). The drop is a plain (no CMD) material, so only the base
     * {@code MATERIAL} offset entry can ever apply here.
     */
    private int resolveQuality(int mobLevel, int bonusMode, org.bukkit.Material material) {
        if (!craftQuality.dropEnabled()) {
            return random.nextInt(Math.max(0, quality.maxQuality()) + 1);
        }
        int qualityModeOffset = itemStats == null ? 0 : itemStats.qualityModeOffsetFor(material, null);
        int mode = CraftQualityPolicy.modeFromLevel(mobLevel, craftQuality.dropStrengthPerQuality(),
                craftQuality.dropBaseQuality()) + Math.max(0, bonusMode) + qualityModeOffset;
        double gaussianSample = random.nextGaussian();
        return CraftQualityPolicy.resolveDropQuality(mode, gaussianSample, quality.spreadUp(),
                quality.spreadDown(), quality.maxQuality());
    }
}
