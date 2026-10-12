package ai.nauru.countryside.physics;

import org.junit.Test;
import static org.junit.Assert.*;

public class VehicleForceModelsTest {
    @Test public void suspensionHasCorrectSpringAndDamperSignsAndBounds() {
        float compressed = CarForceModel.suspensionLoad(0.2f, 0f,
                CarForceModel.SPRING_N_PER_M, CarForceModel.DAMPER_NS_PER_M, CarForceModel.MAX_WHEEL_LOAD_N);
        float rebound = CarForceModel.suspensionLoad(-0.2f, 0f,
                CarForceModel.SPRING_N_PER_M, CarForceModel.DAMPER_NS_PER_M, CarForceModel.MAX_WHEEL_LOAD_N);
        float damped = CarForceModel.suspensionLoad(0.2f, 1f,
                CarForceModel.SPRING_N_PER_M, CarForceModel.DAMPER_NS_PER_M, CarForceModel.MAX_WHEEL_LOAD_N);
        assertEquals(4_800f, compressed, 1f);
        assertEquals(0f, rebound, 0f);
        assertTrue(damped < compressed);
        assertEquals(CarForceModel.MAX_WHEEL_LOAD_N,
                CarForceModel.suspensionLoad(2f, -100f, CarForceModel.SPRING_N_PER_M,
                        CarForceModel.DAMPER_NS_PER_M, CarForceModel.MAX_WHEEL_LOAD_N), 0f);
    }

    @Test public void tyresRespectCombinedFrictionEllipseLimit() {
        float[] force = CarForceModel.frictionCircle(5_000f, 5_000f, 4_000f, 0.8f);
        float magnitude = (float) Math.hypot(force[0], force[1]);
        assertEquals(3_200f, magnitude, 0.1f);
    }

    @Test public void boatBuoyancyBalancesWeightAndDampingRemovesEnergy() {
        float volume = 0.54f;
        float displacementAtRest = BoatForceModel.HULL_MASS_KG
                / (BoatForceModel.WATER_DENSITY_KG_M3 * volume);
        float lift = BoatForceModel.displacedBuoyancy(displacementAtRest, volume);
        assertEquals(BoatForceModel.HULL_MASS_KG * 9.81f, lift, 0.1f);
        assertEquals(-450f, BoatForceModel.verticalDamping(1.5f, 300f), 0.1f);
        assertTrue(BoatForceModel.planingLift(15f, 1.2f, 0.08f) > 0f);
        assertTrue(BoatForceModel.waveHeight(0, 0, 2) != BoatForceModel.waveHeight(0, 0, 0));
    }

    @Test public void helicopterHoverIsForceBalancedAndPedalCountersRotorTorque() {
        float hover = HelicopterForceModel.hoverCollective(HelicopterForceModel.MASS_KG, 1f, 1f);
        float thrust = HelicopterForceModel.thrust(hover, 1f, 1f);
        assertEquals(HelicopterForceModel.MASS_KG * 9.81f, thrust, 2f);
        assertTrue(hover > 0.4f && hover < 0.8f);
        assertTrue(HelicopterForceModel.reactionTorque(0.6f, 0.8f) > 0f);
        assertTrue(HelicopterForceModel.groundEffect(0.5f) > HelicopterForceModel.groundEffect(15f));
        assertTrue(HelicopterForceModel.spool(0.0f, 1f, 0.25f) > 0f);
    }

    @Test public void airplaneHasLiftPeakStallDecayAndFlapTradeoff() {
        float alphaNormal = (float) Math.toRadians(8);
        float alphaStall = (float) Math.toRadians(25);
        float normalCl = AirplaneForceModel.liftCoefficient(alphaNormal, 0f);
        float stalledCl = AirplaneForceModel.liftCoefficient(alphaStall, 0f);
        assertTrue(normalCl > 0.5f);
        assertTrue(stalledCl > 0f && stalledCl < normalCl);
        assertTrue(AirplaneForceModel.liftCoefficient(alphaNormal, 1f) > normalCl);
        assertTrue(AirplaneForceModel.dragCoefficient(normalCl, 1f)
                > AirplaneForceModel.dragCoefficient(normalCl, 0f));
        assertTrue(AirplaneForceModel.approximateStallSpeedMps(AirplaneForceModel.MASS_KG,
                AirplaneForceModel.WING_AREA_M2, 1f)
                < AirplaneForceModel.approximateStallSpeedMps(AirplaneForceModel.MASS_KG,
                AirplaneForceModel.WING_AREA_M2, 0f));
        AirplaneForceModel.Moments moments = AirplaneForceModel.controlMoments(0.5f, -0.25f, 0.2f, 600f);
        assertTrue(moments.rollNm > 0f);
        assertTrue(moments.pitchNm < 0f);
        assertTrue(moments.yawNm > 0f);
    }

    @Test public void fixedPhysicsClockIsIndependentOfRenderCadence() {
        FixedStepClock clockA = new FixedStepClock();
        FixedStepClock clockB = new FixedStepClock();
        final float[] va = {0f}, vb = {0f};
        for (int i = 0; i < 120; i++) clockA.advance(1f / 30f, dt -> va[0] += dt * 3f);
        for (int i = 0; i < 240; i++) clockB.advance(1f / 60f, dt -> vb[0] += dt * 3f);
        assertEquals(12f, va[0], 0.001f);
        assertEquals(va[0], vb[0], 0.001f);
    }
}
