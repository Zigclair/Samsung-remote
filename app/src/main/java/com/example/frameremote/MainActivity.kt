package com.example.frameremote

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.content.Context
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    @Volatile private var connected = false
    private lateinit var status: TextView
    private lateinit var ip: EditText
    private val prefs by lazy { getSharedPreferences("tv", Context.MODE_PRIVATE) }
    private val background = Color.rgb(17, 19, 24)
    private val panel = Color.rgb(42, 46, 55)
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(background); fillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 16.dp, 18.dp, 20.dp)
        }
        scroll.addView(root)
        setContentView(scroll)
        root.addView(TextView(this).apply {
            text = "THE FRAME"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8.dp)
        })
        status = TextView(this).apply {
            text = "Not connected"
            textSize = 13f
            setTextColor(0xffb9bdc7.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(status)
        val connection = LinearLayout(this)
        ip = EditText(this).apply {
            setSingleLine(true)
            hint = "TV IP (192.168.1.50)"
            setText(prefs.getString("ip", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(0xff9297a3.toInt())
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        connection.addView(ip, LinearLayout.LayoutParams(0, 55.dp, 1f))
        connection.addView(button("Connect") { connect() }, LinearLayout.LayoutParams(105.dp, 55.dp))
        root.addView(connection)
        addRow(root, "Power" to { key("KEY_POWER") }, "Art" to { key("KEY_POWER") }, "123" to { key("KEY_123") })
        addRow(root, "Settings" to { key("KEY_MENU") }, "Source" to { key("KEY_SOURCE") })
        addRow(root, "" to {}, "▲" to { key("KEY_UP") }, "" to {})
        addRow(root, "◀" to { key("KEY_LEFT") }, "OK" to { key("KEY_ENTER") }, "▶" to { key("KEY_RIGHT") })
        addRow(root, "" to {}, "▼" to { key("KEY_DOWN") }, "" to {})
        addRow(root, "Back" to { key("KEY_RETURN") }, "Home" to { key("KEY_HOME") }, "Play/Pause" to { key("KEY_PLAY") })
        addRow(root, "Vol +" to { key("KEY_VOLUP") }, "Ch +" to { key("KEY_CHUP") })
        addRow(root, "Mute" to { key("KEY_MUTE") }, "Guide" to { key("KEY_GUIDE") })
        addRow(root, "Vol -" to { key("KEY_VOLDOWN") }, "Ch -" to { key("KEY_CHDOWN") })
        addRow(root, "Netflix" to { launch("3201907018807") }, "YouTube" to { launch("111299001912") })
        addRow(root, "Prime Video" to { launch("3201910019365") }, "Disney+" to { launch("3201901017640") })
        root.addView(TextView(this).apply {
            text = "Connect on the same Wi-Fi and approve on the TV. Some TVs require secure pairing (port 8002), which is not yet supported. Art Mode and app shortcuts depend on TV model. Voice input is not supported."
            textSize = 12f
            setTextColor(0xff9ca2ad.toInt())
            setPadding(8.dp, 18.dp, 8.dp, 0)
        })
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = if (label.length > 9) 12f else 16f
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(panel)
        setOnClickListener { action() }
    }

    private fun addRow(root: LinearLayout, vararg items: Pair<String, () -> Unit>) {
        val row = LinearLayout(this)
        for ((label, action) in items) {
            val lp = LinearLayout.LayoutParams(0, 64.dp, 1f).apply {
                setMargins(3.dp, 3.dp, 3.dp, 3.dp)
            }
            row.addView(if (label.isEmpty()) View(this) else button(label, action), lp)
        }
        root.addView(row)
    }

    private fun showStatus(message: String) = runOnUiThread { status.text = message }

    private fun connect() {
        val host = ip.text.toString().trim()
        if (!Regex("^[0-9.]+$").matches(host)) {
            showStatus("Enter the TV's local IPv4 address")
            return
        }
        prefs.edit().putString("ip", host).apply()
        connected = false
        socket?.close(1000, "reconnect")
        val name = Base64.encodeToString("Frame Remote".toByteArray(), Base64.NO_WRAP)
        val token = prefs.getString("token", "") ?: ""
        val url = "ws://$host:8001/api/v2/channels/samsung.remote.control?name=$name" +
            if (token.isNotEmpty()) "&token=$token" else ""
        showStatus("Connecting - approve on TV if prompted")
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                showStatus("Waiting for TV authorization")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("event")) {
                        "ms.channel.connect" -> {
                            val newToken = obj.optJSONObject("data")?.optString("token", "") ?: ""
                            if (newToken.isNotEmpty()) prefs.edit().putString("token", newToken).apply()
                            connected = true
                            showStatus("Connected")
                        }
                        "ms.channel.unauthorized" -> showStatus("Permission denied on TV")
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                showStatus("Connection failed: ${t.message}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                showStatus("Disconnected")
            }
        })
    }

    private fun send(payload: JSONObject) {
        if (!connected || socket?.send(payload.toString()) != true) showStatus("Connect to TV first")
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
