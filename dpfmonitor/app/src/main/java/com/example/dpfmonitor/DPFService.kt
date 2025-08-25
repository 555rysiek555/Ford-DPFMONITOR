package com.example.dpfmonitor

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.preference.PreferenceManager
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class DPFService : Service() {

    private var socket: BluetoothSocket? = null
    private var inStream: InputStream? = null
    private var outStream: OutputStream? = null
    private var running = false

    private val TAG = "DPFService"
    private val UUID_OBD = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private fun sendAlert(msg: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val debugMode = prefs.getBoolean("debug_mode", false)

        if (debugMode) {
            sendBroadcast(Intent("DPF_ALERT").apply {
                putExtra("MSG", msg)
            })
        }

        Log.d(TAG, msg) // zawsze do logcat
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Thread {
            val prefs = getSharedPreferences("com.example.dpfmonitor_preferences", MODE_PRIVATE)
            val macAddress = prefs.getString("device_mac", null)
            val simulation = prefs.getBoolean("simulation_mode", false)
            sendAlert("Startuję serwis DPF...")
            val isEmulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator")

            if (isEmulator) {
                Log.w(TAG, "Wykryto emulator – wymuszam tryb symulacji")
                runSimulation()}
            else if (simulation || macAddress.equals("SIMULATION", ignoreCase = true)) {
                sendAlert("Tryb symulacji aktywny")
                runSimulation()
            } else {
                sendAlert("Łączę z OBD: $macAddress")
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null) {
                    sendAlert("Brak Bluetooth – przełączam na symulację")
                    runSimulation()
                } else {
                    connectAndRead()
                }
            }
        }.start()
        return START_STICKY
    }

    /** 🔹 Tryb symulacji danych bez Bluetooth */
    private fun runSimulation() {
        Thread {
            running = true
            var soot = 5
            var regen = false

            while (running) {
                if (!regen) {
                    soot += 1
                    if (soot >= 24) regen = true
                } else {
                    soot -= 2
                    if (soot <= 5) regen = false
                }

                val load = (20..80).random()
                val temp = if (regen) (550..650).random() else (250..400).random()

                val intent = Intent("DPF_DATA").apply {
                    putExtra("CONNECTED", true)   // ✅ widoczne jako "Połączono"
                    putExtra("SOOT", "$soot g")
                    putExtra("LOAD", "$load %")
                    putExtra("TEMP", "$temp °C")
                    putExtra("REGEN", regen)
                }
                sendBroadcast(intent)

                Log.d(TAG, "Symulacja -> SOOT=$soot g, LOAD=$load %, TEMP=$temp °C, REGEN=$regen")

                Thread.sleep(2000)
            }
        }.start()
    }
    private fun drainInputStream() {
        Thread.sleep(200)
        try {
            while (inStream?.available() ?: 0 > 0) {
                inStream?.read()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Błąd czyszczenia strumienia: ${e.message}")
        }
    }
    private fun connectAndRead() {
        val prefs = getSharedPreferences("com.example.dpfmonitor_preferences", MODE_PRIVATE)
        val macAddress = prefs.getString("device_mac", null) ?: return

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.e(TAG, "Brak Bluetooth")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Brak uprawnień BLUETOOTH_CONNECT")
                return
            }
        }

        val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)

        try {
            sendAlert("Nawiązywanie połączenia...")
            socket = device.createRfcommSocketToServiceRecord(UUID_OBD)
            adapter.cancelDiscovery()
            socket?.connect()

            sendAlert("Połączono z adapterem")

            inStream = socket?.inputStream
            outStream = socket?.outputStream

            sendAlert("Wysyłam komendy inicjalizujące...")
            sendCommand("ATZ")
            drainInputStream()
            sendCommand("ATE0")
            drainInputStream()
            sendCommand("ATS0")
            drainInputStream()
            sendCommand("ATH0")
            drainInputStream()
            sendCommand("ATL0")
            drainInputStream()
            sendCommand("ATSP0")
            drainInputStream()
            sendCommand("ATSH7E0")
            drainInputStream()

            sendAlert("Oczekiwanie na dane ECU...")

            running = true
            while (running) {
                readDPFData()      // 🔹 teraz odpytuje każdy PID po kolei
                Thread.sleep(2000) // 🔹 odstęp między pełnymi cyklami
            }
        } catch (e: Exception) {
            sendAlert("Błąd połączenia: ${e.message}")
            sendBroadcast(Intent("DPF_DATA").apply {
                putExtra("CONNECTED", false)
            })
        }
    }

    private fun readDPFData() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val pidSoot = prefs.getString("pid_soot", "22F40D") ?: "22F40D"
        val pidLoad = prefs.getString("pid_load", "22F40C") ?: "22F40C"
        val pidTemp = prefs.getString("pid_temp", "22F40E") ?: "22F40E"
        val pidRegen = prefs.getString("pid_regen", "22F40B") ?: "22F40B"

        val pidOrder = listOf(
            "SOOT" to pidSoot,
            "LOAD" to pidLoad,
            "TEMP" to pidTemp,
            "REGEN" to pidRegen
        )

        for ((label, pid) in pidOrder) {
            val raw = sendAndRead(pid)

            val value: Any = when (label) {
                "SOOT" -> parseSoot(raw)
                "LOAD" -> parseLoad(raw)
                "TEMP" -> parseTemp(raw)
                "REGEN" -> parseRegen(raw)
                else -> "---"
            }

            sendAlert("PID $pid ($label) -> $raw  → $value")

            val intent = Intent("DPF_DATA").apply {
                putExtra("CONNECTED", true)
                when (label) {
                    "SOOT" -> putExtra("SOOT", value.toString())
                    "LOAD" -> putExtra("LOAD", value.toString())
                    "TEMP" -> putExtra("TEMP", value.toString())
                    "REGEN" -> putExtra("REGEN", value as Boolean)
                }
            }
            sendBroadcast(intent)

            Thread.sleep(2000) // 🔹 mała przerwa między PID-ami
        }
    }

    private fun sendCommand(cmd: String) {
        try {
            outStream?.write((cmd + "\r").toByteArray())
            outStream?.flush()
            Thread.sleep(80)
        } catch (e: Exception) {
            Log.e(TAG, "Błąd sendCommand: ${e.message}")
        }
    }

    private fun sendAndRead(pid: String): String {
        return try {
            sendCommand(pid)
            Thread.sleep(200)
            val buffer = ByteArray(1024)
            val bytes = inStream?.read(buffer) ?: 0
            val raw = String(buffer, 0, bytes).trim()
            val clean = raw.replace("\r", "").replace(">", "").trim()

            // 🔹 Wyślij do logów w UI (na radiu też zobaczysz w Toast)
            sendBroadcast(Intent("DPF_ALERT").apply {
              //  putExtra("MSG", "PID $pid -> $clean")
            })

            clean
        } catch (e: Exception) {
            "---"
        }
    }

    // ---- PARSERY DANYCH ----
    // SOOT (22F40D) - ilość sadzy w gramach (2 bajty, big-endian)
    private fun parseResponse(resp: String): Map<String, String> {
        val parts = resp.split(" ")
        if (parts.size < 3 || parts[0] != "62" || parts[1] != "F4") return emptyMap()

        val pid = parts[2]
        return when (pid) {
            "0B" -> { // w odpowiedzi ECU, to co my nazwaliśmy SOOT
                val value = parts.getOrNull(3)?.toInt(16) ?: return emptyMap()
                mapOf("SOOT" to "$value g")
            }
            "0C" -> { // ECU daje to jako TEMP
                if (parts.size >= 5) {
                    val high = parts[3].toInt(16)
                    val low = parts[4].toInt(16)
                    val raw = (high shl 8) + low
                    val temp = raw - 40
                    mapOf("TEMP" to "$temp °C")
                } else emptyMap()
            }
            "0D" -> { // ECU daje to jako LOAD
                val raw = parts.getOrNull(3)?.toInt(16) ?: return emptyMap()
                val percent = raw * 100 / 255
                mapOf("LOAD" to "$percent %")
            }
            "0E" -> { // w teorii REGEN, ale u Ciebie brak
                val value = parts.getOrNull(3)?.toInt(16) ?: return emptyMap()
                mapOf("REGEN" to if (value == 1) "true" else "false")
            }
            else -> emptyMap()
        }
    }


    private fun parseSoot(resp: String): String {
        return try {
            val parts = resp.chunked(2) // dzieli string co 2 znaki
            if (parts.size >= 5 && parts[0] == "62" && parts[1] == "F4" && parts[2] == "0D") {
                val high = parts[3].toInt(16)
                val low = parts[4].toInt(16)
                val value = (high shl 8) + low
                "$value g"
            } else "---"
        } catch (e: Exception) {
            "---"
        }
    }

    // LOAD (22F40C) - procent zapełnienia filtra (1 bajt 0-255)
    private fun parseLoad(resp: String): String {
        return try {
            val parts = resp.chunked(2)
            if (parts.size >= 4 && parts[0] == "62" && parts[1] == "F4" && parts[2] == "0C") {
                val raw = parts[3].toInt(16)
                val percent = raw * 100 / 255
                "$percent %"
            } else "---"
        } catch (e: Exception) {
            "---"
        }
    }

    // TEMP (22F40E) - temperatura filtra w °C (2 bajty, big-endian, offset -40)
    private fun parseTemp(resp: String): String {
        return try {
            val parts = resp.chunked(2)
            if (parts.size >= 5 && parts[0] == "62" && parts[1] == "F4" && parts[2] == "0E") {
                val high = parts[3].toInt(16)
                val low = parts[4].toInt(16)
                val raw = (high shl 8) + low
                val temp = raw - 40
                "$temp °C"
            } else "---"
        } catch (e: Exception) {
            "---"
        }
    }

    // REGEN (22F40B) - status regeneracji (1 bajt, 0 = nie, 1 = tak)
    private fun parseRegen(resp: String): Boolean {
        return try {
            val parts = resp.chunked(2)
            parts.size >= 4 && parts[0] == "62" && parts[1] == "F4" && parts[2] == "0B" && parts[3].toInt(16) == 1
        } catch (e: Exception) {
            false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
