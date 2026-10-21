package com.growthbuddy.capture;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.provider.Telephony;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;

import java.util.Arrays;

/**
 * Money's automatic capture, the bridge (web side: scripts/native.js capturePlugin,
 * scripts/capture.js). Holds no text itself: CaptureStore queues it, peek() hands
 * it to the web side, which parses it, sends only the fields, then ack()s it.
 *
 * Also receives text another app shares into Growth Buddy (ACTION_SEND text/plain,
 * MainActivity's intent filter): a bank SMS shared this way is captured; any
 * other text goes to Quick add's preview.
 */
@CapacitorPlugin(
        name = "GbCapture",
        permissions = @Permission(
                strings = { Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS }, alias = "sms"))
public class CapturePlugin extends Plugin {

    private static final int INBOX_MAX = 3000;
    private String shared;

    @Override
    public void load() {
        takeShareFrom(getActivity().getIntent(), false);
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        takeShareFrom(intent, true);
    }

    private void takeShareFrom(Intent intent, boolean live) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        if (intent.getType() == null || !intent.getType().startsWith("text/")) return;
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text == null || text.length() == 0) return;
        shared = text.length() > 2000 ? text.subSequence(0, 2000).toString() : text.toString();
        // Handled once: a rotation or a re-created activity must not share it again.
        intent.setAction(null);
        if (live) notifyListeners("shared", new JSObject());
    }

    /** {text}: the text shared in, once; null when there is none. */
    @PluginMethod
    public void takeShared(PluginCall call) {
        JSObject out = new JSObject();
        out.put("text", shared);
        shared = null;
        call.resolve(out);
    }

    /** {notifAccess, smsAvailable, smsGranted, notif, sms}. */
    @PluginMethod
    public void status(PluginCall call) {
        JSObject out = new JSObject();
        out.put("notifAccess", notifAccess());
        out.put("smsAvailable", smsDeclared());
        out.put("smsGranted", smsGranted());
        out.put("notif", CaptureStore.enabled(getContext(), "notif"));
        out.put("sms", CaptureStore.enabled(getContext(), "sms"));
        call.resolve(out);
    }

    /** {notif?, sms?}: what the native side keeps queueing. Off drops what that source queued. */
    @PluginMethod
    public void setSources(PluginCall call) {
        if (call.hasOption("notif")) CaptureStore.setEnabled(getContext(), "notif", Boolean.TRUE.equals(call.getBoolean("notif")));
        if (call.hasOption("sms")) CaptureStore.setEnabled(getContext(), "sms", Boolean.TRUE.equals(call.getBoolean("sms")));
        status(call);
    }

    /** Android's own switch for this app's notification access (our row directly on 11+). */
    @PluginMethod
    public void openNotificationAccess(PluginCall call) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            intent.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(getContext(), CaptureNotificationListener.class).flattenToString());
        } else {
            intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (Exception e) {
            Intent all = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            all.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(all);
        }
        call.resolve();
    }

    /** {granted}. Always false in a build that doesn't declare SMS (the non-SMS release). */
    @PluginMethod
    public void requestSms(PluginCall call) {
        if (!smsDeclared() || smsGranted()) {
            JSObject out = new JSObject();
            out.put("granted", smsGranted());
            call.resolve(out);
            return;
        }
        requestPermissionForAlias("sms", call, "smsAnswered");
    }

    @PermissionCallback
    private void smsAnswered(PluginCall call) {
        JSObject out = new JSObject();
        out.put("granted", smsGranted());
        call.resolve(out);
    }

    /** {items: [{id, via, origin, text, at}]}: the queue, left as it is until ack. */
    @PluginMethod
    public void peek(PluginCall call) {
        JSArray items = new JSArray();
        JSONArray q = CaptureStore.peek(getContext());
        for (int i = 0; i < q.length(); i++) items.put(q.opt(i));
        JSObject out = new JSObject();
        out.put("items", items);
        call.resolve(out);
    }

    /** {ids}: forget these queued items (the server took them, or they were no payment). */
    @PluginMethod
    public void ack(PluginCall call) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        JSArray arr = call.getArray("ids");
        for (int i = 0; arr != null && i < arr.length(); i++) ids.add(arr.optString(i));
        CaptureStore.ack(getContext(), ids);
        call.resolve();
    }

    /**
     * {sinceMs} -> {items}: bank-sender SMS that look like payments, newest first,
     * read straight into the answer and kept nowhere. SMS capture on, permission given.
     */
    @PluginMethod
    public void readSmsInbox(PluginCall call) {
        JSArray items = new JSArray();
        if (!smsGranted() || !CaptureStore.enabled(getContext(), "sms")) {
            JSObject out = new JSObject();
            out.put("items", items);
            call.resolve(out);
            return;
        }
        long since = call.getLong("sinceMs", System.currentTimeMillis() - 90L * 86400000L);
        Uri uri = Telephony.Sms.Inbox.CONTENT_URI;
        String[] cols = { Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE };
        try (Cursor c = getContext().getContentResolver().query(uri, cols, Telephony.Sms.DATE + " > ?",
                new String[] { String.valueOf(since) }, Telephony.Sms.DATE + " DESC")) {
            while (c != null && c.moveToNext() && items.length() < INBOX_MAX) {
                String address = c.getString(0);
                String body = c.getString(1);
                if (!CaptureStore.bankSender(address) || !CaptureStore.looksLikePayment(body)) continue;
                JSObject it = new JSObject();
                it.put("via", "sms");
                it.put("origin", "sms");
                it.put("text", body);
                it.put("at", c.getLong(2));
                items.put(it);
            }
        } catch (Exception e) {
            call.reject("Could not read your messages.");
            return;
        }
        JSObject out = new JSObject();
        out.put("items", items);
        call.resolve(out);
    }

    private boolean notifAccess() {
        return NotificationManagerCompat.getEnabledListenerPackages(getContext()).contains(getContext().getPackageName());
    }

    private boolean smsGranted() {
        return smsDeclared()
                && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Whether this build's manifest asks for SMS at all (only the SMS release does). */
    private boolean smsDeclared() {
        try {
            PackageInfo info = getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), PackageManager.GET_PERMISSIONS);
            return info.requestedPermissions != null
                    && Arrays.asList(info.requestedPermissions).contains(Manifest.permission.READ_SMS);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
