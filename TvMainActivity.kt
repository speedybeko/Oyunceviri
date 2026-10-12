package com.berkay.oyunceviri.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Kumandayla kullanılan basit kontrol ekranı. Asıl iş OverlayService'te.
 * Android TV'lerde "diğer uygulamaların üzerinde göster" ekranı çoğu zaman yoktur;
 * o durumda izin ADB ile verilir (komut ekranda yazar).
 */
class TvMainActivity : Activity() {

    private lateinit var info: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(48), dp(64), dp(32))
            setBackgroundColor(Color.parseColor("#101828"))
        }

        fun text(t: String, size: Float) = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(8))
        }

        root.addView(text("Oyun Çeviri TV", 32f))
        info = text("", 18f)
        root.addView(info)

        val start = Button(this).apply {
            text = "Servisi başlat"
            setOnClickListener { onStart() }
        }
        val test = Button(this).apply {
            text = "10 sn sonra test altyazısı göster (HDMI'a geç)"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@TvMainActivity)) {
                    startForegroundService(
                        Intent(this@TvMainActivity, OverlayService::class.java)
                            .setAction(OverlayService.ACTION_TEST)
                    )
                    refresh("Test başladı. TV girişini hemen HDMI'a çevir. 10 sn sonra altyazı çıkmalı.")
                } else {
                    onStart()
                }
            }
        }
        val stop = Button(this).apply {
            text = "Servisi durdur"
            setOnClickListener {
                startService(Intent(this@TvMainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
                refresh("Durduruldu.")
            }
        }
        root.addView(start)
        root.addView(test)
        root.addView(stop)
        setContentView(root)
        start.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refresh(null)
    }

    private fun refresh(extra: String?) {
        val perm = if (Settings.canDrawOverlays(this)) "VAR" else "YOK"
        info.text = buildString {
            append("Overlay izni: $perm\n")
            append("Servis adı: OyunCeviri-TV (port ${OverlayService.PORT})\n")
            append("Telefon uygulaması aynı Wi-Fi'de bu TV'yi kendiliğinden bulur.\n")
            if (!Settings.canDrawOverlays(this@TvMainActivity)) {
                append("\nİzin için bilgisayardan (ADB):\n")
                append("adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow\n")
            }
            if (extra != null) append("\n$extra")
        }
    }

    private fun onStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                refresh("Bu TV'de izin ekranı yok. Yukarıdaki ADB komutunu çalıştır.")
            }
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        refresh("Servis çalışıyor. Telefondan bağlanabilirsin.")
    }
}
