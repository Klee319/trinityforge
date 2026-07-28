package com.trinityforge.listeners;

import com.trinityforge.integration.TrainingDummies;
import com.trinityforge.integration.ars.ArsProgressionBridge;
import com.trinityforge.combat.AddonCombatStats;
import com.trinityforge.combat.AttackStatBridge;
import com.trinityforge.combat.EnchantmentStatBridge;
import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.BleedService;
import com.trinityforge.combat.CombatHitResult;
import com.trinityforge.combat.CritFlash;
import com.trinityforge.combat.MaceSmashDamage;
import com.trinityforge.combat.MeleeChargeMultiplier;
import com.trinityforge.combat.MeleeChargeTracker;
import com.trinityforge.combat.PvpDamagePolicy;
import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.ProjectileWeapon;
import com.trinityforge.combat.ReflectDamageBridge;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.MobLevelTableConfig;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.config.domains.UseRequirementsConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.MobData;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.progression.UseRequirementPolicy;
import com.trinityforge.progression.UseRequirementResolver;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.stats.DerivedItemStats;
import com.trinityforge.stats.StatKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import com.trinityforge.TrinityForge;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * M1 physical-component path: routes player melee and player-shot projectiles through the shared
 * {@link SymmetricCombatService} using live config, so editing {@code combat/damage.yml} +
 * {@code /trinityforge reload} changes outgoing damage with no code change (IMPLEMENTATION_PLAN
 * section 3). The magical component shares the same service through its M2 reception port, so
 * both stay byte-for-byte symmetric.
 *
 * <p>Runs on the Bukkit main thread (damage events are synchronous), so the per-event reads need no
 * synchronization.
 */
public final class CombatListener implements Listener {

    // TODO(M2+): EntityDamageEvent.DamageModifier is deprecated in Paper 1.21; migrate the
    // mitigation folding to the new DamageSource / final-damage API before it is removed.
    /** Cached once: Enum.values() copies its backing array on every call. */
    @SuppressWarnings("deprecation")
    private static final EntityDamageEvent.DamageModifier[] DAMAGE_MODIFIERS =
            EntityDamageEvent.DamageModifier.values();

    /**
     * Only these three vanilla modifiers have a counterpart re-derived inside the symmetric pipeline
     * (COMBAT_SYSTEM_SPEC section 5), so they are zeroed here to avoid double mitigation: {@code ARMOR}
     * is re-derived from the victim's armor/toughness attributes ({@code SymmetricCombatService
     * #vanillaArmorDefense}), {@code RESISTANCE} is re-derived from the victim's potion effect as
     * 被ダメージ軽減% ({@code SymmetricCombatService#potionResistanceReduction}), and {@code MAGIC}
     * (vanilla's armor-enchant reduction — Protection/Projectile Protection) is re-derived by
     * {@link com.trinityforge.combat.DefenseEnchantmentBridge} via {@code SymmetricCombatService
     * #vanillaProtectionDefense}. All three MUST be re-injected downstream — zeroing a modifier with no
     * pipeline re-derivation silently deletes the effect (B1), and leaving {@code MAGIC} un-zeroed while
     * {@code BASE} is overwritten by the single-argument {@code setDamage(BASE, total)} below is WORSE
     * than deleting it: Paper does not recompute {@code MAGIC} from the new base (only the
     * double-argument {@code setDamage(double)} does), so a vanilla-scale MAGIC subtraction computed
     * against the tiny original vanilla damage gets applied to TF's much larger final damage instead —
     * this is exactly the 2026-07-25 bug where Protection IV's ~64% mitigation collapsed to ~1.8%
     * (課題1, confirmed by reading this class + {@code PlayerDefenseResolver} together before the fix).
     * Every other modifier (shield {@code BLOCKING}, {@code ABSORPTION}, {@code HARD_HAT},
     * {@code FREEZING}, {@code INVULNERABILITY_REDUCTION}, ...) has no pipeline equivalent and must
     * survive untouched, or shield blocking and absorption hearts silently stop working.
     */
    @SuppressWarnings("deprecation")
    private static final Set<EntityDamageEvent.DamageModifier> FOLDED_MODIFIERS = EnumSet.of(
            EntityDamageEvent.DamageModifier.ARMOR,
            EntityDamageEvent.DamageModifier.RESISTANCE,
            EntityDamageEvent.DamageModifier.MAGIC);

    private static final Set<EntityDamageEvent.DamageCause> MELEE_CAUSES = EnumSet.of(
            EntityDamageEvent.DamageCause.ENTITY_ATTACK,
            EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK);

    private final SymmetricCombatService combatService;
    private final ItemStatsConfig itemStats;
    private final CombatDamageConfig damageConfig;
    private final SkillLevelSource skillLevelSource;
    private final BleedService bleedService;
    private final PerkBuffResolver perkBuffResolver;
    private final PlayerStatAggregator aggregator;
    private final UseRequirementsConfig useRequirements;
    private final SkillExpConfig skillExp;
    private final CraftingFeaturesConfig craftingFeatures;
    private final RoleBuffResolver roleBuffResolver;
    private final Plugin plugin;
    /**
     * {@code combat/mob-level-table.yml} の {@code no-skill-exp-mobs}(2026-07-27 牧場対策)。
     * null許容 — 未配線(旧12引数コンストラクタ経由、既存テスト互換)なら武器スキルEXP抑止は無効。
     */
    private final MobLevelTableConfig mobLevelTable;
    /** Optional live catalog used for the Valhalla-compatible ARCHERY action formula. */
    private final NativeSkillCatalog progressionCatalog;
    private final CombatKillCreditTracker combatKillCredits = new CombatKillCreditTracker();

    /**
     * #2 AoE の再入ガード。AoEスプラッシュの {@code target.damage()} が同ハンドラを同期再入した際に true で
     * 早期returnさせ、AoEの連鎖と主命中再計算の上書きを防ぐ。combat は常にメインスレッド同期実行なので
     * volatile も同期も不要(単純フィールドで十分)。
     */
    private boolean applyingAoe = false;

    /**
     * 課題2 (2026-07-25) の再入ガード。反射ダメージ({@link #onReflectDamage})が {@code attacker.damage(...)}
     * を呼ぶと、その攻撃者自身も反射ステータス持ちなら同ハンドラが同期再入しうる(PvP等)。true の間は早期
     * returnし、反射→反射の無限連鎖を1ホップで断ち切る。{@link #applyingAoe} と同じ理由で単純フィールドで十分。
     */
    private boolean reflecting = false;

    /**
     * B2 レビュー修正(HIGH指摘1): {@code Player#getAttackCooldown()} に依存しない自前チャージトラッカー
     * (詳細は {@link MeleeChargeMultiplier} javadoc)。{@link #onPlayerQuit} でログアウト時に破棄する。
     */
    private final MeleeChargeTracker meleeChargeTracker = new MeleeChargeTracker();

    /** 後方互換コンストラクタ(既存呼び出し/テスト向け)。{@code no-skill-exp-mobs} 抑止は無効(null)。 */
    public CombatListener(Plugin plugin, SymmetricCombatService combatService,
                          ItemStatsConfig itemStats, CombatDamageConfig damageConfig,
                          SkillLevelSource skillLevelSource, BleedService bleedService,
                          PerkBuffResolver perkBuffResolver, PlayerStatAggregator aggregator,
                          UseRequirementsConfig useRequirements, SkillExpConfig skillExp,
                          CraftingFeaturesConfig craftingFeatures, RoleBuffResolver roleBuffResolver) {
        this(plugin, combatService, itemStats, damageConfig, skillLevelSource, bleedService, perkBuffResolver,
                aggregator, useRequirements, skillExp, craftingFeatures, roleBuffResolver, null, null);
    }

    /**
     * @param mobLevelTable {@code combat/mob-level-table.yml} の {@code no-skill-exp-mobs}(2026-07-27
     *                      牧場対策)。武器スキルEXP付与時に victim の EntityType がここに載っていれば
     *                      付与しない。null可(その場合は抑止しない、旧挙動)。
     */
    public CombatListener(Plugin plugin, SymmetricCombatService combatService,
                          ItemStatsConfig itemStats, CombatDamageConfig damageConfig,
                          SkillLevelSource skillLevelSource, BleedService bleedService,
                          PerkBuffResolver perkBuffResolver, PlayerStatAggregator aggregator,
                          UseRequirementsConfig useRequirements, SkillExpConfig skillExp,
                          CraftingFeaturesConfig craftingFeatures, RoleBuffResolver roleBuffResolver,
                          MobLevelTableConfig mobLevelTable) {
        this(plugin, combatService, itemStats, damageConfig, skillLevelSource, bleedService, perkBuffResolver,
                aggregator, useRequirements, skillExp, craftingFeatures, roleBuffResolver, mobLevelTable, null);
    }

