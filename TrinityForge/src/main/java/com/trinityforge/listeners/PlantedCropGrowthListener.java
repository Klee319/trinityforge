package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.farming.FarmingCropCatalog;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code planted_crop_growth_bonus} consumer: プレイヤーが植えた作物の所有権をメモリ上に記録し、
 * <b>バニラが1段階成長するたびに追加の段階を上乗せする</b>。
 *
 * <h2>2026-08-21: 「2秒ごとの抽選」から<b>本物の倍率</b>へ作り直した</h2>
 * ユーザー質問「成長効率25%って1.25倍の速度で成長する認識でいい？」への回答が
 * <b>「いいえ」</b>だったのが発端。旧実装は 40tick(2秒)ごとに {@code min(1, bonus)} の確率で
 * {@code age+1} する<b>独立したポーリング</b>で、バニラの成長速度とは何の関係も無かった:
 *
 * <ul>
 *   <li>+25% で 1段階あたり平均8秒 = 小麦(7段階)が<b>約56秒</b>で成熟。
 *       バニラは最良条件でも1段階に2分前後なので、体感は 1.25 倍ではなく<b>十数倍</b>だった。</li>
 *   <li>{@code min(1.0, bonus)} で頭打ちなので<b>100% を超えても速くならない</b>
 *       (2秒ごとに確実に+1 = 小麦14秒が上限)。percent 表示なのに上限があるのも実体と食い違っていた。</li>
 *   <li>水分・光量・農地の質といったバニラ側の条件を<b>一切見ていなかった</b>ので、
 *       悪条件の畑ほどボーナスの比重が上がるという逆転も起きていた。</li>
 * </ul>
 *
 * <p><b>今の実装</b>: {@link BlockGrowEvent}(＝バニラがランダムtickで1段階成長させた瞬間)に相乗りし、
 * {@link #extraStages} が返すぶんだけ<b>追加で</b>段階を進める。1回の成長イベントで進む段階の期待値が
 * {@code 1 + bonus} になるので、<b>成長速度そのものが {@code ×(1 + bonus)} になる</b> ——
 * +25% なら文字どおり 1.25 倍。上限は無い(2.0 と書けば 3 倍になる)。
 * 水分・光量などバニラ側の条件は自動的に効く(バニラが成長しない状況では倍率も掛からない)。
 *
 * <p>{@code BlockGrowEvent} の {@code newState} は<b>イベント後に place される</b>ので、
 * {@code MONITOR} で書き換えれば反映される(稼働サーバの {@code paper-1.21.11} の
 * {@code CraftEventFactory#handleBlockGrowEvent} を逆アセンブルして確認: イベントが
 * キャンセルされなければ {@code CraftBlockState#place} が呼ばれる)。
 * 追加ぶんは自前で書き込むだけなので {@code BlockGrowEvent} を再発火せず、連鎖しない。
 *
 * <p><b>重要: 所有権は完全にin-memory/セッション限定</b>。サーバ再起動・リロードで失われる
 * (再起動後は誰の追加成長ボーナスも適用されなくなるだけで、作物自体やバニラ成長には影響しない)。
 * 永続化(PDC等)は意図的に行っていない — この機能はplaced-block trackerのような恒久追跡ではなく、
 * 「セッション中に植えた本人の作物を優遇する」程度のプレイ体験ボーナスという位置づけ。
 */
public final class PlantedCropGrowthListener implements Listener {

    private static final String PLANTED_CROP_GROWTH_BONUS = StatKeys.canonical("planted_crop_growth_bonus");

    /**
     * 迷子エントリの掃除だけを行う間隔。<b>成長はここでは一切扱わない</b>
     * (成長は {@link BlockGrowEvent} に相乗りする)。
     *
     * <p>破壊は {@link #onBreak} で外れるが、ピストン・水流・爆発・WorldEdit のように
     * {@link BlockBreakEvent} を伴わない消え方があるので、緩い間隔で拾い直す。
     */
    private static final long PRUNE_INTERVAL_TICKS = 200L;

    private final Plugin plugin;
    private final PlayerStatAggregator aggregator;

    /** location(world+x+y+z)キー文字列 -> 植えたプレイヤーのUUID。境界: 破壊/成熟時に必ず除去する。 */
    private final Map<String, UUID> ownedCrops = new ConcurrentHashMap<>();

    public PlantedCropGrowthListener(Plugin plugin, PlayerStatAggregator aggregator) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        Bukkit.getScheduler().runTaskTimer(this.plugin, this::pruneStale,
                PRUNE_INTERVAL_TICKS, PRUNE_INTERVAL_TICKS);
    }

    /**
     * バニラの1段階成長に<b>上乗せする</b>段階数。
     *
     * <p>{@code bonus} の整数部はそのまま足し、小数部は確率で1段階足す。こうすると
     * 1回の成長イベントで進む段階の期待値が {@code 1 + bonus} になり、
     * <b>成長速度が {@code ×(1 + bonus)} になる</b>(これが「成長効率」の定義)。
     *
     * <p>例: {@code bonus = 0.25} なら 25% の確率で +1 → 期待値 1.25 段階 → 1.25 倍。
     * {@code bonus = 2.0} なら常に +2 → 期待値 3 段階 → 3 倍。<b>上限は設けない</b>
     * (旧実装の {@code min(1.0, bonus)} は「100% 以上書いても無意味」という
     * 表示と食い違う頭打ちを作っていた)。
     *
     * @param roll {@code [0, 1)} の一様乱数。テストから決め打ちできるよう引数で受ける。
     */
    static int extraStages(double bonus, double roll) {
        if (!(bonus > 0.0) || !Double.isFinite(bonus)) {
            return 0;
        }
        int whole = (int) Math.floor(bonus);
        double fraction = bonus - whole;
        return roll < fraction ? whole + 1 : whole;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        trackPlanted(event.getBlock(), event.getPlayer());
    }

    /**
     * この作物を「そのプレイヤーが植えたもの」として追跡に載せる。
     *
     * <p><b>2026-08-17 (ユーザー報告「自動植えつけの作物と自分で植えた作物で成長速度が違う」)</b>:
     * 以前は {@link BlockPlaceEvent} からしか登録していなかった。自動植え直し(auto-replant /
     * area-harvest)はコードから直接ブロックを置くので {@code BlockPlaceEvent} が発火せず、
     * <b>自動で植えた作物にだけ成長ボーナスが乗っていなかった</b>。
     * 植え直し経路からもここを呼ぶこと。
     *
     * <p>植えた時点でボーナスが 0 の人は<b>登録しない</b>(全プレイヤーの全作物を追跡すると
     * メモリが際限なく増えるため)。副作用として、<b>後からノードを取っても既に植わっている
     * 作物には乗らない</b> —— 次に植えたぶんから効く。
     */
    public void trackPlanted(Block block, Player player) {
        if (block == null || player == null) return;
        if (!FarmingCropCatalog.isCrop(block.getType())) return;
        double bonus = aggregator.aggregate(player).totalOf(PLANTED_CROP_GROWTH_BONUS);
        if (bonus <= 0.0) return;
        ownedCrops.put(key(block), player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        ownedCrops.remove(key(event.getBlock()));
    }

    /**
     * バニラが1段階成長させた瞬間に、植えた本人のボーナスぶんを上乗せする。
     *
     * <p>ボーナスは<b>植えた本人の「今の」ステータス</b>から読む(収穫者ではない)。
     * 本人がオフラインなら 0 として扱う ── 装備やパークを解決できないため。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        Block block = event.getBlock();
        if (!FarmingCropCatalog.isCrop(block.getType())) return;

        String key = key(block);
        UUID ownerId = ownedCrops.get(key);
        if (ownerId == null) return;

        BlockState newState = event.getNewState();
        if (!(newState.getBlockData() instanceof Ageable ageable)) {
            ownedCrops.remove(key); // 作物でなくなった → 追跡打ち切り
            return;
        }
        int max = ageable.getMaximumAge();
        if (ageable.getAge() >= max) {
            ownedCrops.remove(key); // このバニラ成長で成熟する → もう追跡不要
            return;
        }

        Player owner = Bukkit.getPlayer(ownerId);
        double bonus = owner == null ? 0.0 : aggregator.aggregate(owner).totalOf(PLANTED_CROP_GROWTH_BONUS);
        int extra = extraStages(bonus, ThreadLocalRandom.current().nextDouble());
        if (extra <= 0) return;

        ageable.setAge(Math.min(max, ageable.getAge() + extra));
        // newState はイベント後に place されるので、ここで書き戻せば反映される。
        newState.setBlockData(ageable);
        if (ageable.getAge() >= max) {
            ownedCrops.remove(key);
        }
    }

    /** 消えた/成熟した/別ブロックになった作物を追跡から外す。成長には関与しない。 */
    private void pruneStale() {
        if (ownedCrops.isEmpty()) return;
        ownedCrops.entrySet().removeIf(entry -> {
            Block block = fromKey(entry.getKey());
            if (block == null) {
                return true; // world不明 → 追跡打ち切り
            }
            // 未ロードチャンクを getType()/getBlockData() で強制ロードしない(オフライン所有者や遠方の
            // 作物が溜まると同期チャンクロードでサーバ負荷になる)。ロードされるまでスキップし追跡は維持。
            if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                return false;
            }
            if (!FarmingCropCatalog.isCrop(block.getType())
                    || !(block.getBlockData() instanceof Ageable ageable)) {
                return true; // 別ブロックに変わった → 追跡打ち切り
            }
            return ageable.getAge() >= ageable.getMaximumAge(); // 成熟済み → もう不要
        });
    }

    private static String key(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private Block fromKey(String key) {
        String[] parts = key.split(":", 4);
        if (parts.length != 4) return null;
        var world = Bukkit.getWorld(UUID.fromString(parts[0]));
        if (world == null) return null;
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return world.getBlockAt(x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
