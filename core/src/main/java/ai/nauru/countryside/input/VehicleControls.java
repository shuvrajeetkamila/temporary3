package ai.nauru.countryside.input;

/** Mutable, allocation-free input sample shared by touch, keys, gamepads, and force models. */
public final class VehicleControls {
    public float moveX;
    public float moveY;
    public float lookX;
    public float lookY;
    public float throttle;
    public float brake;
    public float steer;
    public float handbrake;
    public float collective;
    public float cyclicPitch;
    public float cyclicRoll;
    public float yaw;
    public float flaps;
    public boolean engineOn;
    public boolean jumpPressed;
    public boolean interactPressed;
    public boolean recoverPressed;
    public boolean toggleCameraPressed;
    public boolean mapPressed;
    public boolean diagnosticsPressed;
    public boolean profilePressed;

    public void clearEdges() {
        jumpPressed = false;
        interactPressed = false;
        recoverPressed = false;
        toggleCameraPressed = false;
        mapPressed = false;
        diagnosticsPressed = false;
        profilePressed = false;
    }

    public void reset() {
        moveX = moveY = lookX = lookY = 0f;
        throttle = brake = steer = handbrake = 0f;
        collective = cyclicPitch = cyclicRoll = yaw = flaps = 0f;
        engineOn = false;
        clearEdges();
    }
}
