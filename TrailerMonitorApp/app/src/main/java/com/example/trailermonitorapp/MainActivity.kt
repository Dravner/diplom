package com.example.trailermonitorapp

import android.app.*
import android.content.*
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import java.util.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.media.RingtoneManager
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities



// === ViewModel ===
class TrailerViewModel(private val context: Context) : ViewModel() {

    private val prefs = context.getSharedPreferences("trailer_prefs", Context.MODE_PRIVATE)

    private val _currentAngle = MutableStateFlow(0.0f)
    val currentAngle: StateFlow<Float> = _currentAngle

    private val _criticalAngle = MutableStateFlow(
        prefs.getFloat("critical_angle", 15.0f)
    )
    val criticalAngle: StateFlow<Float> = _criticalAngle

    private val _connectionStatus = MutableStateFlow("Подключение...")
    val connectionStatus: StateFlow<String> = _connectionStatus

    fun updateAngle(newAngle: Float) {
        _currentAngle.value = newAngle
    }

    fun updateThreshold(newThreshold: Float) {
        _criticalAngle.value = newThreshold
        prefs.edit().putFloat("critical_angle", newThreshold).apply()
    }

    fun updateConnectionStatus(status: String) {
        _connectionStatus.value = status
    }
}


// === Composable UI ===
@Composable
fun TrailerMonitorScreen(viewModel: TrailerViewModel) {
    val currentAngle by viewModel.currentAngle.collectAsStateWithLifecycle()
    val criticalAngle by viewModel.criticalAngle.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val mediaPlayer = remember { MediaPlayer.create(context, R.raw.alert) }
    var inputValue by remember { mutableStateOf(TextFieldValue(criticalAngle.toString())) }

    LaunchedEffect(currentAngle, criticalAngle) {
        if (kotlin.math.abs(currentAngle) > criticalAngle) {
            Toast.makeText(context, "ОПАСНОСТЬ ЗАВАЛА!", Toast.LENGTH_SHORT).show()
            if (!mediaPlayer.isPlaying) mediaPlayer.start()
        } else {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
                mediaPlayer.seekTo(0)
            }
        }
    }

    val statusColor = when (connectionStatus) {
        "Ожидание повторного подключения..." -> androidx.compose.ui.graphics.Color.Red
        "Подключение..." -> androidx.compose.ui.graphics.Color.Yellow
        "Подключено" -> androidx.compose.ui.graphics.Color.Green
        else -> androidx.compose.ui.graphics.Color.Gray
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Статус: $connectionStatus",
            color = statusColor,
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Угол наклона: ${"%.2f".format(currentAngle)}°",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = inputValue,
            onValueChange = { inputValue = it },
            label = { Text("Критический угол") }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            inputValue.text.toFloatOrNull()?.let {
                viewModel.updateThreshold(it)
                Toast.makeText(context, "Критический угол обновлён: $it°", Toast.LENGTH_SHORT).show()
                inputValue = TextFieldValue(it.toString())

                val intent = Intent(context, WebSocketService::class.java).apply {
                    action = WebSocketService.ACTION_UPDATE_CRITICAL_ANGLE
                    putExtra("critical_angle", it)
                }
                context.startService(intent)
            } ?: Toast.makeText(context, "Некорректное значение", Toast.LENGTH_SHORT).show()
        }) {
            Text("Сохранить")
        }
    }
}


// === Foreground Service ===
class WebSocketService : Service() {

    companion object {
        const  val CHANNEL_ID = "TrailerMonitorChannel"
        const val ACTION_UPDATE_CRITICAL_ANGLE = "com.example.trailermonitorapp.UPDATE_CRITICAL_ANGLE"
        const val STATUS_BROADCAST = "com.example.trailermonitorapp.STATUS_UPDATE"
    }

    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var client: OkHttpClient
    private var webSocket: WebSocket? = null
    private var criticalAngle = 15.0
    private var lastAlertTime = 0L
    private val alertCooldown = 5_000 // 5 секунд

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Мониторинг прицепа", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Оповещения о завале"
                enableLights(true)
                lightColor = Color.RED
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC

                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                setSound(soundUri, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                )

