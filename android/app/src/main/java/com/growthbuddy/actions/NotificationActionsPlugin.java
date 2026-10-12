package com.growthbuddy.actions;

import android.content.Context;
import android.content.SharedPreferences;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * What NotificationActionReceiver needs to act without the WebView: the session
 * token and the API base, mirrored here by the web app (native.js
 * setNativeAuth) on every sign-in and boot, and wiped on sign-out. Plus a
 * "something changed" flag the app reads on resume to refetch.
 */
@CapacitorPlugin(name = "NotificationActions")
public class NotificationActionsPlugin extends Plugin {
    static final String PREFS = "gb_notification_actions";
    static final String TOKEN = "token";
    static final String BASE = "base";
    static final String CHANGED = "changed";

    /** The running bridge's instance, so a press with the app open repaints it now. */
    private static volatile NotificationActionsPlugin live;

    @Override
    public void load() {
        live = this;
    }

    static void announceChange() {
        NotificationActionsPlugin p = live;
        if (p != null) p.notifyListeners("changed", new JSObject());
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @PluginMethod
    public void setAuth(PluginCall call) {
        String token = call.getString("token");
        String base = call.getString("base");
        if (token == null || token.isEmpty() || base == null || base.isEmpty()) {
            call.reject("token and base are required");
            return;
        }
        prefs(getContext()).edit().putString(TOKEN, token).putString(BASE, base).apply();
        call.resolve();
    }

    @PluginMethod
    public void clearAuth(PluginCall call) {
        prefs(getContext()).edit().clear().apply();
        call.resolve();
    }

    /** True once after a button press changed something while the app was away. */
    @PluginMethod
    public void takeChanged(PluginCall call) {
        SharedPreferences p = prefs(getContext());
        boolean changed = p.getBoolean(CHANGED, false);
        if (changed) p.edit().remove(CHANGED).apply();
        JSObject out = new JSObject();
        out.put("changed", changed);
        call.resolve(out);
    }
}
