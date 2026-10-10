/**
 * Growth Buddy — Custom Notification Sound Plugin
 * 
 * Handles downloading, caching, and playing custom user-uploaded notification sounds.
 * Bridges the browser side with native Android/iOS implementations.
 * 
 * On Android: Writes sound file to app-specific cache directory,
 * then LocalNotifications can reference it by path.
 */

export interface CustomSoundPlugin {
  /**
   * Download custom sound from server and cache locally.
   * 
   * @param options.stratusKey - Object storage key returned from server upload
   * @param options.signedUrl - Signed download URL (expires in 7 days)
   * @param options.fileName - Local filename to save as (e.g., "custom.wav")
   * @returns Promise with local file path that can be used in notifications
   */
  downloadAndCache(options: {
    stratusKey: string;
    signedUrl: string;
    fileName?: string;
  }): Promise<{ filePath: string }>;

  /**
   * Get cached custom sound file path, or empty if not cached.
   * Useful to check if sound is available before scheduling notifications.
   */
  getCachedPath(options: { fileName?: string }): Promise<{ filePath: string | null }>;

  /**
   * Delete cached custom sound file.
   */
  deleteCache(options: { fileName?: string }): Promise<void>;

  /**
   * Check if custom sound download is in progress.
   * Prevents duplicate downloads.
   */
  isDownloading(): Promise<{ downloading: boolean }>;

  /**
   * Test play the custom sound to verify it works.
   * (Desktop browsers: no-op; mobile: plays via MediaPlayer)
   */
  testPlay(options: { filePath: string }): Promise<void>;
}

export type CustomSoundPluginType = CustomSoundPlugin;
