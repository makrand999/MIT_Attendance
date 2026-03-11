package com.mit.attendance.ui.chat

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mit.attendance.R
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.databinding.ActivityChatBinding
import com.mit.attendance.databinding.ItemChatBinding
import com.mit.attendance.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private var mqttClient: MqttAsyncClient? = null
    private lateinit var adapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    
    private var userGender: String = "Male"
    private val topic = "mit_attendance/public_chat/room_1"
    private val brokerUrl = "tcp://broker.emqx.io:1883"
    private var isConnecting = false

    companion object {
        private const val TAG = "ChatActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()

        lifecycleScope.launch {
            val prefs = UserPreferences(applicationContext)
            userGender = prefs.getGender() ?: "Male"
            connectMqtt()
        }

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = ChatAdapter(messages)
        binding.rvChat.adapter = adapter
        binding.rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
    }

    private fun connectMqtt() {
        if (mqttClient?.isConnected == true || isConnecting) return
        isConnecting = true

        val clientId = "mit_chat_${System.currentTimeMillis()}"
        try {
            mqttClient?.let {
                try {
                    it.disconnect()
                    it.close()
                } catch (e: Exception) {}
            }

            val client = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
            mqttClient = client
            
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 30
                keepAliveInterval = 60
                isAutomaticReconnect = true
            }

            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    isConnecting = false
                    Log.d(TAG, "Connected to $serverURI (reconnect: $reconnect)")
                    subscribeToTopic()
                }

                override fun connectionLost(cause: Throwable?) {
                    isConnecting = false
                    Log.w(TAG, "Connection lost", cause)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.payload?.let { String(it) } ?: return
                    Log.d(TAG, "Message arrived: $payload")
                    try {
                        val chatMsg = Json.decodeFromString<ChatMessage>(payload)
                        runOnUiThread {
                            messages.add(chatMsg)
                            adapter.notifyItemInserted(messages.size - 1)
                            binding.rvChat.scrollToPosition(messages.size - 1)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error decoding message", e)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            Log.d(TAG, "Connecting to MQTT broker: $brokerUrl")
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    // Handled by connectComplete for MqttCallbackExtended
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    isConnecting = false
                    Log.e(TAG, "Connection failed", exception)
                    runOnUiThread {
                        Toast.makeText(this@ChatActivity, "Chat connection failed. Retrying...", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        } catch (e: MqttException) {
            isConnecting = false
            Log.e(TAG, "MqttException in connectMqtt", e)
        }
    }

    private fun subscribeToTopic() {
        try {
            mqttClient?.subscribe(topic, 0)
            Log.d(TAG, "Subscribed to $topic")
        } catch (e: MqttException) {
            Log.e(TAG, "Subscription failed", e)
        }
    }

    private fun sendMessage(text: String) {
        val chatMsg = ChatMessage(text = text, gender = userGender)
        val jsonMsg = Json.encodeToString(chatMsg)
        val message = MqttMessage(jsonMsg.toByteArray()).apply { qos = 1 }
        
        try {
            if (mqttClient?.isConnected == true) {
                mqttClient?.publish(topic, message, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Log.d(TAG, "Message published")
                        runOnUiThread { binding.etMessage.text.clear() }
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.e(TAG, "Publish failed", exception)
                        runOnUiThread {
                            Toast.makeText(this@ChatActivity, "Failed to send message", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            } else {
                Toast.makeText(this, "Connecting to chat...", Toast.LENGTH_SHORT).show()
                connectMqtt()
            }
        } catch (e: MqttException) {
            Log.e(TAG, "Error publishing", e)
        }
    }

    override fun onDestroy() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) {}
        super.onDestroy()
    }

    private class ChatAdapter(private val items: List<ChatMessage>) :
        RecyclerView.Adapter<ChatAdapter.VH>() {

        class VH(val binding: ItemChatBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.tvMessage.text = item.text
            
            val context = holder.itemView.context
            if (item.gender.equals("Female", ignoreCase = true)) {
                holder.binding.cardMessage.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.review_female_bg)
                )
            } else {
                holder.binding.cardMessage.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.card_background)
                )
            }
        }

        override fun getItemCount() = items.size
    }
}
