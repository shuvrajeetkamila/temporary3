package ai.nauru.countryside.physics;

/** Analytic waves and distributed buoyancy for the compact island workboat. */
public final class BoatForceModel {
    public static final float HULL_MASS_KG = 420f;
    public static final float WATER_DENSITY_KG_M3 = 1_025f;
    public static final float GRAVITY_M_S2 = 9.81f;
    public static final int BUOYANCY_SAMPLES = 8;
    private BoatForceModel() {}

    public static float waveHeight(float x, float z, float timeSeconds) {
        return 0.36f * (float) Math.sin(0.055f * x + 0.72f * timeSeconds)
             + 0.19f * (float) Math.sin(0.091f * z - 0.51f * timeSeconds)
             + 0.09f * (float) Math.sin(0.038f * (x + z) + 0.31f * timeSeconds);
    }

    public static float displacedBuoyancy(float submergedFraction, float displacedVolumeM3) {
        return WATER_DENSITY_KG_M3 * displacedVolumeM3 * GRAVITY_M_S2
                * clamp(submergedFraction, 0f, 1f);
    }

    public static float verticalDamping(float verticalSpeedMps, float dampingNsPerM) {
        return -verticalSpeedMps * dampingNsPerM;
    }

    public static float planingLift(float speedMps, float wettedAreaM2, float liftCoefficient) {
        float speed = Math.max(0f, speedMps);
        float q = 0.5f * WATER_DENSITY_KG_M3 * speed * speed;
        return Math.max(0f, q * wettedAreaM2 * clamp(liftCoefficient, 0f, 0.35f));
    }

    public static float hydrodynamicDrag(float forwardSpeed, float lateralSpeed,
                                         float forwardCoefficient, float lateralCoefficient) {
        return -forwardCoefficient * forwardSpeed * Math.abs(forwardSpeed)
                - lateralCoefficient * lateralSpeed * Math.abs(lateralSpeed);
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
