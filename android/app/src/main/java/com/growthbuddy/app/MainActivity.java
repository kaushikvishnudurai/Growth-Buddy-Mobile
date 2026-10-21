package com.growthbuddy.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;
import com.growthbuddy.actions.NotificationActionsPlugin;
import com.growthbuddy.capture.CapturePlugin;
import com.growthbuddy.customsound.CustomNotificationSoundPlugin;
import com.growthbuddy.permissions.AppPermissionsPlugin;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // capacitor.plugins.json is generated from npm packages only, so a plugin
        // class living in this app module is invisible to the bridge however it is
        // annotated — @CapacitorPlugin does not register anything by itself. It has
        // to be named here, and BEFORE super.onCreate(), which is where the bridge
        // is built and its plugin list is frozen.
        registerPlugin(CustomNotificationSoundPlugin.class);
        registerPlugin(NotificationActionsPlugin.class);
        registerPlugin(AppPermissionsPlugin.class);
        registerPlugin(CapturePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
