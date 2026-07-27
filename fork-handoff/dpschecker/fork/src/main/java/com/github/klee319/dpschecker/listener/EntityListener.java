package com.github.klee319.dpschecker.listener;

import com.github.klee319.dpschecker.dummy.DummyEntity;
import com.github.klee319.dpschecker.dummy.DummyManager;
import com.github.klee319.dpschecker.gui.MainMenuGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

public class EntityListener implements Listener {

    private final JavaPlugin plugin;
    private final DummyManager dummyManager;

    public EntityListener(JavaPlugin plugin, DummyManager dummyManager) {
        this.plugin = plugin;
        this.dummyManager = dummyManager;
    }

    @EventHandler
    public void onEntityTarget(EntityTargetEvent event) {
        if (dummyManager.isDummyEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityCombust(EntityCombustEvent event) {
        if (dummyManager.isDummyEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * 起動時走査({@code loadExistingDummies})はロード済みチャンクしか見ないため、起動後に(プレイヤー接近等で)
     * ダミーの居るチャンクがロードされた際に再登録する。これが無いと実体は残っても BossBar/GUI/所有数管理から
     * 外れ「再起動で消えた」ように見える(#7)。既登録は registerFromEntities 側の containsKey ガードで重複しない。
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        dummyManager.registerFromEntities(event.getEntities());
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Optional<DummyEntity> opt = dummyManager.getDummyByEntity(event.getRightClicked());
        if (opt.isEmpty()) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        DummyEntity dummy = opt.get();

        // Owner check: only owner or admin can open GUI
        if (!dummy.getOwnerUuid().equals(player.getUniqueId())
                && !player.hasPermission("dpschecker.admin")) {
            player.sendMessage(Component.text("[DPSChecker] ", NamedTextColor.GOLD)
                    .append(Component.text("他のプレイヤーのダミーは操作できません。", NamedTextColor.RED)));
            return;
        }

        player.openInventory(new MainMenuGUI(plugin, dummy).getInventory());
    }
}
