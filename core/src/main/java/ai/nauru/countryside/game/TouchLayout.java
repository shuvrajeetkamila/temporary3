package ai.nauru.countryside.game;

/** Shared screen-space layout for touch hit targets and their rendered affordances. */
final class TouchLayout {
    static final int INTERACT = 0, AUX = 1, RECOVER = 2, CAMERA = 3, MAP = 4, GO = 5, BRAKE = 6, PROFILE = 7, DIAGNOSTICS = 8;
    static final int STICK = 1, LOOK = 2, BUTTON = 3;
    final float[] buttonX = new float[9];
    final float[] buttonY = new float[9];
    final float[] buttonR = new float[9];
    float width, height, unit;
    float stickX, stickY, stickRadius;

    void resize(float width, float height) {
        this.width = Math.max(1f, width);
        this.height = Math.max(1f, height);
        unit = Math.max(0.72f, Math.min(1.75f, Math.min(this.width / 1_000f, this.height / 560f)));
        stickX = 112f * unit;
        stickY = 101f * unit;
        stickRadius = 66f * unit;
        float r = 29f * unit;
        set(INTERACT, this.width * 0.575f, 105f * unit, r);
        set(AUX, this.width * 0.675f, 201f * unit, r);
        set(RECOVER, this.width * 0.765f, 201f * unit, r);
        set(CAMERA, this.width * 0.855f, 201f * unit, r);
        set(MAP, this.width * 0.945f, 201f * unit, r);
        set(GO, this.width * 0.910f, 103f * unit, 34f * unit);
        set(BRAKE, this.width * 0.805f, 103f * unit, 34f * unit);
        set(PROFILE, this.width - 46f * unit, this.height - 36f * unit, 24f * unit);
        set(DIAGNOSTICS, this.width - 104f * unit, this.height - 36f * unit, 24f * unit);
    }

    private void set(int id, float x, float y, float radius) {
        buttonX[id] = x; buttonY[id] = y; buttonR[id] = radius;
    }

    int buttonAt(float x, float y) {
        for (int i = 0; i < buttonX.length; i++) {
            float dx = x - buttonX[i], dy = y - buttonY[i];
            if (dx * dx + dy * dy <= buttonR[i] * buttonR[i]) return i;
        }
        return -1;
    }

    boolean stickArea(float x, float y) {
        return x < width * 0.42f && y < height * 0.62f;
    }

    boolean lookArea(float x, float y) {
        return x > width * 0.43f && y < height * 0.72f;
    }
}
