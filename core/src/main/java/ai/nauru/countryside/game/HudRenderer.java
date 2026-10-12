package ai.nauru.countryside.game;

import ai.nauru.countryside.physics.PerformanceGovernor;
import ai.nauru.countryside.physics.PhysicsWorld;
import ai.nauru.countryside.world.WorldData;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/** Native-resolution, no-texture touch HUD with data attribution and a real-world mini-map. */
final class HudRenderer implements Disposable {
    private static final String[] BUTTON_LABELS = {"USE", "AUX", "REC", "CAM", "MAP", "GO", "BRK", "", "DBG"};
    private final WorldData data;
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final BitmapFont font = new BitmapFont();
    private final Matrix4 projection = new Matrix4();
    private final Vector3 mapPoint = new Vector3();
    private final float minMapX, maxMapX, minMapZ, maxMapZ;
    private float width = 1f, height = 1f, unit = 1f;

    HudRenderer(WorldData data) {
        this.data = data;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        if (data != null) {
            for (int i = 0; i < data.coastlineXZ.length; i += 2) {
                data.transform.map(data.coastlineXZ[i], 0f, data.coastlineXZ[i + 1], mapPoint);
                minX = Math.min(minX, mapPoint.x); maxX = Math.max(maxX, mapPoint.x);
                minZ = Math.min(minZ, mapPoint.z); maxZ = Math.max(maxZ, mapPoint.z);
            }
        }
        if (minX == Float.MAX_VALUE) { minX = minZ = -1f; maxX = maxZ = 1f; }
        minMapX = minX; maxMapX = maxX; minMapZ = minZ; maxMapZ = maxZ;
        font.setColor(Color.WHITE);
        font.getData().setScale(1f);
        shapes.setAutoShapeType(true);
    }

    void resize(float width, float height) {
        this.width = Math.max(1f, width);
        this.height = Math.max(1f, height);
        unit = Math.max(0.72f, Math.min(1.75f, Math.min(this.width / 1_000f, this.height / 560f)));
        projection.setToOrtho2D(0f, 0f, this.width, this.height);
        font.getData().setScale(unit);
        batch.setProjectionMatrix(projection);
        shapes.setProjectionMatrix(projection);
    }

    void render(int mode, float speedMps, float altitude, String place,
                PerformanceGovernor governor, float simTime, int fps, int targetFps,
                boolean mapVisible, boolean diagnostics, boolean paused, boolean engineOn, int gear,
                float rpm, long droppedSteps, float playerX, float playerZ,
                TouchLayout layout, float stickX, float stickY,
                boolean goHeld, boolean brakeHeld, String loadError) {
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        drawShapes(mode, mapVisible, layout, stickX, stickY, goHeld, brakeHeld, playerX, playerZ);
        batch.begin();
        if (loadError != null) {
            font.setColor(0.96f, 0.72f, 0.36f, 1f);
            font.draw(batch, "WORLD STARTUP FAILED", 28f * unit, height - 40f * unit);
            font.setColor(Color.WHITE);
            font.draw(batch, loadError, 28f * unit, height - 72f * unit, width - 56f * unit, 6, true);
            font.draw(batch, "Install the complete build with its offline world package.",
                    28f * unit, height - 154f * unit);
            font.draw(batch, "© OpenStreetMap contributors", width - 250f * unit, 22f * unit);
            batch.end();
            return;
        }

        String modeName = modeName(mode);
        font.setColor(0.95f, 0.97f, 0.93f, 1f);
        font.draw(batch, "NAURU  /  THE COUNTRYSIDE", 22f * unit, height - 23f * unit);
        font.setColor(0.72f, 0.88f, 0.82f, 1f);
        font.draw(batch, modeName + (engineOn && isAircraft(mode) ? "  ·  ENGINE ON" : paused ? "  ·  PAUSED" : ""),
                22f * unit, height - 46f * unit);
        String location = place == null || place.isEmpty() ? "Open island" : place;
        font.setColor(Color.WHITE);
        font.draw(batch, location, 22f * unit, height - 69f * unit);
        String speedLine = Math.round(Math.max(0f, Math.abs(speedMps) * 3.6f))
                + " km/h   ALT " + Math.round(altitude) + " m";
        if (mode == PhysicsWorld.CAR) speedLine += "   G" + gear + "   " + (int) rpm + " rpm";
        else if (mode == PhysicsWorld.HELICOPTER) speedLine += "   ROTOR " + (int) rpm + "%";
        else if (mode == PhysicsWorld.AIRPLANE) speedLine += "   PROP " + (int) rpm + "%";
        font.setColor(0.89f, 0.92f, 0.86f, 1f);
        font.draw(batch, speedLine, 22f * unit, height - 92f * unit);

        font.setColor(0.78f, 0.85f, 0.86f, 1f);
        String quality = governor.active.name() + "  " + targetFps + " FPS  "
                + (governor.thermalStatus >= 3 ? "SEVERE THERMAL" : governor.batterySaver ? "BATTERY SAVER" : "OFFLINE");
        font.draw(batch, quality, 22f * unit, height - 115f * unit);
        font.draw(batch, "Touch left stick to move/steer · drag right side to look · USE to board/exit",
                22f * unit, 24f * unit);
        font.setColor(0.90f, 0.91f, 0.86f, 1f);
        font.draw(batch, "© OpenStreetMap contributors", width - 247f * unit, 22f * unit);
        if (diagnostics) {
            font.setColor(1f, 0.79f, 0.42f, 1f);
            int timeTenths = Math.round(simTime * 10f);
            font.draw(batch, "FPS " + fps + " / " + targetFps + "   SIM " + (timeTenths / 10) + "." + (timeTenths % 10)
                    + " s   THERMAL " + governor.thermalStatus + "   DROPPED " + droppedSteps,
                    22f * unit, height - 140f * unit);
        }
        font.setColor(0.97f, 0.96f, 0.88f, 1f);
        for (int i = 0; i < BUTTON_LABELS.length; i++) {
            String label = i == TouchLayout.PROFILE ? governor.active.name() : BUTTON_LABELS[i];
            float labelWidth = label.length() * 5.0f * unit;
            font.draw(batch, label, layout.buttonX[i] - labelWidth * 0.5f,
                    layout.buttonY[i] + 3.5f * unit);
        }
        batch.end();
    }

