package dev.istvanjuhos.mediaprojectiondemo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.istvanjuhos.mediaprojectiondemo.ui.theme.MediaProjectionDemoTheme

class MainActivity : ComponentActivity() {

    private var isProjecting by mutableStateOf(false)
    private var targetSurface: Surface? = null
    // Track whether a projection session has ever started in this process
    private var hasEverProjected: Boolean = false
    // Ensure we paint the initial white only once before the first session
    private var didPaintInitialWhite: Boolean = false

    private val mediaProjectionManager: MediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val requestNotifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchProjectionConsent()
        }
    }

    private val projectionConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startProjectionService(result.resultCode, result.data!!)
            isProjecting = true
            hasEverProjected = true
            // Send current surface if created after service starts
            targetSurface?.let { sendUpdateSurface(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MediaProjectionDemoTheme {
                ProjectionScreen(
                    isProjecting = isProjecting,
                    onStart = { startProjection() },
                    onStop = { stopProjection() },
                    onSurfaceReady = { surface ->
                        targetSurface = surface
                        if (!hasEverProjected && !didPaintInitialWhite) {
                            paintSurfaceWhite(surface)
                            didPaintInitialWhite = true
                        }
                        if (isProjecting) {
                            sendUpdateSurface(surface)
                        }
                    },
                    onSurfaceDestroyed = {
                        targetSurface = null
                    }
                )
            }
        }
    }

    private fun paintSurfaceWhite(surface: Surface) {
        try {
            if (!surface.isValid) return
            val canvas = surface.lockCanvas(null)
            try {
                canvas.drawColor(Color.WHITE)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (_: Throwable) {
            // Ignore drawing failures; surface may be in transition
        }
    }

    private fun startProjection() {
        if (Build.VERSION.SDK_INT >= 33) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            val granted = ContextCompat.checkSelfPermission(this, permission) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotifPermissionLauncher.launch(permission)
                return
            }
        }
        launchProjectionConsent()
    }

    private fun launchProjectionConsent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionConsentLauncher.launch(
                mediaProjectionManager.createScreenCaptureIntent(
//                    MediaProjectionConfig.createConfigForUserChoice()
                )
            )
        } else {
            projectionConsentLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    private fun startProjectionService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ProjectionService::class.java).apply {
            action = ProjectionService.ACTION_START
            putExtra(ProjectionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ProjectionService.EXTRA_DATA_INTENT, data)
            putExtra(ProjectionService.EXTRA_SURFACE, targetSurface)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun sendUpdateSurface(surface: Surface) {
        val intent = Intent(this, ProjectionService::class.java).apply {
            action = ProjectionService.ACTION_UPDATE_SURFACE
            putExtra(ProjectionService.EXTRA_SURFACE, surface)
        }
        startService(intent)
    }

    private fun stopProjection() {
        val intent = Intent(this, ProjectionService::class.java).apply {
            action = ProjectionService.ACTION_STOP
        }
        startService(intent)
        isProjecting = false
    }
}

@Composable
private fun ProjectionScreen(
    isProjecting: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        Button(
            onClick = { if (isProjecting) onStop() else onStart() }) {
            Text(if (isProjecting) "Stop Projection" else "Start Projection")
        }

        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onSurfaceReady(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {
                                onSurfaceReady(holder.surface)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onSurfaceDestroyed()
                            }
                        },
                    )
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
    }
}

@Preview
@Composable
private fun ProjectionScreenPreview() {
    MediaProjectionDemoTheme {
        ProjectionScreen(
            isProjecting = false,
            onStart = {},
            onStop = {},
            onSurfaceReady = {},
            onSurfaceDestroyed = {}
        )
    }
}