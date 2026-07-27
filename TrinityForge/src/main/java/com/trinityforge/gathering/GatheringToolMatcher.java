package com.trinityforge.gathering;

import com.trinityforge.active.ActivationDispatcher;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * 採取ギミック(一括破壊 / 一括伐採 / 範囲収穫)の「今持っている道具はその採取スキルの道具か」判定。
 *
 * <p><b>2026-07-27 新設の背景</b>: 従来の判定はスキルごとにばらばらだった —
 * 一括伐採だけが {@code WoodcuttingMaterials.isAxe(マテリアル)} を見ており、
 * <b>一括破壊(採掘)と範囲収穫(農業)には判定が一切無かった</b>。TF は伐採用の斧
 * ({@code use-skill: WOODCUTTING}) と戦闘用の斧 ({@code HEAVY_WEAPONS}) を別ラインで出荷しているのに
 * 判定側がそれを読んでいなかったため、<b>戦闘用の斧でも一括伐採が発動</b>し、
 * <b>素手や杖でも一括破壊・範囲収穫が発動</b>していた。
 *
 * <p><b>判定規則</b>(ユーザー決定 2026-07-27「use-skill 判定へ移行＋バニラ製も許可」):
 * <ol>
 *   <li>TF の {@code use-skill} PDC タグがあるアイテムは<b>タグが全て</b>。伐採斧は WOODCUTTING、
 *       戦闘斧は HEAVY_WEAPONS なので、戦闘斧では一括伐採が発動しなくなる。</li>
 *   <li>タグが無い素のバニラ道具は<b>マテリアルから採取用途を推論して許可</b>する。
 *       素のダイヤの斧で一括伐採できる従来の遊び方を壊さないため。</li>
 * </ol>
 *
 * <p><b>{@code stats.UseSkillDefaults} を使わない理由</b>: あちらは「装備ゲート用」の推論で、
 * {@code _AXE} を <b>HEAVY_WEAPONS</b> に落とす(バニラの斧は武器として扱う、という別の目的の規則)。
 * それをそのまま採取判定に流用すると<b>素の斧で一括伐採ができなくなる</b>。採取の文脈では
 * 「斧＝伐採道具」が正しいので、推論表をここに分けて持つ。
 *
 * <p>魔法(Ars)による破壊は {@code listeners.SpellBreakGuard} が別途すべて弾くので、
 * ここでは扱わない(スペルをピッケルにバインドすればこの判定は通ってしまうため、両方が必要)。
 */
public final class GatheringToolMatcher {

    public static final String MINING = "MINING";
    public static final String WOODCUTTING = "WOODCUTTING";
    public static final String DIGGING = "DIGGING";
    public static final String FARMING = "FARMING";

    private GatheringToolMatcher() {
    }

    /**
     * @param mainHand       メインハンドのアイテム(null / 空気 = 素手)
     * @param gatheringSkill {@link #MINING} などの採取スキル id
     * @return そのギミックを発動してよい道具を持っているか
     */
    public static boolean matches(ItemStack mainHand, String gatheringSkill) {
        if (gatheringSkill == null) {
            return false;
        }
        Material material = (mainHand == null) ? null : mainHand.getType();
        return gatheringSkill.equals(resolve(ActivationDispatcher.mainHandUseSkill(mainHand), material));
    }

    /**
     * タグ優先・マテリアル推論フォールバックの解決。Bukkit 非依存の純関数(単体テスト用)。
     *
     * @return 解決された採取スキル id。採取道具として解釈できないときは {@code null}
     */
    static String resolve(String taggedUseSkill, Material material) {
        if (taggedUseSkill != null && !taggedUseSkill.isBlank()) {
            return taggedUseSkill.trim().toUpperCase(Locale.ROOT);
        }
        return inferFromMaterial(material);
    }

    /** 素のバニラ道具のマテリアル推論。採取の文脈なので {@code _AXE} は伐採であって武器ではない。 */
    static String inferFromMaterial(Material material) {
        if (material == null) {
            return null;
        }
        String name = material.name().toUpperCase(Locale.ROOT);
        // _PICKAXE を先に見る必要はない("..._PICKAXE" は "_AXE" で終わらない)が、意図を明示しておく。
        if (name.endsWith("_PICKAXE")) {
            return MINING;
        }
        if (name.endsWith("_AXE")) {
            return WOODCUTTING;
        }
        if (name.endsWith("_SHOVEL")) {
            return DIGGING;
        }
        if (name.endsWith("_HOE")) {
            return FARMING;
        }
        return null;
    }
}
