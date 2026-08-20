package com.trinityforge;

import com.trinityforge.gathering.GatheringEfficiencyEnchantApplier;
import com.trinityforge.skilltree.runtime.PerkAttributeApplier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

        plugin.reapplyOnlinePlayerProjectionsAfterReload();

        verify(perkAttributes).applyAllOnline();
        verify(gatheringEfficiency).applyAllOnline();
    }

    private static void setField(TrinityForge target, String name, Object value) throws Exception {
        Field field = TrinityForge.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
