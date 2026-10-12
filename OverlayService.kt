package com.berkay.oyunceviri.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONException
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * Android TV tarafı:
 *  1) WebSocket sunucusu açar (telefondan altyazı metni gelir),
 *  2) NSD (mDNS) ile "_oyunceviri._tcp." servisini yayınlar, telefon IP'siz bulur,
 *  3) Gelen metni WindowManager overlay'i ile ekranın altına çizer.
 *
 * Mesaj protokolü (JSON, tek satır):
 *   {"type":"sub","text":"Merhaba"}   -> altyazıyı göster
 *   {"type":"clear"}                   -> altyazıyı gizle
 *   {"type":"style","size":28,"bg":70,"shadow":true} -> görünümü ayarla (kalıcı)
 * JSON olmayan düz metin de altyazı olarak gösterilir (test için).
 */
class OverlayService : Service() {

    companion object {
        const val PORT = 8765
        const val SERVICE_TYPE = "_oyunceviri._tcp."
        const val ACTION_TEST = "com.berkay.oyunceviri.tv.TEST"
        const val ACTION_STOP = "com.berkay.oyunceviri.tv.STOP"
        private const val TAG = "OyunCeviriTV"
        private const val AUTO_HIDE_MS = 15_000L
        private const val TEST_DELAY_MS = 10_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var rootView: View? = null
    private var subtitleView: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var server: SubtitleServer? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null

    private val hideRunnable = Runnable { hideSubtitle() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay izni yok. Uygulamayı açıp talimata bak.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (rootView == null) createOverlay()
        if (server == null) startServer()
        if (regListener == null) registerNsd()

        if (intent?.action == ACTION_TEST) {
            // HDMI girişine geçmen için 10 sn bekler, sonra örnek altyazı gösterir
            main.postDelayed({
                showSubtitle("Test altyazısı: overlay bu ekranda çalışıyor")
            }, TEST_DELAY_MS)
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ovl", "Oyun Çeviri TV", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, "ovl")
            .setContentTitle("Oyun Çeviri TV çalışıyor")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    // ---------------- WebSocket sunucusu ----------------

    private inner class SubtitleServer : WebSocketServer(InetSocketAddress(PORT)) {
        override fun onStart() {
            Log.i(TAG, "WebSocket sunucusu hazır: $PORT")
            connectionLostTimeout = 15
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            Log.i(TAG, "Bağlandı: ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
            Log.i(TAG, "Koptu: $code")
        }

        override fun onMessage(conn: WebSocket, message: String) = handleMessage(message)

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.e(TAG, "WebSocket hatası", ex)
        }
    }

    private fun startServer() {
        server = SubtitleServer().also {
            it.setReuseAddr(true)
            it.setTcpNoDelay(true) // küçük mesajlar Nagle yüzünden beklemesin
            it.start()
        }
    }

    // WebSocket thread'inde çağrılır
    private fun handleMessage(raw: String) {
        try {
            val j = JSONObject(raw)
            when (j.optString("type")) {
                "sub" -> main.post { showSubtitle(j.optString("text")) }
                "clear" -> main.post { hideSubtitle() }
                "style" -> main.post { updateStyle(j) }
            }
        } catch (e: JSONException) {
            main.post { showSubtitle(raw) } // düz metin: test için
        }
    }

    // ---------------- NSD (mDNS) ----------------

    private fun registerNsd() {
        val manager = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = manager
        val info = NsdServiceInfo().apply {
            serviceName = "OyunCeviri-TV"
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                Log.i(TAG, "NSD yayında: ${i.serviceName}")
            }

            override fun onRegistrationFailed(i: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD kaydı başarısız: $errorCode")
            }

            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, errorCode: Int) {}
        }
        regListener = listener
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    // ---------------- Overlay ----------------

    private fun createOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LayoutInflater.from(this).inflate(R.layout.overlay_subtitle, null)
        val tv = root.findViewById<TextView>(R.id.subtitleText)
        root.visibility = View.GONE

        val bottomMargin = (resources.displayMetrics.heightPixels * 0.07f).toInt()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = bottomMargin
        }

        rootView = root
        subtitleView = tv
        overlayParams = lp
        wm.addView(root, lp)
        applyStyle()
    }

    private fun prefs() = getSharedPreferences("tv_cfg", MODE_PRIVATE)

    private fun updateStyle(j: JSONObject) {
        prefs().edit()
            .putInt("size", j.optInt("size", 28).coerceIn(14, 72))
            .putInt("bg", j.optInt("bg", 70).coerceIn(0, 100))
            .putBoolean("shadow", j.optBoolean("shadow", true))
            .apply()
        applyStyle()
    }

    /** Yazı boyutu (sp), arka plan koyuluğu (%) ve gölge. */
    private fun applyStyle() {
        val tv = subtitleView ?: return
        val p = prefs()
        val sizeSp = p.getInt("size", 28)
        val bgPercent = p.getInt("bg", 70)
        val shadow = p.getBoolean("shadow", true)

        tv.textSize = sizeSp.toFloat()
        tv.background = GradientDrawable().apply {
            setColor(Color.argb(255 * bgPercent / 100, 0, 0, 0))
            cornerRadius = 12f * resources.displayMetrics.density
        }
        if (shadow) {
            tv.setShadowLayer(6f, 2f, 2f, Color.BLACK)
        } else {
            tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    private fun showSubtitle(text: String) {
        if (text.isBlank()) {
            hideSubtitle()
            return
        }
        subtitleView?.text = text
        rootView?.visibility = View.VISIBLE
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, AUTO_HIDE_MS) // 'clear' kaybolursa takılı kalmasın
    }

    private fun hideSubtitle() {
        main.removeCallbacks(hideRunnable)
        rootView?.visibility = View.GONE
    }

    // ---------------- Temizlik ----------------

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        try {
            regListener?.let { nsd?.unregisterService(it) }
        } catch (_: Exception) {
        }
        regListener = null
        try {
            server?.stop(500)
        } catch (_: Exception) {
        }
        server = null
        rootView?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        rootView = null
        super.onDestroy()
    }
}
