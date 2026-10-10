import Foundation
import Capacitor

/**
 * Custom Notification Sound Plugin for iOS
 * 
 * Downloads custom sounds from server and caches in app's Documents directory.
 * LocalNotifications can reference files by URL.
 */
@objc(CustomNotificationSoundPlugin)
public class CustomNotificationSoundPlugin: CAPPlugin, URLSessionDelegate {
    
    private static let TAG = "CustomSound"
    private static let CACHE_DIR = "custom_sounds"
    private static let DEFAULT_FILENAME = "custom_notification.wav"
    private static let DOWNLOAD_TIMEOUT: TimeInterval = 30
    private static let MAX_FILE_SIZE = 10 * 1024 * 1024  // 10 MB
    
    private var isDownloading = false
    private var audioPlayer: AVAudioPlayer?
    
    // MARK: - Plugin Methods
    
    /**
     * Download sound from signed URL and cache locally.
     */
    @objc func downloadAndCache(_ call: CAPPluginCall) {
        guard let stratusKey = call.getString("stratusKey"),
              let signedUrl = call.getString("signedUrl") else {
            call.reject("Missing stratusKey or signedUrl")
            return
        }
        
        let fileName = call.getString("fileName") ?? Self.DEFAULT_FILENAME
        
        if isDownloading {
            call.reject("Download already in progress")
            return
        }
        
        isDownloading = true
        
        DispatchQueue.global(qos: .background).async {
            do {
                let cacheDir = try self.getCacheDirectory()
                let soundFile = cacheDir.appendingPathComponent(self.sanitizeFileName(fileName))
                
                try self.downloadFile(from: signedUrl, to: soundFile)
                
                let filePath = soundFile.path
                
                DispatchQueue.main.async {
                    var result = JSObject()
                    result["filePath"] = filePath
                    call.resolve(result)
                    self.isDownloading = false
                    
                    print("✅ CustomSound: Cached at \(filePath)")
                }
            } catch {
                DispatchQueue.main.async {
                    call.reject("Failed to download sound: \(error.localizedDescription)")
                    self.isDownloading = false
                }
                print("❌ CustomSound: Download failed: \(error)")
            }
        }
    }
    
    /**
     * Get path of cached custom sound, or null if not cached.
     */
    @objc func getCachedPath(_ call: CAPPluginCall) {
        let fileName = call.getString("fileName") ?? Self.DEFAULT_FILENAME
        
        do {
            let cacheDir = try getCacheDirectory()
            let soundFile = cacheDir.appendingPathComponent(sanitizeFileName(fileName))
            
            var result = JSObject()
            if FileManager.default.fileExists(atPath: soundFile.path) {
                result["filePath"] = soundFile.path
            } else {
                result["filePath"] = NSNull()
            }
            
            call.resolve(result)
        } catch {
            call.reject("Failed to get cached path: \(error.localizedDescription)")
        }
    }
    
    /**
     * Delete cached sound file.
     */
    @objc func deleteCache(_ call: CAPPluginCall) {
        let fileName = call.getString("fileName") ?? Self.DEFAULT_FILENAME
        
        do {
            let cacheDir = try getCacheDirectory()
            let soundFile = cacheDir.appendingPathComponent(sanitizeFileName(fileName))
            
            if FileManager.default.fileExists(atPath: soundFile.path) {
                try FileManager.default.removeItem(at: soundFile)
                print("✅ CustomSound: Deleted cache")
            }
            
            call.resolve()
        } catch {
            call.reject("Failed to delete cache: \(error.localizedDescription)")
        }
    }
    
    /**
     * Check if download is in progress.
     */
    @objc func isDownloading(_ call: CAPPluginCall) {
        var result = JSObject()
        result["downloading"] = isDownloading
        call.resolve(result)
    }
    
    /**
     * Test play the sound via AVAudioPlayer.
     */
    @objc func testPlay(_ call: CAPPluginCall) {
        guard let filePath = call.getString("filePath") else {
            call.reject("Missing filePath")
            return
        }
        
        do {
            let fileURL = URL(fileURLWithPath: filePath)
            audioPlayer = try AVAudioPlayer(contentsOf: fileURL)
            audioPlayer?.volume = 0.5
            audioPlayer?.play()
            
            print("✅ CustomSound: Testing playback")
            call.resolve()
        } catch {
            call.reject("Failed to play sound: \(error.localizedDescription)")
        }
    }
    
    // MARK: - Private Helpers
    
    private func getCacheDirectory() throws -> URL {
        let fileManager = FileManager.default
        let documentDir = try fileManager.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: false
        )
        let cacheDir = documentDir.appendingPathComponent(Self.CACHE_DIR)
        
        if !fileManager.fileExists(atPath: cacheDir.path) {
            try fileManager.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        }
        
        return cacheDir
    }
    
    private func downloadFile(from urlString: String, to destination: URL) throws {
        guard let url = URL(string: urlString) else {
            throw NSError(domain: "CustomSound", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid URL"])
        }
        
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: Self.DOWNLOAD_TIMEOUT)
        request.httpMethod = "GET"
        
        let session = URLSession(configuration: .default)
        let semaphore = DispatchSemaphore(value: 0)
        var error: Error? = nil
        
        let task = session.downloadTask(with: request) { tempURL, response, err in
            defer { semaphore.signal() }
            
            if let err = err {
                error = err
                return
            }
            
            guard let httpResponse = response as? HTTPURLResponse,
                  (200..<300).contains(httpResponse.statusCode) else {
                error = NSError(domain: "CustomSound", code: 2, userInfo: [NSLocalizedDescriptionKey: "HTTP error"])
                return
            }
            
            if let contentLength = httpResponse.expectedContentLength,
               contentLength > Self.MAX_FILE_SIZE {
                error = NSError(domain: "CustomSound", code: 3, userInfo: [NSLocalizedDescriptionKey: "File too large"])
                return
            }
            
            guard let tempURL = tempURL else {
                error = NSError(domain: "CustomSound", code: 4, userInfo: [NSLocalizedDescriptionKey: "No file received"])
                return
            }
            
            do {
                try FileManager.default.moveItem(at: tempURL, to: destination)
            } catch {
                error = error
            }
        }
        
        task.resume()
        semaphore.wait()
        
        if let error = error {
            throw error
        }
    }
    
    private func sanitizeFileName(_ fileName: String) -> String {
        // Allow only alphanumeric, dots, hyphens, underscores
        return fileName
            .replacingOccurrences(of: "[^a-zA-Z0-9._-]", with: "_", options: .regularExpression)
    }
}
