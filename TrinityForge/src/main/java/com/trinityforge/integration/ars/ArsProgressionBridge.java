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
     * <p><b>表は儀式専用</b>({@code ars-smithing.exp-per-material})。2026-08-17 まで作業台の
     * {@code smithing.exp-per-material} を共用していたが、editor で通常鍛冶の素材リストを
     * 編集すると Ars 側のEXPまで動いてしまうため分離した。トークンの語彙は同じ
     * ({@code IRON_INGOT} / {@code custom:<id>})。
     *
     * <p><b>定額へのフォールバックは無い</b>(2026-08-17 に {@code ars-smithing.exp-per-craft} を
     * 機能ごと廃止)。定額があった間は「1つでも表に無い素材があれば合計を捨てて定額へ戻す」という
     * 全か無かの分岐が必要で、そのせいで<b>素材を1つ足すとEXPが100分の1に落ちる</b>向きの
     * 不整合が実際に出ていた({@code binder_spear} が 100 → 1。同格の {@code binder_sword} は
     * 全素材が表に無いおかげで 100 のまま、という食い違い)。定額が無ければ部分カバーは
     * 「その素材ぶんが乗らないだけ」の単調な挙動になるので、分岐そのものが不要になる。
     *
     * <p>出荷データが常に全カバーであることは
     * {@code ShippedRitualMaterialExpCoverageTest} が保証する。
     *
     * @param materialTokens 消費した素材のトークン({@code IRON_INGOT} / {@code custom:<id>})。
     *                       1個につき1要素(同じ素材2個なら2要素)。空なら素材ぶんは 0。
     */
    public static void grantSmithingCraftExp(Plugin plugin, Player player, ItemStack result,
                                             Collection<String> materialTokens) {
        grantSmithingCraftExp(plugin, player, result, materialTokens, 0);
    }

    /**
     * 消費ソース量ぶんの追加EXPつき (2026-08-04 ユーザー要望)。
     *
     * <p>素材表または定額で決まった値に {@code consumedSource × ars-smithing.exp-per-source} を
     * <b>足してから</b>付与する。別途 {@code grantSkillExp} を2回呼ぶ形にはしない ―
     * 逓減ウィンドウ({@code daily-diminishing} / レベル逓減)が2回進み、
     * 「1回の儀式なのに2回目だけ減衰した端数が乗る」という追いにくい挙動になるため。
     *
     * @param consumedSource この儀式で実際に消費したソース量。0 以下ならソース項は付かない
     *                       (作業台経路など、そもそもソースを使わない呼び出しは 0 を渡す)。
     */
    public static void grantSmithingCraftExp(Plugin plugin, Player player, ItemStack result,
                                             Collection<String> materialTokens, int consumedSource) {
        TrinityForge tf = TrinityForge.getInstance();
        if (tf == null || tf.config() == null) {
            return;
        }
        SkillExpConfig skillExp = tf.config().skillExp();
        // ⚠️ 2026-08-17 (ユーザー確定): 表は【儀式専用の ars-smithing.exp-per-material】を引く。
        // 以前は作業台と同じ smithing.exp-per-material を共用していたので、editor で通常鍛冶の
        // 素材リストを編集すると Ars 側まで動いていた。
        //
        // 定額(ars-smithing.exp-per-craft)へのフォールバックも同時に廃止した。定額があったせいで
        // 「1つでも表に無い素材があれば合計を捨てて定額へ戻す」という全か無かの分岐が必要で、
        // 素材を1つ足すとEXPが100分の1に落ちる向きの不整合が実際に出ていた(binder_spear 100→1)。
        // 定額が無ければ部分カバーは「その素材ぶんが乗らないだけ」の単調な挙動になるので、
        // 分岐そのものが不要になる。儀式EXPは「消費ソースぶん + 素材ぶん」の2項だけで決まる。
        double base = sumMaterialExp(materialTokens, skillExp.arsSmithingExpPerMaterial());
        base += sourceExp(consumedSource, skillExp.arsSmithingExpPerSource());
        grantSmithingExpForResult(plugin, player, result, base);
    }

    /**
     * 消費ソース量ぶんの追加EXP。負の消費量・非有限な係数はいずれも 0 を返す
     * (儀式側が返す値をそのまま信用せず、EXPが減る向きの寄与を作らない)。
     */
    public static double sourceExp(int consumedSource, double expPerSource) {
        if (consumedSource <= 0 || !Double.isFinite(expPerSource) || expPerSource <= 0.0) {
            return 0.0;
        }
        return consumedSource * expPerSource;
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
