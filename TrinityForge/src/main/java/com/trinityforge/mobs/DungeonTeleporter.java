package com.trinityforge.mobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Objects;

/**
 * {@link DungeonGate} から決定した転送先へ実際にプレイヤーを移動させる処理(2026-07-27、
 * {@code DungeonEntryGui} から抽出)。「転送が成功したときに限り後処理を実行する」という順序保証
 * ({@link DungeonEntryExecutor} 参照)は TF 自身が転送を行う経路(ワールドスポーン/明示座標/区画中心)
 * でのみ維持する。
 *
 * <p>成功後の後処理を {@link Runnable} として外から注入できるようにしたのは、呼び出し元によって
 * 「成功したときにすべきこと」が異なるため —
 * {@code DungeonEntryGui}(鍵アイテムGUI経由の通常入場)は鍵を消費するが、
 * {@code /tf dungeon <id>}(管理者クイック入場)はそもそも鍵チェック自体を行わないため何もしない
 * (no-op)。転送先の決定ロジック自体は {@link DungeonEntryTargetResolver} に委ねる(Bukkit非依存の
 * 純粋なルール)。
 *
 * <p><b>2026-08-02: {@code onSuccess} は EliteMobs 委譲経路({@link EntryTarget.ElitemobsDelegate})
 * では一切呼ばれない。</b>{@link #delegateToElitemobs(Player, String)} のjavadoc参照
 * (鍵の二重管理/premature 消費を避けるため、実消費は EliteMobs フォーク側の実潜入フックへ一本化した)。
 */
public final class DungeonTeleporter {

    private final DungeonGateService gateService;

    public DungeonTeleporter(DungeonGateService gateService) {
        this.gateService = Objects.requireNonNull(gateService, "gateService");
    }

