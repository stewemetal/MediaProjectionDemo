package dev.istvanjuhos.mediaprojectiondemo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Size
import android.view.Surface
import androidx.core.app.NotificationCompat

/**
 * Foreground service that owns the MediaProjection session and VirtualDisplay.
 * This is required on modern Android versions (API 34+) when performing screen capture.
 */
class ProjectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var targetSurface: Surface? = null
    private var callbackRegistered: Boolean = false

    private val mediaProjectionManager: MediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // MediaProjection was stopped by the system or user.
            // Release the virtual display and stop the service.
            virtualDisplay?.release()
            virtualDisplay = null
            // Unregister to avoid leaks; guard by flag and instance
            mediaProjection?.let { mp ->
                if (callbackRegistered) {
                    try { mp.unregisterCallback(this) } catch (_: Throwable) {}
                }
            }
            callbackRegistered = false
            mediaProjection = null
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_UPDATE_SURFACE -> handleUpdateSurface(intent)
            ACTION_STOP -> stopProjectionAndSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        ensureForeground()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA_INTENT)
        val surface: Surface? = intent.getParcelableExtra(EXTRA_SURFACE)
        if (resultCode == 0 || data == null) {
            // Missing required data; stop
            stopProjectionAndSelf()
            return
        }
        targetSurface = surface

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(projectionCallback, null)
        callbackRegistered = true
        // Create the VirtualDisplay only if we already have a surface; otherwise wait for UPDATE_SURFACE
        if (targetSurface != null) {
            createOrUpdateVirtualDisplay()
        }
    }

    private fun handleUpdateSurface(intent: Intent) {
        val surface: Surface? = intent.getParcelableExtra(EXTRA_SURFACE)
        if (surface != null) {
            targetSurface = surface
            createOrUpdateVirtualDisplay()
        }
    }

    private fun getTargetSize(): Size {
        return if (Build.VERSION.SDK_INT >= 30) {
            // Use the current display metrics of the service's context
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val bounds = wm.currentWindowMetrics.bounds
            Size(bounds.width(), bounds.height())
        } else {
            val dm = resources.displayMetrics
            Size(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun createOrUpdateVirtualDisplay() {
        val mp = mediaProjection ?: return
        val surface = targetSurface ?: return
        val size = getTargetSize()
        val densityDpi = resources.displayMetrics.densityDpi

        if (virtualDisplay == null) {
            // Create once per MediaProjection instance
            virtualDisplay = mp.createVirtualDisplay(
                "MediaProjectionDemo",
                size.width,
                size.height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )
        } else {
            // Only update the surface; do not recreate the VirtualDisplay
            try {
                virtualDisplay?.setSurface(surface)
            } catch (_: Throwable) {
                // As a fallback, avoid creating a new VirtualDisplay on the same MediaProjection to prevent SecurityException.
                // If surface update fails, we'll keep the existing one.
            }
        }
    }

    private fun stopProjectionAndSelf() {
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.let { mp ->
            if (callbackRegistered) {
                try { mp.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
            }
            callbackRegistered = false
            // Trigger onStop callback; acceptable to call stop here when not in callback
            try { mp.stop() } catch (_: Throwable) {}
        }
        mediaProjection = null
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
    }

    private fun ensureForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Screen capture",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Screen capture running")
            .setContentText("Tap Stop in the app to end capture")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_START = "dev.istvanjuhos.mediaprojectiondemo.action.START"
        const val ACTION_UPDATE_SURFACE = "dev.istvanjuhos.mediaprojectiondemo.action.UPDATE_SURFACE"
        const val ACTION_STOP = "dev.istvanjuhos.mediaprojectiondemo.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"
        const val EXTRA_SURFACE = "extra_surface"

        private const val NOTIFICATION_CHANNEL_ID = "projection"
        private const val NOTIFICATION_ID = 1001
    }
}
