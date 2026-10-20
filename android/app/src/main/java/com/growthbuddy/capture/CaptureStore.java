package com.growthbuddy.capture;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * Money's automatic capture, on the phone: which sources the user switched on,
 * and the payment-looking texts read while the app was closed, held until the
 * web side next opens and reads them (scripts/capture.js parses them there and
 * sends only the fields it found).
 *
 * Privacy rules this class keeps:
 * - Only text that looks like a payment (an amount AND a debit/credit word) from
 *   an allow-listed app or a bank sender ID is ever kept; everything else is
 *   dropped the moment it arrives.
 * - It lives in getNoBackupFilesDir(), so Android's cloud backup never copies it.
 * - At most MAX items, none older than MAX_AGE_MS. The web side peek()s, sends
 *   the fields, and only then ack()s what the server took: an app opened offline
 *   or onto a sleeping server keeps the batch for the next open, not loses it.
 * - Switching a source off drops what it queued.
 */
final class CaptureStore {

    static final int MAX = 200;
    static final long MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000;
    /** The same text from the same app this close together is a repost, not a second payment. */
    static final long REPOST_MS = 10L * 60 * 1000;

    private static final Pattern AMOUNT = Pattern.compile("(?i)(?:₹|rs\\.?|inr)\\s*[0-9]");
    private static final Pattern MONEY_WORD = Pattern.compile(
            "(?i)\\b(?:debited|credited|debit|credit|paid|sent|received|spent|withdrawn|deducted|refund(?:ed)?|reversed)\\b");
    /** India's DLT commercial sender format: AX-HDFCBK, VM-ICICIT-S. */
    private static final Pattern BANK_SENDER = Pattern.compile("(?i)^[A-Z]{2}-[A-Z0-9]{3,9}(?:-[A-Z])?$");

    private CaptureStore() {
    }

    static boolean looksLikePayment(String text) {
        return text != null && text.length() <= 2000 && AMOUNT.matcher(text).find() && MONEY_WORD.matcher(text).find();
    }

    static boolean bankSender(String address) {
        return address != null && BANK_SENDER.matcher(address.trim()).matches();
    }

    private static File file(Context ctx) {
        return new File(ctx.getNoBackupFilesDir(), "gb_capture.json");
    }

    private static JSONObject read(Context ctx) {
        File f = file(ctx);
        try {
            if (f.exists()) {
                return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // A corrupt file loses the queue, never the app.
        }
        return new JSONObject();
    }

    private static void write(Context ctx, JSONObject o) {
        try (FileOutputStream out = new FileOutputStream(file(ctx))) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Nothing to tell anyone from a listener; the next payment tries again.
        }
    }

    static synchronized boolean enabled(Context ctx, String source) {
        return read(ctx).optBoolean(source, false);
    }

    static synchronized void setEnabled(Context ctx, String source, boolean on) {
        JSONObject o = read(ctx);
        try {
            o.put(source, on);
            if (!on) {
                JSONArray kept = new JSONArray();
                JSONArray q = o.optJSONArray("queue");
                for (int i = 0; q != null && i < q.length(); i++) {
                    JSONObject it = q.optJSONObject(i);
                    if (it != null && !source.equals(it.optString("via"))) kept.put(it);
                }
                o.put("queue", kept);
            }
        } catch (JSONException ignored) {
            return;
        }
        write(ctx, o);
    }

    /** Queue one text; false when it was a repost, too old or not kept. */
    static synchronized boolean add(Context ctx, String via, String origin, String text, long at) {
        long now = System.currentTimeMillis();
        JSONObject o = read(ctx);
        JSONArray q = o.optJSONArray("queue");
        JSONArray next = new JSONArray();
        try {
            for (int i = 0; q != null && i < q.length(); i++) {
                JSONObject it = q.optJSONObject(i);
                if (it == null || now - it.optLong("at") > MAX_AGE_MS) continue;
                if (origin.equals(it.optString("origin")) && text.equals(it.optString("text"))
                        && Math.abs(at - it.optLong("at")) < REPOST_MS) {
                    return false;
                }
                next.put(it);
            }
            while (next.length() >= MAX) next.remove(0);
            next.put(new JSONObject().put("id", java.util.UUID.randomUUID().toString())
                    .put("via", via).put("origin", origin).put("text", text).put("at", at));
            o.put("queue", next);
        } catch (JSONException e) {
            return false;
        }
        write(ctx, o);
        return true;
    }

    /** Everything queued, oldest first. Nothing is removed until ack(). */
    static synchronized JSONArray peek(Context ctx) {
        JSONArray q = read(ctx).optJSONArray("queue");
        JSONArray out = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; q != null && i < q.length(); i++) {
            JSONObject it = q.optJSONObject(i);
            if (it != null && now - it.optLong("at") <= MAX_AGE_MS) out.put(it);
        }
        return out;
    }

    /** Forget the items with these ids: the server has them, or they were no payment. */
    static synchronized void ack(Context ctx, java.util.Set<String> ids) {
        JSONObject o = read(ctx);
        JSONArray q = o.optJSONArray("queue");
        JSONArray kept = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; q != null && i < q.length(); i++) {
            JSONObject it = q.optJSONObject(i);
            if (it != null && !ids.contains(it.optString("id")) && now - it.optLong("at") <= MAX_AGE_MS) kept.put(it);
        }
        try {
            o.put("queue", kept);
        } catch (JSONException e) {
            return;
        }
        write(ctx, o);
    }
}
