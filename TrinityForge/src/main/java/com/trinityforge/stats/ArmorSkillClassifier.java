package com.trinityforge.stats;

import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Optional;

/**
 * 防具1着が軽装スキルか重装スキルか。被弾EXPの振り分けと、lore の {@code use-skill} 表示を
 * 同じ答えに揃える。
 *
 * <p>2026-08-29 実サーバ報告「ソースジェム装備の表記は軽なのに入る経験値は重」。
 * {@code NativeSkillExperienceListener} は素材名が {@code LEATHER_}/{@code CHAINMAIL_} で
 * なければ全部重装に数えており、ダイヤモンド基材のソースジェム
 * ({@code use-skill: LIGHT_ARMOR}) が重装EXPへ落ちていた。
 *
 * <p>判定順: 個体に刻まれた {@code use-skill} PDC → 無ければ {@link UseSkillDefaults}
 * （金も軽装。2026-07-26 に金の食い違いを一本化した側）。
 */
public final class ArmorSkillClassifier {

    private ArmorSkillClassifier() {
    }

    public static boolean isLight(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (stack.hasItemMeta()) {
            Optional<String> tagged = ItemData.of(stack.getItemMeta()).useSkill();
            if (tagged.isPresent()) {
                String skill = tagged.get().trim().toUpperCase(Locale.ROOT);
                if (SkillId.LIGHT_ARMOR.equals(skill)) {
                    return true;
                }
                if (SkillId.HEAVY_ARMOR.equals(skill)) {
                    return false;
                }
            }
        }
        return UseSkillDefaults.isLightArmor(stack.getType());
    }
}
