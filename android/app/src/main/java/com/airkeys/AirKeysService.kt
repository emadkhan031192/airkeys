package com.airkeys

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

/**
 * Foreground service: holds the Bluetooth socket so input keeps working
 * with the screen off. Auto-reconnects with backoff while the user leaves
 * Connect on. The UI binds to this service through the shared singleton in
 * AirKeysViewModel (see MainActivity) — the service itself owns no UI.
 */
class AirKeysService : Service() {
    companion object {
        const val CHANNEL_ID = "airkeys_connection"
        const val NOTIF_ID = 1
        const val ACTION_CONNECT = "com.airkeys.CONNECT"
        const val ACTION_DISCONNECT = "com.airkeys.DISCONNECT"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_CODE = "code" // 6-digit code, first pairing only; never logged
        @Volatile var instance: AirKeysService? = null; private set
    }

    lateinit var settings: SettingsStore
    lateinit var pairingStore: PairingKeyStore
    private lateinit var conn: ConnectionManager

    @Volatile var status: String = "Disconnected"; private set
    @Volatile var deviceName: String? = null; private set
    @Volatile var wantConnected = false; private set
    @Volatile private var lastPongMs = 0L
    private var reconnectThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        pairingStore = PairingKeyStore(this)
        conn = ConnectionManager(
            onDisconnected = { onSocketLost() },
            onPong = { lastPongMs = System.currentTimeMillis() },
            onPaired = { updateStatus("Connected") },
        )
        createChannel()
        startForeground(NOTIF_ID, buildNotification("AirKeys — Disconnected"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                val code = intent.getStringExtra(EXTRA_CODE)
                wantConnected = true
                startReconnectLoop(address, code)
            }
            ACTION_DISCONNECT -> {
                wantConnected = false
                conn.disconnect()
                updateStatus("Disconnected")
            }
        }
        return START_STICKY
    }

    private fun startReconnectLoop(address: String, code: String?) {
        reconnectThread?.interrupt()
        val t = thread(name = "airkeys-reconnect", isDaemon = true) {
            var backoffMs = 1000L
            val adapter = BluetoothAdapter.getDefaultAdapter()
            while (wantConnected && !Thread.currentThread().isInterrupted) {
                if (conn.isConnected) {
                    // Watchdog: no PONG for 9 s means a dead socket.
                    if (lastPongMs > 0 && System.currentTimeMillis() - lastPongMs > 9000) {
                        conn.disconnect()
                    } else {
                        try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                        continue
                    }
                }
                updateStatus("Connecting…")
                val device: BluetoothDevice? = try {
                    adapter?.getRemoteDevice(address)
                } catch (_: Exception) { null }
                if (device != null) {
                    val stored = pairingStore.load(address)
                    val ok = when {
                        stored != null -> conn.connect(device, stored) { }
                        code != null -> {
                            val connected = conn.connectWithCode(device, code)
                            if (connected) pairingStore.save(address, Crypto.deriveKeyFromCode(code))
                            connected
                        }
                        else -> false
                    }
                    if (ok) {
                        lastPongMs = System.currentTimeMillis()
                        deviceName = device.name
                        settings.lastDeviceAddress = address
                        settings.lastDeviceName = device.name
                        updateStatus("Connected")
                        backoffMs = 1000L
                        continue
                    }
                }
                updateStatus("Reconnecting…")
                try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
                backoffMs = minOf(backoffMs * 2, 30_000L)
            }
        }
        reconnectThread = t
    }

    private fun onSocketLost() {
        if (wantConnected) updateStatus("Reconnecting…") else updateStatus("Disconnected")
        // Reconnect loop (above) sees isConnected == false and retries.
    }

    private fun updateStatus(s: String) {
        status = s
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification("AirKeys — $s"))
        AirKeysViewModel.onServiceStatus(s)
    }

    fun connection(): ConnectionManager = conn

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "AirKeys connection",
                NotificationManager.IMPORTANCE_LOW)
            ch.description = "Shows AirKeys connection status so input keeps working with the screen off."
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AirKeys").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi).setOngoing(true).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { wantConnected = false; conn.disconnect(); instance = null; super.onDestroy() }
}
