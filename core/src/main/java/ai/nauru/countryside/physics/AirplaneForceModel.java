package ai.nauru.countryside.physics;

/** SI force curves for the original single-engine STOL aircraft. */
public final class AirplaneForceModel {
    public static final float MASS_KG = 850f;
    public static final float WING_AREA_M2 = 16.2f;
    public static final float WING_SPAN_M = 10.4f;
    public static final float STALL_ANGLE_RAD = (float) Math.toRadians(15.0);
    public static final float MAX_CL = 1.42f;
    public static final float CD0 = 0.034f;
    public static final float INDUCED_DRAG_FACTOR = 0.055f;
    public static final float MAX_THRUST_N = 1_150f;
    private static final float AIR_DENSITY = 1.225f;
    private AirplaneForceModel() {}

    public static float liftCoefficient(float angleOfAttackRad, float flapFraction) {
        float flap = clamp(flapFraction, 0f, 1f);
        float alpha = angleOfAttackRad;
        float base = 0.08f + 4.5f * alpha;
        float flapLift = 0.42f * flap;
        float cl;
        if (Math.abs(alpha) <= STALL_ANGLE_RAD) {
            cl = base + flapLift;
        } else {
            float beyond = Math.abs(alpha) - STALL_ANGLE_RAD;
            float atStall = (alpha >= 0 ? 1f : -1f) * MAX_CL + flapLift;
            float decay = (float) Math.exp(-beyond * 5.0f);
            cl = atStall * decay;
        }
        return clamp(cl, -MAX_CL, MAX_CL + 0.42f * flap);
    }

    public static float dragCoefficient(float liftCoefficient, float flapFraction) {
        float cl = liftCoefficient;
        float flap = clamp(flapFraction, 0f, 1f);
        return CD0 + 0.055f * flap + INDUCED_DRAG_FACTOR * cl * cl;
    }

    public static float dynamicPressure(float airspeedMps) {
        float speed = Math.abs(airspeedMps);
        return 0.5f * AIR_DENSITY * speed * speed;
    }

    public static float liftNewtons(float airspeedMps, float angleOfAttackRad, float flapFraction) {
        return dynamicPressure(airspeedMps) * WING_AREA_M2
                * liftCoefficient(angleOfAttackRad, flapFraction);
    }

    public static float dragNewtons(float airspeedMps, float angleOfAttackRad, float flapFraction) {
        float q = dynamicPressure(airspeedMps);
        float cl = liftCoefficient(angleOfAttackRad, flapFraction);
        return q * WING_AREA_M2 * dragCoefficient(cl, flapFraction);
    }

    public static float propellerThrust(float throttle, float rpmFraction, float airspeedMps) {
        float power = clamp(throttle, 0f, 1f) * clamp(rpmFraction, 0f, 1.1f);
        float ramLoss = clamp(1f - Math.max(0f, airspeedMps) / 145f, 0.52f, 1f);
        return MAX_THRUST_N * power * ramLoss;
    }

    /** Returns signed physical moments; positive aileron commands right-wing-down roll. */
    public static Moments controlMoments(float aileron, float elevator, float rudder,
                                         float dynamicPressure) {
        return controlMoments(aileron, elevator, rudder, dynamicPressure, new Moments(0f, 0f, 0f));
    }

    public static Moments controlMoments(float aileron, float elevator, float rudder,
                                         float dynamicPressure, Moments out) {
        float qScale = clamp(dynamicPressure / 600f, 0f, 2f);
        out.rollNm = 4_200f * clamp(aileron, -1f, 1f) * qScale;
        out.pitchNm = 5_500f * clamp(elevator, -1f, 1f) * qScale;
        out.yawNm = 2_200f * clamp(rudder, -1f, 1f) * qScale;
        return out;
    }

    public static float approximateStallSpeedMps(float massKg, float wingAreaM2, float flapFraction) {
        float clMax = MAX_CL + 0.42f * clamp(flapFraction, 0f, 1f);
        return (float) Math.sqrt((2f * massKg * 9.81f) / (AIR_DENSITY * wingAreaM2 * clMax));
    }

    public static final class Moments {
        public float rollNm, pitchNm, yawNm;
        public Moments(float rollNm, float pitchNm, float yawNm) {
            this.rollNm = rollNm; this.pitchNm = pitchNm; this.yawNm = yawNm;
        }
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
