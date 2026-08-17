package com.trinityforge.mobs;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TF追加ドロップを EliteMobs の共有戦利品テーブル(emloot、need/greed)へ流す soft bridge
 * (2026-08-09、「複数名で潜入したときのダンジョンドロップを emloot で回収したい」)。
 * {@link EliteMobsInstanceBridge} と同じ作法で、TF は EliteMobs にコンパイル依存しないため
 * すべてリフレクション経由でアクセスし、すべての失敗モードを「引き取らなかった」へ握り潰す。
 *
 * <p><b>呼び出し側の契約。</b> TF追加ドロップを積むリスナーは
 * {@code event.getDrops().add(stack)} を直接呼ばず {@link #deliver(EntityDeathEvent, ItemStack)}
 * を通す。共有テーブルが引き取れば地面へは落ちず、60秒の need/greed を経て当選者1人へ渡る。
 * 引き取らなければ従来どおり {@link EntityDeathEvent#getDrops()} へ積まれる。
 *
 * <p><b>引き取られる条件は EliteMobs 側が持つ</b>({@code TrinityForgeSharedLoot#offerDungeonLoot}):
 * エリートモブであること・ダメージ寄与者が2人以上いること・そのうち誰かがインスタンス化ダンジョン
 * の中にいること。ソロやフィールドの共闘では常に {@code false} が返るので、この橋は実質
 * 「複数人ダンジョン専用」になる。
 *
 * <p><b>フォーク側の名前を変えたら here も直すこと。</b> 参照はすべて文字列なので、
 * {@link #SHARED_LOOT_CLASS} / {@link #OFFER_METHOD} が実体とずれても<b>コンパイルは通り、
 * 実行時に無言で fail-soft(地面へ落とす)へ戻る</b>。フォーク側にはこの結線を固定する
 * バイトコードテストがある。
 */
public final class EliteMobsSharedLootBridge {

    private static final String PLUGIN_NAME = "EliteMobs";
    private static final String SHARED_LOOT_CLASS =
            "com.magmaguy.elitemobs.trinityforge.TrinityForgeSharedLoot";
    private static final String OFFER_METHOD = "offerDungeonLoot";
    private static final String OFFER_PER_PLAYER_METHOD = "offerPerPlayerLoot";

    private static final Logger LOG = Logger.getLogger(EliteMobsSharedLootBridge.class.getName());

    private EliteMobsSharedLootBridge() {
    }

    /**
     * TF追加ドロップを1スタック配る。共有戦利品テーブルが引き取れなかった場合は
     * {@link EntityDeathEvent#getDrops()} へ積む(＝従来の挙動)。
     *
     * @return {@code true} = 共有戦利品テーブルが引き取った(地面には落ちない)。
     */
    public static boolean deliver(EntityDeathEvent event, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (offer(event.getEntity(), stack)) {
            return true;
        }
        event.getDrops().add(stack);
        return false;
    }

    /**
     * 進行アイテム1スタックを<b>ダメージ寄与者全員に1個ずつ</b>配る(2026-08-18)。
     * 引き取り条件は {@link #deliver} と同じ(エリートモブ・寄与者2人以上・インスタンス化ダンジョン)で、
     * 違うのは分配の仕方だけ — need/greed の抽選を通さず全員に渡す。
     *
     * <p><b>なぜ分けるのか。</b> 共有戦利品テーブルは1スタックにつき当選者を1人しか選ばない。
     * スレッドのようなランダム報酬ならそれで正しいが、ダンジョン印や試練の鍵のように
     * <b>全員が1個ずつ持っていないと先へ進めない/図鑑が埋まらない</b>ものを同じ経路に乗せると、
     * 複数人で潜った瞬間に片方が詰む。どのドロップがこちらへ来るかは
     * {@link MobDropRoller#isProgressionDrop(double)} が決める。
     *
     * @return {@code true} = EliteMobs 側が配り終えた(地面には落ちない)。
     */
    public static boolean deliverToEveryDamager(EntityDeathEvent event, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (offerPerPlayer(event.getEntity(), stack)) {
            return true;
        }
        event.getDrops().add(stack);
        return false;
    }

    /**
     * 共有戦利品テーブルへ差し出すだけの下位API(呼び出し側でフォールバック先を決めたいとき用)。
     * EliteMobs が入っていない・クラスが読めない・反射呼び出しが失敗した場合は {@code false}。
     */
    public static boolean offer(Entity entity, ItemStack stack) {
        return invoke(OFFER_METHOD, entity, stack);
    }

    /** 全員配布へ差し出すだけの下位API。条件を満たさなければ {@code false}。 */
    public static boolean offerPerPlayer(Entity entity, ItemStack stack) {
        return invoke(OFFER_PER_PLAYER_METHOD, entity, stack);
    }

    private static boolean invoke(String methodName, Entity entity, ItemStack stack) {
        if (entity == null || stack == null || Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) == null) {
            return false;
        }
        try {
            Class<?> sharedLoot = Class.forName(SHARED_LOOT_CLASS);
            Method offer = sharedLoot.getMethod(methodName, Entity.class, ItemStack.class);
            Object result = offer.invoke(null, entity, stack);
            return result instanceof Boolean taken && taken;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            LOG.log(Level.FINE, "[elitemobs-shared-loot] " + methodName + " failed for " + stack.getType(), ex);
            return false;
        }
    }
}
