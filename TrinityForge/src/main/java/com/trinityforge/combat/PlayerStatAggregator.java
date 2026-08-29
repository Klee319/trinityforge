package com.trinityforge.combat;

import com.trinityforge.config.domains.BaseStatsConfig;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.StatCapsConfig;
import com.trinityforge.progression.PermanentBuffResolver;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.UseRequirementService;
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
import java.util.function.Predicate;

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

    private final ItemStatsConfig itemStats;
    private final CombatDamageConfig combatDamage;
    private final PerkBuffResolver perkBuffResolver;
    private final RoleBuffResolver roleBuffResolver;
    private final NativeAttributeBridge nativeAttributeBridge;
    private final PermanentBuffResolver permanentBuffResolver;
    private final BaseStatsConfig baseStats;
    private final StatCapsConfig statCaps;
    private final UseRequirementService useRequirementService;

    /**
     * Per-tick memo cache (2026-07-25, CMB-30): {@link #aggregate} re-runs 4 armor pieces + mainhand +
     * offhand {@link DerivedItemStats} resolution, {@link PerkBuffResolver}, {@link RoleBuffResolver},
     * {@link PermanentBuffResolver} (a full achievement/collection scan), and {@link BaseStatsConfig}
     * on every call — 3-6x per single hit in practice ({@code CombatListener},
     * {@code NativeCombatPerkListener}, and {@code PerkAttributeApplier}'s 10-tick poll all call this
     * for the same player, often within the same server tick). Memoizing the result per
     * {@code (player, mainhand-contributor item, armor/offhand snapshot, offhand-exclusion flag)} for
     * the lifetime of ONE tick means every consumer within a single damage event observes an identical
     * snapshot. The equipment snapshot is part of the key because a non-cancellable armor-change event
     * may replace armor mid-tick; a rejected piece must not reuse a pre-change aggregate while awaiting
     * deferred removal.
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
     * tick" and self-evicts every tick — never an unbounded per-UUID map.
     *
     * <p><b>メインスレッド専用(2026-07-28 に強制化)。</b>同期化されていない plain HashMap なので、
     * 複数スレッドから触ると壊れる。「呼び出し側は全部メインスレッドだから安全」という当初の想定は
     * <b>誤りだった</b> — {@code NativeExperienceDispatcher#drain}(非同期タスク)が
     * {@code NativeProgressionService} 経由で {@code skill_exp_bonus} を引くために
     * {@link #aggregate(Player)} を呼んでおり、実サーバで
     * {@link java.util.ConcurrentModificationException} を起こしていた。
     * 現在は {@link #aggregate(Player, ItemStack, boolean)} が
     * {@link Bukkit#isPrimaryThread()} を見て、非同期呼び出しにはこのマップを触らせない。
     */
    private final Map<AggregateCacheKey, PlayerCombatAggregate> tickCache = new HashMap<>();
    private int cachedTick = Integer.MIN_VALUE;

    private record AggregateCacheKey(
            UUID playerId,
            ItemStack mainhandContributor,
            boolean contributorIsOffhand,
            ItemStack helmet,
            ItemStack chestplate,
            ItemStack leggings,
            ItemStack boots,
            ItemStack offhand,
            ItemStack actualMainhandSlot) {
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
     *                  permanent buffs.
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
        this(itemStats, combatDamage, perkBuffResolver, roleBuffResolver, nativeAttributeBridge,
                permanentBuffResolver, baseStats, statCaps, null);
    }

    /**
     * @param useRequirementService optional use-level gate. When present, armor that the player may
     *                              not use is excluded from every equipment-stat and multiplier path
     *                              immediately, including the non-cancellable armor-change event's
     *                              one-tick deferred-removal window.
     */
    public PlayerStatAggregator(ItemStatsConfig itemStats,
                                CombatDamageConfig combatDamage,
                                PerkBuffResolver perkBuffResolver,
                                RoleBuffResolver roleBuffResolver,
                                NativeAttributeBridge nativeAttributeBridge,
                                PermanentBuffResolver permanentBuffResolver,
                                BaseStatsConfig baseStats,
                                StatCapsConfig statCaps,
                                UseRequirementService useRequirementService) {
        this.itemStats = Objects.requireNonNull(itemStats, "itemStats");
        this.combatDamage = Objects.requireNonNull(combatDamage, "combatDamage");
        this.perkBuffResolver = Objects.requireNonNull(perkBuffResolver, "perkBuffResolver");
        this.roleBuffResolver = Objects.requireNonNull(roleBuffResolver, "roleBuffResolver");
        this.nativeAttributeBridge = nativeAttributeBridge;
        this.permanentBuffResolver = permanentBuffResolver;
        this.baseStats = baseStats;
        this.statCaps = statCaps;
        this.useRequirementService = useRequirementService;
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

        // 非同期スレッドからの呼び出しは tickCache に一切触らせない (2026-07-28 実サーバの
        // ConcurrentModificationException の真因)。
        // NativeExperienceDispatcher#drain は非同期タスクで、そこから
        // NativeProgressionService -> TrinityForge の skill_exp_bonus サプライヤ -> aggregate(...) と
        // 降りてくる。そのため plain HashMap である tickCache をメインスレッドと非同期スレッドが
        // 同時に触っていた。CME はその最も軽い症状にすぎず、無限ループやエントリ消失まで起こりうる。
        // 非同期側はキャッシュを諦めて毎回計算する(呼び出し頻度は EXP 付与ごとで低く、実害は無い)。
        if (!Bukkit.isPrimaryThread()) {
            return computeAggregate(player, mainhandContributor, contributorIsOffhand);
        }

        int tick = Bukkit.getCurrentTick();
        if (tick != cachedTick) {
            tickCache.clear();
            cachedTick = tick;
        }
        // mainhandContributor is snapshotted via clone() into the key so a caller mutating its own
        // ItemStack reference after this call (e.g. later in the same tick) cannot corrupt an
        // already-cached key's identity, and so the key's equals/hashCode are stable for the map's
        // lifetime regardless of what the live item does afterward.
        // actualMainhandSlot(2026-08-13, 修正1のrevert後も鍵に残す理由): computeAggregate 自身は
        // mainhandContributor をどちらの手でも無条件に合算するようになったが、
        // nativeArmorSetContribution -> NativeAttributeBridge#armorAttributesFor ->
        // PlayerStatAggregator#nonPerkStatTotal の経路が「プレイヤーの実メインハンド」を独立に読む
        // (nonPerkStatTotal:529行)。実メインハンドは mainhandContributor と独立に変わり得るため、
        // 鍵に含めないと同一tick内で古い実メインハンドのスナップショットを返しうる。
        AggregateCacheKey key = new AggregateCacheKey(
                player.getUniqueId(), mainhandContributor.clone(), contributorIsOffhand,
                cloneOrNull(player.getInventory().getHelmet()),
                cloneOrNull(player.getInventory().getChestplate()),
                cloneOrNull(player.getInventory().getLeggings()),
                cloneOrNull(player.getInventory().getBoots()),
                cloneOrNull(player.getInventory().getItemInOffHand()),
                cloneOrNull(player.getInventory().getItemInMainHand()));
        // computeIfAbsent は使わない: マッピング関数の実行中に同じマップが構造変更されると
        // 戻り際の modCount チェックで CME になる。上のスレッドガードで主因は塞いだが、
        // computeAggregate が解決器を経由して同じプレイヤーの aggregate(...) へ再入した場合にも
        // 同じ壊れ方をするため、再入に対して無害な get→compute→put のままにしておく
        // (再入しても内側が先に入れた値を外側が同値で上書きするだけ)。
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
        ItemStack[] usableArmor = usableArmorContents(player);

        // 寄与アイテム(mainhandContributor)がオフハンドにあり、かつ「今のオフハンドの中身」と
        // プロファイルが一致するときだけ、オフハンド"スロット"側の合算を除外する(下の寄与アイテム
        // 合算で既に数えるため、スロット側でも数えると二重計上になる)。飛び道具は発射から着弾まで
        // 秒単位の遅延があり、その間にオフハンドの中身が別アイテムへ入れ替わっていることがある。
        // 入れ替わっていれば二重計上は起こり得ないので除外してはいけない(除外すると新しく持ち替えた
        // アイテムの寄与が無言で落ちる)。
        boolean excludeOffhandSlot = contributorIsOffhand
                && sameItemProfile(player.getInventory().getItemInOffHand(), mainhandContributor);
        Map<String, Double> item = armorAndOffhandStats(player, usableArmor, excludeOffhandSlot);
        Map<String, Map<String, Double>> multipliers =
                armorAndOffhandMultipliers(player, usableArmor, excludeOffhandSlot);

        // mainhand はアイテムCT専用マップ(防具/オフハンドを絶対に混ぜてはいけない不変条件)。
        // 2026-08-13(ユーザー確定仕様・親確定解釈): 「実際にその行為に使われたアイテム
        // (=mainhandContributor)は、どちらの手にあっても常に合算する」。offhand-stats-apply の門は
        // 「オフハンドに持っているだけのアイテム」(armorAndOffhandStats 側の受動的寄与)にだけ掛ける —
        // これはユーザーが是とした魔法の規則(発動したアイテム=触媒だけ合算)と同型。防具を手持ちにした
        // 場合は着用時のみ寄与(AttributeApplier と同じスロット規則)。CT はプレイヤーが実際に「使った」
        // アイテムのものが正しいので、mainhand マップも mainhandContributor から作る
        // (contributorIsOffhand=false のときはこれが元々の実メインハンドと同一)。
        Map<String, Double> mainhand = Map.of();
        if (!excludedFromSlotStats(mainhandContributor, itemStats)) {
            mainhand = DerivedItemStats.resolve(
                    mainhandContributor, itemStats, combatDamage.weaponBaseFormula());
            mainhand.forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
            mergeMultipliers(multipliers, DerivedItemStats.resolveMultipliers(mainhandContributor, itemStats));
        }

        PerkBuffs perkBuffs = perkBuffResolver.buffsFor(player.getUniqueId(), mainhandContributor);
        mergeMultipliers(multipliers, perkBuffs.multipliers());
        Map<String, Double> addon = addonContribution(player);
        // アドオン(スレッドのセット効果「乗算モード」)の倍率を合流させる。
        // 加算チャネル(addon)とは別物 —— こちらは加算合算が終わった総合値に掛かる。
        // 2026-08-22(W-186): レイヤIDは PDC が持つ。thread-sets.yml で layer: を指定した倍率は
        // 装備側の同じレイヤへ吸収され(レイヤ内は Σ(v-1) の加算)、無指定のものだけが
        // AddonCombatStats#MULTIPLIER_LAYER_ID の1本にまとまる。以前は全部が後者に固定だった。
        Map<String, Map<String, Double>> addonMultipliers = AddonCombatStats.readLayeredMultipliers(player);
        if (!addonMultipliers.isEmpty()) {
            mergeMultipliers(multipliers, addonMultipliers);
        }

        // ソース3〜6(パーク general / 役職 attack・defense / 永続バフ / base-stats)は
        // #nonItemContribution へ切り出し済み(2026-07-26 マナ系ステ穴埋め、フォークの
        // nonItemStatTotal と実装を共有するため)。
        Map<String, Double> nonItem = nonItemContribution(player, perkBuffs);
        nonItem.forEach((key, value) -> item.merge(key, value, Double::sum));

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

        return new PlayerCombatAggregate(item, mainhand, attack, perkDefense, addon, multipliers,
                statCaps);
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
     * <p><b>2026-08-15:</b> かつてここには {@code armor-defense-rate} だけを item ではなく
     * perkDefense 経路へ逃がす非対称な振り分け({@code extraArmorDefenseRate})があった。
     * 「item map 側では {@code DefenseStatBridge} がこのキーを 0 固定にしている(バニラ防具属性
     * ミラー専用の経路だった)ので、item に混ぜても防御側へ届かない」ためだったが、防具値ステ自体を
     * 廃止して {@code defense-rate} へ一本化し、{@code DefenseStatBridge} が item map から直接
     * 読むようになったので、この迂回路ごと不要になった。全ソースが素直に item へ入る。
     */
    private Map<String, Double> nonItemContribution(Player player, PerkBuffs perkBuffs) {
        Map<String, Double> item = new LinkedHashMap<>();
        perkBuffs.general().forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
        nonPerkNonItemContribution(player).forEach((key, value) -> item.merge(key, value, Double::sum));
        return item;
    }

    /**
     * {@link #nonItemContribution} からパーク general({@link PerkBuffs#general()})だけを除いたもの
     * (役職 attack・defense buff / 永続バフ / base-stats)。2026-08-13 修正2({@code armor-set-bonus}
     * 総合値化)で {@link #nonPerkStatTotal} と共有するために切り出した — パーク分は
     * {@code NativeAttributeBridge#armorAttributesFor} が {@code perkBuffs.general()} を自前で
     * 読んで既に増幅式へ折り込んでいるため、こちら側に含めると二重計上になる。
     */
    private Map<String, Double> nonPerkNonItemContribution(Player player) {
        Map<String, Double> item = new LinkedHashMap<>();
        RoleBuffResolver.Contribution role = roleBuffResolver.contributionFor(player);
        role.attackBuffs().forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
        role.defenseBuffs().forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));

        if (permanentBuffResolver != null) {
            permanentBuffResolver.buffsFor(player).forEach(
                    (key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
        }
        // 全プレイヤー一律の基礎ステ(combat/base-stats.yml)。permanent buffs と同じ流儀で item へ1レイヤ。
        // 属性チャネル(max-health 等)はここでは no-op(item mapはvanilla属性に読まれない)で、
        // PerkAttributeApplier 側で別途適用される。
        if (baseStats != null) {
            baseStats.stats().forEach(
                    (key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
        }
        return item;
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
        return nonItemContribution(player, perkBuffs).getOrDefault(canonicalKey, 0.0);
    }

    /**
     * パーク由来({@link PerkBuffs#general()})と native セット由来({@code NativeAttributeBridge
     * #armorAttributesFor})を<b>除いた</b>、プレイヤーの該当ステ「総合値」の単純加算合計。
     * 2026-08-13 修正2({@code armor-set-bonus} 総合値化): {@code NativeAttributeBridge} が装備/
     * 役職/永続/base-stats 由来の {@code armor-set-bonus} も増幅率へ反映できるよう、循環しない
     * 読み取り口として新設した。
     *
     * <p>含む: 防具4部位 + オフハンド({@code offhand-stats-apply} 門つき) + 実メインハンド の
     * item ステ + 役職バフ + 永続バフ + base-stats。除く: パーク general(呼び出し元の
     * {@code NativeAttributeBridge} が自前で足すため二重計上になる) / native セット由来(この値自体が
     * native セットの増幅率計算に使われるため、含めると自己参照になる)。
     *
     * <p><b>循環に注意:</b> {@link #computeAggregate} の途中で
     * {@code NativeAttributeBridge#armorAttributesFor} が呼ばれ、それがこのメソッドを呼ぶ経路が
     * 想定されている。このメソッドは {@link #aggregate}/{@link #totalOf} や
     * {@link #nativeArmorSetContribution} を一切呼ばないので、その循環を作らない。
     *
     * <p>{@link #nonItemStatTotal} と違い、装備(防具+オフハンド+メインハンド)の item-stats を<b>含む</b>
     * ({@code nonItemStatTotal} は「装備は呼び出し元が別途自前集計済み」なフォーク向けAPIで、こちらは
     * 逆に「装備集計はTF内部でこのメソッドが行う」)。乗算レイヤは適用しない(素の加算合計。
     * {@code armor-set-bonus} 自体が乗算レイヤ側の増幅率入力であり、ここへ乗算を掛けると二重適用になる)。
     *
     * <p><b>意図的な非対称(2026-08-13):</b> このメソッドは常に {@code armorAndOffhandStats(player,
     * usableArmor, false)}(excludeOffhand=false)でオフハンドを含める。一方 {@link #computeAggregate}
     * は寄与アイテムがオフハンドにあり、かつそれが「今のオフハンドの中身」と同一プロファイルのときだけ
     * オフハンド"スロット"側を除外する({@code excludeOffhandSlot})。つまり armor-set-bonus の増幅率は
     * その除外の有無に関わらず常に全装備(オフハンド含む)を見る — 増幅率は「セットとして何を装備して
     * いるか」の指標であり、寄与アイテムの二重計上防止(item ステの合算)とは別の関心事だから。
     *
     * @param key 任意表記のステキー({@link StatKeys#canonical} で正規化される)
     */
    public double nonPerkStatTotal(Player player, String key) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");
        String canonicalKey = StatKeys.canonical(key);

        ItemStack[] usableArmor = usableArmorContents(player);
        Map<String, Double> item = armorAndOffhandStats(player, usableArmor, false);
        ItemStack actualMainhand = player.getInventory().getItemInMainHand();
        if (!excludedFromSlotStats(actualMainhand, itemStats)) {
            DerivedItemStats.resolve(actualMainhand, itemStats, combatDamage.weaponBaseFormula())
                    .forEach((k, v) -> item.merge(StatKeys.canonical(k), v, Double::sum));
        }

        nonPerkNonItemContribution(player).forEach((k, v) -> item.merge(k, v, Double::sum));

        return item.getOrDefault(canonicalKey, 0.0);
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
        // Native armor-set bonuses are computed from the live equipped-piece count. If even one
        // piece is unusable, do not let that piece complete a 3/4-piece set during deferred removal.
        // Returning EMPTY is deliberately conservative for this one-tick invalid state.
        if (hasDeniedArmor(player)) {
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
     * <b>装着専用アイテム(スレッド)か</b> — {@code stats/item-stats.yml} の
     * {@code socketed-only-stats: true}({@link com.trinityforge.stats.ItemStatProfile#socketedOnly})。
     *
     * <p>true のアイテムは<b>どのスロット(防具4部位 / メインハンド / オフハンド / 触媒)からも</b>
     * ステを寄与しない。{@link #isWornOnlyArmor} が材質({@link EquipmentSlotResolver})で判定するのに対し、
     * こちらは<b>設定側の宣言</b>で判定する — スレッドの材質(鍛冶型テンプレート / 陶器の欠片 / 旗の模様)は
     * どの装備カテゴリにも当たらないため材質からは判別できず、材質を並べた許可リストで判定すると
     * スレッドが1種増えるたびに穴が開き直るため。
     *
     * <p><b>装着済みスレッドの寄与は壊さない:</b> ArsPaper の {@code ArmorManaListener} は
     * {@code TrinityForgeBridge#resolveThreadStats} →
     * {@code WeaponAttackStatResolver#resolveItemStats(material, cmd, quality, rollSeed)} →
     * {@code DerivedItemStats#profileStats} という<b>このクラスを通らない</b>経路で解決し、結果を
     * {@code AddonCombatStats} へ書き戻す。ここでの遮断はプレイヤーのスロット由来の合算だけに掛かる。
     * ロア表示({@code ItemAssembler}/{@code LoreComposer})も {@code DerivedItemStats#resolve} を
     * そのまま通るので「挿したら何が付くか」の表示は変わらない。
     *
     * <p>判定の流儀は {@link #offhandStatsApply(ItemStack, ItemStatsConfig)} と同じ
     * (Material + CustomModelData でプロファイルを引き、無ければフォールバック)。
     */
    private static boolean socketedOnly(ItemStack stack, ItemStatsConfig itemStats) {
        if (stack == null || stack.getType().isAir() || itemStats == null) {
            return false;
        }
        Integer cmd = stack.hasItemMeta()
                ? DerivedItemStats.customModelDataOf(stack.getItemMeta()) : null;
        return itemStats.profileFor(stack.getType(), cmd)
                .map(com.trinityforge.stats.ItemStatProfile::socketedOnly)
                .orElseGet(() -> itemStats.fallback().map(
                        com.trinityforge.stats.ItemStatProfile::socketedOnly).orElse(false));
    }

    /**
     * そのアイテムを<b>スロットに置いているだけ</b>では item ステを合算してはいけないか。
     * 「着用専用の防具を手に持っている」({@link #isWornOnlyArmor})と
     * 「装着専用のスレッド」({@link #socketedOnly})の2つを1つの門にまとめたもの —
     * 合算地点が複数(防具ループ / オフハンド / メインハンド寄与 / 触媒 / 外部防御者)あるため、
     * 門を1つにしておかないと1箇所抜けただけで穴が再発する。
     */
    private static boolean excludedFromSlotStats(ItemStack stack, ItemStatsConfig itemStats) {
        return isWornOnlyArmor(stack) || socketedOnly(stack, itemStats);
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
        ItemStack[] usableArmor = usableArmorContents(player);
        Map<String, Double> combined = armorAndOffhandStats(player, usableArmor, false);
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
        addonContribution(player)
                .forEach((key, value) -> combined.merge(StatKeys.canonical(key), value, Double::sum));
        Map<String, Map<String, Double>> multipliers =
                armorAndOffhandMultipliers(player, usableArmor, false);
        mergeMultipliers(multipliers, perkBuffs.multipliers());
        if (extraContributor != null && !extraContributor.getType().isAir()
                && !socketedOnly(extraContributor, itemStats)) {
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
    private Map<String, Double> armorAndOffhandStats(Player player, ItemStack[] usableArmor,
                                                     boolean excludeOffhand) {
        Map<String, Double> item = new LinkedHashMap<>();
        for (ItemStack piece : usableArmor) {
            // stats/item-stats.yml は装備中の各部位に毎回ライブ適用される(PDC無し・素のMATERIALでもOK)。
            // 防具はweaponカテゴリではないため、武器基礎式は発火しない(DerivedItemStatsのガード)。
            // 装着専用(スレッド)はどのスロットからも寄与しない(通常は防具スロットに入らないが、
            // コマンド等で押し込まれた場合の抜け道を塞ぐ)。
            if (socketedOnly(piece, itemStats)) {
                continue;
            }
            DerivedItemStats.resolve(piece, itemStats, combatDamage.weaponBaseFormula())
                    .forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
        }

        // オフハンド合算(per-item化, ユーザー確定): グローバルトグルを廃止し、オフハンドにあるアイテム自身の
        // item-stats に offhand-stats-apply: true が設定されている場合のみ、その派生ステを item に合算する
        // (mainhand には混ぜない — アイテムCTはメインハンド専用のまま)。既定 false なので未設定なら合算しない。
        // 装着専用(スレッド)は offhand-stats-apply の値によらず寄与しない。
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (!excludeOffhand && offhandContributes(offhand, player.isBlocking(), itemStats)
                && !socketedOnly(offhand, itemStats)) {
            DerivedItemStats.resolve(offhand, itemStats, combatDamage.weaponBaseFormula())
                    .forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
        }
        return item;
    }

    /** 防具4部位 + (設定により)オフハンドの乗算レイヤ収集({@link #armorAndOffhandStats} と対)。 */
    private Map<String, Map<String, Double>> armorAndOffhandMultipliers(
            Player player, ItemStack[] usableArmor, boolean excludeOffhand) {
        Map<String, Map<String, Double>> multipliers = new LinkedHashMap<>();
        for (ItemStack piece : usableArmor) {
            if (socketedOnly(piece, itemStats)) {
                continue; // 装着専用は乗算レイヤも寄与しない(加算側と同じ門)
            }
            mergeMultipliers(multipliers, DerivedItemStats.resolveMultipliers(piece, itemStats));
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (!excludeOffhand && offhandContributes(offhand, player.isBlocking(), itemStats)
                && !socketedOnly(offhand, itemStats)) {
            mergeMultipliers(multipliers, DerivedItemStats.resolveMultipliers(offhand, itemStats));
        }
        return multipliers;
    }

    /**
     * Resolves one configured stat from only the equipped armor pieces accepted by {@code filter}.
     * Unlike {@link #aggregate(Player)}, this deliberately excludes main/offhand items and every
     * player-wide perk/addon source. It is used where an upstream formula distinguishes light and
     * heavy armor totals (Valhalla's TOTAL_LIGHT_ARMOR/TOTAL_HEAVY_ARMOR) rather than asking for the
     * player's combined defense value.
     *
     * <p>The same use-requirement gate, {@link DerivedItemStats} resolver, canonical key handling,
     * and item multiplier layers as normal combat aggregation are retained, so editor-authored
     * custom armor points remain the source of truth.
     */
    public double equippedArmorStatTotal(Player player, String statKey, Predicate<ItemStack> filter) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(statKey, "statKey");
        Objects.requireNonNull(filter, "filter");
        String canonicalKey = StatKeys.canonical(statKey);
        Map<String, Double> item = new LinkedHashMap<>();
        Map<String, Map<String, Double>> multipliers = new LinkedHashMap<>();
        for (ItemStack piece : usableArmorContents(player)) {
            if (piece == null || piece.getType().isAir() || !filter.test(piece)
                    || socketedOnly(piece, itemStats)) {
                continue;
            }
            DerivedItemStats.resolve(piece, itemStats, combatDamage.weaponBaseFormula())
                    .forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
            mergeMultipliers(multipliers, DerivedItemStats.resolveMultipliers(piece, itemStats));
        }
        return new PlayerCombatAggregate(
                item, Map.of(), Map.of(), Map.of(), Map.of(), multipliers, statCaps)
                .totalOf(canonicalKey);
    }

    /**
     * True while at least one equipped armor piece fails the same use gate as
     * {@code ArmorUseGateListener}. Combat uses this to suppress vanilla armor/protection until the
     * non-cancellable armor-change event's deferred removal completes.
     */
    public boolean hasDeniedArmor(Player player) {
        Objects.requireNonNull(player, "player");
        if (useRequirementService == null) {
            return false;
        }
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece != null && !piece.getType().isAir()
                    && useRequirementService.denialFor(player, piece).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Returns the live armor snapshot with denied pieces replaced by AIR. */
    private ItemStack[] usableArmorContents(Player player) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        if (useRequirementService == null) {
            return armor;
        }
        ItemStack[] usable = armor.clone();
        for (int i = 0; i < usable.length; i++) {
            ItemStack piece = usable[i];
            if (piece != null && !piece.getType().isAir()
                    && useRequirementService.denialFor(player, piece).isPresent()) {
                usable[i] = null;
            }
        }
        return usable;
    }

    private static ItemStack cloneOrNull(ItemStack stack) {
        return stack == null ? null : stack.clone();
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
     * フォークがプレイヤー PDC へ書いた addon に、装備へ挿さっているスレッドのライブ合算を穴埋めする。
     *
     * <p>既に addon にあるキーはフォーク側(セット効果込み)を優先する。無いキーだけライブ値を足すので、
     * フォークが動いているときの二重計上は起きない。実効 thread-slots が 0 でフォークが空書きした
     * ときや、詠唱効率のようにフォークがマナ経路へ迂回しているキーがここで拾える。
     */
    private Map<String, Double> addonContribution(Player player) {
        Map<String, Double> addon = new LinkedHashMap<>(AddonCombatStats.read(player));
        SocketedThreadStats.collect(player, itemStats).forEach(addon::putIfAbsent);
        return addon;
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
    /**
     * オフハンドのアイテムが「いま」ステを寄与するか。{@code offhand-stats-apply} に加えて、
     * {@code offhand-stats-require-blocking: true} のアイテム(盾)は<b>構えている間だけ</b>通す
     * (2026-08-20 / W-163)。
     *
     * <p>なぜ盾を材質で決め打ちしないか: 判定を {@code Material.SHIELD} に固定すると、CMD 付きの
     * TF 製の盾以外(モジュール追加の盾・別素材の受け系オフハンド品)に同じ挙動を与えられない。
     * {@code offhand-stats-apply} と同じ per-item のフラグにしておけば、item-stats.yml だけで
     * 「持っているだけで乗る品」と「構えたときだけ乗る品」を書き分けられる。
     *
     * @param blocking {@code Player#isBlocking()}。盾を構えている間だけ true になる
     */
    private static boolean offhandContributes(ItemStack offhand, boolean blocking,
                                              ItemStatsConfig itemStats) {
        if (!offhandStatsApply(offhand, itemStats)) {
            return false;
        }
        return blocking || !offhandRequiresBlocking(offhand, itemStats);
    }

    /** {@code offhand-stats-require-blocking}(既定 false)。null/エア/未設定は false。 */
    private static boolean offhandRequiresBlocking(ItemStack offhand, ItemStatsConfig itemStats) {
        if (offhand == null || offhand.getType().isAir()) {
            return false;
        }
        Integer cmd = offhand.hasItemMeta()
                ? DerivedItemStats.customModelDataOf(offhand.getItemMeta()) : null;
        return itemStats.profileFor(offhand.getType(), cmd)
                .map(com.trinityforge.stats.ItemStatProfile::offhandRequiresBlocking)
                .orElseGet(() -> itemStats.fallback().map(
                        com.trinityforge.stats.ItemStatProfile::offhandRequiresBlocking).orElse(false));
    }

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
     * 寄与アイテム(mainhandContributor)と、いま実際にオフハンドへ入っているアイテムが「同じ設定」かを
     * Material + CustomModelData のプロファイルで判定する。参照比較・{@code equals} は使わない —
     * Craft実装はスロット読み取りごとに新しいミラーを返すので参照は一致せず、{@code equals} は
     * 「同一設定の別アイテム」まで誤って一致させてしまう(既存注記655-660行と同じ理由)。
     * CMD の読み方は {@link #offhandStatsApply(ItemStack, ItemStatsConfig)} と同じ流儀を踏襲する。
     * 両方 null/AIR なら「除外しない」側(false)に倒す(安全側)。
     */
    private static boolean sameItemProfile(ItemStack a, ItemStack b) {
        boolean aEmpty = a == null || a.getType().isAir();
        boolean bEmpty = b == null || b.getType().isAir();
        if (aEmpty || bEmpty) {
            return false;
        }
        if (a.getType() != b.getType()) {
            return false;
        }
        Integer cmdA = a.hasItemMeta() ? DerivedItemStats.customModelDataOf(a.getItemMeta()) : null;
        Integer cmdB = b.hasItemMeta() ? DerivedItemStats.customModelDataOf(b.getItemMeta()) : null;
        return Objects.equals(cmdA, cmdB);
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
        // blocking を知らない旧シグネチャ(back-compat)。「構えていない」として扱うので、
        // offhand-stats-require-blocking: true のアイテム(盾)は寄与しない。
        // 既定 false の他のオフハンド品の挙動は従来どおり変わらない。
        return equipmentDefenseItemStats(armorContents, mainhand, offhand, false, itemStats, combatDamage);
    }

    /**
     * {@link #equipmentDefenseItemStats(ItemStack[], ItemStack, ItemStack, ItemStatsConfig, CombatDamageConfig)}
     * に「いま盾を構えているか」を渡せる版(2026-08-20 / W-163)。
     *
     * @param blocking {@code Player#isBlocking()}。{@code offhand-stats-require-blocking: true} の
     *                 オフハンド品は、これが true のときだけ寄与する
     */
    public static Map<String, Double> equipmentDefenseItemStats(
            ItemStack[] armorContents, ItemStack mainhand, ItemStack offhand, boolean blocking,
            ItemStatsConfig itemStats, CombatDamageConfig combatDamage) {
        Objects.requireNonNull(itemStats, "itemStats");
        Objects.requireNonNull(combatDamage, "combatDamage");
        Map<String, Double> item = new LinkedHashMap<>();
        Map<String, Map<String, Double>> multipliers = new LinkedHashMap<>();

        if (armorContents != null) {
            for (ItemStack piece : armorContents) {
                if (piece == null || piece.getType().isAir() || socketedOnly(piece, itemStats)) {
                    continue;
                }
                DerivedItemStats.resolve(piece, itemStats, combatDamage.weaponBaseFormula())
                        .forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
                mergeMultipliers(multipliers, DerivedItemStats.resolveMultipliers(piece, itemStats));
            }
        }
        // オフハンドは offhand-stats-apply: true のときのみ(プレイヤー防御と同一ゲート)。
        // offhand-stats-require-blocking: true の品は構えている間だけ(2026-08-20 W-163)。
        // 装着専用(スレッド)はここでも寄与しない。
        if (offhandContributes(offhand, blocking, itemStats) && !socketedOnly(offhand, itemStats)) {
            DerivedItemStats.resolve(offhand, itemStats, combatDamage.weaponBaseFormula())
                    .forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
            mergeMultipliers(multipliers, DerivedItemStats.resolveMultipliers(offhand, itemStats));
        }
        // メインハンドは着用専用防具/装着専用スレッドを手持ちした場合を除き寄与
        // (プレイヤー防御と同一の折込規則)。
        if (mainhand != null && !mainhand.getType().isAir()
                && !excludedFromSlotStats(mainhand, itemStats)) {
            DerivedItemStats.resolve(mainhand, itemStats, combatDamage.weaponBaseFormula())
                    .forEach((key, value) -> item.merge(StatKeys.canonical(key), value, Double::sum));
            mergeMultipliers(multipliers, DerivedItemStats.resolveMultipliers(mainhand, itemStats));
        }

        PlayerCombatAggregate mult = new PlayerCombatAggregate(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), multipliers);
        return new LinkedHashMap<>(mult.applyMultipliers(item));
    }
}
