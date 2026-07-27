package com.trinityforge.combat;

import com.trinityforge.config.domains.BaseStatsConfig;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.StatCapsConfig;
import com.trinityforge.progression.PermanentBuffResolver;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.skilltree.runtime.NativeAttributeBridge;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.PerkBuffs;
import com.trinityforge.stats.DerivedItemStats;
import com.trinityforge.stats.EquipmentSlotResolver;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * プレイヤー戦闘スタットの単一集計者(#3 全ステ合算)。攻撃側({@code CombatListener})・防御側
 * ({@link PlayerDefenseResolver})の双方がこのクラスを通じて {@link PlayerCombatAggregate} を得ることで、
 * 「防具4部位 + メインハンド(または発射武器) + (設定により)オフハンド + スキルツリーパーク + アドオン」
 * という同一のステータス源を二重実装しない。
 *
 * <p>各読み取りは {@code stats/item-stats.yml}({@link DerivedItemStats})・{@link PerkBuffResolver}・
 * {@link AddonCombatStats} を毎回ライブに参照するため、焼き込みは行わずリロードが即座に反映される。
 * Bukkit読み取りを伴うためサーバーメインスレッドでの実行が前提。
 */
public final class PlayerStatAggregator {

    /** {@link StatKeys#canonical} form of {@code armor-defense-rate}; see {@link #aggregate} 修正B note. */
    private static final String ARMOR_DEFENSE_RATE_KEY = StatKeys.canonical("armor-defense-rate");

    private final ItemStatsConfig itemStats;
    private final CombatDamageConfig combatDamage;
    private final PerkBuffResolver perkBuffResolver;
    private final RoleBuffResolver roleBuffResolver;
    private final NativeAttributeBridge nativeAttributeBridge;
    private final PermanentBuffResolver permanentBuffResolver;
    private final BaseStatsConfig baseStats;
    private final StatCapsConfig statCaps;

    /**
     * Per-tick memo cache (2026-07-25, CMB-30): {@link #aggregate} re-runs 4 armor pieces + mainhand +
     * offhand {@link DerivedItemStats} resolution, {@link PerkBuffResolver}, {@link RoleBuffResolver},
     * {@link PermanentBuffResolver} (a full achievement/collection scan), and {@link BaseStatsConfig}
     * on every call — 3-6x per single hit in practice ({@code CombatListener},
     * {@code NativeCombatPerkListener}, and {@code PerkAttributeApplier}'s 10-tick poll all call this
     * for the same player, often within the same server tick). Memoizing the result per
     * {@code (player, mainhand-contributor item, offhand-exclusion flag)} for the lifetime of ONE tick
     * means every consumer within a single damage event observes an identical snapshot, while
     * equipment/perk changes are still visible on the very next tick.
     *
     * <p><b>RollHash correctness note (verified 2026-07-25):</b> {@link com.trinityforge.stats.RollHash
     * #standardNormal} seeds a fresh {@link java.util.SplittableRandom} from the item's PDC
     * {@code rollSeed} + the canonical stat key on every call — it is NOT freshly random per call, it
     * is deterministic per (rollSeed, statKey). Repeated resolution of the same item therefore already
     * returned bit-identical values before this cache existed. This cache is a pure PERFORMANCE fix for
     * the roll layer specifically, not a correctness fix for it (unlike, say, a hypothetical
     * time-seeded RNG would have been).
     *
     * <p>Cleared wholesale whenever {@link Bukkit#getCurrentTick()} advances (checked on every call, no
     * extra scheduler task needed), so the map is bounded by "distinct keys queried in the current
     * tick" and self-evicts every tick — never an unbounded per-UUID map. Main-thread only: every real
     * caller found in this codebase ({@code CombatListener}, {@code NativeCombatPerkListener},
     * {@code PlayerDefenseResolver}, {@code PerkAttributeApplier}, the ArsPaper fork bridge) calls this
     * synchronously on the server thread, so no synchronization is used here.
     */
    private final Map<AggregateCacheKey, PlayerCombatAggregate> tickCache = new HashMap<>();
    private int cachedTick = Integer.MIN_VALUE;

    private record AggregateCacheKey(UUID playerId, ItemStack mainhandContributor, boolean contributorIsOffhand) {
    }

    public PlayerStatAggregator(ItemStatsConfig itemStats,
                                CombatDamageConfig combatDamage,
                                PerkBuffResolver perkBuffResolver,
                                RoleBuffResolver roleBuffResolver) {
        this(itemStats, combatDamage, perkBuffResolver, roleBuffResolver, null);
    }

    /**
     * @param nativeAttributeBridge optional (may be {@code null}, e.g. existing tests/call sites that
     *                              predate this parameter): supplies the {@code native:} armor-set bonuses
     *                              (SKILL_TREE MEDIUM audit finding — light armor's set dodge-chance bonus)
     *                              that {@link PerkBuffResolver} cannot see, since those live in each
     *                              node's {@code native:} map rather than {@code buffs:}. Folded into
     *                              {@code perkDefense} at the exact spot {@link PerkBuffs#defense()} is
     *                              read below, so it rides the same combine/multiplier/cap path as every
     *                              other defender addend — no separate channel, no double count.
     */
    public PlayerStatAggregator(ItemStatsConfig itemStats,
                                CombatDamageConfig combatDamage,
                                PerkBuffResolver perkBuffResolver,
                                RoleBuffResolver roleBuffResolver,
                                NativeAttributeBridge nativeAttributeBridge) {
        this(itemStats, combatDamage, perkBuffResolver, roleBuffResolver, nativeAttributeBridge, null);
    }

    /**
     * @param permanentBuffResolver optional (may be {@code null}, e.g. existing tests/call sites that
     *                              predate this parameter): folds in achievement/collection
     *                              {@code rewards.permanent-buffs} (Java-only アチーブメント/図鑑報酬拡張)
     *                              exactly like {@link RoleBuffResolver}'s contribution — merged into the
     *                              same {@code item} map immediately after the role buffs below, so it
     *                              rides the same combine/multiplier/cap path as every other addend and is
     *                              re-evaluated live on every call (no caching, reload-safe).
     */
    public PlayerStatAggregator(ItemStatsConfig itemStats,
                                CombatDamageConfig combatDamage,
                                PerkBuffResolver perkBuffResolver,
                                RoleBuffResolver roleBuffResolver,
                                NativeAttributeBridge nativeAttributeBridge,
                                PermanentBuffResolver permanentBuffResolver) {
        this(itemStats, combatDamage, perkBuffResolver, roleBuffResolver, nativeAttributeBridge,
                permanentBuffResolver, null);
    }

    /**
     * @param baseStats optional (may be {@code null}, e.g. existing tests/call sites that predate this
     *                  parameter): folds {@code combat/base-stats.yml} — a config-driven baseline applied
     *                  uniformly to every player — into the same {@code item} map immediately after the
     *                  permanent buffs below, so it rides the same combine/multiplier/cap path as every
     *                  other addend and is re-evaluated live on every call (no caching, reload-safe).
     *                  Attribute-channel base stats (max-health 等) have no effect via this {@code item}
     *                  map (it is never read for vanilla Attribute stats); those are applied separately on
     *                  the vanilla-Attribute path (see {@code PerkAttributeApplier}), exactly like
     *                  permanent buffs. {@code armor-defense-rate} is routed to {@code perkDefense} here,
     *                  mirroring the permanent-buff handling, since the {@code item} side is 0-fixed by
     *                  {@code DefenseStatBridge}.
     */
    public PlayerStatAggregator(ItemStatsConfig itemStats,
                                CombatDamageConfig combatDamage,
                                PerkBuffResolver perkBuffResolver,
                                RoleBuffResolver roleBuffResolver,
                                NativeAttributeBridge nativeAttributeBridge,
                                PermanentBuffResolver permanentBuffResolver,
                                BaseStatsConfig baseStats) {
        this(itemStats, combatDamage, perkBuffResolver, roleBuffResolver, nativeAttributeBridge,
                permanentBuffResolver, baseStats, null);
    }

    /**
     * @param statCaps optional (may be {@code null}, e.g. existing tests/call sites that predate this
     *                 parameter): {@code combat/stat-caps.yml} — when present, threaded straight into
     *                 every {@link PlayerCombatAggregate} this aggregator produces so
     *                 {@link PlayerCombatAggregate#totalOf} can clamp its "final aggregated value"
     *                 (see that method's javadoc for why it is the single choke point). {@code null}
     *                 reproduces the exact pre-existing behaviour (no cap applied anywhere).
     */
    public PlayerStatAggregator(ItemStatsConfig itemStats,
                                CombatDamageConfig combatDamage,
                                PerkBuffResolver perkBuffResolver,
                                RoleBuffResolver roleBuffResolver,
                                NativeAttributeBridge nativeAttributeBridge,
                                PermanentBuffResolver permanentBuffResolver,
                                BaseStatsConfig baseStats,
                                StatCapsConfig statCaps) {
        this.itemStats = Objects.requireNonNull(itemStats, "itemStats");
        this.combatDamage = Objects.requireNonNull(combatDamage, "combatDamage");
        this.perkBuffResolver = Objects.requireNonNull(perkBuffResolver, "perkBuffResolver");
        this.roleBuffResolver = Objects.requireNonNull(roleBuffResolver, "roleBuffResolver");
        this.nativeAttributeBridge = nativeAttributeBridge;
        this.permanentBuffResolver = permanentBuffResolver;
        this.baseStats = baseStats;
        this.statCaps = statCaps;
    }

    /**
     * このアグリゲータに注入された {@code combat/stat-caps.yml}(未注入なら {@code null})。
     *
     * <p>公開している理由は1つだけ: <b>ATTRIBUTE チャネルのステはこのクラスの
     * {@link PlayerCombatAggregate#totalOf} を通らない</b>。{@code max-health} /
     * {@code move-speed} / {@code attack-reach} / {@code knockback-resistance} /
     * {@code attack-speed-bonus} は {@code skilltree.runtime.PerkAttributeApplier} が
     * バニラ Attribute へ直接書き込む別経路で、そのままだと上限がまったく効かなかった
     * (2026-07-26 保留 P7 → 2026-07-27 実装)。同じ上限表を2箇所で読ませるために公開する。
     */
    public StatCapsConfig statCaps() {
        return statCaps;
    }

    /** 近接/防御側の集計: メインハンド寄与 = プレイヤーの現在のメインハンド。 */
    public PlayerCombatAggregate aggregate(Player player) {
        return aggregate(player, player.getInventory().getItemInMainHand());
    }

    /**
     * 攻撃側(飛び道具含む)の集計。{@code mainhandContributor} は近接ならメインハンド、飛び道具なら
     * 発射した弓/クロスボウ/トライデントそのもの(High bugを踏襲: 着弾時のメインハンドではない)。
     */
    public PlayerCombatAggregate aggregate(Player player, ItemStack mainhandContributor) {
        return aggregate(player, mainhandContributor, false);
    }

    /**
     * {@code contributorIsOffhand} = true のとき、オフハンドスロットを合算から除外する —
     * {@code mainhandContributor} 自体がオフハンドのアイテム(例: オフハンド釣竿)である場合の二重計上防止。
     * スロット単位の除外であることが重要: 参照比較は Craft 実装がスロット読み取りごとに新しいミラーを
     * 返すため一致せず、equals は「同一設定の別アイテム」まで誤って除外するため使えない。
     */
    public PlayerCombatAggregate aggregate(Player player, ItemStack mainhandContributor,
                                           boolean contributorIsOffhand) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(mainhandContributor, "mainhandContributor");

        int tick = Bukkit.getCurrentTick();
        if (tick != cachedTick) {
            tickCache.clear();
            cachedTick = tick;
        }
        // mainhandContributor is snapshotted via clone() into the key so a caller mutating its own
        // ItemStack reference after this call (e.g. later in the same tick) cannot corrupt an
        // already-cached key's identity, and so the key's equals/hashCode are stable for the map's
        // lifetime regardless of what the live item does afterward.
        AggregateCacheKey key = new AggregateCacheKey(
                player.getUniqueId(), mainhandContributor.clone(), contributorIsOffhand);
        // computeIfAbsent は使えない (2026-07-28 実サーバで ConcurrentModificationException):
        // computeAggregate は解決器を経由して同じプレイヤーの aggregate(...) を再入呼び出しすることが
        // あり、その内側の put で HashMap が構造変更されるため、外側の computeIfAbsent が戻り際の
        // modCount チェックで落ちる。落ちると PerkAttributeApplier#reconcileAllOnline のループが
        // その場で中断し、以降のプレイヤーの属性が当たらないまま放置される。
        // get→compute→put なら再入しても内側が先に入れた値を外側が同値で上書きするだけで無害。
        PlayerCombatAggregate cached = tickCache.get(key);
        if (cached != null) {
            return cached;
        }
        PlayerCombatAggregate computed = computeAggregate(player, mainhandContributor, contributorIsOffhand);
        tickCache.put(key, computed);
        return computed;
    }

    /**
     * Explicit invalidation hook for a caller that mutates a player's equipment/perks mid-tick after
     * this cache may already hold a stale snapshot for them (see the {@link #tickCache} javadoc).
     *
     * <p>As of 2026-07-25 no such path was found among this codebase's combat hot paths:
     * {@code CombatListener} and {@code NativeCombatPerkListener} never mutate the attacker's/victim's
     * armor or mainhand weapon mid-event before a later {@link #aggregate} call for the SAME player in
     * the SAME tick. The one in-event item mutation found ({@code NativeCombatPerkListener#onShoot}'s
     * ammo-save, which increments the consumed arrow stack's amount) does not touch armor or the
     * mainhand-contributor item, so it is not part of the cache key and needs no invalidation. This
     * method is exposed for a future/fork caller that does have such a path.
     */
    public void invalidate(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        tickCache.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    private PlayerCombatAggregate computeAggregate(Player player, ItemStack mainhandContributor,
                                                    boolean contributorIsOffhand) {
        Map<String, Double> item = armorAndOffhandStats(player, contributorIsOffhand);
        Map<String, Map<String, Double>> multipliers =
                armorAndOffhandMultipliers(player, contributorIsOffhand);

        // mainhand はメインハンド(または発射武器)単体のマップとして保持する — armor/offhandは絶対に
        // 混ぜない。アイテムCTがこのマップだけを読むことで、防具/オフハンドのアイテムCTスタットが誤って
        // 近接攻撃をゲートしないようにするため。
        // 防具を手持ちにした場合は着用時のみ寄与(AttributeApplier と同じスロット規則)。
        Map<String, Double> mainhand = Map.of();
        if (!isWornOnlyArmor(mainhandContributor)) {
            mainhand = DerivedItemStats.resolve(
                    mainhandContributor, itemStats, combatDamage.weaponBaseFormula());
            mainhand.forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
            mergeMultipliers(multipliers, DerivedItemStats.resolveMultipliers(mainhandContributor, itemStats));
        }

        PerkBuffs perkBuffs = perkBuffResolver.buffsFor(player.getUniqueId(), mainhandContributor);
        mergeMultipliers(multipliers, perkBuffs.multipliers());
        Map<String, Double> addon = AddonCombatStats.read(player);

        // ソース3〜6(パーク general / 役職 attack・defense / 永続バフ / base-stats)は
        // #nonItemContribution へ切り出し済み(2026-07-26 マナ系ステ穴埋め、フォークの
        // nonItemStatTotal と実装を共有するため)。armor-defense-rate の非対称振り分け
        // (extraArmorDefenseRate → perkDefense経路)はそちらのjavadoc参照。
        NonItemContribution nonItem = nonItemContribution(player, perkBuffs);
        nonItem.item().forEach((key, value) -> item.merge(key, value, Double::sum));

        NativeArmorSetContribution nativeSets = nativeArmorSetContribution(player);
        Map<String, Double> attack = perkBuffs.attack();
        if (!nativeSets.attack().isEmpty()) {
            Map<String, Double> merged = new LinkedHashMap<>(attack);
            nativeSets.attack().forEach((key, value) -> merged.merge(key, value, Double::sum));
            attack = merged;
        }
        if (!nativeSets.general().isEmpty()) {
            nativeSets.general().forEach((key, value) -> item.merge(key, value, Double::sum));
        }

        Map<String, Double> perkDefense = perkBuffs.defense();
        if (!nativeSets.defense().isEmpty()) {
            Map<String, Double> merged = new LinkedHashMap<>(perkDefense);
            nativeSets.defense().forEach((key, value) -> merged.merge(key, value, Double::sum));
            perkDefense = merged;
        }
        if (nonItem.extraArmorDefenseRate() != 0.0) {
            Map<String, Double> merged = new LinkedHashMap<>(perkDefense);
            merged.merge(ARMOR_DEFENSE_RATE_KEY, nonItem.extraArmorDefenseRate(), Double::sum);
            perkDefense = merged;
        }

        return new PlayerCombatAggregate(item, mainhand, attack, perkDefense, addon, multipliers,
                statCaps);
    }

    /** {@link #nonItemContribution} の戻り値: item map へ合流させるべき加算分と、perkDefense 経路
     *  ({@code armor-defense-rate})へ振り分けるべき加算分を分離して保持する。 */
    private record NonItemContribution(Map<String, Double> item, double extraArmorDefenseRate) {
    }

    /**
     * 装備アイテムに由来しない、プレイヤー単位の寄与だけを1つのマップへ集める:
     * パーク general buff({@link PerkBuffs#general()}) / 役職 attack・defense buff
     * ({@link RoleBuffResolver}) / 永続バフ({@link PermanentBuffResolver}) /
     * 全プレイヤー一律の基礎ステ({@code combat/base-stats.yml}, {@link BaseStatsConfig})。
     * {@link #computeAggregate} の item マップ構築(旧ソース3〜6)と
     * {@link #nonItemStatTotal}(フォーク向け読み取り口)が、この1つの実装を共有する
     * (2026-07-26 マナ系ステ穴埋め: 同じ集計ロジックを2箇所に書くとドリフトするため共通化)。
     *
     * <p><b>armor-defense-rate の非対称を維持する(重要):</b> item map 側では
     * {@code DefenseStatBridge} が {@code armor-defense-rate} を意図的に 0 固定にしている
     * (vanilla armor属性ミラー専用の経路)ので、item に混ぜても防御側へ届かない(修正B)。
     * このため permanentBuffResolver / baseStats のこのキーだけは item ではなく
     * {@code extraArmorDefenseRate}(呼び出し元が perkDefense 経路へ合流させる)へ振り分ける。
     * 一方 perkBuffs.general() と役職 buff にはこの振り分けが元から無い(このキーがそこに現れても
     * 素直に item へ入る) — この非対称は変更前の {@code computeAggregate} と完全に同じ挙動であり、
     * ここを崩すと防御計算が静かに変わる。
     */
    private NonItemContribution nonItemContribution(Player player, PerkBuffs perkBuffs) {
        Map<String, Double> item = new LinkedHashMap<>();
        perkBuffs.general().forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));

        RoleBuffResolver.Contribution role = roleBuffResolver.contributionFor(player);
        role.attackBuffs().forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
        role.defenseBuffs().forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));

        double extraArmorDefenseRate = 0.0;
        if (permanentBuffResolver != null) {
            for (Map.Entry<String, Double> entry : permanentBuffResolver.buffsFor(player).entrySet()) {
                String key = StatKeys.canonical(entry.getKey());
                if (key.equals(ARMOR_DEFENSE_RATE_KEY)) {
                    extraArmorDefenseRate += entry.getValue();
                } else {
                    item.merge(key, entry.getValue(), Double::sum);
                }
            }
        }
        // 全プレイヤー一律の基礎ステ(combat/base-stats.yml)。permanent buffs と同じ流儀で item へ1レイヤ。
        // 属性チャネル(max-health 等)はここでは no-op(item mapはvanilla属性に読まれない)で、
        // PerkAttributeApplier 側で別途適用される。
        if (baseStats != null) {
            for (Map.Entry<String, Double> entry : baseStats.stats().entrySet()) {
                String key = StatKeys.canonical(entry.getKey());
                if (key.equals(ARMOR_DEFENSE_RATE_KEY)) {
                    extraArmorDefenseRate += entry.getValue();
                } else {
                    item.merge(key, entry.getValue(), Double::sum);
                }
            }
        }
        return new NonItemContribution(item, extraArmorDefenseRate);
    }

    /**
     * 装備アイテム由来を除いた、プレイヤー単位の寄与(パーク general / 役職 attack・defense buff /
     * 永続バフ / base-stats)だけの合計。{@link #aggregate}({@link PlayerCombatAggregate#totalOf}経由)
     * とは違い、防具4部位・メインハンド・オフハンドの item-stats を一切含まない —
     * 「装備は自前で集計済み」のフォーク側消費者(ArsPaper の {@code ArmorManaListener} 等)が、
     * TF の装備集計と二重計上せずにパーク分だけを上乗せするための読み取り口
     * (2026-07-26 マナ系ステ穴埋め: {@code mana_bonus}/{@code mana_regen} 等のパーク・プレステージ
     * 報酬・base-stats がフォーク側の自前装備集計から漏れていた穴を塞ぐ)。
     *
     * <p>{@code addon}({@code AddonCombatStats} — フォークがTFへ書き出すチャネル)は意図的に含めない。
     * 含めるとフォーク→TF→フォークのフィードバックループになり、値が呼び出しの都度膨張するため。
     *
     * <p>{@code multipliers} も適用しない({@link PlayerCombatAggregate#totalOf} と異なる点)。
     * 乗算レイヤ(呪い/セット効果等)は装備由来の集計に対して定義されたものであり、
     * 「装備は呼び出し元が別途自前集計済み」という前提のこのAPIでさらに乗算を掛けると、
     * 呼び出し元が装備集計へ既に適用済みの乗算と二重適用になりうる。よってここでは
     * 素の加算合計のみを返し、乗算適用は必要なら呼び出し元(装備集計と合流させた後)に委ねる。
     *
     * @param key 任意表記のステキー({@link StatKeys#canonical} で正規化される)
     */
    public double nonItemStatTotal(Player player, String key) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");
        String canonicalKey = StatKeys.canonical(key);
        PerkBuffs perkBuffs = perkBuffResolver.buffsFor(
                player.getUniqueId(), player.getInventory().getItemInMainHand());
        NonItemContribution contribution = nonItemContribution(player, perkBuffs);
        if (canonicalKey.equals(ARMOR_DEFENSE_RATE_KEY)) {
            return contribution.extraArmorDefenseRate();
        }
        return contribution.item().getOrDefault(canonicalKey, 0.0);
    }

    /** {@link #nativeArmorSetContribution} 戻り値: チャネル別に振り分け済みの armor-set-buffs 加算分。 */
    private record NativeArmorSetContribution(Map<String, Double> attack, Map<String, Double> defense,
                                               Map<String, Double> general) {
        private static final NativeArmorSetContribution EMPTY =
                new NativeArmorSetContribution(Map.of(), Map.of(), Map.of());
    }

    /**
     * {@link NativeAttributeBridge#armorAttributesFor} が返す装備部位数依存の加算分(set-buffs 由来。
     * ATTACK/DEFENSE/GENERAL の任意チャネルを取り得る、SKILL_TREE armor-set-buffs migration §1)を
     * {@link com.trinityforge.stats.StatVocabulary#channelOf} で判定し、それぞれの合流先へ振り分ける。
     * ATTRIBUTE チャネルのキー(move_speed 等)は {@link com.trinityforge.skilltree.runtime.PerkAttributeApplier}
     * 側で別途適用されるためここでは無視する(二重計上防止)。
     */
    private NativeArmorSetContribution nativeArmorSetContribution(Player player) {
        if (nativeAttributeBridge == null) {
            return NativeArmorSetContribution.EMPTY;
        }
        Map<String, Double> source = nativeAttributeBridge.armorAttributesFor(player);
        if (source.isEmpty()) {
            return NativeArmorSetContribution.EMPTY;
        }
        Map<String, Double> attack = new LinkedHashMap<>();
        Map<String, Double> defense = new LinkedHashMap<>();
        Map<String, Double> general = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value == null || value == 0.0 || !Double.isFinite(value)) {
                return; // zero/absent: never fabricate a key (mirrors the pre-generalization dodge_chance gate)
            }
            String canonicalKey = StatKeys.canonical(key);
            switch (com.trinityforge.stats.StatVocabulary.channelOf(canonicalKey)) {
                case ATTACK -> attack.merge(canonicalKey, value, Double::sum);
                case DEFENSE -> defense.merge(canonicalKey, value, Double::sum);
                case GENERAL -> general.merge(canonicalKey, value, Double::sum);
                default -> { /* ATTRIBUTE: applied by PerkAttributeApplier; NONE: dropped defensively */ }
            }
        });
        return new NativeArmorSetContribution(attack, defense, general);
    }

    /** Armor pieces only contribute while worn; holding them in hand must not double-dip TF stats. */
    private static boolean isWornOnlyArmor(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return switch (EquipmentSlotResolver.resolve(stack.getType())) {
            case HEAD, CHEST, LEGS, FEET -> true;
            default -> false;
        };
    }

    /**
     * メインハンドを除いた「その他装備」ステの合算(防具4部位 + (設定により)オフハンド + パーク攻撃buff +
     * アドオン)を1つのMapへ統合して返す。Change 1(P10 魔法アグリゲーション): 触媒による魔法詠唱の攻撃側
     * ステ集計は、キャスターのメインハンド武器のステを意図的に含めない(触媒自身のステは呼び出し側
     * (ArsPaper {@code TrinityForgeBridge})が別途合算する)。近接/防御側の {@link #aggregate} と同じ
     * 防具/オフハンドの集計ロジック({@link #armorAndOffhandStats})を再利用し、二重実装しない。
     */
    public Map<String, Double> aggregateExcludingMainhand(Player player) {
        return aggregateExcludingMainhandWith(player, null);
    }

    /**
     * {@link #aggregateExcludingMainhand} + 任意の追加寄与アイテム({@code extraContributor}、触媒等)。
     * 追加アイテムの解決済みステと乗算レイヤを「合算してから乗算」の内側に含める —
     * 近接パス(メインハンド武器が乗算対象の合算に含まれる)と対称にするため、フォークが
     * {@code aggregateExcludingMainhand の結果 + 触媒ステ} を外側で足すのではなくこちらを使う。
     */
    public Map<String, Double> aggregateExcludingMainhandWith(Player player, ItemStack extraContributor) {
        Objects.requireNonNull(player, "player");
        Map<String, Double> combined = armorAndOffhandStats(player, false);
        PerkBuffs perkBuffs = perkBuffResolver.buffsFor(player.getUniqueId(), player.getInventory().getItemInMainHand());
        perkBuffs.attack().forEach((key, value) -> combined.merge(StatKeys.canonical(key), value, Double::sum));
        perkBuffs.defense().forEach((key, value) -> combined.merge(StatKeys.canonical(key), value, Double::sum));
        perkBuffs.general().forEach((key, value) -> combined.merge(StatKeys.canonical(key), value, Double::sum));
        RoleBuffResolver.Contribution role = roleBuffResolver.contributionFor(player);
        role.attackBuffs().forEach((key, value) -> combined.merge(StatKeys.canonical(key), value, Double::sum));
        role.defenseBuffs().forEach((key, value) -> combined.merge(StatKeys.canonical(key), value, Double::sum));
        if (permanentBuffResolver != null) {
            permanentBuffResolver.buffsFor(player)
                    .forEach((key, value) -> combined.merge(StatKeys.canonical(key), value, Double::sum));
        }
        // 全プレイヤー一律の基礎ステ: 魔法(触媒)詠唱の攻撃側集計にも近接パスと同様に一律加算する
        // (permanent-buffs と対称。「全プレイヤーへ一律加算」の約束を魔法ダメージでも守る)。
        if (baseStats != null) {
            baseStats.stats()
                    .forEach((key, value) -> combined.merge(StatKeys.canonical(key), value, Double::sum));
        }
        AddonCombatStats.read(player)
                .forEach((key, value) -> combined.merge(StatKeys.canonical(key), value, Double::sum));
        Map<String, Map<String, Double>> multipliers = armorAndOffhandMultipliers(player, false);
        mergeMultipliers(multipliers, perkBuffs.multipliers());
        if (extraContributor != null && !extraContributor.getType().isAir()) {
            DerivedItemStats.resolve(extraContributor, itemStats, combatDamage.weaponBaseFormula())
                    .forEach((key, value) -> combined.merge(StatKeys.canonical(key), value, Double::sum));
            mergeMultipliers(multipliers, DerivedItemStats.resolveMultipliers(extraContributor, itemStats));
        }
        // 乗算レイヤ(装備 + 追加寄与アイテム)を合算後の総合値へ適用して返す。
        PlayerCombatAggregate mult = new PlayerCombatAggregate(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), multipliers);
        return new LinkedHashMap<>(mult.applyMultipliers(combined));
    }

    /**
     * 防具4部位 + (設定により)オフハンドの {@code DerivedItemStats} 合算(canonicalキー)。
     * {@link #aggregate} と {@link #aggregateExcludingMainhand} の共通部分(メインハンドを含まない側)。
     * {@code excludeOffhand} = true のときオフハンドスロットを丸ごと合算しない
     * (mainhandContributor がオフハンドの釣竿等であるときの二重計上防止。スロット単位 — 参照/equals
     * 比較はライブサーバーのミラー実装や同一設定の別アイテムで誤動作するため使わない)。
     */
    private Map<String, Double> armorAndOffhandStats(Player player, boolean excludeOffhand) {
        Map<String, Double> item = new LinkedHashMap<>();
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            // stats/item-stats.yml は装備中の各部位に毎回ライブ適用される(PDC無し・素のMATERIALでもOK)。
            // 防具はweaponカテゴリではないため、武器基礎式は発火しない(DerivedItemStatsのガード)。
            DerivedItemStats.resolve(piece, itemStats, combatDamage.weaponBaseFormula())
                    .forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
        }

        // オフハンド合算(per-item化, ユーザー確定): グローバルトグルを廃止し、オフハンドにあるアイテム自身の
        // item-stats に offhand-stats-apply: true が設定されている場合のみ、その派生ステを item に合算する
        // (mainhand には混ぜない — アイテムCTはメインハンド専用のまま)。既定 false なので未設定なら合算しない。
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (!excludeOffhand && offhandStatsApply(offhand)) {
            DerivedItemStats.resolve(offhand, itemStats, combatDamage.weaponBaseFormula())
                    .forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
        }
        return item;
    }

    /** 防具4部位 + (設定により)オフハンドの乗算レイヤ収集({@link #armorAndOffhandStats} と対)。 */
    private Map<String, Map<String, Double>> armorAndOffhandMultipliers(Player player, boolean excludeOffhand) {
        Map<String, Map<String, Double>> multipliers = new LinkedHashMap<>();
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            mergeMultipliers(multipliers, DerivedItemStats.resolveMultipliers(piece, itemStats));
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (!excludeOffhand && offhandStatsApply(offhand)) {
            mergeMultipliers(multipliers, DerivedItemStats.resolveMultipliers(offhand, itemStats));
        }
        return multipliers;
    }

    /** レイヤ→ステ→Σ(v-1) を加算マージする(同一レイヤは足し合わせてから乗算、の「足し合わせ」部分)。 */
    private static void mergeMultipliers(Map<String, Map<String, Double>> into,
                                         Map<String, Map<String, Double>> add) {
        add.forEach((layer, stats) -> {
            Map<String, Double> target = into.computeIfAbsent(layer, k -> new LinkedHashMap<>());
            stats.forEach((key, value) -> target.merge(StatKeys.canonical(key), value, Double::sum));
        });
    }

    /**
     * オフハンドのアイテムが「オフハンド時ステ合算」対象か。item-stats の {@code offhand-stats-apply}(既定
     * false)を見る。null/エア/未設定は false。バニラ素材(CMDなし)でも該当エントリがあれば有効。
     */
    private boolean offhandStatsApply(ItemStack offhand) {
        return offhandStatsApply(offhand, itemStats);
    }

    /**
     * {@link #offhandStatsApply(ItemStack)} の static 版。外部の防御者集計
     * ({@link #equipmentDefenseItemStats})が同一の {@code offhand-stats-apply} 判定を共有するために切り出す。
     */
    private static boolean offhandStatsApply(ItemStack offhand, ItemStatsConfig itemStats) {
        if (offhand == null || offhand.getType().isAir()) {
            return false;
        }
        Integer cmd = offhand.hasItemMeta()
                ? DerivedItemStats.customModelDataOf(offhand.getItemMeta()) : null;
        return itemStats.profileFor(offhand.getType(), cmd)
                .map(com.trinityforge.stats.ItemStatProfile::offhandApplies)
                .orElseGet(() -> itemStats.fallback().map(
                        com.trinityforge.stats.ItemStatProfile::offhandApplies).orElse(false));
    }

    /**
     * 外部の防御者(DPSChecker のダミー等、プレイヤーでない {@link org.bukkit.entity.LivingEntity})のために、
     * 装備(防具4部位 + メインハンド + オフハンド)からプレイヤーと<b>同一ロジック</b>で防御側 item ステ
     * (乗算適用済み)を導出する公開API。{@link #aggregate} が使う {@link #armorAndOffhandStats}
     * (オフハンドゲート {@code offhand-stats-apply})・メインハンド折込({@link #isWornOnlyArmor} 規則)・
     * 乗算レイヤ({@link PlayerCombatAggregate#applyMultipliers})をそのまま共有し、プレイヤー進行系
     * (perk/role/permanent/base)は<b>含めない</b>(純装備寄与)。
     *
     * <p>戻り値は canonical ステキー→値の乗算適用済みマップ。呼び出し側は {@link DefenseStatBridge#bridge}/
     * {@link DefenseStatBridge#dodgeChance} で {@link DefenseStats}/回避へブリッジする(プレイヤー防御と同じく
     * 防御率%は 0固定 = vanilla armor 属性ミラー経路に委ねる)。{@link DerivedItemStats#resolve} は不正アイテムで
     * 例外を投げうるため、呼び出し側で捕捉すること(TF内部の {@code CombatListener} と同様)。Bukkit 読み取りを
     * 伴うためメインスレッド前提。
     *
     * @param armorContents 防具4部位(通常 {@code EntityEquipment} 由来。null/エア要素は安全にスキップ)
     * @param mainhand      メインハンド(着用専用防具を手持ちした場合は寄与しない)。null/エア可
     * @param offhand       オフハンド({@code offhand-stats-apply: true} のときのみ寄与)。null/エア可
     */
    public static Map<String, Double> equipmentDefenseItemStats(
            ItemStack[] armorContents, ItemStack mainhand, ItemStack offhand,
            ItemStatsConfig itemStats, CombatDamageConfig combatDamage) {
        Objects.requireNonNull(itemStats, "itemStats");
        Objects.requireNonNull(combatDamage, "combatDamage");
        Map<String, Double> item = new LinkedHashMap<>();
        Map<String, Map<String, Double>> multipliers = new LinkedHashMap<>();

        if (armorContents != null) {
            for (ItemStack piece : armorContents) {
                if (piece == null || piece.getType().isAir()) {
                    continue;
                }
                DerivedItemStats.resolve(piece, itemStats, combatDamage.weaponBaseFormula())
                        .forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
                mergeMultipliers(multipliers, DerivedItemStats.resolveMultipliers(piece, itemStats));
            }
        }
        // オフハンドは offhand-stats-apply: true のときのみ(プレイヤー防御と同一ゲート)。
        if (offhandStatsApply(offhand, itemStats)) {
            DerivedItemStats.resolve(offhand, itemStats, combatDamage.weaponBaseFormula())
                    .forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
            mergeMultipliers(multipliers, DerivedItemStats.resolveMultipliers(offhand, itemStats));
        }
        // メインハンドは着用専用防具を手持ちした場合を除き寄与(プレイヤー防御と同一の折込規則)。
        if (mainhand != null && !mainhand.getType().isAir() && !isWornOnlyArmor(mainhand)) {
            DerivedItemStats.resolve(mainhand, itemStats, combatDamage.weaponBaseFormula())
                    .forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
            mergeMultipliers(multipliers, DerivedItemStats.resolveMultipliers(mainhand, itemStats));
        }

        PlayerCombatAggregate mult = new PlayerCombatAggregate(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), multipliers);
        return new LinkedHashMap<>(mult.applyMultipliers(item));
    }
}
