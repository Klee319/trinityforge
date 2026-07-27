package com.trinityforge.farming;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code auto-replant}の「植え直しと収穫が同時(=種消費はdropから1個相殺)」を計算する純ロジック。
 * 実際の{@link org.bukkit.inventory.ItemStack}ではなく素の{@link DropStack}record上で計算するため
 * Bukkitサーバ不要で単体テストできる(呼び出し側でItemStack⇔DropStackを変換する)。
 */
public final class DropAdjustment {

    private DropAdjustment() {
    }

    /** {@link org.bukkit.inventory.ItemStack}の素データ版(Material + 個数)。 */
    public record DropStack(Material material, int amount) {
        public DropStack {
            if (amount < 0) {
                throw new IllegalArgumentException("amount must be >= 0 (was " + amount + ")");
            }
        }
    }

    /**
     * {@code drops}の中から{@code seedMaterial}に一致する最初のスタックを1個分減らした新しいリストを返す
     * ({@code drops}自体は変更しない、コーディング規約の不変性方針に従う)。減算後に個数が0になった
     * スタックはリストから除外する。
     *
     * <p>一致するスタックが無い場合(フォーチュン等で種のdropが0本だった稀なケース)や
     * {@code seedMaterial}が{@code null}(未知の作物Material)の場合は、{@code drops}をそのまま返す
     * (要調整: 減らせないなら減らさず、植え直し自体は行う設計)。
     */
    public static List<DropStack> subtractOne(List<DropStack> drops, Material seedMaterial) {
        if (seedMaterial == null) {
            return List.copyOf(drops);
        }
        List<DropStack> result = new ArrayList<>(drops.size());
        boolean subtracted = false;
        for (DropStack stack : drops) {
            if (!subtracted && stack.material() == seedMaterial && stack.amount() > 0) {
                subtracted = true;
                int newAmount = stack.amount() - 1;
                if (newAmount > 0) {
                    result.add(new DropStack(stack.material(), newAmount));
                }
                continue;
            }
            result.add(stack);
        }
        return List.copyOf(result);
    }
}
