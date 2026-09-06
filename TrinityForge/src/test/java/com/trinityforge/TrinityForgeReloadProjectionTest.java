package com.trinityforge;

import com.trinityforge.gathering.GatheringEfficiencyEnchantApplier;
import com.trinityforge.integration.ars.ArsArmorStatRefreshBridge;
import com.trinityforge.skilltree.runtime.PerkAttributeApplier;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Runtime player projections that must be rebuilt immediately after a config reload. */
class TrinityForgeReloadProjectionTest {

    @Test
    void reloadReappliesPerkAttributesAndGatheringEfficiency() throws Exception {
        TrinityForge plugin = mock(TrinityForge.class, CALLS_REAL_METHODS);
        PerkAttributeApplier perkAttributes = mock(PerkAttributeApplier.class);
        GatheringEfficiencyEnchantApplier gatheringEfficiency =
                mock(GatheringEfficiencyEnchantApplier.class);
        setField(plugin, "perkAttributeApplier", perkAttributes);
        setField(plugin, "gatheringEfficiencyApplier", gatheringEfficiency);

        // getServer() は初期化されていない(field default = null)。ArsPaper スレッド再計算ループが
        // これをガードせずに呼ぶと NullPointerException になり、このテスト自体が落ちる
        // (= 下の reloadTriggersArsArmorStatRefreshForEveryOnlinePlayer と対になる回帰ガード)。
        plugin.reapplyOnlinePlayerProjectionsAfterReload();

        verify(perkAttributes).applyAllOnline();
        verify(gatheringEfficiency).applyAllOnline();
    }

    /**
     * タスク1(武器のスレッド欄が /tf status に反映されない): reload 経路でも
     * {@link ArsArmorStatRefreshBridge#refresh(org.bukkit.entity.Player)} をオンライン全員分
     * 呼ぶことの回帰ガード。この呼び出しを {@code reapplyOnlinePlayerProjectionsAfterReload} から
     * 削ると、このテストは "wanted but not invoked" で落ちる。
     */
    @Test
    void reloadTriggersArsArmorStatRefreshForEveryOnlinePlayer() throws Exception {
        TrinityForge plugin = mock(TrinityForge.class);
        doCallRealMethod().when(plugin).reapplyOnlinePlayerProjectionsAfterReload();

        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        Player alice = mock(Player.class);
        Player bob = mock(Player.class);
        java.util.Collection<? extends Player> online = List.of(alice, bob);
        when(server.getOnlinePlayers()).thenAnswer(invocation -> online);

        try (var bridge = mockStatic(ArsArmorStatRefreshBridge.class)) {
            plugin.reapplyOnlinePlayerProjectionsAfterReload();

            bridge.verify(() -> ArsArmorStatRefreshBridge.refresh(alice));
            bridge.verify(() -> ArsArmorStatRefreshBridge.refresh(bob));
        }
    }

    private static void setField(TrinityForge target, String name, Object value) throws Exception {
        Field field = TrinityForge.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
