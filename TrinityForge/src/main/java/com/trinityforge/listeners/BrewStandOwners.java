package com.trinityforge.listeners;

import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 醸造解放ゲート({@link BrewUnlockListener})から見た所有者の読み書き。
 * <b>記録そのものは {@link BrewOwnership} が唯一持つ</b>(2026-07-31 レビュー指摘#1 の是正) —
 * このインターフェースは「{@link BrewerInventory} しか手元に無いイベント」から
 * {@link BrewOwnership} を呼ぶための薄いアダプタで、専用のPDCキーは<b>持たない</b>。
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
 * 本番実装をテストへ持ち込むと検証そのものが消える)。本番実装 {@link #blockPdc} の配線
 * ({@code getHolder()} でスタンドへ解決する / {@code update()} で書き戻す /
 * {@link BrewOwnership} と同じキーを使う)は {@code BrewStandOwnersBlockPdcTest} が
 * Mockito だけで固定している。
 */
public interface BrewStandOwners {

    /** スタンドに記録された所有者の UUID。未記録・壊れた値・スタンドでない場合は empty。 */
    Optional<UUID> ownerOf(BrewerInventory brew);

    /**
     * {@code player} をこのスタンドの所有者として記録する。<b>既存の記録は上書きしない</b>
     * ({@link BrewOwnership#rememberOwner} = 先着優先)。
     */
    void remember(BrewerInventory brew, Player player);

    /**
     * 記録済み所有者を {@code player} へ差し替える。<b>現所有者ではこの醸造が成立しないと
     * 確認できた場合にだけ</b>呼ぶ({@link BrewOwnership#replaceOwner} の制約)。
     */
    void replace(BrewerInventory brew, Player player);

    /** UUID に対応する<b>オンラインの</b>プレイヤー。オフライン/不在なら {@code null}。 */
    Player online(UUID uuid);

    /** 本番実装: 醸造台ブロックの PDC を {@link BrewOwnership} 経由で読み書きする。 */
    static BrewStandOwners blockPdc(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        BrewOwnership ownership = new BrewOwnership(plugin);
        return new BrewStandOwners() {
            @Override
            public Optional<UUID> ownerOf(BrewerInventory brew) {
                BrewingStand stand = standOf(brew);
                if (stand == null) {
                    return Optional.empty();
                }
                try {
                    return ownership.ownerOf(stand);
                } catch (RuntimeException ex) {
                    // 壊れた値は「所有者不明」として扱う(例外を醸造経路へ伝播させない)。
                    plugin.getLogger().log(Level.FINE, "[brew-unlocks] unreadable brewing stand owner", ex);
                    return Optional.empty();
                }
            }

            @Override
            public void remember(BrewerInventory brew, Player player) {
                write(brew, player, false);
            }

            @Override
            public void replace(BrewerInventory brew, Player player) {
                write(brew, player, true);
            }

            @Override
            public Player online(UUID uuid) {
                return uuid == null ? null : plugin.getServer().getPlayer(uuid);
            }

            private void write(BrewerInventory brew, Player player, boolean displace) {
                BrewingStand stand = standOf(brew);
                if (stand == null || player == null) {
                    return;
                }
                try {
                    // BlockState はスナップショットなので BrewOwnership 側が update() で書き戻す。
                    // イベント内で同期的に呼ぶので、この瞬間のスナップショットと実体の中身は一致している
                    // (クリックのインベントリ変更はイベントから戻った後に適用される)。
                    if (displace) {
                        ownership.replaceOwner(stand, player);
                    } else {
                        ownership.rememberOwner(stand, player);
                    }
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.FINE,
                            "[brew-unlocks] failed to record brewing stand owner", ex);
                }
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
     * 醸造台インベントリの持ち主({@code BrewerInventory#getHolder()} は 1.21.11 で
     * {@link BrewingStand} を返す共変オーバーライドを持つ)。醸造台でなければ {@code null}。
     */
    static BrewingStand standOf(BrewerInventory brew) {
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
