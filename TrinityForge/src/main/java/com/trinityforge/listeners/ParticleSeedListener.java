package com.trinityforge.listeners;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.progression.ParticleEffectService;
import com.trinityforge.progression.SpecialRewardService;
import com.trinityforge.stats.ParticleSeedLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * パーティクルシードの付与 + 発動 (2026-07-23-stat-gate-overhaul §6.1)。
 *
 * <p><b>付与は金床</b>(2026-08-25 / W-214、実サーバ報告「パーティクルシードを作業台で付与できない。
 * 金床形式にしたい」)。左スロットに道具/武器、右スロットに解放済みシードの {@code seed-item} を置くと、
 * 結果スロットにシードを刻印した道具が出る。
 *
 * <p><b>作業台をやめた理由</b>: 旧実装は {@code PrepareItemCraftEvent} で結果枠を差し替えるだけで、
 * 素材の消費を「バニラのクラフト盤面が勝手にやってくれる」ことに頼っていた。
 * だが盤面に一致するレシピが存在しない以上、そこはバニラにとって<b>ただの空の結果</b>であり、
 * 取り出しの成否は経路(通常クリック/シフト/統合版クライアントの逐次配置)ごとに変わる。
 * さらに作業台には {@code CatalogWorkbenchListener} / {@code CatalogCraftGateListener} という
 * 「カタログ品が盤面に乗ったら結果を消す」ガードが同じ {@code HIGH} で並んでおり、
 * どちらが先に走るかは登録順に依存する ── TF の武器・道具はほぼ全部カタログ品なので、
 * <b>ここを直しても「たまたま動く配置」に戻るだけ</b>だった。
 *
 * <p>金床は入力2枠・結果1枠が構造として固定されており、取り出しも
 * {@link #onAnvilResultTake} で<b>こちらが全部やる</b>(キャンセルして自前で消費・付与する)ので、
 * バニラの推測が入り込む余地が無い。{@code CatalogAnvilListener} と同じ土俵になる。
 *
 * <p><b>取り出しをバニラに任せない理由</b>: バニラの金床は「素材の消費数」を自分で計算した
 * {@code repairItemCountCost} で決める。プラグインが後から結果を差し込んだ場合その値は 0 のままなので、
 * バニラの取り出し処理は<b>右スロットのスタックを丸ごと消す</b>。シード素材はブレイズパウダー等の
 * バニラ材で、64 個まとめて持っているのが普通なので、1 個の付与で 64 個消える事故になる。
 *
 * <p>発動 (2026-07-23 仕様§6.1準拠・verifier指摘⑨): シード刻印済みの道具で {@link BlockBreakEvent}
 * (ブロック破壊時) または {@link EntityDamageByEntityEvent}(攻撃時) を起こすたびに、刻印された
 * パーティクルを発生させる。頻度が高いため、プレイヤー毎に {@link #THROTTLE_MILLIS} の軽いスロットルを掛ける。
 */
public final class ParticleSeedListener implements Listener {

    /** 発動トリガーの軽いスロットル(5tick分)。頻度の高いブロック破壊/攻撃連打でのパーティクル過多を防ぐ。 */
    private static final long THROTTLE_MILLIS = 5L * 50L;

    /** 金床の結果スロット(生スロット番号)。 */
    static final int ANVIL_RESULT_SLOT = 2;

    /**
     * 付与にかかる経験値レベル。{@code CatalogAnvilListener} の combine と同じ 1 に揃える。
     *
     * <p>0 にはしない ── バニラの金床は「コスト 0 の結果は取り出せない」ので、
     * 0 だと結果が見えているのにクリックしても何も起きないクライアント表示になる。
     */
    static final int APPLY_LEVEL_COST = 1;

    /** 粒子を実際に撃つ口。差し替え可能にしている理由は {@link #burstForTest}。 */
    interface ParticleBurst {
        void emit(Player origin, Location anchor, org.bukkit.Particle particle,
                  SpecialRewardsConfig.Emission emission, double yaw);
    }

    private final SpecialRewardsConfig config;
    private final SpecialRewardService rewards;
    private final ConcurrentHashMap<UUID, Long> lastTriggerMillis = new ConcurrentHashMap<>();
    private ParticleBurst burst = ParticleEffectService::burst;

    /**
     * @param rewards シードIDの保有判定。<b>必須</b>(null を許す fail-soft にしない) ──
     *                ここを省略できる形にすると「配線を忘れた回だけ誰でも刻印できる」という、
     *                エラーもログも出ない穴になる。呼び出し側は本番も試験も1箇所ずつしかない。
     */
    public ParticleSeedListener(SpecialRewardsConfig config, SpecialRewardService rewards) {
        this.config = Objects.requireNonNull(config, "config");
        this.rewards = Objects.requireNonNull(rewards, "rewards");
    }

    /**
     * テスト専用: 粒子の発生を差し替える。
     *
     * <p><b>「どこに出したか」を挙動として固定するために必要</b> ── MockBukkit は
     * {@code spawnParticle} の呼び出しを観測できないので、これが無いと W-242(発生位置を
     * プレイヤーから当たった場所へ移す)の回帰は「例外が飛ばないこと」しか確認できない。
     * {@code XpBottleListener#waterTargetLookupForTest} と同じ理由・同じ形。
     */
    void burstForTest(ParticleBurst burst) {
        this.burst = Objects.requireNonNull(burst, "burst");
    }

    /**
     * この金床の中身が「解放済みプレイヤーによるシード付与」か。
     *
     * <p><b>{@link CatalogVanillaOperationGuardListener#onPrepareAnvil}(HIGHEST)からも呼ばれる。</b>
     * あちらはカタログ品が素材として食われる操作を {@code setResult(null)} で潰すが、
     * このリスナー({@code HIGH})が結果を入れた<b>後</b>に走るため、除外しないと
     * <b>カタログ品(＝TF の武器・道具ほぼ全部)へのシード付与が金床で必ず無効化される</b> ──
     * 木材修繕が 2026-08-05 に踏んだのと同じ穴で、症状は「バニラの道具にしか付けられない」になる。
     */
    static boolean isSeedApplication(Player player, ItemStack tool, ItemStack seedCandidate,
                                     SpecialRewardsConfig config, SpecialRewardService rewards) {
        return resolveSeed(player, tool, seedCandidate, config, rewards) != null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (config.particleSeeds().isEmpty()) {
            return;
        }
        if (event.getView() == null || !(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        AnvilInventory inventory = event.getInventory();
        SpecialRewardsConfig.ParticleSeed seed =
                resolveSeed(player, inventory.getFirstItem(), inventory.getSecondItem(), config, rewards);
        if (seed == null) {
            return;
        }
        ItemStack stamped = stamp(inventory.getFirstItem(), seed);
        if (stamped == null) {
            return;
        }
        event.setResult(stamped);
        if (event.getView() instanceof AnvilView anvilView) {
            anvilView.setRepairCost(APPLY_LEVEL_COST);
        }
    }

    /**
     * 結果の取り出し。消費も付与も<b>全てここで行い</b>、バニラの金床処理はキャンセルする
     * (クラスjavadoc「取り出しをバニラに任せない理由」)。
     *
     * <p>判定はプレビューの記憶ではなく<b>クリック時点の盤面から取り直す</b>。
     * イベント時のスナップショットを後から書き戻す作りは、1tick の間に盤面が変わる経路
     * (統合版クライアントのまとめ操作等)で複製機になる。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvilResultTake(InventoryClickEvent event) {
        if (event.getRawSlot() != ANVIL_RESULT_SLOT) {
            return;
        }
        if (!(event.getInventory() instanceof AnvilInventory inventory)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (config.particleSeeds().isEmpty()) {
            return;
        }
        ItemStack tool = inventory.getFirstItem();
        ItemStack seedItem = inventory.getSecondItem();
        SpecialRewardsConfig.ParticleSeed seed = resolveSeed(player, tool, seedItem, config, rewards);
        if (seed == null) {
            return; // TF のシード付与ではない → 通常の金床操作なので一切触らない
        }
        // ここから先は「この金床は TF のシード付与である」と確定しているので、
        // 途中で失敗しても常にキャンセルする(バニラに落とすと素材だけ食われる)。
        event.setCancelled(true);

        ClickType click = event.getClick();
        boolean shift = click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
        if (!shift && click != ClickType.LEFT && click != ClickType.RIGHT) {
            return; // 数字キー・ドロップ等の変則操作は受け付けない(取り違えで無償配布になるため)
        }
        ItemStack stamped = stamp(tool, seed);
        if (stamped == null) {
            return;
        }
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        if (!creative && player.getLevel() < APPLY_LEVEL_COST) {
            player.sendActionBar(Component.text(
                    "レベルが足りません(必要 " + APPLY_LEVEL_COST + ")", NamedTextColor.RED));
            return;
        }
        if (shift) {
            if (!player.getInventory().addItem(stamped).isEmpty()) {
                player.sendActionBar(Component.text("インベントリに空きがありません", NamedTextColor.RED));
                return;
            }
        } else {
            ItemStack cursor = player.getItemOnCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                player.sendActionBar(Component.text(
                        "カーソルを空にしてから取り出してください", NamedTextColor.RED));
                return;
            }
            player.setItemOnCursor(stamped);
        }

        inventory.setFirstItem(null);
        inventory.setSecondItem(consumeOne(seedItem));
        if (!creative) {
            player.setLevel(player.getLevel() - APPLY_LEVEL_COST);
        }
        player.updateInventory();
        player.sendActionBar(Component.text(
                seed.clears() ? "パーティクルシードを取り除きました" : "パーティクルシードを付与しました",
                NamedTextColor.GREEN));
    }

    /** {@code stack} を1個減らしたもの。0個になるなら {@code null}(=スロットを空にする)。 */
    private static ItemStack consumeOne(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 1) {
            return null;
        }
        ItemStack left = stack.clone();
        left.setAmount(left.getAmount() - 1);
        return left;
    }

    /**
     * {@code tool} のクローンに {@code seed} を適用したもの。メタが無ければ {@code null}。
     *
     * <p>{@code clears: true} のシードは刻印を<b>消す</b>。どちらの場合も lore の行
     * ({@link ParticleSeedLore}) を PDC に合わせて入れ直す ── 付け替えたのに前の名前が
     * lore に残る、消したのに行だけ残る、のどちらも「壊れている」と読まれる。
     */
    private ItemStack stamp(ItemStack tool, SpecialRewardsConfig.ParticleSeed seed) {
        if (tool == null) {
            return null;
        }
        ItemStack stamped = tool.clone();
        ItemMeta meta = stamped.getItemMeta();
        if (meta == null) {
            return null;
        }
        if (seed.clears()) {
            ItemData.of(meta).clearParticleSeed();
        } else {
            ItemData.of(meta).setParticleSeed(seed.id());
        }
        ParticleSeedLore.reapply(meta, config);
        stamped.setItemMeta(meta);
        return stamped;
    }

    /**
     * {@code tool} がツール/武器、{@code seedCandidate} が<b>{@code player} が解放済みの</b>シードに
     * 一致すればそのシードを返す。
     *
     * <p><b>保有判定を入れる理由</b>(2026-08-21 実サーバ報告「パーティクルシードを入手しても実装が
     * ないのでは？」): 着手前はここが保有を一切見ておらず、出荷 {@code seed-item} は
     * ブレイズパウダー・青氷・銅インゴットといった<b>誰でも手に入るバニラ材</b>だったので、
     * アチーブメント報酬の {@code special: [seed_*]} は<b>持っていても持っていなくても結果が同じ</b>
     * ＝実質何も付与していなかった。ここが報酬IDの唯一の読み手になる。
     *
     * <p>一致しても未解放なら {@code continue} で次のシードを見る({@code return null} にしない) ──
     * 同じ {@code seed-item} を共有するシードが2つ定義されたとき、解放済みの方まで巻き添えで
     * 使えなくなるため。
     *
     * <p>既に同じシードが刻印されている道具は対象外にする。金床の結果に「何も変わらないもの」を
     * 出すと、素材とレベルだけ消えて何も起きないため。
     */
    private static SpecialRewardsConfig.ParticleSeed resolveSeed(
            Player player, ItemStack tool, ItemStack seedCandidate,
            SpecialRewardsConfig config, SpecialRewardService rewards) {
        if (player == null || config == null || rewards == null) {
            return null;
        }
        if (tool == null || tool.getType().isAir() || !ParticleSeedMatcher.isToolOrWeapon(tool.getType())) {
            return null;
        }
        // 読み込みに失敗した回は particleSeeds() が空(実装によっては null)になりうる。
        // ここは【他の金床操作の可否まで左右する】判定なので、必ず「対象外」へ倒す。
        Map<String, SpecialRewardsConfig.ParticleSeed> seeds = config.particleSeeds();
        if (seeds == null || seeds.isEmpty()) {
            return null;
        }
        Optional<String> already = tool.hasItemMeta()
                ? ItemData.of(tool.getItemMeta()).particleSeed()
                : Optional.empty();
        for (Map.Entry<String, SpecialRewardsConfig.ParticleSeed> entry : seeds.entrySet()) {
            SpecialRewardsConfig.ParticleSeed seed = entry.getValue();
            if (!ParticleSeedMatcher.matchesSeed(seedCandidate, seed.seedItem())) {
                continue;
            }
            if (seed.clears()) {
                // 消すシードは【解放を要求しない】。剥がす操作は報酬ではないし、
                // 付け間違いを取り消せないほうが害が大きい。何も刻印されていない道具には出さない
                // (結果に「何も変わらないもの」を出すと素材とレベルだけ消える)。
                if (already.isPresent()) {
                    return seed;
                }
                continue;
            }
            if (already.isPresent() && already.get().equals(entry.getKey())) {
                continue;
            }
            if (!rewards.isUnlocked(player, entry.getKey())) {
                continue;
            }
            return seed;
        }
        return null;
    }

    /**
     * シード刻印ツールでのブロック破壊時に発動(§6.1)。
     *
     * <p>基準点は<b>壊したブロックの中心</b>(2026-08-25 / W-242、実サーバ報告「ツールが適用された位置の
     * ほうがいい」)。着手前は {@code burstAt} が常にプレイヤーの座標を使っていたので、
     * <b>どれだけ遠くのブロックを掘っても粒子は自分の足元から出ていた</b>。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        triggerIfSeeded(player, player.getInventory().getItemInMainHand(),
                event.getBlock().getLocation().add(0.5, 0.5, 0.5));
    }

    /**
     * シード刻印武器での攻撃時に発動(§6.1)。基準点は<b>殴った相手の胴</b>(W-242)。
     *
     * <p><b>飛び道具は除外する</b> ── 矢が刺さった一撃もここへ来るが、そのときの
     * {@code getDamager()} は矢であってプレイヤーではない。飛び道具は
     * {@link #onProjectileLaunch} / {@link #onProjectileHit} が<b>放った時点の武器</b>を根拠に扱う
     * (飛んでいる間に持ち替えられるので、着弾時の手を見ると別のシードが出る)。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        triggerIfSeeded(player, player.getInventory().getItemInMainHand(),
                bodyCenter(event.getEntity()));
    }

    /**
     * 飛び道具を放った瞬間に、放った武器のシードIDを<b>その飛び道具へ</b>書き付ける
     * (2026-08-25 / W-242「飛び道具は個別実装がいるかもね」への回答)。
     *
     * <p>{@link ProjectileLaunchEvent} を使う理由: 弓・クロスボウ・トライデント・雪玉を
     * <b>1箇所で</b>拾える(弓だけなら {@code EntityShootBowEvent} で足りるが、トライデントは通らない)。
     *
     * <p>着弾時に手を見ないのは、<b>飛んでいる間に持ち替えられる</b>ため ──
     * 矢を撃ってから別の道具を握ると、着弾時にはそちらのシードが出てしまう。
     *
     * <p>⚠ <b>魔法(Ars の触媒)はここを通らない。</b>Ars の呪文は Bukkit の飛び道具として
     * 飛ばないものが多く、TF 側にイベントが無い。触媒の詠唱に粒子を乗せるならフォーク側から
     * 明示的に呼ぶ経路が必要で、それはこのクラスの担当外。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)) {
            return;
        }
        String seedId = seedIdOf(player.getInventory().getItemInMainHand());
        if (seedId == null) {
            return;
        }
        projectile.getPersistentDataContainer().set(
                PdcKeys.ITEM_PARTICLE_SEED, PersistentDataType.STRING, seedId);
    }

    /** 刻印された飛び道具の着弾点で発動(W-242)。当たった相手がいればその胴、無ければ着弾座標。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)) {
            return;
        }
        String seedId = projectile.getPersistentDataContainer()
                .get(PdcKeys.ITEM_PARTICLE_SEED, PersistentDataType.STRING);
        if (seedId == null) {
            return;
        }
        Location anchor = event.getHitEntity() != null
                ? bodyCenter(event.getHitEntity())
                : projectile.getLocation();
        trigger(player, seedId, anchor);
    }

    /**
     * そのエンティティの「胴のあたり」。足元({@code getLocation()})だと地面に粒子が埋まるので、
     * 背の半分だけ上げる。{@code null} 安全(呼び出し側の分岐を増やさないため)。
     */
    private static Location bodyCenter(org.bukkit.entity.Entity entity) {
        if (entity == null) {
            return null;
        }
        return entity.getLocation().add(0, entity.getHeight() * 0.5, 0);
    }

    /** {@code item} に刻印されているシードID(無ければ {@code null})。 */
    private static String seedIdOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return ItemData.of(item.getItemMeta()).particleSeed().orElse(null);
    }

    /** {@code item} がシード刻印済みなら(スロットル込みで)刻印パーティクルを発生させる。 */
    private void triggerIfSeeded(Player player, ItemStack item, Location impact) {
        String seedId = seedIdOf(item);
        if (seedId != null) {
            trigger(player, seedId, impact);
        }
    }

    /**
     * シードIDを引いて発生させる。
     *
     * <p>基準点は config の {@code origin:} で決める ── {@code impact} は「道具が当たった場所」で、
     * {@code null} になりうる(飛び道具が世界外へ消えた等)ので、そのときはプレイヤーへ倒す。
     */
    private void trigger(Player player, String seedId, Location impact) {
        SpecialRewardsConfig.ParticleSeed seed = config.particleSeeds().get(seedId);
        // particle が null なのは clears: true のシード(粒子を持たない)。刻印されることは
        // 無いが、config を書き換えて同じIDを消し用へ転用すると既存の道具がここへ来る。
        if (seed == null || seed.clears() || seed.particle() == null) {
            return;
        }
        if (!throttleReady(player.getUniqueId())) {
            return;
        }
        boolean atPlayer = seed.anchor() == SpecialRewardsConfig.Anchor.PLAYER || impact == null;
        Location anchor = atPlayer ? player.getLocation() : impact;
        burst.emit(player, anchor, seed.particle(), seed.emission(), player.getLocation().getYaw());
    }

    private boolean throttleReady(UUID playerId) {
        long now = System.currentTimeMillis();
        Long last = lastTriggerMillis.get(playerId);
        if (last != null && now - last < THROTTLE_MILLIS) {
            return false;
        }
        lastTriggerMillis.put(playerId, now);
        return true;
    }
}
