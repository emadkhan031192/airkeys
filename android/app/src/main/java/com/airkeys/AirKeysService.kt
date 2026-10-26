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
 * Foreground service, v1.1: holds the active transport so input keeps
 * working with the screen off.
 *
 * Transports, in order for Auto mode:
 *  1. LAN (TCP) — phone hotspot you already share, home Wi-Fi, or USB
 *     tethering. Found by UDP broadcast, then subnet scan. This is tried
 *     first because it is debuggable and has no SDP/bonding quirks.
 *  2. Bluetooth RFCOMM — fallback, same frames, same pairing keys.
 *
 * HONEST STATUS (the v1.0 bug): "Connected" is only ever shown after the
 * PAIR handshake succeeds (onPaired). A socket that opened but never
 * paired shows "Pairing…" and then "Pairing failed — re-enter code",
 * never "Connected". No more connected-but-dead input.
 */
class AirKeysService : Service() {
    companion object {
        const val CHANNEL_ID = "airkeys_connection"
        const val NOTIF_ID = 1
        const val ACTION_CONNECT = "com.airkeys.CONNECT"
        const val ACTION_CONNECT_LAN = "com.airkeys.CONNECT_LAN"
        const val ACTION_CONNECT_AUTO = "com.airkeys.CONNECT_AUTO"
        const val ACTION_DISCONNECT = "com.airkeys.DISCONNECT"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_HOST = "host"
        const val EXTRA_CODE = "code" // 6-digit code, first pairing only; never logged
        @Volatile var instance: AirKeysService? = null; private set
    }

    lateinit var settings: SettingsStore
    lateinit var pairingStore: PairingKeyStore
    private lateinit var btConn: ConnectionManager
    private lateinit var tcpConn: TcpTransport

