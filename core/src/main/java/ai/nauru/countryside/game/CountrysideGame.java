package ai.nauru.countryside.game;

import ai.nauru.countryside.input.VehicleControls;
import ai.nauru.countryside.physics.FixedStepClock;
import ai.nauru.countryside.physics.PerformanceGovernor;
import ai.nauru.countryside.physics.PhysicsWorld;
import ai.nauru.countryside.render.SceneRenderer;
import ai.nauru.countryside.world.WorldData;
import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.math.Vector3;

import java.io.IOException;

/** Offline gameplay session, input router, and bounded fixed-step simulation owner. */
public final class CountrysideGame extends Game implements InputProcessor {
    public static final int PAD_A = 1, PAD_B = 1 << 1, PAD_X = 1 << 2, PAD_Y = 1 << 3,
            PAD_L1 = 1 << 4, PAD_R1 = 1 << 5, PAD_START = 1 << 6, PAD_SELECT = 1 << 7;

    private final VehicleControls controls = new VehicleControls();
    private final FixedStepClock fixedClock = new FixedStepClock();
    private final PerformanceGovernor governor = new PerformanceGovernor();
    private final TouchLayout layout = new TouchLayout();
    private final boolean[] keys = new boolean[512];
    private final TouchPointer[] pointers = new TouchPointer[20];
    private final Vector3 candidatePosition = new Vector3();
    private final Vector3 characterPosition = new Vector3();
    private final Vector3 exitPosition = new Vector3();
    private final Vector3 rightVector = new Vector3();
    private final FixedStepClock.Step fixedStep = dt -> {
        simTime += dt;
        if (physics != null) physics.step(controls, mode, simTime);
        controls.clearEdges();
        jumpPendingForStep = false;
    };
    private WorldData worldData;
    private PhysicsWorld physics;
    private SceneRenderer scene;
    private HudRenderer hud;
    private String loadError;
    private int mode = PhysicsWorld.WALK;
    private float simTime;
    private float cameraYaw;
    private float cameraLift;
    private float leftX, leftY, lookX, touchYawDelta;
    private boolean gasHeld, brakeHeld;
    private boolean mapVisible;
    private boolean diagnostics;
    private boolean manuallyPaused;
    private boolean lifecyclePaused;
    private boolean helicopterEngineOn;
    private boolean airplaneEngineOn;
    private boolean jumpPendingForStep;
    private boolean pendingInteract, pendingAux, pendingRecover, pendingCamera, pendingMap, pendingDiagnostics, pendingProfile;
    private float cameraTouchX, cameraTouchY;
    private long gamepadButtons;
    private int previousGamepadButtons;
    private volatile float gamepadLeftX, gamepadLeftY, gamepadRightX, gamepadRightY;
    private volatile float gamepadGas, gamepadBrake;
    private volatile int publishedGamepadButtons;
    private volatile boolean pendingBatterySaver;
    private volatile int pendingThermalStatus;
    private volatile boolean pendingPressure;
    private float qualityDistance = Float.NaN;
    private float qualityScale = Float.NaN;

