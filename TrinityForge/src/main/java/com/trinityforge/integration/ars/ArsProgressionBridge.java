package com.trinityforge.integration.ars;

import com.trinityforge.TrinityForge;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.progression.UseRequirementResolver;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Map;

/** Public integration facade used by TrinityForge and the ArsPaper fork to grant native EXP. */
public final class ArsProgressionBridge {

    public static final String ARS_MAGIC = "ARS_MAGIC";
    public static final String ARS_SMITHING = "ARS_SMITHING";

    private ArsProgressionBridge() {
    }

    public static void grantSkillExp(Plugin plugin, Player player, String skillType, double amount) {
        if (amount <= 0.0 || player == null || skillType == null || skillType.isBlank()) return;
        TrinityForge tf = TrinityForge.getInstance();
        if (tf == null || tf.experienceDispatcher() == null) return;
        try {
            tf.experienceDispatcher().grant(player.getUniqueId(), skillType, amount);
        } catch (RuntimeException ex) {
            // plugin は呼び出し元(ArsPaperフォーク等)から渡されるだけで未検証。null なら
            // plugin.getLogger() 自体がNPEになり境界を越えて例外が漏れるため、その場合は
            // Bukkitのグローバルロガーへフォールバックする(fail-open)。
            java.util.logging.Logger logger = plugin != null ? plugin.getLogger() : org.bukkit.Bukkit.getLogger();
            logger.warning("Failed to grant " + skillType + " EXP: " + ex.getMessage());
        }
    }

    public static void grantSmithingExp(Plugin plugin, Player player, double amount) {
        grantSkillExp(plugin, player, ARS_SMITHING, amount);
    }

    /**
     * Ars gear creation EXP, calculated from the finished item's live use requirement.
     *
     * <p>The base amount and the per-use-level multiplier both live in {@code stats/skill-exp.yml};
     * the ArsPaper ritual path and Bukkit workbench path call this same method so neither can drift
     * back to a fixed hard-coded grant.
     */
    public static void grantSmithingCraftExp(Plugin plugin, Player player, ItemStack result) {
        grantSmithingCraftExp(plugin, player, result, java.util.List.of());
    }

    /**
     * Ars鍛冶(儀式)EXPを<b>消費素材ごと</b>に config から引いて付与する (U1/N6, 2026-08-01)。
     *
     * <p><b>これまで何が起きていたか</b>: 儀式経路は {@code ars-smithing.exp-per-craft} の定額だけを
     * 見ており、素材表({@code smithing.exp-per-material})は作業台経路
     * ({@link com.trinityforge.listeners.CraftQualityListener})にしか繋がっていなかった。
     * そのため「ネザライト級の素材を溶かす儀式」も「石を並べる儀式」も同じEXPだった。
     *
     * <p><b>表は作業台と共用する</b>。素材1個あたりの価値を2箇所で二重管理すると必ずずれるため、
     * 儀式専用の表は<b>作らない</b>。トークンの語彙も同じ({@code IRON_INGOT} / {@code custom:<id>})。
     *
     * <p><b>合計が0のときは定額へ戻す</b>。作業台経路では「表に無い素材は無報酬」が
     * 解体ループ対策のゲートとして意図的だが、儀式素材は Ars 側の materials.yml 由来のものが多く、
     * 表に無いだけで儀式EXPが丸ごと消えると「jarだけ新しいサーバで儀式が無報酬になる」回帰になる。
     * よって儀式では 0 を「未設定」とみなし、従来どおり {@code ars-smithing.exp-per-craft} を使う。
     *
     * @param materialTokens 消費した素材のトークン({@code IRON_INGOT} / {@code custom:<id>})。
     *                       1個につき1要素(同じ素材2個なら2要素)。空なら定額のまま。
     */
    public static void grantSmithingCraftExp(Plugin plugin, Player player, ItemStack result,
                                             Collection<String> materialTokens) {
        TrinityForge tf = TrinityForge.getInstance();
        if (tf == null || tf.config() == null) {
            return;
        }
        SkillExpConfig skillExp = tf.config().skillExp();
        double fromMaterials = sumMaterialExp(materialTokens, skillExp.smithingExpPerMaterial());
        double base = fromMaterials > 0.0 ? fromMaterials : skillExp.arsSmithingExpPerCraft();
        grantSmithingExpForResult(plugin, player, result, base);
    }

