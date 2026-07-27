package com.trinityforge.skilltree.runtime;

import com.trinityforge.combat.AttackSpeedResolver;
import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.BaseStatsConfig;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.StatCapsConfig;
import com.trinityforge.config.domains.WeaponBaseFormula;
import com.trinityforge.progression.PermanentBuffResolver;
import com.trinityforge.stats.AttributeProjection;
import com.trinityforge.stats.AttributeProjection.AttributeModifierSpec;
import com.trinityforge.stats.DerivedItemStats;
import com.trinityforge.stats.StatVocabulary;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies skill-tree {@code buffs} that map to vanilla Attributes (move_speed, attack_speed, …).
 *
 * <p><b>attack-speed / attack-speed-bonus 分離仕様(2026-07-25、2026-07-26 attack-speed の C→A 降格で
 * 読み出し経路を更新)</b>: この2キーはこのクラスの汎用 {@code attrs} 経路から除外し({@link #ATTACK_SPEED_KEY}/
 * {@link #ATTACK_SPEED_BONUS_KEY}参照)、{@link #applyAttackSpeed} が専用に計算・適用する。
 * {@code AttributeApplier}(item-level)は {@code Attribute.ATTACK_SPEED} へ一切触れなくなったため、
 * プレイヤー単位のこの経路が唯一の適用箇所。{@code attack-speed}(絶対値)はもはや {@link StatVocabulary}
 * にも {@link PlayerCombatAggregate} にも存在しない — メインハンド1点だけの真にアイテム固有ステのため、
 * {@link #applyAttackSpeed} は {@link DerivedItemStats#resolve} でメインハンドを直接解決する
 * ({@code WeaponCoatingListener.itemCoatingChargesBonus} と同じパターン)。{@code attack-speed-bonus}
 * (割合・全ソース横断)は引き続き {@link StatVocabulary} 登録の総合ステで、{@link PlayerCombatAggregate}
 * 経由(item/perk/addon combine)のまま変更なし。
 *
 * <p><b>イベント購読方針</b>: 旧{@code PlayerArmorChangeEvent}(deprecated)・{@code PlayerItemHeldEvent}・
 * {@code PlayerSwapHandItemsEvent}・{@code PlayerDropItemEvent}・{@code InventoryClickEvent} の個別購読を
 * {@link EntityEquipmentChangedEvent}(Paper, 装備追加/ログイン/耐久変化/ディスペンサー装備/拾得/防具変更/
 * 手持ち変更を包括的にカバー)一本へ統合した — 高頻度(耐久変化)なので tick単位でデバウンスする
 * ({@link #debounceApply})。取りこぼし(将来のイベント変更やプラグインAPI経由の変更等)は
 * {@link #reconcileAllOnline} の周期的フィンガープリント照合が安全網として補う。{@link PlayerRespawnEvent}
 * は Paper側の「死亡/リスポーン時にmodifierが持ち越されるか」の挙動が issue により不確定
 * (#11467 open「本来リセットされるべき」 vs #10684 closed「持ち越しが正しい」)なため、明示的に
 * フィンガープリントを破棄して強制再適用する。
 */
public final class PerkAttributeApplier implements Listener {

    private static final String KEY_PREFIX = "perk_attr_";
    private static final String ATTACK_SPEED_KEY = "attack_speed";
    private static final String ATTACK_SPEED_BONUS_KEY = "attack_speed_bonus";

    private final Plugin plugin;
    private final PerkBuffResolver resolver;
    private final NativeAttributeBridge nativeBridge;
    private final PermanentBuffResolver permanentBuffResolver;
    private final BaseStatsConfig baseStats;
    private final PlayerStatAggregator statAggregator;
    private final CombatDamageConfig combatDamage;
    private final ItemStatsConfig itemStatsConfig;
    private final AttributeProjection projection = AttributeProjection.defaults();

    /** tick単位デバウンス(同一tick内の複数装備変化イベントを1回のapplyへまとめる)。 */
    private final Map<UUID, Integer> lastAppliedTick = new ConcurrentHashMap<>();
    /** 周期照合用の装備内容フィンガープリント(参照/equalsではなくcontent-based; PlayerStatAggregator note参照)。 */
    private final Map<UUID, Integer> equipmentFingerprints = new ConcurrentHashMap<>();
    private int reconcileTaskId = -1;

    public PerkAttributeApplier(Plugin plugin, PerkBuffResolver resolver) {
        this(plugin, resolver, null);
    }

    public PerkAttributeApplier(Plugin plugin, PerkBuffResolver resolver,
                                NativeAttributeBridge nativeBridge) {
        this(plugin, resolver, nativeBridge, null);
    }

    public PerkAttributeApplier(Plugin plugin, PerkBuffResolver resolver,
                                NativeAttributeBridge nativeBridge,
                                PermanentBuffResolver permanentBuffResolver) {
        this(plugin, resolver, nativeBridge, permanentBuffResolver, null);
    }

    public PerkAttributeApplier(Plugin plugin, PerkBuffResolver resolver,
                                NativeAttributeBridge nativeBridge,
                                PermanentBuffResolver permanentBuffResolver,
                                BaseStatsConfig baseStats) {
        this(plugin, resolver, nativeBridge, permanentBuffResolver, baseStats, null, null, null);
    }

    /**
     * @param statAggregator optional (may be {@code null}, e.g. existing tests/call sites that predate
     *                       this parameter): supplies {@link PlayerCombatAggregate} for the
     *                       attack-speed-bonus computation ({@link #applyAttackSpeed}). When
     *                       {@code null}, attack-speed/attack-speed-bonus are skipped entirely (no-op) —
     *                       all other attribute buffs are unaffected.
     * @param combatDamage   optional (may be {@code null}): supplies {@code combat/damage.yml}'s
     *                       {@code attack-speed.min-effective} clamp floor,
     *                       {@code attack-speed.reconcile-interval-ticks}, and the weapon use-level base
     *                       formula used to resolve the mainhand's own {@code attack-speed}; defaults
     *                       ({@link AttackSpeedResolver#DEFAULT_LOWER_BOUND}, 10 ticks,
     *                       {@link WeaponBaseFormula#disabled()}) are used when {@code null}.
     */
    public PerkAttributeApplier(Plugin plugin, PerkBuffResolver resolver,
                                NativeAttributeBridge nativeBridge,
                                PermanentBuffResolver permanentBuffResolver,
                                BaseStatsConfig baseStats,
                                PlayerStatAggregator statAggregator,
                                CombatDamageConfig combatDamage) {
        this(plugin, resolver, nativeBridge, permanentBuffResolver, baseStats, statAggregator, combatDamage, null);
    }

    /**
     * @param itemStatsConfig optional (may be {@code null}, e.g. existing tests/call sites that predate
     *                        this parameter): supplies {@code stats/item-stats.yml} so
     *                        {@link #applyAttackSpeed} can resolve the mainhand item's OWN
     *                        {@code attack-speed} directly via {@link DerivedItemStats#resolve} — 2026-07-26
     *                        stat-scope 境界引き直し §2 (C→A 降格): {@code attack-speed} is now a purely
     *                        item-level stat (no longer in {@link StatVocabulary}/{@link PlayerCombatAggregate}),
     *                        so this class must resolve it itself instead of reading
     *                        {@code aggregate.mainhand()} (same pattern as
     *                        {@code WeaponCoatingListener.itemCoatingChargesBonus}). When {@code null},
     *                        the mainhand's own attack-speed is treated as undefined (TF does not
     *                        interfere; attack-speed-bonus is unaffected).
     */
    public PerkAttributeApplier(Plugin plugin, PerkBuffResolver resolver,
                                NativeAttributeBridge nativeBridge,
                                PermanentBuffResolver permanentBuffResolver,
                                BaseStatsConfig baseStats,
                                PlayerStatAggregator statAggregator,
                                CombatDamageConfig combatDamage,
                                ItemStatsConfig itemStatsConfig) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.nativeBridge = nativeBridge;
        this.permanentBuffResolver = permanentBuffResolver;
        this.baseStats = baseStats;
        this.itemStatsConfig = itemStatsConfig;
        this.statAggregator = statAggregator;
        this.combatDamage = combatDamage;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onEquipmentChanged(EntityEquipmentChangedEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            // Player限定(2026-07-25決定5): モブの装備変化(EliteMobs等)には一切反応しない。
            return;
        }
        debounceApply(player);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // Paper側の死亡/リスポーン時modifier持ち越し挙動が issue により不確定なため、フィンガープリントを
        // 強制破棄して次tickで確実に再計算・再適用する(クラスjavadoc参照)。
        equipmentFingerprints.remove(player.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> apply(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        equipmentFingerprints.remove(id);
        lastAppliedTick.remove(id);
    }

    private void debounceApply(Player player) {
        int currentTick = plugin.getServer().getCurrentTick();
        UUID id = player.getUniqueId();
        Integer last = lastAppliedTick.get(id);
        if (last != null && last == currentTick) {
            // 同一tick内の複数イベント(耐久変化の高頻度発火等)を1回のapplyへまとめる。
            return;
        }
        lastAppliedTick.put(id, currentTick);
        apply(player);
    }

    /** Re-apply for one player (join / reload / after perk unlock). */
    public void apply(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        // CMB-03: clearOwnModifiers below removes every TF-owned attribute modifier — including any
        // MAX_HEALTH bonus — before the loop further down re-adds them. Bukkit clamps current health
        // down whenever MAX_HEALTH drops, but never raises it back up when MAX_HEALTH is restored, so
        // this clear→reapply cycle (fired on every equipment change / durability tick via
        // EntityEquipmentChangedEvent) permanently shaves the player's current HP down to the transient
        // clamp floor. Snapshotting health before the clear and restoring it (capped at the final,
        // fully-reapplied MAX_HEALTH) at the end of this method undoes that transient dip while still
        // honouring a genuine MAX_HEALTH reduction (e.g. unequipping a +HP item legitimately clamps).
        double healthBeforeReapply = player.getHealth();
        clearOwnModifiers(player);
        Map<String, Double> attrs = new HashMap<>(
                resolver.buffsFor(player.getUniqueId(), player.getInventory().getItemInMainHand()).attributes());
        if (nativeBridge != null) {
            // max_health/knockback_resistance/attack_reach 統合分は削除済み
            // (stat-gate-overhaul §2 移行B13): buffs: 変換後は resolver.buffsFor(...).attributes() が拾う。
            // 2026-07-27 (armor-set-buffs 全面移行 §7): set-buffs は任意の統計キーを宣言できるようになった
            // (NativeAttributeBridge の戻り値は ATTACK/DEFENSE/GENERAL も混ざりうる)ため、直下の
            // permanentBuffResolver と同じく ATTRIBUTE チャネルのキーだけをここへ合流させる。絞らないと
            // PlayerStatAggregator 側の nativeArmorSetContribution と非属性キーが二重に流れてしまう。
            nativeBridge.armorAttributesFor(player).forEach((k, v) -> {
                if (StatVocabulary.channelOf(k) == StatVocabulary.Channel.ATTRIBUTE) {
                    attrs.merge(k, v, Double::sum);
                }
            });
        }
        if (permanentBuffResolver != null) {
            permanentBuffResolver.buffsFor(player).forEach((k, v) -> {
                if (StatVocabulary.channelOf(k) == StatVocabulary.Channel.ATTRIBUTE) {
                    attrs.merge(k, v, Double::sum);
                }
            });
        }
        if (baseStats != null) {
            // 全プレイヤー一律の基礎ステのうち属性チャネル(max-health 等)のみをここで適用する。
            // 非属性キーは PlayerStatAggregator の item map 経路で扱われる(二重計上しない)。
            baseStats.stats().forEach((k, v) -> {
                if (StatVocabulary.channelOf(k) == StatVocabulary.Channel.ATTRIBUTE) {
                    attrs.merge(k, v, Double::sum);
                }
            });
        }
        // attack_speed / attack_speed_bonus はこの汎用経路から除外し、applyAttackSpeed が専用計算する
        // (二重計上防止; クラスjavadoc参照)。
        attrs.remove(ATTACK_SPEED_KEY);
        attrs.remove(ATTACK_SPEED_BONUS_KEY);
        // 2026-07-27 (旧保留 P7): ATTRIBUTE チャネルのステ上限。ここまでの attrs は
        // perk + native armor-set + 永続buff + base-stats を合算し終えた「最終値」で、この後は
        // バニラ Attribute へ直接書き込むだけ。ATTRIBUTE チャネルは PlayerCombatAggregate#totalOf を
        // 通らないため、**stat-caps.yml の上限がここだけ素通りしていた**。合算の最後＝書き込みの直前
        // というこの1点が、totalOf と対称になる唯一のクランプ点。
        clampToStatCaps(attrs);
        if (!attrs.isEmpty()) {
            for (AttributeModifierSpec spec : projection.project(attrs)) {
                Attribute attribute = resolveAttribute(spec.attributeKey());
                if (attribute == null) {
                    continue;
                }
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance == null) {
                    continue;
                }
                NamespacedKey key = new NamespacedKey(plugin, KEY_PREFIX + spec.statKey());
                AttributeModifier.Operation op = switch (spec.operation()) {
                    case ADD_NUMBER -> AttributeModifier.Operation.ADD_NUMBER;
                    case ADD_SCALAR -> AttributeModifier.Operation.ADD_SCALAR;
                    case MULTIPLY_SCALAR_1 -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;
                };
                instance.addModifier(new AttributeModifier(key, spec.amount(), op, EquipmentSlotGroup.ANY));
            }
        }

        applyAttackSpeed(player);
        restoreHealthAfterReapply(player, healthBeforeReapply);
        equipmentFingerprints.put(player.getUniqueId(), equipmentFingerprint(player));
    }

    /**
     * CMB-03 fix: undoes the transient current-HP clamp caused by {@link #clearOwnModifiers} dropping
     * MAX_HEALTH to its vanilla base before this method's modifier loop re-adds any TF MAX_HEALTH bonus.
     * Restores current health to {@code min(healthBeforeReapply, finalMaxHealth)} — i.e. exactly what it
     * was before this {@code apply()} call, unless the fully-reapplied MAX_HEALTH is now genuinely lower
     * (e.g. a +HP item was actually unequipped), in which case the normal vanilla clamp is preserved.
     * Never heals the player above what they had; never no-ops into raising health.
     */
    private static void restoreHealthAfterReapply(Player player, double healthBeforeReapply) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        double target = Math.max(0.0, Math.min(healthBeforeReapply, maxHealth.getValue()));
        if (player.getHealth() < target) {
            player.setHealth(target);
        }
    }

    /**
     * {@code attack-speed}(絶対値・メインハンド専用)+{@code attack-speed-bonus}(割合・全ソース横断)を
     * プレイヤー単位で計算・適用する(2026-07-25仕様、{@link AttackSpeedResolver}に計算を委譲)。
     *
     * <p>Haste保持のための制約: ここでは {@code instance.getValue()} 等のライブ属性値を一切読まない。
     * TFが把握している値(集計済みaggregate/perk/baseStats等)だけから加算量を決定するので、Hasteのような
     * 他システムが付けた別modifierの寄与を誤って巻き込んで相殺することがない。
     */
    private void applyAttackSpeed(Player player) {
        AttributeInstance instance = player.getAttribute(Attribute.ATTACK_SPEED);
        if (instance == null) {
            return;
        }
        ItemStack mainhandItem = player.getInventory().getItemInMainHand();
        PlayerCombatAggregate aggregate = statAggregator != null
                ? statAggregator.aggregate(player, mainhandItem)
                : null;
        if (aggregate == null) {
            // statAggregator未注入(旧テスト等)の呼び出し元では attack-speed 系は完全no-op。
            return;
        }

        Double authoredMainhand = mainhandAuthoredAttackSpeed(mainhandItem);
        double itemOwnContribution = itemOwnAttackSpeedContribution(mainhandItem);
        double lowerBound = combatDamage != null
                ? combatDamage.attackSpeedMinEffective() : AttackSpeedResolver.DEFAULT_LOWER_BOUND;

        AttackSpeedResolver.AbsoluteResult absolute = AttackSpeedResolver.resolveAbsolute(
                authoredMainhand, itemOwnContribution, AttackSpeedResolver.AUTHORING_FALLBACK);
        if (absolute.authoringWarning()) {
            plugin.getLogger().warning("[attack-speed] " + player.getName()
                    + " のメインハンドattack-speedが0以下のため4.0へフォールバックしました("
                    + mainhandItem.getType() + ")");
        }
        double effectiveBeforeBonus = AttackSpeedResolver.effectiveBeforeBonus(
                authoredMainhand, itemOwnContribution, AttackSpeedResolver.AUTHORING_FALLBACK);

        double bonusFraction = collectAttackSpeedBonus(player, mainhandItem, aggregate);
        double multiplyAmount = AttackSpeedResolver.resolveBonusMultiplyAmount(
                bonusFraction, effectiveBeforeBonus, lowerBound);

        if (absolute.addNumberAmount() != 0.0) {
            instance.addModifier(new AttributeModifier(
                    new NamespacedKey(plugin, KEY_PREFIX + ATTACK_SPEED_KEY),
                    absolute.addNumberAmount(), AttributeModifier.Operation.ADD_NUMBER,
                    EquipmentSlotGroup.ANY));
        }
        if (multiplyAmount != 0.0) {
            instance.addModifier(new AttributeModifier(
                    new NamespacedKey(plugin, KEY_PREFIX + ATTACK_SPEED_BONUS_KEY),
                    multiplyAmount, AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                    EquipmentSlotGroup.ANY));
        }
    }

    /**
     * 合算済みの ATTRIBUTE チャネル値へ {@code combat/stat-caps.yml} の上限を適用する
     * (2026-07-27、旧保留 P7)。上限表が未注入(旧テスト等)なら何もしない＝従来挙動。
     *
     * <p>{@link StatCapsConfig#clamp} は上限が定義されていないキーをそのまま返すので、
     * 「上限を設定したステだけが縛られる」という stat-caps.yml の既定(=上限なし)は保たれる。
     */
    private void clampToStatCaps(Map<String, Double> attrs) {
        StatCapsConfig caps = statAggregator == null ? null : statAggregator.statCaps();
        if (caps == null || attrs.isEmpty()) {
            return;
        }
        attrs.replaceAll(caps::clamp);
    }

    /**
     * {@code attack-speed-bonus} の全ソース合算(2026-07-25 othersAS監査、各ソース厳密に1回のみ):
     * {@link PlayerCombatAggregate#item()}(防具4部位+オフハンド+メインハンド自身+ロールbuff+永続buff+
     * base-stats、いずれもunfiltered経由でここに含まれる) + {@link PlayerCombatAggregate#addon()} +
     * {@link PerkBuffs#attributes()}(スキルツリーperkのATTRIBUTEチャネル分、item map未収録) +
     * {@link NativeAttributeBridge}(native armor-set bonus、item map未収録)。
     */
    private double collectAttackSpeedBonus(Player player, ItemStack mainhandItem,
                                           PlayerCombatAggregate aggregate) {
        double total = aggregate.item().getOrDefault(ATTACK_SPEED_BONUS_KEY, 0.0)
                + aggregate.addon().getOrDefault(ATTACK_SPEED_BONUS_KEY, 0.0);
        PerkBuffs perkBuffs = resolver.buffsFor(player.getUniqueId(), mainhandItem);
        total += perkBuffs.attributes().getOrDefault(ATTACK_SPEED_BONUS_KEY, 0.0);
        if (nativeBridge != null) {
            total += nativeBridge.armorAttributesFor(player).getOrDefault(ATTACK_SPEED_BONUS_KEY, 0.0);
        }
        // attack-speed-bonus は上の汎用経路(clampToStatCaps)から意図的に外してあるので、
        // 上限はこの専用合算の出口で掛ける。
        StatCapsConfig caps = statAggregator == null ? null : statAggregator.statCaps();
        return caps == null ? total : caps.clamp(ATTACK_SPEED_BONUS_KEY, total);
    }

    /**
     * メインハンド1点だけの {@code attack-speed}(絶対値)を、{@link StatVocabulary}/
     * {@link PlayerCombatAggregate} を経由せず {@link DerivedItemStats#resolve} で直接解決する
     * (2026-07-26 stat-scope 境界引き直し §2 C→A 降格: {@code WeaponCoatingListener
     * .itemCoatingChargesBonus} と同じ「メインハンド武器1点だけの派生ステを直接読む」パターン)。
     * {@code itemStatsConfig}/{@code combatDamage} が未注入(旧テスト等)、またはアイテムが null/air の
     * ときは {@code null}(未定義)を返し、{@link AttackSpeedResolver#resolveAbsolute} 側の
     * 「TFは干渉しない」既定挙動へフォールバックする。
     */
    private Double mainhandAuthoredAttackSpeed(ItemStack mainhandItem) {
        if (itemStatsConfig == null || mainhandItem == null || mainhandItem.getType().isAir()) {
            return null;
        }
        WeaponBaseFormula formula = combatDamage != null
                ? combatDamage.weaponBaseFormula() : WeaponBaseFormula.disabled();
        Map<String, Double> derived = DerivedItemStats.resolve(mainhandItem, itemStatsConfig, formula);
        return derived.get(ATTACK_SPEED_KEY);
    }

    /**
     * メインハンドのattack-speedが未定義のとき、TFが「一切干渉しない」方針(2026-07-25決定b)を実現する
     * ために補填が必要な、アイテム自身のバニラ材質既定ATTACK_SPEED量。1.20.5以降、ItemMetaに
     * <em>いずれかの</em>明示的attribute_modifiersが1つでも付くと暗黙のmaterial defaultsはitem丸ごと
     * 抑制される仕様のため、TFの他ステ({@code AttributeApplier}が管理するattack-power等)がこのアイテムに
     * 一切無く暗黙適用のままなら(=explicit componentが無いなら)、Bukkit側が既に自然に適用済みなので
     * ここでは何も足さない(二重計上防止)。explicit componentが付いている場合のみ、失われた
     * ATTACK_SPEED材質既定分を明示的に計算し直して補填する。
     */
    private static double itemOwnAttackSpeedContribution(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0.0;
        }
        Material material = item.getType();
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        boolean hasExplicitComponent = meta != null && meta.hasAttributeModifiers();
        if (!hasExplicitComponent) {
            return 0.0;
        }
        double sum = 0.0;
        for (AttributeModifier modifier :
                material.getDefaultAttributeModifiers(EquipmentSlot.HAND).get(Attribute.ATTACK_SPEED)) {
            sum += modifier.getAmount();
        }
        return sum;
    }

    public void applyAllOnline() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            apply(player);
        }
    }

    /**
     * 周期的フィンガープリント照合(安全網): イベント取りこぼしに備え、装備content-based fingerprint
     * (参照/equalsではなくhashCode、PlayerStatAggregatorの既存注意書きと同じ理由)が前回applyから
     * 変化しているプレイヤーだけ再適用する。トレードオフ: 毎周期ごとにオンライン全員のhashCodeを取るのは
     * O(オンライン人数)の軽量比較のみ(実際のapply処理はfingerprintが変化した場合のみ発火)なので、
     * 既定10tick(0.5秒)間隔なら常時コストは無視できるレベル。取りこぼし時の最大検知遅延は1周期分。
     */
    public void startPeriodicReconciliation() {
        if (reconcileTaskId != -1) {
            return;
        }
        int intervalTicks = combatDamage != null
                ? Math.max(1, combatDamage.attackSpeedReconcileIntervalTicks()) : 10;
        reconcileTaskId = plugin.getServer().getScheduler()
                .scheduleSyncRepeatingTask(plugin, this::reconcileAllOnline, intervalTicks, intervalTicks);
    }

    private void reconcileAllOnline() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            int current = equipmentFingerprint(player);
            Integer previous = equipmentFingerprints.get(player.getUniqueId());
            if (previous == null || previous != current) {
                apply(player);
            }
        }
    }

    /** Content-basedフィンガープリント(参照/equals比較の既知の落とし穴を避ける; PlayerStatAggregator参照)。 */
    private static int equipmentFingerprint(Player player) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        return Objects.hash(
                armor.length > 0 ? armor[0] : null,
                armor.length > 1 ? armor[1] : null,
                armor.length > 2 ? armor[2] : null,
                armor.length > 3 ? armor[3] : null,
                player.getInventory().getItemInMainHand(),
                player.getInventory().getItemInOffHand());
    }

    private void clearOwnModifiers(Player player) {
        for (Attribute attribute : Registry.ATTRIBUTE) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            for (AttributeModifier modifier : List.copyOf(instance.getModifiers())) {
                NamespacedKey key = modifier.getKey();
                if (key.getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT))
                        && key.getKey().startsWith(KEY_PREFIX)) {
                    instance.removeModifier(modifier);
                }
            }
        }
    }

    private static Attribute resolveAttribute(String attributeKey) {
        NamespacedKey key = NamespacedKey.minecraft(attributeKey.toLowerCase(Locale.ROOT));
        return Registry.ATTRIBUTE.get(key);
    }
}
