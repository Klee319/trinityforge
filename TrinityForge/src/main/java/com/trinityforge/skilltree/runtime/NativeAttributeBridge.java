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
 * Resolves conditional armor-set buff stats: the {@code set-buffs} schema (SKILL_TREE armor-set-buffs
 * migration §1) is resolved by {@link PerkBuffResolver#setBuffsFor} and amplified here by the
 * {@code armor-set-bonus} total stat. This bridge only exists because that effect depends on the
 * number/type of worn armor pieces.
 *
 * <p>2026-07-31: 旧「装備部位数 × 係数」の平坦キー
 * ({@code light_armor_move_speed_per_piece} / {@code heavy_armor_move_speed_per_piece}) を撤去した。
 * 専用の読み出し経路(perk buffs の {@code general} のみ)を持つだけのキーで、同じ効果は
 * {@code set-buffs} の {@code move-speed}(段3/4条件)で表現できるため統合した
 * (light_armor.yml / heavy_armor.yml のノードAが移行先)。
 */
public final class NativeAttributeBridge {

    private static final String ARMOR_SET_BONUS = "armor_set_bonus";
    private static final String LIGHT_ARMOR_SKILL = "LIGHT_ARMOR";
    private static final String HEAVY_ARMOR_SKILL = "HEAVY_ARMOR";

    private final PerkBuffResolver perkBuffs;

    public NativeAttributeBridge(PerkBuffResolver perkBuffs) {
        this.perkBuffs = Objects.requireNonNull(perkBuffs, "perkBuffs");
    }

    /**
     * The {@code set-buffs} contribution of both the {@code light_armor} and {@code heavy_armor} trees
     * (SKILL_TREE armor-set-buffs migration §1), each amplified by {@code 1 + max(0, armor-set-bonus)}
     * (§2). Because the set-buff threshold tiers are 3 and 4 out of 4 armor slots, a light set and a
     * heavy set can never both be active at once (3 + 3 &gt; 4).
     *
     * <p>Keys returned here span multiple channels ({@code move_speed}/{@code knockback_resistance} are
     * ATTRIBUTE; a {@code set-buffs} author may declare ATTACK/DEFENSE/GENERAL keys too) — callers must
     * route each key through {@link com.trinityforge.stats.StatVocabulary#channelOf} rather than assuming
     * a fixed shape.
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
        double amplifier = 1.0 + Math.max(0.0, general.getOrDefault(ARMOR_SET_BONUS, 0.0));
        mergeSetBuffs(out, perkBuffs.setBuffsFor(id, LIGHT_ARMOR_SKILL, light), amplifier);
        mergeSetBuffs(out, perkBuffs.setBuffsFor(id, HEAVY_ARMOR_SKILL, heavy), amplifier);

        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static void mergeSetBuffs(Map<String, Double> out, Map<String, Double> setBuffs, double amplifier) {
        setBuffs.forEach((key, value) -> add(out, key, value * amplifier));
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
