package example.gwm;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;

/** Adapted from ClusterProbe MainActivity.launchOnDisplay(). */
public final class ActivityLaunchExample {
    public static void launch(Activity host, Class<? extends Activity> target, int logicalDisplayId) {
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(logicalDisplayId);
        Intent intent = new Intent(host, target);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        host.startActivity(intent, opts.toBundle());
        // Catch SecurityException at caller; may be denied by Android display launch policy.
    }
}
