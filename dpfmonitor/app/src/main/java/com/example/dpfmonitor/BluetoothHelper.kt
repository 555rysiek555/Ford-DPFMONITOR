package com.example.dpfmonitor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.util.*

object BluetoothHelper {

    private var socket: BluetoothSocket? = null

    fun connect(address: String): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val device = adapter.getRemoteDevice(address)
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        socket = device.createRfcommSocketToServiceRecord(uuid)
        return try {
            socket?.connect()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun sendCommand(cmd: String): String? {
        return try {
            val out = socket?.outputStream
            val input = socket?.inputStream

            out?.write((cmd + "\r").toByteArray())
            out?.flush()
            Thread.sleep(100)

            val buffer = ByteArray(1024)
            val bytes = input?.read(buffer)
            buffer.take(bytes ?: 0).toByteArray().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun disconnect() {
        socket?.close()
    }
}
