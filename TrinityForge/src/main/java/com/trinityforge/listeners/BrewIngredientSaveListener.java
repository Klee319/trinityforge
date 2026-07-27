package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Bukkit;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code ingredient_save_chance}(材料節約率, skilltree/alchemy.yml D「素材を消費しない確率UP」)を
 * バニラ醸造台へ配線する。従来はArsPaperの{@code AlchemicalSourcelink}(独自クラフト経由の素材投入)
 * だけがこのstatを読んでおり、バニラの{@link BrewEvent}には誰も反応していなかった
 * (2026-07-25 監査 {@code reports/20260725_SkilltreeNodeTriage.md} B-alpha-1)。
 *
 * <p><b>所有者/自動醸造の扱い</b>: 所有者解決は品質({@link PotionQualityListener})/速度と同じく
 * {@link BrewOwnership} に一本化するが、このstatは自動(ホッパー式)醸造では<strong>一切</strong>
 * ロールしない — 品質/速度のような{@code alchemy.auto_mult}減衰すら適用しない、完全スキップ。
 * 理由: 材料節約はアイテムの複製と等価な効果であり、無人ホッパー周回にどんな倍率であれ乗せてしまうと
 * 放置周回がそのまま複製エンジンになってしまうため(タスク仕様で明示された安全要件)。
 *
 * <p><b>消費キャンセルの実装(重複防止の根拠)</b>: 1.21.11の{@code BrewingStandBlockEntity#doBrew}
 * は、材料スロット(index 3)の
 * {@code ItemStack}参照を先に{@code itemstack}へ捕捉した<em>上で</em> {@link BrewEvent} を発火し、
 * 呼び出し先がキャンセルしなければ<strong>同一メソッド呼び出しの中で同期的に</strong>
 * {@code itemstack.shrink(1)} を実行して初めて消費する。{@link BrewEvent#getContents()} は
 * まさにこの醸造台の{@link org.bukkit.inventory.BrewerInventory}であり、
 * {@link org.bukkit.inventory.BrewerInventory#getIngredient()}はCraftBukkitの「ミラー」
 * {@code ItemStack}(コピーではなく同じ内部ハンドルを指すラッパ)を返す。したがってこのハンドラ内で
 * 個数を+1しておけば、直後に走る{@code shrink(1)}と厳密に相殺され「消費されない」結果になる —
 * {@link com.trinityforge.skilltree.runtime.NativeCombatPerkListener#onShoot} が
 * {@code ammo_save_chance} に対して {@code EntityShootBowEvent#getConsumable()} へ同じテクニックを
 * 使っているのと同一の手法をBrewEventへ適用したもの。
 *
 * <p>この方式は完全に同期(1回のイベントディスパッチの中で完結)であり、「次tickまで待って
 * 書き戻す」ような方式と違って、その間にプレイヤーが素材を引き抜いて複製する「窓」が原理的に
 * 存在しない — タスク仕様が要求する複製防止はこの同期性そのものから得られる。
 *
 * <p><b>優先度が HIGHEST でなければならない理由(複製バグの実例)</b>: 上記の相殺は「この後バニラが
 * 必ず{@code shrink(1)}する」ことが前提であり、<strong>誰かがこの後に{@link BrewEvent}をキャンセル
 * すると+1だけが残って純粋な増殖になる</strong>。TF内には実際にキャンセルする
 * {@link BrewUnlockListener#onBrew}(未解放のゲート付きTF醸造レシピを弾く)が存在し、しかも
 * こちらの方が登録順が後(={@code TrinityForge}での登録位置が下)であるため、同じ HIGH に置くと
 * 「+1 → キャンセル → shrinkされない」の順で毎周回1個ずつ増える無限増殖装置になっていた
 * (2026-07-26 のレビューで検出・修正)。よってこのハンドラは
 * <strong>キャンセル判定がすべて終わった後の {@code HIGHEST}</strong> で、かつ
 * {@code ignoreCancelled = true} で走らせる。MONITOR はBukkitの契約上キャンセル禁止なので、
 * HIGHEST より後にキャンセルされる経路は残らない。
 * <br>下限側の制約もある: {@link NativeSkillExperienceListener#onBrew} が MONITOR で所有者PDCを
 * 消去するため、MONITOR まで下げることはできない。HIGHEST はこの上下の制約を同時に満たす唯一の点。
 *
 * <p><b>確率スケール</b>: {@code ingredient_save_chance}は{@link com.trinityforge.stats.PercentStatNormalize}
 * の{@code RATE_KEYS}に登録済みのため、config側で{@code 15}と書いても{@code 0.15}(フラクション)へ
 * 矯正されて集計に載る。この集計フラクション値は{@link com.trinityforge.combat.CritResolver}/
 * {@code ammo_save_chance}({@link com.trinityforge.skilltree.runtime.NativeCombatPerkListener})と
 * 同じ「乱数と直接比較」で消費する — 分母100で割る{@link com.trinityforge.mining.MiningGimmickPolicy#percentRoll}
 * は使わない(そちらは既にフラクション化された値を渡すと二重に100分の1へ縮小してしまう食い違いが
 * 見つかったため、意図的に避けた。詳細はタスク報告を参照)。
 */
public final class BrewIngredientSaveListener implements Listener {

    private static final String INGREDIENT_SAVE_CHANCE = StatKeys.canonical("ingredient-save-chance");

    private final PlayerStatAggregator aggregator;
    private final BrewOwnership brewOwnership;

    public BrewIngredientSaveListener(Plugin plugin, PlayerStatAggregator aggregator) {
        Objects.requireNonNull(plugin, "plugin");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.brewOwnership = new BrewOwnership(plugin);
    }

    /**
     * 優先度は {@code HIGHEST} 固定(クラスjavadoc「優先度が HIGHEST でなければならない理由」参照)。
     * 下げると{@link NativeSkillExperienceListener#onBrew}のMONITORでの所有者PDC消去に間に合わず、
     * 上げると{@link BrewUnlockListener}のキャンセルより前に+1してしまい素材が増殖する。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        if (!(event.getBlock().getState() instanceof BrewingStand stand)) {
            return;
        }
        // 自動(ホッパー)醸造には一切ロールさせない(クラスjavadoc「所有者/自動醸造の扱い」参照)。
        if (brewOwnership.isAutomated(stand)) {
            return;
        }
        Optional<UUID> ownerId = brewOwnership.ownerOf(stand);
        if (ownerId.isEmpty()) {
            return;
        }
        Player owner = Bukkit.getPlayer(ownerId.get());
        if (owner == null) {
            return;
        }
        double chance = Math.max(0.0, Math.min(1.0,
                aggregator.aggregate(owner).totalOf(INGREDIENT_SAVE_CHANCE)));
        if (chance <= 0.0) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }

        ItemStack ingredient = event.getContents().getIngredient();
        if (ingredient == null || ingredient.getType().isAir()) {
            return;
        }
        // クラスjavadoc「消費キャンセルの実装」参照: +1しておけば、この直後にバニラが行う
        // shrink(1)と相殺され、実質的に消費されない。
        ingredient.setAmount(ingredient.getAmount() + 1);
    }
}
