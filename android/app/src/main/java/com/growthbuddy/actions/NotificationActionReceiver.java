package com.growthbuddy.actions;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.Toast;

import com.capacitorjs.plugins.localnotifications.TimedNotificationPublisher;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Scanner;
import java.util.UUID;

/**
 * A notification button pressed with the app closed: Done, Snooze, +250 ml.
 * The patched LocalNotifications plugin (patches/) sends the press here instead
 * of launching the app. Each is the same call the app's
 * handleNotificationAction makes, sent with the token NotificationActionsPlugin
 * keeps. Success clears the card (Snooze re-posts it at snoozedUntil); a failure
 * leaves it up with a toast, so the press can be tried again or done in the app.
 */
public class NotificationActionReceiver extends BroadcastReceiver {
    private static final String TAG = "GbNotifAction";
    private static final int WATER_ML = 250; // push.js WATER_ACTION_ML

    @Override
    public void onReceive(Context context, Intent intent) {
        final int id = intent.getIntExtra("LocalNotificationId", Integer.MIN_VALUE);
        final String action = intent.getStringExtra("LocalNotificationUserAction");
        final String source = intent.getStringExtra("LocalNotficationObject"); // sic, the plugin's key
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                String failure = handle(app, id, action, source);
                if (failure != null) toast(app, failure);
            } catch (Exception e) {
                Log.w(TAG, "Notification action failed", e);
                toast(app, "Could not reach Growth Buddy. Open the app to try again.");
            } finally {
                pending.finish();
            }
        }).start();
    }

    /** null on success, else what to tell the user. */
    private String handle(Context context, int id, String action, String source) throws Exception {
        JSONObject extra = new JSONObject(source == null ? "{}" : source).optJSONObject("extra");
        if (extra == null || action == null) return null;
        SharedPreferences prefs = NotificationActionsPlugin.prefs(context);
        String token = prefs.getString(NotificationActionsPlugin.TOKEN, null);
        String base = prefs.getString(NotificationActionsPlugin.BASE, null);
        if (token == null || base == null) return "Open Growth Buddy once to use notification buttons.";

        String kind = extra.optString("kind");
        String target = extra.optString("id");
        String day = extra.optString("day");
        Notification shown = activeNotification(context, id); // before cancel: Snooze re-posts it
        int status;
        JSONObject answer = null;
        if ("water".equals(kind) && "water".equals(action)) {
            status = send(base, token, "POST", "/api/water/entries",
                    new JSONObject().put("amountMl", WATER_ML).toString(), UUID.randomUUID().toString(), null);
        } else if ("reminder".equals(kind) && "done".equals(action) && !target.isEmpty()) {
            if (day.isEmpty()) status = 204; // a snoozed ring: the press clears the card, as in the app
            else status = send(base, token, "POST", "/api/reminders/" + enc(target) + "/done?date=" + enc(day), null, null, null);
        } else if ("reminder".equals(kind) && "snooze".equals(action) && !target.isEmpty()) {
            JSONObject[] out = new JSONObject[1];
            status = send(base, token, "POST", "/api/reminders/" + enc(target) + "/snooze", "{}", null, out);
            answer = out[0];
        } else if ("habit".equals(kind) && "done".equals(action) && !target.isEmpty()) {
            JSONObject body = new JSONObject().put("done", true);
            body.put("date", day.isEmpty() ? LocalDate.now().toString() : day);
            status = send(base, token, "POST", "/api/habits/" + enc(target) + "/checkin", body.toString(), null, null);
        } else {
            return null;
        }
        if (status == 401) return "Signed out. Open Growth Buddy to sign in.";
        if (status < 200 || status >= 300) return "Could not do that. Open Growth Buddy to try again.";

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(id);
        prefs.edit().putBoolean(NotificationActionsPlugin.CHANGED, true).commit();
        NotificationActionsPlugin.announceChange();
        if (answer != null && shown != null) ringAgain(context, id, shown, answer.optString("snoozedUntil"));
        if ("snooze".equals(action)) toast(context, "Snoozed.");
        else if ("water".equals(action)) toast(context, "Logged " + WATER_ML + " ml. Your body thanks you for the water.");
        else toast(context, "Marked done.");
        return null;
    }

    private static Notification activeNotification(Context context, int id) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (StatusBarNotification sbn : nm.getActiveNotifications()) {
            if (sbn.getId() == id) return sbn.getNotification();
        }
        return null;
    }

    /* The snoozed ring, from this phone: the app's own reSyncDeviceAlarms does this
       when it runs, and it may not run before then. The plugin's publisher posts it.
       ponytail: a distinct request code, so the reminder's own next alarm is kept;
       opening the app before it rings queues the app's copy too, and the second
       notify() with the same id just replaces the first. */
    private static void ringAgain(Context context, int id, Notification notification, String isoAt) {
        if (isoAt == null || isoAt.isEmpty()) return;
        long at = Instant.parse(isoAt).toEpochMilli();
        Intent intent = new Intent(context, TimedNotificationPublisher.class)
                .putExtra("LocalNotificationId", id)
                .putExtra(TimedNotificationPublisher.NOTIFICATION_KEY, notification);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(context, id ^ 0x5A5A0000, intent, flags);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static int send(String base, String token, String method, String path, String body,
                            String idemKey, JSONObject[] answer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base.replaceAll("/+$", "") + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
            if (idemKey != null) c.setRequestProperty("Idempotency-Key", idemKey);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = c.getResponseCode();
            if (answer != null && status >= 200 && status < 300) {
                try (InputStream in = c.getInputStream(); Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
                    answer[0] = new JSONObject(s.hasNext() ? s.next() : "{}");
                }
            }
            return status;
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static void toast(Context context, String text) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
    }
}
