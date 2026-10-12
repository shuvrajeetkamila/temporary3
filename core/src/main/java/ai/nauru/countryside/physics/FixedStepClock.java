package ai.nauru.countryside.physics;

/** Render-rate-independent 60 Hz simulation accumulator with bounded catch-up. */
public final class FixedStepClock {
    public static final float FIXED_DT = 1f / 60f;
    public static final int MAX_SUBSTEPS = 4;
    private float accumulator;
    private long droppedSteps;

    public int advance(float frameDt, Step step) {
        float safeFrame = Math.min(Math.max(frameDt, 0f), 0.1f);
        accumulator += safeFrame;
        int count = 0;
        while (accumulator >= FIXED_DT && count < MAX_SUBSTEPS) {
            step.run(FIXED_DT);
            accumulator -= FIXED_DT;
            count++;
        }
        if (accumulator >= FIXED_DT) {
            int dropped = (int) (accumulator / FIXED_DT);
            droppedSteps += dropped;
            accumulator -= dropped * FIXED_DT;
        }
        return count;
    }

    public float interpolationAlpha() { return accumulator / FIXED_DT; }
    public long droppedSteps() { return droppedSteps; }
    public void reset() { accumulator = 0f; }

    public interface Step { void run(float fixedDt); }
}
