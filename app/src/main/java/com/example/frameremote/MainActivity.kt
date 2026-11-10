package com.example.frameremote

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import okhttp3.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

class MainActivity : Activity() {
    private val client: OkHttpClient by lazy {
        // Samsung TVs commonly present a self-signed certificate on their local port 8002.
        // This client is only used for the selected local TV address.
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
    private var socket: WebSocket? = null
    @Volatile private var connected = false
    private val prefs by lazy { getSharedPreferences("tv", Context.MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var deviceName: TextView
    private lateinit var picker: LinearLayout
    private val discovered = linkedMapOf<String, String>()
    private val bg = Color.rgb(10, 13, 19)
    private val face = Color.rgb(34, 39, 49)
    private val accent = Color.rgb(112, 176, 255)
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(22.dp, 22.dp, 22.dp, 32.dp)
        }
        scroll.addView(root)
        setContentView(scroll)
        root.addView(label("FRAME", 27f, Color.WHITE, true))
        root.addView(label("SMART REMOTE", 10f, accent, true).apply { letterSpacing = 0.35f })
        deviceName = label("Finding your TV", 14f, Color.LTGRAY, false)
        deviceName.setPadding(0, 18.dp, 0, 0)
        root.addView(deviceName)
        status = label("Searching your Wi-Fi network…", 12f, 0xff8d98aa.toInt(), false)
        root.addView(status)
        val scan = pill("⌕  Find TV", 150, 46) { discover() }
        val scanLp = LinearLayout.LayoutParams(150.dp, 46.dp).apply { topMargin = 12.dp; bottomMargin = 6.dp }
        root.addView(scan, scanLp)
        picker = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(picker, LinearLayout.LayoutParams(-1, -2))
        root.addView(space(16))
        row(root, Triple("⏻", "KEY_POWER", 62), Triple("◈ Art", "KEY_POWER", 62), Triple("⌨ 123", "KEY_123", 62))
        root.addView(space(12))
        row(root, Triple("⚙", "KEY_MENU", 58), Triple("▣ Source", "KEY_SOURCE", 58))
        root.addView(space(14))
        val dpad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        dpadRow(dpad, listOf("" to "", "▲" to "KEY_UP", "" to ""))
        dpadRow(dpad, listOf("◀" to "KEY_LEFT", "OK" to "KEY_ENTER", "▶" to "KEY_RIGHT"))
        dpadRow(dpad, listOf("" to "", "▼" to "KEY_DOWN", "" to ""))
        root.addView(dpad)
        root.addView(space(14))
        row(root, Triple("↶ Back", "KEY_RETURN", 62), Triple("⌂ Home", "KEY_HOME", 62), Triple("⏯", "KEY_PLAY", 62))
        root.addView(space(12))
        row(root, Triple("VOL +", "KEY_VOLUP", 62), Triple("CH +", "KEY_CHUP", 62))
        row(root, Triple("◖ Mute", "KEY_MUTE", 62), Triple("Guide", "KEY_GUIDE", 62))
        row(root, Triple("VOL −", "KEY_VOLDOWN", 62), Triple("CH −", "KEY_CHDOWN", 62))
        root.addView(space(18))
        val apps = label("APPS", 11f, accent, true).apply { gravity = Gravity.START }
        root.addView(apps, LinearLayout.LayoutParams(-1, -2))
        appRow(root, "YouTube" to "111299001912", "Netflix" to "3201907018807")
        appRow(root, "Prime Video" to "3201910019365", "Disney+" to "3201901017640")
        root.addView(label("Keep your phone and TV on the same Wi-Fi. Approve the connection on your TV when asked. Art and app shortcuts vary by model.", 11f, 0xff788392.toInt(), false).apply {
            setPadding(0, 18.dp, 0, 0)
        })
        val saved = prefs.getString("ip", "") ?: ""
        if (saved.isNotBlank()) connect(saved)
        discover()
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, h.dp) }
    private fun pill(textValue: String, width: Int, height: Int, action: () -> Unit): TextView =
        label(textValue, if (textValue.length > 11) 12f else 17f, Color.WHITE, true).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(face, 0xff1b2029.toInt())).apply {
                cornerRadius = (height / 2).dp.toFloat()
                setStroke(1.dp, 0xff414b5b.toInt())
            }
            elevation = 3.dp.toFloat()
            isClickable = true
            setOnClickListener { action() }
        }
    private fun row(root: LinearLayout, vararg buttons: Triple<String, String, Int>) {
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for ((text, code, height) in buttons) {
            line.addView(pill(text, 0, height) { key(code) }, LinearLayout.LayoutParams(0, height.dp, 1f).apply {
                setMargins(4.dp, 4.dp, 4.dp, 4.dp)
            })
        }
        root.addView(line, LinearLayout.LayoutParams(-1, -2))
    }
    private fun dpadRow(root: LinearLayout, buttons: List<Pair<String, String>>) {
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for ((text, code) in buttons) {
            val cell = if (text.isEmpty()) View(this) else pill(text, 76, 72) { key(code) }
            line.addView(cell, LinearLayout.LayoutParams(76.dp, 72.dp).apply { setMargins(2.dp, 2.dp, 2.dp, 2.dp) })
        }
        root.addView(line)
    }
    private fun appRow(root: LinearLayout, vararg apps: Pair<String, String>) {
        val line = LinearLayout(this)
        for ((name, id) in apps) {
            line.addView(pill(name, 0, 52) { launch(id) }, LinearLayout.LayoutParams(0, 52.dp, 1f).apply {
                setMargins(4.dp, 5.dp, 4.dp, 5.dp)
            })
        }
        root.addView(line, LinearLayout.LayoutParams(-1, -2))
    }
    private fun showStatus(value: String) = runOnUiThread { status.text = value }

    private fun discover() {
        showStatus("Searching for Samsung TVs…")
        thread(name = "SamsungDiscovery", isDaemon = true) {
            try {
                val message = ("M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\nST: ssdp:all\r\n\r\n").toByteArray()
                DatagramSocket().use { udp ->
                    udp.soTimeout = 650
                    val address = InetAddress.getByName("239.255.255.250")
                    repeat(3) {
                        udp.send(DatagramPacket(message, message.size, address, 1900))
                    }
                    val until = System.currentTimeMillis() + 6500
                    while (System.currentTimeMillis() < until) {
                        val bytes = ByteArray(8192)
                        val packet = DatagramPacket(bytes, bytes.size)
                        try {
                            udp.receive(packet)
                            val response = String(packet.data, 0, packet.length)
                            val host = packet.address.hostAddress ?: continue
                            val samsung = response.contains("samsung", true) || response.contains("RemoteControlReceiver", true)
                            if (samsung) found(host, "Samsung TV")
                        } catch (_: SocketTimeoutException) { }
                    }
                }
            } catch (e: Exception) {
                showStatus("Search unavailable: ${e.message}")
            }
            runOnUiThread {
                if (discovered.isEmpty() && !connected) showStatus("No TV found. Check both devices are on the same Wi-Fi.")
                else if (discovered.isNotEmpty() && !connected) showStatus("Select your TV to connect")
            }
        }
    }
    private fun found(host: String, name: String) = runOnUiThread {
        if (discovered.containsKey(host)) return@runOnUiThread
        discovered[host] = name
        val item = pill("▣  $name  •  $host", 0, 48) { connect(host) }
        picker.addView(item, LinearLayout.LayoutParams(-1, 48.dp).apply { topMargin = 5.dp })
        if (!connected && (prefs.getString("ip", "") ?: "").isBlank()) connect(host)
    }
    private fun connect(host: String) {
        prefs.edit().putString("ip", host).apply()
        deviceName.text = "Samsung TV  •  $host"
        connected = false
        socket?.close(1000, "reconnect")
        connectPort(host, 8002)
    }

    private fun connectPort(host: String, port: Int) {
        val name = Base64.encodeToString("Frame Remote".toByteArray(), Base64.NO_WRAP)
        val token = prefs.getString("token_$host", "") ?: ""
        val scheme = if (port == 8002) "wss" else "ws"
        val url = "$scheme://$host:$port/api/v2/channels/samsung.remote.control?name=$name" +
            if (token.isNotBlank()) "&token=$token" else ""
        showStatus("Connecting securely — approve on your TV if prompted")
        val candidate = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                showStatus("Waiting for TV approval…")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("event")) {
                        "ms.channel.connect" -> {
                            val newToken = obj.optJSONObject("data")?.optString("token", "") ?: ""
                            if (newToken.isNotBlank()) prefs.edit().putString("token_$host", newToken).apply()
                            connected = true
                            socket = webSocket
                            showStatus("● Connected")
                        }
                        "ms.channel.unauthorized" -> showStatus("Permission denied — allow Frame Remote on your TV")
                    }
                } catch (_: Exception) {}
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connected) { connected = false; showStatus("TV disconnected"); return }
                if (port == 8002) {
                    showStatus("Secure connection unavailable; trying standard pairing…")
                    runOnUiThread { connectPort(host, 8001) }
                } else {
                    showStatus("Could not connect: ${t.javaClass.simpleName} — ${t.message ?: "check TV network settings"}")
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) {
                    connected = false
                    showStatus("Disconnected")
                }
            }
        })
        socket = candidate
    }

    private fun send(payload: JSONObject) {
        if (!connected || socket?.send(payload.toString()) != true) showStatus("Connect to your TV first")
    }
    private fun key(code: String) {
        send(JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("Cmd", "Click").put("DataOfCmd", code)
            .put("Option", "false").put("TypeOfRemote", "SendRemoteKey")))
    }
    private fun launch(appId: String) {
        send(JSONObject().put("method", "ms.channel.emit").put("params", JSONObject()
            .put("event", "ed.apps.launch").put("to", "host")
            .put("data", JSONObject().put("appId", appId).put("action_type", "NATIVE_LAUNCH"))))
    }
    override fun onDestroy() {
        socket?.close(1000, "app closed")
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
