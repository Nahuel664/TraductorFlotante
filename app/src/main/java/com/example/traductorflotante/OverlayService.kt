package com.example.traductorflotante

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

private typealias LP = WindowManager.LayoutParams

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var held: Image? = null          // último fotograma de la pantalla
    private var bubble: TextView? = null
    private var result: FrameLayout? = null
    private var w = 0
    private var h = 0
    private var busy = false
    private var started = false

    // OCR (alfabeto latino: inglés, español, francés, etc.)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val langId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.6f).build()
    )
    private val translators = HashMap<String, Translator>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, "data", Intent::class.java) }
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        started = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        startCapture(code, data)
        addBubble()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("tf", "Traductor flotante", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "tf")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Traductor flotante activo")
            .setContentText("Toca esta notificación para detenerlo")
            .setContentIntent(stop)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    @Suppress("DEPRECATION")
    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        projection = p

        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels; h = dm.heightPixels
        }
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            try {
                val img = rd.acquireLatestImage()
                if (img != null) { held?.close(); held = img }
            } catch (_: Exception) {}
        }, handler)
        reader = r
        display = p.createVirtualDisplay(
            "tf", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
    }

    private fun addBubble() {
        val size = dp(56)
        val tv = TextView(this).apply {
            text = "文A"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE61565C0.toInt())
            }
        }
        val lp = LP(
            size, size, LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(200) }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var downT = 0L; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    downT = e.eventTime; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        wm.updateViewLayout(tv, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (e.eventTime - downT > 800) stopSelf() else capture()
                }
            }
            true
        }
        wm.addView(tv, lp)
        bubble = tv
    }

    private fun capture() {
        if (busy) return
        busy = true
        removeResult()
        bubble?.visibility = View.INVISIBLE      // para que la burbuja no salga en la captura
        handler.postDelayed({ grab() }, 300)
    }

    private fun grab() {
        val img = held
        bubble?.visibility = View.VISIBLE
        if (img == null) {
            toast("No pude capturar la pantalla, intenta de nuevo")
            busy = false
            return
        }
        val bmp = try {
            val pl = img.planes[0]
            pl.buffer.rewind()
            val full = Bitmap.createBitmap(pl.rowStride / pl.pixelStride, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(pl.buffer)
            val b = Bitmap.createBitmap(full, 0, 0, w, h)
            if (b !== full) full.recycle()
            b
        } catch (e: Exception) {
            toast("Error al capturar: ${e.message}")
            busy = false
            return
        }
        scope.launch { process(bmp) }
    }

    private suspend fun process(bmp: Bitmap) {
        try {
            val target = getSharedPreferences("prefs", MODE_PRIVATE).getString("target", "en")!!
            val text = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
            val items = ArrayList<Pair<Rect, String>>()
            for (block in text.textBlocks) {
                val rect = block.boundingBox ?: continue
                val src = block.text.replace("\n", " ").trim()
                if (src.count { it.isLetter() } < 3) continue   // ignora números, horas, iconos
                val code = langId.identifyLanguage(src).await()
                if (code == "und" || code == target) continue
                val srcLang = TranslateLanguage.fromLanguageTag(code) ?: continue
                val tr = translators.getOrPut("$srcLang>$target") {
                    Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(srcLang).setTargetLanguage(target).build()
                    )
                }
                tr.downloadModelIfNeeded().await()   // solo descarga la primera vez
                items += rect to tr.translate(src).await()
            }
            if (items.isEmpty()) toast("No encontré texto para traducir") else showResult(items)
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        } finally {
            busy = false
            bmp.recycle()
        }
    }

    private fun showResult(items: List<Pair<Rect, String>>) {
        removeResult()
        val c = FrameLayout(this)
        c.setOnClickListener { removeResult() }
        for ((rect, t) in items) {
            val tv = TextView(this).apply {
                text = t
                setTextColor(Color.BLACK)
                setBackgroundColor(0xF2FFFFFF.toInt())
                setPadding(dp(3), dp(1), dp(3), dp(1))
                gravity = Gravity.CENTER_VERTICAL
                setAutoSizeTextTypeUniformWithConfiguration(8, 18, 1, TypedValue.COMPLEX_UNIT_SP)
            }
            c.addView(tv, FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
                leftMargin = rect.left; topMargin = rect.top
            })
        }
        val lp = LP(
            LP.MATCH_PARENT, LP.MATCH_PARENT, LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(c, lp)
        result = c
    }

    private fun removeResult() {
        result?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        result = null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        if (::wm.isInitialized) {
            removeResult()
            bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        }
        held?.close()
        display?.release()
        reader?.close()
        projection?.stop()
        translators.values.forEach { it.close() }
        recognizer.close()
        langId.close()
        super.onDestroy()
    }
}
