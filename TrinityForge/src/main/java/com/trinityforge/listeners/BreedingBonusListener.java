package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.stats.StatKeys;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 繁殖系のstat consumer(3件): {@code breeding_vanilla_exp_bonus}(+{@code vanilla_exp_bonus})の
 * バニラEXP上乗せ、{@code breeding_extra_child_chance}の追加子供1体抽選、
 * {@code bred_animal_growth_bonus}の子供成長時間短縮。breeder(繁殖させたプレイヤー)がいない
 * (放牧トラップ等、非プレイヤー起因の繁殖)ケースはEXP/追加子供とも対象外。
 */
public final class BreedingBonusListener implements Listener {

    /** breeding_vanilla_exp_bonus+vanilla_exp_bonus が0でも0extraになる基準量。 */
    private static final int BASE_BREED_EXP = 5;
    private static final String VANILLA_EXP_BONUS = StatKeys.canonical("vanilla_exp_bonus");
    private static final String BREEDING_VANILLA_EXP_BONUS = StatKeys.canonical("breeding_vanilla_exp_bonus");
    private static final String BREEDING_EXTRA_CHILD_CHANCE = StatKeys.canonical("breeding_extra_child_chance");
    private static final String BRED_ANIMAL_GROWTH_BONUS = StatKeys.canonical("bred_animal_growth_bonus");
    /**
     * 成長にかかる残り時間の下限(元の何倍まで縮められるか)。速度に直すと {@code ×10} が上限。
     * 2026-08-21 以前の「短縮率 90% が上限」と同じ天井を、倍率の語彙で言い直したもの。
     */
    private static final double MIN_REMAINING_FRACTION = 0.1;

    private final PlayerStatAggregator aggregator;

    public BreedingBonusListener(PlayerStatAggregator aggregator) {
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        applyGrowthBonus(event);

        if (!(event.getBreeder() instanceof Player breeder) || excluded(breeder)) return;
        var totals = aggregator.aggregate(breeder);

        double expBonus = totals.totalOf(VANILLA_EXP_BONUS) + totals.totalOf(BREEDING_VANILLA_EXP_BONUS);
        if (expBonus > 0.0) {
            int extraExp = (int) Math.round(BASE_BREED_EXP * expBonus);
            if (extraExp > 0) {
                breeder.giveExp(extraExp);
            }
        }

        double extraChildChance = totals.totalOf(BREEDING_EXTRA_CHILD_CHANCE);
        if (extraChildChance > 0.0
                && ThreadLocalRandom.current().nextDouble() < Math.min(1.0, extraChildChance)) {
            spawnExtraChild(event);
        }
    }

    /**
     * 生まれた子供の成長を {@code bred_animal_growth_bonus} ぶん速める。
     *
     * <h2>2026-08-21: 「残り時間を bonus ぶん短縮」から<b>本物の倍率</b>へ直した</h2>
     * ユーザー質問「成長効率25%って1.25倍の速度で成長する認識でいい？」への回答が
     * <b>作物と動物で食い違っていた</b>のが発端。旧実装は {@code 残り × (1 - bonus)} で、
     * +25% は「残り時間 −25%」＝<b>速度 1.333 倍</b>だった(1.25 倍ではない)。
     * さらに {@code min(bonus, 0.9)} で頭打ちなので<b>90% を超えて書いても無意味</b>だった。
     *
     * <p>今は {@code 残り ÷ (1 + bonus)}。子供の成長は「1tickにつき age が1進む」だけの
     * <b>一定速度のカウントダウン</b>なので、生まれた瞬間に残りをこの比で割ることが
     * 成長速度 {@code ×(1 + bonus)} と<b>数学的に等価</b>になる ── +25% で文字どおり 1.25 倍。
     * {@code planted_crop_growth_bonus}(作物)と同じ意味になった。
     *
     * <p>安全弁として残り時間の下限だけは残す({@link #MIN_REMAINING_FRACTION})。
     * 速度に直すと {@code ×10} が上限で、旧実装の「90% 短縮」と同じ天井。
     *
     * <p>{@code getAge()} は子供のとき<b>負</b>(0 で成体)なので、絶対値が小さくなる＝
     * 残り時間が減る。掛け算の向きに注意。
     */
    private void applyGrowthBonus(EntityBreedEvent event) {
        if (!(event.getEntity() instanceof Ageable child) || !(event.getBreeder() instanceof Player breeder)
                || excluded(breeder)) {
            return;
        }
        if (child.getAge() >= 0) return; // 既に成体、または成長タイマーなし
        double bonus = Math.max(0.0, aggregator.aggregate(breeder).totalOf(BRED_ANIMAL_GROWTH_BONUS));
        if (bonus <= 0.0) return;
        child.setAge((int) Math.round(child.getAge() * remainingFraction(bonus)));
    }

    /**
     * 成長にかかる残り時間が元の何倍になるか。{@code 1 / (1 + bonus)}(＝速度 {@code ×(1 + bonus)})。
     *
     * <p>純粋関数にしてあるのは、テストが「+25% = 1.25 倍」という<b>意味そのもの</b>を
     * 固定できるようにするため(MockBukkit は繁殖イベントを再現できない)。
     */
    static double remainingFraction(double bonus) {
        if (!(bonus > 0.0) || !Double.isFinite(bonus)) {
            return 1.0;
        }
        return Math.max(MIN_REMAINING_FRACTION, 1.0 / (1.0 + bonus));
    }

    /** child と同種の追加ベビーを1体、同じ場所にスポーンする。 */
    private void spawnExtraChild(EntityBreedEvent event) {
        LivingEntity child = event.getEntity();
        Location location = child.getLocation();
        Entity extra = location.getWorld().spawnEntity(location, child.getType());
        if (extra instanceof Ageable extraAgeable) {
            extraAgeable.setBaby();
        }
    }

    private static boolean excluded(Player player) {
        GameMode gm = player.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }
}
