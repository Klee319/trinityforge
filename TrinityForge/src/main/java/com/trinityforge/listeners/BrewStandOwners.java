package com.trinityforge.listeners;

import com.trinityforge.pdc.PdcKeys;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 醸造台の<b>所有者</b>(= ゲート対象の組み合わせを最初に正当に組み立てたプレイヤー)の読み書き
 * (2026-07-31 D10 レビュー指摘#1(b))。
 *
 * <h2>なぜ「近くのプレイヤー」ではいけないのか</h2>
 * 解放判定を「スタンドの閲覧者 or 半径8ブロック以内のプレイヤー」で行うと、
 * <b>解放者が醸造中(400tick = 20秒)に8ブロック歩くだけで未解放扱いに落ちる</b>。
 * 未解放扱いは {@code BrewEvent} のキャンセルで表現されるが、バニラの {@code doBrew} は
 * キャンセル時に素材を減らさず即 return し、{@code brewTime} は既に 0 なので次tickで
 * {@code brewable && fuel>0} から再開する → <b>20秒ごとに燃料を1つ燃やし続ける</b>。
 * 所有者をブロック側の PDC に持たせると、離席・ログアウト・第三者の介入に依らず判定が安定する。
 *
 * <h2>インターフェースにしている理由</h2>
 * ブロックの {@code BlockState} と {@code Bukkit.getPlayer(UUID)} に触るため、MockBukkit 無しの
 * 単体テストからは差し替えられる必要がある(MockBukkit は未実装 API を SKIPPED に化けさせるので、
 * 本番実装をテストへ持ち込むと検証そのものが消える)。
 */
public interface BrewStandOwners {

    /** スタンドに記録された所有者の UUID。未記録・壊れた値・スタンドでない場合は empty。 */
    Optional<UUID> ownerOf(BrewerInventory brew);

    /** {@code player} をこのスタンドの所有者として記録する(既存の記録は上書きする)。 */
    void remember(BrewerInventory brew, Player player);

    /** UUID に対応する<b>オンラインの</b>プレイヤー。オフライン/不在なら {@code null}。 */
    Player online(UUID uuid);

    /** 本番実装: 醸造台ブロックの PDC を読み書きする。 */
    static BrewStandOwners blockPdc(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return new BrewStandOwners() {
            @Override
            public Optional<UUID> ownerOf(BrewerInventory brew) {
                BrewingStand stand = standOf(brew);
                if (stand == null) {
                    return Optional.empty();
                }
                try {
                    String raw = stand.getPersistentDataContainer()
                            .get(PdcKeys.BREW_STAND_OWNER, PersistentDataType.STRING);
                    if (raw == null || raw.isBlank()) {
                        return Optional.empty();
                    }
                    return Optional.of(UUID.fromString(raw.trim()));
                } catch (RuntimeException ex) {
                    // 壊れた値は「所有者不明」として扱う(例外を醸造経路へ伝播させない)。
                    plugin.getLogger().log(Level.FINE, "[brew-unlocks] unreadable brewing stand owner", ex);
                    return Optional.empty();
                }
            }

            @Override
            public void remember(BrewerInventory brew, Player player) {
                BrewingStand stand = standOf(brew);
                if (stand == null || player == null) {
                    return;
                }
                try {
                    stand.getPersistentDataContainer().set(PdcKeys.BREW_STAND_OWNER,
                            PersistentDataType.STRING, player.getUniqueId().toString());
                    // BlockState はスナップショットなので update() で書き戻す。イベント内で同期的に
                    // 呼ぶので、この瞬間のスナップショットと実体の中身は一致している
                    // (クリックのインベントリ変更はイベントから戻った後に適用される)。
                    stand.update();
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.FINE,
                            "[brew-unlocks] failed to record brewing stand owner", ex);
                }
            }

            @Override
            public Player online(UUID uuid) {
                return uuid == null ? null : Bukkit.getPlayer(uuid);
            }
        };
    }

    /** イベントが持つ {@link Block} から醸造台のインベントリを取り出す(醸造台でなければ {@code null})。 */
    static BrewerInventory inventoryOf(Block block) {
        if (block == null) {
            return null;
        }
        try {
            return block.getState() instanceof BrewingStand stand ? stand.getInventory() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * このスタンドの燃料を 0 にする(未解放の組み合わせで走り出してしまった周回を1回で止めるため)。
     * 醸造台でなければ何もしない。
     */
    static void drainFuel(Block block) {
        if (block == null) {
            return;
        }
        try {
            if (block.getState() instanceof BrewingStand stand) {
                stand.setFuelLevel(0);
                stand.update();
            }
        } catch (RuntimeException ignored) {
            // 燃料を落とせなくても致命ではない(次の BrewingStandFuelEvent で補給を拒否する)。
        }
    }

    private static BrewingStand standOf(BrewerInventory brew) {
        if (brew == null) {
            return null;
        }
        try {
            return brew.getHolder() instanceof BrewingStand stand ? stand : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
