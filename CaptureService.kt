package com.berkay.oyunceviri

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Ekranı yakalar (Xbox uzaktan oynatma yayını telefon ekranında görünür),
 * alt bölgedeki altyazıyı OCR ile okur, Türkçeye çevirir, ekranda gösterir
 * ve istenirse seslendirir.
 *
 * Çeviri kutusu, OCR bölgesinin hemen ÜSTÜNE konur; böylece kendi çevirimizi
 * yeniden okumayız.
 */
class CaptureService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_STOP = "com.berkay.oyunceviri.STOP"
        private const val FRAME_INTERVAL_MS = 500L
        private const val MAX_CAPTURE_SIDE = 1280f
    }

    private lateinit var wm: WindowManager
    private val worker = HandlerThread("capture").also { it.start() }
    private val workerHandler by lazy { Handler(worker.looper) }
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var projCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var overlay: TextView? = null
    private var overlayLp: WindowManager.LayoutParams? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsOn = false

    private var tvLink: TvLink? = null
    private var tvEnabled = false
    private var tvSize = 28
    private var tvBg = 70

    private var translator: Translator? = null
    private var translatorReady = false
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var regionTopPercent = 72
    private var screenH = 0

    private var lastFrameAt = 0L
    private val busy = AtomicBoolean(false)

    // Yalnızca ana thread'de kullanılır
    private var shownKey = ""
    private var candKey = ""
    private var candCount = 0
    private var emptyCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }
        regionTopPercent = intent.getIntExtra("top", 72)
        ttsOn = intent.getBooleanExtra("tts", false)
        tvEnabled = intent.getBooleanExtra("tv", false)
        tvSize = intent.getIntExtra("tvSize", 28)
        tvBg = intent.getIntExtra("tvBg", 70)

        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        releaseAll() // tekrar başlatılırsa temiz başla
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        setupTranslator()
        if (ttsOn) tts = TextToSpeech(this, this)
        createOverlay()
        if (tvEnabled) {
            tvLink = TvLink(
                this,
                styleJson = {
                    JSONObject().put("type", "style").put("size", tvSize)
                        .put("bg", tvBg).put("shadow", true).toString()
                },
                onStatus = { toast(it) }
            ).also { it.start() }
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }
        projCallback = cb
        projection = mpm.getMediaProjection(code, data).also { p ->
            // Android 14+: sanal ekran oluşturmadan önce callback kaydı şart
            p.registerCallback(cb, workerHandler)
        }
        setupCapture()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("cap", "Oyun Çeviri", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val notif = Notification.Builder(this, "cap")
            .setContentTitle("Oyun Çeviri çalışıyor")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_delete, "Durdur", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    // ---------- Çeviri ----------

    private fun setupTranslator() {
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        val t = Translation.getClient(opts)
        translator = t
        translatorReady = false
        t.downloadModelIfNeeded()
            .addOnSuccessListener {
                translatorReady = true
                toast("Çeviri modeli hazır")
            }
            .addOnFailureListener {
                toast("Çeviri modeli indirilemedi. İnternete bağlanıp tekrar dene.")
            }
    }

    // ---------- Seslendirme ----------

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            toast("Seslendirme başlatılamadı")
            return
        }
        val r = tts?.setLanguage(Locale("tr", "TR"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast("Telefonda Türkçe ses verisi yok (Ayarlar > Metin okuma)")
        } else {
            ttsReady = true
        }
    }

    // ---------- Yakalama ----------

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun setupCapture() {
        val dm = realMetrics()
        screenH = dm.heightPixels
        val scale = min(1f, MAX_CAPTURE_SIDE / max(dm.widthPixels, dm.heightPixels))
        val w = (dm.widthPixels * scale).toInt()
        val h = (dm.heightPixels * scale).toInt()

        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        newReader.setOnImageAvailableListener({ onImage(it) }, workerHandler)
        val old = reader
        reader = newReader

        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection?.createVirtualDisplay(
                "oyunceviri", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, workerHandler
            )
        } else {
            vd.surface = newReader.surface
            vd.resize(w, h, dm.densityDpi)
        }
        old?.close()
        positionOverlay()
    }

    // Ekran döndürülünce (Xbox uygulaması yatay moda geçer) yakalamayı yeniden kur
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection != null) main.postDelayed({ setupCapture() }, 400)
    }

    private fun onImage(r: ImageReader) {
        val image: Image = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return
            if (!busy.compareAndSet(false, true)) return
            lastFrameAt = now

            val cropped = cropSubtitleRegion(image)
            if (cropped == null) {
                busy.set(false)
                return
            }
            recognizer.process(InputImage.fromBitmap(cropped, 0))
                .addOnSuccessListener { handleText(it.text) }
                .addOnCompleteListener {
                    cropped.recycle()
                    busy.set(false)
                }
        } finally {
            image.close()
        }
    }

    private fun cropSubtitleRegion(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val full = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)
            val top = (image.height * regionTopPercent / 100f).toInt().coerceIn(0, image.height - 16)
            val cropped = Bitmap.createBitmap(full, 0, top, image.width, image.height - top)
            full.recycle()
            cropped
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Metin işleme (ana thread) ----------

    private fun handleText(raw: String) {
        val text = raw.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (text.length < 4) {
            emptyCount++
            if (emptyCount >= 4) { // ~2 sn altyazı yok -> kutuyu gizle
                overlay?.visibility = View.GONE
                tvLink?.sendClear()
                shownKey = ""
                candKey = ""
                candCount = 0
            }
            return
        }
        emptyCount = 0

        val key = text.lowercase().filter { it.isLetterOrDigit() }
        if (key == shownKey) return
        if (key == candKey) candCount++ else { candKey = key; candCount = 1 }
        if (candCount < 2) return // 2 karede aynıysa kabul et (titreşimi önler)

        if (!translatorReady) return
        shownKey = key
        translator?.translate(text)
            ?.addOnSuccessListener { tr ->
                showOverlay(tr)
                tvLink?.sendSubtitle(tr)
                if (ttsOn && ttsReady) tts?.speak(tr, TextToSpeech.QUEUE_FLUSH, null, "sub")
            }
    }

    // ---------- Ekranda çeviri kutusu ----------

    private fun createOverlay() {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 16)
            setBackgroundColor(0xB3000000.toInt())
            visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        overlay = tv
        overlayLp = lp
        wm.addView(tv, lp)
    }

    // Kutu, OCR bölgesinin hemen üstünde dursun
    private fun positionOverlay() {
        val lp = overlayLp ?: return
        val v = overlay ?: return
        lp.y = (screenH * (100 - regionTopPercent) / 100f).toInt()
        try {
            wm.updateViewLayout(v, lp)
        } catch (_: Exception) {
        }
    }

    private fun showOverlay(text: String) {
        overlay?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    // ---------- Temizlik ----------

    private fun releaseAll() {
        virtualDisplay?.release(); virtualDisplay = null
        reader?.close(); reader = null
        try {
            // Eski oturumun onStop'u yeni oturumu kapatmasın
            projCallback?.let { projection?.unregisterCallback(it) }
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        projCallback = null
        overlay?.let { v ->
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }
        overlay = null
        tts?.shutdown(); tts = null; ttsReady = false
        translator?.close(); translator = null
        tvLink?.stop(); tvLink = null
        shownKey = ""; candKey = ""; candCount = 0; emptyCount = 0
    }

    override fun onDestroy() {
        releaseAll()
        recognizer.close()
        worker.quitSafely()
        super.onDestroy()
    }
}
