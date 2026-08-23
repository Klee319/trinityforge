package com.trinityforge.skilltree.runtime;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.mobs.MobDropRoller;
import com.trinityforge.pdc.MobData;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Survival / reward perk consumers (2026-07-23 stat-gate-overhaul §2 移行B): reads the装備+perk合算
 * ({@link PlayerStatAggregator#aggregate}) instead of the old perk-only {@link NativePerkRewardResolver}.
 */
public final class NativeSurvivalPerkListener implements Listener {

    private static final String HEALTH_REGEN_BONUS = StatKeys.canonical("health_regen_bonus");
    private static final String HUNGER_SAVE_CHANCE = StatKeys.canonical("hunger_save_chance");
    private static final String MOB_DROP_BONUS = StatKeys.canonical("mob_drop_bonus");
    private static final String VANILLA_EXP_BONUS = StatKeys.canonical("vanilla_exp_bonus");
    private static final String KILL_VANILLA_EXP_BONUS = StatKeys.canonical("kill_vanilla_exp_bonus");

    private final PlayerStatAggregator aggregator;

    public NativeSurvivalPerkListener(PlayerStatAggregator aggregator) {
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        double bonus = aggregator.aggregate(player).totalOf(HEALTH_REGEN_BONUS);
        if (bonus <= 0.0) return;
        event.setAmount(event.getAmount() * (1.0 + bonus));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getFoodLevel() >= player.getFoodLevel()) return; // only drains
        double chance = aggregator.aggregate(player).totalOf(HUNGER_SAVE_CHANCE);
        if (chance <= 0.0) return;
        if (ThreadLocalRandom.current().nextDouble() < Math.min(0.9, chance)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeathDrops(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        // プレイヤーの死亡(PlayerDeathEventはEntityDeathEventのサブクラスなのでここにも来る)を
        // 倍率対象にすると、被害者の持ち物がそのまま増える=アイテム複製になる。
        // mob_drop_bonus/kill_vanilla_exp_bonus はモブ討伐報酬なのでPvPでは一切効かせない。
        if (entity instanceof Player) return;
        Player killer = entity.getKiller();
        if (killer == null) return;
        var totals = aggregator.aggregate(killer);

        // アイテムドロップ倍率とEXP倍率は独立に計算する: mob_drop_bonus が0でも
        // vanilla_exp_bonus/kill_vanilla_exp_bonus のみでEXPブーストが効くようにするため、
        // どちらか一方が0でも早期returnしない(旧実装はmob_drop_bonus<=0で丸ごとreturnしていた)。
        double dropMultAdd = totals.totalOf(MOB_DROP_BONUS);
        double dropBonus = MobDropRoller.clampBonus(dropMultAdd);
        if (dropBonus > 0.0 && !carriesPlayerFillableStorage(entity)) {
            // モブの装備欄由来(プレイヤーが持たせた/モブが拾った)のスタックは戦利品ではないので
            // 倍率から除外する。ゾンビ等の拾得アイテムも装備スロットに入るため、装備欄の突合せで両方賄える。
            // 専用収納を持つモブ(アレイ/ピグリン等)は突合せ自体が成立しないので
            // carriesPlayerFillableStorage で丸ごと対象外にしてある(同メソッドの javadoc 参照)。
            List<ItemStack> exclusions = equipmentExclusions(entity);
            List<ItemStack> drops = new ArrayList<>(event.getDrops());
            event.getDrops().clear();
            for (ItemStack drop : drops) {
                if (drop == null || drop.getType().isAir()) continue;
                if (consumeExclusion(exclusions, drop)) {
                    event.getDrops().add(drop);
                    continue;
                }
                ItemStack copy = drop.clone();
                copy.setAmount(scaleAmount(copy.getAmount(), dropBonus,
                        copy.getMaxStackSize(), ThreadLocalRandom.current().nextDouble()));
                event.getDrops().add(copy);
            }
        }

        // vanilla_exp_bonus(常時)は onVanillaExpGain(PlayerExpChangeEvent)側で全バニラXP源に一括適用する。
        // ここで加えるとドロップXPをオーブ回収時に二重適用してしまうため、キル固有分のみ乗せる。
        double expMultAdd = totals.totalOf(KILL_VANILLA_EXP_BONUS);
        double expFactor = 1.0 + Math.max(0.0, expMultAdd);
        if (expFactor > 1.0) {
            event.setDroppedExp((int) Math.round(event.getDroppedExp() * expFactor));
        }
    }

    /**
     * ドロップ増加ステを<b>レアなバニラドロップ(1個か0個しか出ないもの)</b>へ効かせる
     * (2026-08-24 ユーザー指示「落ちなかった分を再抽選する」)。
     *
     * <p><b>なぜ別ハンドラなのか。</b> {@link #onDeathDrops} の個数加算は「既に落ちたスタックを増やす」
     * 仕組みなので、ウィザースケルトンの頭・ネザースター・カカシの棒のような
     * <b>抽選に外れると 0 個で現れない</b>ドロップには構造的に効かない
     * (0 に何を足しても 0 のまま)。そこでモブの loot table をもう一度引き直し、
     * <b>今回落ちなかった種類だけ</b>を追加する。落ちた種類は個数加算側の担当なので触らない。
     *
     * <p><b>優先度を {@code NORMAL} にしてある理由。</b> {@code VanillaItemRemovalListener} と
     * {@code MobLevelTableListener} の削除は {@code HIGH} で走る。ここを {@code MONITOR} にすると
     * <b>削除された種類が「落ちなかった種類」に見えて復活する</b>ので、必ず削除より前に置くこと。
     *
     * <p><b>EliteMobs のモブは対象外。</b> 取り込んだモブの戦利品は EliteMobs 側の
     * {@code EliteDropPolicy} が可否を決めているので、TF が loot table を引き直すと
     * その判断を無効化してしまう。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDeathRerollAbsentDrops(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) return;
        Player killer = entity.getKiller();
        if (killer == null) return;
        MobData data = MobData.of(entity);
        if (data.profileId().isPresent() || data.dungeonTheme().isPresent()) return;
        if (!(entity instanceof Lootable lootable)) return;
        LootTable table = lootTableOf(lootable);
        if (table == null) return;
        double dropBonus = MobDropRoller.clampBonus(aggregator.aggregate(killer).totalOf(MOB_DROP_BONUS));
        int rerolls = MobDropRoller.extraCount(dropBonus, ThreadLocalRandom.current().nextDouble());
        if (rerolls <= 0) return;

        LootContext context = new LootContext.Builder(entity.getLocation())
                .lootedEntity(entity)
                .killer(killer)
                .build();
        for (int i = 0; i < rerolls; i++) {
            Collection<ItemStack> rerolled;
            try {
                rerolled = table.populateLoot(ThreadLocalRandom.current(), context);
            } catch (RuntimeException | LinkageError ex) {
                // loot table を引けない実装(テストダブル等)でも死亡処理を壊さない。
                // 例外を投げると EntityDeathEvent の残りのハンドラが丸ごと落ちる。
                return;
            }
            event.getDrops().addAll(absentTypes(event.getDrops(), rerolled));
        }
    }

    /**
     * モブの loot table。取れない実装(テストダブル、loot table を持たないモブ)では {@code null}。
     * 例外を外へ出さないのは、ここで投げると {@code EntityDeathEvent} の残りのハンドラが
     * 丸ごと落ちる(＝EXP付与や他のドロップ処理まで消える)ため。
     */
    private static LootTable lootTableOf(Lootable lootable) {
        try {
            return lootable.hasLootTable() ? lootable.getLootTable() : null;
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /**
     * {@code rerolled} のうち {@code existing} に<b>1個も入っていない材質</b>のスタックだけを返す。
     * 引き直しの中での重複も潰す(同じ再抽選で同じ材質が2度出ても1つしか足さない)。
     *
     * <p>純関数にしてあるのは、{@code populateLoot} が MockBukkit 未実装で
     * テストが SKIPPED に化けても<b>判定そのものは検証できるようにする</b>ため。
     */
    static List<ItemStack> absentTypes(List<ItemStack> existing, Collection<ItemStack> rerolled) {
        Set<Material> present = new HashSet<>();
        for (ItemStack stack : existing) {
            if (stack == null || stack.getType().isAir()) continue;
            present.add(stack.getType());
        }
        List<ItemStack> added = new ArrayList<>();
        for (ItemStack stack : rerolled) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) continue;
            if (!present.add(stack.getType())) continue;
            added.add(stack);
        }
        return added;
    }

    /**
     * ドロップ増加ステをバニラドロップへ効かせる。<b>2026-08-13 の仕様変更で乗算から加算になった。</b>
     *
     * <p>ユーザー指示の新仕様は「1個固定のドロップは確率を上げる／個数がランダムなものは個数を足す」。
     * バニラドロップは {@code EntityDeathEvent#getDrops()} に<b>抽選済みの結果しか無く</b>、
     * 元の chance も min/max も復元できないので、<b>確率を上げる方の規則は適用できない</b>。
     * したがってここは常に個数を足す側で扱う(+50% なら50%の確率で+1、+150% なら確定+1と50%でもう+1)。
     *
     * <p>旧実装は個数への乗算だったので、32個スタックに +100% を盛ると +32 個だった。
     * 新仕様は +1 個。この弱体化は意図したもの(ユーザー指示)。
     *
     * @param roll 0.0以上1.0未満の乱数。テストのために引数化している。
     */
    static int scaleAmount(int baseAmount, double dropBonus, int maxStackSize, double roll) {
        // 2026-08-09: 実装は MobDropRoller へ移した。TF追加ドロップ側でも同じ整数化が
        // 必要になり、2か所に同じ式を置くと片方だけ直す事故が起きるため。
        return MobDropRoller.cappedCount(baseAmount + MobDropRoller.extraCount(dropBonus, roll), maxStackSize);
    }

    /**
     * 「プレイヤーが中身を詰められる収納を持つモブ」か。真なら<b>そのモブのドロップには倍率を一切
     * 適用しない</b>。
     *
     * <p><b>2026-08-03 実サーバ報告「アレイ等に意図的に持たせたアイテムが増える」の真因。</b>
     * 2026-08-02 の修正は {@code entity instanceof InventoryHolder} なら {@code getInventory()} の
     * 中身を除外リストに積む、というものだった。<b>これは実サーバでは常に空リストになる。</b>
     * Minecraft 1.21.11 の死亡処理は
     * <pre>
     *   LivingEntity.dropAllDeathLoot():
     *       dropEquipment()          // ← Allay はここで inventory.removeAllItems() し、MAINHAND も空にする
     *       dropFromLootTable()
     *       dropCustomDeathLoot()    // ← Piglin はここで inventory.removeAllItems() する
     *       CraftEventFactory.callEntityDeathEvent(...)   // ← EntityDeathEvent はここでやっと発火
     * </pre>
     * の順で、<b>収納が空にされたあとで</b> {@link EntityDeathEvent} が飛ぶ。つまりイベント中に
     * {@code getInventory()} を読んでも必ず空で、除外は 1 件も成立しない。
     * 通常の装備スロットが読めるのは CraftBukkit が {@code LivingEntity.clearEquipmentSlots} で
     * <em>クリアを死亡イベントの後ろへ遅延させている</em>からで、Allay/Piglin の収納クリアは
     * その遅延の対象外である(Allay の MAINHAND クリアも遅延対象外)。
     * MockBukkit はこのバニラ順序を再現しないため、旧修正の単体テストは緑のまま実サーバだけが
     * 壊れていた。
     *
     * <p>そこで「イベント時の読み取り」に頼るのをやめ、<b>収納持ちモブは丸ごと倍率の対象外</b>にする。
     * 状態を持たない判定で複製経路が原理的に消える。失うのは
     * {@code InventoryHolder} モブの自然ドロップへの倍率だけだが、該当するのは
     * アレイ/ピグリン/村人/行商人/ピリジャー(いずれも自然ドロップ無し)と
     * ウマ系(革0-2)/オウムガイ程度なので実害が無い。
     */
    static boolean carriesPlayerFillableStorage(LivingEntity entity) {
        return entity instanceof InventoryHolder;
    }

    /**
     * 装備スロット(手・オフハンド・防具)の中身を倍率除外リストとして返す。
     *
     * <p>プレイヤーが持たせたアイテムも、モブが地面から拾ったアイテムも、Bukkit上では装備スロットに入る。
     * これらは {@code EntityDeathEvent#getDrops()} に戦利品と混ざって現れるため、突合せて除外しないと
     * 「渡した装備が倍率で増える」＝アイテム複製になる。
     *
     * <p>装備スロットに限ってこの読み取りが成立するのは、CraftBukkit が
     * {@code Mob.dropCustomDeathLoot} のスロットクリアを {@code clearEquipmentSlots} フラグで
     * <b>死亡イベントの後ろへ遅延</b>させているため(耐久ランダム化も同じスタック実体に効くので
     * {@code isSimilar} は一致する)。専用収納({@code InventoryHolder})はこの遅延の対象外なので、
     * そちらは読み取りではなく {@link #carriesPlayerFillableStorage} による丸ごと除外で守る。
     */
    static List<ItemStack> equipmentExclusions(LivingEntity entity) {
        List<ItemStack> exclusions = new ArrayList<>();
        var equipment = entity.getEquipment();
        if (equipment != null) {
            for (ItemStack item : new ItemStack[]{
                    equipment.getItemInMainHand(), equipment.getItemInOffHand(),
                    equipment.getHelmet(), equipment.getChestplate(),
                    equipment.getLeggings(), equipment.getBoots()}) {
                if (item != null && !item.getType().isAir()) exclusions.add(item.clone());
            }
        }
        return exclusions;
    }

    /**
     * {@code drop} が除外リストに載っていれば1件だけ消費して true を返す。
     *
     * <p>1件ずつ消費するのは、同じ材質が「装備1個＋戦利品1個」で落ちるとき
     * (骨を落とすスケルトンが弓を装備している等)に、戦利品側まで除外しないため。
     */
    private static boolean consumeExclusion(List<ItemStack> exclusions, ItemStack drop) {
        for (int i = 0; i < exclusions.size(); i++) {
            ItemStack candidate = exclusions.get(i);
            if (candidate.isSimilar(drop) && candidate.getAmount() == drop.getAmount()) {
                exclusions.remove(i);
                return true;
            }
        }
        return false;
    }

    /**
     * 常時バニラEXPブースト({@code vanilla_exp_bonus})を全てのバニラXP獲得源に一括適用する。
     * {@link PlayerExpChangeEvent} はオーブ回収・かまど精錬・釣り・取引・エンチャント瓶など
     * バニラXPの増加を一点で捕捉する。{@code Player#giveExp} 直接付与は本イベントを発火しないため、
     * TF独自付与(破壊/繁殖の合成EXP等)とは二重適用にならない。キル固有({@code kill_vanilla_exp_bonus})は
     * ドロップXP側で別途適用済みで、ここでは常時分のみを乗せる。
     * {@code PlayerExpChangeEvent} は Cancellable でないため ignoreCancelled は付けない。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVanillaExpGain(PlayerExpChangeEvent event) {
        int amount = event.getAmount();
        if (amount <= 0) return;
        double bonus = aggregator.aggregate(event.getPlayer()).totalOf(VANILLA_EXP_BONUS);
        if (bonus <= 0.0) return;
        event.setAmount((int) Math.round(amount * (1.0 + bonus)));
    }
}