    private void drawShapes(int mode, boolean mapVisible, TouchLayout layout,
                            float stickX, float stickY, boolean goHeld, boolean brakeHeld,
                            float playerX, float playerZ) {
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.035f, 0.08f, 0.10f, 0.73f);
        shapes.rect(12f * unit, height - 128f * unit, 370f * unit, 119f * unit);
        shapes.setColor(0.02f, 0.10f, 0.12f, 0.67f);
        for (int i = 0; i < layout.buttonX.length; i++) {
            float alpha = i == TouchLayout.GO && goHeld || i == TouchLayout.BRAKE && brakeHeld ? 0.9f : 0.55f;
            shapes.setColor(i == TouchLayout.INTERACT ? 0.16f : 0.08f,
                    i == TouchLayout.INTERACT ? 0.42f : 0.25f,
                    i == TouchLayout.INTERACT ? 0.40f : 0.31f, alpha);
            shapes.circle(layout.buttonX[i], layout.buttonY[i], layout.buttonR[i], 24);
        }
        shapes.setColor(0.06f, 0.12f, 0.14f, 0.34f);
        shapes.circle(layout.stickX, layout.stickY, layout.stickRadius, 32);
        shapes.setColor(0.52f, 0.75f, 0.72f, 0.46f);
        shapes.circle(layout.stickX + stickX * layout.stickRadius * 0.72f,
                layout.stickY + stickY * layout.stickRadius * 0.72f, 21f * unit, 20);
        if (mapVisible) drawMiniMapFilled();
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.65f, 0.83f, 0.81f, 0.75f);
        shapes.circle(layout.stickX, layout.stickY, layout.stickRadius, 32);
        shapes.setColor(0.78f, 0.88f, 0.84f, 0.7f);
        for (int i = 0; i < layout.buttonX.length; i++)
            shapes.circle(layout.buttonX[i], layout.buttonY[i], layout.buttonR[i], 24);
        if (mapVisible) drawMiniMapLines(playerX, playerZ);
        shapes.end();
    }

    private void drawMiniMapFilled() {
        float x = width - 184f * unit, y = height - 157f * unit;
        shapes.setColor(0.025f, 0.16f, 0.20f, 0.82f);
        shapes.rect(x, y, 164f * unit, 111f * unit);
    }

    private void drawMiniMapLines(float playerX, float playerZ) {
        float x = width - 184f * unit, y = height - 157f * unit;
        float w = 164f * unit, h = 111f * unit;
        shapes.setColor(0.77f, 0.84f, 0.68f, 0.95f);
        int count = data.coastlineXZ.length / 2;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            data.transform.map(data.coastlineXZ[i * 2], 0f, data.coastlineXZ[i * 2 + 1], mapPoint);
            float ax = x + (mapPoint.x - minMapX) / Math.max(0.01f, maxMapX - minMapX) * w;
            float ay = y + (mapPoint.z - minMapZ) / Math.max(0.01f, maxMapZ - minMapZ) * h;
            data.transform.map(data.coastlineXZ[j * 2], 0f, data.coastlineXZ[j * 2 + 1], mapPoint);
            float bx = x + (mapPoint.x - minMapX) / Math.max(0.01f, maxMapX - minMapX) * w;
            float by = y + (mapPoint.z - minMapZ) / Math.max(0.01f, maxMapZ - minMapZ) * h;
            shapes.line(ax, ay, bx, by);
        }
        shapes.setColor(1f, 0.36f, 0.16f, 1f);
        float playerMapX = x + (playerX - minMapX) / Math.max(0.01f, maxMapX - minMapX) * w;
        float playerMapY = y + (playerZ - minMapZ) / Math.max(0.01f, maxMapZ - minMapZ) * h;
        shapes.circle(playerMapX, playerMapY, 4f * unit, 12);
        shapes.setColor(0.95f, 0.85f, 0.38f, 0.9f);
        shapes.rect(x, y + h + 4f * unit, 164f * unit, 20f * unit);
    }

    private static String modeName(int mode) {
        switch (mode) {
            case PhysicsWorld.CAR: return "ISLAND 4×4";
            case PhysicsWorld.BOAT: return "LAGOON WORKBOAT";
            case PhysicsWorld.HELICOPTER: return "UTILITY HELICOPTER";
            case PhysicsWorld.AIRPLANE: return "STOL LIGHT AIRCRAFT";
            default: return "WALKING";
        }
    }

    private static boolean isAircraft(int mode) {
        return mode == PhysicsWorld.HELICOPTER || mode == PhysicsWorld.AIRPLANE;
    }

    @Override public void dispose() {
        font.dispose();
        batch.dispose();
        shapes.dispose();
    }
}
