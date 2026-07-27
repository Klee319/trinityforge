package com.trinityforge.farming;

import com.trinityforge.farming.AreaHarvestPolicy.Offset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link AreaHarvestPolicy}: pure square-offset computation for area-harvest. */
class AreaHarvestPolicyTest {

    @Test
    void radiusOneGivesEightSurroundingOffsetsExcludingOrigin() {
        List<Offset> offsets = AreaHarvestPolicy.squareOffsets(1);
        assertEquals(8, offsets.size());
        assertFalse(offsets.contains(new Offset(0, 0)));
        assertTrue(offsets.contains(new Offset(1, 0)));
        assertTrue(offsets.contains(new Offset(-1, -1)));
        assertTrue(offsets.contains(new Offset(1, 1)));
    }

    @Test
    void radiusTwoGivesTwentyFourOffsets() {
        // (2*2+1)^2 - 1 = 24
        assertEquals(24, AreaHarvestPolicy.squareOffsets(2).size());
    }

    @Test
    void zeroOrNegativeRadiusGivesNoOffsets() {
        assertTrue(AreaHarvestPolicy.squareOffsets(0).isEmpty());
        assertTrue(AreaHarvestPolicy.squareOffsets(-1).isEmpty());
    }
}
