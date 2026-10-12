package ai.nauru.countryside.physics;

/** Documented SI-unit helpers for the compact island 4x4. */
public final class CarForceModel {
    public static final float MASS_KG = 1_180f;
    public static final float SPRING_N_PER_M = 24_000f;
    public static final float DAMPER_NS_PER_M = 3_100f;
    public static final float MAX_WHEEL_LOAD_N = 5_800f;
    public static final float WHEELBASE_M = 2.55f;
    public static final float TRACK_M = 1.58f;
    public static final float DRY_GRIP = 0.92f;
    public static final float WET_GRIP = 0.58f;
    public static final float MAX_ENGINE_TORQUE_NM = 205f;
    private CarForceModel() {}

    public static float suspensionLoad(float compressionMetres, float relativeSuspensionVelocity,
                                       float springRate, float damperRate, float maxLoad) {
        return clamp(springRate * compressionMetres - damperRate * relativeSuspensionVelocity, 0f, maxLoad);
    }

    /** Saturating friction circle: combined longitudinal and lateral tyre force never exceeds grip. */
    public static float[] frictionCircle(float requestedLongitudinal, float requestedLateral,
                                         float normalLoad, float coefficient) {
        float limit = Math.max(0f, normalLoad * coefficient);
        float magnitude = (float) Math.sqrt(requestedLongitudinal * requestedLongitudinal
                + requestedLateral * requestedLateral);
        if (magnitude > limit && magnitude > 1e-5f) {
            float scale = limit / magnitude;
            return new float[]{requestedLongitudinal * scale, requestedLateral * scale};
        }
        return new float[]{requestedLongitudinal, requestedLateral};
    }

    public static float engineTorque(float rpm, float throttle) {
        float t = clamp(throttle, 0f, 1f);
        float normalizedRpm = clamp((rpm - 900f) / (5_200f - 900f), 0f, 1f);
        float torqueShape = 0.72f + 0.28f * (float) Math.sin(Math.PI * normalizedRpm);
        return MAX_ENGINE_TORQUE_NM * torqueShape * t;
    }

    public static float dragNewtons(float speedMps, float dragAreaCd) {
        return 0.5f * 1.225f * dragAreaCd * speedMps * Math.abs(speedMps);
    }

    public static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