    @Override public void create() {
        Gdx.graphics.setContinuousRendering(true);
        Gdx.graphics.setForegroundFPS(30);
        Gdx.input.setInputProcessor(this);
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        layout.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        for (int i = 0; i < pointers.length; i++) pointers[i] = new TouchPointer();
        try {
            worldData = WorldData.load(Gdx.files.internal("compiled/world.bin"));
            physics = new PhysicsWorld(worldData);
            scene = new SceneRenderer(worldData, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            hud = new HudRenderer(worldData);
            hud.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            applyQuality();
            Gdx.app.log("Nauru Countryside", "Loaded offline NCRW world sha256=" + worldData.sha256
                    + " features=" + worldData.features.size());
        } catch (IOException | RuntimeException failure) {
            loadError = "Could not load compiled/world.bin: " + failure.getMessage();
            Gdx.app.error("Nauru Countryside", loadError, failure);
            hud = new HudRenderer(worldData);
            hud.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        }
        if (pendingPressure) governor.setSystemState(pendingBatterySaver, pendingThermalStatus);
    }

    @Override public void render() {
        if (lifecyclePaused) return;
        float frameDt = Math.min(0.1f, Math.max(0f, Gdx.graphics.getDeltaTime()));
        if (pendingPressure) {
            pendingPressure = false;
            governor.setSystemState(pendingBatterySaver, pendingThermalStatus);
        }
        governor.update(frameDt, fps -> Gdx.graphics.setForegroundFPS(fps));
        if (scene == null || physics == null || loadError != null) {
            if (hud != null) {
                hud.render(mode, 0f, 0f, "", governor, simTime,
                        Gdx.graphics.getFramesPerSecond(), governor.targetFps, false,
                        diagnostics, false, false, 0, 0f, fixedClock.droppedSteps(), 0f, 0f,
                        layout, leftX, leftY, gasHeld, brakeHeld, loadError);
            }
            return;
        }
        if (!manuallyPaused) {
            pollGamepadEdges();
            processOneShotActions();
            readControls(frameDt);
            applyQuality();
            fixedClock.advance(frameDt, fixedStep);
        }
        PhysicsWorld.Body controlled = physics.bodyForMode(mode);
        Vector3 hudPosition = physics.position(controlled, candidatePosition);
        float speed = physics.speedMps(controlled);
        String place = scene.updateAndRender(physics, mode, cameraYaw, cameraLift, frameDt, simTime);
        hud.render(mode, speed, hudPosition.y, place, governor, simTime,
                Gdx.graphics.getFramesPerSecond(), governor.targetFps, mapVisible,
                diagnostics, manuallyPaused, mode == PhysicsWorld.HELICOPTER ? helicopterEngineOn
                        : mode == PhysicsWorld.AIRPLANE && airplaneEngineOn,
                physics.carGear(), mode == PhysicsWorld.CAR ? physics.carRpm()
                        : mode == PhysicsWorld.HELICOPTER ? physics.rotorRpm() : physics.aircraftRpm(),
                fixedClock.droppedSteps(), hudPosition.x, hudPosition.z, layout, leftX, leftY,
                gasHeld, brakeHeld, null);
    }

    private void readControls(float frameDt) {
        leftX = 0f; leftY = 0f; lookX = 0f;
        gasHeld = false; brakeHeld = false;
        for (int i = 0; i < pointers.length; i++) {
            TouchPointer pointer = pointers[i];
            if (!pointer.active) continue;
            if (pointer.type == TouchLayout.STICK) {
                leftX = clamp((pointer.x - layout.stickX) / layout.stickRadius, -1f, 1f);
                leftY = clamp((pointer.y - layout.stickY) / layout.stickRadius, -1f, 1f);
            } else if (pointer.type == TouchLayout.LOOK) {
                // Look drag is applied incrementally in touchDragged; vertical orbit adjusts shoulder height.
            } else if (pointer.type == TouchLayout.BUTTON) {
                if (pointer.button == TouchLayout.GO) gasHeld = true;
                if (pointer.button == TouchLayout.BRAKE) brakeHeld = true;
            }
        }
        leftX = combine(leftX, gamepadLeftX);
        leftY = combine(leftY, -gamepadLeftY);
        float padLookX = applyDeadZone(gamepadRightX);
        float padLookY = applyDeadZone(gamepadRightY);
        cameraYaw -= padLookX * 1.75f * frameDt;
        cameraLift = clamp(cameraLift + padLookY * 2.5f * frameDt, -2.0f, 7.0f);

        float keyX = (key(Input.Keys.D) || key(Input.Keys.RIGHT) ? 1f : 0f)
                - (key(Input.Keys.A) || key(Input.Keys.LEFT) ? 1f : 0f);
        float keyY = (key(Input.Keys.W) || key(Input.Keys.UP) ? 1f : 0f)
                - (key(Input.Keys.S) || key(Input.Keys.DOWN) ? 1f : 0f);
        leftX = clamp(leftX + keyX, -1f, 1f);
        leftY = clamp(leftY + keyY, -1f, 1f);
        int padHeld = (int) gamepadButtons;
        gasHeld |= key(Input.Keys.SHIFT_LEFT) || key(Input.Keys.SHIFT_RIGHT)
                || gamepadGas > 0.5f || (padHeld & PAD_R1) != 0;
        brakeHeld |= key(Input.Keys.CONTROL_LEFT) || key(Input.Keys.CONTROL_RIGHT)
                || gamepadBrake > 0.5f || (padHeld & PAD_L1) != 0;

        controls.moveX = 0f;
        controls.moveY = 0f;
        controls.steer = 0f;
        controls.throttle = 0f;
        controls.brake = 0f;
        controls.handbrake = 0f;
        controls.collective = 0f;
        controls.cyclicPitch = 0f;
        controls.cyclicRoll = 0f;
        controls.yaw = 0f;
        controls.flaps = 0f;
        controls.engineOn = mode == PhysicsWorld.HELICOPTER ? helicopterEngineOn
                : mode == PhysicsWorld.AIRPLANE && airplaneEngineOn;
        controls.jumpPressed = false;
        controls.interactPressed = false;
        controls.recoverPressed = false;
        controls.toggleCameraPressed = false;
        controls.mapPressed = false;
        controls.diagnosticsPressed = false;
        controls.profilePressed = false;
        if (mode == PhysicsWorld.WALK) {
            float sin = (float) Math.sin(cameraYaw), cos = (float) Math.cos(cameraYaw);
            controls.moveX = leftX * cos - leftY * sin;
            controls.moveY = -leftX * sin - leftY * cos;
        } else if (mode == PhysicsWorld.HELICOPTER) {
            controls.cyclicRoll = leftX;
            controls.cyclicPitch = leftY;
            controls.collective = clamp(0.53f + (gasHeld ? 0.47f : 0f)
                    - (brakeHeld ? 0.47f : 0f), 0f, 1f);
            controls.yaw = clamp(touchYawDelta + (key(Input.Keys.E) ? 1f : 0f)
                    - (key(Input.Keys.Q) ? 1f : 0f) + applyDeadZone(gamepadRightX) * 0.45f, -1f, 1f);
        } else if (mode == PhysicsWorld.AIRPLANE) {
            controls.cyclicRoll = leftX;
            controls.cyclicPitch = leftY;
            controls.yaw = (key(Input.Keys.E) ? 1f : 0f) - (key(Input.Keys.Q) ? 1f : 0f);
            controls.throttle = gasHeld ? 1f : clamp(Math.max(0f, leftY) * 0.30f, 0f, 0.45f);
            controls.brake = brakeHeld ? 0.75f : 0f;
            controls.flaps = brakeHeld ? 0.35f : 0f;
        } else if (mode == PhysicsWorld.CAR) {
            controls.steer = leftX;
            controls.throttle = gasHeld ? 1f : clamp(Math.max(0f, leftY) * 0.82f, 0f, 1f);
            float carSpeed = physics.speedMps(physics.car);
            if (!gasHeld && leftY < -0.28f && carSpeed < 1.4f) controls.throttle = -0.42f;
            controls.brake = brakeHeld ? 1f
                    : clamp(Math.max(0f, -leftY) * 0.72f, 0f, 0.9f);
            if (controls.throttle < 0f) controls.brake = brakeHeld ? 1f : 0f;
            controls.handbrake = key(Input.Keys.SPACE) ? 1f : 0f;
        } else {
            controls.steer = leftX;
            controls.throttle = gasHeld ? 1f : clamp(leftY * 0.78f, -0.65f, 1f);
            controls.brake = brakeHeld ? 1f : 0f;
        }
        if (mode == PhysicsWorld.AIRPLANE)
            controls.yaw = clamp(touchYawDelta + (key(Input.Keys.E) ? 1f : 0f)
                    - (key(Input.Keys.Q) ? 1f : 0f) + applyDeadZone(gamepadRightX) * 0.45f, -1f, 1f);
        touchYawDelta = 0f;
        controls.interactPressed = pendingInteract;
        controls.jumpPressed = mode == PhysicsWorld.WALK && jumpPendingForStep;
        controls.recoverPressed = pendingRecover;
        controls.toggleCameraPressed = pendingCamera;
        controls.mapPressed = pendingMap;
        controls.diagnosticsPressed = pendingDiagnostics;
        controls.profilePressed = pendingProfile;
    }

    private void processOneShotActions() {
        if (pendingAux && mode == PhysicsWorld.WALK) jumpPendingForStep = true;
        if (pendingInteract) interact();
        if (pendingRecover) {
            if (mode == PhysicsWorld.WALK) physics.recover(physics.character, 1);
            else physics.recover(physics.bodyForMode(mode), mode + 1);
        }
        if (pendingCamera) {
            sceneCameraMultiplier = sceneCameraMultiplier > 1.05f ? 0.78f : 1.28f;
            scene.setCameraDistanceMultiplier(sceneCameraMultiplier);
        }
        if (pendingMap) mapVisible = !mapVisible;
        if (pendingDiagnostics) diagnostics = !diagnostics;
        if (pendingProfile) cycleProfile();
        if (pendingAux && mode == PhysicsWorld.HELICOPTER) helicopterEngineOn = !helicopterEngineOn;
        if (pendingAux && mode == PhysicsWorld.AIRPLANE) airplaneEngineOn = !airplaneEngineOn;
        pendingInteract = pendingAux = pendingRecover = pendingCamera = pendingMap = pendingDiagnostics = pendingProfile = false;
    }

    private float sceneCameraMultiplier = 1f;

    private void interact() {
        if (mode == PhysicsWorld.WALK) {
            int nearestMode = -1;
            float nearestDistance2 = 6.5f * 6.5f;
            physics.position(physics.character, characterPosition);
            for (int candidateMode = PhysicsWorld.CAR; candidateMode <= PhysicsWorld.AIRPLANE; candidateMode++) {
                PhysicsWorld.Body body = physics.bodyForMode(candidateMode);
                physics.position(body, candidatePosition);
                float dx = characterPosition.x - candidatePosition.x;
                float dy = characterPosition.y - candidatePosition.y;
                float dz = characterPosition.z - candidatePosition.z;
                float distance2 = dx * dx + dy * dy + dz * dz;
                if (distance2 < nearestDistance2) { nearestDistance2 = distance2; nearestMode = candidateMode; }
            }
            if (nearestMode < 0) return;
            PhysicsWorld.Body vehicle = physics.bodyForMode(nearestMode);
            physics.position(vehicle, candidatePosition);
            float vehicleYaw = physics.yaw(vehicle);
            rightVector.set((float) Math.cos(vehicleYaw), 0f, -(float) Math.sin(vehicleYaw));
            exitPosition.set(candidatePosition).mulAdd(rightVector, 3.25f);
            physics.setBodyTransform(physics.character, exitPosition, vehicleYaw);
            physics.setCharacterBoarded(true);
            mode = nearestMode;
            return;
        }
        PhysicsWorld.Body vehicle = physics.bodyForMode(mode);
        physics.position(vehicle, candidatePosition);
        float vehicleYaw = physics.yaw(vehicle);
        rightVector.set((float) Math.cos(vehicleYaw), 0f, -(float) Math.sin(vehicleYaw));
        physics.setCharacterBoarded(false);
        exitPosition.set(candidatePosition).mulAdd(rightVector, 3.25f);
        physics.setBodyTransform(physics.character, exitPosition, vehicleYaw);
        mode = PhysicsWorld.WALK;
    }

    private void pollGamepadEdges() {
        gamepadButtons = publishedGamepadButtons;
        int pressed = (int) gamepadButtons & ~previousGamepadButtons;
        previousGamepadButtons = (int) gamepadButtons;
        if ((pressed & PAD_A) != 0) pendingInteract = true;
        if ((pressed & PAD_B) != 0) pendingAux = true;
        if ((pressed & PAD_X) != 0) pendingRecover = true;
        if ((pressed & PAD_Y) != 0) pendingCamera = true;
        if ((pressed & PAD_START) != 0) pendingMap = true;
        if ((pressed & PAD_SELECT) != 0) pendingProfile = true;
    }

    private void cycleProfile() {
        PerformanceGovernor.Profile next;
        switch (governor.requested) {
            case BALANCED: next = PerformanceGovernor.Profile.LOW; break;
            case LOW: next = PerformanceGovernor.Profile.HIGH; break;
            case HIGH: next = PerformanceGovernor.Profile.ECO; break;
            default: next = PerformanceGovernor.Profile.BALANCED; break;
        }
        governor.setRequestedProfile(next);
    }

    private void applyQuality() {
        if (scene == null) return;
        if (qualityDistance == governor.worldViewDistance && qualityScale == governor.renderScale) return;
        qualityDistance = governor.worldViewDistance;
        qualityScale = governor.renderScale;
        scene.setQuality(qualityDistance, qualityScale);
    }

    public void setDevicePressure(boolean batterySaver, int thermalStatus) {
        pendingBatterySaver = batterySaver;
        pendingThermalStatus = Math.max(0, thermalStatus);
        pendingPressure = true;
    }

    public void setGamepadAxes(float leftX, float leftY, float rightX, float rightY, float gas, float brake) {
        gamepadLeftX = applyDeadZone(leftX);
        gamepadLeftY = applyDeadZone(leftY);
        gamepadRightX = applyDeadZone(rightX);
        gamepadRightY = applyDeadZone(rightY);
        gamepadGas = clamp(gas, 0f, 1f);
        gamepadBrake = clamp(brake, 0f, 1f);
    }

    public synchronized void setGamepadButton(int button, boolean down) {
        int old = publishedGamepadButtons;
        publishedGamepadButtons = down ? old | button : old & ~button;
    }

    @Override public void resize(int width, int height) {
        if (scene != null) scene.resize(width, height);
        if (hud != null) hud.resize(width, height);
        layout.resize(width, height);
    }

    @Override public void pause() {
        lifecyclePaused = true;
        fixedClock.reset();
        if (super.getScreen() != null) super.pause();
    }

    @Override public void resume() {
        lifecyclePaused = false;
        fixedClock.reset();
        if (super.getScreen() != null) super.resume();
    }

    @Override public void dispose() {
        if (scene != null) scene.dispose();
        if (physics != null) physics.dispose();
        if (hud != null) hud.dispose();
        super.dispose();
    }

    @Override public boolean keyDown(int keycode) {
        if (keycode >= 0 && keycode < keys.length) {
            if (!keys[keycode]) {
                if (keycode == Input.Keys.E || keycode == Input.Keys.ENTER) pendingInteract = true;
                if (keycode == Input.Keys.SPACE || keycode == Input.Keys.F) pendingAux = true;
                if (keycode == Input.Keys.R) pendingRecover = true;
                if (keycode == Input.Keys.C) pendingCamera = true;
                if (keycode == Input.Keys.M) pendingMap = true;
                if (keycode == Input.Keys.P) pendingProfile = true;
                if (keycode == Input.Keys.F1) pendingDiagnostics = true;
            }
            keys[keycode] = true;
        }
        if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
            manuallyPaused = !manuallyPaused;
            fixedClock.reset();
        }
        return true;
    }

