package ai.nauru.countryside.physics;

/** Low-rate thermal/battery policy; quality work is reduced before simulation accuracy. */
public final class PerformanceGovernor {
    public enum Profile { LOW, BALANCED, HIGH, ECO }
    public Profile requested = Profile.BALANCED;
    public Profile active = Profile.BALANCED;
    public int thermalStatus;
    public boolean batterySaver;
    public int targetFps = 30;
    public float renderScale = 1f;
    public float worldViewDistance = 950f;
    public int particleBudget = 140;
    public boolean dynamicShadows = false;
    private float nextPollSeconds;

    public void setSystemState(boolean saver, int thermal) {
        batterySaver = saver;
        thermalStatus = Math.max(0, thermal);
        recompute();
    }

    public void setRequestedProfile(Profile profile) {
        requested = profile == null ? Profile.BALANCED : profile;
        recompute();
    }

    public void update(float dt, ApplyFrameRate apply) {
        nextPollSeconds -= dt;
        if (nextPollSeconds <= 0f) {
            nextPollSeconds = 5f;
            recompute();
            apply.setForegroundFps(targetFps);
        }
    }

    private void recompute() {
        boolean moderate = batterySaver || thermalStatus >= 2;
        boolean severe = thermalStatus >= 3;
        if (severe) active = Profile.ECO;
        else if (moderate) active = Profile.ECO;
        else active = requested;

        if (severe) {
            targetFps = 15;
            renderScale = 0.58f;
            worldViewDistance = 320f;
            particleBudget = 18;
            dynamicShadows = false;
        } else if (moderate || requested == Profile.ECO) {
            targetFps = 20;
            renderScale = 0.68f;
            worldViewDistance = 420f;
            particleBudget = 32;
            dynamicShadows = false;
        } else if (requested == Profile.LOW) {
            targetFps = 30;
            renderScale = 0.76f;
            worldViewDistance = 520f;
            particleBudget = 48;
            dynamicShadows = false;
        } else if (requested == Profile.HIGH) {
            targetFps = 30;
            renderScale = 1f;
            worldViewDistance = 1_100f;
            particleBudget = 180;
            dynamicShadows = false;
        } else {
            targetFps = 30;
            renderScale = 0.86f;
            worldViewDistance = 780f;
            particleBudget = 90;
            dynamicShadows = false;
        }
    }

    public interface ApplyFrameRate { void setForegroundFps(int fps); }
}
