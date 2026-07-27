package com.trinityforge.integration.ars;

import com.trinityforge.config.domains.BaseStatsConfig;
import com.trinityforge.progression.PermanentBuffResolver;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Soft-depend bridge that exposes Ars Magic perk-buff totals for ArsPaper / Services consumers.
 * Values are always readable even when ArsPaper is absent (TF GUI / diagnostics).
 *
 * <p>{@code unlockedTier}/{@code glyphSlots}/{@code maxManaBonus}/{@code manaRegenBonus} are all
 * "プレイヤー単位の非装備寄与" totals — deliberately never the full {@code PlayerStatAggregator}
 * equipment+perk aggregate: the ArsPaper fork's item-stats path (mainhand/armor/offhand scan in
 * {@code ArmorManaListener}) already applies a caster's equipped stats for these 4 keys separately, so
 * folding equipment stats in here too would double-count them.
 *
 * <p>2026-07-26 マナ系ステ穴埋め: 全4キーとも「パーク general」だけでなく「永続バフ / 役職バフ /
 * base-stats」の4ソースをここで唯一の供給源として合算する(以前は {@code maxManaBonus}/
 * {@code manaRegenBonus} だけが永続バフを拾い、{@code unlockedTier}/{@code glyphSlots} は永続バフを
 * 取りこぼす非対称があった — 意図的な設計ではなく2026-07-24「修正B」がマナ2キーにしか適用されなかった穴
 * だったため、4キー対称に揃えた)。フォーク({@code ArmorManaListener}/{@code SpellCaster})は無改修のまま
 * ここ一箇所を直すだけで4キーとも穴が塞がる — 二重計上は起きない(装備側は今までどおりフォークが自前で
 * 集計し、ここは非装備分だけを供給する契約は変わらない)。
 */
public final class ArsNativeBridge {

    private static final Logger LOG = Logger.getLogger(ArsNativeBridge.class.getName());
    private static final String MANA_BONUS = StatKeys.canonical("mana_bonus");
    private static final String MANA_REGEN = StatKeys.canonical("mana_regen");
    private static final String ARS_TIER_BONUS = StatKeys.canonical("ars_tier_bonus");
    private static final String GLYPH_SLOT_BONUS = StatKeys.canonical("glyph_slot_bonus");

    private final PerkBuffResolver perkBuffResolver;
    private final PermanentBuffResolver permanentBuffResolver;
    private final RoleBuffResolver roleBuffResolver;
    private final BaseStatsConfig baseStats;

    public ArsNativeBridge(PerkBuffResolver perkBuffResolver) {
        this(perkBuffResolver, null);
    }

    /**
     * @param permanentBuffResolver optional (may be {@code null}, e.g. existing tests/call sites that
     *                              predate this parameter): folds achievement/collection permanent-buff
     *                              contributions into the 4 totals below. {@code null} skips it
     *                              (fail-soft — identical behaviour to before this parameter existed).
     */
    public ArsNativeBridge(PerkBuffResolver perkBuffResolver,
                           PermanentBuffResolver permanentBuffResolver) {
        this(perkBuffResolver, permanentBuffResolver, null, null);
    }

    /**
     * @param roleBuffResolver optional (may be {@code null}, e.g. existing tests/call sites that predate
     *                         this parameter): folds {@link RoleBuffResolver}'s attack/defense buff maps
     *                         (役職バフ, オンラインプレイヤーのみ解決可能) into the 4 totals below.
     *                         {@code null} skips it (fail-soft).
     * @param baseStats        optional (may be {@code null}): folds {@code combat/base-stats.yml} — a
     *                         config-driven baseline applied uniformly to every player, independent of
     *                         online/offline — into the 4 totals below. {@code null} skips it (fail-soft).
     */
    public ArsNativeBridge(PerkBuffResolver perkBuffResolver,
                           PermanentBuffResolver permanentBuffResolver,
                           RoleBuffResolver roleBuffResolver,
                           BaseStatsConfig baseStats) {
        this.perkBuffResolver = Objects.requireNonNull(perkBuffResolver, "perkBuffResolver");
        this.permanentBuffResolver = permanentBuffResolver;
        this.roleBuffResolver = roleBuffResolver;
        this.baseStats = baseStats;
    }

    public int unlockedTier(UUID playerId) {
        return playerId == null ? 0 : (int) Math.round(nonEquipmentTotal(playerId, ARS_TIER_BONUS));
    }

    public int glyphSlots(UUID playerId) {
        return playerId == null ? 0 : (int) Math.round(nonEquipmentTotal(playerId, GLYPH_SLOT_BONUS));
    }

    public double maxManaBonus(UUID playerId) {
        return playerId == null ? 0.0 : nonEquipmentTotal(playerId, MANA_BONUS);
    }

    public double manaRegenBonus(UUID playerId) {
        return playerId == null ? 0.0 : nonEquipmentTotal(playerId, MANA_REGEN);
    }

    /**
     * パーク general + 永続バフ + 役職バフ + base-stats の4ソース合算(装備は一切含まない、契約は
     * クラス doc 参照)。{@code playerId} は non-null であること(呼び出し元の4公開メソッドが保証)。
     */
    private double nonEquipmentTotal(UUID playerId, String canonicalKey) {
        double total = perkBuffResolver.buffsFor(playerId).general().getOrDefault(canonicalKey, 0.0);
        total += permanentBuffFor(playerId, canonicalKey);
        total += roleBuffFor(playerId, canonicalKey);
        if (baseStats != null) {
            total += baseStats.stats().getOrDefault(canonicalKey, 0.0);
        }
        return total;
    }

    /** {@link #permanentBuffResolver} は {@link Player} 必須(PDC読み取り)なのでオンラインプレイヤーのみ対象。 */
    private double permanentBuffFor(UUID playerId, String canonicalKey) {
        if (permanentBuffResolver == null) {
            return 0.0;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return 0.0;
        }
        return permanentBuffResolver.buffsFor(player).getOrDefault(canonicalKey, 0.0);
    }

    /**
     * {@link #roleBuffResolver} も {@link Player} 必須(PDCから役職を読む)なのでオンラインプレイヤーのみ
     * 対象(オフライン/未注入時は0、fail-soft)。attack/defense のどちらの節に置かれても拾えるよう両方を
     * 合算する({@link RoleBuffsConfig} は {@link com.trinityforge.stats.StatVocabulary} のような自動
     * チャンネル分類を経由しない、手動区分の生マップのため)。
     */
    private double roleBuffFor(UUID playerId, String canonicalKey) {
        if (roleBuffResolver == null) {
            return 0.0;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return 0.0;
        }
        RoleBuffResolver.Contribution contribution = roleBuffResolver.contributionFor(player);
        return contribution.attackBuffs().getOrDefault(canonicalKey, 0.0)
                + contribution.defenseBuffs().getOrDefault(canonicalKey, 0.0);
    }

    /** Optional registration helper so forks can discover totals via ServicesManager. */
    public void registerService(org.bukkit.plugin.Plugin plugin) {
        try {
            plugin.getServer().getServicesManager().register(
                    ArsNativeBridge.class, this, plugin, org.bukkit.plugin.ServicePriority.Normal);
            LOG.info("[ArsNativeBridge] registered service for Ars Magic perk totals");
        } catch (RuntimeException ex) {
            LOG.warning("[ArsNativeBridge] service registration failed: " + ex.getMessage());
        }
    }
}
