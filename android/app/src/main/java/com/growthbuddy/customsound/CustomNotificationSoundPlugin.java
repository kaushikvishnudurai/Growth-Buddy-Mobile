package com.growthbuddy.customsound;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Capacitor plugin for custom notification sounds.
 * 
 * Handles downloading custom sounds from server and caching locally
 * so LocalNotifications can reference them by file path.
 * 
 * Android limitation: LocalNotifications resolves sound from app assets
 * or app cache directory. Cannot load from arbitrary URLs at notification time.
 * This plugin bridges that gap by pre-downloading and caching.
 */
@CapacitorPlugin(name = "CustomNotificationSound")
public class CustomNotificationSoundPlugin extends Plugin {
    private static final String TAG = "CustomSound";
    private static final String CACHE_DIR = "custom_sounds";
    private static final String DEFAULT_FILENAME = "custom_notification.wav";
    private static final long DOWNLOAD_TIMEOUT_MS = 30_000;
    
    private AtomicBoolean isDownloading = new AtomicBoolean(false);
    private MediaPlayer mediaPlayer;
    
    /**
     * Download sound from signed URL and cache locally.
     * Returns the file path that can be used with LocalNotifications.
     */
    @PluginMethod
    public void downloadAndCache(PluginCall call) {
        String stratusKey = call.getString("stratusKey");
        String signedUrl = call.getString("signedUrl");
        String fileName = call.getString("fileName", DEFAULT_FILENAME);
        
        if (stratusKey == null || signedUrl == null) {
            call.reject("Missing stratusKey or signedUrl");
            return;
        }
        
        // Prevent concurrent downloads
        if (!isDownloading.compareAndSet(false, true)) {
            call.reject("Download already in progress");
            return;
        }
        
        // Download in background thread
        new Thread(() -> {
            try {
                File cacheDir = getCacheDirectory();
                File soundFile = new File(cacheDir, sanitizeFileName(fileName));
                
                downloadFile(signedUrl, soundFile);
                
                // Return the file path that LocalNotifications can use
                String filePath = soundFile.getAbsolutePath();
                JSObject result = new JSObject();
                result.put("filePath", filePath);
                
                Log.d(TAG, "Sound cached: " + filePath + " (" + soundFile.length() + " bytes)");
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                call.reject("Failed to download sound: " + e.getMessage());
            } finally {
                isDownloading.set(false);
            }
        }).start();
    }
    
    /**
     * Get path of cached custom sound, or null if not cached.
     */
    @PluginMethod
    public void getCachedPath(PluginCall call) {
        String fileName = call.getString("fileName", DEFAULT_FILENAME);
        File cacheDir = getCacheDirectory();
        File soundFile = new File(cacheDir, sanitizeFileName(fileName));
        
        JSObject result = new JSObject();
        if (soundFile.exists()) {
            result.put("filePath", soundFile.getAbsolutePath());
        } else {
            result.put("filePath", (String) null);
        }
        
        call.resolve(result);
    }
    
    /**
     * Delete cached sound file.
     */
    @PluginMethod
    public void deleteCache(PluginCall call) {
        String fileName = call.getString("fileName", DEFAULT_FILENAME);
        File cacheDir = getCacheDirectory();
        File soundFile = new File(cacheDir, sanitizeFileName(fileName));
        
        if (soundFile.exists() && soundFile.delete()) {
            Log.d(TAG, "Deleted cached sound: " + soundFile.getAbsolutePath());
            call.resolve();
        } else if (!soundFile.exists()) {
            call.resolve();  // Already gone
        } else {
            call.reject("Failed to delete cache file");
        }
    }
    
    /**
     * Check if download is in progress.
     */
    @PluginMethod
    public void isDownloading(PluginCall call) {
        JSObject result = new JSObject();
        result.put("downloading", isDownloading.get());
        call.resolve(result);
    }
    
    /**
     * Test play the sound via MediaPlayer (for verification).
     */
    @PluginMethod
    public void testPlay(PluginCall call) {
        String filePath = call.getString("filePath");
        if (filePath == null) {
            call.reject("Missing filePath");
            return;
        }
        
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(filePath);
            mediaPlayer.prepare();
            mediaPlayer.start();
            
            Log.d(TAG, "Playing test sound: " + filePath);
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Playback failed", e);
            call.reject("Failed to play sound: " + e.getMessage());
        }
    }
    
    // ---- Private helpers ----
    
    private File getCacheDirectory() {
        Context context = getContext();
        File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return cacheDir;
    }
    
    /**
     * Download file from signed URL with timeout.
     * Follows HTTP redirects and respects content-length.
     */
    private void downloadFile(String urlString, File destFile) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        try {
            conn.setConnectTimeout((int) DOWNLOAD_TIMEOUT_MS);
            conn.setReadTimeout((int) DOWNLOAD_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new Exception("HTTP " + status + ": " + conn.getResponseMessage());
            }
            
            long contentLength = conn.getContentLengthLong();
            if (contentLength > 10 * 1024 * 1024) {  // 10 MB limit
                throw new Exception("File too large: " + contentLength + " bytes");
            }
            
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(destFile)) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        } finally {
            conn.disconnect();
        }
    }
    
    /**
     * Sanitize filename to prevent path traversal attacks.
     */
    private String sanitizeFileName(String fileName) {
        // Allow only alphanumeric, dots, and underscores
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
