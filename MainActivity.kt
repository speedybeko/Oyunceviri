package com.berkay.oyunceviri

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val reqCapture = 1001
    private val reqNotif = 1002

    private lateinit var status: TextView
    private lateinit var regionLabel: TextView
    private lateinit var regionSeek: SeekBar
    private lateinit var ttsSwitch: Switch
    private lateinit var tvSwitch: Switch
    private lateinit var tvSizeSeek: SeekBar
    private lateinit var tvSizeLabel: TextView
    private lateinit var tvBgSeek: SeekBar
    private lateinit var tvBgLabel: TextView

    private fun tvSize() = 20 + tvSizeSeek.progress // 20..48 sp
    private fun tvBg() = tvBgSeek.progress          // 0..100 %

    private fun SeekBar.onChange(block: () -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = block()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    // Altyazı okuma bölgesinin üst kenarı: ekran yüksekliğinin yüzdesi (50..95)
    private fun regionTop() = 50 + regionSeek.progress

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(20))
            setBackgroundColor(Color.parseColor("#121212"))
        }

        fun label(t: String, size: Float = 16f) = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(4))
        }

        root.addView(label("Oyun Çeviri", 26f))
        root.addView(
            label(
                "1) Başlat'a bas, ekran yakalamaya izin ver.\n" +
                    "2) Xbox uygulamasını aç, oyunu uzaktan oynat.\n" +
                    "3) Altyazı ekranın alt kısmında olmalı; aşağıdaki çubukla okunacak bölgenin üst kenarını ayarla.",
                14f
            )
        )

        regionLabel = label("")
        regionSeek = SeekBar(this).apply {
            max = 45
            progress = prefs.getInt("top", 72) - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = updateRegionLabel()
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(regionLabel)
        root.addView(regionSeek)
        updateRegionLabel()

        ttsSwitch = Switch(this).apply {
            text = "Türkçe seslendirme"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("tts", false)
        }
        root.addView(ttsSwitch)

        // --- Android TV'ye gönderme ---
        tvSwitch = Switch(this).apply {
            text = "Çeviriyi TV'ye de gönder (TV'de Oyun Çeviri TV açık olmalı)"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("tv", false)
        }
        root.addView(tvSwitch)

        tvSizeLabel = label("")
        tvSizeSeek = SeekBar(this).apply {
            max = 28
            progress = prefs.getInt("tvSize", 28) - 20
            onChange { updateTvLabels() }
        }
        tvBgLabel = label("")
        tvBgSeek = SeekBar(this).apply {
            max = 100
            progress = prefs.getInt("tvBg", 70)
            onChange { updateTvLabels() }
        }
        root.addView(tvSizeLabel)
        root.addView(tvSizeSeek)
        root.addView(tvBgLabel)
        root.addView(tvBgSeek)
        updateTvLabels()

        val start = Button(this).apply {
            text = "Başlat"
            setOnClickListener { onStartClicked() }
        }
        val stop = Button(this).apply {
            text = "Durdur"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                status.text = "Durduruldu."
            }
        }
        root.addView(start)
        root.addView(stop)

        status = label("Hazır.", 14f).apply { gravity = Gravity.START }
        root.addView(status)

        setContentView(root)
    }

    private fun updateTvLabels() {
        tvSizeLabel.text = "TV yazı boyutu: ${tvSize()}"
        tvBgLabel.text = "TV arka plan koyuluğu: %${tvBg()}"
    }

    private fun updateRegionLabel() {
        regionLabel.text = "Okunacak bölge: ekranın alt %${100 - regionTop()}'i"
    }

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            status.text = "Diğer uygulamaların üzerinde gösterme iznini ver, sonra geri dönüp tekrar Başlat'a bas."
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif)
            return
        }
        requestCapture()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqNotif) requestCapture() // izin verilmese de devam et
    }

    @Suppress("DEPRECATION")
    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Ekran yakalama izni verilmedi."
            return
        }
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putInt("top", regionTop())
            .putBoolean("tts", ttsSwitch.isChecked)
            .putBoolean("tv", tvSwitch.isChecked)
            .putInt("tvSize", tvSize())
            .putInt("tvBg", tvBg())
            .apply()

        val svc = Intent(this, CaptureService::class.java)
            .putExtra("code", resultCode)
            .putExtra("data", data)
            .putExtra("top", regionTop())
            .putExtra("tts", ttsSwitch.isChecked)
            .putExtra("tv", tvSwitch.isChecked)
            .putExtra("tvSize", tvSize())
            .putExtra("tvBg", tvBg())
        startForegroundService(svc)
        status.text = "Çalışıyor. Şimdi Xbox uygulamasına geç. İlk seferde çeviri modeli inerken internet gerekir."
    }
}