    @Override public boolean keyUp(int keycode) {
        if (keycode >= 0 && keycode < keys.length) keys[keycode] = false;
        return true;
    }

    @Override public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (pointer < 0 || pointer >= pointers.length) return false;
        float x = screenX, y = Gdx.graphics.getHeight() - screenY;
        TouchPointer slot = pointers[pointer];
        slot.active = true; slot.x = x; slot.y = y; slot.lastX = x; slot.lastY = y;
        int buttonId = layout.buttonAt(x, y);
        if (buttonId >= 0) {
            slot.type = TouchLayout.BUTTON;
            slot.button = buttonId;
            switch (buttonId) {
                case TouchLayout.INTERACT: pendingInteract = true; break;
                case TouchLayout.AUX: pendingAux = true; break;
                case TouchLayout.RECOVER: pendingRecover = true; break;
                case TouchLayout.CAMERA: pendingCamera = true; break;
                case TouchLayout.MAP: pendingMap = true; break;
                case TouchLayout.PROFILE: pendingProfile = true; break;
                case TouchLayout.DIAGNOSTICS: pendingDiagnostics = true; break;
                default: break;
            }
        } else if (layout.stickArea(x, y)) {
            slot.type = TouchLayout.STICK;
        } else if (layout.lookArea(x, y)) {
            slot.type = TouchLayout.LOOK;
        } else {
            slot.active = false;
            return false;
        }
        return true;
    }

    @Override public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (pointer >= 0 && pointer < pointers.length) pointers[pointer].active = false;
        return true;
    }

    @Override public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (pointer < 0 || pointer >= pointers.length) return false;
        TouchPointer slot = pointers[pointer];
        if (!slot.active) return false;
        float x = screenX, y = Gdx.graphics.getHeight() - screenY;
        if (slot.type == TouchLayout.LOOK) {
            float dx = x - slot.lastX, dy = y - slot.lastY;
            cameraYaw -= dx * 0.0052f;
            cameraLift = clamp(cameraLift + dy * 0.012f, -2.0f, 7.0f);
            if (mode == PhysicsWorld.HELICOPTER || mode == PhysicsWorld.AIRPLANE)
                touchYawDelta = clamp(touchYawDelta - dx * 0.009f, -1f, 1f);
        }
        slot.lastX = x; slot.lastY = y; slot.x = x; slot.y = y;
        return true;
    }

    @Override public boolean mouseMoved(int screenX, int screenY) { return false; }
    @Override public boolean scrolled(float amountX, float amountY) {
        sceneCameraMultiplier = clamp(sceneCameraMultiplier + amountY * 0.1f, 0.62f, 1.65f);
        if (scene != null) scene.setCameraDistanceMultiplier(sceneCameraMultiplier);
        return true;
    }

    private boolean key(int code) { return code >= 0 && code < keys.length && keys[code]; }
    private static float combine(float a, float b) { return clamp(a + b, -1f, 1f); }
    private static float applyDeadZone(float value) {
        float magnitude = Math.abs(value);
        if (magnitude < 0.12f) return 0f;
        return Math.copySign((magnitude - 0.12f) / 0.88f, value);
    }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }

    @Override public boolean keyTyped(char character) { return false; }

    private static final class TouchPointer {
        boolean active;
        int type, button;
        float x, y, lastX, lastY;
    }
}
