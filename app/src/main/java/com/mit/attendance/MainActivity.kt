package com.mit.attendance

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.URI

class MainActivity : AppCompatActivity() {

    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var scrollView: ScrollView
    private var wsClient: QwenWebSocketClient? = null

    private val VPS_URL = "ws://103.205.140.90:8080"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOutput = findViewById(R.id.tvOutput)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        scrollView = findViewById(R.id.scrollView)

        connectToQwen()

        btnSend.setOnClickListener { sendMessage() }
        etInput.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
    }

    private fun connectToQwen() {
        wsClient = QwenWebSocketClient(
            serverUri = URI(VPS_URL),
            onOpenStarted = {
                appendOutput("[Connected to Qwen]\n")
            },
            onMessageReceived = { raw ->
                try {
                    val json = JSONObject(raw)
                    val text = json.optString("text", "")
                    if (text.isNotEmpty()) appendOutput(text)
                } catch (e: Exception) {
                    appendOutput(raw)
                }
            },
            onConnectionClosed = { appendOutput("\n[Disconnected]") },
            onErrorOccurred = { appendOutput("\n[Error: ${it.message}]") }
        )
        wsClient?.connect()
    }

    private fun sendMessage() {
        val input = etInput.text.toString().trim()
        if (input.isEmpty()) return
        appendOutput("\n> $input\n")
        wsClient?.sendInput(input)
        etInput.text.clear()
    }

    private fun appendOutput(text: String) {
        runOnUiThread {
            tvOutput.append(text)
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        wsClient?.close()
        super.onDestroy()
    }
}
