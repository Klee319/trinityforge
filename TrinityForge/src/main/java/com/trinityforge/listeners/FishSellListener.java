package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FishingGimmickConfig;
import com.trinityforge.economy.EconomyBridge;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.StatKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code fish-sell-toggle}(fishing.yml B-alpha-1): 保持者が釣った魚を釣った瞬間に自動でVault通貨へ
 * 換金する(2026-07-25 経済連携)。Vault不在時は {@link EconomyBridge#available()} が {@code false} で
 * 静かに無効化され、通常どおりアイテムとして入手できる(ユーザー要件「なくても効果がないだけで運用可能」)。
 *
 * <p>{@link EventPriority#MONITOR} で登録: {@link FishingGimmickListener}({@code LOW}、宝/ゴミ置換)と
 * {@link FishingQualityListener}({@code NORMAL}、品質刻印/追加ドロップ)の後に走らせ、最終的に確定した
 * 釣果Materialに対して売却判定する。装備(すでに品質刻印された物)は {@code fish-sell.prices} に登録しない
 * 運用を前提とする(未登録Material=売却対象外なので、装備を誤って自動売却する事故はconfig側で防げる)。
 *
 * <p><strong>exploit対策</strong>(#5 exploit fixの教訓 — 過去にこの機構自体が悪用され恒久no-op化された
 * 経緯がある): (1) 換金は {@link EconomyBridge#deposit} が成功した場合にのみ釣果アイテムを
 * {@link Item#remove()} する — 入金失敗時はアイテムをそのまま残し、何も壊さない。(2) アイテムは
 * インベントリ経由のpickupを一切経由せず、このハンドラの中で直接エンティティごと消す — ドラッグ複製等の
 * インベントリ操作系exploitの入口を与えない。(3) 1プレイヤーあたり1分間の売却回数上限
 * ({@code fish-sell.max-sells-per-minute}) を設け、AFK釣り機/自動釣りマクロでの無限換金を防ぐ。
 *
 * <p><strong>価格解決順序</strong>(T2 2026-07-25経済連携拡張: {@code fish}グループへのカスタムアイテム
 * 対応に伴い、Material限定だった {@code fish-sell.prices} をトークン(Material名 または カスタムID)対応に
 * 拡張): ①釣果アイテムのPDC {@code catalog_id}(TF/Arsどちらのタグでも {@link CrossPluginItemResolver#idOf}
 * が読む)がキーに存在すればその価格を優先する — カスタムIDのほうがMaterialより具体的な指定だから。
 * ②無ければ {@link ItemStack#getType()} のMaterial名で引く(既存4件=COD/SALMON/TROPICAL_FISH/PUFFERFISH の
 * 後方互換はこの経路で成立する)。どちらにも無ければ未登録=売却対象外。
 */
public final class FishSellListener implements Listener {

    private static final String FEATURE_FISH_SELL_TOGGLE = "fish-sell-toggle";
    private static final String FISH_SELL_PRICE_BONUS_KEY = StatKeys.canonical("fish_sell_price_bonus");
    private static final long WINDOW_MILLIS = 60_000L;

    private final DedicatedEffectsConfig dedicatedEffects;
    private final FishingGimmickConfig gimmickConfig;
    private final PlayerStatAggregator aggregator;
    private final EconomyBridge economyBridge;
    private final Map<UUID, Deque<Long>> recentSaleTimestamps = new HashMap<>();

    public FishSellListener(DedicatedEffectsConfig dedicatedEffects, FishingGimmickConfig gimmickConfig,
                            PlayerStatAggregator aggregator, EconomyBridge economyBridge) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.economyBridge = Objects.requireNonNull(economyBridge, "economyBridge");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || !dedicatedEffects.isActive(player, FEATURE_FISH_SELL_TOGGLE)) {
            return;
        }
        if (!economyBridge.available()) {
            return; // Vault未導入: 静かに無効化(通常どおりアイテムとして入手させる)。
        }
        ItemStack caughtStack = caught.getItemStack();
        if (caughtStack == null || caughtStack.getType().isAir()) {
            return;
        }
        Optional<Double> basePrice = CrossPluginItemResolver.idOf(caughtStack)
                .flatMap(gimmickConfig::fishSellPriceOf) // ①カスタムID優先
                .or(() -> gimmickConfig.fishSellPriceOf(caughtStack.getType())); // ②Material名フォールバック
        if (basePrice.isEmpty()) {
            return; // 未登録トークン: 売却対象外。通常どおりアイテムとして入手。
        }
        if (!consumeSellQuota(player.getUniqueId())) {
            return; // exploit対策: 1分あたり上限到達。今回は換金せずアイテムのまま入手させる。
        }

        double bonus = Math.max(0.0, aggregator.aggregate(player).totalOf(FISH_SELL_PRICE_BONUS_KEY));
        double price = basePrice.get() * Math.max(1, caughtStack.getAmount()) * (1.0 + bonus);
        if (!economyBridge.deposit(player, price)) {
            return; // 入金失敗: アイテムは消さず、そのまま残す(安全側)。
        }
        caught.remove();
        player.sendActionBar(Component.text(
                String.format(Locale.ROOT, "魚を売却: +%.1f", price), NamedTextColor.GREEN));
    }

    /** {@code true} なら今回の売却を許可し、この呼び出し自体を利用回数として記録する。 */
    private boolean consumeSellQuota(UUID playerId) {
        int max = Math.max(1, gimmickConfig.fishSellMaxPerMinute());
        long now = System.currentTimeMillis();
        Deque<Long> window = recentSaleTimestamps.computeIfAbsent(playerId, id -> new ArrayDeque<>());
        while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MILLIS) {
            window.pollFirst();
        }
        if (window.size() >= max) {
            return false;
        }
        window.addLast(now);
        return true;
    }
}
