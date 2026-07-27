package com.trinityforge.listeners;

import com.trinityforge.config.domains.MobLevelTableConfig;
import com.trinityforge.dungeon.DungeonWorldRegistry;
import com.trinityforge.mobs.LevelTierDropEntry;
import com.trinityforge.mobs.LevelTierRule;
import com.trinityforge.mobs.MobDropRoller;
import com.trinityforge.pdc.MobData;
import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.entity.EntityType;
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
 * Applies {@code combat/mob-level-table.yml} (レベルテーブル要望) at mob death: level-band-scoped
 * ドロップ削除/追加/バニラEXP上書き, optionally gated to EliteMobs dungeon instance worlds only.
 *
 * <p>Runs at {@link EventPriority#HIGH} — strictly after {@code MobTypeDropListener} (default
 * {@code NORMAL}, appends {@code mob-types.yml}'s own {@code drops:}) and after vanilla's own
 * default loot has already been computed into {@link EntityDeathEvent#getDrops()} — so
 * {@code remove-drops} removes by {@link org.bukkit.Material} from whatever is in the FINAL drop
 * list at that point, regardless of whether it came from vanilla's default loot table or a TF
 * {@code mob-types.yml} additive drop. It does NOT see or affect EliteMobs' own loot generation
 * (that system is separate and, per current server policy, disabled for dungeon mobs — see report).
 * Any OTHER plugin that adds drops at {@code HIGHEST}/{@code MONITOR} priority (after this listener)
 * would not be covered by {@code remove-drops}; no such plugin is known to run on this server.
 *
 * <p>Applies to both field mobs ({@code combat/mob-types.yml}) and dungeon mobs (EliteMobs import,
 * {@code combat/mob-profiles.yml}) alike — the BAND gate is simply "does this mob carry a stamped
 * combat level" ({@link MobData#hasProfile()}), never the mob's EntityType/origin. An untagged mob (no
 * level profile) is never touched, matching the rest of the mob-types/mob-profiles pipeline. Within a
 * resolved band, individual {@code add-drops} entries MAY additionally narrow to specific EntityTypes
 * (2026-07-25 レベルテーブルのモブ別ドロップ指定拡張, §2-A — see {@link LevelTierDropEntry#appliesTo});
 * this per-entry filter is independent of the band-level "level only" gate above.
 *
 * <p>Vanilla EXP only (see report for why skill EXP was not chosen): sets
 * {@link EntityDeathEvent#setDroppedExp(int)} when the resolved band configures {@code vanilla-exp}.
 * This is a completely separate pipeline from {@code stats/skill-exp.yml}'s {@code dungeon-only-exp}
 * (which gates weapon/armor SKILL EXP, itself hit-based, not kill-based), so there is no double-gate
 * risk between the two: this listener's own {@code dungeon-only} flag is the only gate that applies
 * to the level-table's own effects.
 *
 * <p>Runtime-verification-only for the {@code EntityDeathEvent} wiring itself (Bukkit event, no unit
 * test); the pure band-resolution/roll logic is unit-tested via {@code MobLevelTableConfigTest} and
 * the shared {@code MobDropRoller}.
 *
 * <p><b>Player-kill gate (2026-07-26 H2 レビュー指摘):</b> {@link EntityDeathEvent#getEntity()}{@code
 * .getKiller()} must be non-null (a {@code Player}) or this listener does nothing at all — no
 * {@code remove-drops}, no {@code add-drops}, no {@code vanilla-exp}. Before this gate a non-player kill
 * (mob infighting, lava, fall damage, …) still fully applied the resolved band's drop/EXP rules,
 * effectively turning any AFK/automated non-player kill loop into a free drop+EXP farm — the exact same
 * class of bug {@link MobOverrideExpListener}/{@link MobOverrideDropListener} had.
 */
public final class MobLevelTableListener implements Listener {

    private static final Logger LOG = Logger.getLogger(MobLevelTableListener.class.getName());

    private final MobLevelTableConfig config;
    private final DungeonWorldRegistry dungeonWorldRegistry;
    private final CrossPluginItemResolver itemResolver;
    private final SplittableRandom random;

    /**
     * @param itemResolver resolves {@code custom:<catalogId>} add-drops (2026-07-25 レベルテーブルの
     *                      モブ別ドロップ指定拡張, §2-B) via {@code items/catalog.yml}/ArsPaper at
     *                      drop-roll time — the SAME seam {@code CrossPluginItemResolver}'s own javadoc
     *                      already earmarks for "future drop tables". Never touched for a plain
     *                      {@link Material}-only entry.
     */
    public MobLevelTableListener(MobLevelTableConfig config, DungeonWorldRegistry dungeonWorldRegistry,
                                  CrossPluginItemResolver itemResolver) {
        this(config, dungeonWorldRegistry, itemResolver, new SplittableRandom());
    }

    /** Package-visible ctor for tests that need a deterministic random source. */
    MobLevelTableListener(MobLevelTableConfig config, DungeonWorldRegistry dungeonWorldRegistry,
                           CrossPluginItemResolver itemResolver, SplittableRandom random) {
        this.config = Objects.requireNonNull(config, "config");
        this.dungeonWorldRegistry = Objects.requireNonNull(dungeonWorldRegistry, "dungeonWorldRegistry");
        this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * 追加ドロップ(add-drops)を止める述語 (2026-07-27、AFK対策)。未配線(null)なら抑止なし。
     * 削除(remove-drops)とバニラEXPには効かない — 理由は onDeath 内のコメント参照。
     */
    private volatile java.util.function.Predicate<org.bukkit.entity.Player> dropGate;

    /** 追加ドロップの抑止述語を設定する(2026-07-27、AFK対策)。null で無効化。 */
    public void setDropGate(java.util.function.Predicate<org.bukkit.entity.Player> gate) {
        this.dropGate = gate;
    }

    /** 述語の例外でドロップ処理を落とさない(失敗したら従来どおり付与する)。 */
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.getKiller() == null) {
            // 2026-07-26 H2: プレイヤーがキルした場合のみ適用する(class javadoc「Player-kill gate」参照)。
            return;
        }
        MobData mobData = MobData.of(entity);
        if (!mobData.hasProfile()) {
            // 未タグ付けモブ(明示的なMOB_LEVELなし): レベルテーブルの適用対象外(mob-types/mob-profiles
            // のどちらの由来でもない = このモブに「レベル帯」という概念が存在しない)。
            return;
        }
        if (config.dungeonOnly() && !dungeonWorldRegistry.isDungeonWorld(entity.getWorld().getUID())) {
            return;
        }
        Optional<LevelTierRule> maybeRule = config.resolve(mobData.level());
        if (maybeRule.isEmpty()) {
            return;
        }
        LevelTierRule rule = maybeRule.get();

        EntityType mobType = entity.getType();
        String profileId = mobData.profileId().orElse(null);
        // 2026-07-26 「レベルテーブルを付けるモブを指定できない」: 帯そのものに mobs:/mob-ids: が
        // 書かれている場合、対象外のモブにはこの帯を一切適用しない(削除/追加/EXPすべて)。
        if (!rule.appliesTo(mobType, profileId)) {
            return;
        }

        if (!rule.removeDrops().isEmpty()) {
            event.getDrops().removeIf(stack -> rule.removeDrops().contains(stack.getType()));
        }

        // 2026-07-27 AFK対策: 抑止対象なら「追加ドロップ」だけ飛ばす。remove-drops(削除)と
        // vanilla-exp は通す — 削除は報酬ではないし、バニラEXPはオーブ回収時の
        // PlayerExpChangeEvent 側(AfkSuppressionListener)で 0 にされるので、ここで二重に止めない。
        List<LevelTierDropEntry> addDrops = isGated(entity.getKiller()) ? List.of() : rule.addDrops();
        for (LevelTierDropEntry drop : addDrops) {
            // 2026-07-25 レベルテーブルのモブ別ドロップ指定拡張(§2-A) + 2026-07-26 mob-ids 追加:
            // 未指定(空)なら従来どおり全モブに適用、指定時はそのEntityType/モブidのキルだけに絞り込む。
            if (!drop.appliesTo(mobType, profileId)) {
                continue;
            }
            if (!MobDropRoller.rolls(drop.chance(), random.nextDouble())) {
                continue;
            }
            int count = MobDropRoller.rollCount(drop.min(), drop.max(), random.nextInt());
            if (count <= 0) {
                continue;
            }
            ItemStack stack = buildDropStack(drop, count, profileId != null ? profileId : mobType.name());
            if (stack != null) {
                event.getDrops().add(stack);
            }
        }

        if (rule.vanillaExp() != null) {
            // TT/放置対策: 同一地点で稼ぎ続けたぶんだけ経験値オーブを減らす(ダンジョンは既定で対象外)。
            event.setDroppedExp(com.trinityforge.progression.LocationExpDiminishing
                    .applyIfRunning(Math.max(0, rule.vanillaExp()), entity));
        }
    }

    /**
     * Builds the rolled stack: a plain {@code new ItemStack(material, count)} for a vanilla entry, or
     * (2026-07-25 §2-B) a {@link CrossPluginItemResolver#create(String)}-built item for a
     * {@code custom:<catalogId>} entry, with its amount overwritten to the rolled {@code count}.
     * Returns {@code null} (skip this roll, never fatal — ⚠️ §2-B requirement) when a {@code custom:}
     * id fails to resolve (unknown/removed catalog id), logging a warning either way.
     */
    /**
     * @param mobLabel 2026-07-26: 警告に「どのモブのキルで起きたか」を載せるための識別子
     *                 (EliteMobsモブid、無ければ EntityType 名)。このリスナーは mob-overrides.yml を
     *                 持たないので日本語表示名までは出せない — 名前が要る警告は
     *                 {@code MobOverrideDropListener} 側が日本語名付きで出す。
     */
    private ItemStack buildDropStack(LevelTierDropEntry drop, int count, String mobLabel) {
        if (!drop.isCustom()) {
            return new ItemStack(drop.material(), count);
        }
        Optional<ItemStack> resolved = itemResolver.create(drop.catalogId());
        if (resolved.isEmpty()) {
            LOG.log(Level.WARNING, "[mob-level-table] " + mobLabel + " の add-drops custom item '"
                    + drop.catalogId() + "' could not be resolved (unknown catalog/Ars id?);"
                    + " this roll was skipped");
            return null;
        }
        ItemStack stack = resolved.get();
        stack.setAmount(count);
        return stack;
    }
}
