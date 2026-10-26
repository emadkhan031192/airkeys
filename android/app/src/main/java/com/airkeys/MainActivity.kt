package com.airkeys

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.airkeys.ui.AirKeysApp

class MainActivity : ComponentActivity() {
    private val vm: AirKeysViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { /* UI re-reads state on resume */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBluetoothPermissions()
        setContent { AirKeysApp(vm, this) }
    }

    fun requestBluetoothPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_SCAN
            if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        } else {
            needed += Manifest.permission.BLUETOOTH
            needed += Manifest.permission.BLUETOOTH_ADMIN
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    fun bondedDevices(): List<Pair<String, String>> {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = mgr.adapter
        return try {
            adapter?.bondedDevices?.map { (it.name ?: "Unknown") to it.address }?.sortedBy { it.first }
                ?: emptyList()
        } catch (e: SecurityException) { emptyList() }
    }

    fun connect(address: String, code: String?) {
        val intent = Intent(this, AirKeysService::class.java).apply {
            action = AirKeysService.ACTION_CONNECT
            putExtra(AirKeysService.EXTRA_ADDRESS, address)
            if (code != null) putExtra(AirKeysService.EXTRA_CODE, code)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    fun connectLan(host: String?, code: String?) {
        val intent = Intent(this, AirKeysService::class.java).apply {
            action = AirKeysService.ACTION_CONNECT_LAN
            if (!host.isNullOrBlank()) putExtra(AirKeysService.EXTRA_HOST, host)
            if (code != null) putExtra(AirKeysService.EXTRA_CODE, code)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    fun connectAuto(code: String?) {
        val intent = Intent(this, AirKeysService::class.java).apply {
            action = AirKeysService.ACTION_CONNECT_AUTO
            if (code != null) putExtra(AirKeysService.EXTRA_CODE, code)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    fun disconnect() {
        val intent = Intent(this, AirKeysService::class.java).apply {
            action = AirKeysService.ACTION_DISCONNECT
        }
        startService(intent)
    }
}
