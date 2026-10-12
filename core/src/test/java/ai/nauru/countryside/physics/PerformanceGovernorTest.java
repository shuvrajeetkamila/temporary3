package ai.nauru.countryside.physics;

import org.junit.Test;
import static org.junit.Assert.*;

public class PerformanceGovernorTest {
    @Test public void batterySaverAndModerateThermalReduceWorkAndRate() {
        PerformanceGovernor governor = new PerformanceGovernor();
        governor.setSystemState(false, 0);
        assertEquals(30, governor.targetFps);
        assertEquals(PerformanceGovernor.Profile.BALANCED, governor.active);
        governor.setSystemState(true, 0);
        assertEquals(20, governor.targetFps);
        assertEquals(PerformanceGovernor.Profile.ECO, governor.active);
        assertTrue(governor.renderScale < 0.8f);
        assertTrue(governor.worldViewDistance < 500f);
    }

    @Test public void severeThermalUsesFifteenFpsAndLowestQuality() {
        PerformanceGovernor governor = new PerformanceGovernor();
        governor.setSystemState(false, 3);
        assertEquals(15, governor.targetFps);
        assertEquals(PerformanceGovernor.Profile.ECO, governor.active);
        assertTrue(governor.renderScale < 0.6f);
        assertFalse(governor.dynamicShadows);
    }

    @Test public void foregroundLimiterIsUpdatedOnItsPollingInterval() {
        PerformanceGovernor governor = new PerformanceGovernor();
        final int[] fps = {0};
        governor.update(0.1f, value -> fps[0] = value);
        assertEquals(30, fps[0]);
        governor.setSystemState(true, 0);
        governor.update(4.0f, value -> fps[0] = value);
        assertEquals(30, fps[0]);
        governor.update(1.0f, value -> fps[0] = value);
        assertEquals(20, fps[0]);
    }
}
