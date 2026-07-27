package com.trinityforge.skilltree.runtime;

import com.trinityforge.stats.UseSkillDefaults;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves conditional armor-set buff stats. Flat stats are handled directly by {@link PerkBuffResolver};
 * this bridge only exists because these effects depend on the number/type of worn armor pieces.
 */
public final class NativeAttributeBridge {

    private static final String LIGHT_MOVE_PER_PIECE = "light_armor_move_speed_per_piece";
    private static final String HEAVY_MOVE_PER_PIECE = "heavy_armor_move_speed_per_piece";
    private static final String LIGHT_SET_MULTIPLIER = "light_armor_set_bonus_multiplier";
    private static final String HEAVY_SET_MULTIPLIER = "heavy_armor_set_bonus_multiplier";
    private static final String LIGHT_SET_DODGE = "light_armor_set_dodge_chance";
    private static final String HEAVY_SET_KNOCKBACK = "heavy_armor_set_knockback_resistance";

    private final PerkBuffResolver perkBuffs;

    public NativeAttributeBridge(PerkBuffResolver perkBuffs) {
        this.perkBuffs = Objects.requireNonNull(perkBuffs, "perkBuffs");
    }

    /**
     * Movement from armor-piece natives: {@code perpiece * worn matching pieces * 0.01}
     * (YAML values are percent-like; 0.5 → +0.5% per piece).
     *
     * <p><b>{@code dodge_chance} consumption path:</b> keys emitted here are consumed on two routes.
     * Vanilla-attribute-shaped keys go through {@link PerkAttributeApplier} /
     * {@link com.trinityforge.stats.AttributeProjection#defaults()}, which has no entry for
     * {@code dodge_chance} (no vanilla attribute exists for it) and skips it. The {@code dodge_chance}
     * key is instead merged by {@code combat.PlayerStatAggregator} into its perk-defense addend, so it
     * reaches the live combat dodge roll ({@code combat.PlayerDefenseResolver} /
     * {@code combat.DodgeResolver}) alongside — never duplicating — {@link PerkBuffResolver}'s
     * {@code dodge_chance} defender buff (that resolver reads only {@code buffs:} sections, this bridge
     * reads only {@code native:} rewards; the sources are disjoint).
     */
    public Map<String, Double> armorAttributesFor(Player player) {
        if (player == null) return Map.of();
        UUID id = player.getUniqueId();
        Map<String, Double> general = perkBuffs.buffsFor(id).general();
        int light = 0;
        int heavy = 0;
        ItemStack[] armor = player.getInventory().getArmorContents();
        if (armor != null) {
            for (ItemStack piece : armor) {
                if (piece == null || piece.getType().isAir()) continue;
                // 2026-07-26: 軽装/重装の振り分けを com.trinityforge.stats.UseSkillDefaults へ一本化。
                // 従来ここは LEATHER_/CHAINMAIL_ だけを軽装とし **金装備を重装扱い**にしていたが、
                // UseSkillDefaults は GOLDEN_ を軽装に入れており、金防具を着ると「レベルゲートは軽装
                // なのにセット効果は重装」という矛盾状態になっていた。
                if (!isArmor(piece.getType())) continue;
                if (UseSkillDefaults.isLightArmor(piece.getType())) light++;
                else heavy++;
            }
        }
        Map<String, Double> out = new HashMap<>();
        double lightMove = general.getOrDefault(LIGHT_MOVE_PER_PIECE, 0.0) * light * 0.01;
        double heavyMove = general.getOrDefault(HEAVY_MOVE_PER_PIECE, 0.0) * heavy * 0.01;
        add(out, "move_speed", lightMove + heavyMove);

        // Set knockback (heavy): requires >= 2 matching heavy pieces; heavyarmor_setamount_add amplifies
        // its own build's bonus only (previously this used Math.max(heavyAmt, lightAmt), letting a
        // light-armor amount perk cross-contaminate a heavy set bonus the wearer never earned).
        double heavyKb = setBonusValue(heavy,
                general.getOrDefault(HEAVY_SET_KNOCKBACK, 0.0),
                general.getOrDefault(HEAVY_SET_MULTIPLIER, 0.0));
        add(out, "knockback_resistance", heavyKb);

        // Set dodge chance (light): mirrors the heavy set-knockback wiring above — requires >= 2 matching
        // light pieces, amplified by lightarmor_setamount_add. Without this, lightarmor_setamount_add had
        // no light-owned base value to amplify and was a dead perk for pure light-armor builds.
        double lightDodge = setBonusValue(light,
                general.getOrDefault(LIGHT_SET_DODGE, 0.0),
                general.getOrDefault(LIGHT_SET_MULTIPLIER, 0.0));
        add(out, "dodge_chance", lightDodge);

        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    /**
     * セット成立に必要な同系統の防具部位数。
     *
     * <p><b>2026-07-26 に 2 → 3 へ引き上げ</b>: 防具枠は4つしかないので、閾値2だと
     * <b>軽装2部位＋重装2部位で light>=2 と heavy>=2 が同時に成立し、軽装セット(回避)と
     * 重装セット(ノックバック耐性)の両方が乗る</b>ハイブリッド二重取りが可能だった。
     * 3にすると 3+3&gt;4 となり、<b>数学的に併用不能</b>になる(片方が3部位ならもう片方は最大1部位)。
     * 「どちらの系統に寄せるか選ばせる」というスキルツリー側の設計意図とも一致する。
     */
    static final int SET_BONUS_MIN_PIECES = 3;

    /**
     * One armor-set bonus: {@code baseValue} applies only once at least
     * {@link #SET_BONUS_MIN_PIECES} matching pieces are worn, scaled up by {@code amplifier}
     * (e.g. a {@code setamount} perk), never down (a negative amplifier floors at 0, matching the
     * existing heavy-set behaviour). Pure/Bukkit-free for unit testing.
     */
    static double setBonusValue(int wornPieces, double baseValue, double amplifier) {
        if (wornPieces < SET_BONUS_MIN_PIECES || baseValue == 0.0) return 0.0;
        return baseValue * (1.0 + Math.max(0.0, amplifier));
    }

    private static boolean isArmor(Material material) {
        String n = material.name().toUpperCase(Locale.ROOT);
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
    }

    private static void add(Map<String, Double> out, String key, double value) {
        if (value == 0.0 || !Double.isFinite(value)) return;
        out.merge(key, value, Double::sum);
    }
}