    public CombatListener(Plugin plugin, SymmetricCombatService combatService,
                          ItemStatsConfig itemStats, CombatDamageConfig damageConfig,
                          SkillLevelSource skillLevelSource, BleedService bleedService,
                          PerkBuffResolver perkBuffResolver, PlayerStatAggregator aggregator,
                          UseRequirementsConfig useRequirements, SkillExpConfig skillExp,
                          CraftingFeaturesConfig craftingFeatures, RoleBuffResolver roleBuffResolver,
                          MobLevelTableConfig mobLevelTable, NativeSkillCatalog progressionCatalog) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combatService = Objects.requireNonNull(combatService, "combatService");
        this.itemStats = Objects.requireNonNull(itemStats, "itemStats");
        this.damageConfig = Objects.requireNonNull(damageConfig, "damageConfig");
        this.skillLevelSource = Objects.requireNonNull(skillLevelSource, "skillLevelSource");
        this.bleedService = Objects.requireNonNull(bleedService, "bleedService");
        this.perkBuffResolver = Objects.requireNonNull(perkBuffResolver, "perkBuffResolver");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.useRequirements = Objects.requireNonNull(useRequirements, "useRequirements");
        this.skillExp = Objects.requireNonNull(skillExp, "skillExp");
        this.craftingFeatures = Objects.requireNonNull(craftingFeatures, "craftingFeatures");
        this.roleBuffResolver = Objects.requireNonNull(roleBuffResolver, "roleBuffResolver");
        this.mobLevelTable = mobLevelTable;
        this.progressionCatalog = progressionCatalog;
    }

    @SuppressWarnings("deprecation") // DamageModifier folding; see DAMAGE_MODIFIERS TODO (M2+).
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // #2 AoE 再入ガード: AoEスプラッシュが起こす二次被弾はTFパイプラインを通さずバニラにそのまま委ねる
        // (各対象自身のバニラ防具で軽減)。これにより「AoEのAoE」の無限連鎖と、主命中ロジックによる
        // スプラッシュ量の再計算上書きの両方を防ぐ。combat はメインスレッド同期なので単純booleanで十分。
        if (applyingAoe) {
            return;
        }
        if (event.getEntity() instanceof Player victim) {
            LivingEntity mobAttacker = resolveMobAttacker(event);
            if (mobAttacker != null) {
                handleMobToPlayerDamage(event, mobAttacker, victim);
                return;
            }
        }
        Player attacker = resolveAttacker(event);
        if (attacker == null) {
            return;
        }

        // Use-level gate (PROGRESSION 1.6, I3): an under-leveled player cannot use the weapon, so the
        // melee hit is cancelled outright. Only the mainhand melee weapon is gated here; a bow/crossbow
        // use requirement is gated at shoot time (onEntityShootBow) and a trident at throw time
        // (onProjectileLaunch), so the same requirement applies to every weapon class.
        if (meleeWeaponUseBlocked(attacker, event)) {
            event.setCancelled(true);
            return;
        }

        // Physical item-cooldown gate (アイテムCT): like an ender pearl's cooldown, a CT weapon on cooldown is
        // 使用不可 — the primary melee hit is cancelled (no damage, no knockback) while the material greys out
        // in the hotbar via setCooldown. The gate only blocks when the mainhand item ACTUALLY owns a
        // item-cooldown stat (vanilla item-use cooldowns on the same material — ender pearl, chorus fruit,
        // goat horn, an axe-disabled shield — must never kill melee). Primary hit only (see ITEM_CT_CAUSES).
        if (meleeWeaponOnCooldown(attacker, event)) {
            event.setCancelled(true);
            return;
        }

        double vanillaBaseDamage = event.getDamage();
        if (vanillaBaseDamage <= 0) {
            return;
        }

        // Bridge the attack weapon's PDC (roll_seed + quality) into AttackStats via the configured
        // attack-stat-keys mapping (COMBAT_SYSTEM_SPEC 3.1); a PDC-less item/bare hand stays at
        // AttackStats.plain(0), matching the pre-existing vanilla-baseline behaviour. For a projectile
        // the weapon is the one that FIRED it (stored on the projectile at launch), not the mainhand at
        // impact, so crit/penetration/attack-power stay attached to the firing bow/crossbow/trident even
        // if the shooter swaps hands mid-flight (High bug). The service injects the level-scaled default
        // damage and resolves the defender (PDC profile, vanilla armor mapping, or config default, COMBAT 5/6).
        // #3 全ステ合算: 防具4部位 + メインハンド(または発射武器) + (設定により)オフハンド + パーク +
        // アドオンを PlayerStatAggregator で一括集計する。攻撃武器の寄与は近接ならメインハンド、飛び道具
        // なら発射時に retain された武器そのもの(High bug: 着弾時のメインハンドではない)。
        ItemStack mainhandContributor = resolveMainhandContributor(event, attacker);
        PlayerCombatAggregate agg = aggregator.aggregate(attacker, mainhandContributor);
        // LD-9 skill-tree perk addend + アドオン契約は従来通り: item(防具+武器(+offhand)) → perk →
        // addon の順に加算し、攻撃ブリッジ/出血判定へ渡す。attack-power は下で個別に扱う(ベース置換規則)。
        Map<String, Double> itemAndPerk = mergeStats(agg.item(), agg.perkAttack());
        Map<String, Double> attackerStats = mergeStats(itemAndPerk, agg.addon());
        double coatingBonus = WeaponCoatingListener.coatingFlatBonus(mainhandContributor, craftingFeatures);
        if (coatingBonus > 0) {
            attackerStats.merge(StatKeys.canonical("flat-bonus-damage"), coatingBonus, Double::sum);
        }
        Entity victim = event.getEntity();
        LivingEntity livingVictim = victim instanceof LivingEntity le ? le : null;
        EnchantmentStatBridge.Bonuses enchantBonuses =
                EnchantmentStatBridge.bonuses(mainhandContributor, livingVictim);
        // Breach(メイス): Lv×10% を TF貫通率へ加算。バニラのARMOR modifierはTFが常時0化して再導出する
        // ため、バニラ側のBreach効果は消えている — TFステとして一度だけ適用する。
        if (enchantBonuses.penetrationBonus() > 0) {
            attackerStats.merge(StatKeys.canonical("penetration"), enchantBonuses.penetrationBonus(), Double::sum);
        }
        // 乗算モード: 加算合算が終わった攻撃側総合値へ乗算レイヤを適用(合算→乗算の契約)。
        attackerStats = agg.applyMultipliers(attackerStats);
        // 2026-07-26 stat-cap カバレッジ拡大: ATTACK チャネルの唯一の出口。装備+パーク+アドオンの
        // 加算と乗算レイヤ適用が終わった直後のこの時点で、attackerStats に載っている全キー
        // (crit-chance/crit-damage/flat-bonus-damage/percent-bonus-damage/penetration/
        // damage-modifier/fixed-damage/bleed-chance/bleed-damage 等)へ上限を適用する。attackerStats
        // は下で weaponStats(AttackStatBridge.bridge)と maybeApplyBleed の両方の入力になるため、
        // ここでクランプすれば両方に自動的に反映される。attack-power はこのマップから消費されない
        // (下の baseDamage 算出部で item/perk/addon を個別に読み直す専用ロジックのため、そちらで別途
        // クランプする)。
        for (Map.Entry<String, Double> statEntry : attackerStats.entrySet()) {
            statEntry.setValue(agg.clamp(statEntry.getKey(), statEntry.getValue()));
        }
        AttackStats weaponStats = AttackStatBridge.bridge(attackerStats, damageConfig.attackStatKeys());
        // vanilla base damage as the pipeline's physical base (the primary balance knob is TF's own stat,
        // typically set per item in stats/item-stats.yml). A weapon with no attack-power (the common case
        // today) keeps the vanilla base, so existing behaviour is unchanged. flat/percent-bonus-damage
        // are folded in later by the pipeline and must NOT be added here (no double counting). Perk
        // attack-power is a flat additive bonus on top of the chosen base (never a replacement), so a bare
        // hand keeps its vanilla base and simply gains the perk amount.
        // #3 により itemAttackPower は now 防具+武器(+offhand)の合算(意図した挙動)。
        String attackPowerKey = StatKeys.canonical("attack-power");
        // 乗算モード: 攻撃力の各addendへ同一倍率を掛ける(m*(a+b+c) = m*a+m*b+m*c なので総合値へ掛けるのと等価)。
        // ベース置換は値の正負ではなく「アイテムにattack-powerが定義されているか」で決める。
        // 明示0/負値もギャンブル装備の正式値であり、バニラ攻撃力へフォールバックさせない。
        double attackPowerMultiplier = agg.multiplierFor(attackPowerKey);
        double rawItemAttackPower = agg.item().getOrDefault(attackPowerKey, 0.0);
        double itemAttackPower = rawItemAttackPower * attackPowerMultiplier;
        double perkAttackPower = agg.perkAttack().getOrDefault(attackPowerKey, 0.0) * attackPowerMultiplier;
        double addonAttackPower = agg.addon().getOrDefault(attackPowerKey, 0.0) * attackPowerMultiplier;
        boolean tfBaseReplaces = agg.item().containsKey(attackPowerKey);
        // #4 2026-07-25バグ修正: バニラのメイススマッシュ攻撃は「攻撃クールダウンの進行状況にかかわらず」
        // 発動する(一次情報: minecraft.wiki "Mace", MaceSmashDamage javadoc参照)。下の
        // MaceSmashDamage.isSmashAttack() 判定結果をここで保持し、下方の近接チャージ減衰(B2)の
        // 適用可否に再利用する — 新しい判定条件は発明しない(2箇所に分かれた判定は食い違いの元)。
        boolean maceSmashActive = false;
        double baseDamage;
        if (tfBaseReplaces) {
            double adjustedWeapon = EnchantmentStatBridge.adjustedAttackPower(itemAttackPower, enchantBonuses);
            // #3 2026-07-25バグ修正: BOW/CROSSBOWはattack-power置換により引き絞り量(0.0〜1.0)スケールが
            // 消え、軽く引いても最大まで引いても同ダメージになっていた。onEntityShootBowでprojectileへ
            // retainしたEntityShootBowEvent#getForce()を掛け戻す — クロスボウは常時1.0(Paper PR#12308
            // 修正後、当プラグイン対象の1.21.11では正しい値)なので実質no-op、トライデントはキー自体を
            // 書かないので既定1.0(バニラに引き絞りスケールが無いことと整合、何も失われていない)。
            if (event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE
                    && event.getDamager() instanceof Projectile firedProjectile) {
                double drawForce = ProjectileWeapon.readDrawForce(firedProjectile);
                adjustedWeapon *= drawForce;
            }
            // 2026-07-26 stat-cap カバレッジ拡大: attack-power チャネルの唯一の出口(このtfBaseReplaces
            // 分岐)。item(enchant倍率込み)+perk+addon を合成した直後、この値がbaseDamageへ入る前に
            // クランプする。enchant倍率(Breach以外の一般エンチャ%)をitem側にだけ乗せた"後"の値を
            // 対象とする判断根拠: このクラス内で他の全チャネル(power-attack-damage等)は
            // agg.totalOf(key) = 「item+perk+addon合算 × 乗算レイヤ」を最終値として上限を掛けている。
            // attack-power だけは agg.multiplierFor で乗算レイヤ適用後にitem側だけ追加でenchant%が
            // 掛かる特殊系のため、"実際にbaseDamageへ寄与する最終値"をここで一度だけ捉えてクランプする
            // (mace のスマッシュ加算/Densityはこの直後に別途 baseDamage += されるが、それらはattack-power
            // ステとは無関係な独立したバニラ加算系統であり対象外)。
            baseDamage = agg.clamp(attackPowerKey, adjustedWeapon + perkAttackPower + addonAttackPower);
            // #1/#2 2026-07-25バグ修正: メイスのスマッシュ攻撃(落下攻撃)によるバニラの階層加算、および
            // Density(重撃)エンチャントの加算が、attack-power置換でvanillaBaseDamageごと丸ごと捨てられて
            // いた。ENTITY_ATTACK(主命中。ENTITY_SWEEP_ATTACKはメイスの通常スイングに伴わないため対象外)
            // かつ mainhandContributor が MACE、かつバニラのスマッシュ発動条件を満たすときだけ、
            // TF独自「パワーアタック」(power-attack-damage、非接地時の乗算ボーナス)とは別に上乗せする
            // — バニラの階層式とDensityは元々バニラでも独立した別項として単純に和算されるため二重計上
            // ではない(MaceSmashDamage javadoc参照)。
            if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && mainhandContributor.getType() == Material.MACE) {
                double fallDistance = attacker.getFallDistance();
                boolean smash = MaceSmashDamage.isSmashAttack(fallDistance, attacker.isOnGround(),
                        attacker.isGliding(), attacker.hasPotionEffect(PotionEffectType.SLOW_FALLING));
                if (smash) {
                    baseDamage += MaceSmashDamage.smashTierBonus(fallDistance);
                    baseDamage += MaceSmashDamage.densityBonus(fallDistance, enchantBonuses.densityLevel());
                    maceSmashActive = true;
                }
            }
        } else {
            // 2026-07-26 stat-cap カバレッジ拡大: attack-power チャネルの唯一の出口(この分岐)。
            // itemAttackPower は tfBaseReplaces=false のとき常に0(agg.item()にattack-powerキーが
            // 無いことの定義そのもの)なので perk+addon の合成値だけをクランプ対象にする。vanillaBaseDamage
            // はTFステでは無い(バニラ武器ダメージそのもの)ため上限の対象外——ここでクランプすると
            // TFステを一切持たない武器のバニラダメージまで上限に晒してしまい、範囲外の変更になる。
            baseDamage = vanillaBaseDamage + agg.clamp(attackPowerKey, perkAttackPower + addonAttackPower);
        }
        // 2026-07-25バグ修正: Sweeping Edge(範囲ダメージ増加)がTF独自ダメージ式で反映されていなかった。
        // tfBaseReplaces=false のとき baseDamage は vanillaBaseDamage(=event.getDamage())そのもの——
        // バニラがこのsweepイベント自体を既にsweep減衰式(Sweeping Edgeレベル込み)で計算済みの値なので、
        // ここでは何もしない(二重適用防止、tfBaseReplacesの他の分岐と同じ理由)。tfBaseReplaces=true の
        // ときだけ、TFが主命中と全く同額のフルダメージをそのままsweepにも使ってしまっていた(=Sweeping
        // Edgeのレベルが完全に無視される既存バグ)ため、バニラのsweep公式で明示的に置き換える。
        if (tfBaseReplaces && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            int sweepingLevel = EnchantmentStatBridge.sweepingEdgeLevel(mainhandContributor);
            baseDamage = EnchantmentStatBridge.sweepDamage(baseDamage, sweepingLevel);
        }
        // 課題1: Projectile Protection is only bridged when this hit's cause is PROJECTILE (DefenseEnchantmentBridge),
        // mirroring vanilla — a melee/sweep hit only ever gets the general Protection bridge.
        boolean projectileHit = event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE;
        // CMB-02 (課題3, 2026-07-25): the EliteMobs fork's TrinityForgeCombatListener already ran this
        // exact hit through TrinityForge's OWN pipeline (defense/dodge/crit/penetration + combat-level
        // scale) via the synchronous EliteMobDamagedByPlayerEvent it fires from its NORMAL-priority raw
        // handler — which completes BEFORE this HIGH-priority handler gets a turn on the same raw event.
        // Recomputing here would double-apply the elite's defense/dodge/crit/penetration and tack on an
        // extra combat-level scale, making every elite roughly twice as tanky as designed. When marked,
        // adopt the fork-priced event.getDamage() as-is instead of re-deriving it — bleed/AoE/combat-EXP
        // below still run off this total (they are attacker-gear systems, not victim-defense, and must
        // keep working against elites). See EliteCombatDelegation's javadoc for the full analysis.
        CombatHitResult hit = com.trinityforge.combat.EliteCombatDelegation.isActive()
                ? CombatHitResult.of(event.getDamage(), false)
                : combatService.physicalFinalDamageResult(
                        attacker.getUniqueId(), victim, baseDamage, weaponStats, projectileHit);
        double total = hit.damage();
        // B2: バニラのチャージ攻撃減衰(連打ペナルティ)。近接(ENTITY_ATTACK/ENTITY_SWEEP_ATTACK)の
        // プレイヤー攻撃のみに適用し、弓/クロスボウ/トライデントの遠隔攻撃・魔法・モブ攻撃には適用しない。
        // TFの8段階パイプライン全体を通過した最終合計(fixed-damage貫通後の値)へ後乗算することで、
        // fixed-damage武器だけ連打が最適化してしまう抜け道も塞ぐ(詳細は MeleeChargeMultiplier javadoc)。
        //
        // レビュー修正(HIGH指摘2, 二重減衰): tfBaseReplaces=false のとき baseDamage は
        // vanillaBaseDamage(=event.getDamage())そのもの——バニラが Player#attack() 内で既にチャージ
        // 減衰を掛けた"後"の値なので、ここでTFの減衰をもう一度掛けると二重になる(attack-power未定義
        // アイテム=素手/CMD無し斧等)。tfBaseReplaces=true のときだけ TF算出値(バニラ非依存)なので適用する
        // ——この判定はSweeping Edge再導入(上のENTITY_SWEEP_ATTACK分岐)と同じ基準を再利用。
        // (2026-07-25: 旧 Sharpness 二重計上ガード zeroEnchantModifierIfPresent は、このPaperバージョンの
        // DamageModifier enumに ENCHANTMENTS が存在せず恒久的にno-opだったため削除済み。Sharpness等の
        // 二重計上防止は EnchantmentStatBridge.adjustedAttackPower 側で「tfBaseReplacesのときだけ
        // vanillaBaseDamageを使わずTF算出値に置き換える」という設計そのものが担っている)。
        //
        // チャージ記録自体(recordAttack)は tfBaseReplaces に関わらず毎回更新する: 武器を切り替えても
        // 「最後に攻撃した瞬間」の継続性を保つため(トグル/武器種を跨いだチャージ状態の一貫性)。
        //
        // #4 2026-07-25バグ修正: バニラのメイススマッシュ攻撃(落下攻撃)は攻撃クールダウンの進行状況に
        // 関わらず発動する仕様のため、maceSmashActive(上のMaceSmashDamage.isSmashAttack()判定の結果)が
        // trueのときはこの減衰を適用しない。メイスの通常攻撃(スマッシュ不成立)には従来どおり適用する。
        if (MELEE_CAUSES.contains(event.getCause())) {
            if (tfBaseReplaces && !maceSmashActive) {
                total *= meleeChargeMultiplier(attacker);
            }
            meleeChargeTracker.recordAttack(attacker.getUniqueId(), Bukkit.getCurrentTick());
        }
        double powerAttackRadius = 0.0;
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            total = powerAttackDamage(total, agg.totalOf(POWER_ATTACK_DAMAGE_KEY), !attacker.isOnGround());
            if (!attacker.isOnGround()) {
                powerAttackRadius = Math.max(0.0, agg.totalOf(POWER_ATTACK_RADIUS_KEY));
            }
        }
        // 2026-07-26 バグ修正: 弓術の距離ダメージ(distance-damage-bonus)をここへ移設。
        // 旧実装は NativeCombatPerkListener が event.setDamage(getDamage() * 係数) を掛けていたが、
        // 両者とも EventPriority.HIGH で、登録順(TrinityForge.java 378行 → 423行)により
        // NativeCombatPerkListener が先に走る。tfBaseReplaces=true(= attack-power を持つアイテム)のとき
        // このリスナーは vanillaBaseDamage を捨ててTF算出値で作り直し setDamage(BASE, total) で
        // 上書きするため、距離ボーナスは毎回消えていた。BOW は attack-power:69、CROSSBOW は 270.5 を
        // 持つので常に該当し、archery.yml のα路線が丸ごと無効だった。
        // power-attack-damage と同じ「total 算出後に一度だけ掛ける」位置に統一する。
        if (projectileHit) {
            total = distanceDamage(total, agg.totalOf(DISTANCE_DAMAGE_BONUS_KEY),
                    attacker.getLocation().distance(victim.getLocation()));
        }
        // 2026-07-27 PvP抑制: モブ向けに調整された値がそのまま player→player に乗っていたため、
        // Lv100帯(攻撃力 約1052)対 プレイヤー最大体力 約33 で「先に当てた方が確定で即死」だった。
        // 位置は「total を出し切った後・setDamage の直前」——ここより後ろの出血/AoEも同じ total を
        // 読むので、抑制後の値が自動的に引き継がれる。モブ→プレイヤーは別経路
        // (handleMobToPlayerDamage)なので影響しない。
        if (livingVictim != null && PvpDamagePolicy.isPvp(victim)) {
            total = PvpDamagePolicy.apply(total, PvpDamagePolicy.maxHealthOf(livingVictim),
                    damageConfig.pvpEnabled(), damageConfig.pvpDamageMultiplier(),
                    damageConfig.pvpMaxDamagePercentOfMaxHealth());
        }
        if (hit.crit() && livingVictim != null) {
            CritFlash.play(livingVictim);
        }

        // Vanilla armor / resistance / armor-enchant (MAGIC) are re-derived inside the pipeline
        // (COMBAT_SYSTEM_SPEC 5, 課題1), so only the engine's own ARMOR/RESISTANCE/MAGIC modifiers are
        // zeroed to avoid double-mitigation (the service re-injects armor from attributes, resistance
        // from the potion effect, and Protection/Projectile Protection via DefenseEnchantmentBridge);
        // every other modifier (shield BLOCKING, ABSORPTION, ...) is left for the engine to apply as-is.
        for (EntityDamageEvent.DamageModifier modifier : DAMAGE_MODIFIERS) {
            if (FOLDED_MODIFIERS.contains(modifier) && event.isApplicable(modifier)) {
                event.setDamage(modifier, 0.0);
            }
        }
        // #6 Part B: 最終物理ダメージが負値の場合はダメージを与えず、その絶対値分だけ被害者を回復する
        // (setCancelled はしない — アグロ/被弾判定は従来通り流す)。0以上は従来通りの挙動。
        if (total < 0 && livingVictim != null) {
            event.setDamage(EntityDamageEvent.DamageModifier.BASE, 0.0);
            healVictim(livingVictim, -total);
        } else {
            event.setDamage(EntityDamageEvent.DamageModifier.BASE, Math.max(0.0, total));
        }

        // Bleed proc (Q3 = (c), melee only): #3 全ステ合算により bleed-chance/bleed-damage は攻撃集約
        // (防具 + メインハンド + (設定により)オフハンド + パーク + アドオン)から読む。アイテムCT(メインハンド専用)
        // とは異なり、出血は他の攻撃系ステと同様「全ソース合算」で扱う(完全合算の意図通り)。負値(=回復)の
        // 一撃では出血させない(回復と同時に出血DoTを付けるのは矛盾するため total>0 に限定)。Projectile bleed
        // は対象外(着弾時のメインハンドが発射武器とは限らない)。
        if (total > 0 && MELEE_CAUSES.contains(event.getCause()) && victim instanceof LivingEntity living) {
            maybeApplyBleed(attacker, living, attackerStats);
        }

        // 訓練用ダミー(DPSChecker-TF)を殴ってもスキルEXPは付与しない(ダミー叩きでのEXP稼ぎ防止)。
        // ワールド倍率(2026-07-26 オーバーワールドEXP開放): ダンジョン内=1.0、ダンジョン外=
        // outside-dungeon-exp-rate(dungeon-only-exp: true なら0.0で従来どおり完全遮断)。
        if (total > 0 && !TrainingDummies.isTrainingDummy(victim)) {
            double worldRate = worldExpRate(victim.getWorld());
            if (worldRate > 0.0) {
                maybeGrantCombatSkillExp(attacker, mainhandContributor, victim.getUniqueId(), total,
                        victim, worldRate);
            }
        }

        // Physical item-cooldown (アイテムCT): after a landed PRIMARY melee hit, start the weapon's cooldown so
        // the next swings are 使用不可 until it elapses (checked at the gate above). agg.mainhand() は
        // メインハンド(または発射武器)単体のマップ — 防具/オフハンドを絶対に混ぜない(アイテムCTの誤ゲート防止)。
        maybeStartItemCooldown(attacker, event, agg.mainhand());

        // #2 攻撃範囲(AoE): 近接主命中(ENTITY_ATTACK)のみ。aoe-radius / aoe-damage-rate / aoe-max-targets を
        // 持つとき、周囲のエンティティへ splash = 主命中TFダメージ × rate を与える。負値/0の一撃では発生しない。
        // 2026-07-26 stat-scope 境界引き直し §3 (B→C 昇格): 従来は agg.item() のみを直接読んでおり、
        // スキルツリーパーク/アドオン由来のAoE分がPlayerCombatAggregateへは合算されていても無視されていた
        // (item map は perkAttack/addon を含まない)。agg.totalOf(key) 経由に切替え、POWER_ATTACK_* 系
        // (直上のコード)と同じ「装備+パーク+アドオンを totalOf で1回だけ合算」パターンに揃える。
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            Map<String, Double> aoeStats = Map.of(
                    AOE_RADIUS_KEY, agg.totalOf(AOE_RADIUS_KEY),
                    AOE_DAMAGE_RATE_KEY, agg.totalOf(AOE_DAMAGE_RATE_KEY),
                    AOE_MAX_TARGETS_KEY, agg.totalOf(AOE_MAX_TARGETS_KEY));
            maybeApplyAreaDamage(attacker, victim, aoeStats, total);
            if (powerAttackRadius > 0.0) {
                maybeApplyAreaDamage(attacker, victim, Map.of(
                        AOE_RADIUS_KEY, powerAttackRadius,
                        AOE_DAMAGE_RATE_KEY, 0.35), total);
            }
        }
    }

    /**
     * B2 レビュー修正(HIGH指摘1): 近接プレイヤー攻撃のチャージ倍率を、{@code Player#getAttackCooldown()}
     * ではなく {@link MeleeChargeTracker} が記録した「前回近接攻撃からの経過tick」と実効攻撃速度
     * ({@code Attribute.ATTACK_SPEED})から自前計算する。{@code melee-charge.enabled=false} なら
     * {@link #meleeChargeTracker} すら読まず1.0を返す(実サーバでの余計な処理を避け、トグルOFF時は
     * テストダブル環境でも安全)。一次情報確認済み(セッションレポート参照): Paper 1.21.11では
     * {@code resetAttackStrengthTicker()} が {@code EntityDamageByEntityEvent} 発火"後"に呼ばれるため
     * {@code getAttackCooldown()} 自体は本来読めるはずだが、Paper開発チームは新しいバージョン系統(PR
     * #13856, 2026-05-03マージ)でこの順序を"事前"へ変更する判断を公式に下しており
     * (「The cooldown is computed before the damage is now, which is proper」)、将来 1.21.11 系統への
     * 波及やAPI仕様変更に対して脆弱——自前計算はこの依存を完全に断つ。
     */
    private double meleeChargeMultiplier(Player attacker) {
        if (!damageConfig.meleeChargeEnabled()) {
            return 1.0;
        }
        int currentTick = Bukkit.getCurrentTick();
        int elapsedTicks = meleeChargeTracker.elapsedTicksSince(attacker.getUniqueId(), currentTick);
        double attackSpeed = attackSpeedOf(attacker);
        return MeleeChargeMultiplier.compute(true, elapsedTicks, attackSpeed,
                damageConfig.meleeChargeMinMultiplier(), damageConfig.meleeChargeExponent());
    }

    /**
     * {@code Attribute.ATTACK_SPEED} の実効値(装備/TF基礎ステ込みのバニラアトリビュート値)。属性
     * インスタンス未登録(理論上ありえないが防御的に)なら vanilla既定値 4.0 にフォールバックする。
     */
    private static double attackSpeedOf(Player attacker) {
        AttributeInstance instance = attacker.getAttribute(Attribute.ATTACK_SPEED);
        return instance != null ? instance.getValue() : 4.0;
    }

    /** ログアウトしたプレイヤーの近接チャージ記録を破棄する(メモリリーク防止, B2レビュー修正)。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        meleeChargeTracker.forget(playerId);
        combatKillCredits.forgetAttacker(playerId);
    }

    /**
     * Stamps spawner origin once so the editable Valhalla archery spawner multiplier can be honored.
     *
     * <p>2026-07-29: キーを {@link PdcKeys#MOB_SPAWNER_SPAWNED} へ移した(値は同一 —
     * プラグイン名由来の namespace が {@code trinityforge} なので完全に互換)。
     * {@code MobTransformCarryOver} が変身時にこの印を引き継ぐためで、
     * 以前はスポナーのゾンビを水没させるだけでスポナーEXP抑制を回避できた。
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            event.getEntity().getPersistentDataContainer()
                    .set(PdcKeys.MOB_SPAWNER_SPAWNED, PersistentDataType.BYTE, (byte) 1);
        }
    }

    /**
     * HEAVY_WEAPONS/LIGHT_WEAPONS pay exactly once on a confirmed death. Requiring the last damage
     * event to resolve to the credited player prevents an old tag followed by lava/fall death from paying.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCombatKill(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        var credit = combatKillCredits.consume(dead.getUniqueId(),
                killer == null ? null : killer.getUniqueId());
        if (credit.isEmpty() || killer == null) return;
        EntityDamageEvent last = dead.getLastDamageCause();
        if (!(last instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Player lastAttacker = resolveAttacker(byEntity);
        if (lastAttacker == null
                || !killer.getUniqueId().equals(lastAttacker.getUniqueId())) {
            return;
        }
        String skill = credit.get().skill();
        double amount = skillExp.combatKillExp(
                skill, dead.getType().name(), Math.max(0, MobData.of(dead).level()), maxHealth(dead));
        if (amount <= 0.0) return;
        double worldRate = worldExpRate(dead.getWorld());
        if (worldRate <= 0.0) return;
        double role = roleBuffResolver.expMultiplierForSkill(killer, skill).orElse(1.0);
        TrinityForge tf = TrinityForge.getInstance();
        double spot = tf == null ? 1.0
                : tf.locationExpDiminishing().multiplierForKillSpot(killer, dead, skillExp,
                        tf.dungeonWorldRegistry().isDungeonWorld(dead.getWorld().getUID()));
        ArsProgressionBridge.grantSkillExp(plugin, killer, skill, amount * role * worldRate * spot);
    }

    private static final String REFLECT_FLAT_KEY = StatKeys.canonical("reflect-flat");
    private static final String REFLECT_PERCENT_KEY = StatKeys.canonical("reflect-percent");

    /**
     * 課題2 (2026-07-25): バニラ自身の {@code THORNS} 原因イベント(棘の鎧が攻撃者へ反撃ダメージを与える
     * ために生成する合成 {@link EntityDamageByEntityEvent})を、ダメージが確定する前(LOWEST)で完全に
     * キャンセルする。反射はもう {@link #onReflectDamage} のステータス駆動計算(reflect-flat/
     * reflect-percent)へ一本化されたため、バニラ自身の棘プロックが別途ダメージを与えると二重反射になる。
     *
     * <p>耐久値消費は維持される: 装備1部位の耐久が減る副作用は、この合成イベントが生成される"前"に
     * バニラ本体(NMS)側で既に確定しており、Bukkitへ通知される頃には既に起きている。ここでのキャンセルは
     * イベントオブジェクトの以後の処理(ダメージ適用・後続リスナー)を止めるだけで、既に確定済みの耐久消費を
     * 遡って取り消す手段はBukkit APIに存在しない — 確認方法は本体のこのjavadocに記載の通りコード上の
     * 構造的保証(このリスナーはItemStack/耐久に一切触れない)であり、実サーバでは「棘の鎧装備でエリートに
     * 殴られた後、防具の耐久ゲージが減っているが、旧来のバニラ棘ダメージ数値は表示されない」ことを目視確認
     * すること。{@code LOWEST} + キャンセル で、フォーク({@code EliteMobDamagedByPlayerEvent
     * #onEliteMobAttacked}, {@code ignoreCancelled = true})を含む全ての後続リスナーがこのイベントを
     * 一切処理しなくなる(処理の有無に依存しない一本化)。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onVanillaThornsProc(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.THORNS) {
            event.setCancelled(true);
        }
    }

    /**
     * 課題2 (2026-07-25): 棘の鎧の再設計 — プレイヤーが被弾した際、守備カテゴリの {@code reflect-flat}
     * (反射率（実）) + {@code reflect-percent}(反射率（割）、被ダメージ割合)ぶんのダメージを実際の攻撃者
     * (近接/投射物の発射者)へ跳ね返す。{@code reflect-percent} には棘の鎧レベル(装備4部位合計)×10%も
     * {@link ReflectDamageBridge} 経由で加算される(ユーザー決定)。バニラ自身の棘プロックは
     * {@link #onVanillaThornsProc} で無効化済みなのでここが反射の単一経路。
     *
     * <p>MONITOR優先度: 他プラグイン(シールドブロック等)によるダメージ軽減が確定した後の
     * {@link EntityDamageEvent#getFinalDamage()} を使う(被ダメージ割合はブロック等で0になった一撃を反射しない)。
     * ダメージを与える相手は {@code EntityDamageByEntityEvent} の damager(近接ならその実体、投射物なら
     * {@link Projectile#getShooter()})。{@link #reflecting} ガードにより、反射で与えたダメージが再度
     * このハンドラを同期再入しても即returnし、無限ループ(反射→反射→…)を1ホップで断ち切る
     * ({@link #applyingAoe} と同一パターン)。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onReflectDamage(EntityDamageByEntityEvent event) {
        if (reflecting) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        double finalDamage = event.getFinalDamage();
        if (finalDamage <= 0.0) {
            return;
        }
        LivingEntity reflectTarget = resolveReflectTarget(event.getDamager());
        if (reflectTarget == null || reflectTarget.equals(victim) || !reflectTarget.isValid()) {
            return;
        }
        PlayerCombatAggregate agg = aggregator.aggregate(victim);
        double flat = Math.max(0.0, agg.totalOf(REFLECT_FLAT_KEY));
        double percent = Math.max(0.0,
                agg.totalOf(REFLECT_PERCENT_KEY) + ReflectDamageBridge.thornsPercentContribution(victim));
        double reflectAmount = flat + percent * finalDamage;
        if (!Double.isFinite(reflectAmount) || reflectAmount <= 0.0) {
            return;
        }
        reflecting = true;
        try {
            reflectTarget.damage(reflectAmount, victim);
        } finally {
            reflecting = false;
        }
    }

    /**
     * 反射先の実体を解決する: 近接/直接攻撃者はそのまま、投射物ならその発射者(生物のみ)。
     * TNT等の非生物ダメージャーや発射者不明の投射物は反射対象なし({@code null})。
     */
    private static LivingEntity resolveReflectTarget(Entity damager) {
        if (damager instanceof LivingEntity living) {
            return living;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity living) {
            return living;
        }
        return null;
    }

    /**
     * Empty-swing アイテムCT: left-click air/block starts CT when the client sends an interact packet.
     * Looking into void/sky often does <em>not</em> fire {@link PlayerInteractEvent} — that path is
     * covered by {@link #onArmSwing} ({@link PlayerAnimationEvent}).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMissSwing(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        maybeStartSwingCooldown(event.getPlayer());
    }

    /**
     * Arm-swing CT: fires for every main-hand swing, including void/sky misses that never send
     * {@link PlayerInteractEvent}. Already-running CT is not refreshed (see {@link #maybeStartSwingCooldown}).
     * Landed hits also fire this before/around damage; starting CT here matches "swing commits CT".
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        maybeStartSwingCooldown(event.getPlayer());
    }

    /**
     * 2026-07-26 右クリック使用アイテムのアイテムCT対応: 杖・触媒等、近接ではなく右クリックで使う
     * アイテムに {@code item-cooldown} を設定してもCTが一切始まらなかった穴を埋める。近接ミス
     * ({@link #onMissSwing})/命中({@link #onArmSwing})と全く同じ {@link #maybeStartSwingCooldown}
     * を再利用するため、ロジックの複製はゼロ — 二重発動ガード(既にCT中のアイテムには掛け直さない)も
     * このメソッド内の {@code player.getCooldown(mainhand) > 0} チェックがそのまま効く。例: 剣を右クリック
     * してからスイングで殴っても、後続の呼び出しは既に始まっているCTを見て即return するため二重に短縮
     * 計算が走ったりCTがリセットされたりしない。
     *
     * <p>優先度はMONITOR、{@code ignoreCancelled = true}: このリスナーはCTを開始するだけで他の判断に
     * 一切影響を与えないため、他プラグイン/TF内の別リスナーが最終的に何を決めた後でも安全に動ける
     * MONITORを選んだ(onMissSwing/onArmSwingと同じ位置付け)。ignoreCancelled=trueにより、
     * アドベンチャーモードで {@code useInteractedBlock()} がDENYになりイベント全体がキャンセルされた
     * ケースを含め、キャンセル済みイベントでは発火しない — これは同ファイル内の他の
     * {@code PlayerInteractEvent} 購読者({@link com.trinityforge.active.ActivationDispatcher}含む)と
     * 揃えた既存の書き方であり、新しい判定方式を発明していない。
     *
     * <p>オフハンドは対象外({@code event.getHand() == EquipmentSlot.HAND} でゲート) —
     * {@link #startItemCooldown} 自体がメインハンド専用の実装であるため、オフハンドの右クリックで
     * 呼び出すと {@code attacker.getInventory().getItemInMainHand()} を読んでメインハンドのCTを誤って
     * 開始してしまう(オフハンドにアイテムCTを掛けたい訳ではない操作でも発動する)。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRightClickItem(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // useItemInHand() は isCancelled()(= useInteractedBlock() が DENY か)とは独立した別フィールド。
        // WorldGuard等の保護プラグインが「ブロック操作は許すがアイテム使用だけ拒否」した場合、イベント自体は
        // キャンセルされないまま useItemInHand() だけ DENY になる。その場合アイテムは実際には使われていない
        // ので、CTを開始してはいけない(使えていないのにCTだけ走る = 事実上のペナルティになる)。
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        maybeStartSwingCooldown(event.getPlayer());
    }

    /** Shared miss/swing CT start: no-op when bare hand, no item-cooldown stat, or already cooling down. */
    private void maybeStartSwingCooldown(Player player) {
        ItemStack mainhand = player.getInventory().getItemInMainHand();
        if (mainhand.getType().isAir()) {
            return;
        }
        Map<String, Double> derived;
        try {
            derived = DerivedItemStats.resolve(mainhand, itemStats, damageConfig.weaponBaseFormula());
        } catch (RuntimeException malformedItem) {
            return;
        }
        // ItemStack版 getCooldown: cooldown_group 単位で照会 (同マテリアル別IDとCTを共有しない)。
        if (player.getCooldown(mainhand) > 0) {
            return;
        }
        startItemCooldown(player, derived);
    }

    /**
     * 被害者を {@code amount} だけ回復する(#6 Part B の負値最終ダメージのヒール変換)。
     * {@link BleedService} の回復クランプ実装を踏襲: 最大体力を超えず、0未満にもならない。
     */
    private static void healVictim(LivingEntity lv, double amount) {
        double newHealth = lv.getHealth() + amount;
        AttributeInstance maxHealth = lv.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            newHealth = Math.min(newHealth, maxHealth.getValue());
        }
        lv.setHealth(Math.max(0.0, newHealth));
    }

    private static double maxHealth(LivingEntity entity) {
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        return maxHealth == null ? Math.max(0.0, entity.getHealth()) : Math.max(0.0, maxHealth.getValue());
    }

    private static final String ITEM_COOLDOWN_KEY = StatKeys.canonical("item-cooldown");

    /**
     * Longest cooldown TF will set (1 hour), so an absurd rolled value can't overflow the int tick count.
     * 段階1宣言(2026-07-27): {@code stats/lore.yml} の {@code item-cooldown.limits.max-duration-ticks-ref}
     * が参照する昇格済み定数(可視性のみpublicへ変更、値・挙動は不変)。
     */
    public static final long ITEM_COOLDOWN_MAX_TICKS = 72_000L;

    /**
     * アイテムCT applies to the PRIMARY melee hit only ({@code ENTITY_ATTACK}), never the sweep
     * ({@code ENTITY_SWEEP_ATTACK}): vanilla fires the primary event — which starts the cooldown — before
     * the same swing's sweep events, so gating/starting on sweep too would cancel a CT weapon's own sweep
     * damage on every swing. Vanilla only sweeps when the primary hit landed, so a blocked primary during
     * an active CT produces no sweep either — excluding sweep here does not leak damage past the CT.
     */
    private static final Set<EntityDamageEvent.DamageCause> ITEM_CT_CAUSES =
            EnumSet.of(EntityDamageEvent.DamageCause.ENTITY_ATTACK);

    /**
     * True when this is a primary melee hit whose mainhand weapon <em>owns</em> a {@code item-cooldown}
     * stat and that cooldown is still running ({@link Player#getCooldown(Material)} > 0) — i.e. its アイテムCT
     * has not elapsed, so the weapon is 使用不可 (like an ender pearl). The item is derived only once a
     * material cooldown is already active, so ordinary hits pay nothing; crucially, a vanilla item-use
     * cooldown on the same material (ender pearl / chorus fruit / goat horn / axe-disabled shield) does
     * NOT block melee, because a weapon without the stat resolves to 0 here. Projectiles never reach this.
     */
    private boolean meleeWeaponOnCooldown(Player attacker, EntityDamageByEntityEvent event) {
        if (!ITEM_CT_CAUSES.contains(event.getCause())) {
            return false;
        }
        ItemStack mainhand = attacker.getInventory().getItemInMainHand();
        // ItemStack版 getCooldown: アイテムの use_cooldown.cooldown_group (ItemAssembler が
        // material+CMD 単位で刻印) を見るため、同マテリアル別IDのアイテムとCTを共有しない。
        // グループ未刻印の旧アイテムはマテリアル共有にフォールバック(次のrefreshで解消)。
        if (mainhand.getType().isAir() || attacker.getCooldown(mainhand) <= 0) {
            return false;
        }
        double seconds = DerivedItemStats.resolve(mainhand, itemStats,
                damageConfig.weaponBaseFormula()).getOrDefault(ITEM_COOLDOWN_KEY, 0.0);
        return seconds > 0.0;
    }

    /**
     * Starts the mainhand weapon's physical cooldown (アイテムCT) after a landed primary melee hit: reads the
     * weapon's derived {@code item-cooldown} in seconds and, when positive, calls {@link Player#setCooldown}
     * with the seconds converted to ticks (× 20, clamped to {@link #ITEM_COOLDOWN_MAX_TICKS}). This greys
     * the material out in the hotbar and, via the gate above, blocks the weapon's attacks until it elapses.
     * A weapon without the stat (0) sets nothing, so existing weapons are unaffected. Primary hit only — a
     * sweep must not restart the cooldown its own primary just set, and a projectile has already left the hand.
     */
    private void maybeStartItemCooldown(Player attacker, EntityDamageByEntityEvent event,
                                          Map<String, Double> weaponDerived) {
        if (!ITEM_CT_CAUSES.contains(event.getCause())) {
            return;
        }
        startItemCooldown(attacker, weaponDerived);
    }

    private static final String COOLDOWN_REDUCTION_KEY = StatKeys.canonical("cooldown_reduction");

    /** Starts mainhand item cooldown from derived {@code item-cooldown} seconds (no-op if ≤ 0). */
    private void startItemCooldown(Player attacker, Map<String, Double> weaponDerived) {
        double seconds = weaponDerived.getOrDefault(ITEM_COOLDOWN_KEY, 0.0);
        if (seconds <= 0.0) {
            return;
        }
        // power_cooldownreduction_add → cooldown_reduction (2026-07-23 stat-gate-overhaul §2 移行B3):
        // 装備+perk合算(アイテムCT短縮 — 2026-07-25 CT短縮ステータス分離 §1-A: 表示名/説明を実態に合わせて訂正。
        // アクティブスキルのCTは一切短縮しない。スキルCT短縮はActiveSkill単位のキー(例:
        // haste-active-mining-cooldown-reduction、com.trinityforge.active.ActivationDispatcher消費、
        // 2026-07-25 CT設計一本化 §2で旧グローバルskill-cooldown-reductionから分割)が担う)。メインハンド
        // 単体マップ(agg.mainhand())ではなく、アイテムCT自身は他の攻撃系ステと同様「全ソース合算」で扱う
        // (#3 全ステ合算の意図通り)。
        double reduction = aggregator.aggregate(attacker).totalOf(COOLDOWN_REDUCTION_KEY);
        if (Double.isFinite(reduction) && reduction > 0.0) {
            seconds = seconds * Math.max(0.05, 1.0 - Math.min(0.9, reduction));
        }
        ItemStack mainhand = attacker.getInventory().getItemInMainHand();
        if (mainhand.getType().isAir()) {
            return;
        }
        long ticks = Math.min(Math.round(seconds * 20.0), ITEM_COOLDOWN_MAX_TICKS);
        if (ticks > 0) {
            // ItemStack版 setCooldown: use_cooldown.cooldown_group 単位でCTを開始する
            // (ItemAssembler が material+CMD 単位のグループを刻印するため、同マテリアル別IDと共有しない)。
            attacker.setCooldown(mainhand, (int) ticks);
        }
    }

    /**
     * Returns {@code base} plus {@code addend} folded in field-wise ({@link Double#sum}); {@code base} is
     * never mutated. When {@code addend} is empty (no unlocked perks / ValhallaMMO absent) the result is a
     * <em>mutable copy</em> equal to {@code base} — never {@code base} itself — so every downstream read is
     * identical to the pre-perk behaviour and every downstream write (e.g. coating-bonus
     * {@code attackerStats.merge(...)}, Breach penetration) is safe even when {@code base} is the immutable
     * {@code Map.copyOf} produced by {@link PlayerCombatAggregate}.
     *
     * <p>CMB-01 (2026-07-25): the previous short-circuit {@code return base;} silently aliased the
     * immutable map whenever both perk and addon addends were empty — the standard state for a fresh
     * player with no skill-tree perks unlocked and no addon stats — and a subsequent {@code .merge()} call
     * (Breach penetration / coating bonus) threw {@link UnsupportedOperationException}, aborting the whole
     * damage pipeline and letting vanilla's raw damage through unmodified.
     */
    private static Map<String, Double> mergeStats(Map<String, Double> base, Map<String, Double> addend) {
        Map<String, Double> merged = new HashMap<>(base);
        if (addend.isEmpty()) {
            return merged;
        }
        addend.forEach((key, value) -> merged.merge(StatKeys.canonical(key), value, Double::sum));
        return merged;
    }

    /**
     * Ranged use-level gate for bows (PROGRESSION 1.6, I3): mirrors the melee gate at fire time. When
     * the shooting player does not meet the used bow's {@link ItemData} use requirement the shot is
     * cancelled and the player is told why, so an under-leveled player cannot use the weapon by
     * switching from melee to ranged. Reuses the shared {@link UseRequirementPolicy} + skill source +
     * action-bar wording (DRY). Non-player shooters (skeletons, dispensers) are ignored.
     *
     * <p><b>Bug fix (arrow-consumed-on-cancel):</b> {@code event.setCancelled(true)} alone does NOT stop
     * vanilla from removing the arrow from the shooter's inventory — Paper ships {@code setConsumeArrow}
     * / {@code setConsumeItem} specifically because cancellation and consumption are decoupled here. Both
     * must be explicitly turned off or the shot is blocked but the arrow still vanishes. Crossbows are
     * NOT fixed by this alone: their ammo is pulled out of the inventory earlier, at load time (see
     * {@link #onEntityLoadCrossbow}), which already happened by the time this event fires for them.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player shooter)) {
            return;
        }
        if (rangedWeaponUseBlocked(shooter, event.getBow())) {
            event.setCancelled(true);
            event.setConsumeArrow(false);
            event.setConsumeItem(false);
            return;
        }
        // Retain the firing bow/crossbow on the launched projectile so its attack stats are re-derived
        // from THIS weapon at impact, not from the shooter's mainhand then (High bug). A null/air bow
        // stores nothing and the impact path falls back to the mainhand as before.
        if (event.getProjectile() instanceof Projectile projectile) {
            ProjectileWeapon.store(projectile, event.getBow());
            // #3 2026-07-25バグ修正: 引き絞り量(0.0〜1.0、クロスボウは常時1.0)も同じ projectile へ
            // retain し、着弾時に tfBaseReplaces のitem attack-power へ乗率として掛け戻す(CombatListener
            // 本体側)。ここで捕捉しないと着弾時点ではもう force を取得する手段がない。
            ProjectileWeapon.storeDrawForce(projectile, event.getForce());
        }
    }

    /**
     * Ranged use-level gate for crossbows at LOAD time (bug fix, companion to {@link #onEntityShootBow}).
     * A crossbow's arrow is consumed from the inventory when it is loaded ({@code
     * EntityLoadCrossbowEvent}), not when it fires ({@code EntityShootBowEvent}) — by the time the shoot
     * event would fire, the ammo is already gone. Gating only at shoot time (as bows do) therefore still
     * lets an under-leveled player waste an arrow loading a crossbow they can never fire. Cancelling here
     * plus {@code setConsumeItem(false)} stops the load itself, so no ammo is pulled at all. The shoot-time
     * gate in {@link #onEntityShootBow} is kept as defense-in-depth for a crossbow that was already loaded
     * before this check applied (e.g. loaded by a dispenser, or while the player still met the
     * requirement).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityLoadCrossbow(io.papermc.paper.event.entity.EntityLoadCrossbowEvent event) {
        if (!(event.getEntity() instanceof Player shooter)) {
            return;
        }
        if (rangedWeaponUseBlocked(shooter, event.getCrossbow())) {
            event.setCancelled(true);
            event.setConsumeItem(false);
        }
    }

    /**
     * Ranged use-level gate for thrown tridents (PROGRESSION 1.6, I3). A trident leaves the hand as the
     * projectile, so the thrown item itself ({@link Trident#getItem()}) carries the weapon's PDC and is
     * gated here; a riptide throw spawns no projectile and is unaffected. Same shared policy + wording as
     * the melee/bow gates. Non-trident projectiles and non-player shooters are ignored.
     *
     * <p><b>Bug fix (same class as bow/crossbow):</b> unlike {@code EntityShootBowEvent}, {@code
     * ProjectileLaunchEvent} has no {@code setConsumeItem}/{@code setConsumeArrow} flag — vanilla removes
     * the thrown trident from the shooter's hand before this event fires, and cancelling the event cannot
     * undo that removal. {@link #restoreBlockedTrident} gives the exact thrown stack back, but ONLY when
     * the mainhand no longer holds it — this makes the fix safe even if a future Paper version changes the
     * consume/event ordering: if the hand still has the trident (never actually consumed), nothing is
     * added and no duplicate is created.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Trident trident)
                || !(trident.getShooter() instanceof Player shooter)) {
            return;
        }
        ItemStack thrown = trident.getItem();
        if (rangedWeaponUseBlocked(shooter, thrown)) {
            event.setCancelled(true);
            if (trident.isValid()) {
                trident.remove();
            }
            restoreBlockedTrident(shooter, thrown);
            return;
        }
        // The trident IS the projectile and carries its own thrown item, so retain that item on itself
        // for impact-time stat derivation (parity with the bow path). A bow-fired arrow also fires this
        // event but is not a Trident, so it is ignored here (its weapon was stored in onEntityShootBow).
        ProjectileWeapon.store(trident, trident.getItem());
    }

    /**
     * Gives {@code thrown} back to {@code shooter} when the blocked throw already removed it from the
     * mainhand (see {@link #onProjectileLaunch}). No-op when the mainhand still holds a matching stack,
     * so this can never create a duplicate regardless of consumption timing.
     */
    private static void restoreBlockedTrident(Player shooter, ItemStack thrown) {
        ItemStack main = shooter.getInventory().getItemInMainHand();
        if (main != null && !main.getType().isAir() && main.isSimilar(thrown) && main.getAmount() > 0) {
            return; // still in hand — the throw never actually consumed it, so restoring would duplicate.
        }
        ItemStack give = thrown.clone();
        give.setAmount(1);
        Map<Integer, ItemStack> leftover = shooter.getInventory().addItem(give);
        leftover.values().forEach(rest ->
                shooter.getWorld().dropItemNaturally(shooter.getLocation(), rest));
    }

    /**
     * True when {@code weapon} carries a use-level requirement the {@code shooter} does not meet; sends
     * the same action-bar notice as the melee gate so the cancelled shot is not silent. A null/air/PDC-less
     * weapon or one with no requirement is never blocked. Shares {@link UseRequirementPolicy} and the
     * skill source with the melee path (the melee gate stays on its own {@code EntityDamageByEntityEvent}).
     */
    private boolean rangedWeaponUseBlocked(Player shooter, ItemStack weapon) {
        if (!useRequirements.enforce()) {
            return false;
        }
        return UseRequirementResolver.resolve(weapon, itemStats)
                .filter(req -> !UseRequirementPolicy.meets(
                        req.skill(), req.level(), skillLevelSource.levelsOf(shooter.getUniqueId())))
                .map(req -> {
                    shooter.sendActionBar(Component.text(
                            "この装備を使うには " + req.skill() + " Lv" + req.level() + " が必要です",
                            NamedTextColor.RED));
                    return true;
                })
                .orElse(false);
    }

    /**
     * Rolls {@code bleed-chance} and, on success, starts a bleed dealing {@code bleed-damage} per
     * application for the configured number of ticks. #3 全ステ合算: the chance/damage are read from the
     * unified attack aggregate ({@code agg.item()} = 防具 + メインハンド + (設定により)オフハンド, plus perk
     * + addon), NOT the mainhand weapon alone — so armor/offhand-authored bleed also procs, matching the
     * "armor's offensive stats boost attacks" intent (アイテムCT だけが agg.mainhand() 専用の例外)。No bleed roll
     * (chance or damage 0) never procs. Keys are read canonically to match the aggregate map.
     */
    private void maybeApplyBleed(Player attacker, LivingEntity victim, Map<String, Double> weaponDerived) {
        double chance = weaponDerived.getOrDefault(StatKeys.canonical("bleed-chance"), 0.0);
        double damage = weaponDerived.getOrDefault(StatKeys.canonical("bleed-damage"), 0.0);
        if (chance <= 0.0 || damage <= 0.0) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            bleedService.apply(victim, attacker.getUniqueId(), damage, damageConfig.bleedTicks());
        }
    }

    private static final String AOE_RADIUS_KEY = StatKeys.canonical("aoe-radius");
    private static final String AOE_DAMAGE_RATE_KEY = StatKeys.canonical("aoe-damage-rate");
    private static final String AOE_MAX_TARGETS_KEY = StatKeys.canonical("aoe-max-targets");
    private static final String POWER_ATTACK_DAMAGE_KEY = StatKeys.canonical("power-attack-damage");
    private static final String POWER_ATTACK_RADIUS_KEY = StatKeys.canonical("power-attack-radius");
    private static final String DISTANCE_DAMAGE_BONUS_KEY = StatKeys.canonical("distance-damage-bonus");

    /**
     * 距離ダメージ(distance-damage-bonus)の効果対象ブロック距離の絶対上限。stats/lore.yml
     * {@code stats.distance-damage-bonus.limits} から {@code java:} cap-ref で参照される
     * (CapRefResolver 拘束テスト対象)。{@code public static final} でないと cap-ref から参照できない。
     */
    public static final double MAX_DISTANCE_DAMAGE_BLOCKS = 64.0;

    /**
     * 弓術の距離ダメージ: 16ブロックで係数1.0倍分、{@link #MAX_DISTANCE_DAMAGE_BLOCKS}ブロックで頭打ち
     * (4.0倍分)。{@code bonus=0.2} なら 64ブロック地点で最終ダメージ×1.8。
     * 純粋関数(Bukkit非依存)なのでユニットテストから直接呼べる — {@link #powerAttackDamage} と同じ流儀。
     */
    static double distanceDamage(double finalDamage, double bonus, double blocks) {
        if (bonus <= 0.0 || !Double.isFinite(bonus) || !Double.isFinite(blocks) || blocks <= 0.0) {
            return finalDamage;
        }
        return finalDamage * (1.0 + bonus * Math.min(MAX_DISTANCE_DAMAGE_BLOCKS, blocks) / 16.0);
    }

    static double powerAttackDamage(double finalDamage, double bonus, boolean airborne) {
        if (!airborne || bonus <= 0.0 || !Double.isFinite(bonus)) return finalDamage;
        return finalDamage * (1.0 + bonus);
    }

    /**
     * 攻撃範囲(AoE, #2): 主命中(近接)後、攻撃集約が {@code aoe-radius}(半径ブロック) と {@code aoe-damage-rate}
     * (主命中TFダメージに対する割合) を持つとき、主対象の周囲 {@code radius} 内の {@link LivingEntity} へ
     * {@code splash = primaryDamage × rate} を与える。攻撃者と主対象は常に除外し、{@code aoe.hit-players} が
     * false の間は他プレイヤーも除外(既定=モブのみ)。{@code aoe-max-targets}(任意, 0/未設定=無制限)で近い順に
     * 対象数を制限する。スプラッシュは {@code target.damage(splash, attacker)} で与えるため、{@link #applyingAoe}
     * ガードにより二次被弾はTF処理を通さずバニラ(各対象自身の防具)で軽減される。負値/0の主命中では発生しない。
     */
    private void maybeApplyAreaDamage(Player attacker, Entity primaryVictim,
                                      Map<String, Double> aggregateStats, double primaryDamage) {
        if (primaryDamage <= 0.0 || !(primaryVictim instanceof LivingEntity primary)) {
            return;
        }
        double radius = aggregateStats.getOrDefault(AOE_RADIUS_KEY, 0.0);
        double rate = aggregateStats.getOrDefault(AOE_DAMAGE_RATE_KEY, 0.0);
        if (radius <= 0.0 || rate <= 0.0) {
            return;
        }
        double splash = primaryDamage * rate;
        if (splash <= 0.0) {
            return;
        }
        double radiusSq = radius * radius;
        boolean hitPlayers = damageConfig.aoeHitPlayers();
        List<LivingEntity> targets = new ArrayList<>();
        for (Entity nearby : primary.getNearbyEntities(radius, radius, radius)) {
            if (!(nearby instanceof LivingEntity candidate)) {
                continue;
            }
            double distanceSq = candidate.getLocation().distanceSquared(primary.getLocation());
            if (aoeEligible(candidate.equals(attacker), candidate.equals(primary),
                    candidate instanceof Player, distanceSq, radiusSq, hitPlayers)) {
                targets.add(candidate);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        int maxTargets = (int) Math.floor(aggregateStats.getOrDefault(AOE_MAX_TARGETS_KEY, 0.0));
        if (maxTargets > 0 && targets.size() > maxTargets) {
            targets.sort(Comparator.comparingDouble(
                    t -> t.getLocation().distanceSquared(primary.getLocation())));
            targets = targets.subList(0, maxTargets);
        }
        // 再入ガードを立ててから同期的に各対象へダメージ。スプラッシュ由来の被弾イベントは
        // onEntityDamageByEntity 冒頭で早期returnされる(AoEの連鎖防止 + splash量の再計算上書き防止)。
        applyingAoe = true;
        try {
            for (LivingEntity target : targets) {
                // AoEスプラッシュはこのリスナーの冒頭(applyingAoeガード)で早期returnされるため、
                // 主命中に掛けたPvP抑制がスプラッシュには届かない。hit-players を有効にした構成で
                // 抜け道にならないよう、ここでも同じ抑制を掛ける。
                double amount = PvpDamagePolicy.isPvp(target)
                        ? PvpDamagePolicy.apply(splash, PvpDamagePolicy.maxHealthOf(target),
                                damageConfig.pvpEnabled(), damageConfig.pvpDamageMultiplier(),
                                damageConfig.pvpMaxDamagePercentOfMaxHealth())
                        : splash;
                target.damage(amount, attacker);
            }
        } finally {
            applyingAoe = false;
        }
    }

    /**
     * AoE対象の純粋な選抜判定(半径・自他除外・プレイヤー除外)。Bukkit非依存なので単体テストで境界を検証する。
     * 攻撃者本人・主対象は常に除外、{@code hitPlayers} が false ならプレイヤーも除外、最後に球状半径
     * ({@code distanceSq <= radiusSq})で判定する。
     */
    static boolean aoeEligible(boolean candidateIsAttacker, boolean candidateIsPrimary,
                               boolean candidateIsPlayer, double distanceSq, double radiusSq,
                               boolean hitPlayers) {
        if (candidateIsAttacker || candidateIsPrimary) {
            return false;
        }
        if (candidateIsPlayer && !hitPlayers) {
            return false;
        }
        return distanceSq <= radiusSq;
    }

    /**
     * Resolves the symmetric-pipeline attacker: a player's melee/sweep hit, or a projectile
     * (arrow, trident, ...) shot by a player (COMBAT_SYSTEM_SPEC 2.2: attack type is determined by
     * the attack source, and a bow/crossbow/trident is still the physical component).
     */
    private static Player resolveAttacker(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Player player && MELEE_CAUSES.contains(event.getCause())) {
            return player;
        }
        if (damager instanceof Projectile projectile
                && event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    private static LivingEntity resolveMobAttacker(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof LivingEntity living && !(damager instanceof Player)
                && MELEE_CAUSES.contains(event.getCause())) {
            return living;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void handleMobToPlayerDamage(EntityDamageByEntityEvent event, LivingEntity mob, Player victim) {
        // Addon ability damage (B1): a marked synthetic target.damage(...) call arrives with a melee
        // cause but is owned by the marking addon (routed through the MAGICAL component there), so the
        // physical mob-melee path must stand down even for an attack-stamped mob.
        if (com.trinityforge.combat.MobAbilityDamage.isActive()) {
            return;
        }
        if (!MobData.of(mob).hasAttackProfile()) {
            return;
        }
        double vanillaBaseDamage = event.getDamage();
        if (vanillaBaseDamage <= 0) {
            return;
        }
        AttackStats attack = MobData.of(mob).attackStats();
        CombatHitResult hit = combatService.physicalFinalDamageFromMobResult(
                mob, victim, vanillaBaseDamage, attack);
        double total = hit.damage();
        if (hit.crit()) {
            CritFlash.play(victim);
        }
        for (EntityDamageEvent.DamageModifier modifier : DAMAGE_MODIFIERS) {
            if (FOLDED_MODIFIERS.contains(modifier) && event.isApplicable(modifier)) {
                event.setDamage(modifier, 0.0);
            }
        }
        if (total < 0) {
            healVictim(victim, -total);
            event.setDamage(EntityDamageEvent.DamageModifier.BASE, 0.0);
        } else {
            event.setDamage(EntityDamageEvent.DamageModifier.BASE, Math.max(0.0, total));
        }
    }

    /**
     * True when this is a melee hit and the attacker does not meet the mainhand weapon's use-level
     * requirement (I3). A bare hand or an item with no requirement is never blocked. The player is
     * told why via the action bar so the cancelled swing is not silent.
     */
    private boolean meleeWeaponUseBlocked(Player attacker, EntityDamageByEntityEvent event) {
        if (!useRequirements.enforce()) {
            return false;
        }
        if (!MELEE_CAUSES.contains(event.getCause())) {
            return false;
        }
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        return UseRequirementResolver.resolve(weapon, itemStats)
                .filter(req -> !UseRequirementPolicy.meets(
                        req.skill(), req.level(), skillLevelSource.levelsOf(attacker.getUniqueId())))
                .map(req -> {
                    attacker.sendActionBar(Component.text(
                            "この装備を使うには " + req.skill() + " Lv" + req.level() + " が必要です",
                            NamedTextColor.RED));
                    return true;
                })
                .orElse(false);
    }

    /**
     * この一撃の攻撃武器そのもの(ItemStack)を解決する。プレイヤーが放った飛び道具なら発射時に
     * {@link ProjectileWeapon} で retain された発射武器(bow/crossbow/trident、High bug修正: 着弾時の
     * メインハンドではない)、それ以外(retain無し、または近接)はプレイヤーの現在のメインハンド。
     * {@link PlayerStatAggregator#aggregate(Player, ItemStack)} の contributor 引数として使う。
     */
    private static ItemStack resolveMainhandContributor(EntityDamageByEntityEvent event, Player attacker) {
        if (event.getDamager() instanceof Projectile projectile) {
            ItemStack firingWeapon = ProjectileWeapon.read(projectile).orElse(null);
            if (firingWeapon != null) {
                return firingWeapon;
            }
        }
        return attacker.getInventory().getItemInMainHand();
    }

    /**
     * true = 武器スキルEXPを付与してよいワールド(dungeon-only-exp=falseなら常にtrue、trueなら
     * ダンジョンワールド({@link com.trinityforge.dungeon.DungeonWorldRegistry})限定)。
     */
    private double worldExpRate(World world) {
        TrinityForge tf = TrinityForge.getInstance();
        if (tf == null || world == null) {
            // ダンジョン判定の材料が無い(プラグイン未起動=ユニットテスト等)ときはワールドゲートを
            // 掛けない。旧 expAllowedInWorld が同じ状況で true を返していたのと同じ挙動。
            return 1.0;
        }
        return skillExp.worldExpRate(tf.dungeonWorldRegistry().isDungeonWorld(world.getUID()));
    }

    /**
     * タスク2(2026-07-26 EXP調整): {@code weapon} を落とした一撃に対する武器スキルEXPを付与する。
     * {@code damage} はこの一撃の最終ダメージ(=呼び出し元の {@code total})、{@code victim} はこの
     * 一撃を受けたEntity(モブレベルの参照に使う)。
     *
     * <p>2026-07-27 牧場対策: {@code victim} の EntityType が {@code combat/mob-level-table.yml} の
     * {@code no-skill-exp-mobs} に載っていれば、武器スキルEXPは一切付与しない(バニラEXPオーブは
     * このメソッドの管轄外なので影響を受けない)。{@code mobLevelTable} が null(旧コンストラクタ経由)
     * のときは従来どおり抑止しない。
     */
    private void maybeGrantCombatSkillExp(Player attacker, ItemStack weapon, UUID targetId,
                                          double damage, Entity victim, double worldRate) {
        // Every new physical hit replaces the prior attribution. If this hit is not a heavy/light
        // weapon, an older qualifying hit must not survive and receive credit for a later bare-hand,
        // archery, or unrelated-tool finishing blow.
        combatKillCredits.clear(targetId);
        if (mobLevelTable != null && victim != null && mobLevelTable.suppressesSkillExp(victim.getType())) {
            return;
        }
        // バグ2修正(2026-07-28): メインハンドの use-skill が何であれ、この経路(近接/投射物ダメージ確定
        // 直後の戦闘EXP)へそのまま渡していたため、採取用ツール(斧/ツルハシ/シャベル/クワ/釣竿)で敵を
        // 殴ると WOODCUTTING 等の採取スキルへ「殴った」だけでEXPが入っていた(stats/item-stats.yml で
        // ツールにも use-skill: <採取スキル> が付いているため)。この経路は「戦闘で武器スキルEXPを
        // 付与する」専用であるべきで、isCombatWeaponSkill で HEAVY_WEAPONS/LIGHT_WEAPONS/ARCHERY の
        // 3つだけに絞る。ARS_MAGIC は ArsMagicExperienceListener の別経路で付与されるため
        // ここには含めない(含めると魔法攻撃でも二重に武器EXPが入る)。防具スキルも別経路。
        // 「代わりにHEAVY_WEAPONSへ与える」等のフォールバックはしない — 採取用の斧/ツルハシ等は道具で
        // あって武器ではなく、TFには戦斧(別マテリアル、use-skill: HEAVY_WEAPONS)が武器として別に存在
        // する。道具で殴っても戦闘EXPが入らないのが正しい挙動であり、道具スキル側のEXPは
        // NativeSkillExperienceListener 側の採取専用経路が担う。
        UseRequirementResolver.resolve(weapon, itemStats)
                .filter(UseRequirementResolver.Resolved::hasSkill)
                .filter(req -> isCombatWeaponSkill(req.skill()))
                .ifPresent(req -> {
                    if (isKillBasedCombatWeaponSkill(req.skill())) {
                        combatKillCredits.record(targetId, attacker.getUniqueId(), req.skill());
                        return;
                    }
                    if (!SkillId.ARCHERY.equals(req.skill())
                            || !(victim instanceof LivingEntity living)) {
                        return;
                    }
                    double exp = archeryExpAmount(weapon, damage, attacker, living);
                    if (exp <= 0.0) return;
                    double mult = roleBuffResolver.expMultiplierForSkill(attacker, req.skill()).orElse(1.0);
                    // TT/放置対策(同一地点の逓減)はワールド倍率とは独立に掛かる。両者とも [0,1] の
                    // 縮小係数なので順序に依存しない。
                    var tf = TrinityForge.getInstance();
                    double spot = tf == null || victim.getWorld() == null ? 1.0
                            : tf.locationExpDiminishing().multiplierForKillSpot(attacker, victim,
                                    skillExp,
                                    tf.dungeonWorldRegistry().isDungeonWorld(victim.getWorld().getUID()));
                    ArsProgressionBridge.grantSkillExp(plugin, attacker, req.skill(),
                            exp * mult * worldRate * spot);
                });
    }

    private double archeryExpAmount(ItemStack weapon, double damage, Player attacker, LivingEntity victim) {
        SkillCatalogEntry archery = progressionCatalog == null
                ? null : progressionCatalog.get(SkillId.ARCHERY);
        if (archery == null) {
            // Missing catalog wiring must never revive the removed per-hit EXP runtime.
            return 0.0;
        }
        double distance = attacker.getWorld().equals(victim.getWorld())
                ? attacker.getLocation().distance(victim.getLocation()) : 0.0;
        boolean infinity = weapon.containsEnchantment(Enchantment.INFINITY);
        boolean spawner = victim.getPersistentDataContainer()
                .has(PdcKeys.MOB_SPAWNER_SPAWNED, PersistentDataType.BYTE);
        return ArcheryExperiencePolicy.calculate(archery, weapon.getType(), damage, distance,
                maxHealth(victim), victim.getType().name(), infinity, spawner, victim instanceof Player);
    }

    /**
     * バグ2修正(2026-07-28): {@link #maybeGrantCombatSkillExp} が武器スキルEXPを付与してよいスキルか
     * どうかを判定する純粋関数(テストから直接叩ける package-private static)。
     *
     * <p>true を返すのは戦闘の武器スキル3つ({@link SkillId#HEAVY_WEAPONS} / {@link SkillId#LIGHT_WEAPONS} /
     * {@link SkillId#ARCHERY})だけ。{@link SkillId#ARS_MAGIC} は魔法攻撃の別経路
     * ({@link ArsMagicExperienceListener})で付与されるためここには含めない。防具スキル
     * ({@code HEAVY_ARMOR}/{@code LIGHT_ARMOR})や採取スキル({@code WOODCUTTING}/{@code MINING}/
     * {@code DIGGING}/{@code FARMING}/{@code FISHING})、{@code SMITHING} 等も含めない —
     * 採取用ツール(斧/ツルハシ/シャベル/クワ/釣竿)は {@code stats/item-stats.yml} で
     * {@code use-skill: <採取スキル>} を持つが、それらで敵を殴っても武器スキルEXPは入らないのが正しい
     * 挙動である(採取用の斧は道具であって武器ではなく、TFには戦斧という別マテリアルの武器が存在する)。
     * 「代わりにHEAVY_WEAPONSへ与える」等のフォールバックは意図的に行わない。
     */
    static boolean isCombatWeaponSkill(String skill) {
        if (skill == null || skill.isEmpty()) {
            return false;
        }
        return skill.equals(SkillId.HEAVY_WEAPONS)
                || skill.equals(SkillId.LIGHT_WEAPONS)
                || skill.equals(SkillId.ARCHERY);
    }

    static boolean isKillBasedCombatWeaponSkill(String skill) {
        return SkillId.HEAVY_WEAPONS.equals(skill) || SkillId.LIGHT_WEAPONS.equals(skill);
    }

}
