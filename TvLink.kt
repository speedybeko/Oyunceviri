package com.berkay.oyunceviri

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Telefon -> Android TV bağlantısı.
 *  - NSD ile "_oyunceviri._tcp." servisini bulur (IP girmek gerekmez),
 *  - OkHttp WebSocket ile bağlanır, koparsa kendiliğinden yeniden dener,
 *  - sendSubtitle / sendClear ile metni TV'ye iletir (kuyruğa atar, bloklamaz).
 *
 * Not: ws:// düz metin trafiktir; manifestte usesCleartextTraffic açık olmalı.
 */
class TvLink(
    context: Context,
    private val styleJson: () -> String,
    private val onStatus: (String) -> Unit
) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)   // kopan bağlantıyı fark etmek için
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket uzun yaşar
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    private var lostNotified = false
    private var discovering = false
    private var resolving = false
    private var lastHost: String? = null
    private var lastPort = 0

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discovering = false
            onStatus("TV aranamadı (hata $errorCode)")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onServiceFound(info: NsdServiceInfo) {
            if (!info.serviceType.startsWith("_oyunceviri._tcp")) return
            resolve(info)
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            // TV kapandıysa WebSocket zaten kopar; yeniden bulununca tekrar bağlanırız
        }
    }

    fun start() {
        running = true
        if (!discovering) {
            discovering = true
            nsd.discoverServices("_oyunceviri._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }
        onStatus("TV aranıyor...")
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        if (discovering) {
            try {
                nsd.stopServiceDiscovery(discoveryListener)
            } catch (_: Exception) {
            }
            discovering = false
        }
        socket?.close(1000, "bye")
        socket = null
        client.dispatcher.executorService.shutdown()
    }

    fun sendSubtitle(text: String) {
        socket?.send(JSONObject().put("type", "sub").put("text", text).toString())
    }

    fun sendClear() {
        socket?.send("""{"type":"clear"}""")
    }

    // ---------- iç işler ----------

    // NsdManager aynı anda tek resolve kabul eder
    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        if (resolving || socket != null) return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving = false
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                resolving = false
                val host = serviceInfo.host?.hostAddress ?: return
                lastHost = host
                lastPort = serviceInfo.port
                connect(host, serviceInfo.port)
            }
        })
    }

    private fun connect(host: String, port: Int) {
        if (!running || socket != null) return
        val request = Request.Builder().url("ws://$host:$port").build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket != null) { // aynı anda iki bağlantı açıldıysa fazlayı kapat
                    webSocket.close(1000, "dup")
                    return
                }
                socket = webSocket
                lostNotified = false
                webSocket.send(styleJson())
                onStatus("TV bağlandı ($host)")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        })
    }

    private fun lost(ws: WebSocket) {
        if (socket === ws) socket = null
        if (!running) return
        if (!lostNotified) { // her denemede bildirim yağmasın
            lostNotified = true
            onStatus("TV bağlantısı koptu, yeniden deneniyor")
        }
        main.postDelayed({
            val h = lastHost
            if (running && socket == null && h != null) connect(h, lastPort)
        }, 2000)
    }
}
