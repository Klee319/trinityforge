package com.trinityforge.progression;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.stats.CrossPluginItemResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * パーティクルシード報酬の<b>実体の受け渡し</b>(2026-08-21 実サーバ報告
 * 「パーティクルシードを入手しても実装がないのでは？アイテムが入手できなかった」)。
 *
 * <p><b>着手前は解放しても何も起きていなかった</b>。{@code special: [seed_spark]} の付与は
 * {@link com.trinityforge.pdc.PlayerData#grantSpecialReward} でIDを1つ書くだけで、
 * <ul>
 *   <li>そのIDを読む処理が<b>1つも無かった</b>(称号は {@code equipTitle}、パーティクルは
 *       {@code equipParticle} が読むが、シードには対応する読み手が存在しなかった)。</li>
 *   <li>合成側({@link com.trinityforge.listeners.ParticleSeedListener})は保有を<b>一切見ず</b>、
 *       誰でもシード素材さえ持てば刻印できた ── つまり報酬は「持っていても持っていなくても同じ」。</li>
 *   <li>アイテムも配られないので、プレイヤーから見ると<b>解放時に何も起きない</b>。</li>
 * </ul>
 * エラーもログも出ないので、気づけるのは「受け取った本人が何も起きないと気づいたとき」だけだった。
 *
 * <p>このクラスは「配る」側を担う: 解放と同時に {@code seed-item} を1個手渡し、
 * <b>使い方(道具/武器と一緒に金床へ置く)を文章で伝える</b>
 * (付与の場は 2026-08-25 / W-214 で作業台から金床へ移した ──
 * 理由は {@link com.trinityforge.listeners.ParticleSeedListener} のクラスjavadoc)。
 * もう一方(保有していないシードは刻印できない)は {@code ParticleSeedListener} 側のゲートで担保する
 * ── 配るだけにすると「素材はバニラ材(ブレイズパウダー等)なので誰でも自前で用意できる」ため、
 * 報酬としての意味が戻らない。
 *
 * <p>称号/パーティクルには何もしない。あちらは {@code /tf settings} の装備画面という
 * 発見経路が既にあるので、ここで二重に案内する必要が無い。
 */
public final class ParticleSeedDelivery {

    private final SpecialRewardsConfig config;
    /** null 可(fail-soft)。未注入ならアイテムは配らず案内文だけ出す。 */
    private final CrossPluginItemResolver itemResolver;
    private final Logger log;

    public ParticleSeedDelivery(SpecialRewardsConfig config, CrossPluginItemResolver itemResolver, Logger log) {
        this.config = Objects.requireNonNull(config, "config");
        this.itemResolver = itemResolver;
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * {@code rewardId} がパーティクルシードなら、その {@code seed-item} を1個渡して使い方を案内する。
     *
     * @return パーティクルシードとして処理したなら {@code true}(称号/パーティクル/未定義IDは {@code false})
     */
    public boolean deliver(Player player, String rewardId) {
        if (player == null || rewardId == null) {
            return false;
        }
        SpecialRewardsConfig.ParticleSeed seed = config.particleSeeds().get(rewardId);
        if (seed == null) {
            return false;
        }
        ItemStack given = giveSeedItem(player, seed);
        player.sendMessage(Component.text("パーティクルシード解放: ", NamedTextColor.LIGHT_PURPLE)
                .append(com.trinityforge.text.MiniText.render(seed.displayName(), NamedTextColor.WHITE)));
        Component seedName = given != null
                ? given.displayName()
                : Component.text(seed.seedItem(), NamedTextColor.WHITE);
        player.sendMessage(Component.text("使い方: ", NamedTextColor.GRAY)
                .append(Component.text("金床の左に 道具/武器、右に ", NamedTextColor.GRAY))
                .append(seedName)
                .append(Component.text(" を置くと、その道具に粒子が焼き付きます"
                        + "(ブロック破壊・攻撃のたびに発生)。", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("持っているシードは /tf settings の最下段で確認できます。",
                NamedTextColor.DARK_GRAY));
        return true;
    }

    /**
     * {@code seed-item} を1個渡す。インベントリが満杯なら足元へ落とす
     * ({@code AchievementService#grantItems} と同じ方針 ── 報酬を黙って消さない)。
     *
     * @return 実際に渡したスタック。解決できなかった場合は {@code null}
     */
    private ItemStack giveSeedItem(Player player, SpecialRewardsConfig.ParticleSeed seed) {
        if (itemResolver == null) {
            return null;
        }
        Optional<ItemStack> built = itemResolver.create(seed.seedItem());
        if (built.isEmpty()) {
            // ここに来るのは seed-item に実在しない Material / カタログIDを書いたとき。
            // 案内文だけは出す(「何も起きない」に戻さないため)。
            log.warning("[particle-seeds] seed '" + seed.id() + "' の seed-item '" + seed.seedItem()
                    + "' を実体化できなかった。案内文のみ送信する");
            return null;
        }
        ItemStack stack = built.get();
        stack.setAmount(1);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        return stack;
    }
}
