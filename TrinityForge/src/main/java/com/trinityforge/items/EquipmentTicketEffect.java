package com.trinityforge.items;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * 「対象選択GUIから装備1点へ効果を適用する消費アイテム」の効果本体
 * ({@link EquipmentTicketGui} が共有する契約)。ランダムステータス再抽選券
 * ({@code stat_reroll_ticket})・品質レベルアップ券({@code quality_upgrade_ticket})の2実装がある。
 *
 * <p>実装クラスは Bukkit イベント/GUI 座標を一切知らない — {@link EquipmentTicketGui} 側が
 * 「対象を選ばせる/2クリックで確定する/券を消費する」という共通の作法を担当し、
 * ここには「その装備に効果が適用できるか」「適用結果」「表示文言」だけを書く。
 */
public interface EquipmentTicketEffect {

    /** {@code items/catalog.yml} 上のこの券自身のID。 */
    String catalogId();

    /** GUIのヘッダに出す見出し。 */
    String title();

    /**
     * この装備に効果を適用できるか。{@code null}/空気/PDCを持たないスタックは呼び出し側
     * ({@link EquipmentTicketGui}) が事前に弾くので、実装側は「効果固有の条件」だけを見ればよい。
     */
    boolean eligible(ItemStack stack);

    /** GUI上のプレビュー行(現在値→変化後の値)。lore に追記される。 */
    List<Component> previewLore(ItemStack stack);

    /**
     * 効果を適用した結果を返す。{@code stack} は既に呼び出し側が clone 済みの安全な作業コピー。
     * 適用できない場合(直前の再確認で条件を満たさなくなった等)は {@link Optional#empty()} を返す —
     * この場合 {@link EquipmentTicketGui} は券を消費しない。
     */
    Optional<ItemStack> apply(ItemStack stack);

    /** 適用成功時にプレイヤーへ出すチャットメッセージ。 */
    String appliedMessage();
}