    @Volatile var status: String = "Disconnected"; private set
    @Volatile var transportName: String = ""; private set
    @Volatile var deviceName: String? = null; private set
    @Volatile var wantConnected = false; private set
    @Volatile private var lastPongMs = 0L
    @Volatile private var pairedThisAttempt = false
    private var loopThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        pairingStore = PairingKeyStore(this)
        btConn = ConnectionManager(
            onDisconnected = { onSocketLost() },
            onPong = { lastPongMs = System.currentTimeMillis() },
            onPaired = { pairedThisAttempt = true; transportName = "Bluetooth"; updateStatus("Connected (Bluetooth)") },
        )
        tcpConn = TcpTransport(
            onDisconnected = { onSocketLost() },
            onPong = { lastPongMs = System.currentTimeMillis() },
            onPaired = { pairedThisAttempt = true; transportName = "LAN"; updateStatus("Connected (LAN)") },
        )
        createChannel()
        startForeground(NOTIF_ID, buildNotification("AirKeys — Disconnected"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                wantConnected = true
                startLoop { btLoop(address, intent.getStringExtra(EXTRA_CODE)) }
            }
            ACTION_CONNECT_LAN -> {
                wantConnected = true
                startLoop { lanLoop(intent.getStringExtra(EXTRA_HOST), intent.getStringExtra(EXTRA_CODE)) }
            }
            ACTION_CONNECT_AUTO -> {
                wantConnected = true
                startLoop { autoLoop(intent.getStringExtra(EXTRA_CODE)) }
            }
            ACTION_DISCONNECT -> {
                wantConnected = false
                btConn.disconnect(); tcpConn.disconnect()
                updateStatus("Disconnected")
            }
        }
        return START_STICKY
    }

    private fun startLoop(body: () -> Unit) {
        loopThread?.interrupt()
        loopThread = thread(name = "airkeys-loop", isDaemon = true) { body() }
    }

    /** Wait up to [timeoutMs] for the PAIR reply. Returns true only if paired. */
    private fun waitPaired(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (pairedThisAttempt) return true
            if (!wantConnected) return false
            if (!btConn.isConnected && !tcpConn.isConnected) return false
            try { Thread.sleep(50) } catch (_: InterruptedException) { return false }
        }
        return pairedThisAttempt
    }

    private fun backoffSleep(backoffMs: Long) {
        try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { /* exit loop */ }
    }

    /** One Bluetooth attempt. Returns true only if socket + PAIR both succeed. */
    private fun tryBluetooth(address: String, code: String?): Boolean {
        pairedThisAttempt = false
        updateStatus("Connecting (Bluetooth)…")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val device: BluetoothDevice? = try { adapter?.getRemoteDevice(address) } catch (_: Exception) { null }
        if (device == null) return false
        val stored = pairingStore.load(address)
        val ok = when {
            stored != null -> btConn.connect(device, stored) { }
            code != null -> {
                val c = btConn.connectWithCode(device, code)
                if (c) pairingStore.save(address, Crypto.deriveKeyFromCode(code))
                c
            }
            else -> false
        }
        if (!ok) return false
        lastPongMs = System.currentTimeMillis()
        if (!waitPaired(5000)) {
            btConn.disconnect()
            updateStatus("Pairing failed — re-enter code (Bluetooth)")
            return false
        }
        deviceName = device.name
        settings.lastDeviceAddress = address
        settings.lastDeviceName = device.name
        return true
    }

    /** One LAN attempt. Returns true only if socket + PAIR both succeed. */
    private fun tryLan(host: String, code: String?): Boolean {
        pairedThisAttempt = false
        updateStatus("Connecting (LAN) $host…")
        val keyId = "lan:$host"
        val stored = pairingStore.load(keyId) ?: pairingStore.loadFirstLan()
        val ok = when {
            stored != null -> tcpConn.connect(host, stored)
            code != null -> {
                val c = tcpConn.connectWithCode(host, code)
                if (c) pairingStore.save(keyId, Crypto.deriveKeyFromCode(code))
                c
            }
            else -> false
        }
        if (!ok) return false
        lastPongMs = System.currentTimeMillis()
        if (!waitPaired(5000)) {
            tcpConn.disconnect()
            updateStatus("Pairing failed — re-enter code (LAN)")
            return false
        }
        deviceName = host
        settings.lastDeviceName = host
        return true
    }

    private fun btLoop(address: String, code: String?) {
        var backoffMs = 1000L
        while (wantConnected && !Thread.currentThread().isInterrupted) {
            if (btConn.isConnected) {
                if (lastPongMs > 0 && System.currentTimeMillis() - lastPongMs > 9000) {
                    btConn.disconnect()
                } else { backoffSleep(1000); continue }
            }
            if (tryBluetooth(address, code)) { backoffMs = 1000L; continue }
            if (!pairedThisAttempt) updateStatus("Reconnecting (Bluetooth)…")
            backoffSleep(backoffMs); backoffMs = minOf(backoffMs * 2, 30_000L)
        }
    }

    private fun lanLoop(host: String?, code: String?) {
        var backoffMs = 1000L
        var knownHost = host?.takeIf { it.isNotBlank() }
        while (wantConnected && !Thread.currentThread().isInterrupted) {
            if (tcpConn.isConnected) {
                if (lastPongMs > 0 && System.currentTimeMillis() - lastPongMs > 9000) {
                    tcpConn.disconnect()
                } else { backoffSleep(1000); continue }
            }
            if (knownHost == null) {
                updateStatus("Discovering LAN PC…")
                knownHost = LanDiscovery.discover()
                if (knownHost == null) {
                    updateStatus("No LAN PC found — is the PC app running on the same Wi-Fi/hotspot?")
                    backoffSleep(backoffMs); backoffMs = minOf(backoffMs * 2, 30_000L); continue
                }
            }
            if (tryLan(knownHost!!, code)) { backoffMs = 1000L; continue }
            // If the host moved (hotspot IP changed), rediscover next round.
            if (!pairedThisAttempt && tcpConn.isConnected.not()) {
                // Keep knownHost for stored-key retries; discovery only
                // resets it after a socket failure without any pairing.
                // (No-op: next attempt reuses the same host.)
            }
            backoffSleep(backoffMs); backoffMs = minOf(backoffMs * 2, 30_000L)
        }
    }

    private fun autoLoop(code: String?) {
        var backoffMs = 1000L
        while (wantConnected && !Thread.currentThread().isInterrupted) {
            if (tcpConn.isConnected || btConn.isConnected) {
                if (lastPongMs > 0 && System.currentTimeMillis() - lastPongMs > 9000) {
                    tcpConn.disconnect(); btConn.disconnect()
                } else { backoffSleep(1000); continue }
            }
            // LAN first (reuses your existing hotspot / Wi-Fi / USB LAN).
            updateStatus("Discovering LAN PC…")
            val lanHost = LanDiscovery.listenOnce(2500) ?: LanDiscovery.scanPrefix("192.168.43.")
                ?: LanDiscovery.scanPrefix("192.168.137.") ?: LanDiscovery.scanPrefix("192.168.42.")
            if (lanHost != null && tryLan(lanHost, code)) { backoffMs = 1000L; continue }
            // Bluetooth fallback, only if we have a last device.
            val lastBt = settings.lastDeviceAddress
            if (lastBt != null && tryBluetooth(lastBt, code)) { backoffMs = 1000L; continue }
            updateStatus("No PC found — check PC app is running on the same network")
            backoffSleep(backoffMs); backoffMs = minOf(backoffMs * 2, 30_000L)
        }
    }

    private fun onSocketLost() {
        if (wantConnected) updateStatus("Reconnecting…") else updateStatus("Disconnected")
    }

    private fun updateStatus(s: String) {
        status = s
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification("AirKeys — $s"))
        AirKeysViewModel.onServiceStatus(s)
    }

    /** The transport actually carrying input right now, if any. */
    fun connection(): InputTransport? =
        when {
            tcpConn.isConnected -> tcpConn
            btConn.isConnected -> btConn
            else -> null
        }

    fun framesSent(): Long = (tcpConn.framesSent + btConn.framesSent)

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
    override fun onDestroy() {
        wantConnected = false; btConn.disconnect(); tcpConn.disconnect()
        instance = null; super.onDestroy()
    }
}
