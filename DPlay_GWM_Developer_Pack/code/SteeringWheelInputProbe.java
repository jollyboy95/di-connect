package example.gwm;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;

/** NEW diagnostic sample, NOT verified GWM key mapping and NOT extracted from SmartSplit or ClusterProbe.
 * Only receives key events routed to this foreground Activity. Test parked.
 */
public final class SteeringWheelInputProbe extends Activity {
    private static final String TAG = "GwmSteeringKeyProbe";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(TAG, "Foreground key diagnostic active");
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        Log.i(TAG, "keyCode=" + event.getKeyCode()
                + " keyName=" + KeyEvent.keyCodeToString(event.getKeyCode())
                + " action=" + event.getAction()
                + " scanCode=" + event.getScanCode()
                + " source=0x" + Integer.toHexString(event.getSource())
                + " deviceId=" + event.getDeviceId());
        return super.dispatchKeyEvent(event);
    }
}