    /**
     * 素材トークン列 → 素材表の合計値。表が空、トークンが空、表に無いトークンはいずれも 0 を積む
     * (「未設定の素材は無報酬」が表の設計意図なので、暗黙の既定値を出してはいけない)。
     */
    public static double sumMaterialExp(Collection<String> materialTokens,
                                        Map<String, Double> perMaterial) {
        if (materialTokens == null || materialTokens.isEmpty()
                || perMaterial == null || perMaterial.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (String token : materialTokens) {
            if (token == null || token.isBlank()) continue;
            Double value = perMaterial.get(SkillExpConfig.normalizeMaterialToken(token));
            if (value != null && Double.isFinite(value)) {
                total += value;
            }
        }
        return total;
    }

    /**
     * 素材トークン: TFカタログ品は {@code custom:<catalogId>}、ArsPaper のカスタム品は
     * {@code custom:<arsId>}、どちらでもなければ Material 名。
     *
     * <p><b>Ars 側の刻印({@code arspaper:custom_item_id})も読むことが必須</b> —— 出荷の
     * {@code smithing.exp-per-material} にある {@code custom:} 行はほとんどが ArsPaper の
     * materials.yml 由来のID(source_gem / magebloom_fiber / hard_metal …)で、TFカタログのPDCしか
     * 見ていなかった頃はそれらが<b>1行も引けず、常に0EXP</b>だった。
     * 二重読み取りは {@link CrossPluginItemResolver#idOf} に既にあるのでそれを使う
     * (ここで {@code arspaper} 名前空間を再実装しない)。
     */
    public static String materialToken(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return "";
        }
        return CrossPluginItemResolver.idOf(stack)
                .filter(id -> !id.isBlank())
                .map(id -> SkillExpConfig.normalizeMaterialToken("custom:" + id))
                .orElseGet(() -> stack.getType().name());
    }

    /**
     * Variant for production recipes that author their own base EXP (for example catalog anvil
     * combines). The finished-item use-level multiplier is still the shared {@code ars-smithing}
     * setting, so a custom base does not bypass tier scaling.
     */
    public static void grantSmithingExpForResult(
            Plugin plugin, Player player, ItemStack result, double baseAmount) {
        if (player == null || result == null || result.getType().isAir()
                || !Double.isFinite(baseAmount) || baseAmount <= 0.0) {
            return;
        }
        TrinityForge tf = TrinityForge.getInstance();
        if (tf == null || tf.config() == null) {
            return;
        }
        try {
            int useLevel = UseRequirementResolver.resolve(result, tf.config().itemStats())
                    .map(UseRequirementResolver.Resolved::level)
                    .orElse(0);
            double multiplier = tf.config().skillExp()
                    .useLevelExpMultiplier(SkillId.ARS_SMITHING, useLevel);
            double amount = baseAmount * multiplier;
            if (Double.isFinite(amount) && amount > 0.0) {
                grantSmithingExp(plugin, player, amount);
            }
        } catch (RuntimeException ex) {
            java.util.logging.Logger logger =
                    plugin != null ? plugin.getLogger() : org.bukkit.Bukkit.getLogger();
            logger.warning("Failed to calculate ARS_SMITHING craft EXP: " + ex.getMessage());
        }
    }

    /**
     * Ars由来の討伐・ブロック破壊EXPへ戦闘ワールド倍率を適用して付与する。
     *
     * <p>倍率適用を {@link #grantSkillExp} 側に置かないのは、他の戦闘EXP経路が既に自分で倍率を
     * 掛けてからそちらを呼ぶため。共通側に置くと二重適用になる。
     */
    public static void grantMagicExp(Plugin plugin, Player player, double amount) {
        grantSkillExp(plugin, player, ARS_MAGIC, amount * worldExpRate(player));
    }

    /** 対象ワールドの戦闘スキルEXP倍率。プラグイン未起動やワールド不明のときは1.0。 */
    private static double worldExpRate(Player player) {
        TrinityForge tf = TrinityForge.getInstance();
        if (tf == null || player == null || player.getWorld() == null) {
            return 1.0;
        }
        return tf.config().skillExp()
                .worldExpRate(tf.dungeonWorldRegistry().isDungeonWorld(player.getWorld().getUID()));
    }
}
