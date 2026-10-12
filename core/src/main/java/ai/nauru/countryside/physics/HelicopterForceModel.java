package ai.nauru.countryside.physics;

/** Force-based rotor model for an original light utility helicopter. */
public final class HelicopterForceModel {
    public static final float MASS_KG = 780f;
    public static final float ROTOR_RADIUS_M = 5.55f;
    public static final float ROTOR_AREA_M2 = (float) Math.PI * ROTOR_RADIUS_M * ROTOR_RADIUS_M;
    public static final float AIR_DENSITY_KG_M3 = 1.225f;
    public static final float MAX_THRUST_N = MASS_KG * 9.81f * 1.68f;
    public static final float ROTOR_TIP_SPEED_MPS = 205f;
    public static final float THRUST_COEFFICIENT = MAX_THRUST_N
            / (AIR_DENSITY_KG_M3 * ROTOR_AREA_M2 * ROTOR_TIP_SPEED_MPS * ROTOR_TIP_SPEED_MPS);
    public static final float MAIN_ROTOR_REACTION_NM = 620f;
    private HelicopterForceModel() {}

    public static float thrust(float collective, float rotorRpmFraction, float inflowEfficiency) {
        float rpm = clamp(rotorRpmFraction, 0f, 1.15f);
        float coll = clamp(collective, 0f, 1f);
        float efficiency = clamp(inflowEfficiency, 0.35f, 1.25f);
        return THRUST_COEFFICIENT * AIR_DENSITY_KG_M3 * ROTOR_AREA_M2
                * ROTOR_TIP_SPEED_MPS * ROTOR_TIP_SPEED_MPS * coll * rpm * rpm * efficiency;
    }

    public static float hoverCollective(float massKg, float rotorRpmFraction, float efficiency) {
        float fullPower = thrust(1f, rotorRpmFraction, efficiency);
        return fullPower <= 1e-3f ? 1f : clamp(massKg * 9.81f / fullPower, 0f, 1f);
    }

    public static float groundEffect(float rotorHeightMetres) {
        float h = Math.max(0f, rotorHeightMetres);
        float effect = 0.12f * (float) Math.exp(-h / (ROTOR_RADIUS_M * 0.62f));
        return 1f + effect;
    }

    public static float reactionTorque(float collective, float pedalInput) {
        return -MAIN_ROTOR_REACTION_NM * clamp(collective, 0f, 1f)
                + pedalInput * 1_250f;
    }

    public static float spool(float currentFraction, float powerSwitch, float dtSeconds) {
        float target = powerSwitch > 0.5f ? 1f : 0f;
        float tau = target > currentFraction ? 4.5f : 7.0f;
        float blend = 1f - (float) Math.exp(-Math.max(0f, dtSeconds) / tau);
        return clamp(currentFraction + (target - currentFraction) * blend, 0f, 1f);
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
