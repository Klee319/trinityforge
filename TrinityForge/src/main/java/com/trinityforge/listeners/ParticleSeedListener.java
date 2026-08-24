package com.trinityforge.listeners;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.ParticleEffectService;
import com.trinityforge.progression.SpecialRewardService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

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

    private final SpecialRewardsConfig config;
    private final SpecialRewardService rewards;
    private final ConcurrentHashMap<UUID, Long> lastTriggerMillis = new ConcurrentHashMap<>();

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
        player.sendActionBar(Component.text("パーティクルシードを付与しました", NamedTextColor.GREEN));
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

    /** {@code tool} のクローンに {@code seed} を刻印したもの。メタが無ければ {@code null}。 */
    private static ItemStack stamp(ItemStack tool, SpecialRewardsConfig.ParticleSeed seed) {
        if (tool == null) {
            return null;
        }
        ItemStack stamped = tool.clone();
        ItemMeta meta = stamped.getItemMeta();
        if (meta == null) {
            return null;
        }
        ItemData.of(meta).setParticleSeed(seed.id());
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
            if (!ParticleSeedMatcher.matchesSeed(seedCandidate, entry.getValue().seedItem())) {
                continue;
            }
            if (already.isPresent() && already.get().equals(entry.getKey())) {
                continue;
            }
            if (!rewards.isUnlocked(player, entry.getKey())) {
                continue;
            }
            return entry.getValue();
        }
        return null;
    }

    /** シード刻印ツールでのブロック破壊時に発動(§6.1)。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        triggerIfSeeded(player, player.getInventory().getItemInMainHand());
    }

    /** シード刻印武器での攻撃時に発動(§6.1)。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        triggerIfSeeded(player, player.getInventory().getItemInMainHand());
    }

    /** {@code item} がシード刻印済みなら(スロットル込みで)刻印パーティクルを発生させる。 */
    private void triggerIfSeeded(Player player, ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemData.of(item.getItemMeta()).particleSeed().ifPresent(seedId -> {
            SpecialRewardsConfig.ParticleSeed seed = config.particleSeeds().get(seedId);
            if (seed == null) {
                return;
            }
            if (!throttleReady(player.getUniqueId())) {
                return;
            }
            ParticleEffectService.burstAt(player, seed.particle(), seed.count(), 0.3);
        });
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
