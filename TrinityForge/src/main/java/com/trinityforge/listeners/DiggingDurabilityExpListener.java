package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.DiggingGimmickConfig;
import com.trinityforge.digging.DiggingDurabilityExpPolicy;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * 切削(DIGGING)C-1/C-2「消費したシャベルの耐久値の総量に応じてバニラ/職業経験値UP」の累積トラッカー
 * (2026-07-25、digging.yml C-1={@code feature:digging-durability-vanilla-exp}最大50% /
 * C-2={@code feature:digging-durability-job-exp}最大25%)。
 *
 * <p><b>シャベル判定</b>: アイテムの {@code use-skill}(PDC、{@link ItemData#useSkill()})が
 * {@code DIGGING} のものだけを対象にする。材質からの推測は行わない(過去に除去済みの誤った実装
 * パターン)。
 *
 * <p><b>永続化方針</b>: 累積耐久消費量はプレイヤーPDC({@link PdcKeys#PLAYER_DIGGING_DURABILITY_ACCUM}、
 * long)に保存する。ログアウト/サーバー再起動を跨いで維持され(Bukkitのプレイヤーデータファイルに
 * 永続化される)、複数のシャベルを使い分けても合算される「プレイヤー単位の生涯累積量」として扱う
 * 設計(DB/メモリのみの案は再起動でリセットされてしまい「累計」の語感に反するため不採用)。
 *
 * <p>EXPボーナスへの実際の適用は本リスナーの外(バニラEXP={@code NativeSurvivalPerkListener}
 * 相当の {@code PlayerExpChangeEvent} 経路、職業EXP={@code NativeExperienceDispatcher} の
 * job-exp-multiplier resolver)が {@link #vanillaExpBonusFraction}/{@link #jobExpBonusFraction} を
 * 呼んで行う — 本クラスは「累積 + 参照」のみを担い、EXP付与そのものには関与しない。
 */
public final class DiggingDurabilityExpListener implements Listener {

    private static final String DIGGING_SKILL_ID = "DIGGING";
    private static final String EFFECT_VANILLA_EXP = "digging-durability-vanilla-exp";
    private static final String EFFECT_JOB_EXP = "digging-durability-job-exp";

    private final DedicatedEffectsConfig dedicatedEffects;
    private final DiggingGimmickConfig gimmickConfig;

    public DiggingDurabilityExpListener(DedicatedEffectsConfig dedicatedEffects, DiggingGimmickConfig gimmickConfig) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return;
        if (ItemData.of(meta).useSkill().filter(DIGGING_SKILL_ID::equalsIgnoreCase).isEmpty()) return;
        if (event.getDamage() <= 0) return;

        Player player = event.getPlayer();
        long current = player.getPersistentDataContainer()
                .getOrDefault(PdcKeys.PLAYER_DIGGING_DURABILITY_ACCUM, PersistentDataType.LONG, 0L);
        player.getPersistentDataContainer().set(
                PdcKeys.PLAYER_DIGGING_DURABILITY_ACCUM, PersistentDataType.LONG, current + event.getDamage());
    }

    /**
     * バニラEXP({@code PlayerExpChangeEvent}、{@code NativeSurvivalPerkListener#onVanillaExpGain}と
     * 同じ全バニラXP源一括捕捉イベント)へC-1のボーナスを乗せる。既存の{@code vanilla_exp_bonus}
     * (常時)/{@code kill_vanilla_exp_bonus}(キル固有)とは完全に独立した別レイヤーとして加算される
     * (このクラスはそれらのstatに一切触れないため二重適用の心配はない)。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVanillaExpGain(PlayerExpChangeEvent event) {
        int amount = event.getAmount();
        if (amount <= 0) return;
        double bonus = vanillaExpBonusFraction(event.getPlayer());
        if (bonus <= 0.0) return;
        event.setAmount((int) Math.round(amount * (1.0 + bonus)));
    }

    /** バニラEXPボーナス(fraction、C-1)。ノード未保持なら常に0。 */
    public double vanillaExpBonusFraction(Player player) {
        if (player == null) return 0.0;
        OptionalDouble tier = dedicatedEffects.valueMax(player, EFFECT_VANILLA_EXP);
        if (tier.isEmpty()) return 0.0;
        int t = (int) tier.getAsDouble();
        long accumulated = accumulatedDurability(player);
        return DiggingDurabilityExpPolicy.bonusFraction(
                accumulated, gimmickConfig.durabilityPerPercentForVanillaExp(t), gimmickConfig.vanillaExpCapPercent(t));
    }

    /** 職業(スキル)EXPボーナス(fraction、C-2)。ノード未保持なら常に0。 */
    public double jobExpBonusFraction(Player player) {
        if (player == null) return 0.0;
        OptionalDouble tier = dedicatedEffects.valueMax(player, EFFECT_JOB_EXP);
        if (tier.isEmpty()) return 0.0;
        int t = (int) tier.getAsDouble();
        long accumulated = accumulatedDurability(player);
        return DiggingDurabilityExpPolicy.bonusFraction(
                accumulated, gimmickConfig.durabilityPerPercentForJobExp(t), gimmickConfig.jobExpCapPercent(t));
    }

    private long accumulatedDurability(Player player) {
        return player.getPersistentDataContainer()
                .getOrDefault(PdcKeys.PLAYER_DIGGING_DURABILITY_ACCUM, PersistentDataType.LONG, 0L);
    }
}
