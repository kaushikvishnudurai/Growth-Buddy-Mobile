package com.growthbuddy.customsound;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.util.Base64;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * A notification channel that rings with the user's OWN sound (an upload or a
 * recording). The web app keeps those as data URLs; Android takes a channel's
 * sound from a URI it can open, and LocalNotifications only builds res/raw ones.
 * So the bytes are written to filesDir/gb_sounds/, served through the app's
 * FileProvider (res/xml/file_paths.xml), and read access is granted to System
 * UI, the process that actually plays a notification's sound.
 *
 * A channel is frozen once created, so its id carries the sound's id
 * (gb-tone-custom-<id8>, push.js channelForTone): a new upload is a new
 * channel, never an edit of an old one. Called from native.js ensureChannel.
 */
@CapacitorPlugin(name = "CustomNotificationSound")
public class CustomNotificationSoundPlugin extends Plugin {
    private static final String DIR = "gb_sounds";
    private static final String PREFIX = "gb-tone-custom-";
    // The web side caps an upload at 300 kB; this is only the backstop.
    private static final int MAX_BYTES = 1024 * 1024;

    @PluginMethod
    public void ensureChannel(PluginCall call) {
        String id = call.getString("id");
        String name = call.getString("name", "Reminders");
        String dataUrl = call.getString("dataUrl");
        // The id becomes a file name: nothing but our own prefix and [a-z0-9-].
        if (id == null || !id.matches(PREFIX + "[a-z0-9-]+") || dataUrl == null) {
            call.reject("id (" + PREFIX + "...) and dataUrl are required");
            return;
        }
        Context ctx = getContext();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(id) != null) {
            call.resolve();
            return;
        }
        int comma = dataUrl.indexOf(',');
        int semi = dataUrl.indexOf(';');
        if (!dataUrl.startsWith("data:audio/") || comma < 0 || semi < 0 || semi > comma
                || !dataUrl.substring(0, comma).endsWith(";base64")) {
            call.reject("dataUrl must be a base64 audio data URL");
            return;
        }
        try {
            byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                call.reject("sound is empty or too large");
                return;
            }
            File dir = new File(ctx.getFilesDir(), DIR);
            dir.mkdirs();
            File file = new File(dir, id + extension(dataUrl.substring(5, semi)));
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", file);
            ctx.grantUriPermission("com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // HIGH, as native.js builds the built-in tones: the one that banners.
            NotificationChannel channel = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(uri, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            channel.enableVibration(true);
            nm.createNotificationChannel(channel);
            call.resolve();
        } catch (Exception e) {
            call.reject("could not build the channel: " + e.getMessage());
        }
    }

    /** Drop our custom channels, and their files, whose id is not in `keep` (a deleted sound). */
    @PluginMethod
    public void prune(PluginCall call) {
        Set<String> keep = new HashSet<>();
        try {
            keep.addAll(call.getArray("keep", new JSArray()).<String>toList());
        } catch (Exception e) {
            call.reject("keep must be a list of channel ids");
            return;
        }
        Context ctx = getContext();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        for (NotificationChannel c : nm.getNotificationChannels()) {
            if (c.getId().startsWith(PREFIX) && !keep.contains(c.getId())) nm.deleteNotificationChannel(c.getId());
        }
        File[] files = new File(ctx.getFilesDir(), DIR).listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (!keep.contains(n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n)) f.delete();
            }
        }
        call.resolve();
    }

    // The extension is how the player picks a decoder for a content:// file.
    private static String extension(String mime) {
        switch (mime) {
            case "audio/mpeg":
            case "audio/mp3":
                return ".mp3";
            case "audio/wav":
            case "audio/x-wav":
            case "audio/wave":
                return ".wav";
            case "audio/ogg":
                return ".ogg";
            case "audio/webm":
                return ".webm";
            case "audio/aac":
                return ".aac";
            case "audio/mp4":
            case "audio/x-m4a":
            case "audio/m4a":
                return ".m4a";
            default:
                return ".audio";
        }
    }
}
