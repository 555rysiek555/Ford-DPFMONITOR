package com.example.dpfmonitor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class BluetoothDeviceActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val requestCode = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth_device)

        listView = findViewById(R.id.deviceList)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                requestCode
            )
        } else {
            loadDevices()
        }
    }

    private fun loadDevices() {
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        if (pairedDevices == null || pairedDevices.isEmpty()) {
            Toast.makeText(this, "Brak sparowanych urządzeń Bluetooth", Toast.LENGTH_LONG).show()
            return
        }

        val deviceList = pairedDevices.map { "${it.name}\n${it.address}" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceList)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val address = deviceList[position].split("\n")[1]
            getSharedPreferences("DPF_PREF", MODE_PRIVATE)
                .edit().putString("DEVICE_ADDRESS", address).apply()
            Toast.makeText(this, "Zapisano urządzenie: $address", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            loadDevices()
        } else {
            Toast.makeText(this, "Brak zgody na Bluetooth", Toast.LENGTH_LONG).show()
        }
    }
}
