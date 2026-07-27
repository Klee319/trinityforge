package com.trinityforge.config;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/**
 * ポーションエフェクトID解決の共有ユーティリティ。現行語彙(Registry.EFFECTのnamespaced key)を優先しつつ、
 * pre-1.20.5 Bukkit時代のレガシー名({@code FAST_DIGGING}→{@code haste}等)もフォールバックとして解決する。
 * 元は {@code CraftingFeaturesConfig#resolvePotionEffectType} にあったロジックを共有先へ抽出したもの。
 */
public final class PotionEffectTypes {

    private PotionEffectTypes() {
    }

    /**
     * 指定された名前(現行名/レガシー名/namespaced key、大文字小文字問わず)から {@link PotionEffectType} を
     * 解決する。どの経路でも解決できない場合は {@code null} を返す(呼び出し側でwarning+skipのfail-safeを行う)。
     */
    public static PotionEffectType resolve(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        String lower = typeName.trim().toLowerCase(Locale.ROOT);
        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(lower));
        if (type != null) {
            return type;
        }
        // Pre-1.20.5 Bukkit names
        String aliased = switch (lower) {
            case "fast_digging" -> "haste";
            case "slow_digging" -> "mining_fatigue";
            case "slow" -> "slowness";
            case "confusion" -> "nausea";
            case "damage_resistance" -> "resistance";
            case "increase_damage" -> "strength";
            case "heal" -> "instant_health";
            case "harm" -> "instant_damage";
            case "jump" -> "jump_boost";
            default -> null;
        };
        if (aliased != null) {
            type = Registry.EFFECT.get(NamespacedKey.minecraft(aliased));
            if (type != null) {
                return type;
            }
        }
        return PotionEffectType.getByName(typeName.trim());
    }
}
