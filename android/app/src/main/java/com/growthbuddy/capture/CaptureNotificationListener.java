package com.growthbuddy.capture;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Set;

/**
 * Reads the payment notifications of UPI and bank apps (and of nothing else) for
 * Money's automatic capture. The user turns it on twice: the switch in the app,
 * then Android's own "Notification access" page. Off in the app = nothing kept.
 *
 * Messaging apps are deliberately NOT in APPS: reading bank SMS through the
 * Messages app's notifications would sidestep Play's SMS permission review, and
 * Play treats that as policy circumvention. SMS goes through CaptureSmsReceiver.
 *
 * Package names to confirm on a phone (Play listings): they are the apps' long-
 * standing ids, but a bank can ship a new app.
 */
public class CaptureNotificationListener extends NotificationListenerService {

    static final Set<String> APPS = Set.of(
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app",
            "net.one97.paytm",
            "in.org.npci.upiapp", // BHIM
            "com.dreamplug.androidapp", // CRED
            "com.snapwork.hdfc", // HDFC Bank
            "com.csam.icici.bank.imobile", // ICICI iMobile
            "com.sbi.lotusintouch", // SBI YONO
            "com.axis.mobile", // Axis
            "com.msf.kbank.mobile" // Kotak
    );

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !APPS.contains(sbn.getPackageName())) return;
        if (!CaptureStore.enabled(this, "notif")) return;
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle x = n.extras;
        if (x == null) return;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence big = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence body = big != null ? big : x.getCharSequence(Notification.EXTRA_TEXT);
        String text = ((title == null ? "" : title) + "\n" + (body == null ? "" : body)).trim();
        if (!CaptureStore.looksLikePayment(text)) return;
        // The app's own event time: a repost or an update of one notification keeps it,
        // a second payment gets its own, even when the two read exactly alike.
        long at = n.when > 0 ? n.when : sbn.getPostTime();
        CaptureStore.add(this, "notif", sbn.getPackageName(), text, at);
    }
}
