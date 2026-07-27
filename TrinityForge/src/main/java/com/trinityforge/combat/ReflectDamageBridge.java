package com.trinityforge.combat;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 課題2 (2026-07-25, ユーザー決定): 棘の鎧を守備カテゴリの反射率ステータスへ作り直す。バニラの
 * {@link Enchantment#THORNS} レベル(装備4部位の合計)を {@code reflect-percent}(反射率（割）)へ
 * {@code 10% × レベル} 寄与させる — {@link EnchantmentStatBridge}(攻撃側) /
 * {@link DefenseEnchantmentBridge}(課題1, 防護系)と同じ「vanilla enchant → TF stat」ブリッジパターンの
 * 守備側 第2弾。
 *
 * <p>バニラ本来の耐久値消費(装備1部位がランダムに2ポイント減る)は、この寄与とは完全に無関係な NMS 側の
 * 副作用であり、{@code CombatListener} がバニラの {@code THORNS} 原因イベントを {@code setCancelled(true)}
 * するタイミングより前に既に確定している(そのイベント自体がその副作用の"結果"として生成される)ため、
 * このクラスやそのキャンセルには一切影響されない — 「バニラの耐久値消費と引き換えの仕様は維持したまま」
 * という要求を満たす。
 */
public final class ReflectDamageBridge {

    /** 棘の鎧 1レベルにつき反射率（割）へ寄与する割合(ユーザー決定: 10%×レベル)。 */
    private static final double THORNS_PERCENT_PER_LEVEL = 0.10;

    private ReflectDamageBridge() {
    }

    /** 装備4部位の {@link Enchantment#THORNS} レベル合計(未装備/エンチャント無しは0)。 */
    public static int wornThornsLevel(LivingEntity living) {
        if (living == null) {
            return 0;
        }
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack piece : equipment.getArmorContents()) {
            if (piece == null || piece.getType().isAir() || !piece.hasItemMeta()) {
                continue;
            }
            ItemMeta meta = piece.getItemMeta();
            if (meta == null) {
                continue;
            }
            total += Math.max(0, meta.getEnchantLevel(Enchantment.THORNS));
        }
        return total;
    }

    /** 棘の鎧レベル合計 × 10% — 反射率（割）への寄与分。 */
    public static double thornsPercentContribution(LivingEntity living) {
        return wornThornsLevel(living) * THORNS_PERCENT_PER_LEVEL;
    }
}
