package com.trinityforge.listeners;

import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.mobs.EliteMobsSharedLootBridge;
import com.trinityforge.mobs.MobDropRoller;
import com.trinityforge.mobs.MobOverrideDropEntry;
import com.trinityforge.pdc.MobData;
import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Applies {@code combat/mob-overrides.yml}'s per-（ダンジョンワールド × EliteMobsモブid）drop table
 * (2026-07-26 「エリートモブズインスタンス内のカスタムモブごとに個別のドロップテーブルを設定できる」要望)
 * at mob death. Gated on {@link MobData#profileId()} — only entities the EliteMobs fork stamped with
 * {@code MOB_PROFILE_ID} (2026-07-26, {@code TrinityForgeSpawnListener}) are eligible; field mobs
 * ({@code combat/mob-types.yml}) and any EliteMobs mob spawned by a fork build that predates this stamp
 * are silently skipped (no id to resolve an override with).
 *
 * <p><b>Priority: {@link EventPriority#MONITOR}</b> — deliberately later than both
 * {@code MobLevelTableListener} ({@code HIGH}) and the point at which EliteMobs' own
 * {@code LootTables#onDeath} clears {@link EntityDeathEvent#getDrops()} for a non-vanilla-loot mob (that
 * clear happens nested inside the {@code EliteMobDeathEvent} fired by EliteMobs' {@code NORMAL}-priority
 * {@code EntityDeathEvent} listener — see {@code TrinityForgeLootListener} javadoc in the fork for why the
 * drops list is otherwise a "decoy" for elite loot). Running at {@code MONITOR} guarantees this listener's
 * additions survive that clear and are never themselves stripped by {@code MobLevelTableListener}'s own
 * {@code remove-drops} (which runs at {@code HIGH}, strictly before this listener).
 *
 * <p><b>Coexistence with existing drop sources (report per 2026-07-26 spec §3):</b>
 * <ul>
 *   <li>{@code MobTypeDropListener} — never fires for EliteMobs mobs at all (it requires the
 *       {@code MOB_TYPE_STAMPED} marker, which only {@code MobTypeSpawnListener} writes for vanilla
 *       {@code mob-types.yml} spawns). Zero overlap, no double-drop risk.</li>
 *   <li>{@code MobLevelTableListener} — DOES apply to EliteMobs mobs (its gate is any {@code MOB_LEVEL}
 *       stamp, origin-agnostic). This listener's override drops are treated as a fully independent,
 *       ADDITIVE third table — mirroring the existing precedent that {@code mob-types.yml}'s own
 *       {@code drops:} and {@code mob-level-table.yml}'s {@code add-drops} already coexist additively.
 *       No suppression either way: configuring {@code mob-overrides.yml drops:} for a mob does not turn
 *       off that mob's level-band {@code add-drops}, and vice versa. An operator who wants ONLY the
 *       override drops for a given mob should leave that mob out of {@code mob-level-table.yml}'s bands
 *       (or narrow the band's {@code add-drops mobs:} EntityType filter) — this listener does not attempt
 *       to infer that intent automatically.</li>
 * </ul>
 * The override table's own {@code drops:} REPLACE-not-merge semantics ({@link MobOverridesConfig#dropsFor})
 * only apply within {@code mob-overrides.yml} itself (world scope replaces default scope), never across
 * files.
 *
 * <p><b>Player-kill gate (2026-07-26 H2 レビュー指摘):</b> {@link EntityDeathEvent#getEntity()}{@code
 * .getKiller()} must be non-null (a {@code Player}) or this listener adds nothing. Without this gate a
 * mob killed by another mob, lava, fall damage, etc. still rolled the full override drop table,
 * effectively turning any AFK/automated non-player kill loop into a free item farm.
 *
 * <p><b>レベル差による足きり(2026-08-09 に共通設定へ移設):</b> 判定は {@link KillRewardAdjuster} が
 * {@code combat/damage.yml} の {@code level-cutoff:} から解決する(2026-07-27 版は
 * {@code combat/mob-overrides.yml} 側にあり、EliteMobsのスタンプが無いモブに効かなかった)。
 * {@code diff = プレイヤー戦闘Lv - モブLv} で under-level が発動していれば TF追加ドロップを一切付けず
 * 即return、over-level が発動していて {@code drop-rate == -1} でも同様。それ以外で over-level が
 * 発動していれば各 drop entry の {@code chance} に倍率を掛けてから抽選する。<b>バニラ本来のドロップ
 * ({@code event.getDrops()}に元々入っていたもの)には一切触らない</b> — このリスナーはTF追加ドロップの
 * roll処理にしか関与しないため、足きりの影響範囲は自然とTF追加ドロップのみに限定される。
 *
 * <p><b>ドロップ増加ステ({@code mob_drop_bonus}、2026-08-09 / 2026-08-13 に効かせ方を変更):</b>
 * {@link KillRewardAdjuster#dropBonus} を、個数が1個固定のエントリなら<b>抽選確率</b>へ、
 * それ以外なら<b>抽選後の個数への加算</b>へ回す(乗算ではない)。以前はこのステが
 * {@code NativeSurvivalPerkListener} で {@code event.getDrops()} の中身にしか掛かっておらず、
 * <b>TF追加ドロップには一切載っていなかった</b>(同じ MONITOR 優先度で先に登録されている =
 * TF追加ドロップが積まれる前に走り終わっている)。
 *
 * <p><b>複数人ダンジョンでの分配(2026-08-09):</b> 抽選に通ったスタックは
 * {@code event.getDrops()} へ直接積まず {@link com.trinityforge.mobs.EliteMobsSharedLootBridge#deliver}
 * を通す。エリートモブ・ダメージ寄与者2人以上・インスタンス化ダンジョンの3条件がそろったときだけ
 * EliteMobs の共有戦利品テーブル(emloot、need/greed)が引き取り、60秒の投票を経て当選者1人へ渡る。
 * それ以外(ソロ・フィールド・EliteMobs 不在)は橋の中で従来どおり {@code event.getDrops()} へ
 * 積まれるので、この分岐でドロップが失われることはない。
 */
public final class MobOverrideDropListener implements Listener {

    private static final Logger LOG = Logger.getLogger(MobOverrideDropListener.class.getName());

    private final MobOverridesConfig mobOverrides;
    private final CrossPluginItemResolver itemResolver;
    private final KillRewardAdjuster adjuster;
    private final SplittableRandom random;

    public MobOverrideDropListener(MobOverridesConfig mobOverrides, CrossPluginItemResolver itemResolver,
                                    KillRewardAdjuster adjuster) {
        this(mobOverrides, itemResolver, adjuster, new SplittableRandom());
    }

    /** Package-visible ctor for tests that need a deterministic random source. */
    MobOverrideDropListener(MobOverridesConfig mobOverrides, CrossPluginItemResolver itemResolver,
                             KillRewardAdjuster adjuster, SplittableRandom random) {
        this.mobOverrides = Objects.requireNonNull(mobOverrides, "mobOverrides");
        this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");
        this.adjuster = Objects.requireNonNull(adjuster, "adjuster");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * TF追加ドロップを止める述語 (2026-07-27、AFK対策)。{@code true} を返したキル者には
     * 追加ドロップを付けない。バニラ本来のドロップには一切触らない — そこまで止めると
     * モブトラップが完全に死んで「AFK対策」の域を超えるため。未配線(null)なら抑止なし。
     */
    private volatile java.util.function.Predicate<org.bukkit.entity.Player> dropGate;

    /** 追加ドロップの抑止述語を設定する(2026-07-27、AFK対策)。null で無効化。 */
    public void setDropGate(java.util.function.Predicate<org.bukkit.entity.Player> gate) {
        this.dropGate = gate;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.getKiller() == null) {
            // 2026-07-26 H2: プレイヤーがキルした場合のみ適用する(class javadoc「Player-kill gate」参照)。
            return;
        }
        if (isGated(entity.getKiller())) {
            return;
        }
        MobData mobData = MobData.of(entity);
        Optional<String> profileId = mobData.profileId();
        if (profileId.isEmpty()) {
            return;
        }
        String worldName = entity.getWorld().getName();
        if (adjuster.blocksItems(entity.getKiller(), entity)) {
            // 足きり: under-level発動、またはover-level発動でdrop-rate==-1。バニラ本来の
            // ドロップには一切触れず、TF追加ドロップのroll処理だけをここで打ち切る。
            return;
        }
        double dropMultiplier = adjuster.chanceMultiplier(entity.getKiller(), entity);
        double dropBonus = adjuster.dropBonus(entity.getKiller());
        List<MobOverrideDropEntry> drops = mobOverrides.dropsFor(worldName, profileId.get());
        // 2026-07-26: 解決失敗の警告に「どのモブの設定か」を載せる。モブidだけだと 396 体の生成物の
        // どれなのか運用側で追えないため、display-name があれば日本語名を併記する。
        String mobLabel = mobOverrides.mobDisplayName(worldName, profileId.get())
                .map(name -> name + " (" + profileId.get() + ")")
                .orElseGet(profileId::get);
        for (MobOverrideDropEntry drop : drops) {
            // 2026-08-13: ドロップ増加ステの効かせ方はドロップの形で分かれる。
            // 1個固定(=レアドロップ)は抽選確率を上げ、それ以外は個数を足す。
            boolean singleFixed = MobDropRoller.isSingleFixed(drop.min(), drop.max());
            double chance = drop.chance() * dropMultiplier;
            if (singleFixed) {
                chance = MobDropRoller.boostedChance(chance, dropBonus);
            }
            if (!MobDropRoller.rolls(chance, random.nextDouble())) {
                continue;
            }
            int count = MobDropRoller.rollCount(drop.min(), drop.max(), random.nextInt());
            if (count <= 0) {
                continue;
            }
            ItemStack stack = buildDropStack(drop, count, mobLabel);
            if (stack != null && !singleFixed && dropBonus > 0.0) {
                // 2026-08-09: ドロップ増加ステ(mob_drop_bonus)。NativeSurvivalPerkListener は同じ
                // MONITOR優先度でも登録順で先に走るため、あとから足すこのドロップには一度も
                // 掛かっていなかった。個数を確定させた直後にここで掛ける。
                stack.setAmount(MobDropRoller.cappedCount(
                        stack.getAmount() + MobDropRoller.extraCount(dropBonus, random.nextDouble()),
                        stack.getMaxStackSize()));
            }
            if (stack != null) {
                // 2026-08-09: 複数人でインスタンス化ダンジョンに潜っているときだけ、地面へ落とさず
                // EliteMobs の共有戦利品テーブル(emloot、need/greed)へ回す。対象外なら
                // deliver() の中で従来どおり event.getDrops() へ積まれる。
                EliteMobsSharedLootBridge.deliver(event, stack);
            }
        }
    }

    /**
     * Builds the rolled stack: a plain {@code new ItemStack(material, count)} for a vanilla entry, or a
     * {@link CrossPluginItemResolver#create(String)}-built item for a {@code custom:<id>} entry, with its
     * amount overwritten to the rolled {@code count}. Returns {@code null} (skip this roll, never fatal)
     * when a {@code custom:} id fails to resolve, logging a warning either way — mirrors
     * {@code MobLevelTableListener#buildDropStack}'s fail-open contract.
     */
    private ItemStack buildDropStack(MobOverrideDropEntry drop, int count, String mobLabel) {
        if (!drop.isCustom()) {
            return new ItemStack(drop.material(), count);
        }
        Optional<ItemStack> resolved = itemResolver.create(drop.catalogId());
        if (resolved.isEmpty()) {
            LOG.log(Level.WARNING, "[mob-overrides] " + mobLabel + " の drops custom item '"
                    + drop.catalogId() + "' could not be resolved (unknown catalog/Ars id?);"
                    + " this roll was skipped");
            return null;
        }
        ItemStack stack = resolved.get();
        stack.setAmount(count);
        return stack;
    }

    /** 述語の例外でドロップ処理を落とさない(抑止は付加機能なので、失敗したら従来どおり付与する)。 */
    private boolean isGated(org.bukkit.entity.Player killer) {
        java.util.function.Predicate<org.bukkit.entity.Player> gate = this.dropGate;
        if (gate == null || killer == null) {
            return false;
        }
        try {
            return gate.test(killer);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
