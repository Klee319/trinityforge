package com.trinityforge.mobs;

import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.DungeonGateConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared dungeon entry evaluation for TF teleports, EliteMobs instanced-dungeon joins, and
 * (D3 topology) in-place 区画ダンジョン boundary crossings.
 * Single SoT: {@code dungeon/gates.yml} keyed by world name with optional content-package aliases
 * and an optional {@code region:} boundary.
 */
public final class DungeonGateService {

    private final DungeonGateConfig gateConfig;
    private final SymmetricCombatService combatService;

    public DungeonGateService(DungeonGateConfig gateConfig, SymmetricCombatService combatService) {
        this.gateConfig = Objects.requireNonNull(gateConfig, "gateConfig");
        this.combatService = Objects.requireNonNull(combatService, "combatService");
    }

    /**
     * Checks whether {@code player} may enter the dungeon identified by {@code lookupKey} (world name
     * or content-package alias). Sends denial messages and consumes the key on success.
     *
     * @return {@code true} if entry is allowed (or ungated), {@code false} if denied
     */
    public boolean checkEntry(Player player, String lookupKey) {
        if (player == null || lookupKey == null || lookupKey.isBlank()) {
            return true;
        }
        Optional<DungeonGate> gateOpt = gateConfig.resolve(lookupKey);
        if (gateOpt.isEmpty()) {
            return true;
        }
        return evaluateAndConsume(player, List.of(gateOpt.get()), true);
    }

    /** 区画ゲートが1つでも設定されているか(移動イベントの早期リターン用)。 */
    public boolean hasRegionGates() {
        return gateConfig.hasRegionGates();
    }

    /**
     * D3 topology: {@code from} から {@code to} への移動/テレポートが区画ゲート境界を外→内へ
     * 跨ぐ場合に入場評価する。跨いだ全ゲートを二相で評価し(先に全ゲート通過を確認してから
     * キーを消費)、拒否時は {@code notify} が真のときだけメッセージを送る(移動イベントの
     * 連射スパム防止はリスナー側のスロットルに委ねる)。
     *
     * @return {@code true} if entry is allowed (or no gated region was entered)
     */
    public boolean checkRegionEntry(Player player, Location from, Location to, boolean notify) {
        if (player == null || to == null || to.getWorld() == null) {
            return true;
        }
        List<DungeonGate> candidates = gateConfig.regionGates(to.getWorld().getName());
        if (candidates.isEmpty()) {
            return true;
        }
        String toWorld = to.getWorld().getName();
        boolean sameWorldFrom = from != null && from.getWorld() != null
                && from.getWorld().getName().equals(toWorld);
        List<DungeonGate> entered = new ArrayList<>();
        for (DungeonGate gate : candidates) {
            GateRegion region = gate.region();
            boolean inTo = region.contains(toWorld, to.getBlockX(), to.getBlockY(), to.getBlockZ());
            // 別ワールドからのテレポートは「外から」扱い(fromの座標は比較しない)。
            boolean inFrom = sameWorldFrom
                    && region.contains(toWorld, from.getBlockX(), from.getBlockY(), from.getBlockZ());
            if (inTo && !inFrom) {
                entered.add(gate);
            }
        }
        if (entered.isEmpty()) {
            return true;
        }
        return evaluateAndConsume(player, entered, notify);
    }

    /**
     * 二相評価: まず全ゲートの通過可否を確認し(1つでも拒否なら何も消費せずfalse)、全通過が
     * 確定してからキーを消費する — 重なった区画で片方のキーだけ先に消費される事故を防ぐ。
     */
    private boolean evaluateAndConsume(Player player, List<DungeonGate> gates, boolean notify) {
        int combatLevel = combatService.combatLevelOf(player.getUniqueId());
        for (DungeonGate gate : gates) {
            boolean hasKey = !gate.keyRequired()
                    || player.getInventory().contains(gate.keyMaterial(), gate.keyAmount());
            switch (DungeonGatePolicy.evaluate(
                    gate.requiredCombatLevel(), combatLevel, gate.keyRequired(), hasKey)) {
                case UNDER_LEVEL -> {
                    if (notify) {
                        player.sendMessage(Component.text(
                                "このダンジョンに入るには combat level " + gate.requiredCombatLevel()
                                        + " が必要です（現在 " + combatLevel + "）", NamedTextColor.RED));
                    }
                    return false;
                }
                case MISSING_KEY -> {
                    if (notify) {
                        player.sendMessage(Component.text(
                                "入場には " + gate.keyMaterial() + " x" + gate.keyAmount() + " が必要です",
                                NamedTextColor.RED));
                    }
                    return false;
                }
                case NONE -> {
                    // fall through to consumption phase
                }
            }
        }
        for (DungeonGate gate : gates) {
            if (gate.keyRequired()) {
                player.getInventory().removeItem(new ItemStack(gate.keyMaterial(), gate.keyAmount()));
            }
        }
        return true;
    }
}
