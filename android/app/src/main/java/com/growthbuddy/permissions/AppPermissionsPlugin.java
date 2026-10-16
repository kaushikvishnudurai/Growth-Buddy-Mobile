package com.growthbuddy.permissions;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * The camera question, asked again each time the user picks a camera option
 * (web side: camera.js withCamera). The WebView's own prompt (BridgeWebChromeClient)
 * gives up silently once Android stops showing the dialog, so a tap after
 * "Don't allow" did nothing. This one reports that state as "blocked", and
 * openSettings takes the user to the app's page, the only place it can change.
 */
@CapacitorPlugin(
        name = "AppPermissions",
        permissions = @Permission(strings = { Manifest.permission.CAMERA }, alias = "camera"))
public class AppPermissionsPlugin extends Plugin {

    /** Asks for the camera if Android still shows the dialog: {state: granted | prompt | blocked}. */
    @PluginMethod
    public void requestCamera(PluginCall call) {
        if (granted()) {
            resolve(call, "granted");
            return;
        }
        requestPermissionForAlias("camera", call, "cameraAnswered");
    }

    @PermissionCallback
    private void cameraAnswered(PluginCall call) {
        if (granted()) {
            resolve(call, "granted");
            return;
        }
        // After a refusal Android still re-asks while it says a rationale is due;
        // once it stops (the second "Don't allow", or "Don't ask again") only
        // the app's settings page can turn the camera back on.
        boolean askable = ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.CAMERA);
        resolve(call, askable ? "prompt" : "blocked");
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getContext().getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    private boolean granted() {
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void resolve(PluginCall call, String state) {
        JSObject out = new JSObject();
        out.put("state", state);
        call.resolve(out);
    }
}
