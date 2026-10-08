package net.gaiadesk.embed.internal

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * Capture.SCREEN only (the app opted in): the whole screen through MediaProjection. Android asks
 * the user itself ([ProjectionRequestActivity]) on top of the library's consent, and shows its own
 * casting indication; the projection runs in a foreground service ([ProjectionService]), as
 * Android 10+ requires. The app must declare FOREGROUND_SERVICE and
 * FOREGROUND_SERVICE_MEDIA_PROJECTION (README); without them it fails as capture_failed.
 */
internal class ScreenCapture(
    private val context: Context,
    private val sink: FrameSink,
    /** The app's window has focus: the projection sends nothing while it does not. */
    private val focused: () -> Boolean,
    /** The masks as last measured on the main thread (window pixels). */
    private val masks: () -> DoubleArray?,
) : Capture {
    override fun start(fps: Int, done: (Boolean) -> Unit) {
        Projection.pending = { resultCode, data ->
            if (data == null || resultCode != Activity.RESULT_OK) {
                done(false)
            } else {
                Projection.onReady = { projection ->
                    if (projection == null) done(false) else done(Projection.run(context, projection, fps, sink, focused, masks))
                }
                val svc = Intent(context, ProjectionService::class.java).putExtra("code", resultCode).putExtra("data", data)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
            }
        }
        context.startActivity(Intent(context, ProjectionRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun stop() = Projection.stop(context)
}

internal object Projection {
    @Volatile var pending: ((Int, Intent?) -> Unit)? = null
    @Volatile var onReady: ((MediaProjection?) -> Unit)? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    fun run(context: Context, p: MediaProjection, fps: Int, sink: FrameSink, focused: () -> Boolean, masks: () -> DoubleArray?): Boolean {
        val dm = context.getSystemService(DisplayManager::class.java)
        val screen = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") screen.getRealMetrics(it) }
        val (w, h) = EmbedJson.fit(m.widthPixels, m.heightPixels)
        val t = HandlerThread("gd-embed-projection").apply { start() }
        val handler = Handler(t.looper)
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var last = 0L
        val gap = 1000L / fps.coerceIn(1, 30)
        r.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - last < gap) return@setOnImageAvailableListener
                last = now
                // Nothing while the app is not in front (the screen is shared
                // for the app's sake, not for whatever is over it).
                if (!focused()) return@setOnImageAvailableListener
                // The screen as it is NOW (rotation, navigation bar): the
                // picture rect comes from the real metrics of this frame, and
                // an image of another shape (rotated since the virtual display
                // was made) is not sent at all.
                val now_ = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") screen.getRealMetrics(it) }
                if (!EmbedJson.sameShape(img.width, img.height, now_.widthPixels, now_.heightPixels)) return@setOnImageAvailableListener
                val picture = doubleArrayOf(0.0, 0.0, now_.widthPixels.toDouble(), now_.heightPixels.toDouble())
                // A direct buffer of rowStride-wide rows: the library packs them.
                val plane = img.planes[0]
                sink.push(plane.buffer, w, h, plane.rowStride, picture, masks())
            } finally {
                img.close()
            }
        }, handler)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stop(context)
        }, handler)
        display = p.createVirtualDisplay("gaiadesk-embed", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
        projection = p
        reader = r
        thread = t
        return display != null
    }

    fun stop(context: Context) {
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        thread?.quitSafely()
        thread = null
        context.stopService(Intent(context, ProjectionService::class.java))
    }
}

/** Shows Android's own screen-capture consent and hands the answer back. */
class ProjectionRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        Projection.pending?.invoke(resultCode, data)
        Projection.pending = null
        finish()
    }
}

/** The foreground service MediaProjection runs in (Android 10+). */
class ProjectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("gaiadesk-embed", "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "gaiadesk-embed")
            .setContentTitle("Sharing your screen with support")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, n)
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            val data: Intent? = intent?.getParcelableExtra("data")
            val p = data?.let { mpm.getMediaProjection(intent.getIntExtra("code", 0), it) }
            Projection.onReady?.invoke(p)
        } catch (e: SecurityException) {
            // The app did not declare the foreground-service permissions for SCREEN.
            Projection.onReady?.invoke(null)
            stopSelf()
        }
        Projection.onReady = null
        return START_NOT_STICKY
    }
}
