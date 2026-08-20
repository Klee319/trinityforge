package com.trinityforge.afk;

import com.trinityforge.config.domains.AfkConfig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;

import java.util.Objects;

/**
 * AFK 中のバニラ経験値を止める (2026-07-27)。
 *
 * <p>{@link PlayerExpChangeEvent} はオーブ回収・かまど精錬・釣り・取引・エンチャント瓶など、
 * バニラXPの増加を一点で捕捉する({@code NativeSurvivalPerkListener} が常時ボーナスを乗せるのと
 * 同じ経路)。モブ討伐で落ちたXPオーブも「拾った時点」でここを通るため、モブトラップ放置も
 * この1箇所で塞がる。
 *
 * <p>{@link EventPriority#LOWEST} で 0 にする。ボーナス倍率を乗せる {@code HIGH} の購読者より
 * 先に走るので、「0 に倍率を掛けても 0」で確実に無効化される(逆順だと倍率適用後に 0 にする形になり、
 * 他の購読者が中間値を観測してしまう)。{@code PlayerExpChangeEvent} は Cancellable ではないため
 * 金額を 0 にするのが唯一の止め方。
 */
public final class AfkSuppressionListener implements Listener {

    private final AfkService service;
    private final AfkConfig config;

    public AfkSuppressionListener(AfkService service, AfkConfig config) {
        this.service = Objects.requireNonNull(service, "service");
        this.config = Objects.requireNonNull(config, "config");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVanillaExpGain(PlayerExpChangeEvent event) {
        if (!config.suppressVanillaExp() || event.getAmount() <= 0) {
            return;
        }
        if (service.isAfk(event.getPlayer())) {
            event.setAmount(0);
        }
    }
}
