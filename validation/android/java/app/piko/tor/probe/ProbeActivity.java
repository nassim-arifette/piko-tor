package app.piko.tor.probe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("Independent test UID. This application is not X and proves no X coverage.");
        setContentView(label);
    }
}