    /**
     * {@code gate} から転送先の種類を決定し、転送(またはEliteMobsへの参加委譲)を試みる。成功したとき
     * に限り {@code onSuccess} を実行する。失敗時のユーザー向けメッセージはこのメソッド自身が送る。
     */
    public void proceedToTarget(Player player, DungeonGate gate, Runnable onSuccess) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(onSuccess, "onSuccess");
        EntryTarget target = DungeonEntryTargetResolver.resolve(gate);
        if (target instanceof EntryTarget.ElitemobsDelegate delegate) {
            // onSuccess(鍵消費)はここへは渡さない — delegateToElitemobs のjavadoc参照。
            delegateToElitemobs(player, delegate.contentPackageId());
        } else if (target instanceof EntryTarget.ExplicitLocation explicit) {
            teleportTo(player, gate, toBukkitLocation(explicit.location()), onSuccess);
        } else if (target instanceof EntryTarget.RegionCenter regionCenter) {
            teleportTo(player, gate, regionCenterLocation(regionCenter.region()), onSuccess);
        } else if (target instanceof EntryTarget.WorldSpawn worldSpawn) {
            teleportTo(player, gate, worldSpawnLocation(worldSpawn.worldName()), onSuccess);
        }
    }

    /**
     * EliteMobs連携ダンジョンはTF側では転送せず参加処理へ委譲する。{@code EliteMobsDungeonBridge}の
     * javadocの通り、事前検証(canEnter)は主要な失敗経路(タイポ/未インストール/二重参加)は防ぐが、
     * teleport自体がvoidであるため100%の成否検出はできない — 既知の限界。
     *
     * <p><b>ここで {@code onSuccess}(鍵消費)を呼ばない(2026-08-02、実サーバ報告の鍵ロスト修正)。</b>
     * {@code teleport()} の「reflective呼び出しが例外なく完了した」は、ブラウザ型ダンジョン
     * ({@code WorldInstancedDungeonPackage}/{@code DynamicDungeonPackage} — EMダンジョンの大半)では
     * 「EliteMobs側のレベル選択ブラウザGUIを開いただけ」を意味し、実際の潜入(インスタンス生成・
     * ワールド移動)ではない。ここで鍵を消費すると、プレイヤーがブラウザGUIを閉じただけで鍵だけが
     * 消える(報告された不具合そのもの)。加えて、EliteMobsフォーク側は 2026-08-01(HIGH-2/HIGH-3)
     * 以降 {@code TrinityForgeDungeonGateListener.checkDungeonEntryAllowed} を実潜入の瞬間
     * (ブラウザ型は {@code DungeonInstance#addNewPlayer}、非ブラウザ型は
     * {@code PlayerPreTeleportEvent} 経由の {@code onPreTeleport})で必ず呼び、同じ
     * {@link DungeonGateService} に対して同じゲートをもう一度評価・消費する — ここで先に消費すると
     * その再評価が「鍵を持っていない」で弾かれ、ブラウザで実際に難易度を選んでも入場自体が失敗する
     * 二重障害になる(鍵だけ失って一度も入れない)。実消費は EliteMobs 側の実入場フックに一本化し、
     * TF 側はここでは検証(canEnter)と失敗時メッセージだけを担当する。
     */
    private void delegateToElitemobs(Player player, String contentPackageId) {
        if (!EliteMobsDungeonBridge.isAvailable()) {
            player.sendMessage(Component.text("EliteMobsが利用できないため入場できません", NamedTextColor.RED));
            return;
        }
        boolean success = EliteMobsDungeonBridge.canEnter(player, contentPackageId)
                && EliteMobsDungeonBridge.teleport(player, contentPackageId);
        if (!success) {
            player.sendMessage(Component.text("ダンジョンに入場できませんでした", NamedTextColor.RED));
        }
    }

    private void teleportTo(Player player, DungeonGate gate, Location location, Runnable onSuccess) {
        if (location == null || location.getWorld() == null) {
            player.sendMessage(Component.text("行き先ワールドが見つからないため入場できません", NamedTextColor.RED));
            return;
        }
        // 到着時のDungeonGateListenerによる二重判定/二重消費を防ぐため、転送を試みる前に
        // 一回限りの通行許可を発行しておく(PlayerTeleportEventはteleport()呼び出し中に同期発火するため)。
        gateService.grantOneTimePass(player.getUniqueId(), gate.world());
        boolean success = DungeonEntryExecutor.executeIfSuccessful(
                () -> player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN),
                onSuccess);
        if (!success) {
            // 転送できなかったのにパスを残すと、現地に留まったプレイヤーが期限内だけ歩いて
            // 無料入場できてしまう。使わなかったパスは必ずここで取り消す。
            gateService.revokeOneTimePass(player.getUniqueId(), gate.world());
            player.sendMessage(Component.text("転送に失敗しました", NamedTextColor.RED));
        }
    }

    private Location toBukkitLocation(EntryLocation location) {
        World world = Bukkit.getWorld(location.world());
        if (world == null) {
            return null;
        }
        return new Location(world, location.x(), location.y(), location.z(), location.yaw(), location.pitch());
    }

    /**
     * 区画の中心(x/zは中心、yはmin..max範囲内で最も高い安全な地表。範囲外なら中心yにクランプ)。
     */
    private Location regionCenterLocation(GateRegion region) {
        World world = Bukkit.getWorld(region.world());
        if (world == null) {
            return null;
        }
        int centerX = (region.minX() + region.maxX()) / 2;
        int centerZ = (region.minZ() + region.maxZ()) / 2;
        int centerY = (region.minY() + region.maxY()) / 2;
        int highestY = world.getHighestBlockYAt(centerX, centerZ);
        int y = (highestY >= region.minY() && highestY <= region.maxY()) ? highestY : centerY;
        return new Location(world, centerX + 0.5, y, centerZ + 0.5);
    }

    private Location worldSpawnLocation(String worldName) {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : world.getSpawnLocation();
    }
}
