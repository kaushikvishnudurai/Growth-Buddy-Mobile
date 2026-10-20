package com.growthbuddy.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/**
 * A bank's payment SMS as it arrives, for Money's automatic capture. Only a DLT
 * bank sender ID (AX-HDFCBK) with a payment-looking text is kept, until the app
 * next opens; every other SMS is never looked at past its sender.
 *
 * Declared in the manifest only in the SMS release (see MOBILE.md, "SMS capture
 * release"): Play reviews READ_SMS / RECEIVE_SMS ("SMS-based money management")
 * and holds the whole release while it does, so it ships on its own.
 */
public class CaptureSmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        if (!CaptureStore.enabled(ctx, "sms")) return;
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) return;
        String sender = parts[0].getOriginatingAddress();
        if (!CaptureStore.bankSender(sender)) return;
        StringBuilder body = new StringBuilder();
        for (SmsMessage p : parts) {
            if (p != null && p.getMessageBody() != null) body.append(p.getMessageBody());
        }
        String text = body.toString();
        if (!CaptureStore.looksLikePayment(text)) return;
        CaptureStore.add(ctx, "sms", "sms", text, parts[0].getTimestampMillis());
    }
}
