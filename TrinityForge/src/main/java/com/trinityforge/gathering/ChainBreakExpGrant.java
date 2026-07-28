package com.trinityforge.gathering;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;

/**
 * 連鎖破壊(一括伐採/一括破壊/範囲収穫)1ブロック分の採取スキルEXP付与口。
 *
 * <p>実装は {@code NativeSkillExperienceListener#grantChainBreak}。ギミック側リスナーが
 * EXP 計算そのものを知らずに済むよう、関数インタフェースとして注入する(未配線=nullなら
 * EXP 付与のみ行われない、という後方互換の逃げ道も残す)。
 *
 * <p><strong>破壊前に呼ぶこと。</strong> 破壊後は {@code block.getType()} が AIR になり、
 * ドロップも取れないので EXP が 0 になる。
 */
@FunctionalInterface
public interface ChainBreakExpGrant {

    /**
     * @param drops この破壊で出るドロップ({@code block.getDrops(tool, player)} 相当)。EXP 表の
     *              {@code drop_sum} モードがこれを読む。
     */
    void grant(Player player, Block block, Collection<ItemStack> drops, ItemStack tool);
}
