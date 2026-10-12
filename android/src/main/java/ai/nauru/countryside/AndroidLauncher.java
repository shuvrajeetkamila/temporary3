package ai.nauru.countryside;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import ai.nauru.countryside.game.CountrysideGame;

/** Android launcher and low-frequency thermal/battery sampler. */
public final class AndroidLauncher extends AndroidApplication {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private CountrysideGame game;
    private PowerManager powerManager;

    private final Runnable samplePowerState = new Runnable() {
        @Override public void run() {
            if (game == null || powerManager == null) return;
            int thermal = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                thermal = powerManager.getCurrentThermalStatus();
            }
            game.setDevicePressure(powerManager.isPowerSaveMode(), thermal);
            handler.postDelayed(this, 10_000L);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        game = new CountrysideGame();
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useGL30 = true;
        config.useImmersiveMode = true;
        config.useAccelerometer = false;
        config.useCompass = false;
        config.numSamples = 0;
        initialize(game, config);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(samplePowerState);
        handler.post(samplePowerState);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(samplePowerState);
        if (game != null) game.setGamepadAxes(0f, 0f, 0f, 0f, 0f, 0f);
        super.onPause();
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int source = event.getSource();
        boolean joystick = (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (joystick && event.getAction() == MotionEvent.ACTION_MOVE && game != null) {
            float gas = Math.max(event.getAxisValue(MotionEvent.AXIS_GAS),
                    event.getAxisValue(MotionEvent.AXIS_RTRIGGER));
            float brake = Math.max(event.getAxisValue(MotionEvent.AXIS_BRAKE),
                    event.getAxisValue(MotionEvent.AXIS_LTRIGGER));
            game.setGamepadAxes(event.getAxisValue(MotionEvent.AXIS_X),
                    event.getAxisValue(MotionEvent.AXIS_Y), event.getAxisValue(MotionEvent.AXIS_Z),
                    event.getAxisValue(MotionEvent.AXIS_RZ), gas, brake);
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        int mapped = gamepadButton(keyCode);
        if (mapped != 0 && game != null) {
            game.setGamepadButton(mapped, true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        int mapped = gamepadButton(keyCode);
        if (mapped != 0 && game != null) {
            game.setGamepadButton(mapped, false);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private static int gamepadButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return CountrysideGame.PAD_A;
            case KeyEvent.KEYCODE_BUTTON_B: return CountrysideGame.PAD_B;
            case KeyEvent.KEYCODE_BUTTON_X: return CountrysideGame.PAD_X;
            case KeyEvent.KEYCODE_BUTTON_Y: return CountrysideGame.PAD_Y;
            case KeyEvent.KEYCODE_BUTTON_L1: return CountrysideGame.PAD_L1;
            case KeyEvent.KEYCODE_BUTTON_R1: return CountrysideGame.PAD_R1;
            case KeyEvent.KEYCODE_BUTTON_START: return CountrysideGame.PAD_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return CountrysideGame.PAD_SELECT;
            default: return 0;
        }
    }
}