                vibrationPattern = longArrayOf(0, 500, 100, 500)
                enableVibration(true)
            }

            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.deleteNotificationChannel(CHANNEL_ID) // <-- ДОБАВЬ ЭТО, чтобы сбрасывать "тихий" канал
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TrailerMonitor")
            .setContentText("Слежение за углом наклона активно")
            .setSmallIcon(R.drawable.ic_start_monitoring) // ← твоя иконка
            .build()

        startForeground(1, notification)
        mediaPlayer = MediaPlayer.create(this, R.raw.alert)

        // загрузка критического угла
        criticalAngle = getSharedPreferences("trailer_prefs", MODE_PRIVATE)
            .getFloat("critical_angle", 15.0f).toDouble()

        connectToWebSocket()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_CRITICAL_ANGLE) {
            val newThreshold = intent.getFloatExtra("critical_angle", criticalAngle.toFloat())
            criticalAngle = newThreshold.toDouble()
            getSharedPreferences("trailer_prefs", MODE_PRIVATE)
                .edit().putFloat("critical_angle", newThreshold).apply()
            Log.d("WebSocketService", "Обновлен критический угол: $criticalAngle")
        }
        return START_STICKY
    }

    private fun connectToWebSocket() {
        updateStatus("Подключение...")

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val allNetworks = connectivityManager.allNetworks

// Найдём Wi-Fi сеть (которая не имеет интернета)
        val wifiNetwork = allNetworks.find { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

        if (wifiNetwork != null) {
            Log.d("WebSocket", "Привязываем WebSocket к Wi-Fi-сети ESP32")

            client = OkHttpClient.Builder()
                .socketFactory(wifiNetwork.socketFactory)
                .build()
        } else {
            Log.e("WebSocket", "Wi-Fi сеть не найдена!")
            return
        }

        val request = Request.Builder().url("ws://192.168.4.1:81/").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebSocket", "Соединение установлено")
                updateStatus("Подключено")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val roll = text.toDouble()
                    Log.d("WebSocket", "Угол: $roll")

                    if (kotlin.math.abs(roll) > criticalAngle) {
                        val now = System.currentTimeMillis()
                        if (now - lastAlertTime > alertCooldown) {
                            lastAlertTime = now
                            showDangerPushNotification()
                            if (!mediaPlayer.isPlaying) mediaPlayer.start()
                        }
                    }


                    val intent = Intent("com.example.trailermonitorapp.ANGLE_UPDATE").apply {
                        setPackage(packageName)
                        putExtra("angle", roll.toFloat())
                    }
                    sendBroadcast(intent)
                } catch (e: Exception) {
                    Log.e("WebSocket", "Ошибка при обработке сообщения: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocket", "Ошибка подключения: ${t.message}")
                updateStatus("Ожидание повторного подключения...")
                reconnectWithDelay()
            }
        })
    }

    private fun showDangerPushNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification) // ← твоя иконка
            .setContentTitle("Опасность завала!")
            .setContentText("Угол превышает критический предел")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(Notification.DEFAULT_ALL)
            .setSound(soundUri) // <-- ВАЖНО: звук
            .setVibrate(longArrayOf(0, 500, 200, 500)) // <-- ВАЖНО: вибрация
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(fullScreenIntent, true)

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(777, builder.build())
    }


    private fun reconnectWithDelay() {
        CoroutineScope(Dispatchers.Main).launch {
            delay(5000)
            connectToWebSocket()
        }
    }

    private fun updateStatus(status: String) {
        val intent = Intent(STATUS_BROADCAST).apply {
            setPackage(packageName)
            putExtra("status", status)
        }
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, null)
        client.dispatcher.executorService.shutdown()
        if (mediaPlayer.isPlaying) mediaPlayer.stop()
        mediaPlayer.release()
    }
}

// === Main Activity ===
class MainActivity : ComponentActivity() {
    private lateinit var angleReceiver: BroadcastReceiver
    private lateinit var statusReceiver: BroadcastReceiver

    private val viewModel: TrailerViewModel by viewModels(factoryProducer = {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TrailerViewModel(applicationContext) as T
            }
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

        enableEdgeToEdge()
        startService(Intent(this, WebSocketService::class.java))

        setContent {
            MaterialTheme {
                TrailerMonitorScreen(viewModel)
            }
        }
    }


    override fun onStart() {
        super.onStart()

        angleReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val angle = intent?.getFloatExtra("angle", 0.0f) ?: return
                viewModel.updateAngle(angle)
            }
        }

        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val status = intent?.getStringExtra("status") ?: return
                viewModel.updateConnectionStatus(status)
            }
        }

        val angleFilter = IntentFilter("com.example.trailermonitorapp.ANGLE_UPDATE")
        val statusFilter = IntentFilter(WebSocketService.STATUS_BROADCAST)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(angleReceiver, angleFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(statusReceiver, statusFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(angleReceiver, angleFilter)
            registerReceiver(statusReceiver, statusFilter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(angleReceiver)
        unregisterReceiver(statusReceiver)
    }
}
